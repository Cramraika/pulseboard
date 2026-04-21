package com.pulseboard.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.util.Log
import androidx.core.content.ContextCompat

// Sentinel Android returns from WifiInfo.getSSID() when SSID isn't available.
internal const val UNKNOWN_SSID = "<unknown ssid>"

// Sentinel we use when ACCESS_FINE_LOCATION is denied on Android 8+ — distinguishes
// "user declined permission" from "genuine null BSSID" in the Sheet.
internal const val BSSID_PERMISSION_DENIED = "permission_denied"

/**
 * Captures Wi-Fi / network context at sample time and at flush time.
 *
 * Two entry points:
 * - [snapshot] is cheap (<1ms) and intended to be called per sample (1 Hz × 4 targets).
 * - [scanSnapshot] triggers `WifiManager.startScan()` and is subject to Android's
 *   scan rate limit — call at most once per 15-minute flush window.
 */
class WifiMetadataCollector(private val context: Context) {

    private val tag = "PingCore.Wifi"
    private val wifiManager =
        context.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    // Permission denial logged once per collector instance, not per sample, so
    // logcat doesn't drown in repeat messages.
    @Volatile
    private var loggedPermissionDenied = false

    fun snapshot(): WifiSnapshot {
        val nowMs = System.currentTimeMillis()
        val activeNetwork = connectivityManager.activeNetwork
        val caps = activeNetwork?.let { connectivityManager.getNetworkCapabilities(it) }
        val networkType = mapNetworkType(caps)
        val vpnActive = caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true

        if (networkType != "wifi") {
            return WifiSnapshot(
                ssid = null, bssid = null, rssi = null,
                linkSpeedMbps = null, frequencyMhz = null,
                networkType = networkType,
                vpnActive = vpnActive,
                collectedAtMs = nowMs
            )
        }

        @Suppress("DEPRECATION")
        val info: WifiInfo? = wifiManager.connectionInfo
        val hasFineLocation = hasFineLocationPermission()

        val bssid = if (hasFineLocation) {
            info?.bssid
        } else {
            if (!loggedPermissionDenied) {
                Log.w(tag, "ACCESS_FINE_LOCATION not granted — BSSID will report '$BSSID_PERMISSION_DENIED'")
                loggedPermissionDenied = true
            }
            BSSID_PERMISSION_DENIED
        }

        return WifiSnapshot(
            ssid = stripSsidQuotes(info?.ssid),
            bssid = bssid,
            rssi = info?.rssi,
            linkSpeedMbps = info?.linkSpeed,
            frequencyMhz = info?.frequency,
            networkType = networkType,
            vpnActive = vpnActive,
            collectedAtMs = nowMs
        )
    }

    fun scanSnapshot(): ScanSnapshot? {
        val nowMs = System.currentTimeMillis()
        val triggered = try {
            @Suppress("DEPRECATION")
            wifiManager.startScan()
        } catch (e: SecurityException) {
            Log.w(tag, "startScan() threw SecurityException", e)
            false
        }
        if (!triggered) return null

        val results = try {
            wifiManager.scanResults
        } catch (e: SecurityException) {
            Log.w(tag, "scanResults threw SecurityException", e)
            return null
        }
        if (results.isEmpty()) return null

        val best = results.maxByOrNull { it.level }
        return ScanSnapshot(
            visibleApsCount = results.size,
            bestAvailableBssid = best?.BSSID,
            bestAvailableRssi = best?.level,
            scannedAtMs = nowMs
        )
    }

    private fun hasFineLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
}

internal fun stripSsidQuotes(raw: String?): String? {
    if (raw == null) return null
    if (raw == UNKNOWN_SSID) return raw
    return if (raw.length >= 2 && raw.startsWith('"') && raw.endsWith('"')) {
        raw.substring(1, raw.length - 1)
    } else raw
}

internal fun mapNetworkType(caps: NetworkCapabilities?): String {
    if (caps == null) return "none"
    return when {
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
        else -> "none"
    }
}
