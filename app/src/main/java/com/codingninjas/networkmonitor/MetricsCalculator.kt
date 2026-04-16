package com.codingninjas.networkmonitor

import kotlin.math.sqrt

data class NetworkMetrics(
    val avgPing: Double,
    val bestPing: Double,
    val worstPing: Double,
    val jitter: Double,
    val packetLoss: Double
)

object MetricsCalculator {
    fun calculate(pingResult: PingResult): NetworkMetrics {
        if (!pingResult.success || pingResult.rtts.isEmpty()) {
            return NetworkMetrics(0.0, 0.0, 0.0, 0.0, 0.0)
        }

        val rtts = pingResult.rtts
        val n = rtts.size
        val mean = rtts.sum() / n
        val variance = rtts.sumOf { (it - mean) * (it - mean) } / n
        val stdDev = sqrt(variance)

        return NetworkMetrics(
            avgPing = round1(mean),
            bestPing = round1(rtts.min()),
            worstPing = round1(rtts.max()),
            jitter = round1(stdDev),
            packetLoss = pingResult.packetLoss
        )
    }

    private fun round1(value: Double): Double = Math.round(value * 10.0) / 10.0
}
