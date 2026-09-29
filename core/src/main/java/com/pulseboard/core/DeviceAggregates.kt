package com.pulseboard.core

/**
 * Window-level Wi-Fi / network aggregates computed across ALL targets' samples.
 *
 * Duplicated into each of the N per-target rows emitted per flush so each Sheet
 * row is self-contained (no joins needed for non-technical viewers).
 *
 * - `*ChangesCount` values count null↔value and value↔value transitions walking
 *   the timestamp-sorted sample list. value↔same-value does not count.
 * - `primary*` values are the most-frequent non-null value across the window.
 * - `current*` values come from the latest sample's snapshot — useful for
 *   "was the device on X at the moment of flush."
 * - `vpnActive` is true when the majority of snapshots had VPN transport.
 */
data class DeviceAggregates(
    val bssidChangesCount: Int,
    val ssidChangesCount: Int,
    val rssiMin: Int?,
    val rssiAvg: Int?,
    val rssiMax: Int?,
    val networkTypeDominant: String,
    val primaryBssid: String?,
    val primarySsid: String?,
    val primaryFrequencyMhz: Int?,
    val primaryLinkSpeedMbps: Int?,
    val currentBssid: String?,
    val currentRssi: Int?,
    val vpnActive: Boolean,
    // v1.5.5: BSSID transitions after collapsing to 5-octet physical-AP prefix.
    // Counts only real-MAC-to-different-real-MAC transitions; sentinels / nulls
    // are skipped so a permission blip doesn't inflate the physical-roam count.
    val physicalApChangesCount: Int = 0,
    // v1.5.6: dominant macRandomization across the window's snapshots. "none"
    // is the only safe value for a MAC-whitelisted SSID; any other value
    // (especially "persistent") on such an SSID is the smoking gun for
    // silent VoIP failure.
    val dominantMacRandomization: String = "unsupported",
    // v1.5.6: was the device on Wi-Fi WITHOUT a DHCP-assigned IP for any
    // sample in the window? OBSERVABLE symptom of MAC-whitelist DHCP-block.
    // Per-snapshot value is bool? (null on cellular); aggregate = "true if
    // any wifi snapshot reported false". Cleaner ASM filter:
    //   WHERE wifi_no_ip_observed = true AND primary_ssid = '<whitelisted SSID>'
    val wifiNoIpObserved: Boolean? = null
) {
    companion object {
        val EMPTY = DeviceAggregates(
            bssidChangesCount = 0,
            ssidChangesCount = 0,
            rssiMin = null, rssiAvg = null, rssiMax = null,
            networkTypeDominant = "none",
            primaryBssid = null, primarySsid = null,
            primaryFrequencyMhz = null, primaryLinkSpeedMbps = null,
            currentBssid = null, currentRssi = null,
            vpnActive = false,
            physicalApChangesCount = 0,
            dominantMacRandomization = "unsupported",
            wifiNoIpObserved = null
        )
    }
}
