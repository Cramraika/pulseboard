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

        val rssiValues = snapshots.mapNotNull { it.rssi }
        val rssiMin = rssiValues.minOrNull()
        val rssiMax = rssiValues.maxOrNull()
        val rssiAvg = if (rssiValues.isNotEmpty()) rssiValues.average().toInt() else null

        val networkTypeDominant = snapshots
            .groupingBy { it.networkType }.eachCount()
            .maxByOrNull { it.value }?.key ?: "none"

        val vpnDominant = snapshots.count { it.vpnActive } > snapshots.size / 2

        val current = snapshots.lastOrNull()

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
            vpnActive = vpnDominant
        )
    }

    private fun <T> countTransitions(values: List<T?>): Int {
        if (values.size < 2) return 0
        var count = 0
        for (i in 1 until values.size) {
            if (values[i] != values[i - 1]) count++
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
