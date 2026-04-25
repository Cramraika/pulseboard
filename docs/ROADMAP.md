# Pulseboard Roadmap

**Doc owner:** Chinmay
**Latest revision:** 2026-04-25
**Current version on Play (Internal track):** v1 (versionCode 1)
**Current source-of-truth on `main`:** v0.2.0 (versionCode 2 — staged, not yet uploaded)
**Sibling project (Windows desktop diagnostic):** `Cramraika/pulseboard-desktop` v1.0.0 (live)

---

## 1. Where we are today

Pulseboard the public Android app is in a deliberately-paused stub state — the public `:core` engine (`PingEngine`, `SampleBuffer`, `MetricsCalculator`, `SheetsUploader`, `WifiMetadataCollector`, `GatewayResolver`, `UdpDnsPinger`) is shipped + tested, the brand + signing + distribution pipeline (Firebase + Play Store, Makefile, render-assets, sync-listing) is fully wired, the Play Store listing is published with v1 internal, and the in-app placeholder now surfaces the freshly-released `pulseboard-desktop` companion. What we do **not** have on the public side is the `:app`-level wiring: no `PingService`, no onboarding flow, no per-target configuration UX, no upload retry hardening.

Pulseboard's CN-internal sibling (`NetworkMonitorCN`, private) has shipped through v1.5.2 with all of that in production. The shape of the work for the next 6-12 months is clear: **port the proven `:app`-level architecture from NMCN to Pulseboard, but with the CN-specific assumptions extracted into runtime configuration so any team can fork-and-rebrand without editing `Constants.kt`.**

This document is the plan for that work.

### 1.1 Concrete delta vs NMCN today

What's in NMCN's `:core` that should land in Pulseboard's `:core`:

| File | Purpose | Why we want it |
|---|---|---|
| `HttpClients.kt` (52 LOC) | Shared OkHttp instance, 1 connection pool + TLS cache | Saves ~200 KB RAM per extra HTTP consumer; required before adding `ThroughputProbe` |
| `PingTargetId.kt` (43 LOC) | Enum of canonical target IDs the Sheet pivots on | Target identity belongs in `:core`, not `:app` — forks may add their own targets but the `Sheet → enum` round-trip should be canonical |
| `ThroughputProbe.kt` (211 LOC) | 500 KB HTTPS download per flush window | The single most analytically-useful real-world metric; CN field data showed it's what separates good Wi-Fi from bad more reliably than ICMP RTT |
| `WifiGroup.kt` (70 LOC) | Maps `(gateway_ip, network_type)` → coarse bucket | Server-side derivation belongs in the Apps Script handler, but a client-side helper is the cheapest way to surface a Wi-Fi-group hint in the UI |

What's in NMCN's `:app` that needs equivalent wiring on the public side (NOT direct port — the public version must be configurable, not hardcoded):

| Capability | NMCN file | Public-side approach |
|---|---|---|
| Foreground sampling service | `PingService.kt` (620 LOC) | Port the **architecture** (5 sampler coroutines + flusher + retain-on-failure buffer) but read targets/webhook/email-policy from `SharedPreferences` set during onboarding |
| Wall-clock-aligned flusher | inside `PingService.kt` | Same; alignment is universal |
| Onboarding | `OnboardingActivity.kt` (569 LOC) | New flow that prompts for webhook URL + (optional) target list + (optional) email allowlist regex; CN's 8-step permission chain stays |
| Encrypted prefs | `SecurePrefs.kt` (103 LOC) | Same primitive; what's stored differs (no CN email) |
| Watchdog | `WatchdogWorker.kt` (111 LOC) | Same primitive; OEM hardening matters for any forking team |
| Boot receiver | `BootReceiver.kt` | Same |
| Notification helper | `NotificationHelper.kt` | Same |
| Last-flush dashboard | `MainActivity.kt` (145 LOC) | Replace the current stub with a real read-only dashboard once the service is wired |
| Constants | `Constants.kt` (107 LOC) | **Major fork point**: split into `BuildConfig` defaults (forkers can override at build time) + `SharedPreferences` runtime values (end users configure via onboarding) |

### 1.2 What we deliberately are NOT bringing across

