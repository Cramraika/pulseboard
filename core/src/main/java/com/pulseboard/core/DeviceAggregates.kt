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
    val vpnActive: Boolean
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
            vpnActive = false
        )
    }
}
