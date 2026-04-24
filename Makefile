# Pulseboard — Play Store release Makefile
#
# All Play Console operations routed through ~/.claude/scripts/google-play-publisher.py.
# Never open Play Console in a browser for routine ops — use these targets.
#
# First-time prereqs per app (see docs/play/NEW_APP_RUNBOOK.md for full flow):
#   1. ~/.config/google-play/sa.json exists (shared Vagary Labs SA JSON, chmod 600)
#   2. ~/.gradle/gradle.properties has PULSEBOARD_KEYSTORE_* props
#   3. Cloud setup done: python3 ~/.claude/scripts/vagary-android-cloud-setup.py
#      (enables GCP APIs, links Firebase, registers Android app, wires GH secrets, etc.)
#   4. Play Console app created + declarations filled (~30 min, one-time)
#   5. First "Start rollout to Internal testing" click in Play Console (one-time)

PACKAGE  ?= com.vagarylabs.pulseboard
TRACK    ?= internal
PCT      ?= 1.0
LOCALE   ?=
AAB      := app/build/outputs/bundle/release/app-release.aab
APK      := app/build/outputs/apk/release/app-release.apk
PUBLISH  := python3 $(HOME)/.claude/scripts/google-play-publisher.py
DOCTOR   := python3 $(HOME)/.claude/scripts/vagary-android-doctor.py
GRADLE   := JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew

# Firebase App Distribution config (written by cloud-setup; leave as placeholders until then)
export FIREBASE_APP_ID ?= 1:485446040927:android:4bc85408e5b394b2764aea
export FIREBASE_PROJECT_NUMBER ?= 485446040927
TESTERS  ?=
GROUPS   ?=

.PHONY: help test lint build-aab assemble-release release-internal promote-alpha \
        promote-beta promote-prod ship-testers ship-internal ship-production \
        release-cascade rollout halt resume status version-codes reviews \
        sync-listing sync-listing-text listings-audit delete-listing \
        distribute distribute-testers distribute-group render-assets \
        doctor cloud-setup clean

help:
	@awk 'BEGIN{FS=":.*##"; printf "Pulseboard Play Store targets\n"} \
	     /^[a-zA-Z_-]+:.*##/ {printf "  %-22s %s\n", $$1, $$2}' $(MAKEFILE_LIST)

## ---------- build ----------

test:            ## Run unit tests
	$(GRADLE) :app:testDebugUnitTest

lint:            ## Gradle lint
	$(GRADLE) :app:lint

build-aab:       ## Signed AAB — Play Store upload format
	$(GRADLE) :app:bundleRelease
	@echo "→ $(AAB)"

assemble-release: ## Signed APK — side-load / Firebase distribution
	$(GRADLE) :app:assembleRelease

## ---------- ship to Play Store tracks ----------

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

promote-alpha:   ## Promote current internal → closed testing (triggers Google review)
	@vc=$$($(PUBLISH) version-codes --package $(PACKAGE) | awk '/^internal:/ {print $$2}' | cut -d, -f1); \
	 test -n "$$vc" || (echo "no internal versionCode found"; exit 1); \
	 $(PUBLISH) release --package $(PACKAGE) --version-code $$vc --track alpha \
	     --release-name "alpha-$$vc" --status completed

promote-beta:    ## Promote current alpha → open testing (triggers Google review)
	@vc=$$($(PUBLISH) version-codes --package $(PACKAGE) | awk '/^alpha:/ {print $$2}' | cut -d, -f1); \
	 test -n "$$vc" || (echo "no alpha versionCode found"; exit 1); \
	 $(PUBLISH) release --package $(PACKAGE) --version-code $$vc --track beta \
	     --release-name "beta-$$vc" --status completed

promote-prod:    ## Promote current beta → production at PCT=0.05 (5% staged)
	@vc=$$($(PUBLISH) version-codes --package $(PACKAGE) | awk '/^beta:/ {print $$2}' | cut -d, -f1); \
	 test -n "$$vc" || (echo "no beta versionCode found"; exit 1); \
	 $(PUBLISH) release --package $(PACKAGE) --version-code $$vc --track production \
	     --release-name "v$$vc" --status draft; \
	 $(PUBLISH) rollout --package $(PACKAGE) --track production --fraction $(PCT)

## ---------- orchestrators (bump + build + ship + commit in one) ----------

ship-testers: ## Bump versionCode → build APK → Firebase distribute → commit → push (pass TESTERS=csv)
	@test -n "$(TESTERS)" || (echo "Pass TESTERS=<email,email>"; exit 2)
	@python3 $(HOME)/.claude/scripts/vagary-android-bump-version.py --file app/build.gradle.kts --seed-changelog metadata/android/en-US/changelogs/
	@$(MAKE) distribute TESTERS="$(TESTERS)"
	@vc=$$(grep -E 'versionCode\s*=' app/build.gradle.kts | head -1 | grep -oE '[0-9]+'); \
	 git add app/build.gradle.kts metadata/android/en-US/changelogs/ && \
	 git commit -m "chore(release): versionCode $$vc — shipped to Firebase testers" -q && \
	 git push origin HEAD

