# Troubleshooting — Vagary Labs Android pipeline

Consolidated failure-mode reference. Organized by symptom. Every entry has a root-cause explanation plus the exact fix command.

---

## Auth / access

### `401 Unauthorized` from publisher script
- **Cause**: Service account doesn't have permission for this app, OR API not enabled, OR Play API ToS not accepted.
- **Fix**:
  1. Play Console → Users & permissions → confirm `vagarylife@vagarylife.iam.gserviceaccount.com` is invited with Admin role
  2. `make doctor` — will verify GCP APIs + SA scope
  3. Accept the Play Developer API ToS banner (Play Console → API access)

### `403 Forbidden` on specific endpoint
- **Cause**: SA has base access but not the specific scope (e.g. Admin SDK needs domain-wide delegation).
- **Fix**: See `docs/play/WORKSPACE_SETUP.md` for DWD setup if using Admin SDK.

### `404 Package not found`
- **Cause**: App not created in Play Console yet.
- **Fix**: Follow `FINAL-STEPS.md` §1 — create app in Play Console with the expected package name. After creation, retry.

### `gh auth status` shows not logged in
- **Fix**: `gh auth login` (GitHub CLI)

---

## Draft-app gate (first-rollout)

### `Only releases with status draft may be created on draft app`
- **Cause**: The app is in Play's "draft app" state. Until you click **Start rollout to Internal testing** once in Play Console, ALL `edits.commit` calls fail — not just release rollouts. This includes listing updates, details updates, image uploads.
- **Fix**:
  1. Finish declarations in Play Console → App content (11 forms)
  2. Play Console → Internal testing → Releases → Review release → **Start rollout to Internal testing**
  3. Wait for Play's automated quick checks (15 min to several hours for first-time apps; up to 24h is plausible but unusual)
  4. Once checks pass, draft-app lifts forever and all API edits work

### `Some languages have errors` in Play Console
- **Cause**: A listing (locale) was auto-created when the app was created, based on your Play developer account's default language (e.g. en-GB if UK-based). Our pipeline only populates en-US, leaving the auto-created locale empty.
- **Fix (ideal)**: At app creation, explicitly pick "English (United States)" as default language.
- **Fix (after the fact)**:
  - `make listings-audit` — shows the incomplete locales
  - If draft-app gate has lifted: `make delete-listing LOCALE=en-GB`
  - If gate still active: Play Console → Store presence → Main store listing → Language settings → remove en-GB translation (browser-only)

### Quick checks running >24 hours
- **Cause**: First-time developer account, Play queue backlog, or flagged for manual review.
- **Check**: Play Console → Dashboard — look for specific action-needed items. The UI shows more than the API.
- **Typical**: 15 min to 4 hours. Longer than 24h is worth contacting Play Support.

---

## Publisher / API errors

### `Cannot set tester group on an internal track`
- **Cause**: `edits.testers` rejects Google Groups on internal track — only alpha/beta support group-based testers. Internal uses account-level email lists (no API).
- **Fix**: Use `TRACK=alpha` or `TRACK=beta`. Or manage internal testers via Play Console UI (browser).

### `Firebase project needs to be connected to your Google Play developer account`
- **Cause**: Uploading **AAB** to Firebase App Distribution requires Firebase↔Play linking (browser step).
- **Fix**: Use APK (which the publisher defaults to for `distribute`) — `make distribute TESTERS=foo@bar.com` works without the linking.
- **Or**: Firebase Console → Project settings → Integrations → Google Play → Link (browser-only).

### `"Only releases..."` during `make release-internal`
- See draft-app gate above.

