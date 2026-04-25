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

### v1.2 — multi-endpoint adapter (~1-1.5 weeks; SCOPE EXPANDED 2026-04-25)

**Scope**: `:core`'s upload mechanism is currently `SheetsUploader` only. Generalise to a pluggable interface and ship **all four** backends in this milestone (decision 2026-04-25; the original "ship Webhook only, add Supabase later" sequencing was rejected in favour of one cohesive multi-endpoint release).

**Concrete deliverables**:
1. **`Uploader` interface** in `:core` with method `suspend fun upload(payload: List<SheetPayload>): UploadResult`
2. **Four implementations**:
   - `SheetsUploader` (existing — refactored to implement `Uploader`)
   - `WebhookUploader` — generic POST to any HTTPS endpoint with optional bearer token. Maximum flexibility (works with Zapier / Make.com / n8n / PostgREST / anything).
   - `SupabaseUploader` — direct insert into a Supabase Postgres table. Real DB, SQL queries from day 1. ~150 LOC of dedicated client code.
   - `NotionUploader` — append to a Notion database row. Notion-as-team-knowledge-base is increasingly common at small teams. ~200 LOC of dedicated Notion API code.
3. **Onboarding flow**: backend picker step. Default = Sheets (continues v0.4 onboarding's behaviour). Webhook + Supabase + Notion are alternative paths.
4. **Per-backend onboarding sub-flows**:
   - Sheets: Apps Script clone-Sheet (default) or paste-script (advanced) — same as v0.4
   - Webhook: paste URL + optional bearer-token field
   - Supabase: paste project URL + anon key + table name
   - Notion: paste integration token + database ID + property mapping
5. **Tests**: each implementation has its own test class with mock HTTP server. ~40 new tests across 4 backends.

**Why ship all 4 at v1.2 instead of staging**: the core abstraction is the same effort regardless of how many implementations sit on top. Adding 3 more backends after the interface lands is just N×~150 LOC of mechanical work + onboarding screens. One milestone, one announcement, one set of v1.2 release notes — beats spreading the same work across v1.2 / v1.2.1 / v1.2.2.

**Exit criteria**: All 4 backends pass smoke test with a live deploy. Onboarding picker UI tested on each path. Per-backend section in `docs/BACKENDS.md`.

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

### v2.x — competitive-coverage program (DECIDED 2026-04-25)

**Frame** (replaces the original "iOS / federation / hosted / AP" candidates): map Pulseboard's feature surface against the existing market, identify gaps, ship parity-or-better. The differentiator stays "best-in-class Sheet/integration setup" (already covered by v1.2 multi-endpoint adapter + v1.5 self-serve provisioner) — so v2.x focuses on FEATURE-SET parity with established players in 4 categories:

#### v2.0 — Active diagnostic tools parity (PingPlotter / WinMTR / Smokeping)

**Audience**: power users and IT teams who today open PingPlotter/WinMTR for ad-hoc diagnostics. Pulseboard's wedge is "continuous + multi-device + Sheet-native pivot" instead of single-device-snapshot.

**Feature gaps to close**:
- On-demand "deep-probe now" UI (not just continuous sampling) — a button in MainActivity that fires an immediate MTR + speedtest + iperf burst and shows results inline (vs waiting for next 15-min flush)
- Configurable target schedules (some targets every 1s, others every 5 min — saves bandwidth on low-priority targets)
- Path-MTU discovery alongside RTT (PingPlotter has it; Pulseboard doesn't)
- Pre-built provider profiles for the 9 VoIP providers in `pulseboard-desktop`'s `docs/VOIP_PROVIDERS.md` — port the same profile snippets into the Android app's onboarding "preset providers" picker

**Effort**: ~3-4 weeks of focused work.

#### v2.1 — Enterprise SaaS NPM features (Datadog NPM / Catchpoint / ThousandEyes parity for free)

**Audience**: teams currently quoting $1k-$10k/mo for Datadog NPM but who would prefer free + private. Pulseboard's wedge: spreadsheet-native + no SaaS subscription.

**Feature gaps to close**:
- Synthetic monitor scheduling ("ping smartflo every minute, but only between 9 AM and 6 PM IST")
- Alert routing (Slack / email / PagerDuty webhook on threshold breach — already opted-in via v0.4's notification step)
- BGP path analysis on the desktop side (`pulseboard-desktop` extension)
- Multi-region simultaneous probing (multi-device coordination — one Android in Delhi, another in Bangalore, sample the same targets, surface regional divergence in the Sheet)
- SLO tracking (per-target uptime % over rolling 7/30/90-day windows, computed Sheet-side via the Apps Script handler)

**Effort**: ~4-6 weeks. Many features are Sheet-script work (Apps Script handler upgrades), not Android changes.

#### v2.2 — Self-hosted infrastructure monitoring features (PRTG / Nagios / Zabbix / Solarwinds NPM equivalents)

**Audience**: small IT shops who currently run a dedicated monitoring server. Pulseboard's wedge: "the agent is on the user's phone, not on a server you maintain".

**Feature gaps to close**:
- SNMP polling (the Android app polls printer/AP/switch SNMP from inside the office — controversial, may need a lightweight desktop daemon instead)
- Network topology auto-discovery from gateway probes (build a "discovered devices" tab in the Sheet)
- Per-device uptime tracking (each network device gets a row, last-seen + uptime% over windows)
- Alert templates ("notify if subnet X loses > 50% of devices for > 5 min")

**Effort**: ~4-6 weeks. Some features (SNMP) may pivot to live in `pulseboard-desktop` instead of the Android app.

#### v2.3 — AP-side WLAN integration (Aruba Insight / Cisco DNA / Ubiquiti Site Monitoring)

**Audience**: IT teams running enterprise Wi-Fi. Pulseboard's wedge: cross-references client-side reality (from phones) with AP-side counters (from APs themselves).

**Feature gaps to close**:
- Aruba ArubaCentral API integration (read AP/client state, enrich Pulseboard rows with `ap_id`, `radio_band`, `client_steering_events`)
- Ubiquiti UniFi Controller API (same shape; UniFi is the dominant SMB choice)
- Cisco Meraki Dashboard API (heavier integration; defer unless explicit ask)
- Sheet-side join: Pulseboard rows × AP-side rows on `(timestamp, bssid)` to surface "this user's bad Wi-Fi window correlates with this AP's high-channel-utilisation window"
- Mikrotik RouterOS API (lower priority but cheap to add; copy the AP integration shape)

**Effort**: ~6-8 weeks across 4 vendors; can ship Aruba + Ubiquiti at v2.3.0 and stage Cisco/Mikrotik for v2.3.1+.

#### v2.x sequencing

| Track | Trigger | Estimated ship |
|---|---|---|
| v2.0 — Active diagnostic parity | After v1.5 + 1-week dogfood | ~2026-10 |
| v2.1 — Enterprise SaaS NPM features | After v2.0 stabilises | ~2026-12 |
| v2.2 — Self-hosted infra monitoring | After v2.1 | ~2027-02 |
| v2.3 — AP-side WLAN integration | After v2.2 | ~2027-04 |

**Each v2.x track is its own ~1-2 month program.** No more "single milestone, decide at v1.5 time" framing. The roadmap explicitly commits to all 4, with sequencing that prioritises the most-direct-comparison categories first (active diagnostic > enterprise NPM > self-hosted > AP-side).

#### Cross-track competitive analysis (lives in `docs/COMPETITIVE.md`)

Add a new doc that tracks, for each market tool:
- Feature matrix (rows = features, columns = tools, cells = ✅ / 🟡 / ❌)
- Where Pulseboard sits on each row
- Roadmap milestone that closes each gap (links back to v2.0 / v2.1 / v2.2 / v2.3)
- Last-audit date per tool (audit annually)

This doc is the single source of truth for "what's the next feature parity gap to close" decisions.

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

## 6. Decisions log (all RESOLVED 2026-04-25)

The original "open questions" section is now closed. Decisions made + locked into the roadmap:

| # | Decision | Locked choice | Where reflected |
|---|---|---|---|
| 1 | Architecture direction | **Direction C** (hybrid: port `:core` gaps directly, rebuild `:app` fresh with configurable `Constants` using NMCN as architectural reference) | §3 |
| 2 | Pace | **Sustainable** ~5-6 weeks to v1.0 | §9 calendar |
| 3 | v2.0 frame | **Market-feature-parity + best-in-class sheet/integration setup** (NOT iOS/federation/hosted) | §4 v2.x |
| 4 | v0.4 onboarding scope | **Comprehensive** — custom-IP editor + Slack/email self-notifications + auto-provision flow + Wi-Fi group label override + dark/light theme toggle | §4 v0.4 |
| 5 | Default ping targets | **Anycast 5 + auto-gateway** (5 anycast presets ON by default + auto-detect default gateway as 6th target via existing `GatewayResolver`) | §4 v0.4 |
| 6 | Apps Script delivery | **Both clone-Sheet (default) + paste-script (advanced)** in onboarding | §4 v0.6 + v1.5 |
| 7 | CI on PR | **At v0.4** alongside the Constants split | §4 v0.4 |
| 8 | Sponsor tier structure | **Keep flat** (default GitHub Sponsors tiers; same convention as bulk + tldv_downloader). Add tier perks LATER if sponsor revenue justifies the design effort. | §5.1 |
| 9 | Identifier policy default | **Any non-empty string** (no domain gate by default; forkers set their corporate regex via `BuildConfig` if they want one) | §4 v0.4 |
| 10 | Domain `pulseboard.build` | **Defer indefinitely** — `cramraika.github.io/pulseboard` is enough | (removed from roadmap) |
| 11 | v2.0 benchmarks | **All 4 categories**: active diagnostic + enterprise SaaS NPM + self-hosted infra + AP-side WLAN. Sequenced as v2.0 → v2.1 → v2.2 → v2.3. | §4 v2.x |
| 12 | v1.2 backends | **All 3 at once**: Webhook + Supabase + Notion. Single milestone, single announcement. | §4 v1.2 |

### Items kept as deferrals (not blockers)

| Item | Status |
|---|---|
| `wifi_group` auto-create lookup tab in Sheet | Deferred to v2.1 (folded into "Sheet-side SLO tracking" work) |
| Configurable Sheet retention auto-trim | Deferred — at default cadence + 50 users, ~2 years of headroom; revisit at v2.0+ |
| Naming — Play listing per-fork vs always Pulseboard | **Decided**: public Play listing always reads "Pulseboard"; forks publish under their own branded names with their own Play accounts via `scripts/rebrand.sh` (v1.1) |

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
| v0.3 | `:core` gap close (4 files: HttpClients, PingTargetId, ThroughputProbe, WifiGroup) | 1 day | — | Internal track |
| v0.4 | Comprehensive onboarding + configurable Constants + first real PingService + CI-on-PR | 5 sittings | v0.3 | Internal track |
| v0.5 | OEM hardening + watchdog + WatchdogWorker + SecurePrefs + BootReceiver | 2 sittings | v0.4 | Internal track |
| v0.6 | Apps Script handler v1.3 ported (clone-Sheet + paste-script paths in docs) | 1 day | v0.4 | docs/ + Internal |
| **v1.0** | **Promote to Play Production + announce** | **1 day** | **v0.5 + v0.6 soak (1 week each)** | **All tracks** |
| v1.1 | `scripts/rebrand.sh` fork-and-rebrand toolkit | 1-2 days | v1.0 | Production track |
| **v1.2** | **Multi-endpoint adapter — ALL 4 backends (Sheets refactor + Webhook + Supabase + Notion)** | **1-1.5 weeks** | v1.1 | Production track |
| v1.3 | Optional Sentry/GlitchTip (paste DSN in BuildConfig at fork-time) | 1 day | v1.2 | Production track |
| v1.4 | pulseboard-desktop pairing — `desktop_correlation_id` Sheet column + `--match-pulseboard-android` flag | 2 days | v1.0 + pulseboard-desktop coordination | Production track |
| v1.5 | Self-serve Sheet provisioner — Sheet template URL with Make-A-Copy | 2 days | v1.0 | docs/ + Production |
| **v2.0** | **Active-diagnostic-tools parity** (PingPlotter / WinMTR / Smokeping equivalents) | **3-4 weeks** | v1.5 dogfood 1 week | Production |
| v2.1 | Enterprise SaaS NPM features (Datadog NPM / Catchpoint / ThousandEyes equivalents) | 4-6 weeks | v2.0 | Production |
| v2.2 | Self-hosted infra monitoring features (PRTG / Nagios / Zabbix / Solarwinds NPM equivalents) | 4-6 weeks | v2.1 | Production |
| v2.3 | AP-side WLAN integration (Aruba ArubaCentral + UniFi + optionally Cisco Meraki + Mikrotik) | 6-8 weeks | v2.2 | Production |

**Calendar projection** (assuming sustainable pace — ~1 sitting per weekend of focused effort, around the day job):

| Date | Milestone |
|---|---|
| 2026-05-02 | v0.3 ships to Internal |
| 2026-05-23 | v0.4 ships (5 sittings = ~3 weeks) |
| 2026-06-06 | v0.5 ships |
| 2026-06-13 | v0.6 ships |
| 2026-06-20 | v1.0 Internal soak begins |
| 2026-07-04 | v1.0 Production rollout begins (5%) |
| 2026-07-18 | v1.0 100% production |
| 2026-08-01 | v1.1 (rebrand.sh) ships Production |
| 2026-08-22 | v1.2 (4 backends) ships Production |
| 2026-08-29 | v1.3 (optional Sentry) ships |
| 2026-09-12 | v1.4 (pulseboard-desktop pairing) ships |
| 2026-09-26 | v1.5 (self-serve Sheet) ships |
| 2026-10-24 | **v2.0 (active-diagnostic parity) ships** |
| 2026-12-12 | v2.1 (enterprise NPM features) ships |
| 2027-02-13 | v2.2 (self-hosted infra features) ships |
| 2027-04-17 | v2.3 (AP-side WLAN integration) ships |

**v1.0 production: ~2026-07-18.** v2.x competitive-coverage program runs through Oct 2026 → Apr 2027.

---

## 10. Implementation tasks per milestone (concrete work breakdown)

Each milestone below lists the specific files to add/edit, tests to write, exit-criteria checks, and ship vehicle. Follow these in order — each builds on the previous.

### v0.3 implementation tasks (1 day)

**Files added** to `core/src/main/java/com/pulseboard/core/`:
- `HttpClients.kt` — verbatim copy from NMCN. Shared OkHttp instance, base + newBuilder pattern. ~52 LOC.
- `PingTargetId.kt` — verbatim copy. Enum of canonical target IDs (SMARTFLO, SMARTFLO_2, GATEWAY, CLOUDFLARE_DNS, CLOUDFLARE_CDN). The smartflo entries stay in the enum but are NOT auto-included in default `$Targets` — they're available for forks that want them. ~43 LOC.
- `ThroughputProbe.kt` — verbatim copy. 500 KB HTTPS download per flush window. ~211 LOC.
- `WifiGroup.kt` — copy with empty default `LOOKUP` map. Comment notes that forkers populate via `BuildConfig` at v0.4 time. ~70 LOC.

**Files added** to `core/src/test/java/com/pulseboard/core/`:
- `PingTargetIdTest.kt` (4 tests)
- `ThroughputProbeTest.kt` (13 tests)
- `WifiGroupTest.kt` (11 tests, verifying empty-LOOKUP behaviour)
- HttpClients doesn't need its own test file; covered transitively by Throughput + Sheets tests
- Adapt `SheetsUploaderTest` if any tests reference the new HttpClients shared instance (check vs the existing 12 tests).

**Files edited**:
- `SheetsUploader.kt` — refactor to use `HttpClients.newBuilder()` instead of constructing its own OkHttpClient. Mirror NMCN's pattern.
- Remove the doc-comment "smartflo / gateway / cloudflare / dns for CN" in `SheetsUploader.kt` (line ~15-17 — generalise to "one row per target per flush").

**Verification**:
```bash
./gradlew :core:test                 # all 52 + ~28 new = ~80 tests pass
./gradlew :core:lint                 # 0 new errors
git status -- core/                  # only files in the list above changed
```

**Ship**: bump pulseboard versionCode 2 → 3, versionName 0.2.0 → 0.3.0; commit + push; `make ship-internal` to push v0.3.0 AAB to Play Internal.

**Acceptance**: pulseboard `:core` test count goes 52 → ~80; `:core` LOC goes 868 → ~1184; v0.3.0 in Play Internal track.

---

### v0.4 implementation tasks (5 sittings, ~3 weeks)

**Files added**:
- `app/src/main/java/com/vagarylabs/pulseboard/Constants.kt` — split into:
  - **`BuildConfig`-backed** (added to `app/build.gradle.kts` `buildConfigField` lines): `DEFAULT_WEBHOOK_URL`, `DEFAULT_TARGET_IPS`, `EMAIL_DOMAIN_REGEX`, `APP_VERSION_TAG`, `WIFI_GROUP_LOOKUP_JSON` (JSON map of gateway IP → group label, baked in at fork time)
  - **Runtime constants** (Kotlin `const val`): notification channel IDs, prefs keys, magic numbers like `SAMPLE_INTERVAL_MS = 1000L`, `MAX_BUFFER_SAMPLES = 6750`
- `app/src/main/java/com/vagarylabs/pulseboard/PingTarget.kt` — wrap `:core`'s `PingTargetId` enum into the resolve-address + sampler abstraction (~24 LOC — direct port from NMCN).
- `app/src/main/java/com/vagarylabs/pulseboard/service/PingService.kt` — fresh implementation, NMCN as architectural reference. ~400-500 LOC. Differences from NMCN's PingService:
  - Read target list from `SharedPreferences.enabled_target_ids`, not hardcoded
  - Read webhook URL from `SharedPreferences.webhook_url`, not `Constants.WEBHOOK_URL`
  - Skip throughput probe on cellular by default; configurable via `SharedPreferences.throughput_on_cellular = false`
  - No ASM-specific code path; no API key handling
  - Same wall-clock-aligned flusher, same retain-on-failure buffer (5400 samples), same OEM-hardening foreground-service-type combo
- `app/src/main/java/com/vagarylabs/pulseboard/OnboardingActivity.kt` — comprehensive flow per Decision 4. ~400 LOC. Steps:
  1. Welcome screen + identifier field (default policy: any non-empty string per Decision 9; `EMAIL_DOMAIN_REGEX` BuildConfig override)
  2. Webhook URL paste + validation (HTTPS-only; HEAD request to verify it responds)
  3. Optional: Apps Script Quick Deploy (clone-Sheet button per Decision 6 — opens browser to template Sheet's "Make a copy" URL)
  4. Target picker — 5 anycast presets defaulted ON (Cloudflare DNS, Cloudflare CDN, Google DNS, Google API, Microsoft Teams) PLUS auto-gateway-detect (per Decision 5; resolved by `GatewayResolver` on first sample) PLUS custom-IP editor for adding own targets
  5. Optional Slack/email self-notification webhook (per Decision 4 comprehensive scope; if user pastes a webhook, the app POSTs threshold-breach summaries to it)
  6. Wi-Fi group label override (advanced; user can manually override what `WifiGroup.fromGatewayIp` returns)
  7. Theme picker (light/dark/system-default)
  8. Permissions chain (notifications, location-for-Wi-Fi-BSSID, battery exemption — full 8-step playbook from NMCN)
- `app/src/main/java/com/vagarylabs/pulseboard/SecurePrefs.kt` — minimal stub (full implementation in v0.5). For v0.4, plain SharedPreferences is enough.
- `app/src/main/java/com/vagarylabs/pulseboard/NotificationHelper.kt` — direct port from NMCN; ~44 LOC; channel ID + builder.

**Files edited**:
- `app/build.gradle.kts` — add `buildConfigField` lines, add core-ktx dependency variant if needed
- `app/src/main/AndroidManifest.xml` — add INTERNET, ACCESS_NETWORK_STATE, FOREGROUND_SERVICE, FOREGROUND_SERVICE_DATA_SYNC, POST_NOTIFICATIONS, REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, ACCESS_FINE_LOCATION, ACCESS_BACKGROUND_LOCATION, NEARBY_WIFI_DEVICES; declare PingService as foreground service; declare BootReceiver
- `app/src/main/java/com/vagarylabs/pulseboard/MainActivity.kt` — replace stub-with-companion-card with read-only dashboard. Show: last flush timestamp, last-window p95 RTT/jitter/loss per target, Wi-Fi group label, service status + watchdog last-restart, companion-card kept at bottom.
- `app/src/main/res/layout/activity_main.xml` — replace ScrollView+stub with proper dashboard layout (RecyclerView for target rows + Card for service status + companion card at bottom)
- `app/src/main/res/values/strings.xml` — add ~30 strings for onboarding screens

**Tests added** (`app/src/test/`):
- `OnboardingActivityTest.kt` (~5 tests — webhook validation, target picker default state, identifier policy)
- `ConstantsTest.kt` (~3 tests — BuildConfig defaults match expected)
- `PingServiceTest.kt` (~10 tests — mock-context based)

**CI on PR — added at v0.4 per Decision 7**:
- New file: `.github/workflows/android-ci.yml`. Runs on pull_request to main:
  - `./gradlew :core:lint`
  - `./gradlew :core:test`
  - `./gradlew :app:lintDebug`
  - `./gradlew :app:testDebugUnitTest`
- 30-min timeout per job. Caches gradle.

**Verification**:
- Fresh install + onboarding (all 8 steps) → row lands in user's Sheet within 15 min
- `make ship-internal` succeeds; v0.4.0 appears in Play Internal track
- Two test forkers (Chinmay's deploy + one other) confirm fork-and-rebrand works without editing `Constants.kt`
- 95%+ duty-cycle on a Pixel 8 across 24 hr (smoke test)
- CI passes on a sample PR

**Ship**: versionCode 3 → 4, versionName 0.3.0 → 0.4.0, GitHub Release v0.4.0 with full notes.

---

### v0.5 implementation tasks (2 sittings)

**Files added**:
- `app/src/main/java/com/vagarylabs/pulseboard/SecurePrefs.kt` — full EncryptedSharedPreferences wrapper for `user_id`. Migration shim from plain prefs. ~100 LOC. Direct port from NMCN.
- `app/src/main/java/com/vagarylabs/pulseboard/service/WatchdogWorker.kt` — WorkManager periodic worker that pings the last-sampler-heartbeat preference; if stale > 5 min, restarts `PingService`. Cooldown 10 min. ~110 LOC. Direct port from NMCN.
- `app/src/main/java/com/vagarylabs/pulseboard/BootReceiver.kt` — restart on `BOOT_COMPLETED`. ~30 LOC. Direct port.

**Files edited**:
- `OnboardingActivity` — extend Step 8 (permissions) to the full 8-step chain from NMCN's v1.2 hardening (NEARBY_WIFI_DEVICES gate, ACCESS_BACKGROUND_LOCATION gate, OEM autostart intent for Xiaomi/Oppo/Realme/OnePlus/Vivo/Huawei, auto-revoke exemption for SDK 30+).
- `app/src/main/AndroidManifest.xml` — declare PingService with `foregroundServiceType="dataSync|location"` (combined type required for `WifiManager.startScan()` from background on Android 13+); declare WatchdogWorker initializer.
- `PingService` — write last-sampler-heartbeat to SharedPreferences on each flush so WatchdogWorker can detect stale state.

**Tests added**:
- `WatchdogWorkerTest.kt` (~5 tests)
- `SecurePrefsTest.kt` (~3 tests — migration from plain prefs)

**Verification**:
- Survives 7-day soak on Xiaomi/Realme/Vivo/OPPO devices, ≥ 95% duty cycle
- Fresh install on stock Android 14 → service still running 24 hr later through screen-off cycles
- Manual test: kill PingService process → WatchdogWorker restarts within 15 min

**Ship**: versionCode 4 → 5, versionName 0.5.0.

---

### v0.6 implementation tasks (1 day)

**Files added** to `docs/apps-script/`:
- `doPost-v1.gs` — paste-ready Apps Script handler. Mirrors NMCN's `docs/superpowers/apps-script/doPost-v1.3.gs` structure but:
  - Empty `WIFI_GROUP_LOOKUP` map (forkers populate via Apps Script Properties or by editing the script)
  - No CN-specific Sheet IDs hardcoded
  - Defaults to creating a new Sheet tab on first run
  - Server-derives `wifi_group` from `gateway_ip` + `network_type_dominant` (mirrors NMCN's logic with empty default lookup)
  - Server-derives `flush_outcome` (ok / lossy / total_loss / unreachable / sparse / heartbeat / onboarding)
  - Auto-maintains a `Users` tab with per-rep last-seen-version
- `README.md` — deploy instructions for both clone-Sheet and paste-script paths
- `SCHEMA.md` — column reference (the v1.3 + v1.5+ ASM schema, generalised)
- `BACKFILL.gs` (optional one-shot) — populates Users tab from existing historical Sheet data

**Files edited**:
- `OnboardingActivity` Step 3 — wire the "Apps Script Quick Deploy" button to open the GitHub-hosted clone-Sheet URL (template Sheet that copies the script too)

**Verification**:
- Two deploys (Chinmay's own + one external test forker) successfully receive v0.4+ flushes and produce the expected derived columns

**Ship**: versionCode 5 → 6 (only if app code touched; for docs-only change, no version bump). docs/ committed + pushed.

---

### v1.0 implementation tasks (1 day for Production rollout)

No new features. Promote v0.6 through Internal → Alpha → Beta → Production.

**Tasks**:
1. Confirm v0.6 has been in Internal track for 1 week with no regressions
2. `make promote-alpha` — Google review (hours-days first time)
3. After 1-week Alpha soak: `make promote-beta`
4. After 1-week Beta soak: `make promote-prod PCT=0.05` (5% staged)
5. Day 3-7 of production: monitor via `make reviews` + `make status`; confirm crash-free sessions ≥ 99%
6. `make rollout PCT=0.5` then `PCT=1.0` if green
7. Tag `v1.0.0` git tag
8. `gh release create v1.0.0` with full notes (template in `docs/announcements/v1.0.md`)
9. Update `docs/index.md` with "now on Play Store" + Play badge
10. Update README's shield row with Play Store badge
11. Announcement push: HN Show HN, r/networking, r/sysadmin, LinkedIn (use `docs/announcements/v1.0.md` copy)

**Halt protocol**: if crash-free sessions drops below 99% for any 24-hour window during the production rollout, `make halt TRACK=production`. Investigate, fix, ship v1.0.1.

**Acceptance**: 100% prod rollout; ≥ 99% crash-free; sponsor count > 0 (any non-zero is the win signal at this stage).

---

### v1.1 implementation tasks (1-2 days)

**Files added**:
- `scripts/rebrand.sh` — interactive bash script. Prompts for: app name, package name, brand primary colour (hex), logo PNG path, optional default webhook URL, optional default email regex.
  - Renames package (`sed` across `app/src/main/java/com/vagarylabs/pulseboard/` → `app/src/main/java/<new-package>/`)
  - Updates `applicationId` in `app/build.gradle.kts`
  - Regenerates icon set via `scripts/render_brand_assets.py` with the new logo + colour
  - Updates `metadata/android/en-US/title.txt`, `short_description.txt`, `full_description.txt` text
  - Re-renders feature graphic + 12 screenshots (4 phone + 4 7-inch + 4 10-inch) via `render_brand_assets.py`
  - Updates README.md and CLAUDE.md app name + signing-keystore-prefix references
  - Sets `BuildConfig.DEFAULT_WEBHOOK_URL` and `BuildConfig.EMAIL_DOMAIN_REGEX` in `local.properties` (NOT committed) per the user's input
- `docs/FORK_GUIDE.md` — long-form fork-onboarding guide. Discoverable from README. ~200 LOC.

**Test fork**: Build `Cramraika/pulseboard-fork-test` end-to-end via the script in < 10 min. Confirm passes lint, builds signed APK with new identity. Document in repo with the artifact deleted (don't keep the test fork as a permanent repo).

**Ship**: versionCode 6 → 7, versionName 1.0.0 → 1.1.0.

---

### v1.2 implementation tasks (1-1.5 weeks; ALL 4 backends per Decision 12)

**Files added** to `core/src/main/java/com/pulseboard/core/`:
- `Uploader.kt` — interface with `suspend fun upload(payload: List<SheetPayload>): UploadResult` and a sealed `UploadResult` (Success / Retry / Fatal)
- `WebhookUploader.kt` — generic POST to any HTTPS endpoint with optional bearer-token field. ~80 LOC.
- `SupabaseUploader.kt` — direct insert into a Supabase Postgres table via the REST API. Requires Supabase project URL + anon key + table name. ~150 LOC.
- `NotionUploader.kt` — append to a Notion database via Notion API. Requires integration token + database ID. ~200 LOC.

**Files edited**:
- `SheetsUploader.kt` — refactor to implement `Uploader` interface (no behaviour change; just sits behind the new interface)

**`:app` changes**:
- `OnboardingActivity` — add a backend-picker step before the webhook-URL step. Default = Sheets. Picker reveals different sub-flows per backend.
- `Constants.kt` — add `SharedPreferences` keys for `backend_type`, `webhook_bearer_token`, `supabase_url`, `supabase_anon_key`, `supabase_table`, `notion_token`, `notion_database_id`, `notion_property_mapping_json`
- `PingService` — read `SharedPreferences.backend_type` and instantiate the right `Uploader` implementation; same retain-on-failure buffer wraps any uploader

**Tests added** (~40 new tests):
- `WebhookUploaderTest.kt` (~10 tests; mock HTTP server)
- `SupabaseUploaderTest.kt` (~15 tests; mock Supabase REST endpoints)
- `NotionUploaderTest.kt` (~15 tests; mock Notion API)

**Docs**:
- `docs/BACKENDS.md` — overview of the 4 backends + when to pick each
- `docs/backends/SHEETS.md` (existing Apps Script setup, moved here)
- `docs/backends/WEBHOOK.md` (Zapier / Make.com / n8n / PostgREST / generic recipes)
- `docs/backends/SUPABASE.md` (table schema, RLS rules, anon-key security note)
- `docs/backends/NOTION.md` (database schema + integration setup)

**Ship**: versionCode 7 → 8, versionName 1.1.0 → 1.2.0.

---

### v1.3 / v1.4 / v1.5 implementation outlines

**v1.3 (1 day)**: Add Sentry SDK as `compileOnly` in `app/build.gradle.kts`; add `BuildConfig.SENTRY_DSN` (empty default for OSS; forkers paste their DSN at fork-time); init Sentry in `Application.onCreate()` only if DSN is non-empty. New file: `docs/SENTRY.md`.

**v1.4 (2 days)**: Add `desktop_correlation_id` optional column to the v1.3 `SheetPayload` data class. PingService reads `SharedPreferences.desktop_pairing_dir` (default `/sdcard/pulseboard-desktop-pairing/`); if a marker file exists there with a recent mtime (< 5 min old), reads its content as the correlation ID. Update `pulseboard-desktop` separately to add `--match-pulseboard-android <pairing-dir>` flag that emits the marker file. New file: `docs/PAIRING.md`.

**v1.5 (2 days)**: Public-shareable Google Sheet URL with the v0.6 script + Users + APs tabs pre-set up. User clicks "File → Make a copy" + redeploys script with their own Google credentials in 1 click. Update `OnboardingActivity` Step 3 to default to this clone-flow URL (paste-script becomes the advanced disclosure). New file: `docs/apps-script/QUICK_DEPLOY.md`.

---

### v2.0+ implementation outlines

Each v2.x track gets its own dedicated implementation plan doc when it reaches the active milestone (don't pre-write — the design will shift based on what's learned from earlier tracks). New files when the milestones reach active:
- `docs/plans/v2.0-active-diagnostic-parity.md` (created when v1.5 ships)
- `docs/plans/v2.1-enterprise-npm-features.md` (when v2.0 ships)
- `docs/plans/v2.2-self-hosted-infra-features.md` (when v2.1 ships)
- `docs/plans/v2.3-ap-side-wlan-integration.md` (when v2.2 ships)

`docs/COMPETITIVE.md` — created at v1.5 ship. Maintains feature matrix across all tools mentioned in §4 v2.x. Annual audit obligation lands on this doc.

---

## 11. What's NOT in this roadmap (explicit non-goals)

- **Real-time dashboards inside the app.** The Sheet is the dashboard. If you want a real-time view, Google Sheets has live editing — open the Sheet, watch it.
- **Push-notification-style alerts in the app for the user themselves.** Pulseboard's promise is "passive, install-and-forget". Notifications about poor network would be ironic. The Slack/email self-notification webhook in v0.4's onboarding goes to OPS / IT (a team-routing surface), not back to the user.
- **Built-in chat / support / community in-app.** Issues go to GitHub. Discussion in the repo. No in-app social surface.
- **Telemetry by default.** Anything that talks to a Vagary-Labs-controlled endpoint by default is a non-starter. Sentry (v1.3) is opt-in via `BuildConfig`.
- **In-app purchases / monetisation.** OSS, sponsor-funded. No in-app payment flow.
- **A managed SaaS hosting tier under the Pulseboard name.** Not in v2.x. Crosses the OSS-vs-SaaS line; only revisit if sponsor revenue justifies operations cost; would land as a v3.0 conversation, not v2.0.
- **iOS port.** Originally a v2.0 candidate; superseded by the v2.x competitive-coverage program. Sponsor-funded only — if a sponsor commits ≥ $X/mo for iOS, can be slotted in as v2.5 or after v2.3 ships. Not a default roadmap commitment.
- **`com.pulseboard.*` second-party SDK for other apps.** Not Pulseboard's mission.
- **Domain `pulseboard.build`.** Deferred indefinitely per Decision 10.

---

## 12. Cross-references

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

## 13. Document ownership

- **Roadmap author**: Chinmay (2026-04-25)
- **Living doc**: yes — Claude updates as milestones land. Each milestone closing a row in §4 should bump the "where we are today" section + add a Past / Phase History entry in CLAUDE.md.
- **Quarterly review**: 2026-07-25 (next), then quarterly.
- **Decision-maker on direction questions in §6**: Chinmay; Claude assists with research + drafts.
