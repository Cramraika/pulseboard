# Network Monitor CN — Design Spec

**Date:** 2026-04-16
**Owner:** chinmay.ramraika@codingninjas.com
**Status:** Approved for implementation

## 1. Purpose

Internal-use Android app that continuously samples network quality (ping RTT, jitter, packet loss) on employee devices and logs aggregated metrics to a Google Sheet for centralized monitoring. Distributed as a side-loaded signed APK; no Google Play publication.

## 2. Goals & non-goals

### Goals
- Sample ICMP ping RTT **once per second**, continuously, as long as device is awake.
- Every 15 minutes, compute rich aggregate metrics (avg, min, max, p50, p95, p99, jitter, packet loss) and POST one row to a Google Sheet.
- Survive app swipe-close, OS memory pressure (best-effort), and device reboots.
- Blocking email-gated onboarding: `@codingninjas.com` only.

### Non-goals (v1)
- Play Store distribution.
- Multi-target ping (only `8.8.8.8`).
- Live dashboard refresh (dashboard reads last-flushed snapshot from `SharedPreferences`).
- Historical charting in-app.
- Crash reporting (Crashlytics etc.).
- In-app auto-update checker.
- Raw per-sample upload. *(Aggregate rows only. Raw CSV to Drive is a post-v1 layerable enhancement.)*
- Sheet rotation / archiving (handled manually; will not fill for years).

## 3. Architecture

```
┌─────────────────────────────────────────────────────────────┐
│ OnboardingActivity (blocking, one-time)                     │
│  - Email prompt → validate format + @codingninjas.com       │
│  - Request POST_NOTIFICATIONS (block onboarding if denied)  │
│  - Request battery-opt exemption                            │
│  - Save email + onboarded=true to prefs                     │
│  - Start PingService + launch MainActivity + finish()       │
└────────────────────────┬────────────────────────────────────┘
                         │ startForegroundService()
                         ▼
┌─────────────────────────────────────────────────────────────┐
│ PingService  (foreground service, type=dataSync)            │
│  - Persistent notification "Network Monitor — tap to open"  │
│  - Two coroutines on Dispatchers.IO:                        │
│      sampler:  every 1s → PingEngine.runPing(count=1,       │
│                 timeout=2) → SampleBuffer.add()             │
│      flusher:  every 15m → SampleBuffer.drain() →           │
│                 MetricsCalculator.aggregate() →             │
│                 SheetsUploader.upload()                     │
│                 on success: persist last_result +           │
│                             last_update_time to prefs       │
│                 on failure: buffer.prepend(retained)        │
└────────────────────────┬────────────────────────────────────┘
                         │ writes SharedPreferences
                         ▼
┌─────────────────────────────────────────────────────────────┐
│ MainActivity (read-only dashboard)                          │
│  - onResume: read prefs, render 6 cells                     │
│  - No menu, no live updates                                 │
└─────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────┐
│ BootReceiver (BOOT_COMPLETED)                               │
│  - If email saved: start PingService                        │
└─────────────────────────────────────────────────────────────┘
```

## 4. Data model

### Sample (in-memory only)
```kotlin
data class Sample(
    val rttMs: Double?,    // null = ping failed (timeout/unreachable)
    val tsMs: Long         // absolute wall-clock at sample time (System.currentTimeMillis())
)
```

Offsets (for `max_rtt_offset_sec`) are derived on drain from `tsMs`, not stored on the sample. This makes retained-and-merged windows self-consistent: `window_start = samples.minOf { it.tsMs }` regardless of how many flushes were rolled together.

### NetworkMetrics (aggregate of one window)
```kotlin
data class NetworkMetrics(
    val avgPing: Double?,          // null when all samples in window failed
    val minPing: Double?,
    val maxPing: Double?,
    val p50Ping: Double?,
    val p95Ping: Double?,
    val p99Ping: Double?,
    val jitter: Double?,           // population stddev
    val packetLoss: Double,        // percent, 0.0..100.0 — always valid
    val samplesCount: Int,         // <900 means sampler was throttled
    val maxRttOffsetSec: Int?      // seconds from window_start to worst RTT; null if no RTTs
)
```