ship-internal: ## Bump versionCode → build AAB → release to Play internal → tag → push
	@python3 $(HOME)/.claude/scripts/vagary-android-bump-version.py --file app/build.gradle.kts --seed-changelog metadata/android/en-US/changelogs/
	@$(MAKE) release-internal
	@vc=$$(grep -E 'versionCode\s*=' app/build.gradle.kts | head -1 | grep -oE '[0-9]+'); \
	 git add app/build.gradle.kts metadata/android/en-US/changelogs/ && \
	 git commit -m "chore(release): v$$vc — Play internal" -q && \
	 git tag "v$$vc" && \
	 git push origin HEAD "v$$vc"

ship-production: ## Promote current beta → production at PCT=0.05; tag + push
	@$(MAKE) promote-prod PCT=$(if $(PCT),$(PCT),0.05)
	@vc=$$($(PUBLISH) version-codes --package $(PACKAGE) | awk '/^production:/ {print $$2}' | cut -d, -f1); \
	 git tag "prod-v$$vc" 2>/dev/null; git push origin "prod-v$$vc" 2>/dev/null || true

release-cascade: ## Walk through internal → alpha → beta → prod with confirmation between stages
	@python3 $(HOME)/.claude/scripts/vagary-android-cascade.py --package $(PACKAGE) --publisher "$(PUBLISH)"

## ---------- rollout control ----------

rollout:         ## Set staged rollout % on TRACK (e.g. make rollout PCT=0.1 TRACK=production)
	$(PUBLISH) rollout --package $(PACKAGE) --track $(TRACK) --fraction $(PCT)

halt:            ## HALT current rollout on TRACK (default production)
	$(PUBLISH) halt --package $(PACKAGE) --track $(if $(TRACK),$(TRACK),production)

resume:          ## Resume halted rollout at PCT (default 0.05)
	$(PUBLISH) resume --package $(PACKAGE) --track $(if $(TRACK),$(TRACK),production) --fraction $(PCT)

## ---------- observability ----------

status:          ## Show current releases across all tracks
	$(PUBLISH) status --package $(PACKAGE)

version-codes:   ## Print active versionCode per track
	$(PUBLISH) version-codes --package $(PACKAGE)

reviews:         ## Fetch recent reviews
	$(PUBLISH) reviews --package $(PACKAGE)

doctor:          ## Comprehensive health check (workstation + repo + cloud)
	$(DOCTOR) --dir .

doctor-strict:   ## Health check; warnings count as failures (CI mode)
	$(DOCTOR) --dir . --strict

## ---------- store listing ----------

listings-audit:  ## Audit every Play-side listing for completeness
	$(PUBLISH) listings-audit --package $(PACKAGE)

delete-listing:  ## Delete a non-default locale's listing (pass LOCALE=en-GB); requires gate lifted
	@test -n "$(LOCALE)" || (echo "Pass LOCALE=<en-GB|...>"; exit 2)
	$(PUBLISH) delete-listing --package $(PACKAGE) --lang $(LOCALE)

sync-listing:    ## Push metadata/android/ → Play (all locales, includes images)
	$(PUBLISH) sync-listing --package $(PACKAGE) --dir metadata/android \
	    $(if $(LOCALE),--lang $(LOCALE),)

sync-listing-text: ## Text-only sync (fast; skips image upload)
	$(PUBLISH) sync-listing --package $(PACKAGE) --dir metadata/android --skip-images \
	    $(if $(LOCALE),--lang $(LOCALE),)

## ---------- Firebase App Distribution (pre-release testing, no Play) ----------

distribute: assemble-release  ## Ship APK to Firebase testers/groups (bypasses Play, no review)
	@vc=$$(grep -E 'versionCode\s*=' app/build.gradle.kts | head -1 | grep -oE '[0-9]+'); \
	 notes_file=metadata/android/en-US/changelogs/$$vc.txt; \
	 notes_arg=$$(test -f $$notes_file && echo "--notes-file $$notes_file" || echo ""); \
	 testers_arg=$$(test -n "$(TESTERS)" && echo "--testers $(TESTERS)" || echo ""); \
	 groups_arg=$$(test -n "$(GROUPS)" && echo "--groups $(GROUPS)" || echo ""); \
	 test -n "$$testers_arg$$groups_arg" || (echo "Pass TESTERS=<csv> and/or GROUPS=<csv>"; exit 1); \
	 $(PUBLISH) distribute --apk $(APK) $$testers_arg $$groups_arg $$notes_arg

distribute-testers: ## Quick: ship APK to TESTERS=<csv>
	$(MAKE) distribute TESTERS="$(TESTERS)"

distribute-group:  ## Quick: ship APK to Firebase GROUPS=<csv>
	$(MAKE) distribute GROUPS="$(GROUPS)"

## ---------- assets ----------

render-assets:   ## Rasterize brand assets (icon, feature graphic, phone + tablet screenshots, mipmaps)
	DYLD_LIBRARY_PATH=/opt/homebrew/lib python3 scripts/render_brand_assets.py

## ---------- maintenance ----------

cloud-setup:     ## Re-run cloud orchestration (idempotent heal / drift-check)
	python3 $(HOME)/.claude/scripts/vagary-android-cloud-setup.py \
	    --dir . --app-name "Pulseboard" --package $(PACKAGE) \
	    --skip-distribute --skip-assets

clean:           ## gradle clean
	$(GRADLE) clean
