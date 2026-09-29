package com.pulseboard.core

import kotlin.math.pow
import kotlin.math.sqrt

data class NetworkMetrics(
    val avgPing: Double?,
    val minPing: Double?,
    val maxPing: Double?,
    val p50Ping: Double?,
    val p95Ping: Double?,
    val p99Ping: Double?,
    val jitter: Double?,
    // Null when samples list was non-empty but every sample was `unreachable=true`
    // (loss denominator = 0; loss is undefined without reachable measurements).
    // 0.0 when samples list was empty (no attempts, no loss).
    val packetLoss: Double?,
    val samplesCount: Int,
    // Samples that were `unreachable=false` (i.e. had a resolvable target).
    // Defaulted to samplesCount so pre-v1.1 callers that build NetworkMetrics
    // directly still compile; aggregate() always sets this explicitly.
    val reachableSamplesCount: Int = samplesCount,
    val maxRttOffsetSec: Int?
)

object MetricsCalculator {

    fun aggregate(samples: List<Sample>): NetworkMetrics {
        if (samples.isEmpty()) {
            return NetworkMetrics(
                avgPing = null, minPing = null, maxPing = null,
                p50Ping = null, p95Ping = null, p99Ping = null,
                jitter = null, packetLoss = 0.0, samplesCount = 0,
                reachableSamplesCount = 0,
                maxRttOffsetSec = null
            )
        }

        val total = samples.size
        val reachable = samples.filter { !it.unreachable }
        val reachableCount = reachable.size

        if (reachableCount == 0) {
            // Window had samples but none were reachable (e.g. gateway target
            // had no resolvable address for the full 15-min window). Loss is
            // undefined without a reachable denominator → null.
            return NetworkMetrics(
                avgPing = null, minPing = null, maxPing = null,
                p50Ping = null, p95Ping = null, p99Ping = null,
                jitter = null, packetLoss = null, samplesCount = total,
                reachableSamplesCount = 0,
                maxRttOffsetSec = null
            )
        }

        val successful = reachable.filter { it.rttMs != null }
        val rtts = successful.mapNotNull { it.rttMs }
        val loss = ((reachableCount - rtts.size).toDouble() / reachableCount) * 100

        if (rtts.isEmpty()) {
            return NetworkMetrics(
                avgPing = null, minPing = null, maxPing = null,
                p50Ping = null, p95Ping = null, p99Ping = null,
                jitter = null, packetLoss = round1(loss), samplesCount = total,
                reachableSamplesCount = reachableCount,
                maxRttOffsetSec = null
            )
        }

        val windowStartMs = samples.minOf { it.tsMs }
        val sorted = rtts.sorted()
        val mean = rtts.sum() / rtts.size
        val variance = rtts.sumOf { (it - mean).pow(2) } / rtts.size
        val stdDev = sqrt(variance)

        val maxRtt = rtts.max()
        val maxSample = successful.first { it.rttMs == maxRtt }
        val maxOffset = ((maxSample.tsMs - windowStartMs) / 1000L).toInt()

        return NetworkMetrics(
            avgPing = round1(mean),
            minPing = round1(sorted.first()),
            maxPing = round1(sorted.last()),
            p50Ping = round1(percentile(sorted, 50.0)),
            p95Ping = round1(percentile(sorted, 95.0)),
            p99Ping = round1(percentile(sorted, 99.0)),
            jitter = round1(stdDev),
            packetLoss = round1(loss),
            samplesCount = total,
            reachableSamplesCount = reachableCount,
            maxRttOffsetSec = maxOffset
        )
    }

    /**
     * Counts pairs of consecutive samples (by timestamp) whose delta exceeds
     * [thresholdMs]. Used as a proxy for brief service disconnections / OEM
     * throttle pauses — v1.0 sampler runs at 1 Hz, so a gap > 3 s means at
     * least two samples' worth of time was lost.
     *
     * Sorts by timestamp first, so unsorted input is handled.
     */
    fun gapsCount(samples: List<Sample>, thresholdMs: Long = 3000L): Int {
        if (samples.size < 2) return 0
        val sorted = samples.sortedBy { it.tsMs }
        var count = 0
        for (i in 1 until sorted.size) {
            if (sorted[i].tsMs - sorted[i - 1].tsMs > thresholdMs) count++
        }
        return count
    }