All non-null numeric fields rounded to 1 decimal place for display and upload. Null values become blank cells in the Sheet (Gson serializes null fields as JSON `null`; Apps Script writes empty cells). Consumers reading the Sheet can visually identify total-loss windows at a glance by looking for the empty RTT columns combined with `packet_loss_pct = 100.0`.

### SheetPayload (Google Sheet schema — 15 columns)

Total-loss windows (no successful pings in the interval) leave columns E–K and N blank. `packet_loss_pct` and `samples_count` are always present.

| Column | JSON key | Type | Example | Blank on total loss? |
|---|---|---|---|---|
| A | `window_start` | ISO-8601 UTC | `2026-04-16T12:00:00Z` | no |
| B | `user_id` | email string | `chinmay.ramraika@codingninjas.com` | no |
| C | `device_model` | string | `Samsung SM-G998B` | no |
| D | `network_type` | string | `WiFi` / `Mobile` / `Ethernet` / `Unknown` | no |
| E | `avg_ping_ms` | double\|null | `42.3` | **yes** |
| F | `min_ping_ms` | double\|null | `18.1` | **yes** |
| G | `max_ping_ms` | double\|null | `812.0` | **yes** |
| H | `p50_ping_ms` | double\|null | `38.0` | **yes** |
| I | `p95_ping_ms` | double\|null | `74.5` | **yes** |
| J | `p99_ping_ms` | double\|null | `340.0` | **yes** |
| K | `jitter_ms` | double\|null | `28.7` | **yes** |
| L | `packet_loss_pct` | double | `100.0` | no |
| M | `samples_count` | int | `900` | no |
| N | `max_rtt_offset_sec` | int\|null | `342` | **yes** |
| O | `app_version` | string | `1.0` | no |

## 5. Key algorithms

### 5.1 Sampling loop (1 Hz target)

```kotlin
while (coroutineContext.isActive) {
    val t0 = System.currentTimeMillis()
    val result = PingEngine.runPing(Constants.PING_TARGET, count=1, timeoutSec=2)
    val rtt = if (result.success) result.rtts.firstOrNull() else null
    buffer.add(Sample(rttMs = rtt, tsMs = t0))
    val elapsed = System.currentTimeMillis() - t0
    delay((1000L - elapsed).coerceAtLeast(0))
}
```

Drift handling: when a single ping exceeds 1 sec (timeout), the next iteration loops immediately without compensating — one sample is effectively lost and recorded as loss. This is correct for latency monitoring: during an outage, we miss samples but do not distort the ratio.

### 5.1.1 Flusher — wall-clock alignment, capture order, partial-window semantics

Flushes fire at quarter-hour boundaries (`:00`, `:15`, `:30`, `:45` UTC) so aggregate rows across devices are directly comparable by window start.

```kotlin
private suspend fun flusherLoop() {
    val quarterMs = 15 * 60_000L
    val initialDelay = quarterMs - (System.currentTimeMillis() % quarterMs)
    delay(initialDelay)
    while (coroutineContext.isActive) {
        runOneFlush()
        delay(quarterMs)
    }
}

private suspend fun runOneFlush() {
    val drained = buffer.drain()
    if (drained.isEmpty()) return            // service just started / no samples yet
    val metrics = MetricsCalculator.aggregate(drained)
    val networkType = NetworkUtils.getNetworkType(applicationContext)   // capture HERE, not earlier
    val payload = buildPayload(metrics, drained.minOf { it.tsMs }, networkType)
    val uploaded = SheetsUploader.upload(payload)
    if (uploaded) {
        persistToPrefs(metrics, networkType)  // writes PREF_LAST_RESULT, PREF_LAST_UPDATE_TIME, PREF_LAST_NETWORK_TYPE
    } else {
        buffer.prepend(drained)               // retain for next flush
    }
}
```

