package com.pulseboard.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WifiMetadataCollectorTest {

    @Test
    fun `stripSsidQuotes removes surrounding double quotes`() {
        assertEquals("Office-WiFi", stripSsidQuotes("\"Office-WiFi\""))
    }

    @Test
    fun `stripSsidQuotes preserves unquoted strings`() {
        assertEquals("Office-WiFi", stripSsidQuotes("Office-WiFi"))
    }

    @Test
    fun `stripSsidQuotes preserves the unknown-ssid sentinel unchanged`() {
        assertEquals("<unknown ssid>", stripSsidQuotes("<unknown ssid>"))
    }

    @Test
    fun `stripSsidQuotes returns null for null input`() {
        assertNull(stripSsidQuotes(null))
    }

    // --- v1.2 typed sentinels ---

    @Test
    fun `sentinel constants are distinct across the three failure modes`() {
        val s = setOf(
            BSSID_PERMISSION_DENIED,
            BSSID_NEARBY_WIFI_DENIED,
            BSSID_LOCATION_OFF
        )
        assertEquals("each failure mode must emit a distinct sentinel", 3, s.size)
    }

    @Test
    fun `PermissionStateSnapshot encodes to expected compact string when all granted`() {
        val snap = PermissionStateSnapshot(
            fineLocationGranted = true,
            nearbyWifiGranted = true,
            backgroundLocationGranted = true,
            postNotificationsGranted = true,
            locationServicesOn = true
        )
        assertEquals(
            "loc=on;fine=1;nearby=1;bgloc=1;notif=1",
            snap.encode()
        )
    }

    @Test
    fun `PermissionStateSnapshot encodes denied + location-off cases`() {
        val snap = PermissionStateSnapshot(
            fineLocationGranted = true,
            nearbyWifiGranted = false,
            backgroundLocationGranted = false,
            postNotificationsGranted = true,
            locationServicesOn = false
        )
        assertEquals(
            "loc=off;fine=1;nearby=0;bgloc=0;notif=1",
            snap.encode()
        )
    }

    @Test
    fun `PermissionStateSnapshot encodes all-denied case`() {
        val snap = PermissionStateSnapshot(
            fineLocationGranted = false,
            nearbyWifiGranted = false,
            backgroundLocationGranted = false,
            postNotificationsGranted = false,
            locationServicesOn = false
        )
        assertEquals(
            "loc=off;fine=0;nearby=0;bgloc=0;notif=0",
            snap.encode()
        )
    }

    // ---- v1.5.4 RSSI normalization ----
    // Caught in v1.5.3 field data (2026-04-27): one device's first
    // 2 windows on a Xiaomi HyperOS device + 2.4GHz + NEARBY_WIFI_DEVICES
    // denied returned rssi_max=+1 and rssi_max=-2. Wi-Fi RSSI is documented
    // to be in [-127, -1] dBm. The driver was returning non-dBm sentinel
    // values; we faithfully recorded the garbage. Fix: normalize any
    // non-negative RSSI to null at the WifiMetadataCollector source so
    // MetricsCalculator's mapNotNull aggregation cleanly skips them.

    @Test
    fun `normalizeRssi passes through valid negative dBm`() {
        // No need to instantiate — normalizeRssi is in the companion object
        assertEquals(-30, WifiMetadataCollector.normalizeRssi(-30))
        assertEquals(-70, WifiMetadataCollector.normalizeRssi(-70))
        assertEquals(-90, WifiMetadataCollector.normalizeRssi(-90))
        assertEquals(-127, WifiMetadataCollector.normalizeRssi(-127))
        assertEquals(-1, WifiMetadataCollector.normalizeRssi(-1))
    }

    @Test
    fun `normalizeRssi nulls non-negative driver garbage`() {
        // No need to instantiate — normalizeRssi is in the companion object
        assertEquals(null, WifiMetadataCollector.normalizeRssi(0))
        assertEquals(null, WifiMetadataCollector.normalizeRssi(1))
        assertEquals(null, WifiMetadataCollector.normalizeRssi(50))
        assertEquals(null, WifiMetadataCollector.normalizeRssi(127))
    }

    @Test
    fun `normalizeRssi passes null through`() {
        // No need to instantiate — normalizeRssi is in the companion object
        assertEquals(null, WifiMetadataCollector.normalizeRssi(null))
    }

    // ---- v1.5.6 MAC randomization sentinel constants ----

    @Test
    fun `mac randomization sentinels are distinct strings`() {
        val s = setOf(
            MAC_RANDOMIZATION_NONE,
            MAC_RANDOMIZATION_PERSISTENT,
            MAC_RANDOMIZATION_NON_PERSISTENT_RANDOM,
            MAC_RANDOMIZATION_RANDOM,
            MAC_RANDOMIZATION_UNKNOWN,
            MAC_RANDOMIZATION_UNSUPPORTED
        )
        assertEquals("each MAC randomization mode must emit a distinct value", 6, s.size)
    }

    @Test
    fun `mac randomization values match ASM dashboard 2026-05-01 KT spec`() {
        // ASM dashboard handles: none / persistent / random / non_persistent_random / unknown
        // Plus NetMon-specific "unsupported" (renders as yellow until ASM adds it).
        // Wire alignment is required — these strings ship as-is on every flush row.
        assertEquals("none", MAC_RANDOMIZATION_NONE)
        assertEquals("persistent", MAC_RANDOMIZATION_PERSISTENT)
        assertEquals("non_persistent_random", MAC_RANDOMIZATION_NON_PERSISTENT_RANDOM)
        assertEquals("random", MAC_RANDOMIZATION_RANDOM)
        assertEquals("unknown", MAC_RANDOMIZATION_UNKNOWN)
        assertEquals("unsupported", MAC_RANDOMIZATION_UNSUPPORTED)
    }

    @Test
    fun `no_wifi_connection sentinel does not collide with other BSSID sentinels`() {
        val sentinels = setOf(
            BSSID_PERMISSION_DENIED,
            BSSID_NEARBY_WIFI_DENIED,
            BSSID_LOCATION_OFF,
            BSSID_NO_WIFI_CONNECTION,
            ANDROID_SENTINEL_BSSID
        )
        // All five must be distinct so analysts can pivot on root cause.
        assertEquals(5, sentinels.size)
    }

    @Test
    fun `no_wifi_connection sentinel does not look like a real MAC address`() {
        // The 5-octet-prefix collapse in MetricsCalculator.churnBssidPrefixes uses
        // length == 17 && colon-at-index-2 to filter real MACs. The new sentinel
        // must NOT match — otherwise cellular rows would inflate physical-AP
        // change counts.
        val s = BSSID_NO_WIFI_CONNECTION
        val looksLikeMac = s.length == 17 && s[2] == ':'
        assertEquals(false, looksLikeMac)
    }
}
