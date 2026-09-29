package com.pulseboard.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat

// Sentinel Android returns from WifiInfo.getSSID() when SSID isn't available.
internal const val UNKNOWN_SSID = "<unknown ssid>"

// Sentinel Android returns from WifiInfo.getBSSID() when BSSID is gated by
// a missing permission. Identical to the "locally administered MAC" prefix,
// which is why we replace it with a typed sentinel before emitting.
internal const val ANDROID_SENTINEL_BSSID = "02:00:00:00:00:00"

// v1.2 typed sentinels — distinguish the three distinct failure modes so the
// Sheet can pivot on root cause rather than guessing from an opaque
// "02:00:00:00:00:00" string.
internal const val BSSID_PERMISSION_DENIED = "permission_denied"      // ACCESS_FINE_LOCATION missing (pre-SDK 33 path)
internal const val BSSID_NEARBY_WIFI_DENIED = "nearby_wifi_denied"    // NEARBY_WIFI_DEVICES missing on SDK 33+
internal const val BSSID_LOCATION_OFF = "location_off"                // system-wide location toggle off

// v1.5.6 typed sentinel — distinguishes "rep on cellular / no network" from
// the three permission-gate failures and the Android transient sentinel.
// Live data 2026-04-30 showed 54% NULL BSSID on primary-target rows; ~all of those
// were on cellular transport (not a bug, but ambiguous to analysts who saw
// "NULL" without immediately checking network_type_dominant). Emitting this
// explicit sentinel lets ASM filter
// `WHERE primary_bssid='no_wifi_connection'` directly.
internal const val BSSID_NO_WIFI_CONNECTION = "no_wifi_connection"

// Same sentinel family for SSID.
internal const val SSID_PERMISSION_DENIED = BSSID_PERMISSION_DENIED
internal const val SSID_NEARBY_WIFI_DENIED = BSSID_NEARBY_WIFI_DENIED
internal const val SSID_LOCATION_OFF = BSSID_LOCATION_OFF
internal const val SSID_NO_WIFI_CONNECTION = BSSID_NO_WIFI_CONNECTION

// v1.5.6 MAC randomization values emitted on the wire as `mac_randomization`.
// SDK 31+ reads from WifiInfo.macRandomizationSetting via reflection; pre-SDK 31
// reports "unsupported" so analysts know detection wasn't possible.
//
// Wire-format alignment with ASM dashboard 2026-05-01 KT — values must match
// exactly: none / persistent / random / non_persistent_random / unknown.
// The string "non_persistent_random" (not "non_persistent") is the dashboard's
// canonical value; "random" is the catch-all for unrecognized non-zero ints
// (vendor-specific or future-Android additions). "unsupported" is NetMon-specific
// — ASM renders it as a yellow span until they add it to the known-set.
internal const val MAC_RANDOMIZATION_NONE = "none"                                // device MAC — good for whitelist SSIDs
internal const val MAC_RANDOMIZATION_PERSISTENT = "persistent"                    // randomized once per SSID (Android default)
internal const val MAC_RANDOMIZATION_NON_PERSISTENT_RANDOM = "non_persistent_random"  // re-randomized every connect (worst)
internal const val MAC_RANDOMIZATION_RANDOM = "random"                            // unrecognized non-zero int (future-proof catch-all)
internal const val MAC_RANDOMIZATION_UNKNOWN = "unknown"                          // SDK 31+ but read threw / null
internal const val MAC_RANDOMIZATION_UNSUPPORTED = "unsupported"                  // SDK < 31 / not on Wi-Fi

/** Outcome of a pre-read permission + location-toggle check. */
internal enum class WifiIdentityAccess {
    ALLOWED,
    PERMISSION_DENIED,     // pre-SDK 33 fine-location missing
    NEARBY_WIFI_DENIED,    // SDK 33+ NEARBY_WIFI_DEVICES missing
    LOCATION_OFF           // system-wide location services off
}

/**
 * Captures Wi-Fi / network context at sample time and at flush time.
 *
 * Two entry points:
 * - [snapshot] is cheap (<1ms) and intended to be called per sample (1 Hz × 5 targets).
 * - [scanSnapshot] triggers `WifiManager.startScan()` and is subject to Android's
 *   scan rate limit — call at most once per 15-minute flush window.
 */
class WifiMetadataCollector(private val context: Context) {

    private val tag = "PingCore.Wifi"
    private val wifiManager =
        context.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val locationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    // Logged once per collector instance per failure mode, so logcat doesn't
    // drown in repeat messages when a permission is denied for the session.
    @Volatile private var loggedAccess: WifiIdentityAccess? = null