**Capture-order invariant:** `networkType` is snapshotted once per flush, used both in the uploaded payload (Sheet column D) *and* in the persisted `PREF_LAST_NETWORK_TYPE`. Same value in both places → dashboard NETWORK cell always matches the metrics it sits beside.

**Partial first window:** when the service starts at `:14:30`, the first flush at `:15:00` covers ~30 seconds. `samples_count` will reflect this (~30 instead of 900). Percentiles from such small samples are noisier but still valid. Expected behavior on every cold start; Sheet consumers should treat `samples_count` as the authoritative denominator.

**Empty drain:** if `drained.isEmpty()` (service killed + restarted seconds before a boundary), we skip the flush silently — no row sent. `samples.minOf { it.tsMs }` is never called on empty input.

### 5.2 Percentile (linear interpolation)

Matches NIST / Excel `PERCENTILE` / Grafana / JMeter conventions, so numbers are cross-comparable with standard monitoring tools.

```kotlin
private fun percentile(sorted: List<Double>, p: Double): Double {
    // Caller (MetricsCalculator.aggregate) must filter empty RTT lists before reaching this.
    // §5.4 routes total-loss windows to null fields without invoking percentile().
    require(sorted.isNotEmpty()) { "percentile() called on empty list — caller bug" }
    if (sorted.size == 1) return sorted[0]
    val rank = (p / 100.0) * (sorted.size - 1)
    val lo = rank.toInt()
    val hi = (lo + 1).coerceAtMost(sorted.size - 1)
    val frac = rank - lo
    return sorted[lo] + frac * (sorted[hi] - sorted[lo])
}
```

### 5.3 Retain-on-failure (bounded)

On upload failure (HTTP non-2xx — see §9 for why all Apps Script errors now return 5xx), drained samples are prepended back to the buffer with `buffer.prepend(retained)`. Next flush covers the combined window. `window_start = samples.minOf { it.tsMs }` is computed fresh each drain, so merged windows carry the earliest original timestamp; `max_rtt_offset_sec` is relative to this combined start and always 0 ≤ offset < `samples_count` seconds.

**Buffer cap:** `Constants.MAX_BUFFER_SAMPLES = 5400` (90 min of samples = 6 failed flushes). If the cap is exceeded on `add()`, the oldest sample is evicted. Prevents OOM on extended offline periods.

**Trade-off accepted:** if the service is killed between flush attempts, retained samples are lost (no on-disk queue). In-memory retry is v1 simplification per user approval.

### 5.4 Total-loss window handling

If `samples.none { it.rttMs != null }` (every ping failed for the entire window):
- `packet_loss_pct = 100.0`, `samples_count` = actual count.
- All RTT-derived fields (`avg`, `min`, `max`, `p50`, `p95`, `p99`, `jitter`, `max_rtt_offset_sec`) are `null`.
- Gson serializes nulls (`GsonBuilder().serializeNulls()`); Apps Script writes blank cells for undefined-or-null JSON values.
- Row is still uploaded — it's valuable to know an outage happened.

### 5.5 Percentile for VoIP-relevant monitoring

Primary signals for VoIP quality observable in the aggregate row:
- **p99_ping_ms** — tail latency; >300 ms signals degraded call quality windows.
- **jitter_ms** — >30 ms typically exceeds default VoIP jitter-buffer capacity.
- **packet_loss_pct** — >3 % produces audible artifacts.

ICMP RTT is a proxy for network health; not a direct measure of RTP inter-arrival jitter on a specific media server.

## 6. Android components

### 6.1 OnboardingActivity

Sequence on first launch:
1. Check `prefs.getString(PREF_USER_ID)`. If non-blank, route to MainActivity immediately.
2. Inflate email layout.
3. On button tap:
   - Trim input. Validate with `android.util.Patterns.EMAIL_ADDRESS`.
   - Require suffix `@codingninjas.com`. On fail: Toast "Use your @codingninjas.com work email".
   - Runtime prompt `POST_NOTIFICATIONS` (API 33+):
     - If granted → continue.
     - If denied once → show rationale AlertDialog ("Monitoring needs this notification to run in the background"), then re-prompt.
     - If denied a second time → show **"Open Settings" AlertDialog** launching `Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS, ...)` (on Android 13+ the system blocks further prompts; Settings is the only path forward). If user declines again, stay on onboarding screen.
   - Launch `Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)` with package URI. Proceed regardless of user choice.
   - Save email to prefs.
   - `ContextCompat.startForegroundService(Intent(this, PingService::class.java))`.
   - `startActivity(MainActivity)` + `finish()`.

