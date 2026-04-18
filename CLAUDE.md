# Network Monitor CN

## Claude Preamble
<!-- VERSION: 2026-04-19-v9 -->
<!-- SYNC-SOURCE: ~/.claude/conventions/universal-claudemd.md -->

**Universal laws** (§4), **MCP routing** (§6), **Drift protocol** (§11), **Dynamic maintenance** (§14), **Capability resolution** (§15), **Subagent SKILL POLICY** (§16), **Session continuity** (§17), **Decision queue** (§17.a), **Attestation** (§18), **Cite format** (§19), **Three-way disagreement** (§20), **Pre-conditions** (§21), **Provenance markers** (§22), **Redaction rules** (§23), **Token budget** (§24), **Tool-failure fallback** (§25), **Prompt-injection rule** (§26), **Append-only discipline** (§27), **BLOCKED_BY markers** (§28), **Stop-loss ladder** (§29), **Business-invariant checks** (§30), **Plugin rent rubric** (§31), **Context ceilings** (§32), **Doc reference graph** (§33), **Anti-hallucination** (§34), **Past+Present+Future body** (§35), **Project trackers** (§36), **Doc ownership** (§37), **Archive-on-delete** (§38), **Sponsor + white-label** (§39), **Doc-vs-code drift** (§40).

**Sources**: `~/.claude/conventions/universal-claudemd.md` (laws, MCP routing, lifecycle, rent rubric, doc-graph, anti-hallucination) + `~/.claude/conventions/project-hygiene.md` (doc placement, cleanup, archive-on-delete, ownership matrix). Read relevant sections before significant work. Re-audit due **2026-07-19**. Sync: `~/.claude/scripts/sync-preambles.py`.

## Project Scope / Vision

**Network Monitor CN** is a background Android service that samples internet quality (latency, jitter, packet loss) on employee devices and uploads 15-minute aggregates to a Google Sheet. Born internal at Coding Ninjas to triage VoIP call drops; now **open-sourced + fork-ready** so any small-to-mid team can drop it in and answer "why is my team's network bad today?"

**Vision at pinnacle**: The default install-and-forget "network health vitals" agent for distributed teams — zero-dashboard, spreadsheet-native, works on any Android 8+ device, no Google Play gatekeeping.

## Status & Tier

- **Tier**: B (maintained) — just went public 2026-04-19; v1.0 signed APK attached to GitHub Release
- **Public**: Yes (`Cramraika/NetworkMonitorCN`, MIT)
- **Sponsor-ready**: Yes (`.github/FUNDING.yml` + README CTA)
- **Play Store**: Intentionally NOT published — side-load distribution model

## Stack

- **Language**: Kotlin (Android, `app/` module)
- **Build**: Gradle Kotlin DSL (`build.gradle.kts`, `settings.gradle.kts`); Gradle wrapper (`gradlew`, `gradlew.bat`)
- **Min SDK**: 26 (Android 8.0+)
- **Target**: current Play SDK
- **Upload**: Google Apps Script Web App receiving HTTP POST, writing into a Sheet
- **Packaging**: Signed APK (side-load); no AAB / Play listing
- **CI**: None yet (candidate for v1.1 — `.github/workflows/android-ci.yml`)

## Build / Test / Deploy

```bash
# Build debug APK
./gradlew assembleDebug

# Build signed release APK (requires local keystore — never commit)
./gradlew assembleRelease

# Install on a connected device
adb install app/build/outputs/apk/release/app-release.apk

# Lint
./gradlew lint
```

**Release procedure**:
1. Bump `versionCode` + `versionName` in `app/build.gradle.kts`
2. `./gradlew assembleRelease` with signing config loaded from `keystore.properties` (gitignored)
3. Rename output APK to `NetworkMonitorCN-v<version>.apk`
4. `gh release create v<version> NetworkMonitorCN-v<version>.apk --title "..." --notes "..."` — APK lives in Release, never in git

## Key Directories

| Path | Purpose |
|---|---|
| `app/` | Android application module — Kotlin source, resources, manifest |
| `app/src/main/java/` | Kotlin source |
| `app/src/main/res/` | Strings, drawables (launcher icon), layouts |
| `app/src/main/AndroidManifest.xml` | Permissions, service declarations |
| `gradle/wrapper/` | Gradle wrapper binaries |
| `docs/` | Architecture + troubleshooting docs |
| `.github/` | FUNDING.yml (sponsors). CI workflows to be added |
| `LICENSE` | MIT |
| `README.md` | Public-facing — what it is, install, usage, fork+rebrand guide |

