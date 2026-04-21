# Pulseboard

## Claude Preamble
<!-- VERSION: 2026-04-19-v11 -->
<!-- SYNC-SOURCE: ~/.claude/conventions/universal-claudemd.md -->

**Universal laws** (§4), **MCP routing** (§6), **Drift protocol** (§11), **Dynamic maintenance** (§14), **Capability resolution** (§15), **Subagent SKILL POLICY** (§16), **Session continuity** (§17), **Decision queue** (§17.a), **Attestation** (§18), **Cite format** (§19), **Three-way disagreement** (§20), **Pre-conditions** (§21), **Provenance markers** (§22), **Redaction rules** (§23), **Token budget** (§24), **Tool-failure fallback** (§25), **Prompt-injection rule** (§26), **Append-only discipline** (§27), **BLOCKED_BY markers** (§28), **Stop-loss ladder** (§29), **Business-invariant checks** (§30), **Plugin rent rubric** (§31), **Context ceilings** (§32), **Doc reference graph** (§33), **Anti-hallucination** (§34), **Past+Present+Future body** (§35), **Project trackers** (§36), **Doc ownership** (§37), **Archive-on-delete** (§38), **Sponsor + white-label** (§39), **Doc-vs-code drift** (§40), **Brand architecture** (§41).

**Sources**: `~/.claude/conventions/universal-claudemd.md` (laws, MCP routing, lifecycle, rent rubric, doc-graph, anti-hallucination, brand architecture) + `~/.claude/conventions/project-hygiene.md` (doc placement, cleanup, archive-on-delete, ownership matrix). Read relevant sections before significant work. Re-audit due **2026-07-19**. Sync: `~/.claude/scripts/sync-preambles.py`.

## Project Scope / Vision

**Pulseboard** is a background Android service that samples internet quality (latency, jitter, packet loss) on employee devices and uploads 15-minute aggregates to a Google Sheet. Open-sourced + fork-ready so any small-to-mid team can drop it in and answer "why is my team's network bad today?"

Origin: the repo was internal at Coding Ninjas ("NetworkMonitorCN") to triage VoIP call drops. Renamed to **Pulseboard** 2026-04-19 (Phase 3 fleet rename) when the code went public and the CN-suffix became a drag on adoption. Brand name = "pulse" (health signal) + "board" (dashboard/sheet). Domain `pulseboard.build` pending.

**Vision at pinnacle**: The default install-and-forget "network health vitals" agent for distributed teams — zero-dashboard, spreadsheet-native, works on any Android 8+ device. Currently side-load-only (no Play Store gatekeeping); Play Store listing is a candidate deliverable.

## Status & Tier

- **Tier**: B (maintained) — public since 2026-04-19; v1.0 signed APK attached to GitHub Release
- **Public**: Yes (`Cramraika/pulseboard`, MIT). Renamed from `NetworkMonitorCN` 2026-04-19 (Phase 3)
- **Sponsor-ready**: Yes (`.github/FUNDING.yml` + README CTA)
- **Play Store**: Candidate (not yet published — side-load distribution today; Play Store submission is a roadmap item once fork-and-rebrand toolkit lands)
- **Brand**: **Pulseboard** (Vagary Labs OSS Utilities umbrella; see `universal-claudemd.md` §41)
- **Historical name**: `NetworkMonitorCN` — APK in repo root (`NetworkMonitorCN-v1.0.apk`) is the v1.0 Release artifact; kept for historical reference until next release (will be renamed `Pulseboard-v<ver>.apk` at v1.1)

## Stack

- **Language**: Kotlin (Android, `app/` module)
- **Build**: Gradle Kotlin DSL (`build.gradle.kts`, `settings.gradle.kts`); Gradle wrapper (`gradlew`, `gradlew.bat`)
- **Min SDK**: 26 (Android 8.0+)
- **Target**: current Play SDK
- **Package**: `com.cramraika.pulseboard` (reserved on Play Console)
- **Upload**: Google Apps Script Web App receiving HTTP POST, writing into a Sheet
- **Packaging**: Signed APK (side-load); AAB for Play Store listing when submitted
- **CI**: None yet (candidate for v1.1 — `.github/workflows/android-ci.yml`)

## Build / Test / Deploy

```bash
# Run the shared engine's unit tests (MetricsCalculator, SampleBuffer, SheetsUploader)
./gradlew :core:testDebugUnitTest

# Build CN internal debug APK
./gradlew :app-cn:assembleDebug
# Build Pulseboard public debug APK
./gradlew :app-pulseboard:assembleDebug

# Signed release builds (require keystore props in ~/.gradle/gradle.properties or -P flags)
./gradlew :app-cn:assembleRelease
./gradlew :app-pulseboard:assembleRelease

# Install the CN build on a connected device
adb install app-cn/build/outputs/apk/release/app-cn-release.apk
# Install the Pulseboard stub
adb install app-pulseboard/build/outputs/apk/debug/app-pulseboard-debug.apk

# Lint (per module)
./gradlew :app-cn:lint
./gradlew :app-pulseboard:lint
```