### 6.2 PingService (foreground service, type=dataSync)

- **CoroutineScope**: `CoroutineScope(SupervisorJob() + Dispatchers.IO)`. `SupervisorJob` ensures a crash in the sampler doesn't cancel the flusher (and vice versa) — each child coroutine fails independently. Each loop body is wrapped in `try/catch` that logs the exception and continues iteration (so a transient `IOException` from `PingEngine` doesn't kill the sampler).
- `onCreate`: create notification channel (`NOTIF_CHANNEL_ID`, IMPORTANCE_LOW), build static ongoing notification, `startForeground(NOTIF_ID, notif, FOREGROUND_SERVICE_TYPE_DATA_SYNC)`.
- `onStartCommand`: idempotent launch of sampler + flusher coroutines (see §5.1 and §5.1.1); return `START_STICKY`.
- `onDestroy`: `scope.cancel()`.

**Notification text:** `"Network Monitor — tap to open"`. Never updates. Tapping launches MainActivity via `PendingIntent`.

See **§11a** for `SheetsUploader` HTTP client configuration, **§11b** for logging tags.

### 6.3 SampleBuffer

Thread-safe `ArrayDeque<Sample>` wrapped in `synchronized(lock)`. Each sample carries absolute `tsMs`; no internally-tracked window start.

```kotlin
class SampleBuffer(private val maxSize: Int = Constants.MAX_BUFFER_SAMPLES) {
    private val samples = ArrayDeque<Sample>()
    private val lock = Any()

    fun add(sample: Sample) = synchronized(lock) {
        samples.addLast(sample)
        while (samples.size > maxSize) samples.removeFirst()   // drop oldest on overflow
    }

    fun drain(): List<Sample> = synchronized(lock) {
        val out = samples.toList()
        samples.clear()
        out
    }

    fun prepend(retained: List<Sample>) = synchronized(lock) {
        val merged = ArrayDeque<Sample>(retained.size + samples.size)
        merged.addAll(retained)
        merged.addAll(samples)
        while (merged.size > maxSize) merged.removeFirst()
        samples.clear()
        samples.addAll(merged)
    }
}
```

The flusher derives `window_start_ms = drained.minOf { it.tsMs }` after draining. Offsets for `max_rtt_offset_sec` are computed as `((sample.tsMs - window_start_ms) / 1000).toInt()`.

### 6.4 MainActivity

- Read-only. No menu. No "Change Email".
- `onCreate`: bind views, read email from prefs, render greeting.
- `onResume`: read `PREF_LAST_RESULT` (JSON of `NetworkMetrics`) + `PREF_LAST_UPDATE_TIME` + `PREF_LAST_NETWORK_TYPE` from prefs. If present, render 6 cells; else show "No data yet" message.
- **Layout cells**: `AVG PING` / `MAX` / `P99` / `JITTER` / `PACKET LOSS` / `NETWORK`.
- The `NETWORK` cell displays the **persisted** `last_network_type` (captured at flush time, alongside the metrics), **not** the live network at dashboard-view time. This keeps all six cells describing the same measurement window — no confusing "WiFi" label over metrics collected on Mobile.
- Any nullable metric displayed as `"—"` (total-loss window).

### 6.5 BootReceiver

```kotlin
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getString(Constants.PREF_USER_ID, "")?.isNotBlank() == true) {
            ContextCompat.startForegroundService(
                context, Intent(context, PingService::class.java)
            )
        }
    }
}
```

## 7. File plan

