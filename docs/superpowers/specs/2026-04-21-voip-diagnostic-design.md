# CN v1.1 — VoIP Diagnostic Enhancement (Design Spec)

**Scope**: `:app-cn` module only (`com.codingninjas.networkmonitor`). Pulseboard public build (`:app-pulseboard`) is NOT touched by this spec — its enhancement is a separate track.
**Source plan**: [`../plans/2026-04-21-voip-diagnostic-enhancement.md`](../plans/2026-04-21-voip-diagnostic-enhancement.md) (checked in unchanged from the engineer's original problem-framing document).
**Target branch**: `voip-diagnostic-v1.1`
**Target version**: `:app-cn` `versionCode=2`, `versionName=1.1`.

---

## 1. Context

Coding Ninjas employees use mobile VoIP over office Wi-Fi via Smartflo (Tata Tele Business Services). The office has dense Aruba Wi-Fi (10+ APs at ~15–20 ft spacing) serving 300+ users with 80–150 concurrent VoIP calls at peak. Calls intermittently degrade — robotic audio, dropped words, one-way audio, occasional drops — with no clear pattern. Mac-based `ping`/`mtr` from a single laptop has exhausted its diagnostic value; 24/7 distributed instrumentation on real user phones is the next step.

CN v1.0 (already deployed) monitors a single target (`8.8.8.8`) once per second and uploads 15-minute aggregates to a Google Sheet. The v1.0 architecture (foreground service, 1 Hz sampler, 15-min wall-clock-aligned flusher, retain-on-failure buffer, OEM-hardened lifecycle) is field-proven across 5 devices / 4 OEM skins. v1.1 keeps that architecture intact and extends the sampler dimension: four parallel targets instead of one, with per-sample Wi-Fi context, once-per-window AP-scan context, and VPN detection.

All diagnosis happens in the Sheet. The app's visible UX gains only a last-sent-summary view and a one-step location-permission onboarding prompt.

## 2. Module layout after v1.1 (reminder)

```
:core                    com.pulseboard.core        (library)
:app-cn                  com.codingninjas.networkmonitor  (CN internal app)
:app-pulseboard          com.cramraika.pulseboard   (public stub — untouched in v1.1)
```

Decision rule: `:core` gets primitives whose shape does NOT depend on product decisions either app has to make. CN-specific constructs (target vocabulary, email gate, dashboard copy, thresholds) stay in `:app-cn`.

## 3. `:core` additions

### 3.1 `Sample` extended (three new defaulted fields)

```kotlin
data class Sample(
    val rttMs: Double?,
    val tsMs: Long,
    val target: String = "default",       // free-form ID (e.g. "smartflo")
    val unreachable: Boolean = false,     // true = no resolvable address, excluded from loss denom
    val wifi: WifiSnapshot? = null
)
```

All new fields default, so existing `:core` unit tests compile unchanged. `Pulseboard` stub is not forced to populate `wifi` or `target`.

### 3.2 `WifiSnapshot` — new data class

```kotlin
data class WifiSnapshot(
    val ssid: String?,
    val bssid: String?,
    val rssi: Int?,
    val linkSpeedMbps: Int?,
    val frequencyMhz: Int?,
    val networkType: String,              // "wifi" | "cellular" | "ethernet" | "none"
    val vpnActive: Boolean,               // TRANSPORT_VPN capability on active network
    val collectedAtMs: Long
)
```

### 3.3 `ScanSnapshot` — new data class

```kotlin
data class ScanSnapshot(
    val visibleApsCount: Int,
    val bestAvailableBssid: String?,      // strongest AP the device could hear
    val bestAvailableRssi: Int?,
    val scannedAtMs: Long
)
```

### 3.4 `WifiMetadataCollector` — new class

```kotlin
class WifiMetadataCollector(
    private val wifiManager: WifiManager,
    private val connectivityManager: ConnectivityManager
) {
    fun snapshot(): WifiSnapshot       // cheap (<1ms); called per sample
    fun scanSnapshot(): ScanSnapshot?  // triggers startScan(); called once per flush window
}

// Package-visible helpers (unit-tested):
internal fun stripSsidQuotes(raw: String?): String?
internal fun mapNetworkType(caps: NetworkCapabilities?): String
```

Behaviors:
- `stripSsidQuotes` removes the literal quote wrapping `WifiInfo` returns (`"CN-Office"` → `CN-Office`), preserves unquoted strings, preserves Android's `<unknown ssid>` sentinel.
- On Android 8+, BSSID requires `ACCESS_FINE_LOCATION`. If not granted, `snapshot()` returns BSSID as literal `"permission_denied"` and logs the state **once** (not per sample). This lets the Sheet distinguish declined-permission from genuinely-null BSSID.
- On cellular / ethernet / no network, Wi-Fi-specific fields are null but `networkType` is correctly populated.
- `vpnActive` comes from `connectivityManager.getNetworkCapabilities(activeNetwork)?.hasTransport(TRANSPORT_VPN)`.
- `scanSnapshot()` calls `wifiManager.startScan()`; returns `ScanSnapshot?` where null means the scan was rate-limited or failed. Android 9+ allows ~4 scans per 2 min — once-per-15-min-flush is well within budget.

### 3.5 `GatewayResolver` — new class

```kotlin
class GatewayResolver(private val connectivityManager: ConnectivityManager) {
    fun currentGateway(): String?                                      // IPv4 only; null if no default route
    fun registerOnChange(callback: (String?) -> Unit): UnregisterHandle // 1s debounce on link changes
}

// Package-visible helper (unit-tested):
internal fun pickDefaultIPv4Gateway(routes: List<RouteInfo>): String?
```

`registerOnChange` uses `ConnectivityManager.NetworkCallback`'s `onLinkPropertiesChanged` + `onCapabilitiesChanged`. Debounce collapses rapid AP-roam events into a single callback.

### 3.6 `UdpDnsPinger` — new class

```kotlin
class UdpDnsPinger(
    private val resolverIp: String = "1.1.1.1",
    private val port: Int = 53,
    private val timeoutMs: Int = 2000
) {
    fun runQuery(domain: String = "cloudflare.com"): PingResult
}
```

Sends a minimal DNS `A` query over UDP via `DatagramSocket`, measures RTT, reuses the existing `PingResult` data class (rtt, packet_loss, success). Purpose: measures UDP-to-internet health independent of ICMP, which many middleboxes deprioritize differently from UDP/RTP.

### 3.7 `MetricsCalculator` — three additions

```kotlin
// Existing aggregate() modified:
// - loss denominator = reachable samples (excludes unreachable=true)
// - NetworkMetrics gains reachableSamplesCount: Int (defaulted to samplesCount for back-compat)
// - WHEN reachableSamplesCount == 0 (every sample is unreachable),
//     packet_loss_pct = null  (loss is undefined without reachable measurements)
//   The row's unreachable_target=true flag is the downstream detector.
//   Sheet filter: WHERE packet_loss_pct IS NOT NULL excludes these rows from loss pivots.

fun gapsCount(samples: List<Sample>, thresholdMs: Long = 3000L): Int
fun deviceLevelAggregates(samples: List<Sample>): DeviceAggregates
```

```kotlin
data class DeviceAggregates(
    val bssidChangesCount: Int,
    val ssidChangesCount: Int,
    val rssiMin: Int?, val rssiAvg: Int?, val rssiMax: Int?,
    val networkTypeDominant: String,
    val primaryBssid: String?,
    val primarySsid: String?,
    val primaryFrequencyMhz: Int?,
    val primaryLinkSpeedMbps: Int?,
    val currentBssid: String?,
    val currentRssi: Int?,
    val vpnActive: Boolean          // dominant vpn-active state across the window
)
```

Implementations walk the timestamp-sorted sample list, count transitions (null↔value counted, value↔same-value not), group `networkType` / `vpnActive` to find dominant, compute min/avg/max over non-null RSSI, pick most-frequent for primary.

### 3.8 `SheetsUploader.uploadBatch` — new method

```kotlin
fun uploadBatch(payloads: List<SheetPayload>): Boolean
```

POSTs a JSON array. Same dual-gate response check as `upload()` (HTTP 200 + JSON `{"status":"ok"}`). All-or-nothing semantics — on failure, caller retains the whole batch.

### 3.9 `SheetPayload` — 25 new nullable fields

Extended to carry the full 40-column schema (see §5). All new fields are nullable with Gson `serializeNulls()` preserved, so missing values explicitly emit JSON `null`.

## 4. `:app-cn` wiring

### 4.1 `PingTarget.kt` — new file, local to `:app-cn`

```kotlin
data class PingTarget(
    val id: String,                        // "smartflo" | "gateway" | "cloudflare" | "dns"
    val resolveAddress: () -> String?,     // null → unreachable this sample
    val sampler: (address: String) -> PingResult   // ICMP or UDP-DNS
)
```

Four instances constructed in `PingService.onCreate`, each carrying either `PingEngine.runPing(it, 1, PING_TIMEOUT_SEC)` or `udpDnsPinger.runQuery()`.

### 4.2 `Constants.kt` — CN-specific values

```kotlin
object Constants {
    const val WEBHOOK_URL = "https://script.google.com/..."         // unchanged
    const val SMARTFLO_IP = "14.97.20.3"
    const val CLOUDFLARE_IP = "1.1.1.1"
    const val DNS_RESOLVER_IP = "1.1.1.1"

    const val SAMPLE_INTERVAL_MS = 1000L
    const val FLUSH_INTERVAL_MINUTES = 15L
    const val PING_TIMEOUT_SEC = 2
    const val MAX_BUFFER_SAMPLES = 5400                 // per-target buffer cap
    const val GAP_THRESHOLD_MS = 3000L
    const val EXPECTED_SAMPLES_PER_TARGET_PER_WINDOW = 900
    const val EXPECTED_TOTAL_SAMPLES_PER_WINDOW = 3600  // 4 targets × 900

    const val ALLOWED_EMAIL_DOMAIN = "@codingninjas.com"
    const val NOTIF_CHANNEL_ID = "nm_channel"
    const val NOTIF_CHANNEL_NAME = "Network Monitor"
    const val NOTIF_ID = 1001

    const val HTTP_CONNECT_TIMEOUT_SEC = 10L
    const val HTTP_WRITE_TIMEOUT_SEC = 10L
    const val HTTP_READ_TIMEOUT_SEC = 15L

    const val PREFS_NAME = "nm_prefs"
    const val PREF_USER_ID = "user_id"
    const val PREF_LAST_BATCH_JSON = "last_batch_json"          // List<SheetPayload>
    const val PREF_LAST_FLUSH_MS = "last_flush_ms"
    const val PREF_FLUSH_SEQ = "flush_seq"                       // persists across restarts
    const val PREF_PENDING_RETAIN_COUNT = "pending_retain_count"

    const val APP_VERSION = "1.1"
}
```

**Removed from v1.0**: `PING_TARGET`, `PREF_LAST_RESULT`, `PREF_LAST_UPDATE_TIME`, `PREF_LAST_NETWORK_TYPE`.

### 4.3 `PingService.kt` — refactor shape

```
onCreate:
  NotificationHelper.ensureChannel + startForeground (unchanged)
  gatewayResolver = GatewayResolver(connectivityManager)
  wifiCollector   = WifiMetadataCollector(wifiManager, connectivityManager)
  udpDnsPinger    = UdpDnsPinger(Constants.DNS_RESOLVER_IP)
  targets = listOf(
    PingTarget("smartflo",   { Constants.SMARTFLO_IP },   { PingEngine.runPing(it, 1, PING_TIMEOUT_SEC) }),
    PingTarget("gateway",    { gatewayResolver.currentGateway() }, { PingEngine.runPing(it, 1, PING_TIMEOUT_SEC) }),
    PingTarget("cloudflare", { Constants.CLOUDFLARE_IP }, { PingEngine.runPing(it, 1, PING_TIMEOUT_SEC) }),
    PingTarget("dns",        { Constants.DNS_RESOLVER_IP }, { udpDnsPinger.runQuery() })
  )
  buffers = targets.associateWith { SampleBuffer(Constants.MAX_BUFFER_SAMPLES) }
  gatewayUnregister = gatewayResolver.registerOnChange { /* logged; next sampler iter picks it up */ }

onStartCommand:
  if !loopsStarted:
    loopsStarted = true
    targets.forEach { target -> scope.launch { samplerLoop(target) } }
    scope.launch { flusherLoop() }
  return START_STICKY

samplerLoop(target):
  while active:
    t0 = now()
    wifi = wifiCollector.snapshot()                   // BEFORE the ping — consistent with RTT
    address = target.resolveAddress()
    sample = if (address == null)
               Sample(rttMs=null, tsMs=t0, target=target.id, unreachable=true, wifi=wifi)
             else
               val result = target.sampler(address)
               Sample(rttMs=if(result.success) result.rtts.firstOrNull() else null,
                      tsMs=t0, target=target.id, unreachable=false, wifi=wifi)
    buffers[target].add(sample)
    delay((SAMPLE_INTERVAL_MS − elapsed).coerceAtLeast(0L))   // clamp: ping timeout can exceed interval

flusherLoop:
  initialDelay aligned to next :00/:15/:30/:45 (unchanged from v1.0)
  while active: runOneFlush(); delay(quarterMs)

runOneFlush:
  flushStartMs = now()
  scanSnapshot = wifiCollector.scanSnapshot()         // once per window
  drained = targets.associateWith { buffers[it].drain() }
  allSamples = drained.values.flatten()
  // Duty-cycle numerator uses ONLY samples produced in the current window,
  // not retained-from-prior-failure samples — otherwise duty climbs past 1.0
  // after a failed flush because allSamples contains both windows' data.
  currentWindowSamplesCount = allSamples.count { it.tsMs >= flushStartMs - quarterMs }
  deviceAgg = MetricsCalculator.deviceLevelAggregates(allSamples)
  flushSeq = prefs.long(PREF_FLUSH_SEQ) + 1; prefs.put(PREF_FLUSH_SEQ, flushSeq)
  retainedFromPrior = prefs.int(PREF_PENDING_RETAIN_COUNT)
  rows = targets.map { target →
    val samples = drained[target].orEmpty()
    val metrics = MetricsCalculator.aggregate(samples)
    val gaps = MetricsCalculator.gapsCount(samples, GAP_THRESHOLD_MS)
    buildPayload(target, metrics, gaps, deviceAgg, scanSnapshot,
                 currentWindowSamplesCount, flushSeq, retainedFromPrior)
  }
  ok = uploader.uploadBatch(rows)
  if ok:
    persistLastBatch(rows); prefs.put(PREF_PENDING_RETAIN_COUNT, 0)
  else:
    targets.forEach { buffers[it].prepend(drained[it].orEmpty()) }
    prefs.put(PREF_PENDING_RETAIN_COUNT, retainedFromPrior + rows.size)

onDestroy:
  gatewayUnregister(); scope.cancel()
```

**Critical ordering**: `wifi = wifiCollector.snapshot()` is taken BEFORE the ping, so RTT and Wi-Fi fields describe the same network state at `t0`. If the device roams during the ping, the stale sample self-corrects on the next iteration; `bssid_changes_count` captures the transition at window granularity.

### 4.4 `OnboardingActivity.kt` — add location permission step

Insert between `proceedAfterNotifGranted()` and `finalizeOnboarding()`:
1. Check `ACCESS_FINE_LOCATION`. If already granted, skip ahead to battery prompt.
2. Show `AlertDialog` rationale: *"We use location permission only to identify which Wi-Fi access point your device is connected to. We do not collect GPS location or upload coordinates."* Single button: "Grant".
3. Launch `ActivityResultContracts.RequestPermission` for `ACCESS_FINE_LOCATION`.
4. Regardless of grant/deny, proceed to battery prompt. Denial does NOT block onboarding.

`AndroidManifest.xml` adds `<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION"/>`. No second-denial escalation flow (keeps the code smaller; users who deny can re-enable via system settings).

### 4.5 `MainActivity.kt` + `activity_main.xml` — last-sent summary

Layout: vertical `LinearLayout` with seven `TextView`s — `tvGreeting`, `tvLastSentHeader`, `tvSmartflo`, `tvGateway`, `tvCloudflare`, `tvDns`, `tvFooter`.

`onResume` reads `PREF_LAST_BATCH_JSON` and `PREF_LAST_FLUSH_MS`; renders:

```
Hi, alice@codingninjas.com

Last sent 14:15
  smartflo:   p95 34ms  jitter 12ms  loss 0.2%  (900 samples)
  gateway:    p95  8ms  jitter  2ms  loss 0.0%  (900 samples)
  cloudflare: p95 18ms  jitter  5ms  loss 0.1%  (900 samples)
  dns:        p95 22ms  jitter  4ms  loss 0.0%  (900 samples)
Next flush 14:30 • Wi-Fi: CN-Office 5GHz (-58 dBm) • duty 97%
```

No auto-refresh. No bad-call button (dropped — all correlation to reported calls happens Sheet-side via timestamp matching).

### 4.6 Unchanged in `:app-cn`

`BootReceiver.kt`, `NotificationHelper.kt` — no changes. Foreground-service lifecycle, OEM-hardening behavior, retain-on-failure semantics all preserved from v1.0.

## 5. Schema — 40 columns, 4 rows per flush

Column order groups left-to-right: identity → target → RTT metrics → sample counts → Wi-Fi context → scan context → operational telemetry.

```
window_start, user_id, device_model, android_sdk, oem_skin, app_version,
target, gateway_ip, unreachable_target,
avg_rtt_ms, min_rtt_ms, max_rtt_ms,
p50_rtt_ms, p95_rtt_ms, p99_rtt_ms,
jitter_ms, packet_loss_pct,
samples_count, reachable_samples_count, max_rtt_offset_sec,
gaps_count, bssid_changes_count, ssid_changes_count,
rssi_min, rssi_avg, rssi_max,
primary_bssid, primary_ssid, primary_frequency_mhz, primary_link_speed_mbps,
current_bssid, current_rssi,
network_type_dominant, vpn_active,
visible_aps_count, best_available_rssi, sticky_client_gap_db,
duty_cycle_pct, flush_seq, retain_merged_count
```

| Column | Source | Per-target vs per-window |
|---|---|---|
| `window_start` | `drained.minOf { it.tsMs }`, ISO-8601 | per-window (same across 4 rows) |
| `user_id`, `device_model`, `android_sdk`, `oem_skin`, `app_version` | Prefs + `Build.*` + `Constants.APP_VERSION` | per-window |
| `target` | `PingTarget.id` | **per-target** |
| `gateway_ip` | `gatewayResolver.currentGateway()` at flush time | per-window |
| `unreachable_target` | true if samples for this target had `unreachable=true` for the whole window | **per-target** |
| `avg/min/max/p50/p95/p99_rtt_ms`, `jitter_ms`, `packet_loss_pct`, `max_rtt_offset_sec` | `MetricsCalculator.aggregate(drained[target])` | **per-target** |
| `samples_count`, `reachable_samples_count` | from the same aggregate | **per-target** |
| `gaps_count` | `MetricsCalculator.gapsCount(drained[target], 3000ms)` | **per-target** |
| `bssid_changes_count`, `ssid_changes_count`, `rssi_min/avg/max`, `primary_bssid`, `primary_ssid`, `primary_frequency_mhz`, `primary_link_speed_mbps`, `current_bssid`, `current_rssi`, `network_type_dominant`, `vpn_active` | `deviceLevelAggregates(allSamples)` | per-window (duplicated across 4 rows) |
| `visible_aps_count`, `best_available_rssi`, `sticky_client_gap_db` | `scanSnapshot` at flush time; `sticky_client_gap_db = best_available_rssi − current_rssi` | per-window |
| `duty_cycle_pct` | `currentWindowSamplesCount / EXPECTED_TOTAL_SAMPLES_PER_WINDOW` where `currentWindowSamplesCount = allSamples.count { it.tsMs >= flushStartMs - 15min }` (excludes retained-from-prior-failure samples so duty stays ≤ 1.0 after recovered failures) | per-window |
| `flush_seq` | `PREF_FLUSH_SEQ` monotonic counter, persists | per-window |
| `retain_merged_count` | rows retained from the previous failed flush (0 on success, 4 on single-failure retry) | per-window |

Null semantics: any column whose source is unavailable (e.g. `rssi_avg` when device was on cellular the whole window) emits JSON `null` → blank cell in Sheet. Explicit `null` over absence, via Gson `serializeNulls()`.

## 6. Apps Script webhook

- `doPost(e)` parses `e.postData.contents` as JSON. Must be an array; object payloads return 400.
- On first write to an empty Sheet, writes the header row (40 columns in the order above).
- For each element, builds a row by looking up each column name in that element's keys. Missing keys → blank cell. Appends all rows to the `metrics` sheet.
- Returns `{"status":"ok","rows_appended":N}`. `SheetsUploader`'s existing dual-gate check (`status == "ok"`) works unchanged.
- No markers tab, no event_type routing, no backward-compat branch for v1.0-shaped single-object payloads (Sheet is not live; clean cutover).

## 7. Test strategy

**Principle**: framework-touching classes split into pure helpers (JVM-tested in `:core`) and framework bindings (validated on device).

### 7.1 Unit tests to add (26 new, all in `:core`)

`MetricsCalculatorTest` — 10 new cases covering unreachable-sample exclusion, `gapsCount`, and `deviceLevelAggregates` (BSSID/SSID transitions, dominant network type, RSSI min/avg/max, empty-sample defaults, all-cellular path).

`SheetsUploaderTest` — 5 new `uploadBatch` cases: 200+ok, 500, 200+error, connection failure, array serialization verified via `MockWebServer.takeRequest().body`.

`GatewayResolverTest` — 4 cases on `pickDefaultIPv4Gateway`: default IPv4 picked, no default route, IPv6-only default, multiple defaults (first wins).

`WifiMetadataCollectorTest` — 4 cases on `stripSsidQuotes`: quoted-wrapping removed, unquoted preserved, `<unknown ssid>` sentinel preserved, null input returns null.

`UdpDnsPingerTest` — 3 cases: happy-path resolve (pointed at a localhost UDP echo or recorded response), timeout on unreachable resolver, malformed response returns success=false.

Existing 26 tests remain green; `Sample`'s new fields all default.

### 7.2 On-device validation matrix

Run sequentially on the engineer's primary device before any sideload to other users. Each ~20–30 min.

| Scenario | Expected in Sheet |
|---|---|
| Baseline 1-hour run on office Wi-Fi | 4 flushes × 4 rows = 16 rows. All RTT metrics populated. BSSID/SSID non-null. `duty_cycle_pct ≥ 0.95`. `flush_seq` increments by 1. `vpn_active = false`. `visible_aps_count > 0`. |
| Airplane mode ON for 20s mid-window, then OFF | `gaps_count ≥ 1`. `unreachable_target = true` for gateway target if gateway disappeared. |
| Walk to a different AP | `bssid_changes_count ≥ 1`. `primary_bssid` may change. `sticky_client_gap_db` near 0 after roam. |
| Connect to corporate VPN | `vpn_active = true` in that window's rows. |
| Force-stay on weak AP (move far from current AP without reconnecting) | `rssi_min` drops, `sticky_client_gap_db ≥ 15` if a stronger AP was audible. |
| Device reboot, wait 15 min | BootReceiver restarts service. Flush lands. `flush_seq` continues from persisted value. |
| Swipe app from recents | Foreground service survives (v1.0 OEM hardening intact). Next flush still lands. |

No re-run of the v1.0 multi-OEM matrix — the service lifecycle is unchanged; only the samplers' contents changed.

## 8. Rollout sequence

1. Deploy updated Apps Script. `curl` a 3-element test array; verify 3 rows land with 40 columns.
2. `./gradlew :app-cn:assembleRelease` signed with existing CN keystore.
3. Sideload v1.1 on engineer's primary device (`adb install -r`).
4. Run through §7.2 over 1–2 hours. Fix anything surprising.
5. Distribute `NetworkMonitorCN-v1.1.apk` via Drive link to 4–5 test users. Each installs over v1.0 in place.
6. After 2–3 days of collection, verify §11 acceptance criteria against a reported bad-call incident.

`applicationId = com.codingninjas.networkmonitor` unchanged → in-place upgrade from v1.0, no data loss.

## 9. Risks

| Risk | Mitigation |
|---|---|
| Location permission denied → no BSSID signal | `WifiMetadataCollector` returns `"permission_denied"` as BSSID; Sheet can filter. Degrades to "overall Wi-Fi health" diagnosis. Not a blocker. |
| Carrier CGN makes gateway un-pingable | `unreachable=true` excludes from loss denominator. `unreachable_target=true` in the Sheet distinguishes from fake 100% loss. |
| 4 concurrent samplers trigger OEM battery saver | Foreground service protection preserved. Monitor `duty_cycle_pct` post-rollout. Fallback: round-robin 4 targets at 4 Hz from one sampler (`:app-cn`-only change). |
| Apps Script 6-min execution timeout | Appending 4 rows completes in <500ms. Non-issue. |
| Sheet 10M-cell ceiling | 5 users × 4 flushes/hr × 4 rows × 40 cols ≈ 3.2k cells/hr. Months of runway. Full 300-user rollout would hit the ceiling in ~3 weeks; that's a v1.2 BigQuery migration trigger, not a v1.1 concern. |
| Framework bindings have no unit tests | §7.2 on-device matrix covers realistic failure modes. Pure helpers unit-tested. |
| Scan permission expanded (`ACCESS_FINE_LOCATION`) | Already the permission we need for BSSID — no additional scope. Graceful degradation if denied. |
| Keystore loss | Unchanged from v1.0; key must remain backed up outside repo. In-place upgrade depends on same-key signing. |

## 10. Open questions (resolve during implementation)

1. **First-flush timing** when service starts mid-quarter: aligned flusher waits to next :00/:15/:30/:45, so first v1.1 flush may have <900 samples per target. `duty_cycle_pct` reflects this honestly; not a bug.
2. **Gateway address changing during in-flight ping**: one stale sample is noise-level; next iteration self-corrects; `bssid_changes_count` captures the window-level signal. Wi-Fi snapshot taken BEFORE the ping ensures RTT and BSSID describe the same state at `t0`.
3. **`flush_seq` starting value**: 0 on first install; persists via `PREF_FLUSH_SEQ`. Restart continues counting.
4. **`sticky_client_gap_db` when `current_rssi` is null** (cellular): column is null. Sheet filter `WHERE vpn_active = FALSE AND network_type_dominant = 'wifi'` isolates the Wi-Fi diagnostic slice cleanly.

## 11. Acceptance criteria

v1.1 ships successfully when, after 2–3 days of field data:

1. Every flush window in the Sheet has exactly 4 rows per active device.
2. `duty_cycle_pct ≥ 0.95` on devices where v1.0 previously hit that threshold.
3. At least one reported bad-call incident can be diagnosed from Sheet data alone in <5 min of pivoting: which target showed elevated p99/jitter/loss, which BSSID the user was on, whether a roam/gap happened in that 15-min window, whether VPN was active, whether a stronger AP was available but the device stayed sticky.
4. `gaps_count`, `bssid_changes_count`, `visible_aps_count`, `vpn_active` populate correctly under deliberate provocation (airplane mode, AP walk, VPN connect).
5. The `:core` test suite (26 existing + 26 new = 52) is green.

## 12. Out of scope (explicit deferrals)

**Deferred to v1.2 if v1.1 data proves insufficient:**
- Dual-size pings for MTU discovery
- TCP SYN-ACK RTT to Smartflo SIP port 5060
- BigQuery backend migration (trigger: full 300-user rollout → Sheet ceiling)

**Confirmed out of scope due to infrastructure / permission constraints:**
- Aruba-side channel utilization, noise floor, airtime fairness (controller-exclusive)
- Automatic VoIP call detection (privileged APIs; unreliable across OEMs)
- Packet capture / pcap (requires root or VpnService hijack)
- WebRTC peer-connection stats (not the VoIP stack in use)
- RTP probes on ports 20000–40000 (Smartflo doesn't echo)

**Explicitly dropped via this design's brainstorm:**
- "I had a bad call" marker button — Sheet-side timestamp correlation is the substitute
- Rich per-target dashboard with color bands and Wi-Fi card — deferred to Pulseboard public v1.1 track
- Pulseboard public build wiring — entirely separate spec, separate track

## 13. Files changed

### `:core` (library)
- `Sample.kt` — 3 new defaulted fields
- `WifiSnapshot.kt` — NEW
- `ScanSnapshot.kt` — NEW
- `WifiMetadataCollector.kt` — NEW
- `GatewayResolver.kt` — NEW
- `UdpDnsPinger.kt` — NEW
- `DeviceAggregates.kt` — NEW
- `MetricsCalculator.kt` — aggregate() update, gapsCount() + deviceLevelAggregates() added
- `SheetsUploader.kt` — uploadBatch() added
- `SheetPayload.kt` — 25 new nullable fields

### `:core` (tests)
- `MetricsCalculatorTest.kt` — 10 new
- `SheetsUploaderTest.kt` — 5 new
- `GatewayResolverTest.kt` — NEW (4 tests)
- `WifiMetadataCollectorTest.kt` — NEW (3 tests)
- `UdpDnsPingerTest.kt` — NEW (3 tests)

### `:app-cn`
- `Constants.kt` — v1.1 target IPs, new prefs keys, APP_VERSION bump
- `PingTarget.kt` — NEW (data class + 4 instances)
- `service/PingService.kt` — refactor to 4 samplers + scan-at-flush + UDP DNS pinger wiring
- `ui/MainActivity.kt` — last-sent summary
- `res/layout/activity_main.xml` — rewrite for summary view
- `OnboardingActivity.kt` — location permission step
- `AndroidManifest.xml` — ACCESS_FINE_LOCATION permission
- `build.gradle.kts` — versionCode 2, versionName 1.1

### Apps Script (server)
- Array-payload handler
- 40-column header auto-write
- `{"status":"ok","rows_appended":N}` response

### Docs
- `README.md` — updated "What it does" + permissions list + known limitations
- This spec at `docs/superpowers/specs/2026-04-21-voip-diagnostic-design.md`
- Source plan at `docs/superpowers/plans/2026-04-21-voip-diagnostic-enhancement.md`
