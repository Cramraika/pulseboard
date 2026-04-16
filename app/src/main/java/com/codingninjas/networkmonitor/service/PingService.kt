package com.codingninjas.networkmonitor.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import com.codingninjas.networkmonitor.Constants
import com.codingninjas.networkmonitor.MetricsCalculator
import com.codingninjas.networkmonitor.NetworkMetrics
import com.codingninjas.networkmonitor.NetworkUtils
import com.codingninjas.networkmonitor.NotificationHelper
import com.codingninjas.networkmonitor.PingEngine
import com.codingninjas.networkmonitor.Sample
import com.codingninjas.networkmonitor.SampleBuffer
import com.codingninjas.networkmonitor.SheetPayload
import com.codingninjas.networkmonitor.SheetsUploader
import com.google.gson.GsonBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.Date
import java.util.Locale
import kotlin.coroutines.coroutineContext

class PingService : Service() {

    private val tag = "NM.Service"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val buffer = SampleBuffer()
    private val uploader = SheetsUploader()
    // Matches SheetsUploader's serialization settings — null fields are preserved
    // so the prefs round-trip behaves identically to what the Sheet receives.
    private val gson = GsonBuilder().serializeNulls().create()
    private var loopsStarted = false

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.ensureChannel(this)
        val notification = NotificationHelper.buildOngoing(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this,
                Constants.NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(Constants.NOTIF_ID, notification)
        }
        Log.i(tag, "service created and foregrounded")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (loopsStarted) return START_STICKY
        loopsStarted = true
        scope.launch { samplerLoop() }
        scope.launch { flusherLoop() }
        Log.i(tag, "loops launched")
        return START_STICKY
    }

    private suspend fun samplerLoop() {
        val samplerTag = "NM.Sampler"
        while (coroutineContext.isActive) {
            try {
                val t0 = System.currentTimeMillis()
                val result = PingEngine.runPing(
                    Constants.PING_TARGET,
                    count = 1,
                    timeoutSec = Constants.PING_TIMEOUT_SEC
                )
                val rtt = if (result.success) result.rtts.firstOrNull() else null
                buffer.add(Sample(rttMs = rtt, tsMs = t0))
                val elapsed = System.currentTimeMillis() - t0
                delay((Constants.SAMPLE_INTERVAL_MS - elapsed).coerceAtLeast(0))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(samplerTag, "sampler iteration threw", e)
                delay(Constants.SAMPLE_INTERVAL_MS)   // avoid tight error loop
            }
        }
    }

    private suspend fun flusherLoop() {
        val flusherTag = "NM.Flusher"
        val quarterMs = Constants.FLUSH_INTERVAL_MINUTES * 60_000L
        val initialDelay = quarterMs - (System.currentTimeMillis() % quarterMs)
        Log.i(flusherTag, "first flush in ${initialDelay / 1000}s")
        delay(initialDelay)
        while (coroutineContext.isActive) {
            try {
                runOneFlush()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(flusherTag, "flusher iteration threw", e)
            }
            delay(quarterMs)
        }
    }

    private fun runOneFlush() {
        val flusherTag = "NM.Flusher"
        val drained = buffer.drain()
        if (drained.isEmpty()) {
            Log.i(flusherTag, "drained 0 samples — skip")
            return
        }
        val metrics = MetricsCalculator.aggregate(drained)
        val networkType = NetworkUtils.getNetworkType(applicationContext)
        val windowStartMs = drained.minOf { it.tsMs }
        val payload = buildPayload(metrics, windowStartMs, networkType)

        val uploaded = uploader.upload(payload)
        if (uploaded) {
            persistToPrefs(metrics, networkType)
            Log.i(flusherTag, "flush ok — samples=${metrics.samplesCount} loss=${metrics.packetLoss}%")
        } else {
            buffer.prepend(drained)
            Log.w(flusherTag, "flush failed — retained ${drained.size} samples for next window")
        }
    }

    private fun buildPayload(
        metrics: NetworkMetrics,
        windowStartMs: Long,
        networkType: String
    ): SheetPayload {
        val userId = getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
            .getString(Constants.PREF_USER_ID, "") ?: ""
        return SheetPayload(
            windowStart = Instant.ofEpochMilli(windowStartMs).toString(),
            userId = userId,
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
            networkType = networkType,
            avgPingMs = metrics.avgPing,
            minPingMs = metrics.minPing,
            maxPingMs = metrics.maxPing,
            p50PingMs = metrics.p50Ping,
            p95PingMs = metrics.p95Ping,
            p99PingMs = metrics.p99Ping,
            jitterMs = metrics.jitter,
            packetLossPct = metrics.packetLoss,
            samplesCount = metrics.samplesCount,
            maxRttOffsetSec = metrics.maxRttOffsetSec,
            appVersion = Constants.APP_VERSION
        )
    }

    private fun persistToPrefs(metrics: NetworkMetrics, networkType: String) {
        val displayTime = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.US).format(Date())
        getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(Constants.PREF_LAST_RESULT, gson.toJson(metrics))
            .putString(Constants.PREF_LAST_UPDATE_TIME, displayTime)
            .putString(Constants.PREF_LAST_NETWORK_TYPE, networkType)
            .apply()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        Log.i(tag, "service destroyed")
        super.onDestroy()
    }
}
