# Pulseboard — Play Console Declarations (completed 2026-04-22)

Record of what was submitted for `com.vagarylabs.pulseboard` during first-rollout setup. Kept as a source of truth so future edits and audits have a trail.

This document is also the **template** for any new Vagary Labs Android app — the bootstrap script copies it forward with placeholders. For Pulseboard specifically, all items below are **completed** in Play Console.

---

## 1. Privacy policy ✅
- **URL**: https://cramraika.github.io/pulseboard/play/PRIVACY.html _(GH Pages, HTTP 200)_
- Source: [`docs/play/PRIVACY.md`](PRIVACY.md)
- Covers: what we collect, what we don't, permissions and why, data sharing policy, user rights, open-source attestation, contact

## 2. App access ✅
- **"All functionality available without special access"** — Yes

## 3. Ads ✅
- **Contains ads**: No _(Vagary Labs policy: ad-free)_

## 4. Content rating ✅
- **Contact email for rating authorities (IARC)**: contact@vagarylife.com
- **Category**: All other app types (utility/productivity)
- **Terms & conditions**: Agreed to IARC Terms of Use
- **Questionnaire answers** (all No):
  - Downloaded app — Does the app contain any ratings-relevant content downloaded as part of the app package? → **No**
  - User content sharing — Does the app natively allow users to interact/exchange content? → **No**
  - Online content — Does the app feature/promote content accessed from the app? → **No**
  - Promotion or sale of age-restricted products → **No**
  - Miscellaneous:
    - Shares user's current and precise physical location with other users → **No**
    - Allows users to purchase digital goods → **No**
    - Cash rewards / gift cards / play-to-earn / convertible crypto / NFTs → **No**
    - Web browser or search engine → **No**
    - Primarily a news or educational product → **No**
- **Resulting rating**: PEGI 3 / Everyone _(auto-assigned)_

## 5. Target audience and content ✅
- **Target age group**: 13+
- **Does the app unintentionally appeal to children?**: No

## 6. Data safety ✅
For v0.1.0-stub: **Collects no user data.** Honest for the current stub (no engine wired).

When v1.1 ships, this must be revised to declare:
- **Personal info** → User IDs (hashed email recommended) — collected, shared with user-configured Sheets endpoint, not sold, encrypted in transit, collection optional (self-provided), user can request deletion by contacting maintainer
- **Device or other IDs** → same handling
- **App info and performance** → Diagnostics (network metrics) — collected, shared with user-configured endpoint, not sold, encrypted in transit

## 7. Government apps ✅
- **Published by a government agency?**: No

## 8. Financial features ✅
- **App features**: **"My app doesn't provide any financial features"** (selected the none-of-the-above option from the full list: Banking/loans, Payments/transfers, Purchase agreements, Trading/funds, Support services, Other)

## 9. Health ✅
- **Includes health features?**: No

## 10. News apps ✅
- **Is this a news app?**: No

## 11. COVID-19 contact tracing ✅
- **Is this a COVID-19 app?**: No

---

## 12. Main store listing ✅
All fields synced from filesystem via `make sync-listing`. Source of truth: [`metadata/android/en-US/`](../../metadata/android/en-US/).

- **App name**: Pulseboard (10 chars / 30 max)
- **Short description**: Install-and-forget network health vitals for distributed teams. (63 chars / 80 max)
- **Full description**: 1392 chars / 4000 max (see `full_description.txt`)
- **App icon**: 512×512 PNG — cyan pulse-wave mark
- **Feature graphic**: 1024×500 PNG — dark banner with icon + wordmark + tagline
- **Phone screenshots**: 4 at 1080×1920 — splash, measures, your-sheet, fork-rebrand
- **7-inch tablet screenshots**: 4 at 1200×1920 _(REQUIRED — Play marks these with *)_
- **10-inch tablet screenshots**: 4 at 1600×2560 _(REQUIRED — Play marks these with *)_
- **Video**: not used
- **Chromebook / Android XR screenshots**: optional, not used

## 13. Store settings → App category ⚠️ (browser-only — API doesn't expose)
- **App or game**: App
- **Category**: Tools
- **Tags**: _(fill from Play Console's tag picker — Utilities, Productivity, Network, Wi-Fi, Diagnostics are good candidates)_

## 14. Store settings → Contact details ✅ (API-set 2026-04-22)
- **Email**: contact@vagarylife.com
- **Phone number**: _(blank)_
- **Website**: https://cramraika.github.io/pulseboard/
- **Default language**: en-US

## 15. External marketing ⚠️ (browser-only)
- **Advertise my app outside Google Play**: leave ON _(default)_

## 16. Countries and regions ⚠️ (browser-only — set on each track's release UI)
- **Availability**: All countries _(recommended for OSS utility)_

---

## 17. Internal testing rollout ⏳ (one-time click)

After all the above are marked "Completed" on the Play Console dashboard:

1. **Internal testing** → **Releases** tab → see the draft **"v1"**
2. Click **Review release** → **Start rollout to Internal testing**
3. This click exits "draft app" state forever; all future releases are fully API-driven

## 18. Internal testers ⚠️ (individual emails = browser only)

- **Play Dev API** supports Google Groups (`make testers --track internal --set-groups foo@googlegroups.com`) but not individual emails
- For a small solo/team case: Play Console → **Internal testing** → **Testers** tab → **Create email list** → paste emails → activate → copy opt-in URL

---

## For new Vagary Labs apps

Use this document as a starting template. The bootstrap script scaffolds a fresh `DECLARATIONS.md` per app with Vagary Labs defaults (ads: No, health: No, category: Tools, ad-free policy). Edit per your app's actual behavior before transcribing into Play Console.
