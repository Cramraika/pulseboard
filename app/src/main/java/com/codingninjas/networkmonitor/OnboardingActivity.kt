package com.codingninjas.networkmonitor

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.util.Patterns
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.codingninjas.networkmonitor.service.PingService
import com.codingninjas.networkmonitor.ui.MainActivity

class OnboardingActivity : AppCompatActivity() {

    private val tag = "NM.Onboard"
    private var pendingEmail: String? = null
    private var notifDenialCount = 0
    private var awaitingSettingsReturn = false

    private val requestNotifPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            Log.i(tag, "notification permission granted")
            proceedAfterNotifGranted()
        } else {
            notifDenialCount++
            handleNotifDenial()
        }
    }

    // Activity Result launcher for the battery-exemption system dialog.
    // Proceeds to finalizeOnboarding regardless of whether user granted —
    // we've done our due diligence by asking.
    private val batteryExemptionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        Log.i(tag, "battery-exemption dialog returned")
        finalizeOnboarding()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.getString(Constants.PREF_USER_ID, "").isNullOrBlank()) {
            Log.i(tag, "already onboarded — routing to MainActivity")
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        // Restore transient onboarding state across config change
        savedInstanceState?.let {
            pendingEmail = it.getString(KEY_PENDING_EMAIL)
            notifDenialCount = it.getInt(KEY_DENIAL_COUNT, 0)
            awaitingSettingsReturn = it.getBoolean(KEY_AWAITING_SETTINGS, false)
        }

        setContentView(R.layout.activity_onboarding)

        val etEmail = findViewById<EditText>(R.id.etEmail)
        val btnStart = findViewById<Button>(R.id.btnStart)
        pendingEmail?.let { etEmail.setText(it) }

        btnStart.setOnClickListener {
            val email = etEmail.text.toString().trim()
            if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                Toast.makeText(this, "Please enter a valid email", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!email.endsWith(Constants.ALLOWED_EMAIL_DOMAIN, ignoreCase = true)) {
                Toast.makeText(
                    this,
                    "Use your ${Constants.ALLOWED_EMAIL_DOMAIN} work email",
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }
            pendingEmail = email
            checkNotifPermission()
        }
    }

    override fun onResume() {
        super.onResume()
        // If user went to app-notification Settings and returned, re-check the permission
        // automatically so they don't have to tap "Start Monitoring" again.
        if (awaitingSettingsReturn && pendingEmail != null) {
            awaitingSettingsReturn = false
            checkNotifPermission()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_PENDING_EMAIL, pendingEmail)
        outState.putInt(KEY_DENIAL_COUNT, notifDenialCount)
        outState.putBoolean(KEY_AWAITING_SETTINGS, awaitingSettingsReturn)
    }

    private fun checkNotifPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            // API <33: permission granted at install time.
            proceedAfterNotifGranted()
            return
        }
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            proceedAfterNotifGranted()
        } else {
            requestNotifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun handleNotifDenial() {
        when (notifDenialCount) {
            1 -> {
                AlertDialog.Builder(this)
                    .setTitle("Notification required")
                    .setMessage("Monitoring needs this notification so Android lets it run in the background continuously.")
                    .setPositiveButton("Try again") { _, _ ->
                        requestNotifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    .setCancelable(false)
                    .show()
            }
            else -> {
                AlertDialog.Builder(this)
                    .setTitle("Notification required")
                    .setMessage("Android won't show the prompt again. Open app notification settings to grant manually, then return to this app.")
                    .setPositiveButton("Open Settings") { _, _ ->
                        awaitingSettingsReturn = true
                        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                            putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                        }
                        startActivity(intent)
                    }
                    .setNegativeButton("Cancel", null)
                    .setCancelable(false)
                    .show()
            }
        }
    }

    /**
     * Notification permission granted. Now ask about battery-optimization exemption.
     * We launch the system dialog via Activity Result so we can wait for the user's
     * response before proceeding to finalize — otherwise MainActivity launches on top
     * of the system dialog and the user never actually sees the battery prompt.
     */
    private fun proceedAfterNotifGranted() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            Log.i(tag, "battery exemption already granted — skipping prompt")
            finalizeOnboarding()
            return
        }
        try {
            val intent = Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:$packageName")
            )
            batteryExemptionLauncher.launch(intent)
        } catch (e: Exception) {
            // Some OEM devices reject this intent. Log and proceed anyway.
            Log.w(tag, "failed to launch battery-exemption intent, proceeding", e)
            finalizeOnboarding()
        }
    }

    private fun finalizeOnboarding() {
        val email = pendingEmail ?: return
        getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(Constants.PREF_USER_ID, email)
            .apply()
        ContextCompat.startForegroundService(this, Intent(this, PingService::class.java))
        Log.i(tag, "onboarding done — email=$email, service started")
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    companion object {
        private const val KEY_PENDING_EMAIL = "pending_email"
        private const val KEY_DENIAL_COUNT = "notif_denial_count"
        private const val KEY_AWAITING_SETTINGS = "awaiting_settings_return"
    }
}
