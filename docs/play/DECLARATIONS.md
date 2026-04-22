# Play Console Declarations — first-rollout checklist

One-time form work Play requires before any track release (including internal) can transition from draft → rolled-out. After this is done once, every future `make release-internal` publishes end-to-end with no browser involvement.

Answers drafted against Pulseboard v0.1.0-stub (`com.vagarylabs.pulseboard`). Minimum required fields only; fields marked ⓘ are strongly recommended but deferrable.

---

## 1. Privacy policy
**Required IF** the app collects any user data or asks for location/camera/mic/contacts permissions. Pulseboard's stub doesn't yet — but v1.1 will (location for Wi-Fi BSSID, device identifiers, network metrics). URL already hosted.

- **Privacy policy URL**: https://cramraika.github.io/pulseboard/play/PRIVACY.html _(live; paste this into Play Console → App content → Privacy policy)_

## 2. App access
- **Is all or part of your app restricted based on login credentials, memberships, location, or other restrictions?** → **No, all functionality is available without special access**

## 3. Ads
- **Does your app contain ads?** → **No**

## 4. Content rating
Open the questionnaire. For Pulseboard stub, the answers are all "No" except:
- Category: **Utility, Productivity, Communication, or Other**
- Violence → **No**
- Sexuality → **No**
- Profanity → **No**
- Controlled substance → **No**
- Gambling → **No**
- User-generated content → **No**
- Shares user's location with other users → **No**
- Digital purchases → **No**

Result: likely **Everyone / PEGI 3**.

## 5. Target audience and content
- **Target age groups**: **13+** (Pulseboard is for employees/professionals; not designed for children)
- **Does your app unintentionally appeal to children?** → **No**
- **Does your app include features that specifically target children?** → **No**

## 6. Data safety
This is the long one. Minimum honest answers for v0.1.0-stub (currently collects nothing):

- **Does your app collect or share any of the required user data types?** → **No** (accurate for the stub; update when v1.1 adds the engine)

When v1.1 ships, revise to **Yes** and declare:
- **Personal info** → User IDs (the user-configured identifier, recommended as email hash) — collected, shared with Google Sheets endpoint, not sold, encrypted in transit, user can request deletion by contacting developer, collection optional (self-provided)
- **Device or other IDs** → for distinguishing samples, same handling as above
- **App info and performance** → Diagnostics (network metrics) — collected, shared with user-configured Sheets endpoint, not sold, encrypted in transit

## 7. Government apps
- **Is this app published by a government agency?** → **No**

## 8. Financial features
- **Does this app include financial features?** → **No**

## 9. Health
- **Does this app include health features?** → **No**

## 10. News apps
- **Is this app a news app?** → **No**

## 11. COVID-19 contact tracing and status apps
- **Is this a publicly available COVID-19 app?** → **No**

## 12. Main store listing
Already pushed via `make sync-listing` — title, short description, full description live. **Still needed from you:**
- **App icon** (512×512 PNG) — see `metadata/android/en-US/images/` and `docs/play/ASSETS.md` for the render plan
- **Feature graphic** (1024×500 PNG)
- **Phone screenshots** (minimum 2, maximum 8; 16:9 or 9:16) — blocked on having a real UI; see `docs/play/ASSETS.md` for the placeholder-screenshot workaround

## 13. App category and tags
- **App or game**: App
- **Category**: **Tools** (primary) / Productivity (if they offer it as secondary)
- **Tags**: network, monitoring, wifi, diagnostics

## 14. Store settings → Contact details
_Set via API 2026-04-22 — already live. Listed here for completeness._
- **Website**: https://cramraika.github.io/pulseboard/
- **Email**: contact@vagarylife.com
- **Default language**: en-US
- **Phone** (optional): blank

## 15. Countries and regions
- **Availability**: **All countries** (recommended for an OSS utility) or restrict as preferred

---

## After you complete these forms

1. Play Console → **Internal testing** → you'll see the existing draft release "v1"
2. Click **Review release** → **Start rollout to Internal testing**
3. Play will show any remaining blockers; fix them and re-click
4. Once rolled out: the app state flips from "draft app" to "published" and every future release goes `make release-internal` → live on internal, no browser ever

## After that, for adding testers

Play Console → **Internal testing** → **Testers** tab → either:
- **Create email list** → paste your email(s), up to 100 → Save → toggle list to active
- OR **Google Group** method for larger/dynamic lists

Then: share the opt-in URL shown at the top of the Testers tab. You + any listed testers click it once in a browser logged into the matching Google account, hit "Become a tester," and the app appears installable from the Play Store app.
