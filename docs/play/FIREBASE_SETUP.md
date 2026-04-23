# Firebase App Distribution — 100% API-driven pre-release testing

The real answer to "how do I get builds to testers with zero browser clicks." Firebase App Distribution **bypasses Google Play entirely** for pre-release testing, supports individual-email testers (no Google Group required), has no review delay, and is 100% CLI-driven end-to-end.

---

## Current state for Pulseboard

All set up 2026-04-23:
- ✅ Firebase Management API + App Distribution API enabled on GCP project `vagarylife`
- ✅ Firebase linked to `vagarylife` GCP project
- ✅ Android app `com.vagarylabs.pulseboard` registered with Firebase
- ✅ **App ID**: `1:485446040927:android:4bc85408e5b394b2764aea`
- ✅ **Project number**: `485446040927`
- ✅ Publisher has `distribute` command
- ✅ Makefile has `make distribute TESTERS=<csv>` target
- ✅ First release (v0.1.0-stub, versionCode=1) distributed to `chinu.ramraika@gmail.com`

## Usage

### Ship to one tester (or many)
```bash
make distribute TESTERS=chinu.ramraika@gmail.com
make distribute TESTERS=alice@x.com,bob@y.com,carol@z.com
```

### Ship to a Firebase tester group (for scaling)
```bash
# First, create a group in Firebase (one-time, browser at console.firebase.google.com → App Distribution → Testers and Groups → Add group)
# Then:
make distribute GROUPS=vagary-qa
```

### Combined
```bash
make distribute TESTERS=chinu@x.com GROUPS=vagary-qa,external-beta
```

Release notes are auto-pulled from `metadata/android/en-US/changelogs/<versionCode>.txt` if present.

### What the testers see
1. Receive email: "You've been invited to test Pulseboard"
2. Tap the install link → installs "Firebase App Tester" (Google's official tester app) from Play Store (one-time)
3. In App Tester, tap **Install** next to Pulseboard
4. Updates auto-appear when you `make distribute` new builds

## The architecture this unlocks

```
code push / tag
      │
      ├── make distribute TESTERS=...   # pre-release testing, instant, no review
      │        ↓
      │    (testers install via Firebase App Tester)
      │
      └── make release-internal / make promote-prod    # actual Play Store, with the one-time browser tax
               ↓
           (public install via Play Store)
```

Testing and production are decoupled. Most of your dev cycle lives in the Firebase path; Play Console is touched only for production launches.

---

## For a NEW Vagary Labs Android app (the bootstrap path)

This entire setup per-new-app is ~5 min (versus ~30 min for Play Console alone):

### Step 1 — Bootstrap the app
```bash
bash ~/.claude/scripts/bootstrap-vagary-android.sh \
    --app-name "Orbitwatch" \
    --package com.vagarylabs.orbitwatch \
    --dir ~/AndroidStudioProjects/orbitwatch
```

### Step 2 — Register with Firebase (API-driven, 30 sec)
```bash
# Enable Firebase APIs if new GCP project (already on for vagarylife)
# Then register the app:
python3 ~/.claude/scripts/firebase-register-app.py \
    --project vagarylife \
    --package com.vagarylabs.orbitwatch \
    --display-name "Orbitwatch"
# Prints the app_id — copy into the new app's Makefile
```

(The bootstrap script updates to do this automatically in a future iteration.)

### Step 3 — First distribution
```bash
cd ~/AndroidStudioProjects/orbitwatch
make distribute TESTERS=chinu.ramraika@gmail.com
```

**Total browser clicks per new app for pre-release testing: ZERO.**

Compare to Play Console path: ~30-45 min browser work per app for declarations, privacy, category, etc. before first rollout.

## When to bother with Play Store then?

Only when you're ready for **public** release. Play Store still gets its one-time browser tax per app — but that's now a single event at production-launch time, not blocking every tester build.

## What's genuinely still browser-only in Firebase

Minimal:

1. **Creating Firebase tester groups** (for group-based distribution at scale) — Firebase Console → App Distribution → Testers & Groups → Add group. ~30 sec, one-time per group, reusable across all apps in the project.
2. **AAB uploads require Firebase↔Play linking** — if you ever want to upload AAB instead of APK. Browser step at Firebase Console → Project settings → Integrations → Google Play. We use APK for App Distribution to avoid this.
3. **Viewing detailed crash reports** — Firebase Console (Crashlytics UI). Crashlytics can be initialized via API; the reporting UI is browser.

## Why this is better than Play's internal testing

| | Play Internal | Firebase App Distribution |
|---|---|---|
| Max testers | 100 | No limit (practically thousands) |
| Google review delay | None | None |
| Individual email testers via API | ❌ | ✅ |
| Google Groups via API | ❌ (internal) | ✅ |
| First rollout gate | Draft-app click required | None |
| Per-app setup time | ~30-45 min browser | ~2 min (register app via API) |
| Tester install path | Play Store | Firebase App Tester |
| Supports AAB | ✅ | ⚠️ (needs Firebase↔Play link) |
| Supports APK | ✅ | ✅ |

## What we still use Play Console for

Only these, and only when you want public distribution:

- App Store listing (we sync via `make sync-listing`)
- Production rollout (we drive via `make promote-prod PCT=0.05`)
- Store analytics (views, installs from Play search)
- Reviews from public users (`make reviews`)

For pre-release testing: Firebase App Distribution is the canonical path. Period.
