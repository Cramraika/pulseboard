# Pulseboard — Play Store release Makefile
#
# All Play Console operations routed through ~/.claude/scripts/google-play-publisher.py.
# Never open Play Console in a browser for routine ops — use these targets.
#
# Prereqs (one-time):
#   1. ~/.config/google-play/sa.json exists (service account JSON, chmod 600)
#   2. ~/.gradle/gradle.properties has PULSEBOARD_KEYSTORE_* props (see CLAUDE.md)
#   3. Google Cloud project has "Google Play Android Developer API" enabled
#   4. Play Console → Users & permissions → service-account email has Admin access
#   5. Play Developer API ToS accepted (one-time banner in Play Console)

PACKAGE  ?= com.vagarylabs.pulseboard
TRACK    ?= internal
PCT      ?= 1.0
LOCALE   ?=
AAB      := app/build/outputs/bundle/release/app-release.aab
PUBLISH  := python3 $(HOME)/.claude/scripts/google-play-publisher.py
GRADLE   := JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew

.PHONY: help test lint build-aab assemble-release release-internal promote-alpha \
        promote-beta promote-prod rollout halt resume status version-codes \
        sync-listing sync-listing-text reviews clean

help:
	@awk 'BEGIN{FS=":.*##"; printf "Pulseboard Play Store targets\n"} \
	     /^[a-zA-Z_-]+:.*##/ {printf "  %-22s %s\n", $$1, $$2}' $(MAKEFILE_LIST)

## ---------- build ----------

test:            ## Run :core unit tests (52 tests)
	$(GRADLE) :core:testDebugUnitTest

lint:            ## Gradle lint on :app
	$(GRADLE) :app:lint

build-aab:       ## Signed Android App Bundle — upload-ready
	$(GRADLE) :app:bundleRelease
	@echo "→ $(AAB)"

assemble-release: ## Signed APK — side-load distribution (GitHub Releases)
	$(GRADLE) :app:assembleRelease

## ---------- upload to Play ----------

release-internal: build-aab  ## Upload AAB → internal testing track
	$(PUBLISH) upload --package $(PACKAGE) --aab $(AAB) --track internal

promote-alpha:   ## Promote current internal versionCode → closed testing (alpha)
	@vc=$$($(PUBLISH) version-codes --package $(PACKAGE) | awk '/^internal:/ {print $$2}' | cut -d, -f1); \
	 test -n "$$vc" || (echo "no internal versionCode found"; exit 1); \
	 $(PUBLISH) release --package $(PACKAGE) --version-code $$vc --track alpha \
	     --release-name "alpha-$$vc" --status completed

promote-beta:    ## Promote current alpha → open testing (beta)
	@vc=$$($(PUBLISH) version-codes --package $(PACKAGE) | awk '/^alpha:/ {print $$2}' | cut -d, -f1); \
	 test -n "$$vc" || (echo "no alpha versionCode found"; exit 1); \
	 $(PUBLISH) release --package $(PACKAGE) --version-code $$vc --track beta \
	     --release-name "beta-$$vc" --status completed

promote-prod:    ## Promote current beta → production at PCT=0.05 (5%) by default
	@vc=$$($(PUBLISH) version-codes --package $(PACKAGE) | awk '/^beta:/ {print $$2}' | cut -d, -f1); \
	 test -n "$$vc" || (echo "no beta versionCode found"; exit 1); \
	 $(PUBLISH) release --package $(PACKAGE) --version-code $$vc --track production \
	     --release-name "v$$vc" --status draft; \
	 $(PUBLISH) rollout --package $(PACKAGE) --track production --fraction $(PCT)

## ---------- rollout control ----------

rollout:         ## Set staged rollout % (e.g. make rollout PCT=0.1)
	$(PUBLISH) rollout --package $(PACKAGE) --track $(TRACK) --fraction $(PCT)

halt:            ## HALT current rollout on TRACK (default production)
	$(PUBLISH) halt --package $(PACKAGE) --track $(if $(TRACK),$(TRACK),production)

resume:          ## Resume a halted rollout at PCT (default 0.05)
	$(PUBLISH) resume --package $(PACKAGE) --track $(if $(TRACK),$(TRACK),production) --fraction $(PCT)

## ---------- observability ----------

status:          ## Show current releases across all tracks
	$(PUBLISH) status --package $(PACKAGE)

version-codes:   ## Print active versionCode per track
	$(PUBLISH) version-codes --package $(PACKAGE)

reviews:         ## Fetch recent reviews
	$(PUBLISH) reviews --package $(PACKAGE)

## ---------- store listing ----------

sync-listing:    ## Push metadata/android/ → Play (all locales, includes images)
	$(PUBLISH) sync-listing --package $(PACKAGE) --dir metadata/android \
	    $(if $(LOCALE),--lang $(LOCALE),)

sync-listing-text: ## Text-only sync (fast; skips image upload)
	$(PUBLISH) sync-listing --package $(PACKAGE) --dir metadata/android --skip-images \
	    $(if $(LOCALE),--lang $(LOCALE),)

## ---------- housekeeping ----------

clean:           ## gradle clean
	$(GRADLE) clean
