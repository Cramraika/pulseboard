package com.pulseboard.core

import android.util.Log
import com.google.gson.GsonBuilder
import com.google.gson.annotations.SerializedName
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

// Default HTTP timeouts (seconds). Match v1.0 production values.
private const val DEFAULT_CONNECT_TIMEOUT_SEC = 10L
private const val DEFAULT_WRITE_TIMEOUT_SEC = 10L
private const val DEFAULT_READ_TIMEOUT_SEC = 15L

// v1.2 in-flight retry policy. Apps Script occasionally returns transient 5xx
// during quota rollover; 3 attempts with 2s / 8s / 32s backoff cleared >95%
// of retries in the 2026-04-22 field data that was causing retain_merged_count
// to climb into the 20s. Outer retain-on-failure remains as fallback.
internal const val DEFAULT_UPLOAD_MAX_ATTEMPTS = 3
internal val DEFAULT_UPLOAD_BACKOFF_MS = longArrayOf(2_000L, 8_000L, 32_000L)

/**
 * One row in the ASM flush schema (56 client-emitted fields as of v1.5.6).
 * The Android uploader posts a JSON array of N of these per flush (one per
 * ping target, e.g. primary / gateway / cloudflare_dns / cloudflare_cdn).
 * Device-level aggregates (Wi-Fi transitions, scan context, flush_seq) are
 * duplicated across every row in the array so each row is self-contained.
 *
 * Field naming convention:
 *   - v1.0 `*_ping_ms` renamed to `*_rtt_ms` (it was always RTT, "ping"
 *     was colloquial).
 *   - v1.0 `network_type` renamed to `network_type_dominant` (now computed
 *     over a 15-min window with possibly mixed transports).
 *   - Every new v1.1 field is nullable-with-default so test helpers +
 *     future callers only populate what they need.
 */