    fun snapshot(): WifiSnapshot {
        val nowMs = System.currentTimeMillis()
        val activeNetwork = connectivityManager.activeNetwork
        val caps = activeNetwork?.let { connectivityManager.getNetworkCapabilities(it) }
        val networkType = mapNetworkType(caps)
        val vpnActive = caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true

        if (networkType != "wifi") {
            // v1.5.6: explicit "no_wifi_connection" sentinel instead of bssid=null.
            // Disambiguates "rep on cellular" from "rep on Wi-Fi but BSSID lookup
            // raced/failed" in the data. Aggregator's countTransitions still treats
            // string transitions correctly (cellular ↔ wifi BSSID counts as 1
            // transition); countNonNullTransitions skips the sentinel because
            // length != 17 (sentinel is 18 chars), so physical-AP-changes is
            // unaffected.
            return WifiSnapshot(
                ssid = SSID_NO_WIFI_CONNECTION,
                bssid = BSSID_NO_WIFI_CONNECTION,
                rssi = null,
                linkSpeedMbps = null, frequencyMhz = null,
                networkType = networkType,
                vpnActive = vpnActive,
                collectedAtMs = nowMs,
                macRandomization = MAC_RANDOMIZATION_UNSUPPORTED,
                wifiHasIp = null
            )
        }

        @Suppress("DEPRECATION")
        val info: WifiInfo? = wifiManager.connectionInfo
        val access = checkWifiIdentityAccess()

        val (bssid, ssid) = when (access) {
            WifiIdentityAccess.ALLOWED -> {
                // Even with permission, Android can still return the sentinel BSSID
                // transiently during Wi-Fi disconnect/reconnect. Preserve that raw
                // value — downstream analysis already knows to treat it as
                // "transient disconnect state" when permission is good.
                Pair(info?.bssid, stripSsidQuotes(info?.ssid))
            }
            WifiIdentityAccess.PERMISSION_DENIED -> {
                logAccessOnce(access, "ACCESS_FINE_LOCATION not granted — BSSID/SSID will report '$BSSID_PERMISSION_DENIED'")
                Pair(BSSID_PERMISSION_DENIED, SSID_PERMISSION_DENIED)
            }
            WifiIdentityAccess.NEARBY_WIFI_DENIED -> {
                logAccessOnce(access, "NEARBY_WIFI_DEVICES not granted on SDK ${Build.VERSION.SDK_INT} — BSSID/SSID will report '$BSSID_NEARBY_WIFI_DENIED'")
                Pair(BSSID_NEARBY_WIFI_DENIED, SSID_NEARBY_WIFI_DENIED)
            }
            WifiIdentityAccess.LOCATION_OFF -> {
                logAccessOnce(access, "Device location services are off — BSSID/SSID will report '$BSSID_LOCATION_OFF'")
                Pair(BSSID_LOCATION_OFF, SSID_LOCATION_OFF)
            }
        }

        return WifiSnapshot(
            ssid = ssid,
            bssid = bssid,
            rssi = normalizeRssi(info?.rssi),
            linkSpeedMbps = info?.linkSpeed,
            frequencyMhz = info?.frequency,
            networkType = networkType,
            vpnActive = vpnActive,
            collectedAtMs = nowMs,
            // v1.5.6: MAC randomization detection.
            // Some enterprise SSIDs enforce a MAC whitelist on the controller. When a phone's
            // OS picks "Use random MAC" (Android default since 10), the rep is
            // associated to the SSID at L2 but their randomized MAC isn't in the
            // whitelist → DHCP may fail or L3 traffic gets filtered. Symptom:
            // "I'm on the office Wi-Fi but VoIP doesn't work." Detection: only public on
            // SDK 31+ via WifiInfo.macRandomizationSetting. Pre-SDK 31 reports
            // "unsupported" so ASM analysts know we tried and the OS didn't
            // expose it. Strong forensic signal when joined with bad
            // p95 RTT on a whitelisted primary_ssid.
            macRandomization = readMacRandomization(info),
            // v1.5.6: did DHCP succeed? An AP MAC whitelist refusing a
            // lease is the OBSERVABLE symptom we can read without @SystemApi.
            wifiHasIp = readWifiHasIp(info)
        )
    }

