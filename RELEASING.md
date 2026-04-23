# Releasing Pulseboard to Google Play

Complete lifecycle runbook. For **Google's official checklist mapped to our automation**, see [`docs/play/PLAY_CHECKLIST.md`](docs/play/PLAY_CHECKLIST.md). For **declarations answers**, see [`docs/play/DECLARATIONS.md`](docs/play/DECLARATIONS.md). For **the privacy policy draft**, see [`docs/play/PRIVACY.md`](docs/play/PRIVACY.md).

---

## One-time setup (per workstation)

Already done for this Mac. If starting fresh on a new workstation:

```bash
# 1. Service account JSON at the canonical path
mkdir -p ~/.config/google-play
mv <downloaded-sa>.json ~/.config/google-play/sa.json
chmod 600 ~/.config/google-play/sa.json

# 2. Publisher dependencies
python3 -m pip install --user google-auth google-api-python-client

# 3. System libs for asset rendering
brew install cairo

# 4. Keystore + gradle properties
# (Pulseboard keystore at ~/keystores/pulseboard.jks already; props in ~/.gradle/gradle.properties)

# 5. One-time browser work (Google-side)
#    - Enable Google Play Android Developer API in GCP project `vagarylife`
#    - Play Console → Users and permissions → invite vagarylife@vagarylife.iam.gserviceaccount.com as Admin
#    - Accept Play Developer API ToS
```

## One-time setup (per NEW app)

For any new Vagary Labs Android app (not Pulseboard), use the bootstrap:

```bash
bash ~/.claude/scripts/bootstrap-vagary-android.sh \
    --app-name "Orbitwatch" \
    --package com.vagarylabs.orbitwatch \
    --dir ~/AndroidStudioProjects/orbitwatch \
    --tagline "Your one-liner"
```

The script scaffolds: Android project, Makefile, vendored publisher, CI workflow, metadata/, DECLARATIONS template, PRIVACY template, adaptive icon, keystore (prompts). Then prints the remaining browser steps.

---

## First rollout on a brand-new app (Pulseboard's current state)

This is the **only time you must use Play Console**. After this, every release is automated.

1. **Play Console → Create app**
   - App name, default language, package name (e.g. `com.vagarylabs.pulseboard`), free, accept declarations → Create

2. **Fill declarations** — walk through `docs/play/DECLARATIONS.md` section by section. ~30-45 min browser time. Long parts: data safety, content rating.

3. **Host privacy policy** — enable GitHub Pages on the repo (Settings → Pages → Deploy from a branch → `main` → `/docs`). Policy becomes available at `https://cramraika.github.io/pulseboard/play/PRIVACY` or configure a nicer route. Paste the URL into Play Console → App content → Privacy policy.

4. **Upload first AAB + push listing**
   ```bash
   make sync-listing       # title, short/full, icon, feature graphic, screenshots
   make release-internal   # build + upload (draft) + attach release notes
   ```

5. **Click "Start rollout to Internal testing"** in Play Console → Internal testing → review draft → confirm rollout. This is the click that exits "draft app" state forever.

6. **Create tester email list** — Play Console → Internal testing → Testers tab → Create email list → paste your email(s) → activate. Copy the opt-in URL.

7. **Install on your device** — open opt-in URL in browser logged into tester Google account → "Become a tester" → install from Play Store.

---

## Steady-state release flow (every release after the first)

```bash
# 1. Bump version
# Edit app/build.gradle.kts: versionCode = 2, versionName = "0.2.0"
# Add metadata/android/en-US/changelogs/2.txt with release notes

# 2. Internal testing (no review — instant)
make release-internal

# 3. Check status, ramp up if happy
make status
make promote-alpha   # closed testing — triggers Google review
make promote-beta    # open testing — triggers Google review
make promote-prod PCT=0.05   # production at 5% staged

# 4. Ramp production
make rollout PCT=0.2     # widen to 20%
make rollout PCT=0.5     # 50%
make rollout PCT=1.0     # full (auto-transitions to status=completed)

# 5. If rollout goes bad
make halt                          # freeze propagation
# fix the issue, bump versionCode, build, promote a new build
# (Play has no native rollback — higher versionCode of the good build is the answer)
```

## CI-driven release

```bash
git tag v0.2.0
git push origin v0.2.0
# → .github/workflows/release.yml runs, uploads AAB to internal track
```

