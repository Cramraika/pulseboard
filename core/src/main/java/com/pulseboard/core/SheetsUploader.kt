package com.pulseboard.core

import android.util.Log
import com.google.gson.GsonBuilder
import com.google.gson.annotations.SerializedName
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

// Default HTTP timeouts (seconds). Match v1.0 production values.
private const val DEFAULT_CONNECT_TIMEOUT_SEC = 10L
private const val DEFAULT_WRITE_TIMEOUT_SEC = 10L
private const val DEFAULT_READ_TIMEOUT_SEC = 15L

/**
 * One row in the v1.1 schema (40 columns). The Android uploader posts a
 * JSON array of N of these per flush (one per target: smartflo / gateway /
 * cloudflare / dns for CN). Device-level aggregates (Wi-Fi transitions,
 * scan context, flush_seq) are duplicated across every row in the array
 * so each Sheet row is self-contained.
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
    @SerializedName("retain_merged_count") val retainMergedCount: Int? = null
)

class SheetsUploader(
    private val webhookUrl: String,
    connectTimeoutSec: Long = DEFAULT_CONNECT_TIMEOUT_SEC,
    writeTimeoutSec: Long = DEFAULT_WRITE_TIMEOUT_SEC,
    readTimeoutSec: Long = DEFAULT_READ_TIMEOUT_SEC
) {

    private val tag = "PingCore.Upload"
    private val jsonMedia = "application/json".toMediaType()
    private val gson = GsonBuilder().serializeNulls().create()
    private val client = OkHttpClient.Builder()
        .connectTimeout(connectTimeoutSec, TimeUnit.SECONDS)
        .writeTimeout(writeTimeoutSec, TimeUnit.SECONDS)
        .readTimeout(readTimeoutSec, TimeUnit.SECONDS)
        .build()

    fun upload(payload: SheetPayload): Boolean =
        executePost(gson.toJson(payload), describe = "samples=${payload.samplesCount}")

    /**
     * POSTs a JSON array of payloads in one request. All-or-nothing: if the
     * server response isn't HTTP 2xx + JSON `{"status":"ok"}`, the whole batch
     * is considered failed and the caller retains all rows for retry.
     *
     * Used by v1.1's 4-target flusher to append 4 Sheet rows (one per target)
     * per 15-minute window in a single POST.
     */
    fun uploadBatch(payloads: List<SheetPayload>): Boolean =
        executePost(gson.toJson(payloads), describe = "batch_size=${payloads.size}")

    private fun executePost(jsonBody: String, describe: String): Boolean {
        return try {
            val body = jsonBody.toRequestBody(jsonMedia)
            val request = Request.Builder().url(webhookUrl).post(body).build()
            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string() ?: ""
                val httpOk = response.isSuccessful
                val bodyOk = try {
                    JSONObject(bodyStr).optString("status") == "ok"
                } catch (_: Exception) {
                    false
                }
                val ok = httpOk && bodyOk
                if (ok) {
                    Log.i(tag, "upload ok (status=${response.code}, $describe)")
                } else {
                    Log.w(tag, "upload failed (status=${response.code}, httpOk=$httpOk, bodyOk=$bodyOk, body=${bodyStr.take(200)})")
                }
                ok
            }
        } catch (e: Exception) {
            Log.e(tag, "upload threw", e)
            false
        }
    }
}