### Delete
- `app/src/main/java/com/codingninjas/networkmonitor/MainActivity.kt` (old Compose scaffold)
- `app/src/main/java/com/codingninjas/networkmonitor/ui/theme/Color.kt`
- `app/src/main/java/com/codingninjas/networkmonitor/ui/theme/Theme.kt`
- `app/src/main/java/com/codingninjas/networkmonitor/ui/theme/Type.kt`
- `app/src/main/java/com/codingninjas/networkmonitor/PingWorker.kt`
- `app/src/main/java/com/codingninjas/networkmonitor/WorkScheduler.kt`

### Rewrite
| File | Change |
|---|---|
| `Constants.kt` | Drop `WORK_*` and `PING_COUNT`; add `SAMPLE_INTERVAL_MS=1000L`, `FLUSH_INTERVAL_MINUTES=15L`, `MAX_BUFFER_SAMPLES=5400`, `NOTIF_CHANNEL_ID="nm_channel"`, `NOTIF_ID=1001`, `ALLOWED_EMAIL_DOMAIN="@codingninjas.com"`, `PREF_LAST_RESULT="last_result"`, `PREF_LAST_UPDATE_TIME="last_update_time"`, `PREF_LAST_NETWORK_TYPE="last_network_type"`, HTTP timeouts: `HTTP_CONNECT_TIMEOUT_SEC=10L`, `HTTP_WRITE_TIMEOUT_SEC=10L`, `HTTP_READ_TIMEOUT_SEC=15L`. |
| `MetricsCalculator.kt` | Replace `calculate(PingResult)` with `aggregate(samples: List<Sample>): NetworkMetrics` (window_start derived via `samples.minOf { tsMs }`). Linear-interpolation percentiles. Nullable RTT fields on total-loss windows per §5.4. |
| `SheetsUploader.kt` | `SheetPayload` = 15 fields per §4. Nullable `Double?` for RTT-derived fields. Use `GsonBuilder().serializeNulls().create()` so nulls become JSON `null`. Configure OkHttp with explicit timeouts. Success signal = `response.isSuccessful` only. |
| `OnboardingActivity.kt` | Email (not name), domain check, tiered permission gauntlet (§6.1), start PingService |
| `activity_onboarding.xml` | EditText hint `"Your @codingninjas.com email"`, `inputType="textEmailAddress"` |
| `ui/MainActivity.kt` | Remove menu entirely; render 6-cell set `AVG/MAX/P99/JITTER/LOSS/NETWORK`; NETWORK cell from persisted `PREF_LAST_NETWORK_TYPE`. |
| `activity_main.xml` | Update cell labels (P95 → P99); remove any menu-affordance stubs |

### New
| File | Purpose |
|---|---|
| `service/PingService.kt` | Foreground service + sampler + flusher coroutines |
| `SampleBuffer.kt` | Thread-safe sample accumulator |
| `BootReceiver.kt` | Auto-restart service on BOOT_COMPLETED |
| `NotificationHelper.kt` | Channel + builder (extracted for clarity) |

### Unchanged
- `PingEngine.kt` — invoked with `count=1, timeoutSec=2` from service
- `NetworkUtils.kt`

## 8. Gradle / manifest / theme

### 8.1 `app/build.gradle.kts`

**Final-state dependency inventory:**

| Dependency | Status | Used by |
|---|---|---|
| `androidx.core:core-ktx` | existing, keep | framework-wide |
| `androidx.lifecycle:lifecycle-runtime-ktx` | existing, keep (shared coroutine scoping) | — |
| `androidx.work:work-runtime-ktx:2.9.0` | **remove** | was used by deleted `PingWorker`/`WorkScheduler` |
| `com.squareup.okhttp3:okhttp:4.12.0` | existing, keep | `SheetsUploader` |
| `com.google.code.gson:gson:2.10.1` | existing, keep | `SheetsUploader` (`SheetPayload` JSON) |
| `org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3` | existing, keep | `PingService` coroutines |
| `androidx.lifecycle:lifecycle-viewmodel-ktx` | existing, remove (unused) | — |
| `androidx.lifecycle:lifecycle-livedata-ktx` | existing, remove (unused) | — |
| `androidx.appcompat:appcompat:1.7.0` | **add** | `AppCompatActivity` base class |
| `com.google.android.material:material:1.12.0` | **add** | `MaterialButton`, `MaterialCardView` |
| `androidx.cardview:cardview:1.0.0` | **add** | `activity_main.xml` layout |
| JUnit (`junit`, `androidx.test.ext:junit`) | existing, keep (tests) | — |
| All `androidx.compose.*`, `androidx.activity.compose` | **remove** | Compose no longer used |

