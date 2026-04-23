# NEW_APP_RUNBOOK — ship any Vagary Labs Android app in moments' notice

Canonical single-source reference for publishing any new Vagary Labs Android app. Supersedes scattered docs for the "I need to ship X" case.

---

## TL;DR — the complete flow

```bash
# === 1. Scaffold local repo (one command, ~1 min) ===
bash ~/.claude/scripts/bootstrap-vagary-android.sh \
    --app-name "Orbitwatch" \
    --package com.vagarylabs.orbitwatch \
    --dir ~/AndroidStudioProjects/orbitwatch \
    --tagline "Real-time Wi-Fi signal orbit tracker"

# === 2. Everything cloud-side (one command, ~3 min) ===
python3 ~/.claude/scripts/vagary-android-cloud-setup.py \
    --dir ~/AndroidStudioProjects/orbitwatch \
    --app-name "Orbitwatch" \
    --package com.vagarylabs.orbitwatch \
    --first-tester chinu.ramraika@gmail.com

# At this point, the first tester has a Firebase install link in their email.
# Zero browser. Zero Play Console. Testing starts now.

# === 3. Every future release to testers (one command, seconds) ===
cd ~/AndroidStudioProjects/orbitwatch
make ship-testers TESTERS=alice@x.com,bob@y.com

# === 4. OPTIONAL — Public Play Store launch ===
# ⚠️ CRITICAL ORDERING for the one-time Play Console browser work:
#
#   a. In Play Console → Create app. Pick "English (United States)" as
#      default language EXPLICITLY at creation (default depends on your
#      developer-account locale; if en-GB is default, an empty en-GB
#      listing is auto-created that blocks publish).
#
#   b. `make sync-listing` — push all listings, images, contact details
#      WHILE declarations are still incomplete. Once you finish declarations,
#      the API's edit-commit gate tightens and every API edit gets blocked
#      until the first Start-rollout click. So sync FIRST.
#
#   c. `make listings-audit` — confirm only en-US exists and is complete.
#      If a stray locale shows up: `make delete-listing LOCALE=en-GB`.
#
#   d. Fill Play Console declarations (25-30 min) per docs/play/DECLARATIONS.md.
#
#   e. Play Console → Internal testing → Releases → Start rollout. This
#      click + Play's automated "quick checks" (15 min–4 hr) lift the draft-app
#      gate forever. After this, all future API commits work.
#
# Once Play is live:
make ship-internal              # instant — Play internal track
make promote-alpha              # Google review (hours–days)
make promote-beta
make ship-production PCT=0.05   # staged 5% prod rollout
make rollout PCT=0.5            # widen
make rollout PCT=1.0            # full
```

That's it. All future Vagary Labs apps follow this exact pattern.

---

## What the two setup commands actually do

### `bootstrap-vagary-android.sh`
Creates the local repo structure. Fully offline — no network calls except keystore signing material.

| Artifact | Purpose |
|---|---|
| `app/` | Android Studio project with your package name, namespace, keystore wiring |
| `Makefile` | 25+ targets for build, test, release, rollout |
| `scripts/google-play-publisher.py` | Vendored publisher (source of truth: `~/.claude/scripts/`) |
| `scripts/render_brand_assets.py` | Adaptive icon XML → PNGs (phone + tablet + mipmap) |
| `metadata/android/en-US/` | Listing text (title, short/full description, changelogs) |
| `docs/play/{DECLARATIONS,PRIVACY,PLAY_CHECKLIST,AUTOMATION_ENVELOPE,FIREBASE_SETUP,NEW_APP_RUNBOOK}.md` | Full doc set |
| `.github/workflows/release.yml` | Tag-push → Play internal auto-upload |
| `.gitignore` | Pre-configured to block SA JSON + keystore accidents |
| Git repo | `git init` + first commit |
| Keystore | `~/keystores/<slug>.jks` (prompts for passwords if not supplied) |

### `vagary-android-cloud-setup.py`
Does ALL cloud-side API orchestration. Fully idempotent — safe to re-run any time as a drift check / healer.

| Step | Tool | Effect |
|---|---|---|
| 1. Enable GCP APIs | Service Usage API | `androidpublisher`, `firebase`, `firebaseappdistribution`, `admin`, `iam`, `cloudresourcemanager`, `serviceusage` |
| 2. Link Firebase to GCP project | Firebase Management API | `addFirebase` (no-op if already linked) |
| 3. Register Android app | Firebase Management API | Returns `appId` + `projectNumber` |
| 4. Wire Firebase IDs into Makefile | Regex edit | `FIREBASE_APP_ID` / `FIREBASE_PROJECT_NUMBER` as env defaults |
| 5. Create GitHub repo | `gh repo create` | Public repo under `--gh-org` (default: Cramraika) |
| 6. Enable GitHub Pages | `gh api repos/.../pages` | Serves `docs/` at `https://<org>.github.io/<slug>/` |
| 7. Wire 5 GitHub Actions secrets | `gh secret set` via stdin | SA JSON, keystore base64, 3 passwords |
| 8. Render brand assets | `make render-assets` | 14+ PNGs (icon, feature graphic, 4 phone × 2 tablet screenshots, mipmap webps) |
| 9. First Firebase distribution | `publisher distribute` | Tester receives install link immediately |
| 10. Write `FINAL-STEPS.md` | File write | Exact browser steps for Play launch (when needed) |

---

## What's irreducible (the FINAL-STEPS.md tax)

Three things Google won't let any automation do. Only relevant if you want **public Play Store** distribution — pre-release testing via Firebase doesn't need any of this:

1. **Create app in Play Console** (~1 min) — click through name + package fields.
   **CRITICAL**: set **Default language = English (United States)** at creation. If your Play developer account's default is en-GB (or any non-en-US), Play auto-creates an empty default listing in that language, which our `metadata/android/en-US/` source-of-truth pipeline doesn't populate → "default language not populated" blocker at publish time. Fix after the fact: Store presence → Main store listing → Language settings → change Default language to English (United States).
2. **Fill 11 compliance declarations** (~25-30 min) — all answers pre-drafted in `docs/play/DECLARATIONS.md`. The long one is Data Safety.
3. **Paste privacy policy URL + pick category + first "Start rollout" click** (~3 min total) — one-time decisions. The Start Rollout click is the ACTUAL draft-app gate — until that click, not only rollouts but ALL `edits.commit` calls are rejected (including listing updates). So this click is genuinely irreducible.

**Total irreducible browser tax: ~30-35 min per new app, once forever.**

For the 90% case (solo tester / small team / internal utility) — **you can stop at Firebase and skip all Play Console work indefinitely.**

---

## Common operations cheat sheet

```bash
# Ship to testers (preferred for pre-release)
make ship-testers TESTERS=foo@bar.com              # bump + build + distribute + commit + push

# Ship to Play internal (requires declarations done once)
make ship-internal                                  # bump + build AAB + upload + commit + tag + push

# Promote through tracks (after review cycle)
make promote-alpha                                  # internal → alpha (closed testing)
make promote-beta                                   # alpha → beta (open testing)
make ship-production PCT=0.05                       # beta → prod at 5%
make rollout PCT=0.5                                # widen prod rollout
make halt                                           # emergency — stop rollout

# Observability
make status                                         # all tracks
make reviews                                        # user reviews
make version-codes                                  # current vc per track

# Listing / assets
make render-assets                                  # regenerate all PNGs from icon XML + palette
make sync-listing                                   # push metadata → Play (text + images)
make sync-listing-text                              # faster, text-only

# Drift check / heal
make cloud-setup                                    # re-runs cloud-setup idempotently against current state
```

---

## Where things live

### Workstation shared infrastructure
- `~/.config/google-play/sa.json` — shared SA JSON (all apps use this one)
- `~/.claude/scripts/google-play-publisher.py` — publisher source of truth (vendored into each app)
- `~/.claude/scripts/bootstrap-vagary-android.sh` — local scaffold generator
- `~/.claude/scripts/vagary-android-cloud-setup.py` — cloud orchestrator
- `~/keystores/<slug>.jks` — per-app signing key (NEVER share across apps)
- `~/.gradle/gradle.properties` — `<SLUG>_KEYSTORE_*` props for each app

### Per-app repo
- `Makefile` — the command interface
- `scripts/` — vendored publisher + renderer
- `metadata/android/en-US/` — listing source of truth
- `docs/play/` — runbooks
- `FINAL-STEPS.md` — auto-generated "what's still on you" checklist
- `.github/workflows/release.yml` — CI on tag push

### Cloud
- GCP project `vagarylife` — single shared project for all Vagary Labs apps (Firebase, APIs, SA)
- Firebase project `vagarylife` — same project, Firebase-linked — one Firebase app per Android package
- GitHub org `Cramraika` — one public repo per app
- Play Console developer account — one Android app listing per package

---

## Related docs (for when you need specifics)

- **`FIREBASE_SETUP.md`** — Firebase App Distribution detail; tester UX; group management
- **`PLAY_CHECKLIST.md`** — Google's Play Console checklist with ✅/🟡/⚠️ automation tier per item
- **`DECLARATIONS.md`** — the 11 Play Console compliance form answers, pre-filled
- **`PRIVACY.md`** — privacy policy template (hosted via GH Pages)
- **`AUTOMATION_ENVELOPE.md`** — what's API-automatable vs. Google-mandated browser
- **`WORKSPACE_SETUP.md`** — Admin SDK path if you want Google-Group tester automation (requires Workspace domain)
- **`../../RELEASING.md`** — full release lifecycle + emergency playbook

---

## Troubleshooting

| Symptom | Root cause | Fix |
|---|---|---|
| `401 Unauthorized` from publisher | SA not granted in Play Console | Play Console → Users & permissions → invite SA as Admin |
| `404 Package not found` | App not created in Play Console | Follow FINAL-STEPS.md §1 |
| `"Only releases with status draft may be created on draft app"` | First-rollout click not done yet | Play Console → Internal testing → Start rollout (one-time) |
| `"Cannot set tester group on an internal track"` | Expected — use `TRACK=alpha` | Skip internal for tester groups; alpha/beta accept Groups via API |
| Firebase distribute fails with "Firebase project needs to be connected to your Google Play developer account" | AAB upload to Firebase requires Play↔Firebase link | Use APK (the publisher's `distribute` already defaults to APK) |
| `make ship-testers` builds but distribute fails | FIREBASE_APP_ID env missing | `make cloud-setup` to re-wire Makefile with Firebase IDs |
| GitHub Actions release workflow 401s on Play | Secret drift | `make cloud-setup` re-pushes all 5 secrets |

---

## The asymptotic vision

Every new Vagary Labs Android app is released following this runbook. One day:
- Google exposes the Data Safety API publicly → we drop that form from DECLARATIONS.md → irreducible tax drops to ~20 min
- Google exposes content rating API → drops to ~15 min
- Google exposes first-rollout acceptance API → drops to ~5 min

Until then, the ~30 min browser tax per new app is Google's policy-mandated floor. We've automated everything below it.

**Per-release cost after setup: `make ship-testers` (seconds) for every build, forever.**