data class SheetPayload(
    // --- identity ---
    @SerializedName("window_start") val windowStart: String,
    @SerializedName("user_id") val userId: String,
    @SerializedName("device_model") val deviceModel: String,
    @SerializedName("android_sdk") val androidSdk: Int? = null,
    @SerializedName("oem_skin") val oemSkin: String? = null,
    @SerializedName("app_version") val appVersion: String,

    // --- per-target ---
    @SerializedName("target") val target: String? = null,
    @SerializedName("gateway_ip") val gatewayIp: String? = null,
    @SerializedName("unreachable_target") val unreachableTarget: Boolean? = null,

    // --- RTT metrics ---
    @SerializedName("avg_rtt_ms") val avgRttMs: Double? = null,
    @SerializedName("min_rtt_ms") val minRttMs: Double? = null,
    @SerializedName("max_rtt_ms") val maxRttMs: Double? = null,
    @SerializedName("p50_rtt_ms") val p50RttMs: Double? = null,
    @SerializedName("p95_rtt_ms") val p95RttMs: Double? = null,
    @SerializedName("p99_rtt_ms") val p99RttMs: Double? = null,
    @SerializedName("jitter_ms") val jitterMs: Double? = null,
    @SerializedName("packet_loss_pct") val packetLossPct: Double? = null,

    // --- sample counts ---
    @SerializedName("samples_count") val samplesCount: Int = 0,
    @SerializedName("reachable_samples_count") val reachableSamplesCount: Int? = null,
    @SerializedName("max_rtt_offset_sec") val maxRttOffsetSec: Int? = null,

    // --- Wi-Fi aggregates (duplicated across per-target rows) ---
    @SerializedName("gaps_count") val gapsCount: Int? = null,
    @SerializedName("bssid_changes_count") val bssidChangesCount: Int? = null,
    @SerializedName("ssid_changes_count") val ssidChangesCount: Int? = null,
    @SerializedName("rssi_min") val rssiMin: Int? = null,
    @SerializedName("rssi_avg") val rssiAvg: Int? = null,
    @SerializedName("rssi_max") val rssiMax: Int? = null,
    @SerializedName("primary_bssid") val primaryBssid: String? = null,
    @SerializedName("primary_ssid") val primarySsid: String? = null,
    @SerializedName("primary_frequency_mhz") val primaryFrequencyMhz: Int? = null,
    @SerializedName("primary_link_speed_mbps") val primaryLinkSpeedMbps: Int? = null,
    @SerializedName("current_bssid") val currentBssid: String? = null,
    @SerializedName("current_rssi") val currentRssi: Int? = null,
    @SerializedName("network_type_dominant") val networkTypeDominant: String? = null,
    @SerializedName("vpn_active") val vpnActive: Boolean? = null,

    // --- scan context (once per 15-min flush) ---
    @SerializedName("visible_aps_count") val visibleApsCount: Int? = null,
    @SerializedName("best_available_rssi") val bestAvailableRssi: Int? = null,
    @SerializedName("sticky_client_gap_db") val stickyClientGapDb: Int? = null,

    // --- operational telemetry ---
    @SerializedName("duty_cycle_pct") val dutyCyclePct: Double? = null,
    @SerializedName("flush_seq") val flushSeq: Long? = null,
    @SerializedName("retain_merged_count") val retainMergedCount: Int? = null,

    // --- v1.2 telemetry + diagnostics ---
    // row_type differentiates regular per-target sample rows from lifecycle
    // rows (onboarding_complete, heartbeat). Defaults to "sample" for backward
    // compatibility with every existing caller.
    @SerializedName("row_type") val rowType: String? = "sample",
    // Wall-clock gap between consecutive samples within the window. Surfaces
    // Doze / OEM service-kill gaps as a first-class column instead of hiding
    // them inside max_rtt_offset_sec tails.
    @SerializedName("sampler_wall_gap_sec") val samplerWallGapSec: Int? = null,
    // Structured snapshot of the app's permission/location state at flush
    // time. Format: "loc=on;fine=1;nearby=1;bgloc=1;batt=1;notif=1" — one
    // underscore-separated record that pivots cleanly in the Sheet.
    @SerializedName("permission_state") val permissionState: String? = null,
    // How gatewayIp was resolved: "live" / "cached" / "null". Validates the
    // cache-last-good fallback (PingCore.GatewayResolver) is earning its keep.
    @SerializedName("gateway_resolver_source") val gatewayResolverSource: String? = null,

    // --- v1.3 throughput probe (populated only on the primary-target row; one
    //      probe per 15-min flush window; skipped on cellular transports) ---
    @SerializedName("throughput_bytes") val throughputBytes: Long? = null,
    @SerializedName("throughput_duration_ms") val throughputDurationMs: Long? = null,
    @SerializedName("throughput_mbps") val throughputMbps: Double? = null,
    // Short error class name on failure, "skipped_cellular" when a cellular
    // transport skipped the probe to preserve the user's mobile data plan,
    // null on success.
    @SerializedName("throughput_err") val throughputErr: String? = null,

    // --- v1.5.5 window-level diagnostics ---
    // SUM of per-target gaps_count across all 5 targets for this flush window.
    // Emitted only on the gateway row (null on all others). NETMON-FOLLOWUP-1:
    // ASM will prefer this column when present, falling back to SUM(gaps_count)
    // over un-deduped rows for legacy clients.
    @SerializedName("window_gaps_count") val windowGapsCount: Int? = null,
    // True when bssid_changes_count > 15 in this window — threshold separates
    // healthy band-steering (≤ 6 typical) from device/AP flapping faults.
    @SerializedName("high_bssid_churn") val highBssidChurn: Boolean? = null,
    // Pipe-separated top-2 5-octet BSSID prefixes most involved in transitions
    // when high_bssid_churn is true. E.g. "02:00:00:eb:a0|02:00:00:e9:52".
    // Lets ASM distinguish "band-steering on AP X" from "walking past 4 APs".
    // Null when !high_bssid_churn or no non-null transitions found.
    @SerializedName("churn_bssid_prefix") val churnBssidPrefix: String? = null,
    // BSSID transitions after collapsing each real MAC to its 5-octet physical-AP
    // prefix. Distinguishes "band-steered on same AP" (physicalApChanges < bssidChanges)
    // from "physically roamed". NETMON-FOLLOWUP-2: ASM renders this as "physical roams"
    // alongside bssid_changes_count.
    @SerializedName("physical_ap_changes_count") val physicalApChangesCount: Int? = null,

    // --- v1.5.6 forensic-richness fields ---
    // Three-tier churn classification derived from bssid_changes_count:
    //   "low"   ≤ BSSID_CHURN_THRESHOLD (15) — healthy band-steering
    //   "high"  16–30                         — borderline / sustained band flapping
    //   "fault" > BSSID_CHURN_FAULT_THRESHOLD (30) — likely device or AP fault
    // Live data 2026-04-30 21:00 IST showed one device at 95 transitions/window and
    // another at 37 — far past v1.5.5's 15-threshold; this tier separates true
    // fault-class devices from the broader at-15 population. Emitted on every
    // sample row (duplicated, like other window-level fields) for slice-friendliness.
    @SerializedName("churn_tier") val churnTier: String? = null,

    // F6 (carried from v1.5.4 audit § 5): unreachable_pct — explicit denominator
    // disambiguating packet_loss_pct semantics. Formula:
    //   unreachable_pct = (samples_count - reachable_samples_count) / samples_count * 100
    // Where reachable=0 across the window (e.g. cellular-only flush vs an unreachable
    // primary target), this is 100. Where samples=0 (true empty drain), this is null.
    // Per-target value, NOT duplicated.
    @SerializedName("unreachable_pct") val unreachablePct: Double? = null,

    // SDK 31+ WifiInfo.macRandomizationSetting; the dominant value across the
    // window's wifi snapshots. One of: "none" (device MAC — good for whitelist
    // SSIDs), "persistent" (Android default since 10), "non_persistent" (worst),
    // "unknown" (read failed), "unsupported" (SDK < 31 or no Wi-Fi).
    // MAC-whitelisted SSID + persistent randomization = silent VoIP failure;
    // joining this with a whitelisted primary_ssid AND voip_health='bad'
    // surfaces the population.
    @SerializedName("mac_randomization") val macRandomization: String? = null,

    // PowerManager.isDeviceIdleMode + isLightDeviceIdleMode at flush time.
    // Values: "active" / "light_idle" / "idle". Forensic context for windows
    // with sparse samples or 100% loss — Doze can freeze the FGS coroutine
    // mid-window even though the watchdog later restarts it. Joining this
    // with samples_count low + retain_merged_count > 0 confirms whether the
    // gap was Doze-driven vs network-driven.
    @SerializedName("device_idle_mode") val deviceIdleMode: String? = null,

    // Did any Wi-Fi snapshot in this window report "L2-associated but no IPv4
    // assigned"? OBSERVABLE symptom of an AP MAC-whitelist DHCP-block
    // when the phone is presenting a randomized MAC. ASM filter:
    //   WHERE wifi_no_ip_observed = true AND primary_ssid = '<whitelisted SSID>'
    // → devices whose VoIP fails because their MAC isn't whitelisted.
    // Null when window had zero Wi-Fi snapshots (cellular-only window).
    @SerializedName("wifi_no_ip_observed") val wifiNoIpObserved: Boolean? = null
)

