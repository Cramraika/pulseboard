package com.codingninjas.networkmonitor

import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

data class SheetPayload(
    @SerializedName("timestamp") val timestamp: String,
    @SerializedName("user_id") val userId: String,
    @SerializedName("device_model") val deviceModel: String,
    @SerializedName("network_type") val networkType: String,
    @SerializedName("avg_ping_ms") val avgPingMs: Double,
    @SerializedName("best_ping_ms") val bestPingMs: Double,
    @SerializedName("worst_ping_ms") val worstPingMs: Double,
    @SerializedName("jitter_ms") val jitterMs: Double,
    @SerializedName("packet_loss_pct") val packetLossPct: Double,
    @SerializedName("app_version") val appVersion: String
)

object SheetsUploader {
    private const val TAG = "SheetsUploader"
    private val JSON = "application/json".toMediaType()
    private val client = OkHttpClient()
    private val gson = Gson()

    fun upload(payload: SheetPayload): Boolean {
        return try {
            val json = gson.toJson(payload)
            val request = Request.Builder()
                .url(Constants.WEBHOOK_URL)
                .post(json.toRequestBody(JSON))
                .build()

            client.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        } catch (e: Exception) {
            Log.e(TAG, "Upload failed", e)
            false
        }
    }
}