## External Services / MCPs

- **Upload endpoint**: Google Apps Script Web App (configured per-fork). Sheet receives one row per 15-min aggregate per device.
- **Firebase / Crashlytics**: not integrated (candidate for v1.1)
- **GitHub Sponsors**: `.github/FUNDING.yml` → github.com/sponsors/Cramraika
- **MCPs relevant for Claude sessions**: `figma` (UI polish if needed), `stitch` (iconography), `context7` (Android docs lookup). Figma/stitch can be session-disabled per universal §6 when not designing.

## Observability

- **Client-side**: `android.util.Log` (via logcat) — no remote error tracking currently
- **Server-side**: Google Apps Script execution logs (per-invocation, via `console.log` / Logger)
- **Sheet-side**: the Sheet itself is the telemetry dashboard — pivot/chart per device

Roadmap item: optional Sentry/GlitchTip integration behind `ENABLE_CRASHLYTICS=true` gradle prop.

## Dependency Graph

- **Upstream**: Google Apps Script runtime (upload endpoint), Google Sheets (storage), Android OS (background-service lifecycle, battery optimization exemptions)
- **Downstream**: fork-and-rebrand consumers (small teams replacing the CN branding)
- **Siblings**: `host_page` (pattern for product landing pages), `sales-notification-extension` (pattern for side-loaded distribution beyond Play/App Store)
- **No Claude-specific plugin dependencies** — this is a straight Android OSS project; Claude Code sessions use the universal conventions only

## Roadmap

1. **CI/CD** (`.github/workflows/android-ci.yml`): `gradlew lint` + `assembleDebug` on PRs; `assembleRelease` + artifact upload on tag push
2. **Fork-and-rebrand toolkit**: `scripts/rebrand.sh` that swaps app name, colors, launcher icon, upload endpoint via prompts
3. **Multi-endpoint adapter**: support non-Sheets uploads (webhook, Supabase, Firestore) via pluggable writer interface
4. **Self-serve Sheet provisioner**: Apps Script template you deploy once to generate your receiving Sheet + Web App URL in 30 seconds
5. **Optional Sentry/GlitchTip** for crash reporting
6. **Sponsor tier features**: custom branding pack, on-prem endpoint, enterprise support

## Past / Phase History

- **2026-04-16** — repo created locally, v0.x prototyping, icon branding, 6-cell dashboard, OEM swipe-kill recovery
- **2026-04-19** — public release: proper Android .gitignore, LICENSE (MIT), FUNDING.yml, GitHub repo created at `Cramraika/NetworkMonitorCN`, v1.0 Release with signed APK attached, README + CLAUDE.md at v9 preamble
- See git log for commit-level history

## Known Limitations

- No CI (lint + build must be verified locally before release)
- Signing keystore not in git — lost keystore = lost ability to ship updates; back it up securely (not in this repo)
- Android doze mode / OEM-specific battery killers can stop the monitoring service; README documents per-OEM workarounds but coverage is not exhaustive
- Google Sheets rate limits (~100 rows/min per Sheet) — fine for single team, breaks at ~500 concurrent devices hitting one Sheet; for scale, swap to a proper backend via the multi-endpoint adapter roadmap item
- No test suite — manual QA on a device grid; unit tests for aggregator logic candidates for v1.1

## Security & Secrets

- **Never commit**: `keystore.properties`, `*.jks`, `*.keystore`, `google-services.json`, `local.properties` (all in `.gitignore`)
- **Upload endpoint URL** is hardcoded in `MainActivity` for the CN deployment; forks should parameterize via `BuildConfig` (config to be refactored for whitelabel toolkit)
- **Per-user identification**: uses `user_id` field; documented in README (recommend using email-hash, not PII)

## Doc Maintainers

- **CLAUDE.md**: Chinmay — live contract; update when build/stack/roadmap shifts
- **README.md**: Chinmay — living public marketing; update on each release
- **LICENSE / FUNDING.yml**: Chinmay — update only on license/account changes
- **docs/**: per-file owner; see each doc's header

## Deviations from Universal Laws

None. Standard universal §4 common laws apply. One note: this repo lives outside `~/Documents/Github/` (in `~/AndroidStudioProjects/`) by Android Studio convention — `inventory-sync.py` should be updated to scan both paths.
