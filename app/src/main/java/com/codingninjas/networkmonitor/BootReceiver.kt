package com.codingninjas.networkmonitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.codingninjas.networkmonitor.service.PingService

class BootReceiver : BroadcastReceiver() {

    private val tag = "NM.Boot"

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        val userId = prefs.getString(Constants.PREF_USER_ID, "") ?: ""
        if (userId.isBlank()) {
            Log.i(tag, "boot received but no email saved — skipping service start")
            return
        }
        Log.i(tag, "boot received — starting PingService for $userId")
        ContextCompat.startForegroundService(
            context, Intent(context, PingService::class.java)
        )
    }
}
