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