    /**
     * Window-level Wi-Fi aggregates computed from every sample's [WifiSnapshot].
     * Samples without a snapshot (e.g. v1.0-style construction with no wifi)
     * are skipped — their RTT data still contributes to per-target metrics,
     * but they don't affect Wi-Fi context.
     */
    fun deviceLevelAggregates(samples: List<Sample>): DeviceAggregates {
        val snapshots = samples
            .sortedBy { it.tsMs }
            .mapNotNull { it.wifi }
        if (snapshots.isEmpty()) return DeviceAggregates.EMPTY

        val bssidChanges = countTransitions(snapshots.map { it.bssid })
        val ssidChanges = countTransitions(snapshots.map { it.ssid })

        // Physical AP changes: collapse BSSID to 5-octet prefix (OUI+slot, 14 chars),
        // count transitions between different non-null prefixes. Skips sentinels and
        // permission-blip nulls so a temporary null doesn't count as an AP change.
        // Only real MAC addresses (length 17, colon at index 2) get collapsed;
        // typed sentinels ("permission_denied", "02:00:00:00:00:00", etc.) map to null.
        val physicalPrefixes = snapshots.map { snap ->
            val b = snap.bssid
            if (b != null && b.length == 17 && b[2] == ':') b.take(14) else null
        }
        val physicalApChanges = countNonNullTransitions(physicalPrefixes)

        val rssiValues = snapshots.mapNotNull { it.rssi }
        val rssiMin = rssiValues.minOrNull()
        val rssiMax = rssiValues.maxOrNull()
        val rssiAvg = if (rssiValues.isNotEmpty()) rssiValues.average().toInt() else null

        val networkTypeDominant = snapshots
            .groupingBy { it.networkType }.eachCount()
            .maxByOrNull { it.value }?.key ?: "none"

        val vpnDominant = snapshots.count { it.vpnActive } > snapshots.size / 2

        val current = snapshots.lastOrNull()

        // v1.5.6: dominant MAC randomization across the window. Filter out
        // "unsupported" (cellular / pre-SDK 31) when computing the dominant
        // so the "real" Wi-Fi reading wins over no-Wi-Fi noise in mixed
        // windows. If every snapshot was unsupported, fall through to it
        // explicitly.
        val macRandValues = snapshots.mapNotNull { it.macRandomization.ifBlank { null } }
        val macRandDominant = run {
            val realValues = macRandValues.filter { it != "unsupported" }
            if (realValues.isNotEmpty()) {
                realValues.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
                    ?: "unsupported"
            } else if (macRandValues.isNotEmpty()) "unsupported" else "unsupported"
        }

        // v1.5.6: did any Wi-Fi snapshot report "associated but no IP"? That's
        // the MAC-whitelist DHCP-block symptom. Aggregate: true iff at least one
        // wifi snapshot had wifiHasIp == false. Null if no Wi-Fi snapshots in
        // the window (all cellular).
        val wifiSnapshots = snapshots.filter { it.networkType == "wifi" }
        val wifiNoIpObserved: Boolean? = if (wifiSnapshots.isEmpty()) {
            null
        } else {
            wifiSnapshots.any { it.wifiHasIp == false }
        }

        return DeviceAggregates(
            bssidChangesCount = bssidChanges,
            ssidChangesCount = ssidChanges,
            rssiMin = rssiMin,
            rssiAvg = rssiAvg,
            rssiMax = rssiMax,
            networkTypeDominant = networkTypeDominant,
            primaryBssid = dominant(snapshots.map { it.bssid }),
            primarySsid = dominant(snapshots.map { it.ssid }),
            primaryFrequencyMhz = dominant(snapshots.map { it.frequencyMhz }),
            primaryLinkSpeedMbps = dominant(snapshots.map { it.linkSpeedMbps }),
            currentBssid = current?.bssid,
            currentRssi = current?.rssi,
            vpnActive = vpnDominant,
            physicalApChangesCount = physicalApChanges,
            dominantMacRandomization = macRandDominant,
            wifiNoIpObserved = wifiNoIpObserved
        )
    }

