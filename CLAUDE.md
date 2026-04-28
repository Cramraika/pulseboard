# pulseboard (Android) — CLAUDE.md v2

**Date:** 2026-04-28 (S11B authoring)
**Supersedes:** v1 (revision 2026-04-22-v11; located at `~/AndroidStudioProjects/pulseboard/`)
**Tier:** B (maintained) — Play Store candidate. **Bare-fork stack-up cluster (off-fleet by design)** per dispatch §1.m.

## Identity & Role

`pulseboard` (Android) is a **background Android service that samples internet quality** (latency, jitter, packet loss, Wi-Fi context) on employee devices and uploads 15-minute aggregates to a Google Sheet. Open-source + fork-ready for any small-to-mid team to drop in and answer "why is my team's network bad today?"

Originally `NetworkMonitorCN` 2026-04-16 (CN-internal VoIP triage); renamed to `pulseboard` 2026-04-19 when public; split 2026-04-22 — CN-specific internal fork moved to private `Cramraika/NetworkMonitorCN`; this public `pulseboard` keeps generic engine + public app.

Vagary Labs brand: **Pulseboard** (OSS Utilities). Domain `pulseboard.build` pending.

Sibling: `~/Documents/Github/pulseboard-desktop/` (Windows companion; off-fleet local-only PowerShell tool).

## Coverage Today (post-PCN-S6/S7/S11A)

Per matrix row `pulseboard` — **bare-fork stack-up cluster (~7 T-cells, off-fleet by design)**:

```
Mail | DNS | RP | Orch | Obs | Backup | Sup | Sec | Tun | Err | Wflw | Spec
 NA  | T   | NA | NA   | NA  | NA     | T   | U   | NA  | T   | NA   | NA
```

- USED: Sec (no telemetry, no auth surface; signing keystore + Pulseboard credentials in `~/.gradle/gradle.properties`).
- TRIGGER-TO-WIRE: DNS (CF zone for `pulseboard.build` once purchased), Sup (Cosign / signed APK + AAB Play Store path), Err (GlitchTip if observability wired post-Play-Store-submit).
- NA across all VPS dimensions (Android app distributed via GitHub Release / Play Store; not a VPS-hosted service). Pulseboard is **off-fleet by design**.

## What's Wired

- **GitHub:** `Cramraika/pulseboard` (public, MIT); v1.0 signed APK attached to GitHub Release.
- **Play Store:** Candidate (side-load distribution today; submission tracked as roadmap #6).
- **Sponsor:** `.github/FUNDING.yml` + README CTA.
- **CI:** Android Gradle build + 52 engine unit tests (`./gradlew :core:testDebugUnitTest`).
- **Signing:** separate Pulseboard keystore (NOT CN); credentials `PULSEBOARD_*` in `~/.gradle/gradle.properties`; `.jks` at `~/keystores/pulseboard.jks`.
- **applicationId:** `com.vagarylabs.pulseboard` (renamed 2026-04-22 from `com.cramraika.pulseboard` — pre-first-upload, zero Play Store cost).

## Stack

- **Language:** Kotlin (Android)
- **Build:** Gradle Kotlin DSL; Gradle wrapper
- **Min SDK:** 26 (Android 8.0+); Target: current Play SDK
- **Upload:** Google Apps Script Web App (configured per-fork — each installing team deploys their own)
- **Packaging:** Signed APK (side-load) + AAB (Play Store when submitted)

## Roadmap (post-S11A) — bare-fork stack-up

### Cluster 3 — Cosign per-repo CI fanout
- T (post host_page PR #50 merge); applies to APK + AAB build pipeline (CI signing pre-Play-Store-submit).

### Bare-fork stack-up (Cluster 1; off-fleet variant per dispatch §1.m)
~7 T-cells (excluding the ~7 NA cells specific to non-VPS-hosted Android distribution):
- DNS — once `pulseboard.build` purchased.
- Sup — Cosign for build pipeline + Trivy if container-packaged for self-host variant.
- Err — GlitchTip Android SDK (when crash-reporting wired post-Play-Store-submit).
- Renovate — for Gradle dependencies (Kotlin gradle plugin, Compose, Navigation, etc.).
- Backup — for sponsor-funded distribution mirror (GitHub Releases is canonical).
- Mail/RP/Orch/Obs/Tun/Wflw/Spec — NA for off-fleet Android app.

### Existing roadmap (carried forward)
1. v1.1 public release (track separate from CN internal v1.1 already shipped).
2. Linux + Mac diagnostic ports (sibling `pulseboard-desktop` is Windows; v1.1 Linux, v1.2 macOS).
3. Tauri/Electron GUI wrapper (sponsor-funded).
4. Jupyter analysis notebook in `samples/`.
5. More provider profiles in `docs/VOIP_PROVIDERS.md` (sibling repo).
6. Play Store listing (submission).

## ADR Compliance

- **ADR-038 personal-scope:** ✓ — Cramraika org public; MIT; off-fleet by design.
- **ADR-033 Renovate canonical:** T (pending bare-fork stack-up; Renovate for Gradle deps).
- **ADR-041 Trivy gate:** N/A or T (Android app; Trivy not directly applicable; if self-host variant added, T).
- **SOC2 risk-register cross-ref:** N/A (no customer data; per-fork Google Apps Script Web App is fork-owner's responsibility).

## Cross-references

- `platform-docs/05-architecture/part-B-service-appendices/products/pulseboard.md` (or specialized tier; pending S11B authoring)
- Sibling repo: `~/Documents/Github/pulseboard-desktop/CLAUDE.md` (Windows companion)
- Origin: `~/AndroidStudioProjects/NetworkMonitorCN/` (private CN-internal, post-split 2026-04-22)
- `~/.claude/conventions/universal-claudemd.md` §41 brand architecture (Pulseboard)
- `~/.claude/conventions/repo-inventory.md`

## Migration from v1

**Major v1 → v2 changes:**
1. Per-project-service-matrix row added — **bare-fork stack-up cluster (~7 T-cells, off-fleet by design)**.
2. Cluster 3 Cosign per-repo CI fanout queued post-PR-#50 (build pipeline).
3. CN-internal split (2026-04-22) trajectory preserved (NetworkMonitorCN private fork separate).
4. Pulseboard brand architecture §41 cited.
5. Domain `pulseboard.build` pending — DNS T-cell.
