package com.codingninjas.networkmonitor

import android.content.Context
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class PingWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences(
            Constants.PREFS_NAME,
            Context.MODE_PRIVATE
        )
        val userId = prefs.getString(Constants.PREF_USER_ID, "") ?: ""
        if (userId.isBlank()) {
            return Result.failure()
        }

        if (!NetworkUtils.isNetworkAvailable(applicationContext)) {
            return Result.retry()
        }

        val pingResult = withContext(Dispatchers.IO) {
            PingEngine.runPing(
                Constants.PING_TARGET,
                Constants.PING_COUNT,
                Constants.PING_TIMEOUT_SEC
            )
        }

        val metrics = MetricsCalculator.calculate(pingResult)

        val utcFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val timestamp = utcFormat.format(Date())

        val payload = SheetPayload(
            timestamp = timestamp,
            userId = userId,
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
            networkType = NetworkUtils.getNetworkType(applicationContext),
            avgPingMs = metrics.avgPing,
            bestPingMs = metrics.bestPing,
            worstPingMs = metrics.worstPing,
            jitterMs = metrics.jitter,
            packetLossPct = metrics.packetLoss,
            appVersion = Constants.APP_VERSION
        )

        val displayTime = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.US).format(Date())
        prefs.edit()
            .putString(Constants.PREF_LAST_RESULT, Gson().toJson(metrics))
            .putString("last_update_time", displayTime)
            .apply()

        val uploaded = withContext(Dispatchers.IO) {
            SheetsUploader.upload(payload)
        }

        return if (uploaded) Result.success() else Result.retry()
    }
}
