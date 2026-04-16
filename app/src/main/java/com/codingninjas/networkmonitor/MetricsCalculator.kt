package com.codingninjas.networkmonitor

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
    val packetLoss: Double,
    val samplesCount: Int,
    val maxRttOffsetSec: Int?
)

object MetricsCalculator {

    fun aggregate(samples: List<Sample>): NetworkMetrics {
        if (samples.isEmpty()) {
            return NetworkMetrics(
                avgPing = null, minPing = null, maxPing = null,
                p50Ping = null, p95Ping = null, p99Ping = null,
                jitter = null, packetLoss = 0.0, samplesCount = 0,
                maxRttOffsetSec = null
            )
        }

        val total = samples.size
        val successful = samples.filter { it.rttMs != null }
        val rtts = successful.mapNotNull { it.rttMs }
        val loss = ((total - rtts.size).toDouble() / total) * 100

        if (rtts.isEmpty()) {
            return NetworkMetrics(
                avgPing = null, minPing = null, maxPing = null,
                p50Ping = null, p95Ping = null, p99Ping = null,
                jitter = null, packetLoss = round1(loss), samplesCount = total,
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
