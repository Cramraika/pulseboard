package com.pulseboard.core

/**
 * Result of one `WifiManager.startScan()` + `scanResults` read. Captured once per
 * 15-minute flush window (Android 9+ rate-limits scans to ~4 per 2 min, so per-sample
 * scanning is not viable).
 *
 * `visibleApsCount` and `bestAvailableRssi` let downstream analysis detect the
 * classic sticky-client failure: device connects to AP-A at -55 dBm, walks toward
 * AP-B, signal degrades to -78 dBm, device never roams even though AP-B is audible
 * at -48 dBm. The "sticky gap" = best_available_rssi − current_rssi.
 */
data class ScanSnapshot(
    val visibleApsCount: Int,
    val bestAvailableBssid: String?,
    val bestAvailableRssi: Int?,
    val scannedAtMs: Long
)