### `versionCode already used`
- **Cause**: Play treats every uploaded versionCode as immutable forever. Re-using a number (even one you thought wasn't uploaded) fails.
- **Fix**: Bump versionCode in `app/build.gradle.kts`. Or use `make ship-internal` which bumps automatically.

### `POST /addFirebase` returns 200 but project.get returns 404
- **Cause**: Long-running operation still in progress.
- **Fix**: Poll the returned `operations/...` name at `firebase.googleapis.com/v1beta1/operations/...` until `done: true`.

---

## Build / keystore

### `Keystore was tampered with, or password was incorrect`
- **Cause**: Gradle properties password doesn't match keystore.
- **Fix**: Check `~/.gradle/gradle.properties` values for `<SLUG>_KEYSTORE_PASSWORD` and `<SLUG>_KEY_PASSWORD`. Verify keystore opens manually: `keytool -list -keystore ~/keystores/<slug>.jks`.

### `Missing ~/.gradle/gradle.properties` keys
- **Fix**:
  ```
  SLUG=yourapp
  cat >> ~/.gradle/gradle.properties <<EOF
  ${SLUG^^}_KEYSTORE_PATH=$HOME/keystores/$SLUG.jks
  ${SLUG^^}_KEYSTORE_PASSWORD=<password>
  ${SLUG^^}_KEY_ALIAS=$SLUG
  ${SLUG^^}_KEY_PASSWORD=<password>
  EOF
  ```
  Then `make cloud-setup` re-wires GitHub Actions secrets from these.

### `Signed APK missing after assembleRelease`
- **Cause**: Release build without signingConfig, or keystore path wrong.
- **Fix**: Check `app/build.gradle.kts` `signingConfigs.release` block resolves `<SLUG>_KEYSTORE_PATH` to an existing file.

---

## Firebase App Distribution

### `No FIREBASE_APP_ID / FIREBASE_PROJECT_NUMBER`
- **Cause**: Cloud-setup wasn't run, or Makefile template had blank placeholders.
- **Fix**: `make cloud-setup` — populates these from the registered Firebase Android app.

### Tester didn't receive install email
- **Cause**: Email in spam; tester wasn't added to project-level testers list; distribution didn't complete.
- **Fix**:
  - Re-run distribute: `make distribute TESTERS=email@foo.com` (idempotent — registers tester + sends email)
  - Check spam / promotions tab
  - Verify tester at `firebase.googleapis.com/v1/projects/<number>/testers` (via publisher `members` or direct API)

### `Invalid binary` from Firebase
- **Cause**: Corrupt APK, unsigned, or wrong signing scheme.
- **Fix**: Rebuild: `make clean && make assemble-release`.

---

## Render / assets

### `cairosvg: no library called libcairo-2 was found`
- **Cause**: `DYLD_LIBRARY_PATH` not set; libcairo is at `/opt/homebrew/lib` on macOS Homebrew.
- **Fix**: `make render-assets` — sets `DYLD_LIBRARY_PATH=/opt/homebrew/lib` automatically. If calling directly: prefix command with that env var.
- **Install if missing**: `brew install cairo`

### Screenshots don't appear in Play listing after sync
- **Cause**: Draft-app gate blocked commit (silent-ish; script prints "listing synced" before commit fails).
- **Check**: `make listings-audit` shows the Play-side state.
- **Fix**: See draft-app gate section above.

---

## CI / GitHub Actions

### Workflow fails at "Decode keystore" step
- **Cause**: `PLAY_KEYSTORE_BASE64` secret missing or corrupted.
- **Fix**: `make cloud-setup` re-pushes all 5 required secrets from local keystore + gradle.properties.

### Workflow fails at publisher upload
- **Cause**: `PLAY_SERVICE_ACCOUNT_JSON` secret missing or drifted.
- **Fix**: `gh secret set PLAY_SERVICE_ACCOUNT_JSON --repo Cramraika/<slug> < ~/.config/google-play/sa.json`

### `Resource not accessible by integration`
- **Cause**: GH Actions token doesn't have permissions for the workflow's operations (Pages, Releases).
- **Fix**: Check `permissions:` block in `.github/workflows/release.yml` — needs `contents: write` for tag pushes / release creation.

---

## Network / infrastructure

### Monitor logs `OTHER_ERROR Remote end closed connection without response`
- **Cause**: Laptop slept or Wi-Fi blipped.
- **Auto-recovers**: monitor retries every 3 min; no action needed. Will resume polling on next iteration.

### `Unable to find the server at oauth2.googleapis.com`
- **Cause**: DNS/network failure.
- **Check**: `ping google.com`. Restart network. Monitor auto-recovers.

---

## Doctor output

### Doctor shows failures after fresh bootstrap
Expected if cloud-setup hasn't run yet. Order of operations:
1. `bash bootstrap-vagary-android.sh --app-name X --package Y --dir Z`
2. `python3 vagary-android-cloud-setup.py --dir Z --app-name X --package Y`
3. `cd Z && make doctor` — should now be all ✓ or benign warnings

### Doctor shows `vendored publisher DRIFTED`
- **Cause**: `~/.claude/scripts/google-play-publisher.py` was updated but the app's `scripts/google-play-publisher.py` wasn't re-synced.
- **Fix**: `cp ~/.claude/scripts/google-play-publisher.py scripts/google-play-publisher.py` — or re-run `make cloud-setup`.

### Doctor shows `no Firebase app for <package>`
- **Cause**: cloud-setup wasn't run, or was run before creating Firebase project.
- **Fix**: `python3 ~/.claude/scripts/vagary-android-cloud-setup.py --dir . --app-name X --package com.vagarylabs.x` (skip-distribute is fine for a healing pass)

---

## Pipeline-specific gotchas

### Pushing listings AFTER declarations are filled
- **Symptom**: `make sync-listing` prints success messages per-locale but final commit fails with draft-app error.
- **Cause**: The draft-app gate tightens once declarations are all marked "complete" in Play Console. Before that, listing edits commit freely; after, they're blocked until Start Rollout is clicked.
- **Correct ordering** (for new apps):
  1. Create app in Play Console
  2. `make sync-listing` (pushes text + 14 images)
  3. `make details --website X --email Y`
  4. Fill all 11 declarations in Play Console
  5. Click Start Rollout to Internal testing (one irreducible click)
  6. From here: 100% API forever

### Make variable collision with shell env (e.g. `$LANG`)
- **Cause**: Shell exports locale as `LANG=en_US.UTF-8`; Make auto-imports env vars, collides with generic Makefile variables.
- **Fix**: Use namespaced / unusual names (`LOCALE`, `PUB_LANG`) — the template does this.

### GCP project name vs. package prefix mismatch (`vagarylife` vs `vagarylabs`)
- **Not a bug**: Intentional hierarchy. `vagarylife.com` is the parent brand; `Vagary Labs` is the OSS sub-brand; `com.vagarylabs.*` is the package namespace. GCP project name has no functional relationship with package prefix — SA authenticates across them.

---

## When all else fails

1. **Run `make doctor`** — tells you exactly what's misconfigured
2. **Check Play Console UI** — the web UI shows more granular state than the API ever does
3. **Paste the exact error into a new conversation** with this runbook loaded — root cause is almost always in here

Updates to this document go alongside each new discovered failure mode. Check `docs/play/PLAY_CHECKLIST.md` and `docs/play/AUTOMATION_ENVELOPE.md` for parallel coverage.