    /**
     * v1.5.6: returns the WifiInfo.macRandomizationSetting value as a typed string.
     *
     * IMPORTANT: `WifiInfo.getMacRandomizationSetting()` is `@SystemApi @hide` —
     * not in the public android.jar (so we can't reference WifiInfo.RANDOMIZATION_*
     * constants directly), but the method exists at runtime on SDK 31+ devices.
     * Hidden-API enforcement may block reflection on Android 12+ for non-system
     * apps; we catch all throwables and fall back to "unknown" so a single
     * blocked call doesn't kill every Wi-Fi sample.
     *
     * When this works (older devices, OEM builds with relaxed enforcement,
     * grey-listed APIs), we get the strong signal. When it doesn't, the
     * `wifiHasIp` field gives an indirect symptom-level signal that a
     * regular-API-only app CAN read reliably.
     *
     * Documented integer values:
     *   0 — RANDOMIZATION_NONE (device MAC; good for MAC-whitelist SSIDs)
     *   1 — RANDOMIZATION_PERSISTENT (Android default since 10, collides with whitelist)
     *   2 — RANDOMIZATION_NON_PERSISTENT (worst — re-rolls every connect, SDK 33+)
     */
    private fun readMacRandomization(info: WifiInfo?): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return MAC_RANDOMIZATION_UNSUPPORTED
        }
        if (info == null) return MAC_RANDOMIZATION_UNKNOWN
        return try {
            val method = info.javaClass.getMethod("getMacRandomizationSetting")
            val raw = method.invoke(info) as? Int ?: return MAC_RANDOMIZATION_UNKNOWN
            when (raw) {
                0 -> MAC_RANDOMIZATION_NONE
                1 -> MAC_RANDOMIZATION_PERSISTENT
                2 -> MAC_RANDOMIZATION_NON_PERSISTENT_RANDOM
                // Vendor-specific or future-Android randomization variants. ASM
                // dashboard renders this as the catch-all "randomized but not
                // exactly persistent/non-persistent" tier. Better than emitting
                // "unknown" (which means "we couldn't read at all") because we
                // DID read a value — we just don't have a canonical name for it.
                else -> MAC_RANDOMIZATION_RANDOM
            }
        } catch (e: Throwable) {
            // Hidden-API blacklist on Android 12+ may throw NoSuchMethodException,
            // SecurityException, or InvocationTargetException with a wrapped
            // IllegalAccessException. Don't log per-sample (logcat noise — this
            // happens at 1 Hz × 5 samplers); silently degrade to "unknown".
            MAC_RANDOMIZATION_UNKNOWN
        }
    }

    /**
     * v1.5.6: did the device get a DHCP-assigned IPv4 on the current Wi-Fi?
     *
     * This is the OBSERVABLE symptom of MAC randomization colliding with a MAC
     * whitelist (enterprise AP controller): the device associates at L2 to the
     * SSID, but the controller refuses to lease an IP because the randomized
     * MAC isn't in the whitelist. From a regular app, `WifiInfo.ipAddress`
     * returns 0 when no IPv4 has been assigned — strong forensic signal that
     * a privileged-API-free app CAN read.
     *
     * Returns true iff connected to Wi-Fi AND ipAddress != 0. Returns false on
     * Wi-Fi but no IP. Returns null on non-Wi-Fi transport (caller decides
     * what null means in that context — typically "not applicable").
     */
    @Suppress("DEPRECATION")
    private fun readWifiHasIp(info: WifiInfo?): Boolean? {
        if (info == null) return null
        // info.ipAddress is the legacy v1 IPv4 representation, deprecated SDK 31+
        // but still works for our purpose. The non-deprecated path requires
        // ConnectivityManager.getLinkProperties(network).linkAddresses which we
        // already query in GatewayResolver — but for sample-rate calls (1 Hz × 5
        // samplers = 5/sec), the legacy field is cheaper.
        val ip = info.ipAddress
        return ip != 0
    }

    /**
     * Android's `WifiInfo.getRssi()` is documented to return values in
     * [-127, -1] dBm — a negative-only range. Some OEM drivers (Xiaomi
     * HyperOS observed in v1.5.3 field data on the 2.4GHz radio
     * when `NEARBY_WIFI_DEVICES` was denied) return non-dBm sentinel
     * values like `0` or `+1`. Treat any non-negative value as "no
     * valid signal" by returning null — `MetricsCalculator.aggregate()`
     * already uses `mapNotNull { it.rssi }` so null samples are
     * cleanly excluded from rssi_min/avg/max. Downstream analyst
     * pivots can rely on the column being negative-only or null,
     * never garbage.
     *
     * Why null and not the documented `INVALID_RSSI = -127` sentinel:
     * -127 would still flow into the aggregation and skew the min,
     * making "is the rep on a weak AP?" pivots harder to read. null
     * is honest — we attempted but got an invalid reading.
     */
    companion object {
        /**
         * Pure helper — JVM-testable without an Android Context.
         * See KDoc above for design rationale.
         */
        internal fun normalizeRssi(raw: Int?): Int? = when {
            raw == null -> null
            raw >= 0 -> null
            else -> raw
        }
    }

    fun scanSnapshot(): ScanSnapshot? {
        val nowMs = System.currentTimeMillis()
        // Short-circuit if we already know the identity-access path will
        // refuse the scan: startScan() on SDK 33+ without NEARBY_WIFI_DEVICES
        // throws SecurityException, and on any SDK without fine location it
        // returns empty results.
        val access = checkWifiIdentityAccess()
        if (access != WifiIdentityAccess.ALLOWED) {
            Log.d(tag, "scanSnapshot: skipping startScan — identity access = $access")
            return null
        }

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

    /**
     * Returns the permission-state snapshot used to encode the
     * `permission_state` Sheet column. Kept public so PingService can emit
     * at flush time without re-implementing the checks.
     */
    fun permissionStateSnapshot(): PermissionStateSnapshot {
        return PermissionStateSnapshot(
            fineLocationGranted = hasPerm(Manifest.permission.ACCESS_FINE_LOCATION),
            nearbyWifiGranted =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                    hasPerm(Manifest.permission.NEARBY_WIFI_DEVICES)
                else true,  // not required on pre-SDK 33; treat as granted
            backgroundLocationGranted =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                    hasPerm(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                else true,
            postNotificationsGranted =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                    hasPerm(Manifest.permission.POST_NOTIFICATIONS)
                else true,
            locationServicesOn = isLocationServicesOn()
        )
    }

    /**
     * Internal: runs the three-way gate check for BSSID / SSID / scan reads.
     * Visibility exposed for unit tests via the package-private helper below.
     */
    internal fun checkWifiIdentityAccess(): WifiIdentityAccess {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!hasPerm(Manifest.permission.NEARBY_WIFI_DEVICES)) {
                return WifiIdentityAccess.NEARBY_WIFI_DENIED
            }
        }
        // Fine location still required on all SDKs for these APIs to return
        // real values — even if targetSdk < 33 no longer needs nearby-wifi.
        if (!hasPerm(Manifest.permission.ACCESS_FINE_LOCATION)) {
            return WifiIdentityAccess.PERMISSION_DENIED
        }
        if (!isLocationServicesOn()) {
            return WifiIdentityAccess.LOCATION_OFF
        }
        return WifiIdentityAccess.ALLOWED
    }

    private fun hasPerm(name: String): Boolean =
        ContextCompat.checkSelfPermission(context, name) == PackageManager.PERMISSION_GRANTED

    private fun isLocationServicesOn(): Boolean {
        val lm = locationManager ?: return false
        return try {
            // SDK 28+ exposes isLocationEnabled() — simpler + more accurate than
            // polling individual providers. All our target devices are SDK 26+,
            // but 26/27 lack it, so we fall back to provider polling there.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                lm.isLocationEnabled
            } else {
                @Suppress("DEPRECATION")
                lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                    lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            }
        } catch (e: Exception) {
            Log.w(tag, "isLocationServicesOn: check threw, assuming off", e)
            false
        }
    }

    private fun logAccessOnce(access: WifiIdentityAccess, message: String) {
        if (loggedAccess == access) return
        loggedAccess = access
        Log.w(tag, message)
    }
}

/**
 * Lightweight record of the app's permission + location state at a point in
 * time. PingService encodes this into the `permission_state` Sheet column on
 * every flush, so fleet-wide permission regressions are visible from the
 * Sheet without needing adb.
 */
data class PermissionStateSnapshot(
    val fineLocationGranted: Boolean,
    val nearbyWifiGranted: Boolean,
    val backgroundLocationGranted: Boolean,
    val postNotificationsGranted: Boolean,
    val locationServicesOn: Boolean
) {
    /**
     * Compact single-string form for a Sheet cell.
     * Example: "loc=on;fine=1;nearby=1;bgloc=1;notif=1"
     */
    fun encode(): String = buildString {
        append("loc="); append(if (locationServicesOn) "on" else "off")
        append(";fine="); append(if (fineLocationGranted) "1" else "0")
        append(";nearby="); append(if (nearbyWifiGranted) "1" else "0")
        append(";bgloc="); append(if (backgroundLocationGranted) "1" else "0")
        append(";notif="); append(if (postNotificationsGranted) "1" else "0")
    }
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