**Also remove:**
- `alias(libs.plugins.kotlin.compose)` plugin line
- `buildFeatures { compose = true }` block
- All `debugImplementation(…compose…)` and `androidTestImplementation(…compose…)` lines

**Add signing config:**
```kotlin
signingConfigs {
    create("release") {
        storeFile = file(project.findProperty("KEYSTORE_PATH") as String? ?: "")
        storePassword = project.findProperty("KEYSTORE_PASSWORD") as String? ?: ""
        keyAlias = project.findProperty("KEY_ALIAS") as String? ?: ""
        keyPassword = project.findProperty("KEY_PASSWORD") as String? ?: ""
    }
}
buildTypes {
    release {
        // Intentionally disabled for internal side-loaded builds.
        // Preserves full class/method names in adb logcat and in Gson/OkHttp reflection.
        isMinifyEnabled = false
        signingConfig = signingConfigs.getByName("release")
        proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
}
```

### 8.2 `libs.versions.toml`

Strip unused Compose entries; no new version refs required (inline versions for the three new libraries is fine, or promote to `[versions]`).

### 8.3 `themes.xml`

```xml
<style name="Theme.NetworkMonitorCN"
       parent="Theme.MaterialComponents.DayNight.NoActionBar">
    <item name="colorPrimary">#4F46E5</item>
    <item name="android:windowBackground">#F5F5F5</item>
</style>
```

Fixes the `AppCompatActivity` crash caused by the current `android:Theme.Material.Light.NoActionBar` parent.

### 8.4 `AndroidManifest.xml`

**Final-state permission list** (every `<uses-permission>` that must exist after edits):

```xml
<uses-permission android:name="android.permission.INTERNET"/>                          <!-- existing, keep -->
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE"/>               <!-- existing, keep -->
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED"/>             <!-- existing, keep -->
<uses-permission android:name="android.permission.POST_NOTIFICATIONS"/>                 <!-- new -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE"/>                 <!-- new -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC"/>       <!-- new (API 34+) -->
<uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"/><!-- new -->
```

**Add inside `<application>`:**
```xml
<service
    android:name=".service.PingService"
    android:exported="false"
    android:foregroundServiceType="dataSync"/>

<receiver
    android:name=".BootReceiver"
    android:exported="true">
    <intent-filter>
        <action android:name="android.intent.action.BOOT_COMPLETED"/>
    </intent-filter>
</receiver>
```

## 9. Google Apps Script (webhook)

Replace the existing `Code.gs` with this, redeploy the existing Web App deployment (Manage deployments → edit → New version). The `/exec` URL stays constant; `Constants.WEBHOOK_URL` unchanged.

```javascript
const SHEET_NAME = "Sheet1";

const HEADERS = [
  "window_start", "user_id", "device_model", "network_type",
  "avg_ping_ms", "min_ping_ms", "max_ping_ms",
  "p50_ping_ms", "p95_ping_ms", "p99_ping_ms",
  "jitter_ms", "packet_loss_pct",
  "samples_count", "max_rtt_offset_sec", "app_version"
];

function doPost(e) {
  // Unhandled exceptions are auto-logged to Apps Script → Executions in the editor.
  // No try/catch here — but the client does NOT rely on HTTP status alone (see below).
  const data = JSON.parse(e.postData.contents);
  const sheet = SpreadsheetApp.getActiveSpreadsheet().getSheetByName(SHEET_NAME);
  if (sheet.getLastRow() === 0) sheet.appendRow(HEADERS);
  sheet.appendRow([
    data.window_start, data.user_id, data.device_model, data.network_type,
    data.avg_ping_ms, data.min_ping_ms, data.max_ping_ms,
    data.p50_ping_ms, data.p95_ping_ms, data.p99_ping_ms,
    data.jitter_ms, data.packet_loss_pct,
    data.samples_count, data.max_rtt_offset_sec, data.app_version
  ]);
  return ContentService.createTextOutput(JSON.stringify({status: "ok"}))
    .setMimeType(ContentService.MimeType.JSON);
}

function doGet(e) {
  return ContentService.createTextOutput("Network Monitor webhook is live.");
}
```

