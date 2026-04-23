# Google Play Checklist — mapped to Vagary Labs automation

Mirrors Google's exact Play Console checklist (the one shown on the app dashboard). Each item is tagged:

- ✅ **Automated** — one `make` command handles it end-to-end
- 🟡 **Semi-automated** — CLI exists, but Play requires one-time browser setup per app
- ⚠️ **Manual only** — Google doesn't expose this to any API; always browser-only

Legend for commands: `make X` runs in the app repo root; `python3 scripts/google-play-publisher.py X` is the underlying API call if you want raw access.

---

## 1. Start testing now — Internal testing (no Google review)

> Share your app with up to 100 internal testers to identify issues and get early feedback.

| # | Google's step | Tag | Our automation |
|---|---|---|---|
| 1.1 | Create a new release | ✅ | `make build-aab` → `make release-internal` |
| 1.2 | Preview and confirm the release | ✅ | Publisher `release` command with `--status completed` — Play's "preview" is a no-op when release notes come from `metadata/android/en-US/changelogs/<vc>.txt` |
| 1.3 | Select testers | 🟡 | `make testers TRACK=internal --set-groups <csv>` for Google Groups (API-doable). For ad-hoc individual emails (the common solo-dev case): Play Console → Internal testing → Testers tab → Create email list. |
| 1.4 | **Hidden gate**: "draft app" state unlock | ⚠️ | **First rollout ever on a brand-new app** requires the Set-up-your-app declarations (§2 below) to be marked Done in Play Console + clicking **"Start rollout to Internal testing"** once. After that one click, every future release ships via `make release-internal` with no browser. |

**Outcome after 1.1–1.4 done once**: tag-push CI (`git tag v2 && git push --tags`) or local `make release-internal` puts a new build in front of your tester list in ~3 minutes, no review, no browser.

---

## 2. Set up your app — declarations

> Let us know about the content of your app.

All items in this section are completed once per app in Play Console (browser). **Answers for Pulseboard are pre-drafted in `docs/play/DECLARATIONS.md`** — you transcribe. For a new Vagary Labs app, the bootstrap script generates a fresh `DECLARATIONS.md` template with sensible defaults.

| # | Google's step | Tag | Our support |
|---|---|---|---|
| 2.1 | Set privacy policy | ⚠️ | We draft the policy (`docs/play/PRIVACY.md`). Host at `https://<org>.github.io/<repo>/privacy/` via GitHub Pages (free), paste URL into Play Console. API does NOT accept privacy-policy URL changes for first-time setup. |
| 2.2 | App access | ⚠️ | Browser radio button: "All functionality available without special access" in 95% of cases. Documented in `DECLARATIONS.md`. |
| 2.3 | Ads | ⚠️ | Browser radio button. Default No for Vagary Labs apps (ad-free policy). |
| 2.4 | Content rating | ⚠️ | 5-minute Play Console questionnaire. Answers pre-drafted in `DECLARATIONS.md`. Yields an IARC rating (usually PEGI 3 / Everyone for utility apps). |
| 2.5 | Target audience | ⚠️ | Browser form. Default: "13+", "Does not unintentionally appeal to children". |
| 2.6 | Data safety | ⚠️ | 10-15 min browser form — the long one. Answers pre-drafted per app capability. At stub stage: "Collects no user data." At v1.1+: declare the Wi-Fi metadata + user ID + diagnostic uploads honestly. |
| 2.7 | Government apps | ⚠️ | Browser radio: No (unless genuinely government-published). |
| 2.8 | Financial features | ⚠️ | Browser radio: No for Vagary Labs utility apps. |
| 2.9 | Health | ⚠️ | Browser radio: No for Vagary Labs utility apps. |
| 2.10 | (Also) News app | ⚠️ | Browser radio: No. |
| 2.11 | (Also) COVID-19 | ⚠️ | Browser radio: No. |

> Manage how your app is organised and presented.

| # | Google's step | Tag | Our support |
|---|---|---|---|
| 2.12 | Select an app category | ⚠️ | Browser dropdown. "Tools" (primary) for most Vagary Labs utilities; "Productivity" for Pulseboard-class. Tags also browser-only (not API-exposed). |
| 2.13 | Provide contact details | ✅ | `make details` → publisher sets `contactWebsite`/`contactEmail`/`contactPhone`/`defaultLanguage` via `edits.details.update`. |
| 2.14 | Set up your Store Listing | ✅ | `make sync-listing` pushes title, short/full descriptions, icon (512×512), feature graphic (1024×500), **phone screenshots (1080×1920, min 2, max 8)**, **7-inch tablet screenshots (1200×1920, REQUIRED \*)**, **10-inch tablet screenshots (1600×2560, REQUIRED \*)** from `metadata/android/en-US/`. Source of truth: filesystem. Chromebook + Android XR screenshots and promo videos optional. |
| 2.15 | External marketing toggle | ⚠️ | Browser only. Default ON ("Advertise outside Google Play"). Takes up to 60 days to propagate. |

---

## 3. Closed testing — alpha track (Google review required)

> Test your app with a larger group of testers that you control.

