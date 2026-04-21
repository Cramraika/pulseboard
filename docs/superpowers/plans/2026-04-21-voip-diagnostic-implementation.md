# CN v1.1 — VoIP Diagnostic Enhancement (Implementation Plan)

**Spec**: [`../specs/2026-04-21-voip-diagnostic-design.md`](../specs/2026-04-21-voip-diagnostic-design.md)
**Source plan**: [`./2026-04-21-voip-diagnostic-enhancement.md`](./2026-04-21-voip-diagnostic-enhancement.md) (kept as the problem-framing reference)
**Target**: `:app-cn` module only (`com.codingninjas.networkmonitor` → v1.1). `:core` gets generic engine additions. `:app-pulseboard` untouched.
**Branch**: `voip-diagnostic-v1.1`
**Final version**: `:app-cn` `versionCode=2`, `versionName=1.1`

This plan is 10 commit-sized steps + 3 non-code operations (Apps Script deploy, on-device validation, release). Each code step is independently green: tests pass, both apps build.

---

## Prerequisites

```bash
git switch -c voip-diagnostic-v1.1
./gradlew :core:testDebugUnitTest           # verify step-1 baseline: 26 tests green
./gradlew :app-cn:assembleDebug             # verify :app-cn still compiles
```

No version bump at branch creation — versionCode stays `1` through step 10 to avoid install-confusion while developing.

---

## Step 1 — `:core`: Sample model extensions + unreachable-aware aggregate

