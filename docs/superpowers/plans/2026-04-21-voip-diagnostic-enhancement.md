# Network Monitor CN — VoIP Diagnostic Enhancement Plan

**Target**: Existing Android app `com.codingninjas.networkmonitor` (v1.0)
**Goal**: Transform the app from a generic latency monitor into a precise VoIP call-quality diagnostic tool that produces actionable evidence for internal IT and external VoIP vendor (Tata/Smartflo).
**Audience**: Engineer (human + AI pair) working in Android Studio on this codebase.

---

## 1. Problem Statement

### 1.1 The real-world problem

Coding Ninjas employees use **mobile VoIP over office Wi-Fi** as their primary calling mechanism, via Smartflo (Tata Tele Business Services). Calls intermittently degrade — robotic audio, dropped words, one-way audio, occasional call drops. The degradation has no clear time-of-day pattern and affects different users at different moments.

Three organizations are involved and each has been pointing at the others:

- **Internal IT team** initially claimed a "routing loop on Tata's end" based on `mtr` output interpretation. This was not supported by the data.
- **Tata/Smartflo** provides the VoIP service and states only that "a stable internet connection is required."
- **The business (us)** is experiencing the symptoms and needs to resolve them.

### 1.2 The infrastructure context

- **Office Wi-Fi**: Aruba-based deployment. 20+ access points across one floor, roughly one AP every 15–20 feet. "Huge bandwidth" per IT team. Multiple APs visible, likely a single or small number of SSIDs.
- **User base**: 300+ employees on the Wi-Fi, of which 10–15 are on concurrent VoIP calls at any given time.
- **VoIP endpoint**: Smartflo SIP and RTP share the same IP range.
  - Primary test IP: `14.97.20.3` (AHM region)
  - Full subnet: `14.97.20.0/24`
  - RTP ports: UDP 20000–40000
  - SIP signaling: UDP/TCP 5060/5061, TLS 49868
- **Access from our side**: We control the app on user phones. We do NOT have direct access to Aruba controllers, firewall, or Smartflo internal stats.

### 1.3 What the existing Mac-based testing proved

Over multiple rounds of `ping` and `mtr` testing from a MacBook on office Wi-Fi:

1. **No routing loop to Tata.** Destination consistently reachable; no repeating hop patterns.
2. **No sustained end-to-end packet loss** to the Smartflo endpoint.
3. **Upstream path to Smartflo is clean** when tested from home (~51ms, 0.7% loss).
4. **Occasional latency spikes** observed (one notable spike to 531ms in a 130-sample window).
5. **One apparent "Wi-Fi disconnection event"** turned out to be the tester closing their laptop and going home — a false positive caused by the gateway IP becoming unreachable from a different network.

The Mac testing has plateaued. It cannot:

- Reproduce the *actual user experience* (tests are run stationary from one laptop, while the real use case is mobile phones walking around an office with roaming between 20+ APs)
- Run 24/7 across multiple devices
- Correlate with specific bad-call moments
- Capture Wi-Fi roaming / RSSI / BSSID changes that are the prime suspects

### 1.4 Why the existing monitor app is the right instrument — but currently points at the wrong target

The app (`com.codingninjas.networkmonitor` v1.0) already runs:

- Foreground service with persistent notification
- 1 Hz ping sampler with robust OEM hardening
- 15-minute wall-clock-aligned flush with full aggregate metrics (avg, min, max, p50, p95, p99, jitter, loss)
- Retain-on-failure buffer surviving network outages
- Google Sheets backend with per-device, per-window rows
- Field-tested across 5 devices and 4 OEM skins

But it currently pings `8.8.8.8` (Google DNS), which answers "is the general internet working" — a question that is almost always "yes" even during bad VoIP calls. The architecture is right; the target and the metadata are wrong for VoIP diagnosis.

### 1.5 Concrete diagnostic questions the enhanced app must answer

After these enhancements ship and run for a few days, we should be able to answer:

1. **Is the upstream path to Smartflo clean from each user's device?** (ping-to-Smartflo metrics)
2. **Is the local Wi-Fi itself stable from each user's device?** (ping-to-gateway metrics)
3. **Is the general internet clean?** (ping-to-public-control metrics — isolates ISP-wide issues)
4. **When a user reports a bad call, what was happening on their device's network in that exact 15-minute window?** (manual bad-call marker button)
5. **Which specific APs are associated with the worst call quality?** (BSSID per sample, aggregated per window)
6. **Are users roaming excessively?** (BSSID-change count per window)
7. **Are users stuck on weak signal?** (RSSI min/avg per window, correlated with spikes)
8. **Are there brief Wi-Fi disconnection events?** (gap-count per window — gaps between consecutive samples)

