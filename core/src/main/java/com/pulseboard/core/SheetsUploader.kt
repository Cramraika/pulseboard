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

data class SheetPayload(
    @SerializedName("window_start") val windowStart: String,
    @SerializedName("user_id") val userId: String,
    @SerializedName("device_model") val deviceModel: String,
    @SerializedName("network_type") val networkType: String,
    @SerializedName("avg_ping_ms") val avgPingMs: Double?,
    @SerializedName("min_ping_ms") val minPingMs: Double?,
    @SerializedName("max_ping_ms") val maxPingMs: Double?,
    @SerializedName("p50_ping_ms") val p50PingMs: Double?,
    @SerializedName("p95_ping_ms") val p95PingMs: Double?,
    @SerializedName("p99_ping_ms") val p99PingMs: Double?,
    @SerializedName("jitter_ms") val jitterMs: Double?,
    @SerializedName("packet_loss_pct") val packetLossPct: Double,
    @SerializedName("samples_count") val samplesCount: Int,
    @SerializedName("max_rtt_offset_sec") val maxRttOffsetSec: Int?,
    @SerializedName("app_version") val appVersion: String
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

    fun upload(payload: SheetPayload): Boolean {
        return try {
            val body = gson.toJson(payload).toRequestBody(jsonMedia)
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
                    Log.i(tag, "upload ok (status=${response.code}, samples=${payload.samplesCount})")
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