**Files:**
- `core/src/main/java/com/pulseboard/core/SampleBuffer.kt` — extend `Sample` data class with three defaulted fields: `target: String = "default"`, `unreachable: Boolean = false`, `wifi: WifiSnapshot? = null`. (`WifiSnapshot` declared in step 2; this step adds the field as `Any?` placeholder or forward-declares via a `WifiSnapshot` stub — **actually** do this step second if that's cleaner; see Step 2 note.)
- `core/src/main/java/com/pulseboard/core/MetricsCalculator.kt` — in `aggregate()`: filter `unreachable=true` before computing loss, emit `packet_loss_pct = null` when `reachableSamplesCount == 0`.
- `core/src/main/java/com/pulseboard/core/MetricsCalculator.kt` — extend `NetworkMetrics` with `reachableSamplesCount: Int = samplesCount` (defaulted so existing tests still compile).
- `core/src/test/java/com/pulseboard/core/MetricsCalculatorTest.kt` — add 3 tests: `partial unreachable excluded from loss denominator`, `all unreachable yields packet_loss_pct null and reachable_samples_count zero`, `mixed unreachable+successful yields correct partial loss`.

**Definition of done:**
- `./gradlew :core:testDebugUnitTest` — all 29 tests (26 existing + 3 new) green.
- `./gradlew :app-cn:assembleDebug` — builds unchanged (v1.0 `Sample` construction still works because new fields default).

**Commit message:** `feat(core): Sample unreachable flag + aggregate null-loss rule`

**Note on step ordering:** Do Step 2 before Step 1 if you want `WifiSnapshot` defined before `Sample` references it. Swap the commits if the IDE flags a forward-reference issue. Both orderings end with the same state.

---

## Step 2 — `:core`: Wi-Fi data classes + `WifiMetadataCollector`

**Files:**
- `core/src/main/java/com/pulseboard/core/WifiSnapshot.kt` — new data class with 8 fields per spec §3.2.
- `core/src/main/java/com/pulseboard/core/ScanSnapshot.kt` — new data class per spec §3.3.
- `core/src/main/java/com/pulseboard/core/WifiMetadataCollector.kt` — new class with `snapshot()` + `scanSnapshot()` + package-visible helpers `stripSsidQuotes` + `mapNetworkType`.
- `core/src/test/java/com/pulseboard/core/WifiMetadataCollectorTest.kt` — new file with 4 tests on `stripSsidQuotes`: strip quoted, preserve unquoted, preserve `<unknown ssid>` sentinel, null returns null.

**Definition of done:**
- 33 `:core` tests green (29 + 4).
- Helper-function tests pass; `snapshot()` and `scanSnapshot()` are validated on device in Step 12.

**Commit message:** `feat(core): WifiMetadataCollector with snapshot + startScan support`

---

## Step 3 — `:core`: `GatewayResolver`

**Files:**
- `core/src/main/java/com/pulseboard/core/GatewayResolver.kt` — new class per spec §3.5. `currentGateway()` + `registerOnChange()` with 1s debounce via `android.os.Handler.postDelayed`. Package-visible helper `pickDefaultIPv4Gateway(routes: List<RouteInfo>): String?`.
- `core/src/test/java/com/pulseboard/core/GatewayResolverTest.kt` — new file, 4 tests on the pure helper: default IPv4 picked, no default route returns null, IPv6-only default returns null, multiple defaults picks first.

**Definition of done:**
- 37 `:core` tests green.
- `NetworkCallback` registration validated on device in Step 12.

**Commit message:** `feat(core): GatewayResolver with IPv4 default route picker`

---

## Step 4 — `:core`: `UdpDnsPinger`

**Files:**
- `core/src/main/java/com/pulseboard/core/UdpDnsPinger.kt` — new class per spec §3.6. Uses `java.net.DatagramSocket` + a minimal hand-built DNS query byte buffer (no external DNS library). Returns `PingResult` (reuses existing data class).
- `core/src/test/java/com/pulseboard/core/UdpDnsPingerTest.kt` — new file, 3 tests: happy-path (point at a local `DatagramSocket` echo server), timeout on unreachable resolver (closed socket), malformed response yields `success=false`.

**Definition of done:**
- 40 `:core` tests green.

**Commit message:** `feat(core): UdpDnsPinger over DatagramSocket`

---

## Step 5 — `:core`: `DeviceAggregates` + MetricsCalculator extensions

**Files:**
- `core/src/main/java/com/pulseboard/core/DeviceAggregates.kt` — new data class with 12 fields per spec §3.7 (includes `vpnActive: Boolean`, `primaryFrequencyMhz`, `primaryLinkSpeedMbps`).
- `core/src/main/java/com/pulseboard/core/MetricsCalculator.kt` — add `gapsCount(samples, thresholdMs = 3000L): Int` and `deviceLevelAggregates(samples): DeviceAggregates`.
- `core/src/test/java/com/pulseboard/core/MetricsCalculatorTest.kt` — add 4 `gapsCount` tests + 6 `deviceLevelAggregates` tests per spec §7.1.

**Definition of done:**
- 50 `:core` tests green.

**Commit message:** `feat(core): DeviceAggregates + MetricsCalculator.gapsCount + deviceLevelAggregates`

---

## Step 6 — `:core`: `SheetsUploader.uploadBatch()`

**Files:**
- `core/src/main/java/com/pulseboard/core/SheetsUploader.kt` — add `uploadBatch(payloads: List<SheetPayload>): Boolean` using existing `OkHttpClient`, `GsonBuilder().serializeNulls()`, and dual-gate response parser. No change to existing `upload()`.
- `core/src/test/java/com/pulseboard/core/SheetsUploaderTest.kt` — add 5 tests per spec §7.1: 200+ok, 500, 200+error, connection refused, array-serialization verified via `MockWebServer.takeRequest().body`.

**Definition of done:**
- 55 `:core` tests green.
- `:app-cn` still builds as v1.0 (no `SheetPayload` schema change yet).

**Commit message:** `feat(core): SheetsUploader.uploadBatch for multi-row flushes`

---

## Step 7 — `:app-cn`: Constants refactor + `PingTarget.kt`

**Files:**
- `app-cn/src/main/java/com/codingninjas/networkmonitor/Constants.kt` — per spec §4.2: add `SMARTFLO_IP`, `CLOUDFLARE_IP`, `DNS_RESOLVER_IP`, `GAP_THRESHOLD_MS`, `EXPECTED_SAMPLES_PER_TARGET_PER_WINDOW`, `EXPECTED_TOTAL_SAMPLES_PER_WINDOW`, `PREF_LAST_BATCH_JSON`, `PREF_LAST_FLUSH_MS`, `PREF_FLUSH_SEQ`, `PREF_PENDING_RETAIN_COUNT`. **Keep** v1.0 `PING_TARGET`, `PREF_LAST_RESULT`, `PREF_LAST_UPDATE_TIME`, `PREF_LAST_NETWORK_TYPE` (removed in Step 9). Do **not** bump `APP_VERSION` yet.
- `app-cn/src/main/java/com/codingninjas/networkmonitor/PingTarget.kt` — new file with `data class PingTarget(id: String, resolveAddress: () -> String?, sampler: (String) -> PingResult)`.

**Definition of done:**
- `./gradlew :app-cn:assembleDebug` green.
- No runtime change yet (constants not wired into PingService).

**Commit message:** `refactor(app-cn): add v1.1 Constants + PingTarget (not yet wired)`

---

## Step 8 — `:core` + `:app-cn` (single commit): SheetPayload rewrite + PingService refactor

This is the one cross-module commit — necessary because SheetPayload's v1.1 schema renames v1.0 fields (`avg_ping_ms` → `avg_rtt_ms`, `network_type` → `network_type_dominant`), which breaks v1.0 `PingService`'s call sites if done alone.

**Files:**
- `core/src/main/java/com/pulseboard/core/SheetsUploader.kt` — rewrite `SheetPayload` data class to the 40-column schema per spec §5. All new fields nullable and defaulted. The v1.0 field renames (ping→rtt, network_type→network_type_dominant) propagate to constructor parameter names.
- `app-cn/src/main/java/com/codingninjas/networkmonitor/service/PingService.kt` — full refactor per spec §4.3: 4 samplers on a `SupervisorJob`, per-target `SampleBuffer`, `GatewayResolver` + `WifiMetadataCollector` + `UdpDnsPinger` wired in `onCreate`. `runOneFlush` implements duty-cycle fix (`currentWindowSamplesCount` via timestamp filter), `flush_seq` persistence, `retain_merged_count` accumulation. Wi-Fi snapshot taken BEFORE each ping per spec §4.3 critical-ordering note. `delay()` clamped via `.coerceAtLeast(0L)`.

**Definition of done:**
- `./gradlew :core:testDebugUnitTest` — 55 tests still green (no test file changes in this commit).
- `./gradlew :app-cn:assembleDebug` green.
- MainActivity still compiles but may render "no data" or stale v1.0 data on resume — intentional, fixed in Step 9. Verify by sideloading and confirming the foreground notification says running and the Sheet receives 4-row batches at the next 15-min mark.

**Commit message:** `feat(core,app-cn): 40-column v1.1 SheetPayload + 4-sampler PingService`

---

## Step 9 — `:app-cn`: MainActivity summary view + drop dead prefs

**Files:**
- `app-cn/src/main/res/layout/activity_main.xml` — rewrite per spec §4.5: vertical `LinearLayout` with 7 `TextView`s (`tvGreeting`, `tvLastSentHeader`, `tvSmartflo`, `tvGateway`, `tvCloudflare`, `tvDns`, `tvFooter`). Monospace on the 4 target-summary lines for column alignment.
- `app-cn/src/main/java/com/codingninjas/networkmonitor/ui/MainActivity.kt` — rewrite `onResume` to read `PREF_LAST_BATCH_JSON` + `PREF_LAST_FLUSH_MS`, Gson-decode `List<SheetPayload>`, render per-target summary + footer with `current_bssid`, `current_rssi`, `primary_ssid`, `duty_cycle_pct`. Compute next-flush time client-side. No auto-refresh; `onResume`-only.
- `app-cn/src/main/java/com/codingninjas/networkmonitor/Constants.kt` — remove dead v1.0 prefs constants: `PING_TARGET`, `PREF_LAST_RESULT`, `PREF_LAST_UPDATE_TIME`, `PREF_LAST_NETWORK_TYPE`.

**Definition of done:**
- `./gradlew :app-cn:assembleDebug` green.
- Sideload; Activity shows "No data yet" until first flush, then renders 4-line summary.

**Commit message:** `feat(app-cn): MainActivity last-sent summary view`

---

## Step 10 — `:app-cn`: Onboarding location permission + manifest + version bump

**Files:**
- `app-cn/src/main/AndroidManifest.xml` — add `<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION"/>`.
- `app-cn/src/main/java/com/codingninjas/networkmonitor/OnboardingActivity.kt` — insert location-permission step between `proceedAfterNotifGranted()` and `finalizeOnboarding()` per spec §4.4. AlertDialog rationale text, single "Grant" button, `ActivityResultContracts.RequestPermission` launcher. Denial does **not** block onboarding.
- `app-cn/build.gradle.kts` — `versionCode = 2`, `versionName = "1.1"`.
- `app-cn/src/main/java/com/codingninjas/networkmonitor/Constants.kt` — bump `APP_VERSION = "1.1"`.

**Definition of done:**
- `./gradlew :app-cn:assembleRelease` green with the existing CN keystore.
- Fresh install prompts for notification + location + battery exemption in that order.

**Commit message:** `feat(app-cn): location permission onboarding + v1.1 version bump`

---

## Step 11 — Apps Script deployment (non-code)

**Not a git commit** — this work happens in the Google Apps Script editor bound to the existing CN Sheet.

1. Open the bound Apps Script project.
2. Replace `doPost(e)` with the v1.1 handler per spec §6:
   - Parse `e.postData.contents` as JSON array (return HTTP 400 on non-array).
   - If Sheet row 1 is empty, write the 40-column header row.
   - For each element, build a row by looking up each column name in the element's keys (missing → blank cell). Append.
   - Return `ContentService.createTextOutput(JSON.stringify({status:"ok", rows_appended: N}))` with `application/json` MIME type.
3. Deploy as a new web app version; set access to "Anyone with the link". Keep the `/exec` URL unchanged (matches `Constants.WEBHOOK_URL`).
4. Manual `curl` validation:
   ```bash
   curl -L -X POST -H "Content-Type: application/json" \
        -d '[{"window_start":"2026-04-21T10:00:00Z","target":"smartflo","avg_rtt_ms":34.5}]' \
        $WEBHOOK_URL
   # Expect: {"status":"ok","rows_appended":1}
   ```
5. Optional: wipe the existing Sheet rows (the spec treats this as OK; no backward-compat required).

---

## Step 12 — On-device validation matrix (non-code)

**Not a git commit** — per spec §7.2. Run sequentially on the engineer's own device over ~2 hours before distributing to other test users.

```bash
adb install -r app-cn/build/outputs/apk/release/app-cn-release.apk
```

| Scenario | Duration | Sheet check |
|---|---|---|
| Baseline office Wi-Fi | 60 min | 4 flushes × 4 rows = 16 rows; RTT + BSSID populated; `duty_cycle_pct ≥ 0.95`; `flush_seq` increments by 1 per window; `vpn_active = false`; `visible_aps_count > 0`. |
| Airplane mode 20s mid-window | 15 min | `gaps_count ≥ 1`; `unreachable_target = true` for gateway if it disappeared. |
| Walk to a different AP | 15 min | `bssid_changes_count ≥ 1`; `primary_bssid` may change; `sticky_client_gap_db` small post-roam. |
| Connect to corp VPN | 15 min | `vpn_active = true` in that window's rows. |
| Stay on weak AP (walk far) | 15 min | `rssi_min` drops; if a stronger AP was audible, `sticky_client_gap_db ≥ 15`. |
| Device reboot + wait | 20 min | BootReceiver restarts service; `flush_seq` continues from persisted value. |
| Swipe from recents | 15 min | Foreground service survives (v1.0 OEM hardening preserved). Next flush still lands. |

If any row diverges from expected, halt and fix before proceeding to Step 13.

---

## Step 13 — Release + distribution (non-code)

**Not a git commit.**

1. Rename output: `mv app-cn/build/outputs/apk/release/app-cn-release.apk NetworkMonitorCN-v1.1.apk`.
2. Upload to the Drive link used for v1.0 distribution.
3. Notify the 4–5 test users: "Please update — same install process, one new permission prompt (location, used only for Wi-Fi access-point identification)."
4. Merge `voip-diagnostic-v1.1` into `main` via PR.
5. Tag `cn-v1.1` on the merged commit for historical reference (CN builds are not published to GitHub Releases per CLAUDE.md, so tag-only, no artifact upload).

---

## Final verification (after Step 13)

After 2–3 days of field data collection, verify spec §11 acceptance criteria:

1. Every flush window in the Sheet has exactly 4 rows per active device.
2. `duty_cycle_pct ≥ 0.95` on devices where v1.0 previously hit that threshold.
3. Pick one reported bad-call incident. Within 5 minutes of Sheet pivoting, you can answer: which target showed elevated p99/jitter/loss, which BSSID the user was on, whether a roam/gap happened in that window, whether VPN was active, whether a stronger AP was available.
4. `gaps_count`, `bssid_changes_count`, `visible_aps_count`, `vpn_active` all populate under the provocations you ran in Step 12.
5. `./gradlew :core:testDebugUnitTest` green: 26 existing + 26 new = 52 tests.

If any criterion fails → back to the relevant step; fix on the branch; re-release as v1.2 or as an amended v1.1 (versionCode 3).

---

## Rollback

If v1.1 misbehaves in the field and the problem cannot be fixed within hours:

1. Rebuild the archived `NetworkMonitorCN-v1.0.apk` from `main` prior to the merge (or retain it on Drive).
2. Each affected user uninstalls v1.1 and reinstalls v1.0.
3. Redeploy the v1.0 Apps Script handler (single-object payload format) to keep any still-running v1.0 devices reporting.

`applicationId` is unchanged between v1.0 and v1.1, so uninstall-then-install loses SharedPreferences (user will re-onboard) but does not require keystore rotation.

---

## Notes for the implementer

- **Commit granularity**: Steps 1–6 are truly independent; feel free to rebase / squash if you hit merge conflicts. Steps 7–10 have ordering dependencies; don't reorder.
- **Test-first discipline**: for Steps 1–6, write the test cases in the same commit as the code. Don't batch tests into a separate "add tests" commit.
- **Don't version-bump early**: `versionCode = 2` lands in Step 10 only. If you bump it earlier, mid-development sideloads will claim to be v1.1 while still missing features, making validation confusing.
- **Don't rename `applicationId`**: explicitly preserved at `com.codingninjas.networkmonitor` to keep the in-place upgrade path.
- **Engine reuse in Pulseboard**: the `:core` additions (Steps 1–6) are generic by construction. When `:app-pulseboard` public v1.1 is implemented on its own track, those same classes are consumed without change.