Without at least these eight answers from real devices in the field, the investigation cannot converge.

---

## 2. Solution Overview

Convert the existing single-target latency monitor into a **three-target, Wi-Fi-aware, user-annotated VoIP quality probe**, without disturbing the v1.0 architecture that is already field-proven.

### 2.1 Architectural principles

- **Don't rewrite what works.** The foreground service, sampler/flusher coroutines, wall-clock alignment, retain-on-failure buffer, OEM hardening guidance, and Sheets webhook pipeline are all correct and should be preserved.
- **Schema change is the biggest change.** Move from "one row per device per window" to "one row per `(device, target)` per window," plus Wi-Fi metadata fields, plus a separate "bad-call markers" sheet/tab.
- **Make it pivotable.** All metrics go into columns; filtering by `target` or by `bssid` should give a clean view.
- **Fail soft per-target.** If one target is unreachable (e.g., mobile gateway isn't pingable on some carriers), that target's row should record `unreachable=true` rather than blowing up the flush.
- **Preserve backward compatibility where possible.** If a user is mid-window when the upgrade installs, we accept a transitional window with partial data rather than corrupting historical rows.

### 2.2 What the final shipped app does

1. Runs three parallel 1 Hz ping samplers — to Smartflo (`14.97.20.3`), to the current default gateway (dynamically resolved), and to Cloudflare (`1.1.1.1`) as a control.
2. On each sample, captures Wi-Fi metadata: SSID, BSSID, RSSI, link speed, frequency, and network type.
3. Every 15 minutes (wall-clock aligned, unchanged), aggregates each target independently and each Wi-Fi-metadata dimension, and uploads one row per target (three rows per device per window).
4. Detects and counts per-window **gap events** (consecutive sample times > 3 s apart) — proxy for brief disconnections.
5. Detects and counts per-window **BSSID change events** — proxy for Wi-Fi roaming.
6. Exposes a "I had a bad call just now" button in `MainActivity` that posts a timestamped marker to a separate sheet tab.
7. Updates the dashboard to show the latest per-target metrics side-by-side with quick-read status bands (good / noticeable / bad).
8. Preserves all existing OEM hardening, boot-restart, retain-on-failure, and duty-cycle behavior unchanged.

### 2.3 What this app explicitly does NOT do (deferred / out of scope)

- **WebRTC peer-connection monitoring.** Requires signaling/STUN/TURN infrastructure; not worth the lift given ping-to-Smartflo is a good proxy.
- **UDP echo probes on actual RTP ports 20000–40000.** Smartflo doesn't run echo services on those ports; probes would fail.
- **Per-sample upload.** 15-minute aggregates remain the granularity. Adding `gaps_count` and `bssid_changes_count` captures most of what finer granularity would reveal.
- **Live dashboard refresh.** Dashboard remains read-only, refreshes on Activity resume. Existing v1.0 limitation is accepted.
- **Backend migration (Sheets → BigQuery).** Defer until the 10M-cell ceiling becomes pressing.
- **In-app update checker.** Continue distributing updated APKs manually via Drive link.
- **Automatic call-event detection** (detecting actual VoIP call start/end). Too OS-restricted and app-specific. Use the manual "bad call" marker button instead.

---

## 3. Detailed Requirements

### 3.1 Ping targets

The app must simultaneously monitor three targets:

| Name          | Address                                             | Purpose                                         |
| ------------- | --------------------------------------------------- | ----------------------------------------------- |
| `smartflo`    | `14.97.20.3` (hardcoded)                            | Actual VoIP endpoint — the thing that matters   |
| `gateway`     | Dynamically resolved from `ConnectivityManager`     | Local Wi-Fi / first-hop health                  |
| `cloudflare`  | `1.1.1.1` (hardcoded control)                       | Generic internet reachability baseline          |

**Gateway resolution rules:**

- On service start, resolve the current default gateway via `ConnectivityManager.getLinkProperties(activeNetwork).routes`.
- Register a `NetworkCallback` listening on `onLinkPropertiesChanged` and `onCapabilitiesChanged`. When the network changes (Wi-Fi to mobile, AP roam that changes subnet, etc.), re-resolve the gateway and update the sampler's target.
- If no gateway is resolvable (rare, e.g., offline), the gateway sampler skips that second's sample and records it as `unreachable`.
- Some mobile data carriers use CGN and do not provide a pingable gateway. Mark those samples `unreachable=true` rather than counting as 100% loss; aggregator must filter out unreachable samples when computing loss %, so that a carrier-gateway-blocked user doesn't appear to have 100% loss on the `gateway` target.

### 3.2 Sampling behavior

- **One sampler coroutine per target**, all three on the same `SupervisorJob`. Failure of one does not cancel the others.
- **1 Hz per target**, same as v1.0. That's 3 pings per second total, still trivially low overhead.
- **Sample record** now includes:
  - `timestamp_ms`
  - `target` (enum: SMARTFLO / GATEWAY / CLOUDFLARE)
  - `rtt_ms` (or null if timed out / failed)
  - `success` (boolean)
  - `unreachable` (boolean — distinguishes "target doesn't respond" from "we have no target to ping")
  - `ssid`, `bssid`, `rssi`, `link_speed_mbps`, `frequency_mhz`, `network_type`
- **Wi-Fi metadata collection** happens once per sample. Use `WifiManager.getConnectionInfo()`. On Android 8+ BSSID requires `ACCESS_FINE_LOCATION`; this permission must be requested at onboarding alongside notifications.
- **Sample buffer** becomes per-target. Either three separate `SampleBuffer` instances keyed by target, or a single buffer that partitions on flush. Either is fine; the former is simpler and matches the independent-failure principle.

### 3.3 Flushing and aggregation

- **Wall-clock alignment remains unchanged** — flushes at :00/:15/:30/:45 minute marks.
- **Per-flush output**: 3 rows per device per flush (one per target) plus Wi-Fi metadata aggregates that apply to the window as a whole (BSSID changes, SSID changes, etc. are device-level, not per-target, but for schema simplicity they can be duplicated into each of the 3 rows, OR stored in a single "device metadata" column set that appears identical across the 3 target rows).

  **Decision**: duplicate device/Wi-Fi metadata into all 3 rows per flush. This keeps the Sheet self-contained per row and avoids join complexity for non-technical viewers.

- **Per-target aggregates** (same math as v1.0, now computed per-target):
  - avg_rtt_ms, min_rtt_ms, max_rtt_ms
  - p50_rtt_ms, p95_rtt_ms, p99_rtt_ms (linear interpolation, as before)
  - jitter_ms (population stddev, as before)
  - packet_loss_pct — denominator is **reachable** samples only (excludes `unreachable` samples)
  - samples_count — total samples attempted for this target in this window
  - reachable_samples_count — samples where target was reachable
  - max_rtt_offset_sec — when within the window the worst spike occurred

- **Per-window device-level aggregates** (computed once, duplicated across the 3 rows):
  - gaps_count — number of times the gap between consecutive timestamps on the SAME target exceeded 3000ms (computed per-target, take the max or sum across targets — recommend **sum**, as any gap is a real gap)
  - bssid_changes_count — number of BSSID transitions in the window
  - ssid_changes_count — number of SSID transitions
  - rssi_min, rssi_avg, rssi_max
  - network_type_dominant — the network type the device was on for the majority of the window
  - primary_bssid — the BSSID the device was on for the majority of the window
  - primary_ssid — the SSID the device was on for the majority of the window

### 3.4 Duty cycle / OEM throttle detection

v1.0 used `samples_count / window_seconds` as the duty cycle. That metric now has 3x the nominal sample count. Update the interpretation accordingly:

- Expected samples per target per window: 900 (15 min × 60 s × 1 Hz)
- Total expected across 3 targets per device per window: 2700
- Compute `duty_cycle = total_samples / 2700` per device per window
- Expose this in the dashboard as a single "Health: XX%" indicator

### 3.5 Bad-call marker

New feature — a button in `MainActivity` labeled **"I just had a bad call"**. Tapping it:

1. Records a local marker event with current timestamp, device model, user_id, current BSSID, SSID, RSSI, and network type.
2. Immediately fires a separate POST to the webhook with `event_type=BAD_CALL_MARKER`.
3. Webhook appends the marker to a separate sheet tab (`markers`), not the main metrics tab.
4. Shows a 2-second toast confirming the marker was recorded.
5. On upload failure, enqueue the marker in SharedPreferences to retry on the next successful flush.

The button is the simplest possible UX — no form, no optional note field. Label only. The goal is that a user experiencing a bad call can tap once within a few seconds, and a timestamped row appears in the Sheet that the analyst can cross-reference with metric rows.

### 3.6 Dashboard updates

`MainActivity` currently shows the last-flush metrics. Updated layout:

- **Three cards, one per target** (Smartflo / Gateway / Cloudflare), each showing:
  - Current p95 latency with color band (green <80ms, amber 80–150, red >150)
  - Current jitter with band (green <20ms, amber 20–50, red >50)
  - Current loss % with band (green <0.5%, amber 0.5–2, red >2)
  - Time of last flush
- **Wi-Fi card** showing:
  - Current SSID and BSSID (last 6 hex pairs only, for visibility)
  - Current RSSI
  - Roam count in last window
  - Gap count in last window
- **"I just had a bad call" button**, prominent, centered.
- **Health row** at the bottom: duty cycle %, last flush time, next flush time, service running indicator.
- Continue to read from `SharedPreferences` on resume; no live updates.

### 3.7 Onboarding update

The existing `OnboardingActivity` gates on `@codingninjas.com` email and requests notification permission. Add:

- `ACCESS_FINE_LOCATION` request (required for BSSID on Android 8+). Explain clearly in a rationale dialog: *"We need location permission only to identify which Wi-Fi access point your device is connected to. We do not collect or upload GPS location."*
- If the user denies location, the app should still function — BSSID/SSID fields will be null or `"permission_denied"`, and the analysis will proceed without the AP-level correlation. Do NOT block the app on location denial.

### 3.8 Schema — final target

Main `metrics` sheet row (35 columns):

```
timestamp_iso, user_id, device_model, android_version, oem_skin,
target, avg_rtt_ms, min_rtt_ms, max_rtt_ms,
p50_rtt_ms, p95_rtt_ms, p99_rtt_ms, jitter_ms,
packet_loss_pct, samples_count, reachable_samples_count, max_rtt_offset_sec,
gaps_count, bssid_changes_count, ssid_changes_count,
rssi_min, rssi_avg, rssi_max,
network_type_dominant, primary_bssid, primary_ssid,
current_bssid, current_ssid, current_rssi,
app_version, duty_cycle_pct, flush_seq, retain_merged_count,
gateway_ip, unreachable_target
```

Separate `markers` sheet row (10 columns):

```
timestamp_iso, user_id, device_model,
bssid, ssid, rssi, network_type,
android_version, app_version, note
```

(`note` is for future use; for v1.1 it stays empty.)

### 3.9 Apps Script webhook changes

- Accept a new optional field `event_type` (default: `METRICS`). If `event_type=BAD_CALL_MARKER`, route the row to the `markers` sheet instead.
- Accept either a single-row payload (v1.0 format) OR an array of 3 rows (new format, one per target). Handle both for backward compatibility during transition.
- Return a stable JSON response `{"ok": true}` so the Android uploader's dual-gate success check continues to work.

### 3.10 Constants

All new values (target IPs, thresholds, ports, window duration, buffer caps, intervals) must be in `Constants.kt`. Do not sprinkle magic numbers in business logic.

---

## 4. Implementation Plan

This is a single linear plan. Work through it top to bottom. Each section is a commit-sized unit.

### 4.1 Preliminaries

- [ ] Read the existing codebase end-to-end before touching anything. Files to study in order: `Constants.kt`, `PingEngine.kt`, `SampleBuffer.kt`, `MetricsCalculator.kt`, `SheetsUploader.kt`, `service/PingService.kt`, `ui/MainActivity.kt`, `OnboardingActivity.kt`, `BootReceiver.kt`, `NetworkUtils.kt`, `NotificationHelper.kt`.
- [ ] Confirm all 26 existing unit tests pass: `./gradlew testDebugUnitTest`.
- [ ] Create a new branch: `voip-diagnostic-v1.1`.
- [ ] Bump `versionCode` and `versionName` in `app/build.gradle` (e.g., from 1 to 2, name `1.0` to `1.1`). Keep updates incremental as sub-milestones below are hit (1.1 → 1.2 → 1.3) — but ship them as one combined release unless there's a reason to split.

### 4.2 Constants and target configuration

- [ ] In `Constants.kt`, introduce:
  ```kotlin
  enum class PingTarget(val id: String, val defaultAddress: String?) {
      SMARTFLO("smartflo", "14.97.20.3"),
      GATEWAY("gateway", null),        // resolved at runtime
      CLOUDFLARE("cloudflare", "1.1.1.1")
  }
  ```
- [ ] Add thresholds for dashboard color bands:
  ```kotlin
  const val LATENCY_GOOD_MS = 80
  const val LATENCY_BAD_MS = 150
  const val JITTER_GOOD_MS = 20
  const val JITTER_BAD_MS = 50
  const val LOSS_GOOD_PCT = 0.5
  const val LOSS_BAD_PCT = 2.0
  const val GAP_THRESHOLD_MS = 3000L
  const val EXPECTED_SAMPLES_PER_TARGET_PER_WINDOW = 900
  const val EXPECTED_TOTAL_SAMPLES_PER_WINDOW = 2700
  ```
- [ ] Add marker event type constant: `const val EVENT_TYPE_METRICS = "METRICS"`, `const val EVENT_TYPE_BAD_CALL = "BAD_CALL_MARKER"`.
- [ ] Keep the old `PING_TARGET` constant for any legacy code path but mark it `@Deprecated`. Remove it at the end of the refactor.

### 4.3 Gateway resolver

- [ ] Create new class `GatewayResolver.kt`.
  - Inject `ConnectivityManager` and `WifiManager`.
  - Expose `fun currentGateway(): String?` — returns the IPv4 gateway of the active network, or null.
  - Implementation: iterate `connectivityManager.getLinkProperties(activeNetwork).routes`, find the one where `isDefaultRoute` is true and `gateway` is an `Inet4Address`, return its host address. Handle nulls gracefully.
  - Expose `fun registerOnChange(callback: (String?) -> Unit)` — registers a `NetworkCallback` that fires on `onLinkPropertiesChanged` and `onCapabilitiesChanged`, with debouncing (wait 1 s after rapid changes before firing).
- [ ] Unit tests for `GatewayResolver`:
  - Mock `ConnectivityManager` returning a sample `LinkProperties` with a default route; assert gateway is extracted.
  - Mock with no default route; assert null.
  - Mock with IPv6-only gateway; assert null (we only handle IPv4 for now).

### 4.4 Wi-Fi metadata collector

- [ ] Create new class `WifiMetadataCollector.kt`.
  - Inject `WifiManager`, `ConnectivityManager`.
  - Expose `fun snapshot(): WifiSnapshot` returning a data class:
    ```kotlin
    data class WifiSnapshot(
        val ssid: String?,
        val bssid: String?,
        val rssi: Int?,
        val linkSpeedMbps: Int?,
        val frequencyMhz: Int?,
        val networkType: String,   // "wifi", "cellular", "ethernet", "none"
        val collectedAtMs: Long
    )
    ```
  - On Android < 8, return BSSID normally. On Android 8+ without `ACCESS_FINE_LOCATION`, return BSSID as `"permission_denied"` and log once (not per sample).
  - SSID is often returned wrapped in quotes (`"MyWifi"`) by WifiInfo — strip them.
  - If network is cellular or ethernet, return null for ssid/bssid/rssi/linkSpeed/frequency but still set `networkType`.
- [ ] Unit tests:
  - Mock `WifiInfo` with quoted SSID; assert quotes stripped.
  - Mock no Wi-Fi; assert network type correctly reported.
  - Mock permission denied state; assert graceful fallback.

### 4.5 Sample model update

- [ ] Update the `Sample` data class (currently in `PingEngine.kt` or similar) to include target and Wi-Fi metadata:
  ```kotlin
  data class Sample(
      val timestampMs: Long,
      val target: PingTarget,
      val rttMs: Double?,           // null if timeout / failure
      val success: Boolean,
      val unreachable: Boolean,     // target was not resolvable (e.g., no gateway)
      val wifi: WifiSnapshot
  )
  ```
- [ ] Update `PingEngine.runPing(...)` signature to accept `target: PingTarget` and the resolved address. If the target is `GATEWAY` and no address is available, return a `Sample` with `unreachable=true` and `success=false`.
- [ ] Update any existing tests for `PingEngine` to pass the new `target` parameter. Most should continue to work since behavior for a resolvable address is unchanged.

### 4.6 SampleBuffer: per-target storage

- [ ] Either:
  - (Option A, simpler) Keep `SampleBuffer` as-is but instantiate three of them, keyed by target, inside `PingService`. OR
  - (Option B) Extend `SampleBuffer` internally to partition by target.
- [ ] **Pick Option A.** Simpler, matches "fail soft per target," preserves all existing `SampleBufferTest` tests unchanged.
- [ ] Update `Constants.kt` buffer cap: the 5400-sample retain buffer in v1.0 assumed one target. For three targets, size each buffer at 5400 (so total retained capacity is 16200). Memory cost is negligible (Sample is small).

### 4.7 MetricsCalculator: unreachable handling + gaps + roams

- [ ] Current `MetricsCalculator.aggregate(samples: List<Sample>): Metrics` computes avg/min/max/p50/p95/p99/jitter/loss. Update to:
  - Filter out `unreachable=true` samples before computing loss. New `loss_pct = (attempted - success) / (attempted - unreachable)` where division by zero returns 100 if no samples were reachable.
  - Add `gaps_count`: count of consecutive timestamp pairs where `delta > GAP_THRESHOLD_MS`.
  - Add `reachable_samples_count`.
  - Add `max_rtt_offset_sec` already present in v1.0, retain it.
- [ ] New function `MetricsCalculator.aggregatePerTarget(...)` that takes a flat list of samples (all three targets mixed, as you'd get from sorting by timestamp) and returns a `Map<PingTarget, Metrics>`.
- [ ] New function `MetricsCalculator.deviceLevelAggregates(samples: List<Sample>): DeviceAggregates` returning:
  ```kotlin
  data class DeviceAggregates(
      val bssidChangesCount: Int,
      val ssidChangesCount: Int,
      val rssiMin: Int?,
      val rssiAvg: Int?,
      val rssiMax: Int?,
      val networkTypeDominant: String,
      val primaryBssid: String?,
      val primarySsid: String?,
      val currentBssid: String?,
      val currentSsid: String?,
      val currentRssi: Int?
  )
  ```
  - Compute transitions by walking the sample list in timestamp order and counting changes (null-to-value and value-to-null both count; value-to-same-value does not).
  - Compute "dominant" by picking the value that occupies the most samples in the window.
  - "Current" values are simply the latest sample's values.
- [ ] Unit tests:
  - Sample list with all samples at one BSSID → `bssid_changes_count = 0`.
  - Sample list with 3 BSSID transitions → `bssid_changes_count = 3`.
  - Sample list with a 4-second gap → `gaps_count = 1`.
  - Sample list where some targets are `unreachable` → loss % excludes them.
  - All unreachable → loss % = 100.

### 4.8 SheetsUploader: new schema + batch + markers

- [ ] Extend `SheetsUploader` to accept a new `uploadBatch(rows: List<MetricsRow>): Boolean` that POSTs a JSON array of rows in one request. Apps Script will append all rows to the `metrics` sheet.
- [ ] Add `uploadMarker(marker: BadCallMarker): Boolean` that POSTs a single row to the `markers` sheet. Payload includes `event_type: "BAD_CALL_MARKER"`.
- [ ] The dual-gate success check (HTTP 200 + JSON `ok: true`) from v1.0 remains.
- [ ] Retain-on-failure: failed batch uploads return their rows to a pending queue. The queue lives in `SharedPreferences` as a JSON array, max size 48 rows (enough for ~4 hours of 3-row windows = 16 flush cycles).
- [ ] Unit tests (extend `SheetsUploaderTest`):
  - MockWebServer returns 200 + `{"ok":true}` → uploadBatch succeeds.
  - MockWebServer returns 200 but `{"ok":false}` → uploadBatch fails.
  - MockWebServer returns 500 → uploadBatch fails, rows queued.
  - Marker upload routes to a different payload (`event_type=BAD_CALL_MARKER`).

### 4.9 PingService refactor: three samplers, one flusher

- [ ] Refactor `PingService.kt` to launch **three sampler coroutines**, one per `PingTarget`, each writing to its own `SampleBuffer`.
- [ ] Each sampler:
  1. Every 1 second (minus the actual ping time to prevent drift), resolve the target address. For `GATEWAY`, consult the shared `GatewayResolver`. For the others, use the static IP.
  2. Call `PingEngine.runPing(target, address)`.
  3. Enrich with current `WifiMetadataCollector.snapshot()`.
  4. Append to its buffer.
- [ ] Flusher coroutine (wall-clock aligned at :00/:15/:30/:45) does:
  1. Drain all three buffers into lists.
  2. Compute per-target metrics via `MetricsCalculator.aggregatePerTarget(...)`.
  3. Compute device-level aggregates via `MetricsCalculator.deviceLevelAggregates(combined list)`.
  4. Construct 3 `MetricsRow` objects (one per target), each containing that target's metrics AND a copy of the device-level aggregates.
  5. Call `SheetsUploader.uploadBatch(rows)`.
  6. On success, also drain any pending retry queue by attaching those rows to the next batch.
  7. On failure, enqueue the current 3 rows and merge the count into the next window's `retain_merged_count` field.
- [ ] `NetworkCallback` integration: register the `GatewayResolver`'s callback in `onCreate`, unregister in `onDestroy`.
- [ ] Update foreground notification text to reflect current status (e.g., "Monitoring 3 targets — last flush: 14:15, next: 14:30").

### 4.10 OnboardingActivity: location permission

- [ ] In `OnboardingActivity`, after the existing notification permission request, add a rationale dialog and request for `ACCESS_FINE_LOCATION`.
- [ ] The rationale must explain: *"Location permission is only used to identify your Wi-Fi access point (BSSID) for network diagnostics. We do not collect GPS location or upload coordinates."*
- [ ] If denied, proceed normally. Do not block onboarding.
- [ ] Document the behavior in README.

### 4.11 MainActivity: dashboard redesign

- [ ] Replace the existing single-metric layout in `activity_main.xml` with:
  - A scrolling `LinearLayout` containing:
    - **Three metric cards** (Smartflo, Gateway, Cloudflare) — each a `CardView` with labeled p95, jitter, loss%, colored according to bands.
    - **One Wi-Fi card** with SSID, BSSID (last 6 hex pairs, e.g., `...AB:CD:EF`), RSSI, roam count last window, gap count last window.
    - **A prominent "I just had a bad call" button** (accent color, full-width, high contrast).
    - **Health row** at the bottom: duty cycle, last flush, next flush.
- [ ] In `MainActivity.kt`:
  - On `onResume`, read the latest per-target metrics from `SharedPreferences` (written by the flusher) and populate the cards.
  - Button `onClick`:
    1. Construct a `BadCallMarker` with current snapshot.
    2. Launch a coroutine on `lifecycleScope` that calls `SheetsUploader.uploadMarker(marker)`.
    3. Show toast "Marker saved" on success, "Marker queued (will retry)" on failure.
    4. If failure, enqueue the marker in SharedPreferences as pending; the flusher's next upload will include pending markers.

### 4.12 SharedPreferences keys

Introduce clear keys, centralize them in `Constants.kt`:

- `LAST_FLUSH_METRICS_SMARTFLO`, `LAST_FLUSH_METRICS_GATEWAY`, `LAST_FLUSH_METRICS_CLOUDFLARE` (JSON-serialized `Metrics`).
- `LAST_DEVICE_AGGREGATES` (JSON-serialized `DeviceAggregates`).
- `LAST_FLUSH_TIMESTAMP_MS`.
- `PENDING_MARKERS` (JSON array of `BadCallMarker`).
- `PENDING_RETRY_ROWS` (JSON array of `MetricsRow`).
- `USER_ID`, `USER_EMAIL` (unchanged from v1.0).

### 4.13 Apps Script webhook

- [ ] In the bound Apps Script, update the `doPost(e)` handler:
  1. Parse request body. If it's an array, iterate and route each element. If it's an object with `event_type=BAD_CALL_MARKER`, route to `markers` sheet; otherwise route to `metrics` sheet.
  2. Add a column header row to both sheets if the sheet is empty on first write.
  3. Handle both single-row (v1.0 format — metrics only) and multi-row array (v1.1+ format) payloads for backward compatibility.
- [ ] Test the webhook by hand with `curl` before shipping to devices:
  - Single old-format row → should append to `metrics`.
  - 3-row array → should append 3 rows to `metrics`.
  - Marker object → should append to `markers`.
- [ ] Verify by opening the `/exec` URL in a browser — should still display the "live" message.

### 4.14 Testing

- [ ] Run `./gradlew testDebugUnitTest`. All old tests must still pass.
- [ ] Add new tests:
  - `GatewayResolverTest` (see 4.3)
  - `WifiMetadataCollectorTest` (see 4.4)
  - `MetricsCalculatorTest` — extend with gap counting, roam counting, unreachable handling.
  - `SheetsUploaderTest` — extend with batch upload, marker upload, retry queue.
- [ ] Instrumented / device testing:
  - Install on your own device. Run for 1 hour on office Wi-Fi. Verify 4 rows appear in the Sheet (one flush cycle × 3 rows for metrics + potentially markers you trigger manually). Validate every column has a sensible value.
  - Turn off Wi-Fi mid-flush. Verify that the gateway target starts producing `unreachable=true` samples but Smartflo and Cloudflare samples continue via mobile data. Verify the `network_type_dominant` column captures the transition.
  - Walk between APs. Verify `bssid_changes_count` increments.
  - Tap the bad-call button; verify a marker row appears in the `markers` tab within ~5 seconds.
- [ ] OEM matrix re-validation: re-run the v1.0 matrix (Xiaomi/Realme/Vivo/OPPO/Samsung/POCO) for at least 2 hours each. Verify duty cycle remains ≥ 0.95 on hardened devices.

### 4.15 Documentation updates

- [ ] Update `README.md`:
  - New "What it does" section reflecting 3 targets + Wi-Fi metadata + markers.
  - Update the metrics table with new columns.
  - Update installation instructions to include location permission step.
  - Update the known limitations section (remove "single ping target" since it's addressed; add a note that UDP path testing is deferred).
- [ ] Update `docs/superpowers/specs/` with a v1.1 design doc that summarizes the new architecture.
- [ ] Update `docs/superpowers/testing/multi-device-matrix.md` with re-validation results.

### 4.16 Release

- [ ] Build signed release APK: `./gradlew assembleRelease`.
- [ ] Verify signature.
- [ ] Upload to Drive. Update the distribution link.
- [ ] Notify existing users: "Please update to v1.1 — new diagnostic features. Same install process. One new permission (location, Wi-Fi-AP-only, no GPS)."
- [ ] After 3 days of data collection from 3–5 test users, pull the Sheet and check:
  - Do spikes on `target=smartflo` correlate with reported bad calls?
  - Is there a BSSID that appears disproportionately in rows with high p99?
  - Are `gaps_count` events frequent? On which targets?
  - Is `bssid_changes_count` high for any user (sticky-AP problem or excessive roaming)?

---

## 5. Success Criteria

This work is successful when, for each reported bad-call incident, we can open the Sheet and in under 5 minutes determine:

1. **Which target(s) showed elevated p99/jitter/loss** in the window of the bad call → blame assignment (Smartflo/Tata vs Wi-Fi vs general internet).
2. **Which BSSID the user was on** during the bad call → AP-level correlation with Aruba logs.
3. **Whether a roam or gap event** occurred within a minute of the bad call → Wi-Fi handoff confirmation.
4. **Whether the user's device is OEM-throttled** → duty cycle check.

Secondary success criteria:

- Three test users run the app for 7 consecutive days without service kills (given OEM hardening applied).
- At least one bad-call incident is successfully diagnosed end-to-end using only app data, without needing new `mtr` runs.
- The IT team can be handed a list of the top 5 "worst" BSSIDs by p99 to SIP target, with confidence that the data is sound.

---

## 6. Risks and Mitigations

| Risk                                                                                       | Mitigation                                                                                                                                                               |
| ------------------------------------------------------------------------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| Location permission denial reduces BSSID signal                                            | Graceful degradation — app continues to function with null BSSID. README makes it clear that granting location permission significantly improves diagnostic value.       |
| Mobile carrier gateway not pingable causes `gateway` target to appear 100% loss            | `unreachable` flag excludes from loss denominator. Dashboard shows "N/A" rather than "100% loss" when target is unreachable.                                             |
| Sheet fills faster (3x rows per flush)                                                     | README already flags 10M-cell ceiling. 3x faster fill means ~4 months instead of 1 year per sheet for 10 users. Rotate the sheet quarterly. BigQuery migration is v1.4.  |
| Existing v1.0 users don't update                                                           | Send a direct notification in the admin channel. Consider adding a minimum-version check in the webhook — old clients still accepted but logged separately.             |
| Wi-Fi metadata snapshot adds sampling overhead                                             | Metadata is read from a cached `WifiInfo` that Android keeps current; the call is cheap (< 1ms). Still, measure: add logging around sampler iterations during testing.   |
| Users tap the "bad call" button far too often or far too seldom                           | Acceptable tradeoff — even sparse data helps. If a user taps 30x a day we have a rich signal; if they tap 0 we fall back to metric-only inference.                      |
| Three simultaneous ping sockets trigger battery-saver flags on some OEMs                  | Same foreground-service protection applies. Monitor duty cycle per-target in v1.1 field tests; if degradation appears, consider interleaving targets at 3 Hz round-robin instead of 3 parallel 1 Hz samplers. |

---

## 7. Out of Scope (explicit deferrals)

- WebRTC peer-connection monitoring.
- UDP probes on RTP port range.
- Automatic VoIP call detection.
- Live dashboard updates.
- BigQuery backend migration.
- In-app update checker.
- iOS version.
- Desktop/Mac version (we have the laptop tests; that's enough).

Any of the above may become a future v1.4+ item if v1.3's data proves insufficient.

---

## 8. Appendix — Key code locations touched

```
app/src/main/java/com/codingninjas/networkmonitor/
├── Constants.kt                        EDIT  (new enums, thresholds, keys)
├── PingEngine.kt                       EDIT  (target parameter, unreachable handling)
├── NetworkUtils.kt                     EDIT  (broaden to support gateway detection helpers)
├── MetricsCalculator.kt                EDIT  (per-target aggregation, device aggregates, gaps)
├── SampleBuffer.kt                     LIGHT EDIT (no partitioning — instantiate three)
├── SheetsUploader.kt                   EDIT  (batch upload, marker upload, retry queue)
├── OnboardingActivity.kt               EDIT  (location permission request)
├── GatewayResolver.kt                  NEW
├── WifiMetadataCollector.kt            NEW
├── BadCallMarker.kt                    NEW   (data class + serialization)
├── MetricsRow.kt                       NEW   (replaces ad-hoc row construction in v1.0)
├── service/
│   └── PingService.kt                  MAJOR EDIT (three samplers, new flusher logic)
└── ui/
    └── MainActivity.kt                 MAJOR EDIT (new layout + bad-call button)

app/src/main/res/layout/
├── activity_main.xml                   MAJOR EDIT
└── activity_onboarding.xml             LIGHT EDIT (location rationale text)

app/src/test/java/com/codingninjas/networkmonitor/
├── GatewayResolverTest.kt              NEW
├── WifiMetadataCollectorTest.kt        NEW
├── MetricsCalculatorTest.kt            EXTEND
└── SheetsUploaderTest.kt               EXTEND

docs/
├── README.md                           EDIT
└── superpowers/specs/
    └── 2026-04-22-network-monitor-v1.1-design.md    NEW
```
