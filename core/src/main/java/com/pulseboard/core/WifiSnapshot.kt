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
    val collectedAtMs: Long,
    // v1.5.6: SDK 31+ WifiInfo.macRandomizationSetting (read via reflection because
    // it's @SystemApi). One of MAC_RANDOMIZATION_* constants in WifiMetadataCollector.kt.
    // Critical for diagnosing devices L2-associated to a MAC-whitelisted SSID but filtered by
    // the AP MAC whitelist due to phone-side MAC randomization. Default
    // "unsupported" so existing test fixtures and pre-SDK 31 paths don't break.
    // May report "unknown" on SDK 31+ if hidden-API enforcement blocks reflection.
    val macRandomization: String = "unsupported",
    // v1.5.6: did the device get a DHCP-assigned IPv4 on the current Wi-Fi?
    // True iff on Wi-Fi AND WifiInfo.ipAddress != 0. False on Wi-Fi without IP
    // (the OBSERVABLE symptom of MAC-whitelist DHCP-block). Null on non-Wi-Fi
    // (cellular / ethernet / no network).
    val wifiHasIp: Boolean? = null
)