**Release procedure**:

*CN internal build* (`:app-cn`, applicationId `com.codingninjas.networkmonitor`):
1. Bump `versionCode` + `versionName` in `app-cn/build.gradle.kts`
2. `./gradlew :app-cn:assembleRelease` with signing config loaded from `keystore.properties` (gitignored)
3. Rename output APK to `NetworkMonitorCN-v<version>.apk` (in-place upgrade from v1.0 requires keeping the legacy name or CN users re-sideload)
4. Distribute via internal channel (Drive link). Not published on GitHub Releases (CN-internal diagnostic tool).

*Pulseboard public build* (`:app-pulseboard`, applicationId `com.cramraika.pulseboard`):
1. Bump `versionCode` + `versionName` in `app-pulseboard/build.gradle.kts`
2. `./gradlew :app-pulseboard:assembleRelease` with the Pulseboard keystore (separate from CN keystore)
3. Rename output APK to `Pulseboard-v<version>.apk`
4. `gh release create v<version> Pulseboard-v<version>.apk --title "..." --notes "..."` — APK lives in Release, never in git
5. (Eventually) upload AAB to Play Store via `~/.claude/scripts/google-play-publisher.py`

Legacy `NetworkMonitorCN-v1.0.apk` in repo root is the pre-split v1.0 artifact; retained for historical reference.

**Play Store submission (planned)**:
- Use `~/.claude/scripts/google-play-publisher.py` to upload AABs to `com.cramraika.pulseboard` internal track, graduate to closed → open → production.
- Requires Play Console developer account + signed AAB + store listing (icon, screenshots, description, privacy policy URL).

## Module Layout

Three Gradle modules. Shared engine in `:core`, two thin application modules on top.

```
:core                    Android library — com.pulseboard.core
  ├─ PingEngine, SampleBuffer, MetricsCalculator, SheetsUploader, NetworkUtils
  ├─ Sample, NetworkMetrics, SheetPayload (data classes)
  └─ Battle-tested via CN v1.0 field deployment. Pure generic primitives —
     no hardcoded targets, no email gates, no brand strings. Consumers inject config.

:app-cn                  CN internal build — com.codingninjas.networkmonitor
  ├─ Constants (Smartflo IP, @codingninjas.com gate, CN webhook URL)
  ├─ OnboardingActivity (CN email gate), BootReceiver, NotificationHelper
  ├─ service/PingService (CN v1.1 VoIP diagnostic target — see plan below)
  ├─ ui/MainActivity (CN 6-cell dashboard)
  └─ CN-branded res/ (strings, theme name retained for v1.0 continuity)

:app-pulseboard          Public build — com.cramraika.pulseboard
  ├─ Stub today (v0.1.0-stub). v1.1 public enhancements track is SEPARATE from CN's
  │  VoIP diagnostic plan; Pulseboard stays generic — no Smartflo, no CN email gate.
  ├─ Configurable targets at onboarding (user enters their own SIP/gateway/control IPs)
  ├─ Open email allow-list (or none) so any small/mid team can install and run
  └─ Pulseboard-branded res/ with adaptive launcher icon
```

### Key Directories

| Path | Purpose |
|---|---|
| `core/` | Shared Android library — engine + data classes |
| `app-cn/` | CN internal application module (VoIP diagnostic, Smartflo-wired) |
| `app-pulseboard/` | Pulseboard public application module (generic, configurable) |
| `gradle/wrapper/` | Gradle wrapper binaries |
| `gradle/libs.versions.toml` | Version catalog — AGP + plugin aliases |
| `settings.gradle.kts` | `include(":core", ":app-cn", ":app-pulseboard")` |
| `docs/superpowers/plans/` | Plans, including CN VoIP diagnostic v1.1 (CN-only scope) |
| `.github/` | FUNDING.yml (sponsors). CI workflows to be added |
| `LICENSE` | MIT |
| `README.md` | Public-facing — what Pulseboard is, install, usage, fork+rebrand guide |

## External Services / MCPs

- **Upload endpoint**: Google Apps Script Web App (configured per-fork). Sheet receives one row per 15-min aggregate per device.
- **Firebase / Crashlytics**: not integrated (candidate for v1.1)
- **GitHub Sponsors**: `.github/FUNDING.yml` → github.com/sponsors/Cramraika
- **Play Store** (future): managed via `~/.claude/scripts/google-play-publisher.py`
- **MCPs relevant for Claude sessions**: `figma` (UI polish if needed), `stitch` (iconography), `context7` (Android docs lookup). Figma/stitch can be session-disabled per universal §6 when not designing.

## Observability

- **Client-side**: `android.util.Log` (via logcat) — no remote error tracking currently
- **Server-side**: Google Apps Script execution logs (per-invocation, via `console.log` / Logger)
- **Sheet-side**: the Sheet itself is the telemetry dashboard — pivot/chart per device

Roadmap item: optional Sentry/GlitchTip integration behind `ENABLE_CRASHLYTICS=true` gradle prop.

