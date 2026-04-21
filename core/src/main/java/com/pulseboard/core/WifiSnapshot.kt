package com.pulseboard.core

/**
 * One sample's-worth of Wi-Fi / network context.
 *
 * Captured at the moment a [Sample] is created (BEFORE the ping runs, so RTT and
 * Wi-Fi fields describe the same network state at t0). If the device roams during
 * the subsequent ping, the next sample picks up the new state cleanly.
 *
 * On non-Wi-Fi transports (cellular, ethernet, none), Wi-Fi-specific fields are
 * null but [networkType] is still populated.
 */
data class WifiSnapshot(
    val ssid: String?,
    val bssid: String?,
    val rssi: Int?,
    val linkSpeedMbps: Int?,
    val frequencyMhz: Int?,
    val networkType: String,      // "wifi" | "cellular" | "ethernet" | "none"
    val vpnActive: Boolean,
    val collectedAtMs: Long
)
