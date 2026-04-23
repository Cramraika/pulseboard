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
APK      := app/build/outputs/apk/release/app-release.apk
PUBLISH  := python3 $(HOME)/.claude/scripts/google-play-publisher.py
GRADLE   := JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew

# Firebase App Distribution config (bypasses Play Store entirely for pre-release testing)
export FIREBASE_APP_ID ?= 1:485446040927:android:4bc85408e5b394b2764aea
export FIREBASE_PROJECT_NUMBER ?= 485446040927
TESTERS  ?=
GROUPS   ?=

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

release-internal: build-aab  ## Upload AAB → internal testing track (draft → rolled out)
	$(PUBLISH) upload --package $(PACKAGE) --aab $(AAB) --track internal --status draft
	@vc=$$($(PUBLISH) version-codes --package $(PACKAGE) | awk '/^internal:/ {print $$2}' | cut -d, -f1); \
	 mapping=app/build/outputs/mapping/release/mapping.txt; \
	 if [ -f "$$mapping" ]; then \
	     echo "→ uploading R8 mapping.txt for versionCode=$$vc"; \
	     $(PUBLISH) upload-mapping --package $(PACKAGE) --version-code $$vc --mapping "$$mapping"; \
	 fi; \
	 notes_file=metadata/android/en-US/changelogs/$$vc.txt; \
	 notes=$$(test -f $$notes_file && cat $$notes_file || echo "Release v$$vc"); \
	 $(PUBLISH) release --package $(PACKAGE) --version-code $$vc --track internal \
	     --release-name "v$$vc" --status completed --notes-en "$$notes"

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

## ---------- Orchestrators (one-command common flows) ----------

ship-testers: ## Bump versionCode → build APK → Firebase distribute → commit → push (pass TESTERS=csv)
	@test -n "$(TESTERS)" || (echo "Pass TESTERS=<email,email>"; exit 2)
	@echo "→ bumping versionCode"; \
	 vc=$$(grep -E 'versionCode\s*=' app/build.gradle.kts | head -1 | grep -oE '[0-9]+'); \
	 new=$$((vc + 1)); \
	 sed -i.bak "s/versionCode = $$vc/versionCode = $$new/" app/build.gradle.kts && rm app/build.gradle.kts.bak; \
	 echo "Release v$$new" > metadata/android/en-US/changelogs/$$new.txt; \
	 echo "  versionCode $$vc → $$new"
	@$(MAKE) distribute TESTERS="$(TESTERS)"
	@git add app/build.gradle.kts metadata/android/en-US/changelogs/ && \
	 vc=$$(grep -E 'versionCode\s*=' app/build.gradle.kts | head -1 | grep -oE '[0-9]+'); \
	 git commit -m "chore(release): versionCode $$vc — shipped to Firebase testers" -q && \
	 git push origin HEAD

ship-internal: ## Bump versionCode → build AAB → release to Play internal → commit → push
	@vc=$$(grep -E 'versionCode\s*=' app/build.gradle.kts | head -1 | grep -oE '[0-9]+'); \
	 new=$$((vc + 1)); \
	 sed -i.bak "s/versionCode = $$vc/versionCode = $$new/" app/build.gradle.kts && rm app/build.gradle.kts.bak; \
	 echo "Release v$$new" > metadata/android/en-US/changelogs/$$new.txt; \
	 echo "  versionCode $$vc → $$new"
	@$(MAKE) release-internal
	@vc=$$(grep -E 'versionCode\s*=' app/build.gradle.kts | head -1 | grep -oE '[0-9]+'); \
	 git add app/build.gradle.kts metadata/android/en-US/changelogs/ && \
	 git commit -m "chore(release): v$$vc — Play internal" -q && \
	 git tag "v$$vc" && \
	 git push origin HEAD "v$$vc"

ship-production: ## Promote current beta → production at PCT=0.05; commit tag
	@$(MAKE) promote-prod PCT=$(if $(PCT),$(PCT),0.05)
	@vc=$$($(PUBLISH) version-codes --package $(PACKAGE) | awk '/^production:/ {print $$2}' | cut -d, -f1); \
	 git tag "prod-v$$vc" 2>/dev/null; git push origin "prod-v$$vc" 2>/dev/null || true

cloud-setup: ## Re-run cloud orchestration (idempotent heal/drift-check)
	python3 $(HOME)/.claude/scripts/vagary-android-cloud-setup.py \
	    --dir . --app-name "$(shell grep app_name metadata/android/en-US/title.txt 2>/dev/null || echo Pulseboard)" \
	    --package $(PACKAGE) --skip-distribute --skip-assets

## ---------- Firebase App Distribution (pre-release testing, no Play) ----------

distribute: assemble-release  ## Ship APK to Firebase App Distribution testers/groups (no Play Store, no review)
	@vc=$$(grep -E 'versionCode\s*=' app/build.gradle.kts | head -1 | grep -oE '[0-9]+'); \
	 notes_file=metadata/android/en-US/changelogs/$$vc.txt; \
	 notes_arg=$$(test -f $$notes_file && echo "--notes-file $$notes_file" || echo ""); \
	 testers_arg=$$(test -n "$(TESTERS)" && echo "--testers $(TESTERS)" || echo ""); \
	 groups_arg=$$(test -n "$(GROUPS)" && echo "--groups $(GROUPS)" || echo ""); \
	 test -n "$$testers_arg$$groups_arg" || (echo "Pass TESTERS=<csv> and/or GROUPS=<csv>"; exit 1); \
	 $(PUBLISH) distribute --apk $(APK) $$testers_arg $$groups_arg $$notes_arg

distribute-testers: ## Quick: ship APK to TESTERS=<csv> (builds if needed)
	$(MAKE) distribute TESTERS="$(TESTERS)"

distribute-group:  ## Quick: ship APK to Firebase GROUPS=<csv>
	$(MAKE) distribute GROUPS="$(GROUPS)"

## ---------- assets ----------

render-assets:   ## Rasterize brand assets: 512 icon, 1024x500 feature, mipmaps, screenshots
	DYLD_LIBRARY_PATH=/opt/homebrew/lib python3 scripts/render_brand_assets.py

## ---------- housekeeping ----------

clean:           ## gradle clean
	$(GRADLE) clean