NMCN-specific bits that **stay private** and never land in Pulseboard:

- ASM dual-write architecture (CN-specific business decision; pulseboard's upload remains pluggable)
- The hardcoded `WEBHOOK_URL` (`https://sales-oneview.codingninjas.com/...`) and `ASM_API_KEY`
- The `@codingninjas.com` email gate (Pulseboard's email gate is a configurable regex; default is "any non-empty string")
- Specific Smartflo IPs (`14.97.20.x`) — these belong in a "preset providers" list in docs, not in default `$Targets`
- The CN-specific Wi-Fi gateway lookup (`172.16.24.1` → `smartflo_wifi`) — `WifiGroup.kt` lands in pulseboard but with an empty default lookup; users supply their own via `BuildConfig`

---

## 2. North star — what Pulseboard 1.0 means

From `CLAUDE.md`'s vision section, lightly tightened:

> **The default install-and-forget "network health vitals" agent for distributed teams.** Zero-dashboard, spreadsheet-native, configurable in 5 minutes, works on any Android 8+ device. Fork-friendly enough that 50 teams could each ship their own branded build to their employees with zero code changes — only `BuildConfig` flags + an Apps Script template they deploy themselves.

Pulseboard 1.0 means: **a fresh Android user can install the public APK from Play, walk through onboarding (deploy provided Apps Script template, paste their `/exec` URL, type their employee identifier), and 15 minutes later have a row in their own Google Sheet.** No CN-specific assumptions, no "you need to be at our office", no infrastructure beyond what Google gives them for free.

Three audiences are served by reaching that bar:

1. **End users at small-to-mid teams** — install via Play, the IT person gives them the Apps Script URL, done.
2. **IT teams at small-to-mid orgs** — fork the repo, run a one-shot rebrand script, ship a branded APK to their team via Firebase distribution or sideload.
3. **Sponsors / consultants** — Vagary Labs OSS Utilities umbrella; Pulseboard is the flagship Android piece (alongside `pulseboard-desktop` for Windows, and future Linux/Mac ports).

---

## 3. Strategic directions considered

Three approaches to closing the NMCN ↔ Pulseboard gap. We picked **C**.

### Direction A — Direct port from NMCN, strip CN tokens
- **Pros**: Fastest to feature parity. ~1500 LOC of working, field-tested code.
- **Cons**: NMCN's heritage assumptions (CN office Wi-Fi map, Smartflo IPs in defaults, `@codingninjas.com` email gate, ASM webhook URL) are sprinkled through `Constants.kt`, `OnboardingActivity.kt`, and `PingService.kt`. Stripping them is mechanical but each strip introduces a "what's the default now?" decision. Risk of leaving CN-specific assumptions baked in by accident.

### Direction B — Greenfield rebuild with no NMCN reference
- **Pros**: Cleanest possible codebase. Zero risk of CN heritage leaking.
- **Cons**: Reimplements 1500 LOC of debugged production code. ~3-4 weeks of work that doesn't deliver new capability — just re-derives existing.

### Direction C — Hybrid (CHOSEN)
- Port `:core` gaps directly from NMCN (the 4 missing files are CN-clean already and were always intended as shared engine).
- Rebuild `:app` fresh, using NMCN's `:app` as **architectural reference** (sampler/flusher pattern, retain-on-failure semantics, OEM hardening playbook, watchdog cadence) — but every value that's currently a hardcoded constant in NMCN becomes either a `BuildConfig` field (forkers set at build time) or a `SharedPreferences` value (end users set during onboarding).
- **Net effect**: ~70% of `:app` LOC is fresh code organised around configurability. The remaining ~30% is mechanical port of OEM-hardening tactics (autostart intent, foreground service type combos, NEARBY_WIFI_DEVICES permission gate) that work the same regardless of who's building.

The key invariant: **no value in `Constants.kt` should be CN-specific**. If a forking team would need to change it, it goes in `BuildConfig` or `SharedPreferences`.

---

## 4. Milestone roadmap

Versioning convention: stay on `0.x.y` until v1.0 (which is the "fully-functional public app" milestone). Then `1.x.y` for additive features. `2.0.0` reserved for a major architectural shift (e.g. multi-endpoint adapter or iOS port).

### v0.3 — `:core` gap close (1 sitting, ~1 day)

**Scope**: Port the 4 missing `:core` files from NMCN (HttpClients, PingTargetId, ThroughputProbe, WifiGroup), with their tests. No `:app` changes.

**Files added**:
- `core/src/main/java/com/pulseboard/core/HttpClients.kt`
- `core/src/main/java/com/pulseboard/core/PingTargetId.kt`
- `core/src/main/java/com/pulseboard/core/ThroughputProbe.kt`
- `core/src/main/java/com/pulseboard/core/WifiGroup.kt` (with empty default `LOOKUP` map — forkers populate via `BuildConfig`)
- Corresponding test files (4 new, ~55 new tests)

**Exit criteria**:
- `./gradlew :core:test` green; total test count goes from 52 → ~107
- `:core` LOC goes from 868 → ~1180
- No regression in `:app` (it's still a stub; nothing to break)

**Why first**: These primitives are zero-risk additions (no `:app` consumers yet) and unblock everything downstream. They also let `pulseboard-desktop` users running the laptop-side tool point at the same shared `WifiGroup` semantics if/when we add a "match my phone's wifi_group label" feature on the desktop side.

### v0.4 — Configurable Constants + Apps Script template + onboarding skeleton (~3 sittings)

**Scope**: Stop being a stub. Wire MainActivity to read a configured webhook URL + at least one ping target. Onboarding flow. Apps Script template that anyone can deploy.

**Concrete deliverables**:

1. **Split `Constants.kt`** into:
   - `BuildConfig`-backed (`build.gradle.kts` `buildConfigField` lines): `DEFAULT_WEBHOOK_URL` (empty for OSS build, fork-overridable), `DEFAULT_TARGET_IPS` (empty for OSS, fork-overridable), `EMAIL_DOMAIN_REGEX` (empty for OSS = no gate), `APP_VERSION_TAG`
   - `SharedPreferences`-backed (set during onboarding): `webhook_url`, `user_id`, `enabled_target_ids`, `flush_interval_min` (fixed at 15 for v0.4)

2. **`OnboardingActivity`** (new, ~300 LOC). 4 steps:
   - Step 1: Email/identifier (gated by `EMAIL_DOMAIN_REGEX` if set, otherwise free-form)
   - Step 2: Webhook URL (paste your `/exec`; validate it's HTTPS + responds; offer "I haven't set up Apps Script yet, here's the template" link)
   - Step 3: Pick targets (Cloudflare DNS, Cloudflare CDN, Google DNS, Google API, Microsoft Teams — all defaulted on; advanced: add custom IPs)
   - Step 4: Permissions (notifications, location-for-Wi-Fi-BSSID, battery exemption)

3. **Apps Script template**: `docs/apps-script/bootstrap.gs` — paste-into-Sheet template that creates a new Sheet tab + accepts the v1.3 schema. User deploys as Web App, copies the `/exec` URL, pastes into onboarding. Goes alongside the existing `metadata/android/...` material in repo.

4. **`MainActivity`** (replace the stub-with-companion-card). Read-only dashboard that displays:
   - Last flush timestamp
   - Last-window p95 RTT + jitter + loss per active target
   - Wi-Fi group label (if `WifiGroup` resolves to anything specific)
   - Service status (foreground service running? watchdog last restart?)
   - The companion-card we already shipped, repositioned at the bottom

5. **`PingService`** (new, ~400 LOC). Architecture mirrors NMCN's PingService but:
   - Reads target list from `SharedPreferences` (not enum hardcode)
   - Webhook URL from `SharedPreferences`
   - Same wall-clock-aligned flusher, same retain-on-failure buffer (5400 samples), same OEM-hardening foreground service type combo
   - Skips throughput probe on cellular by default (configurable)

**Exit criteria**:
- Fresh install + onboarding + 15-min wait → row lands in the user's Sheet
- `make ship-internal` succeeds; v0.4.0 in Internal track
- Two test forkers (Chinmay's own deploy + one other) confirm fork-and-rebrand works without editing `Constants.kt`
- 95%+ duty-cycle on a Pixel 8 across 24 hr (smoke test)

**Why second**: This is the smallest scope that makes Pulseboard genuinely useful to a non-Chinmay user. v0.3 sets up `:core`; v0.4 turns the stub into an installable app.

### v0.5 — OEM hardening + permission chain port (~2 sittings)

**Scope**: NMCN's v1.2 permission/foreground-service hardening, ported to Pulseboard with no CN-specific values.

**Concrete deliverables**:

1. **`OnboardingActivity`** gains the full 8-step permission chain (NEARBY_WIFI_DEVICES gate, ACCESS_BACKGROUND_LOCATION gate, OEM autostart intent, auto-revoke exemption). Identical playbook to NMCN's v1.2.

2. **`WatchdogWorker`** (new, ~110 LOC). WorkManager periodic worker that pings the last-sampler-heartbeat preference; if stale > 5 min, restarts `PingService`. Cooldown 10 min. Direct architectural copy from NMCN.

3. **`SecurePrefs`** (new, ~100 LOC). EncryptedSharedPreferences wrapper for `user_id`. Migration shim from plain prefs.

4. **`BootReceiver`**. Restart on `BOOT_COMPLETED`.

5. **Manifest declarations**: `FOREGROUND_SERVICE_DATA_SYNC | FOREGROUND_SERVICE_LOCATION` (combined type — required for `WifiManager.startScan()` from background on Android 13+).

**Exit criteria**:
- Survives 7-day soak on Xiaomi/Realme/Vivo/OPPO devices (NMCN's matrix), with ≥ 95% duty cycle
- Fresh install on a stock Android 14 device → service still running 24h later through screen-off

### v0.6 — Apps Script handler v1.3 ported + `WifiGroup` server-side derivation (~1 sitting)

**Scope**: A canonical Apps Script handler in the repo that handles the v1.3 schema, server-derives `wifi_group` and `flush_outcome`, maintains a Users tab. Forkers deploy this verbatim.

**Concrete deliverables**:

1. `docs/apps-script/doPost-v1.gs` (paste-ready). Mirrors NMCN's `docs/superpowers/apps-script/doPost-v1.3.gs` structure but:
   - Empty `WIFI_GROUP_LOOKUP` (forkers populate)
   - No CN-specific Sheet IDs hardcoded
   - Defaults to creating a new Sheet on first run
2. `docs/apps-script/README.md` — deploy instructions
3. `docs/apps-script/SCHEMA.md` — column reference

**Exit criteria**:
- Two deploys (Chinmay + one external test forker) successfully receive v0.4+ flushes and produce the expected derived columns

### v1.0 — first production-track release (~1 day for promotion + announce)

**Scope**: No new features. Promote v0.6 from Internal → Alpha → Beta → Production. Announce.

**Concrete actions**:
1. `make promote-alpha` (Google review, hours-days first time)
2. `make promote-beta`
3. `make promote-prod PCT=0.05` (5% staged rollout)
4. Day 3-7: monitor reviews + crash reports + Sheet-side data-quality metrics
5. `make rollout PCT=0.5` then `PCT=1.0` if green
6. Tag `v1.0.0`, GitHub Release with full notes
7. Update `docs/index.md` with "now on Play Store" + Play badge
8. Announcement push (HN, r/networking, r/sysadmin, LinkedIn — copy in `docs/announcements/v1.0.md`)

**Exit criteria**: 100% prod rollout; ≥ 95% crash-free sessions; sponsor count > 0.

### v1.1 — fork-and-rebrand toolkit (~1 sitting)

**Scope**: A `scripts/rebrand.sh` (interactive) that produces a forkable build for any team in under 10 minutes.

**Concrete deliverables**:
1. `scripts/rebrand.sh`. Prompts for:
   - App name (e.g. "Acme Network Monitor")
   - Package name (e.g. "com.acme.netmon")
   - Brand primary colour (hex)
   - Logo (PNG path, will be processed into adaptive icon + mipmaps via `render_brand_assets.py`)
   - Default webhook URL (optional, baked into `BuildConfig`)
   - Default email regex (optional)
2. Renames package, updates `applicationId`, regenerates icon set, updates `metadata/android/` text + screenshots, updates README/CLAUDE.md to reflect the fork's identity.
3. `docs/FORK_GUIDE.md` — long-form guide. Discoverable from README.

**Exit criteria**: A test fork (`Cramraika/pulseboard-fork-test`) is built end-to-end via the script in < 10 min, passes lint, builds a signed APK with the new identity. Documented in the repo with the artifact deleted.

**Why this is its own milestone**: Fork-and-rebrand is the central differentiator of Pulseboard's positioning. It's worth investing in as a polished, sponsor-credible feature.

### v1.2 — multi-endpoint adapter (~3-4 sittings)

**Scope**: `:core`'s upload mechanism is currently `SheetsUploader` only. Generalise to a pluggable interface so forks can target other backends.

**Concrete deliverables**:
1. **`Uploader` interface** in `:core` with method `suspend fun upload(payload: List<SheetPayload>): UploadResult`
2. **3 implementations**:
   - `SheetsUploader` (existing — refactored to interface)
   - `WebhookUploader` (generic POST to any HTTPS endpoint with optional bearer token)
   - `SupabaseUploader` (insert into a Supabase table — Supabase is a common "we want a real DB not a Sheet" target for small teams)
3. **Onboarding step** lets user pick the upload target type during onboarding (default: Sheets).
4. **Tests**: each implementation has its own test class. ~30 new tests.

**Exit criteria**: All 3 backends successfully receive a flush in test deploys.

### v1.3 — Sentry/GlitchTip optional crash reporting (~1 sitting)

**Scope**: Out-of-the-box, the app sends nothing to Sentry. If forkers want crash reporting, they paste their DSN into `BuildConfig` at fork-time. The Sentry SDK is included as a dependency but disabled if no DSN is set.

**Why optional**: Pulseboard's brand promise is "no telemetry, no SaaS dependency". Public default builds must honour that. Forkers who want crash reports for THEIR team get them at zero friction.

### v1.4 — `pulseboard-desktop` deeper integration (~2 sittings)

**Scope**: Make the Android ↔ Windows pairing more useful than just a README link.

**Concrete deliverables**:
1. **Sheet schema gains optional `desktop_correlation_id` column.** When `pulseboard-desktop` runs in the same Wi-Fi as a phone running Pulseboard, it can write a marker file the phone reads + uploads as the correlation ID. Lets analysts overlay phone-side and desktop-side data on the same time window.
2. **Documentation**: `docs/PAIRING.md` explaining how to use both tools together, with example pivots.
3. **Optional**: `pulseboard-desktop` gains a `--match-pulseboard-android` flag that emits a marker file the phone can read.

**Why this is its own milestone**: cross-promotion of two OSS tools is more credible when there's actual product-side integration, not just READMEs that link to each other.

### v1.5 — Self-serve Sheet provisioner (~2 sittings)

**Scope**: Today, deploying the Apps Script handler is the highest-friction onboarding step (5-10 min of Google Apps Script UI clicks). Reduce to "click this link, it does it for you."

**Concrete deliverables**:
1. **Sheet template** at a public-shareable Google Sheet URL with the script + tabs pre-set up. User clicks "File → Make a copy", then redeploys the script with their own credentials in 1 click.
2. **`docs/apps-script/QUICK_DEPLOY.md`** — the new shortened path, becomes the default in onboarding.
3. The longer hand-paste path stays as fallback for users who want to inspect the script before running it.

### v2.0 — milestone gate; pick at the time

When v1.5 ships, four genuinely-major directions could land at v2.0. Pick one based on actual demand at that point (sponsor signals, fork count, GitHub issue patterns):

- **v2.0a — iOS port.** Biggest single addressable-audience expansion. Probably ~3 months of focused work; sponsor-funded.
- **v2.0b — federated multi-tenant Sheet.** "One Sheet for ALL your forks across customers" — moves Pulseboard up-market into MSP territory.
- **v2.0c — first-party hosted backend.** Optional managed Supabase + dashboard for teams who don't want to run their own Apps Script. Crosses the OSS-vs-SaaS line — only do this if the sponsor revenue justifies operations cost.
- **v2.0d — Wi-Fi-AP-side integration.** Talk to Aruba / Ubiquiti / Mikrotik APIs to enrich Pulseboard data with AP-side counters (bssid_changes, channel utilisation, etc). Powerful for IT teams; niche audience.

The v2.0 decision is **deferred to v1.5 ship time**. Don't over-commit early.

---

## 5. Cross-cutting concerns

### 5.1 Sponsor flywheel evolution

Tied to milestones, not standalone:

| Stage | Sponsor pitch reads as | What unlocks the next stage |
|---|---|---|
| Today (v0.2) | "Help us ship a real public app" | v1.0 (functional public app) |
| v1.0 → v1.2 | "Help us go cross-platform / multi-endpoint" | First 5 sponsors |
| v1.2 → v1.5 | "Help us get to managed iOS / GUI / sponsor tier features" | Sponsor revenue covers a real day of work/month |
| v2.0+ | Sponsor tier features become real perks (custom branding pack, on-prem endpoint, enterprise support — already in CLAUDE.md roadmap item 8) | — |

**Sponsor visibility hygiene** (do at v1.0 ship):
- `docs/SPONSORS.md` — public thanks list
- README banner gets a "🙏 Recent sponsors:" line auto-generated from FUNDING data
- Release notes acknowledge sponsors per release
- One-quarterly LinkedIn post showcasing the project + thanking sponsors by name

### 5.2 Distribution rollout cadence

Now that the draft-app gate is lifted (`internal: v1 completed`), the rollout machinery is fully live. Standard cadence:

| Track | When something lands here |
|---|---|
| Firebase Distribution | On any push to `main` that bumps versionCode (auto-distribute to internal testers via `make distribute`) |
| Play Internal | On every milestone (v0.3, v0.4, …) via `make ship-internal` |
| Play Alpha | On every minor (v1.1, v1.2, …) via `make promote-alpha` after 48 hr Internal soak |
| Play Beta | On every minor after 1 week Alpha soak |
| Play Production | On every minor after 1 week Beta soak; staged rollout 5% → 25% → 50% → 100% over 2 weeks |
| GitHub Release | On every Play Production push |

**Halt protocol** (already wired): `make halt TRACK=production` if crash-free sessions drops below 99% for any 24-hour window during rollout.

### 5.3 Fork-and-rebrand UX (cross-cutting through all milestones)

Every code change between now and v1.0 holds itself to the **fork-and-rebrand acid test**:

> Can a non-Chinmay developer at a non-CN org fork the repo, run a single command (eventually `scripts/rebrand.sh`), and ship a branded APK to their team without editing any `.kt` source file?

If a feature can't pass that test, it goes in `BuildConfig` or `SharedPreferences`, not as a hardcoded `Constants.kt` value.

### 5.4 Cross-promotion with `pulseboard-desktop`

Already wired:
- README "Related projects" section in both repos
- GitHub Pages "Desktop companion" section
- In-app card in MainActivity (just shipped in v0.2.0)
- Play Store "Desktop Companion" paragraph in full description (synced as pending edit)

Future deepening:
- v1.4 explicit pairing feature (above)
- v1.0 announcement positions pulseboard + pulseboard-desktop as "the Vagary Labs network-quality stack"

### 5.5 Repo hygiene + technical debt as we go

Bake into milestones, don't defer:

| Debt item | Where it lands |
|---|---|
| 9 pre-existing lint warnings (Gradle dep updates, RedundantLabel, ObsoleteSdkInt, Overdraw, MonochromeLauncherIcon × 2) | Chip away one per milestone — drop to 0 by v1.0 |
| `:core`'s `WifiSnapshot` has CN-specific defaults? Audit during v0.3 port | v0.3 |
| `SheetsUploader.kt` mentions "smartflo / gateway / cloudflare / dns for CN" in the doc comment | Generalise during v0.3 |
| Brand assets are static PNGs — re-render needs `cairo` system lib | Already documented in CLAUDE.md; OK |
| `.github/workflows/release.yml` exists but no CI on PRs | v0.4 — add `lint + test` PR workflow (same pattern as `pulseboard-desktop`'s `lint-powershell.yml`) |
| `docs/play/AUTOMATION_ENVELOPE.md` lists `data_safety` as browser-only — re-check periodically | Annual review |

### 5.6 What changes in CLAUDE.md when each milestone lands

`CLAUDE.md` § Past / Phase History grows by one entry per milestone. The Roadmap section in CLAUDE.md gets updated to reflect what's already shipped. Don't let CLAUDE.md drift from reality — the doc-vs-code drift sweep (universal-claudemd.md §40) is owed quarterly per current convention.

---

## 6. Open questions (decisions deferred)

1. **Should `OnboardingActivity` collect a Slack/email webhook for self-notifications** (e.g. "your duty cycle dropped below 80%")? Adds complexity but increases stickiness. **Decision deferred to v0.6**.
2. **Should the Apps Script template auto-create a `wifi_group` lookup tab** that admins can edit in the Sheet itself rather than re-deploying the script? Better UX but more moving parts. **Defer to v0.6**.
3. **Naming** — keep `Pulseboard` always, or per-fork the Play listing? Per-fork is the whole point of fork-and-rebrand; the **public Play listing always reads "Pulseboard"**. Forks publish under their own branded names with their own Play accounts. **Decided.**
4. **Should v1.0 include a "configurable retention period for the Sheet"** auto-trim? Sheet has 10M-cell ceiling; for a 50-user org at default cadence, ~2 years of headroom. **Defer to v1.5 or later — not a v1.0 blocker.**
5. **Default ping target list for v0.4** — anycast controls only (Cloudflare DNS+CDN, Google DNS+API, MS Teams), or also a "your gateway" auto-detect? Auto-detect is more useful (catches LAN-side issues) but requires gateway-resolution code path on first sample. NMCN's `GatewayResolver` already does this — **enable auto-gateway-detect by default in v0.4**.

---

## 7. Risks

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| Play Store policy update breaks the foreground-service-type combo | Medium | High | Watch policy announcements monthly; NMCN tested Android 14/15/16 in field; keep dependency on the canonical FGS combo to one place (`PingService.foregroundServiceType`) so a fix is a one-line change |
| Forking team accidentally commits webhook URL to public fork | Medium | Medium | `BuildConfig` defaults documented as "set in `local.properties`, never in `build.gradle.kts`". `.gitignore` covers `local.properties`. README warns. |
| Apps Script quota changes (Google can slash quotas without notice) | Low | High | Roadmap item v1.2 (multi-endpoint) covers the migration path; if quota becomes a real problem before v1.2, accelerate the WebhookUploader implementation |
| Pulseboard gets very few stars / forks → sponsors don't materialise | Medium | Medium | Sponsor pitch is honest about state ("help ship the public version"); v1.0 production rollout + Show HN is the demand-test gate. If still cold after v1.0, pivot to MSP-targeted positioning. |
| iOS users feel left out / fragment the user base | Medium | Low | iOS port is on roadmap (v2.0a candidate); be explicit in README that v1.x is Android-only. `pulseboard-desktop` provides cross-platform desktop coverage. |
| OEM-specific kill behavior changes on a new Xiaomi update | High over time | Medium | NMCN's v1.2 hardening tactics ported; same playbook in pulseboard. New OEM behavior surfaces via field reports → hot-fix release path is already paved. |

---

## 8. Cadence + release rhythm

**Weekly**: Sponsor outreach (1 LinkedIn DM or 1 email per week to a relevant audience: VoIP consultants, IT MSPs, sysadmins on r/networking, etc).

**Per-milestone (~every 2-4 weeks)**: One milestone shipped to Internal. ~1 sitting per minor milestone (v0.3-v0.6); 1-2 days per major (v1.0, v1.2).

**Monthly during v1.x**: One Play Production rollout (5% → 100% over 2 weeks). One CHANGELOG.md update + GitHub Release.

**Quarterly**: Doc-vs-code drift sweep (universal-claudemd.md §40 obligation). Review this ROADMAP.md.

**Annually**: Re-audit `docs/play/AUTOMATION_ENVELOPE.md`. Re-audit FUNDING.yml + sponsor tier structure.

---

## 9. Sequencing summary

| Milestone | Scope | Effort | Dependencies | Ship vehicle |
|---|---|---|---|---|
| v0.3 | `:core` gap close | 1 day | — | Internal track |
| v0.4 | Configurable Constants + onboarding skeleton + first real PingService | 3 days | v0.3 | Internal track |
| v0.5 | OEM hardening + watchdog | 2 days | v0.4 | Internal track |
| v0.6 | Apps Script handler v1.3 ported | 1 day | v0.4 | docs/ + Internal track |
| **v1.0** | **Promote to Play Production** | **1 day** | **v0.5 + v0.6 soak** | **All tracks** |
| v1.1 | rebrand.sh fork toolkit | 1-2 days | v1.0 | Production track |
| v1.2 | Multi-endpoint adapter (Webhook + Supabase) | 3-4 days | v1.1 | Production track |
| v1.3 | Optional Sentry/GlitchTip | 1 day | v1.2 | Production track |
| v1.4 | pulseboard-desktop pairing | 2 days | v1.0 + pulseboard-desktop GUI work | Production track |
| v1.5 | Self-serve Sheet provisioner | 2 days | v1.0 | docs/ + Production |
| v2.0a-d | Decision-deferred until v1.5 ships | varies | varies | varies |

**Calendar projection** (assuming ~1 sitting/week of focused effort, around the day job):

- v0.3: 2026-05-02 (next weekend)
- v0.4: 2026-05-23 (3 weeks)
- v0.5: 2026-06-06
- v0.6: 2026-06-13
- v1.0 Internal soak begins: 2026-06-20
- v1.0 Production rollout begins: 2026-07-04
- v1.0 100% production: 2026-07-18
- v1.1 / v1.2 / v1.3 / v1.4 / v1.5: rolling through Aug-Nov 2026
- v2.0 decision: 2026-11

---

## 10. What's NOT in this roadmap (explicit non-goals)

- **Real-time dashboards inside the app.** The Sheet is the dashboard. If you want a real-time view, Google Sheets has live editing — open the Sheet, watch it.
- **Push-notification-style alerts in the app.** Pulseboard's promise is "passive, install-and-forget". Notifications about poor network would be ironic. Sheet-side conditional formatting + email-on-cell-change scripts handle this for power users.
- **Built-in chat / support / community in-app.** Issues go to GitHub. Discussion in the repo. No in-app social surface.
- **Telemetry by default.** Anything that talks to a Vagary-Labs-controlled endpoint by default is a non-starter. Sentry (v1.3) is opt-in via `BuildConfig`.
- **In-app purchases / monetisation.** OSS, sponsor-funded. No in-app payment flow.
- **A managed SaaS hosting tier under the Pulseboard name.** Out of scope unless v2.0c is selected based on demand.
- **`com.pulseboard.*` second-party SDK for other apps.** Not Pulseboard's mission.

---

## 11. Cross-references

- This repo: `CLAUDE.md` § Project Scope / Vision (north star)
- This repo: `RELEASING.md` (release lifecycle runbook)
- This repo: `docs/play/AUTOMATION_ENVELOPE.md` (what the publisher pipeline can and cannot do)
- This repo: `docs/play/NEW_APP_RUNBOOK.md` (per-app onboarding for the publishing pipeline)
- This repo: `docs/design/brand.md` (brand voice + competitor positioning)
- Sibling repo: [`Cramraika/pulseboard-desktop`](https://github.com/Cramraika/pulseboard-desktop) (Windows companion)
- Private parent: `~/AndroidStudioProjects/NetworkMonitorCN/` (CN-internal sibling that's the source of all the proven-in-production code we'll port)
- Vagary Labs umbrella: `~/.claude/conventions/repo-inventory.md` § Cramraika OSS utilities
- Sponsor convention: `~/.claude/conventions/universal-claudemd.md` § 39 Sponsor + white-label

---

## 12. Document ownership

- **Roadmap author**: Chinmay (2026-04-25)
- **Living doc**: yes — Claude updates as milestones land. Each milestone closing a row in §4 should bump the "where we are today" section + add a Past / Phase History entry in CLAUDE.md.
- **Quarterly review**: 2026-07-25 (next), then quarterly.
- **Decision-maker on direction questions in §6**: Chinmay; Claude assists with research + drafts.