| # | Google's step | Tag | Our automation |
|---|---|---|---|
| 3.1 | Complete the initial setup tasks first | ⚠️ | §2 above — same declarations gate. |
| 3.2 | Set up your closed test track | ✅ | Track exists by default on every app; nothing to "set up" beyond attaching a release. |
| 3.3 | Select countries and regions | 🟡 | `edits.tracks.update` supports country lists; currently we don't expose this via the publisher script — default "all countries" applies. Add `--countries` flag if a per-app restriction is needed. |
| 3.4 | Select testers | 🟡 | `make testers TRACK=alpha --set-groups <csv-of-google-groups>` via `edits.testers.update`. **Google Groups only** — individual emails need Play Console UI. |
| 3.5 | Create and roll out a release | ✅ | `make promote-alpha` — pulls current internal versionCode, attaches it to alpha track as `status=completed`, triggers Google review. |
| 3.6 | Preview and confirm | ✅ | Embedded in `promote-alpha`. |
| 3.7 | Send the release to Google for review | ✅ | Automatic side effect of rolling out to alpha/beta/production — Google review starts the moment the release is not a draft. Review timeline: hours to 7 days typical. |

---

## 4. Open testing — beta track (Google review required)

> Let anyone sign up to test your app on Google Play.

| # | Google's step | Tag | Our automation |
|---|---|---|---|
| 4.1 | Complete the initial setup tasks first | ⚠️ | Same declarations gate. |
| 4.2 | Set up your open test track | ✅ | Exists by default. |
| 4.3 | Select countries and regions | 🟡 | Same as 3.3. |
| 4.4 | Create and roll out a release | ✅ | `make promote-beta`. |
| 4.5 | Preview and confirm | ✅ | Embedded. |
| 4.6 | Send for review | ✅ | Automatic. |

---

## 5. Pre-registration

> Build excitement for your app with pre-registration.

We don't use pre-registration for Vagary Labs OSS utilities (free, OSS, no hype-launch cycle). If ever needed: Play Console only — no API coverage. Skip.

---

## Publisher API command reference

| Command | What it does |
|---|---|
| `make status` | Show all tracks' releases |
| `make version-codes` | Current versionCode per track |
| `make reviews` | Recent user reviews |
| `make details ARGS="--get"` _(via publisher)_ | Read contact details + default language |
| `make details ARGS="--email X --website Y --default-language en-US"` | Write contact fields + default locale |
| `make testers TRACK=internal ARGS="--get"` | Show current Google Groups on a track |
| `make testers TRACK=internal ARGS="--set-groups emails@googlegroups.com"` | Set tester Google Groups on a track |
| `make release-internal` | Build AAB → upload draft → roll out to internal |
| `make promote-alpha/beta/prod` | Move current versionCode across tracks |
| `make rollout PCT=0.1 TRACK=production` | Staged rollout fraction |
| `make halt TRACK=production` | HALT current rollout |
| `make resume TRACK=production PCT=0.05` | Resume halted rollout |
| `make sync-listing [LOCALE=en-US]` | Push metadata + all images |
| `make sync-listing-text` | Skip images; faster |
| `make render-assets` | Rasterize icon, feature graphic, phone + tablet screenshots, mipmap webps |

---

## 6. Production release — public on Play

> Publish your app to real users on Google Play by releasing it to your production track.

| # | Google's step | Tag | Our automation |
|---|---|---|---|
| 6.1 | Complete the initial setup tasks first | ⚠️ | Same declarations gate. |
| 6.2 | Select countries and regions | 🟡 | Same as 3.3. |
| 6.3 | Create and roll out a release | ✅ | `make promote-prod PCT=0.05` — promotes current beta to production at 5% staged rollout. |
| 6.4 | Preview and confirm | ✅ | Embedded. |
| 6.5 | Send for review | ✅ | Automatic. |
| 6.6 | Publish your app on Google Play | ✅ | Rolls out after Google approves review. Monitor with `make status`; ramp with `make rollout PCT=0.5` → `make rollout PCT=1.0`. |

---

## Rollout management (not in Google's checklist but critical)

| Operation | Command |
|---|---|
| Show current state across all tracks | `make status` |
| Fetch reviews | `make reviews` |
| Set staged rollout fraction | `make rollout PCT=0.1 TRACK=production` |
| HALT a bad rollout | `make halt TRACK=production` |
| Resume after fix | `make resume TRACK=production PCT=0.05` |
| Rollback | Impossible by design (Play rejects lower versionCodes). Instead: bump versionCode + `make release-internal` + promote up. `make halt` first to stop the bad release from spreading. |

---

## One-time-per-app tax summary

The "draft app" gate is the main friction. For any new Vagary Labs Android app, the irreducible human browser work is:

**Total: ~30-45 min browser time, once forever per app**

1. Click **Create app** — 1 min (fields: app name, language, package name, free/paid, declarations)
2. Fill declarations 2.1–2.13 — ~25 min (Data safety is the long one)
3. Paste privacy policy URL (after hosting via GH Pages) — 2 min
4. Create tester email list — 1 min
5. Click **Start rollout to Internal testing** on the staged draft — 1 min

**After that**: every future release across every track is a single `make promote-*` or a tag push.

---

## For a NEW app (not Pulseboard): the bootstrap

```bash
# One command scaffolds the whole pipeline, pre-wired
bash ~/.claude/scripts/bootstrap-vagary-android.sh \
    --app-name "Orbitwatch" \
    --package com.vagarylabs.orbitwatch \
    --dir ~/AndroidStudioProjects/orbitwatch
```

What it does — see `~/.claude/scripts/bootstrap-vagary-android.sh` header comment for full details. TL;DR: generates Android project with Makefile + metadata/ + CI workflow + DECLARATIONS template pre-filled for Vagary Labs defaults + privacy policy template + adaptive-icon vector + render script. Service account JSON and keystore are reused from the Vagary Labs shared workstation paths.

After bootstrap: you still do the 30-45 min browser tax above. After that, it's every future release automated.