class SheetsUploader(
    private val webhookUrl: String,
    connectTimeoutSec: Long = DEFAULT_CONNECT_TIMEOUT_SEC,
    writeTimeoutSec: Long = DEFAULT_WRITE_TIMEOUT_SEC,
    readTimeoutSec: Long = DEFAULT_READ_TIMEOUT_SEC,
    private val maxAttempts: Int = DEFAULT_UPLOAD_MAX_ATTEMPTS,
    private val backoffMs: LongArray = DEFAULT_UPLOAD_BACKOFF_MS,
    // v1.5: when non-null, attached as X-API-Key header on every request.
    // Required by the ASM Fastify /api/v1/netmon/flush endpoint (the v1.5
    // wire target) which authenticates every POST. Apps Script's /exec URL
    // ignores the header, so keeping this optional preserves test + v1.1
    // historical compatibility.
    private val apiKey: String? = null,
    // Test seam: tests inject a no-op sleeper so assertions don't wait 42s.
    private val sleeper: (Long) -> Unit = { ms -> if (ms > 0) Thread.sleep(ms) }
) {

    private val tag = "PingCore.Upload"
    private val jsonMedia = "application/json".toMediaType()
    private val gson = GsonBuilder().serializeNulls().create()
    // Share the base OkHttp client's connection pool + dispatcher + TLS
    // cache with ThroughputProber, applying per-consumer timeouts on top.
    private val client = HttpClients.newBuilder()
        .connectTimeout(connectTimeoutSec, TimeUnit.SECONDS)
        .writeTimeout(writeTimeoutSec, TimeUnit.SECONDS)
        .readTimeout(readTimeoutSec, TimeUnit.SECONDS)
        .build()

    fun upload(payload: SheetPayload): Boolean =
        executePostWithRetry(gson.toJson(payload), describe = "samples=${payload.samplesCount}")

    /**
     * POSTs a JSON array of payloads in one request. All-or-nothing: if the
     * server response isn't HTTP 2xx + JSON `{"status":"ok"}`, the whole batch
     * is considered failed and the caller retains all rows for retry.
     *
     * Used by v1.1's 4-target flusher to append 4 Sheet rows (one per target)
     * per 15-minute window in a single POST.
     *
     * v1.2: wraps the POST in a short retry loop with exponential backoff
     * (2s / 8s / 32s by default). Idempotent on the server side since each
     * row includes flush_seq + window_start; duplicates are rare and harmless
     * in the Sheet for internal use.
     */
    fun uploadBatch(payloads: List<SheetPayload>): Boolean =
        executePostWithRetry(gson.toJson(payloads), describe = "batch_size=${payloads.size}")

    private fun executePostWithRetry(jsonBody: String, describe: String): Boolean {
        val attempts = maxAttempts.coerceAtLeast(1)
        for (attempt in 1..attempts) {
            val ok = executePost(jsonBody, describe, attempt, attempts)
            if (ok) return true
            if (attempt < attempts) {
                val delayMs = backoffMs.getOrNull(attempt - 1) ?: backoffMs.last()
                Log.i(tag, "retry in ${delayMs}ms (attempt ${attempt + 1}/$attempts)")
                sleeper(delayMs)
            }
        }
        return false
    }

    private fun executePost(
        jsonBody: String,
        describe: String,
        attempt: Int,
        maxAttempts: Int
    ): Boolean {
        return try {
            val body = jsonBody.toRequestBody(jsonMedia)
            val builder = Request.Builder().url(webhookUrl).post(body)
            // `.header()` replaces rather than appends — intentional so a
            // misconfigured double-call can't produce two X-API-Key values.
            // Guard against both null/empty AND whitespace-only keys so a
            // build-property misconfiguration can't silently send "  " as
            // the auth value and collect 401s indefinitely.
            val trimmedKey = apiKey?.trim()
            if (!trimmedKey.isNullOrEmpty()) builder.header("X-API-Key", trimmedKey)
            val request = builder.build()
            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string() ?: ""
                val httpOk = response.isSuccessful
                // Accept either Apps Script's {"status":"ok"} or ASM Fastify's
                // {"ok":true} — same uploader talks to both shapes during the
                // v1.5 cutover.
                val bodyOk = try {
                    val json = JSONObject(bodyStr)
                    json.optString("status") == "ok" || json.optBoolean("ok", false)
                } catch (_: Exception) {
                    false
                }
                val ok = httpOk && bodyOk
                if (ok) {
                    Log.i(tag, "upload ok (status=${response.code}, attempt=$attempt/$maxAttempts, $describe)")
                } else {
                    // Log failure WITHOUT the raw response body. If the server
                    // ever echoes request headers in its error body (some
                    // debugging middlewares do this), raw-dumping would leak
                    // X-API-Key into logcat + crash-reporter pipelines. Log
                    // only the status code + a short body-length hint.
                    val hint = if (bodyStr.isEmpty()) "empty" else "${bodyStr.length}B"
                    Log.w(tag, "upload failed (status=${response.code}, httpOk=$httpOk, bodyOk=$bodyOk, attempt=$attempt/$maxAttempts, body=$hint)")
                }
                ok
            }
        } catch (e: Exception) {
            // Log only exception class + message — full stack can serialize
            // URL query params or headers on some transports. The URL itself
            // is public (lives in Constants.kt) but we don't ship keys via
            // query params so nothing sensitive should land here anyway.
            Log.e(tag, "upload threw (attempt=$attempt/$maxAttempts, class=${e.javaClass.simpleName})")
            false
        }
    }
}
