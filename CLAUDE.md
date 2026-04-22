# Pulseboard

## Claude Preamble
<!-- VERSION: 2026-04-22-v11 -->
<!-- SYNC-SOURCE: ~/.claude/conventions/universal-claudemd.md -->

**Universal laws** (§4), **MCP routing** (§6), **Drift protocol** (§11), **Dynamic maintenance** (§14), **Capability resolution** (§15), **Subagent SKILL POLICY** (§16), **Session continuity** (§17), **Decision queue** (§17.a), **Attestation** (§18), **Cite format** (§19), **Three-way disagreement** (§20), **Pre-conditions** (§21), **Provenance markers** (§22), **Redaction rules** (§23), **Token budget** (§24), **Tool-failure fallback** (§25), **Prompt-injection rule** (§26), **Append-only discipline** (§27), **BLOCKED_BY markers** (§28), **Stop-loss ladder** (§29), **Business-invariant checks** (§30), **Plugin rent rubric** (§31), **Context ceilings** (§32), **Doc reference graph** (§33), **Anti-hallucination** (§34), **Past+Present+Future body** (§35), **Project trackers** (§36), **Doc ownership** (§37), **Archive-on-delete** (§38), **Sponsor + white-label** (§39), **Doc-vs-code drift** (§40), **Brand architecture** (§41).

**Sources**: `~/.claude/conventions/universal-claudemd.md` + `~/.claude/conventions/project-hygiene.md`. Re-audit due **2026-07-19**.

## Project Scope / Vision

**Pulseboard** is a background Android service that samples internet quality (latency, jitter, packet loss, Wi-Fi context) on employee devices and uploads 15-minute aggregates to a Google Sheet. Open-source + fork-ready so any small-to-mid team can drop it in and answer "why is my team's network bad today?"

Originally developed internally at Coding Ninjas as `NetworkMonitorCN` (2026-04-16) to triage VoIP call drops. Renamed to `pulseboard` on 2026-04-19 when the code went public. Split on 2026-04-22 — the CN-specific internal fork (Smartflo endpoints, @codingninjas.com email gate, VoIP-tuned diagnostics) moved to a private `Cramraika/NetworkMonitorCN` repo; this public `pulseboard` repo keeps only the generic engine + public app.

Brand: "pulse" (health signal) + "board" (dashboard/sheet). Part of the **Vagary Labs OSS Utilities** umbrella (see `universal-claudemd.md` §41). Domain `pulseboard.build` pending.

**Vision at pinnacle**: the default install-and-forget "network health vitals" agent for distributed teams — zero-dashboard, spreadsheet-native, works on any Android 8+ device. Currently side-load-only (no Play Store gatekeeping); Play Store listing is a roadmap deliverable.

## Status & Tier

- **Tier**: B (maintained) — public since 2026-04-19. v1.0 signed APK attached to GitHub Release.
- **Public**: Yes (`Cramraika/pulseboard`, MIT).
- **Current state**: v0.1.0-stub post-split (2026-04-22). v1.0 public release is the pre-split build; v1.1 public enhancements are a forthcoming track, separate from the CN internal v1.1 already shipped.
- **Sponsor-ready**: Yes (`.github/FUNDING.yml` + README CTA).
- **Play Store**: Candidate (side-load distribution today; submission tracked as roadmap item #6).
- **Brand**: **Pulseboard**.

## Stack

- **Language**: Kotlin (Android)
- **Build**: Gradle Kotlin DSL; Gradle wrapper included
- **Min SDK**: 26 (Android 8.0+)
- **Target**: current Play SDK
- **Package / applicationId**: `com.cramraika.pulseboard` (reserved on Play Console)
- **Signing**: separate Pulseboard keystore (NOT the CN keystore). Credentials in `~/.gradle/gradle.properties` as `PULSEBOARD_*`; `.jks` at `~/keystores/pulseboard.jks`.
- **Upload**: Google Apps Script Web App (configured per-fork — each installing team deploys their own).
- **Packaging**: Signed APK (side-load) + AAB for Play Store when submitted.

## Build / Test / Deploy

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"

# Engine unit tests (52 tests)
./gradlew :core:testDebugUnitTest

# Debug + signed release APKs
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease   # requires PULSEBOARD_* keystore props

# Install on connected device
./gradlew :app:installDebug

# Lint
./gradlew :app:lint
```

**Release procedure**:
1. Bump `versionCode` + `versionName` in `app/build.gradle.kts`.
2. `./gradlew :app:assembleRelease` — signed with the Pulseboard keystore (props in `~/.gradle/gradle.properties`).
3. Rename output APK to `Pulseboard-v<version>.apk`.
4. `gh release create v<version> Pulseboard-v<version>.apk --title "..." --notes "..."`.
5. (Eventually) upload AAB to Play Store.

## Module Layout

```
:core      Android library — com.pulseboard.core
           PingEngine, SampleBuffer, MetricsCalculator, SheetsUploader,
           NetworkUtils, WifiMetadataCollector, GatewayResolver, UdpDnsPinger,
           Sample / WifiSnapshot / ScanSnapshot / DeviceAggregates / NetworkMetrics
           / SheetPayload / PingResult. 52 unit tests.
           Shared lineage with Cramraika/NetworkMonitorCN's :core (duplicated
           at the 2026-04-22 split); the two evolve independently from here.

