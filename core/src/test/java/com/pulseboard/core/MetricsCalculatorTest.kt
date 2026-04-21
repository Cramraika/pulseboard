package com.pulseboard.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class MetricsCalculatorTest {

    @Test
    fun `all successful samples produce rounded aggregates`() {
        val baseTs = 1_000_000_000L
        val rtts = listOf(10.0, 20.0, 30.0, 40.0, 50.0)
        val samples = rtts.mapIndexed { i, rtt -> Sample(rtt, baseTs + i * 1000L) }
        val m = MetricsCalculator.aggregate(samples)

        assertEquals(30.0, m.avgPing!!, 0.001)
        assertEquals(10.0, m.minPing!!, 0.001)
        assertEquals(50.0, m.maxPing!!, 0.001)
        assertEquals(30.0, m.p50Ping!!, 0.001)          // median
        assertEquals(48.0, m.p95Ping!!, 0.001) // linear interpolation: 50*0.8 + 40*0.2? No — (0.95 * 4 = 3.8) → sorted[3]=40 + 0.8*(50-40) = 48
        assertEquals(49.6, m.p99Ping!!, 0.01)  // 0.99 * 4 = 3.96 → sorted[3]=40 + 0.96*(50-40) = 49.6
        assertEquals(0.0, m.packetLoss!!, 0.001)
        assertEquals(5, m.samplesCount)
        assertEquals(4, m.maxRttOffsetSec)     // last sample (50.0) at offset 4
    }

    @Test
    fun `jitter is population stddev`() {
        // Samples with known stddev: [2, 4, 4, 4, 5, 5, 7, 9] → mean=5, stddev=2.0
        val samples = listOf(2.0, 4.0, 4.0, 4.0, 5.0, 5.0, 7.0, 9.0)
            .mapIndexed { i, rtt -> Sample(rtt, 1_000_000_000L + i * 1000L) }
        val m = MetricsCalculator.aggregate(samples)
        assertEquals(2.0, m.jitter!!, 0.001)
    }

    @Test
    fun `single sample window`() {
        val m = MetricsCalculator.aggregate(listOf(Sample(42.0, 1_000L)))
        assertEquals(42.0, m.avgPing!!, 0.001)
        assertEquals(42.0, m.minPing!!, 0.001)
        assertEquals(42.0, m.maxPing!!, 0.001)
        assertEquals(42.0, m.p50Ping!!, 0.001)
        assertEquals(42.0, m.p99Ping!!, 0.001)
        assertEquals(0.0, m.jitter!!, 0.001)
        assertEquals(0.0, m.packetLoss!!, 0.001)
        assertEquals(1, m.samplesCount)
        assertEquals(0, m.maxRttOffsetSec)
    }

    @Test
    fun `partial loss — half samples failed`() {
        val base = 1_000_000L
        val samples = listOf(
            Sample(10.0, base),
            Sample(null, base + 1000),
            Sample(20.0, base + 2000),
            Sample(null, base + 3000)
        )
        val m = MetricsCalculator.aggregate(samples)
        assertEquals(50.0, m.packetLoss!!, 0.001)
        assertEquals(15.0, m.avgPing!!, 0.001)
        assertEquals(4, m.samplesCount)
    }

    @Test
    fun `total loss window — all RTT fields are null, loss is 100, samples_count preserved`() {
        val base = 1_000_000L
        val samples = (0..9).map { Sample(null, base + it * 1000L) }
        val m = MetricsCalculator.aggregate(samples)
        assertNull(m.avgPing)
        assertNull(m.minPing)
        assertNull(m.maxPing)
        assertNull(m.p50Ping)
        assertNull(m.p95Ping)
        assertNull(m.p99Ping)
        assertNull(m.jitter)
        assertNull(m.maxRttOffsetSec)
        assertEquals(100.0, m.packetLoss!!, 0.001)
        assertEquals(10, m.samplesCount)
    }

    @Test
    fun `empty list — all fields null or zero, loss is zero by convention`() {
        val m = MetricsCalculator.aggregate(emptyList())
        assertNull(m.avgPing)
        assertEquals(0.0, m.packetLoss!!, 0.001)   // no samples → no attempt → no loss either
        assertEquals(0, m.samplesCount)
    }

    @Test
    fun `maxRttOffsetSec is relative to earliest sample in drain`() {
        val base = 1_000_000_000L
        // offsets:   0,    10,   20,   30
        // rtts:      10,   20,   100,  15      → worst at offset 20
        val samples = listOf(
            Sample(10.0,  base),
            Sample(20.0,  base + 10_000L),
            Sample(100.0, base + 20_000L),
            Sample(15.0,  base + 30_000L)
        )
        val m = MetricsCalculator.aggregate(samples)
        assertEquals(100.0, m.maxPing)
        assertEquals(20, m.maxRttOffsetSec)
    }

    @Test
    fun `maxRttOffsetSec ignores null samples when finding max`() {
        val base = 500L
        val samples = listOf(
            Sample(null, base),          // offset 0, excluded
            Sample(50.0, base + 1000),   // offset 1
            Sample(null, base + 2000),   // offset 2, excluded
            Sample(99.0, base + 3000)    // offset 3, WINS
        )
        val m = MetricsCalculator.aggregate(samples)
        assertEquals(99.0, m.maxPing)
        assertEquals(3, m.maxRttOffsetSec)
    }

    @Test
    fun `rounding is to one decimal place`() {
        val samples = (1..3).map { Sample(it * 0.333333, 1_000L + it * 1000L) }
        val m = MetricsCalculator.aggregate(samples)
        // mean = 0.666666 → 0.7
        assertEquals(0.7, m.avgPing!!, 0.0001)
    }

    @Test
    fun `ties on max RTT resolve to earliest occurrence`() {
        // Two samples share the maximum value of 99.0.
        // Policy: earliest timestamp wins → offset should reflect the first one.
        val base = 10_000L
        val samples = listOf(
            Sample(50.0, base),
            Sample(99.0, base + 5_000L),    // offset 5 — should win
            Sample(30.0, base + 10_000L),
            Sample(99.0, base + 15_000L)    // offset 15 — tie, but not first
        )
        val m = MetricsCalculator.aggregate(samples)
        assertEquals(99.0, m.maxPing)
        assertEquals(5, m.maxRttOffsetSec)
    }

    @Test
    fun `empty buffer drain feeds aggregate without crash and produces zero-loss null-fields`() {
        // Integration-level assertion for the PingService flusher contract:
        // drain can return empty, and aggregate must tolerate it without throwing.
        val buffer = SampleBuffer()
        val drained = buffer.drain()
        val m = MetricsCalculator.aggregate(drained)
        assertEquals(0, m.samplesCount)
        assertEquals(0, m.reachableSamplesCount)
        assertEquals(0.0, m.packetLoss!!, 0.0)  // no attempts → no loss
        assertNull(m.avgPing)
        assertNull(m.maxRttOffsetSec)
    }

    // --- v1.1 unreachable-sample handling ---

    @Test
    fun `partial unreachable excluded from loss denominator`() {
        // 4 samples: 2 reachable (1 success + 1 timeout) + 2 unreachable.
        // Loss denominator = 2 reachable, not 4 total. Loss = 1/2 = 50%.
        val base = 1_000L
        val samples = listOf(
            Sample(10.0, base,              target = "t", unreachable = false),
            Sample(null, base + 1_000,      target = "t", unreachable = false),
            Sample(null, base + 2_000,      target = "t", unreachable = true),
            Sample(null, base + 3_000,      target = "t", unreachable = true)
        )
        val m = MetricsCalculator.aggregate(samples)
        assertEquals(50.0, m.packetLoss!!, 0.001)
        assertEquals(4, m.samplesCount)
        assertEquals(2, m.reachableSamplesCount)
        assertEquals(10.0, m.avgPing!!, 0.001)
    }

    @Test
    fun `all unreachable yields packet_loss_pct null and reachable_samples_count zero`() {
        val base = 1_000L
        val samples = (0..4).map {
            Sample(null, base + it * 1000L, target = "gateway", unreachable = true)
        }
        val m = MetricsCalculator.aggregate(samples)
        assertNull("loss undefined when no reachable denominator", m.packetLoss)
        assertEquals(5, m.samplesCount)
        assertEquals(0, m.reachableSamplesCount)
        assertNull(m.avgPing)
        assertNull(m.maxRttOffsetSec)
    }

    // --- v1.1 gapsCount ---

    @Test
    fun `gapsCount returns 0 for fewer than two samples`() {
        assertEquals(0, MetricsCalculator.gapsCount(emptyList()))
        assertEquals(0, MetricsCalculator.gapsCount(listOf(Sample(10.0, 1000L))))
    }

    @Test
    fun `gapsCount returns 0 when all deltas are at or below threshold`() {
        val samples = listOf(
            Sample(null, 0L),
            Sample(null, 1000L),     // delta 1000
            Sample(null, 4000L),     // delta 3000 (== threshold, not > )
            Sample(null, 6500L)      // delta 2500
        )
        assertEquals(0, MetricsCalculator.gapsCount(samples, thresholdMs = 3000L))
    }

    @Test
    fun `gapsCount counts each delta strictly exceeding threshold`() {
        val samples = listOf(
            Sample(null, 0L),
            Sample(null, 5000L),     // delta 5000 > 3000 → gap
            Sample(null, 6000L),     // delta 1000 → ok
            Sample(null, 10_500L)    // delta 4500 > 3000 → gap
        )
        assertEquals(2, MetricsCalculator.gapsCount(samples))
    }

    @Test
    fun `gapsCount sorts unsorted input by timestamp before scanning`() {
        val samples = listOf(
            Sample(null, 10_000L),
            Sample(null, 0L),
            Sample(null, 5_000L)
        )
        // Sorted: 0, 5000, 10_000. Deltas 5000 and 5000 → 2 gaps.
        assertEquals(2, MetricsCalculator.gapsCount(samples))
    }

    // --- v1.1 deviceLevelAggregates ---

    @Test
    fun `deviceLevelAggregates counts BSSID transitions and picks dominant values`() {
        val base = 1_000L
        val a = wifiSnapshot(bssid = "aa:aa", ssid = "Office", rssi = -55, networkType = "wifi", atMs = base)
        val b = wifiSnapshot(bssid = "bb:bb", ssid = "Office", rssi = -60, networkType = "wifi", atMs = base + 1000)
        val a2 = wifiSnapshot(bssid = "aa:aa", ssid = "Office", rssi = -50, networkType = "wifi", atMs = base + 2000)
        val samples = listOf(
            Sample(10.0, base,         target = "t", wifi = a),
            Sample(12.0, base + 1000,  target = "t", wifi = b),
            Sample(11.0, base + 2000,  target = "t", wifi = a2),
            Sample(15.0, base + 3000,  target = "t", wifi = a2)  // a2 again — no transition
        )
        val agg = MetricsCalculator.deviceLevelAggregates(samples)
        // transitions: a→b (1), b→a2 (2), a2→a2 (still 2)
        assertEquals(2, agg.bssidChangesCount)
        assertEquals(0, agg.ssidChangesCount)                 // constant Office
        assertEquals("aa:aa", agg.primaryBssid)               // 3 samples of aa:aa vs 1 of bb:bb
        assertEquals("Office", agg.primarySsid)
        assertEquals("wifi", agg.networkTypeDominant)
        assertEquals("aa:aa", agg.currentBssid)
        assertEquals(-50, agg.currentRssi)                    // latest snapshot
    }

    @Test
    fun `deviceLevelAggregates computes RSSI stats ignoring nulls and handles mixed transports`() {
        val base = 1_000L
        val wifi55 = wifiSnapshot(bssid = "aa", ssid = "Office", rssi = -55, networkType = "wifi", atMs = base)
        val wifi45 = wifiSnapshot(bssid = "aa", ssid = "Office", rssi = -45, networkType = "wifi", atMs = base + 1000)
        val cell = wifiSnapshot(bssid = null, ssid = null, rssi = null, networkType = "cellular", atMs = base + 2000)
        val vpn = wifiSnapshot(bssid = null, ssid = null, rssi = null, networkType = "wifi", atMs = base + 3000, vpn = true)
        val samples = listOf(
            Sample(null, base,          target = "t", wifi = wifi55),
            Sample(null, base + 1000,   target = "t", wifi = wifi45),
            Sample(null, base + 2000,   target = "t", wifi = cell),
            Sample(null, base + 3000,   target = "t", wifi = vpn)
        )
        val agg = MetricsCalculator.deviceLevelAggregates(samples)
        assertEquals(-55, agg.rssiMin)
        assertEquals(-45, agg.rssiMax)
        assertEquals(-50, agg.rssiAvg)                        // (-55 + -45) / 2
        assertEquals("wifi", agg.networkTypeDominant)         // 3 wifi vs 1 cellular
        assertFalse("vpn minority → dominant false", agg.vpnActive)
    }

    @Test
    fun `deviceLevelAggregates returns EMPTY defaults when no samples have wifi context`() {
        // Samples with wifi=null (v1.0-style construction) contribute nothing
        // to Wi-Fi aggregates, even if their RTT data is populated.
        val samples = listOf(
            Sample(10.0, 1000L),
            Sample(20.0, 2000L),
            Sample(null, 3000L)
        )
        val agg = MetricsCalculator.deviceLevelAggregates(samples)
        assertEquals(DeviceAggregates.EMPTY, agg)
        assertEquals("none", agg.networkTypeDominant)
        assertNull(agg.rssiAvg)
        assertNull(agg.primaryBssid)
    }

    private fun wifiSnapshot(
        bssid: String?, ssid: String?, rssi: Int?, networkType: String,
        atMs: Long, vpn: Boolean = false
    ) = WifiSnapshot(
        ssid = ssid, bssid = bssid, rssi = rssi,
        linkSpeedMbps = null, frequencyMhz = null,
        networkType = networkType, vpnActive = vpn,
        collectedAtMs = atMs
    )

    @Test
    fun `mixed unreachable and successful yields correct partial loss`() {
        // 4 samples: 2 successful + 1 timeout + 1 unreachable.
        // Reachable = 3, successful = 2, loss = 1/3 ≈ 33.3%
        val base = 1_000L
        val samples = listOf(
            Sample(10.0, base,          target = "t"),
            Sample(20.0, base + 1_000,  target = "t"),
            Sample(null, base + 2_000,  target = "t", unreachable = true),
            Sample(null, base + 3_000,  target = "t", unreachable = false)
        )
        val m = MetricsCalculator.aggregate(samples)
        assertEquals(33.3, m.packetLoss!!, 0.05)
        assertEquals(4, m.samplesCount)
        assertEquals(3, m.reachableSamplesCount)
        assertEquals(15.0, m.avgPing!!, 0.001)
    }
}
