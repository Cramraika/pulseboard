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

    // v1.1: BSSID reads on Android 8+ require ACCESS_FINE_LOCATION. Denial is
    // non-fatal (BSSID field reports the "permission_denied" sentinel), so we
    // always proceed to the battery step regardless of outcome.
    private val requestLocationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        Log.i(tag, "location permission ${if (granted) "granted" else "denied"}")
        proceedAfterLocationDone()
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
            // Already onboarded. Ensure the monitoring service is running —
            // MIUI and other OEM skins kill foreground services when the user swipes the app
            // from recents, and our START_STICKY contract isn't honored there. Calling
            // startForegroundService on an already-running service is an idempotent no-op.
            Log.i(tag, "already onboarded — ensuring service + routing to MainActivity")
            ContextCompat.startForegroundService(this, Intent(this, PingService::class.java))
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
     * Notification permission granted. v1.1 inserts a location-permission step
     * (required for BSSID reads) before the existing battery-exemption prompt.
     * Rationale dialog explains the Wi-Fi-AP-only use so the user isn't surprised
     * by a GPS-looking prompt.
     */
    private fun proceedAfterNotifGranted() {
        val alreadyGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (alreadyGranted) {
            proceedAfterLocationDone()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Location permission")
            .setMessage(
                "We use location permission only to identify which Wi-Fi access " +
                "point (BSSID) your device is connected to. We do not collect " +
                "GPS location or upload coordinates."
            )
            .setPositiveButton("Grant") { _, _ ->
                requestLocationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            .setNegativeButton("Skip") { _, _ ->
                Log.i(tag, "location rationale declined — proceeding without BSSID")
                proceedAfterLocationDone()
            }
            .setCancelable(false)
            .show()
    }

    /**
     * Location step complete (granted or declined — both fine). Ask about
     * battery-optimization exemption. Activity Result so MainActivity doesn't
     * launch on top of the system dialog.
     */
    private fun proceedAfterLocationDone() {
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