    /**
     * v1.5.6: client-side derivation of `unreachable_pct` so analysts have
     * an explicit denominator without recomputing from samples_count vs
     * reachable_samples_count. Returns null when samples=0 (no data, no
     * meaningful percentage); otherwise (samples - reachable) / samples * 100.
     */
    fun unreachablePct(samplesCount: Int, reachableSamplesCount: Int): Double? {
        if (samplesCount <= 0) return null
        val unreachable = (samplesCount - reachableSamplesCount).coerceAtLeast(0)
        return Math.round(unreachable.toDouble() / samplesCount.toDouble() * 1000.0) / 10.0
    }

    /**
     * v1.5.6: three-tier churn classification driven by Constants thresholds.
     * Returns "low" / "high" / "fault" based on the bssidChangesCount value.
     * Threshold values are passed in so :core stays decoupled from the :app
     * Constants object.
     *
     * Boundary semantics (aligned with ASM dashboard expectations 2026-05-01 KT):
     *   bssidChangesCount ≤ highThreshold  → "low"   (e.g. ≤ 15)
     *   bssidChangesCount in (high..fault) → "high"  (e.g. 16–29)
     *   bssidChangesCount ≥ faultThreshold → "fault" (e.g. ≥ 30)
     *
     * Note: the boundary AT faultThreshold is "fault", AT highThreshold is "low".
     * This matches the ASM dashboard's `low (≤15) / high (16–29) / fault (≥30)`
     * spec exactly.
     */
    fun churnTier(bssidChangesCount: Int, highThreshold: Int, faultThreshold: Int): String =
        when {
            bssidChangesCount >= faultThreshold -> "fault"
            bssidChangesCount > highThreshold -> "high"
            else -> "low"
        }

    /**
     * Returns the top-2 5-octet BSSID prefixes (pipe-separated) most involved in
     * transitions within the window. Used by PingService when [highBssidChurn] is
     * true to tell the backend "device is flapping between AP X and AP Y" vs "walking past
     * 4 physical APs". Returns null if no non-null transitions exist.
     *
     * Each real MAC prefix participating in at least one transition is scored by how
     * many transitions it was part of; the top-2 are returned. Sentinels and permission-
     * blip nulls are ignored (same logic as physicalApChangesCount above).
     */
    fun churnBssidPrefixes(samples: List<Sample>): String? {
        val prefixes = samples.sortedBy { it.tsMs }.mapNotNull { it.wifi }.map { snap ->
            val b = snap.bssid
            if (b != null && b.length == 17 && b[2] == ':') b.take(14) else null
        }
        val transitionScore = mutableMapOf<String, Int>()
        var prev: String? = null
        for (p in prefixes) {
            if (p != null && prev != null && p != prev) {
                transitionScore[prev] = (transitionScore[prev] ?: 0) + 1
                transitionScore[p] = (transitionScore[p] ?: 0) + 1
            }
            if (p != null) prev = p
        }
        return transitionScore.entries
            .sortedByDescending { it.value }
            .take(2)
            .joinToString("|") { it.key }
            .takeIf { it.isNotEmpty() }
    }

    private fun <T> countTransitions(values: List<T?>): Int {
        if (values.size < 2) return 0
        var count = 0
        for (i in 1 until values.size) {
            if (values[i] != values[i - 1]) count++
        }
        return count
    }

    // Counts transitions between consecutive non-null values; null entries are skipped.
    // Use this when null represents a transient permission blip or sentinel, not a
    // real state change (i.e., "no address" should not count as "moved APs").
    private fun <T> countNonNullTransitions(values: List<T?>): Int {
        var count = 0
        var prev: T? = null
        for (v in values) {
            if (v == null) continue
            if (prev != null && v != prev) count++
            prev = v
        }
        return count
    }

    private fun <T : Any> dominant(values: List<T?>): T? {
        val nonNull = values.filterNotNull()
        if (nonNull.isEmpty()) return null
        return nonNull.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
    }

    private fun percentile(sorted: List<Double>, p: Double): Double {
        // Caller (aggregate) must filter empty RTT lists before reaching this.
        // Total-loss windows are routed to null fields without invoking percentile().
        require(sorted.isNotEmpty()) { "percentile() called on empty list — caller bug" }
        if (sorted.size == 1) return sorted[0]
        val rank = (p / 100.0) * (sorted.size - 1)
        val lo = rank.toInt()
        val hi = (lo + 1).coerceAtMost(sorted.size - 1)
        val frac = rank - lo
        return sorted[lo] + frac * (sorted[hi] - sorted[lo])
    }

    private fun round1(v: Double): Double = Math.round(v * 10.0) / 10.0
}