**Two-gate success check in the client (defense in depth).** Apps Script deployed as web app does *not* reliably return HTTP 5xx on failure — an unhandled exception can come back as HTML wrapped in a 200 or 302. Therefore `SheetsUploader` treats the upload as successful **only** if both conditions hold:

```kotlin
val body = response.body?.use { it.string() } ?: ""
val httpOk = response.isSuccessful
val bodyOk = try { JSONObject(body).optString("status") == "ok" } catch (_: Exception) { false }
val success = httpOk && bodyOk
```

- A 5xx from Google or network exception → `httpOk=false` → retry.
- A 200 with HTML error page → `bodyOk=false` (JSON parse fails) → retry.
- A 200 with `{"status":"ok"}` → both true → success.
- A 200 with `{"status":"error", ...}` → `bodyOk=false` → retry.

Auth model: "Anyone" access. Acceptable risk for internal use; not validating shared secret in v1.

## 10. Build, sign, distribute

### 10.1 Keystore (one-time)

```bash
keytool -genkey -v \
  -keystore ~/keystores/networkmonitor-release.jks \
  -alias networkmonitor \
  -keyalg RSA -keysize 2048 -validity 10000
```

Keystore file + passwords stored outside the repo. `~/.gradle/gradle.properties` holds the 4 properties (`KEYSTORE_PATH`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`). Lose them = can no longer update the app for existing installs.

### 10.2 Release build

```bash
./gradlew assembleRelease
# → app/build/outputs/apk/release/app-release.apk
```

### 10.3 Distribution

Primary: Google Drive link (set to "anyone with the link"). Secondary option: self-hosted on internal server. Users enable *Install unknown apps* per-source, tap APK, tap past "unknown developer" warning. Updates: bump `versionCode` + `versionName`, rebuild, share new APK URL; signature match lets Android update in place preserving SharedPreferences.

## 11. Post-install verification checklist

1. App launches (if `themes.xml` not fixed: crash with `IllegalStateException: You need to use a Theme.AppCompat theme`).
2. Onboarding email screen blocks until `@codingninjas.com` email entered.
3. POST_NOTIFICATIONS prompt (API 33+).
4. Battery-optimization exemption dialog.
5. Persistent notification visible.
6. `adb shell dumpsys activity services | grep PingService` → service running.
7. At the next wall-clock quarter-hour boundary, Sheet receives a new row. Header row auto-inserted if sheet was empty. Normal row: 15 populated columns. Total-loss window: RTT-derived cells (E–K, N) blank; `packet_loss_pct = 100`.
8. Swipe app from recents → notification persists → next flush still fires.
9. Reboot device → service auto-restarts within ~30 sec.
10. Dashboard on `onResume` shows last-flush metrics, updated timestamp reflects last successful upload.

## 11a. HTTP client configuration (SheetsUploader)

```kotlin
private val client = OkHttpClient.Builder()
    .connectTimeout(Constants.HTTP_CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
    .writeTimeout(Constants.HTTP_WRITE_TIMEOUT_SEC, TimeUnit.SECONDS)
    .readTimeout(Constants.HTTP_READ_TIMEOUT_SEC, TimeUnit.SECONDS)
    .build()

private val gson = GsonBuilder().serializeNulls().create()
```

- Single `OkHttpClient` instance per process (holds connection pool; reusing it keeps TLS sessions warm for the quarter-hourly uploads).
- `GsonBuilder().serializeNulls()` ensures nullable RTT fields produce JSON `null` (which Apps Script writes as blank cells) rather than being omitted from the payload.
- `upload(payload)` runs inside `withContext(Dispatchers.IO)` from the flusher coroutine. Returns `response.isSuccessful` (2xx only); anything else — timeout, 5xx, redirect loop, I/O exception — returns `false` and triggers retain.
- Timestamp formatting: `Instant.ofEpochMilli(windowStartMs).toString()` (native in API 26+, always UTC, no timezone risk).

## 11b. Logging convention

Development/adb-logcat visibility only; no remote logging in v1.

| Class | TAG | When |
|---|---|---|
| `PingService` | `"NM.Service"` | `Log.i` on start/stop; `Log.e` on fatal coroutine cancellation |
| `PingService` sampler | `"NM.Sampler"` | `Log.v` per sample (noisy — wrap in `BuildConfig.DEBUG` check) |
| `PingService` flusher | `"NM.Flusher"` | `Log.i` on flush attempt + outcome; `Log.w` on retain-on-failure |
| `SheetsUploader` | `"NM.Upload"` | `Log.i` on 2xx; `Log.w` on non-2xx with status code; `Log.e` on IOException |
| `MetricsCalculator` | `"NM.Metrics"` | `Log.w` on total-loss window; otherwise silent |
| `SampleBuffer` | `"NM.Buffer"` | `Log.w` on overflow eviction (signals extended offline period) |
| `OnboardingActivity` | `"NM.Onboard"` | `Log.i` on email saved + service started |
| `BootReceiver` | `"NM.Boot"` | `Log.i` on service restart after reboot |

Filter everything at once (`-s` does not support glob patterns; use per-tag or grep):

```bash
# Explicit tag list + silence everything else
adb logcat NM.Service:V NM.Sampler:V NM.Flusher:V NM.Upload:V \
          NM.Metrics:V NM.Buffer:V NM.Onboard:V NM.Boot:V *:S

# Or simpler: pipe through grep
adb logcat | grep "NM\."
```

## 12. Risks and open items

| Risk | Mitigation |
|---|---|
| OS kills service under memory pressure between flushes | `START_STICKY` triggers automatic restart; samples accumulated during down-window are lost (acceptable for internal monitoring) |
| User denies battery exemption | Device owner (user) has admin access to override in Settings → Battery → App info |
| User denies `POST_NOTIFICATIONS` | Onboarding blocks progress; on 2nd denial, Settings redirect dialog guides user to grant manually (Android 13+ stops showing the prompt after 2 denials) |
| Buffer overflow during extended offline (>90 min) | `MAX_BUFFER_SAMPLES=5400` cap evicts oldest; `NM.Buffer` WARN log entry records the overflow for post-hoc investigation |
| Apps Script 6-min execution limit | Single-row append is <1 sec; far below limit |
| Sheet fills up (10M cell limit) | 15-col × 96 rows/day/user = 1440 cells/day/user; 10 users × 365 days = ~5.3M cells/year. Safe for >1 year. Monitor and rotate if needed. |
| Webhook URL leak (Anyone access) | Low-stakes internal metric; tolerable risk. Add shared-secret validation if exposure widens. |
| `ping` binary behavior differences across Android OEM builds | `ping -c 1 -W 2` is POSIX-baseline; supported by toybox on all API 26+ images. |
| Clock drift affecting `window_start` | `Instant.ofEpochMilli` is timezone-agnostic (always UTC), so timezone misconfig on device is irrelevant. Device clock accuracy itself can drift ±seconds; acceptable for monitoring. |

## 13. Future enhancements (post-v1, layerable)

- Live dashboard via bound service / LocalBroadcastManager.
- Raw CSV upload to Drive alongside aggregate Sheet rows.
- In-app version check against remote `latest_version.txt`.
- Multi-target ping (Google DNS + Cloudflare + internal gateway).
- Shared-secret webhook authentication.
- Sheet auto-rotation monthly.
- Crashlytics.
