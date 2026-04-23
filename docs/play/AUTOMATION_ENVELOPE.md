# The 100% Automation Envelope

Honest scope of what can and cannot be automated for Google Play publishing, given Google's own API and ToS constraints. This is the reference — any time someone claims "make it fully automatic," refer here.

## Short version

- **Release lifecycle** (code → APK → upload → track promotion → rollout → halt/resume → reviews): **100% automated.** Zero browser.
- **Store presence** (listing text + all image sizes + contact details): **100% automated.** Zero browser.
- **Testing at scale** (Google Group–based alpha/beta tracks): **100% automated.** Zero browser after Google Group creation.
- **First-time per-app setup** (app creation, compliance declarations, privacy policy URL, category, tags, rating questionnaire, data safety, target audience, first "Start rollout" click): **~30-45 min browser work per app, once forever.** Google forbids API access to these.

**So "100% no-browser" is impossible** — not because our tooling is limited, but because Google deliberately requires human attestation for legal/compliance items. The best any pipeline can achieve is the per-app browser budget above.

---

## What's fully automated

### Release lifecycle
- `make build-aab` — gradle signed AAB
- `make release-internal` — upload + roll out to internal track
- `make promote-alpha` / `promote-beta` / `promote-prod PCT=X` — track transitions
- `make rollout PCT=X TRACK=Y` — staged rollout fractions
- `make halt TRACK=Y` / `make resume TRACK=Y PCT=X` — rollout control
- `make status` / `make version-codes` / `make reviews` — observability
- CI workflow on tag push — fully hands-off

### Store listing
- `make sync-listing` — title, short/full description, icon, feature graphic, phone + 7-inch + 10-inch screenshots, all locales
- `make render-assets` — regenerate all PNGs from adaptive-icon XML + brand palette

### Contact details
- `make details` command — contact email, website, phone, default language via `edits.details`

### Tester management (alpha/beta only)
- `make testers TRACK=alpha ARGS="--set-groups x@googlegroups.com,y@..."` via `edits.testers`
- Google Groups attachment: API-doable
- **Individual email tester additions to a Google Group**: browser at groups.google.com, OR Admin SDK `admin.directory.members` API if on Google Workspace (domain-wide delegation required)

---

## What's browser-only, per Google's deliberate design

Verified 2026-04-22 against `androidpublisher/v3` discovery doc and Google support articles.

### Per-app one-time setup (~30-45 min each)
1. **Create app** — Play Console → Create app (required fields: name, package, language, free/paid, declarations). No API.
2. **Privacy policy URL** — App content → Privacy policy. `edits.details` doesn't accept it; `edits.listings` schema has only title/short/full/video.
3. **App access** — App content → App access. Radio button. No API.
4. **Ads** — App content → Ads. Radio button. No API.
5. **Content rating** — App content → Content rating → IARC questionnaire. No API.
6. **Target audience** — App content → Target audience. No API.
7. **Data safety** — App content → Data safety. `applications.dataSafety` exists in newer Google Play API specs but not in standard `androidpublisher/v3` Python client. Requires raw HTTP + separate token scope even when it does work. Realistically browser-only.
8. **Government / Financial / Health / News / COVID** declarations — App content. Radio buttons. No API.
9. **App category + tags** — Grow → Store settings. No API in `edits.details` or `edits.listings`.
10. **External marketing toggle** — Grow → Store settings. No API. (60 days to propagate.)
11. **First "Start rollout to Internal testing" click** — Internal testing → Releases. API returns 400 "Only releases with status draft may be created on draft app" until this is done once per app. After that, fully automated forever. **Confirmed 2026-04-22**: filling all declarations is necessary but NOT sufficient — the click itself is Google's mandated human-attestation step. No API workaround.
12. **Internal-track individual testers** — Testers tab → Create email list. `edits.testers` explicitly rejects non-Google-Group entries for internal: "Cannot set tester group on an internal track."
13. **Per-track country/region selection** — `edits.countryavailability` is GET-only.

### Ongoing (rare)
- **Accepting Play Console policy updates** — browser once per policy change.
- **Responding to policy violations / appeals** — browser only.

---

## The scalable testing strategy

Given the internal-track API gap, our canonical testing flow for any Vagary Labs app:

### Pattern: skip internal, use alpha
- **Internal track**: optional; use only for solo-sanity tests. Requires browser for email list creation.
- **Alpha (closed testing)**: our actual "early tester" track. Fully API-managed via Google Groups.
- **Beta (open testing)**: public opt-in + Google Groups.
- **Production**: staged rollout.

### One Google Group to rule them all
- Create one group per organization (e.g., `vagarylabs-tester@googlegroups.com`)
- Attach to alpha track of every Vagary Labs app via `make testers TRACK=alpha ARGS="--set-groups vagarylabs-tester@googlegroups.com"`
- Add/remove individual testers via groups.google.com UI (or Admin SDK if Workspace-enabled)
- Same group membership applies to every app, so testers onboard once and receive all Vagary Labs app invites

### For Pulseboard (current state)
- Alpha track has `vagarylabs-tester@googlegroups.com` attached ✅
- Internal track uploaded as draft (v1) — still blocked by first-rollout declarations gate
- **Recommended**: finish one browser pass through Play Console declarations, then skip the "Start rollout to Internal testing" step entirely. Instead `make promote-alpha` — which goes through Google's review (~hours to 7 days) but works end-to-end via API.

---

## Closing the last tester-management gap (Workspace Admin SDK)

Implemented 2026-04-22 via `publisher members` command + `docs/play/WORKSPACE_SETUP.md`.

- ✅ Admin SDK API enabled in GCP project `vagarylife`
- ✅ Publisher has `members --add / --remove / --list` subcommand (Admin SDK Directory API)
- ⏳ Requires one-time browser setup: create Workspace-domain group at `admin.google.com` + grant domain-wide delegation to the SA. See `WORKSPACE_SETUP.md`.
- ⏳ Requires `PLAY_ADMIN_EMAIL` env var set to a Workspace super admin email for impersonation.

After that one-time setup, tester add/remove across every Vagary Labs app is a single CLI call forever. No browser ever again for tester management.

### Why public googlegroups.com groups won't work
Admin SDK `admin.directory.members` only manages groups that live **inside** a Workspace domain. The public `vagarylabs-tester@googlegroups.com` group has no public API for membership. Must be recreated as `testers@vagarylife.com` (or similar) inside the Workspace domain.

---

## The per-new-app bootstrap

Given the envelope above, the bootstrap script (`~/.claude/scripts/bootstrap-vagary-android.sh`) minimizes browser work to:

1. **Create app in Play Console** (1 min)
2. **Transcribe DECLARATIONS.md into forms** (25-30 min; Data safety is the long one)
3. **Paste privacy URL** (30 sec)
4. **Pick category + tags** (1 min)
5. **Attach Google Group to alpha track** via `make testers TRACK=alpha` (this is scripted, but listed as a step for completeness)
6. **Optional: click Start rollout to Internal testing** (skip if only using alpha/beta/prod)

**Total: ~30-35 min per app, once forever.** Every release after that on every track is `make release-internal` / `make promote-*`.

## The forbidden path (for reference)

Browser automation against Play Console (Playwright/Selenium) is **forbidden by Google's Terms of Service** for developer tools and carries a real risk of developer-account suspension. We will not go down this path.

If Google ever exposes declarations/ratings/data-safety via public API — we'll add them to the pipeline the same day.
