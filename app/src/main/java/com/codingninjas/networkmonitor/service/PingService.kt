package com.codingninjas.networkmonitor.service

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import com.codingninjas.networkmonitor.Constants
import com.codingninjas.networkmonitor.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class PingService : Service() {

    private val tag = "NM.Service"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
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
        // Loops will be started here in Task 10. Currently no-op.
        Log.i(tag, "onStartCommand — loops placeholder")
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        Log.i(tag, "service destroyed")
        super.onDestroy()
    }
}