The CI workflow also supports manual dispatch with a track picker:

```bash
gh workflow run "Play Store Release" --ref main -f track=alpha
```

CI never auto-promotes to production. Production is always a deliberate human `make promote-prod` on your laptop.

---

## Commands cheat sheet

All targets run from repo root. Run `make help` to see them in the terminal.

```
test                    Run :core unit tests
lint                    Gradle lint
build-aab               Signed AAB
assemble-release        Signed APK (side-load distribution)
release-internal        Upload + roll out to internal track
promote-alpha           Internal → closed testing
promote-beta            Alpha → open testing
promote-prod PCT=0.05   Beta → production at 5% rollout
rollout PCT=0.2         Widen staged rollout
halt                    HALT current rollout (safe; freezes propagation)
resume PCT=0.05         Resume a halted rollout
status                  Show all tracks' releases
version-codes           Print current versionCode per track
reviews                 Fetch recent reviews
sync-listing            Push metadata/android/ → Play (all locales, images)
sync-listing-text       Text-only sync (fast; skips image upload)
render-assets           Rasterize brand assets (icon, feature graphic, screenshots, mipmaps)
```

Variables:
- `TRACK` — internal / alpha / beta / production (default depends on target)
- `PCT` — staged rollout fraction, 0.0 < x ≤ 1.0 (1.0 auto-completes)
- `LOCALE` — single-locale sync-listing target, e.g. `LOCALE=en-US`

---

## Emergency playbook

**Bad production rollout — users reporting crashes**
```bash
make halt TRACK=production   # immediate — stops propagation to more users
# fix the bug on a branch, bump versionCode, merge to main, then:
make release-internal
make promote-alpha
make promote-beta
make promote-prod PCT=0.05   # fresh staged rollout of the good build
```

**401 / 403 errors from publisher**
SA isn't granted in Play Console → Users and permissions, or API isn't enabled in GCP, or ToS not accepted. See `docs/play/PLAY_CHECKLIST.md` §Prereqs.

**404 Package not found**
App not yet created in Play Console → Create app first.

**"Only releases with status draft may be created on draft app"**
First rollout gate — see §First rollout on a brand-new app above. You need to complete declarations + click "Start rollout" once in Play Console.

**`make release-internal` says "versionCode already used"**
Bump `versionCode` in `app/build.gradle.kts`. Play treats every uploaded versionCode as immutable forever.

**"No testers specified" warning on internal track**
Internal testing doesn't accept Google Groups via API. Two fixes:
- Browser: Play Console → Internal testing → Testers → Create email list → add emails. One-time.
- API-first: Skip internal entirely. `make promote-alpha` — alpha track has Google Groups attached via API. Goes through Google review (hours–days) but every future release is zero-browser.

**"No deobfuscation file associated" warning**
Benign when `isMinifyEnabled = false` (current stub has no obfuscation → nothing to deobfuscate). When v1.1 enables R8, the `make release-internal` target now auto-uploads `app/build/outputs/mapping/release/mapping.txt` via `publisher upload-mapping` when it exists. No manual action needed.

**"Only releases with status draft may be created on draft app" (after declarations done)**
Confirmed 2026-04-22: filling declarations is necessary but NOT sufficient. The **"Start rollout to Internal testing"** click in Play Console is Google's mandated human-attestation step that exits draft-app state. One click per app, forever. After that, every track works via API.

**CI workflow fails at "Decode keystore"**
`PLAY_KEYSTORE_BASE64` secret missing or wrong. Regenerate:
```bash
base64 -i ~/keystores/pulseboard.jks | gh secret set PLAY_KEYSTORE_BASE64 --repo Cramraika/pulseboard
```

---

## Things Play does NOT expose to any API (so the pipeline can't help)

From [`docs/play/PLAY_CHECKLIST.md`](docs/play/PLAY_CHECKLIST.md) items marked ⚠️:

- Creating the app for the first time
- Any of the 11 app content declarations (privacy policy URL, ads, content rating, data safety, target audience, etc.)
- App category selection
- Contact details
- Creating tester email lists
- Pre-registration campaigns
- Accepting Play Console policy updates

These are one-time per app (except policy updates). After first-time completion, your whole release lifecycle is CLI/CI driven.