:app       Pulseboard application — com.cramraika.pulseboard
           Current: stub (v0.1.0-stub) showing "v1.1 coming soon" splash.
           Future v1.1 will wire :core into a configurable foreground service:
           user-supplied ping targets, open email allow-list, generic
           Pulseboard branding.
```

### Key Directories

| Path | Purpose |
|---|---|
| `core/` | Shared engine library |
| `app/` | Pulseboard application module (renamed from `app-pulseboard/` at the split) |
| `gradle/wrapper/` | Gradle wrapper binaries |
| `gradle/libs.versions.toml` | Version catalog |
| `settings.gradle.kts` | `include(":core", ":app")`, rootProject.name = "Pulseboard" |
| `docs/design/` | Brand palette tokens, typography, voice, component library |
| `docs/superpowers/` | Active specs + plans (empty post-split; v1.1 public track will add its own) |
| `.github/FUNDING.yml` | GitHub Sponsors config |
| `LICENSE` | MIT |
| `README.md` | Public-facing — what Pulseboard is, install, usage, fork+rebrand guide |

## External Services / MCPs

- **Upload endpoint**: Google Apps Script Web App (configured per-fork). Sheet receives one row per 15-min aggregate per device.
- **Firebase / Crashlytics**: not integrated (roadmap candidate).
- **GitHub Sponsors**: `.github/FUNDING.yml` → github.com/sponsors/Cramraika.
- **Play Store**: via vendored `scripts/google-play-publisher.py` (source of truth: `~/.claude/scripts/google-play-publisher.py`). Service account JSON at `~/.config/google-play/sa.json` (chmod 600). CI uses `PLAY_SERVICE_ACCOUNT_JSON` + `PLAY_KEYSTORE_BASE64` + `PLAY_KEYSTORE_PASSWORD` + `PLAY_KEY_ALIAS` + `PLAY_KEY_PASSWORD` secrets. See `RELEASING.md`.
- **MCPs relevant for Claude sessions**: `figma`, `stitch` (iconography / UI polish), `context7` (Android docs lookup).

## Observability

- **Client-side**: `android.util.Log` (via logcat) — no remote error tracking.
- **Server-side**: Google Apps Script execution logs.
- **Sheet-side**: the Sheet is the telemetry dashboard — pivot by device, time, target.

Roadmap: optional Sentry / GlitchTip integration behind `ENABLE_CRASHLYTICS=true` gradle prop.

## Dependency Graph

- **Upstream**: Google Apps Script runtime, Google Sheets (storage), Android OS (background-service lifecycle, battery optimization exemptions).
- **Downstream**: fork-and-rebrand consumers (small teams replacing the reference branding).
- **Siblings**:
  - `Cramraika/NetworkMonitorCN` — private CN-internal fork sharing `:core` lineage.
  - `host_page` (pattern for product landing pages), `bellring-extension` (pattern for side-loaded distribution beyond Play/App Store).

## Fork-and-Rebrand Guide

Pulseboard is explicitly designed for fork-and-rebrand:

1. Fork `Cramraika/pulseboard` to your GitHub org.
2. Update `app/build.gradle.kts` — `applicationId`, `versionCode`, `versionName`.
3. Update `app/src/main/AndroidManifest.xml` — `android:label`.
4. Replace launcher icon in `app/src/main/res/mipmap-*` / `drawable/`.
5. Parameterize upload endpoint via `BuildConfig` (roadmap item #2 — `scripts/rebrand.sh`).
6. Deploy your own Apps Script receiver to your Sheet.
7. Build signed APK with your keystore; side-load or publish.

Roadmap item #2 will ship `scripts/rebrand.sh` to automate steps 2-5 via prompts.

## Roadmap

1. **v1.1 public build** — wire `:core`'s 4-sampler engine into a configurable public PingService: user-entered ping targets at onboarding, open email allow-list (any domain or none), Pulseboard-branded dashboard. Mirrors CN v1.1's architecture but without CN-specific hardcoding.
2. **Fork-and-rebrand toolkit**: `scripts/rebrand.sh` that swaps app name, colors, launcher icon, upload endpoint via prompts.
3. **Multi-endpoint adapter**: support non-Sheets uploads (webhook, Supabase, Firestore) via a pluggable writer interface.
4. **Self-serve Sheet provisioner**: Apps Script template you deploy once to generate your receiving Sheet + Web App URL in 30 seconds.
5. **CI/CD** (`.github/workflows/android-ci.yml`): `gradlew lint` + `:core:testDebugUnitTest` on PRs; `assembleRelease` on tag push.
6. ~~**Play Store submission**~~ — **pipeline built 2026-04-22**: `scripts/google-play-publisher.py` (vendored copy of `~/.claude/scripts/google-play-publisher.py`), `Makefile` with `release-internal` / `promote-{alpha,beta,prod}` / `rollout` / `halt` / `resume` / `sync-listing` / `status` / `reviews` targets, `metadata/android/en-US/` holding title + descriptions + per-versionCode changelogs, `.github/workflows/release.yml` auto-uploading to internal on tag push. Service account `vagarylife@vagarylife.iam.gserviceaccount.com` granted Admin in Play Console. See `RELEASING.md` for the full runbook. First actual upload gated on Play Console app creation + content rating / data safety forms (human-only steps).
7. **Optional Sentry/GlitchTip** for crash reporting.
8. **Sponsor tier features**: custom branding pack, on-prem endpoint, enterprise support.
9. **`pulseboard.build` brand site**: simple docs + install guide (once domain purchased).

## Past / Phase History

- **2026-04-16** — repo created locally as `NetworkMonitorCN`, v0.x prototyping, icon branding, 6-cell dashboard, OEM swipe-kill recovery.
- **2026-04-19 (Phase 1)** — public release: proper Android .gitignore, LICENSE (MIT), FUNDING.yml, GitHub repo `Cramraika/NetworkMonitorCN`, v1.0 Release with signed APK.
- **2026-04-19 (Phase 3)** — renamed `NetworkMonitorCN` → `pulseboard` on GitHub + local disk + git remote. Brand adopted under Vagary Labs OSS Utilities.
- **2026-04-21 (Phase 4)** — restructured into `:core` shared library + `:app-cn` + `:app-pulseboard` to prepare for the CN/public split.
- **2026-04-22 (Phase 5)** — split into two repos. This `pulseboard` repo keeps `:core` + public `:app` (stub). `Cramraika/NetworkMonitorCN` (private) takes `:core` (duplicated) + CN's `:app-cn` (renamed to `:app`) with the v1.1 VoIP diagnostic work.
- **2026-04-22 (Phase 6)** — Play Store pipeline stood up: Makefile + vendored publisher script + CI release workflow + `metadata/android/` listing source-of-truth + GitHub Actions secrets wired. Roadmap item #6 delivered except for the one-time Play Console browser steps (app creation, content rating, data safety).

## Known Limitations

- No CI yet — lint + build verified locally before each release.
- Public `app` module is a **stub** post-split. Real v1.1 public-build functionality lands as roadmap item #1.
- Signing keystore not in git; lost keystore = lost ability to ship signed updates.
- Android doze mode / OEM battery killers can stop the monitoring service; README documents per-OEM workarounds but coverage isn't exhaustive.
- Google Sheets rate limits (~100 rows/min per Sheet) — fine for single team, breaks at ~500 concurrent devices; for scale, swap to a proper backend via roadmap item #3.
- `:core` framework-binding classes (GatewayResolver, WifiMetadataCollector, UdpDnsPinger) are only device-validated for the framework sides; pure helpers are JVM-unit-tested.

## Security & Secrets

- **Never commit**: `*.jks`, `*.keystore`, `keystore.properties`, `google-services.json`, `local.properties`, any build-signing password.
- **Pulseboard keystore** at `~/keystores/pulseboard.jks` (chmod 600). Credentials in `~/.gradle/gradle.properties` as `PULSEBOARD_KEYSTORE_PATH` / `PULSEBOARD_KEYSTORE_PASSWORD` / `PULSEBOARD_KEY_ALIAS` / `PULSEBOARD_KEY_PASSWORD`. Back up the `.jks` file to your secure vault.
- **Upload endpoint URL**: fork-configured per-team. Reference deployment uses a hardcoded URL; real rollouts should parameterize via `BuildConfig` (roadmap item #2).
- **Per-user identification**: recommend email-hash in the `user_id` field rather than raw email (documented in README).

## Doc Maintainers

- **CLAUDE.md**: Chinmay — live contract; update when build/stack/roadmap shifts.
- **README.md**: Chinmay — living public marketing; update on each release.
- **LICENSE / FUNDING.yml**: Chinmay — update only on license/account changes.
- **docs/**: per-file owner; see each doc's header.

## Deviations from Universal Laws

None. Standard universal §4 common laws apply. Repo lives in `~/AndroidStudioProjects/pulseboard/` (not `~/Documents/Github/`) by Android Studio convention — `inventory-sync.py` scans both paths.