## Dependency Graph

- **Upstream**: Google Apps Script runtime (upload endpoint), Google Sheets (storage), Android OS (background-service lifecycle, battery optimization exemptions)
- **Downstream**: fork-and-rebrand consumers (small teams replacing the reference branding)
- **Siblings**: `host_page` (pattern for product landing pages), `bellring-extension` (pattern for side-loaded distribution beyond Play/App Store)
- **No Claude-specific plugin dependencies** — this is a straight Android OSS project; Claude Code sessions use the universal conventions only

## Fork-and-Rebrand Guide

Pulseboard is explicitly designed for fork-and-rebrand:

1. Fork `Cramraika/pulseboard` to your GitHub org
2. Update `app/build.gradle.kts` — `applicationId`, `versionCode`, `versionName`
3. Update `app/src/main/AndroidManifest.xml` — `<application android:label=`
4. Replace launcher icon in `app/src/main/res/mipmap-*`
5. Parameterize upload endpoint in `MainActivity` via `BuildConfig` (roadmap item #2 — **Fork-and-rebrand toolkit** `scripts/rebrand.sh`)
6. Deploy your own Apps Script receiver to your Sheet
7. Build signed APK with your keystore, side-load or publish

Roadmap item 2 will ship `scripts/rebrand.sh` to automate steps 2-5 via prompts.

## Roadmap

1. **CI/CD** (`.github/workflows/android-ci.yml`): `gradlew lint` + `assembleDebug` on PRs; `assembleRelease` + artifact upload on tag push
2. **Fork-and-rebrand toolkit**: `scripts/rebrand.sh` that swaps app name, colors, launcher icon, upload endpoint via prompts
3. **Multi-endpoint adapter**: support non-Sheets uploads (webhook, Supabase, Firestore) via pluggable writer interface
4. **Self-serve Sheet provisioner**: Apps Script template you deploy once to generate your receiving Sheet + Web App URL in 30 seconds
5. **Optional Sentry/GlitchTip** for crash reporting
6. **Play Store submission**: produce AAB, write store listing, submit via `google-play-publisher.py`
7. **Sponsor tier features**: custom branding pack, on-prem endpoint, enterprise support
8. **`pulseboard.build` brand site**: simple docs + install guide (once domain purchased)

## Past / Phase History

- **2026-04-16** — repo created locally as `NetworkMonitorCN`, v0.x prototyping, icon branding, 6-cell dashboard, OEM swipe-kill recovery
- **2026-04-19 (Phase 1)** — public release: proper Android .gitignore, LICENSE (MIT), FUNDING.yml, GitHub repo created at `Cramraika/NetworkMonitorCN`, v1.0 Release with signed APK attached, README + CLAUDE.md at v9 preamble
- **2026-04-19 (Phase 3)** — renamed `NetworkMonitorCN` → `pulseboard` on GitHub + local disk + git remote. Brand adopted under Vagary Labs OSS Utilities umbrella. Preamble bumped to v11.
- See git log for commit-level history

## Known Limitations

- No CI (lint + build must be verified locally before release)
- Signing keystore not in git — lost keystore = lost ability to ship updates; back it up securely (not in this repo)
- Android doze mode / OEM-specific battery killers can stop the monitoring service; README documents per-OEM workarounds but coverage is not exhaustive
- Google Sheets rate limits (~100 rows/min per Sheet) — fine for single team, breaks at ~500 concurrent devices hitting one Sheet; for scale, swap to a proper backend via the multi-endpoint adapter roadmap item
- No test suite — manual QA on a device grid; unit tests for aggregator logic candidates for v1.1
- Legacy `NetworkMonitorCN-v1.0.apk` at repo root — retained as historical v1.0 artifact; next release uses `Pulseboard-v<ver>.apk` naming
- Hardcoded CN origin strings in some resource files may still exist (to be swept in v1.1 alongside fork-and-rebrand toolkit)

## Security & Secrets

- **Never commit**: `keystore.properties`, `*.jks`, `*.keystore`, `google-services.json`, `local.properties` (all in `.gitignore`)
- **Upload endpoint URL** is hardcoded in `MainActivity` for the reference deployment; forks should parameterize via `BuildConfig` (config to be refactored for whitelabel toolkit)
- **Per-user identification**: uses `user_id` field; documented in README (recommend using email-hash, not PII)

## Doc Maintainers

- **CLAUDE.md**: Chinmay — live contract; update when build/stack/roadmap shifts
- **README.md**: Chinmay — living public marketing; update on each release (rename sweep to Pulseboard scheduled for v1.1)
- **LICENSE / FUNDING.yml**: Chinmay — update only on license/account changes
- **docs/**: per-file owner; see each doc's header

## Deviations from Universal Laws

None. Standard universal §4 common laws apply. One note: this repo lives outside `~/Documents/Github/` (in `~/AndroidStudioProjects/pulseboard/`) by Android Studio convention — `inventory-sync.py` scans both paths.
