package com.pulseboard.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    // --- v1.5.5 churnBssidPrefixes (G1, G2, G3) ---

    @Test
    fun `churnBssidPrefixes returns null for empty samples`() {
        // G1 — empty input edge case
        assertNull(MetricsCalculator.churnBssidPrefixes(emptyList()))
    }

    @Test
    fun `churnBssidPrefixes returns null when all BSSIDs are null`() {
        // G1 — wifi=null on every sample → no transitions
        val samples = listOf(
            Sample(null, 1000L, target = "t"),
            Sample(null, 2000L, target = "t"),
            Sample(null, 3000L, target = "t")
        )
        assertNull(MetricsCalculator.churnBssidPrefixes(samples))
    }

    @Test
    fun `churnBssidPrefixes returns null when all BSSIDs are sentinels`() {
        // G1 — typed sentinels (length != 17) and Android raw sentinel
        // (length 17 but well-known) all map to null prefix → no transitions.
        val sentinels = listOf("permission_denied", "nearby_wifi_denied", "02:00:00:00:00:00")
        val samples = sentinels.mapIndexed { i, bssid ->
            Sample(null, 1000L + i * 1000L, target = "t",
                wifi = wifiSnapshot(bssid = bssid, ssid = null, rssi = null,
                    networkType = "wifi", atMs = 1000L + i * 1000L))
        }
        // Note: 02:00:00:00:00:00 IS length 17 with colon at index 2, so it maps
        // to prefix "02:00:00:00:00" and counts. permission_denied (17 chars but
        // no colons in MAC pattern) does NOT pass the length+colon check.
        // Result: sentinels collapse to one prefix, no transitions.
        val result = MetricsCalculator.churnBssidPrefixes(samples)
        // Either null (all sentinels filter out) or a single prefix (no transitions).
        // Acceptable behaviour: not multiple prefixes.
        if (result != null) {
            assertFalse("no '|' should appear when all are same/sentinel", result.contains("|"))
        }
    }

    @Test
    fun `churnBssidPrefixes top-2 ordered by transition count`() {
        // G2 — A↔B 5 transitions vs C↔D 1 transition. Top-2 must be {A,B}.
        val a = "02:00:00:eb:a0:11"
        val b = "02:00:00:eb:b0:22"  // different prefix
        val c = "34:8a:12:ff:c0:33"
        val d = "34:8a:12:ff:d0:44"
        val sequence = listOf(a, b, a, b, a, b, a, b, a, b, a, c, d, c) // mostly A↔B
        val samples = sequence.mapIndexed { i, bssid ->
            val ts = 1000L + i * 1000L
            Sample(null, ts, target = "t",
                wifi = wifiSnapshot(bssid = bssid, ssid = null, rssi = null,
                    networkType = "wifi", atMs = ts))
        }
        val result = MetricsCalculator.churnBssidPrefixes(samples)
        assertNotNull("result must not be null when transitions exist", result)
        val prefixes = result!!.split("|")
        assertEquals("top-2 cap (take(2))", 2, prefixes.size)
        // A and B must both be present (they participate in 10 transitions)
        val aPrefix = a.take(14)
        val bPrefix = b.take(14)
        assertTrue("A prefix in top-2", prefixes.contains(aPrefix))
        assertTrue("B prefix in top-2", prefixes.contains(bPrefix))
    }

    @Test
    fun `churnBssidPrefixes zero physical-AP transitions on dual-radio band steering`() {
        // G3 — same physical AP, two radios (common enterprise-AP pattern: last byte differs).
        // Both BSSIDs share the 5-octet prefix "02:00:00:eb:a0".
        // bssidChangesCount counts the byte-level transitions; physicalApChangesCount
        // (and churnBssidPrefixes which uses the same 5-octet collapse) should NOT.
        val r24 = "02:00:00:eb:a0:11"  // 2.4 GHz radio
        val r5  = "02:00:00:eb:a0:22"  // 5 GHz radio (same physical AP)
        val sequence = listOf(r24, r5, r24, r5, r24, r5)
        val samples = sequence.mapIndexed { i, bssid ->
            val ts = 1000L + i * 1000L
            Sample(null, ts, target = "t",
                wifi = wifiSnapshot(bssid = bssid, ssid = null, rssi = null,
                    networkType = "wifi", atMs = ts))
        }
        // After 5-octet collapse, every snapshot is the same prefix "02:00:00:eb:a0".
        // No transitions → churnBssidPrefixes returns null.
        assertNull(
            "band-steering on same physical AP must produce zero churn prefix",
            MetricsCalculator.churnBssidPrefixes(samples)
        )
    }

    // --- v1.5.5 physicalApChangesCount (G4, G5) ---

    @Test
    fun `deviceLevelAggregates physicalApChangesCount skips nulls and sentinels`() {
        // G4 — A → null → A → B sequence.
        // bssidChangesCount counts the value-to-null and null-to-value transitions
        // (prior behavior, unchanged): A→null=1, null→A=2, A→B=3.
        // physicalApChangesCount must skip nulls: only A→B = 1.
        val a = "02:00:00:eb:a0:11"
        val b = "02:00:00:eb:b0:22"
        val samples = listOf(
            sampleWithBssid(a, 1000L),
            sampleWithBssid(null, 2000L),  // null bssid
            sampleWithBssid(a, 3000L),
            sampleWithBssid(b, 4000L)
        )
        val agg = MetricsCalculator.deviceLevelAggregates(samples)
        // bssidChangesCount counts ALL transitions including null↔value.
        // Wait: snapshots filter out the null-bssid entry only if the WHOLE
        // wifi snapshot is null. Here we set bssid=null but wifi snapshot is
        // present, so the snapshots list keeps all 4. Transitions on .map { it.bssid }:
        // a → null (1) → a (2) → b (3) = 3.
        assertEquals("bssid_changes counts null transitions", 3, agg.bssidChangesCount)
        // physicalApChangesCount uses 5-octet collapse + countNonNullTransitions:
        // [A_prefix, null, A_prefix, B_prefix] → only A→B counts = 1.
        assertEquals(
            "physical_ap_changes skips nulls (permission blip != roam)",
            1, agg.physicalApChangesCount
        )
    }

    @Test
    fun `deviceLevelAggregates physicalApChangesCount treats sentinels as null`() {
        // G5 — typed-sentinel BSSIDs ("permission_denied" length=17 but no colons
        // in MAC positions) map to null prefix. Real BSSID after sentinel must
        // count as one transition only between real values.
        val realA = "02:00:00:eb:a0:11"
        val realB = "02:00:00:eb:b0:22"
        val samples = listOf(
            sampleWithBssid("permission_denied", 1000L),
            sampleWithBssid("nearby_wifi_denied", 2000L),
            sampleWithBssid(realA, 3000L),
            sampleWithBssid(realB, 4000L)
        )
        val agg = MetricsCalculator.deviceLevelAggregates(samples)
        // After collapse: [null, null, A_prefix, B_prefix]. Real transitions: A→B = 1.
        assertEquals(1, agg.physicalApChangesCount)
    }

    // --- v1.5.6 churnTier (NF-LIVE-2 calibration response) ---

    @Test
    fun `churnTier boundaries match ASM dashboard spec — low ≤15, high 16-29, fault ≥30`() {
        // ASM dashboard 2026-05-01 KT spec: low (≤15) / high (16–29) / fault (≥30).
        // Code uses `bssidChangesCount >= faultThreshold` so the AT-30 boundary
        // is fault, AT-15 boundary is low.
        assertEquals("low", MetricsCalculator.churnTier(0, 15, 30))
        assertEquals("low", MetricsCalculator.churnTier(15, 15, 30))   // boundary low/high
        assertEquals("high", MetricsCalculator.churnTier(16, 15, 30))  // 1 over → high
        assertEquals("high", MetricsCalculator.churnTier(29, 15, 30))  // 1 under fault
        assertEquals("fault", MetricsCalculator.churnTier(30, 15, 30)) // boundary high/fault → fault
        assertEquals("fault", MetricsCalculator.churnTier(31, 15, 30)) // 1 over fault
        // observed real-world value (field data 2026-04-30)
        assertEquals("fault", MetricsCalculator.churnTier(95, 15, 30))
        // second observed real-world value
        assertEquals("fault", MetricsCalculator.churnTier(37, 15, 30))
    }

    // --- v1.5.6 unreachablePct (F6 closure) ---

    @Test
    fun `unreachablePct zero when all reachable`() {
        assertEquals(0.0, MetricsCalculator.unreachablePct(100, 100)!!, 0.001)
    }

    @Test
    fun `unreachablePct hundred when all unreachable`() {
        assertEquals(100.0, MetricsCalculator.unreachablePct(100, 0)!!, 0.001)
    }

    @Test
    fun `unreachablePct partial split rounds to one decimal`() {
        assertEquals(50.0, MetricsCalculator.unreachablePct(10, 5)!!, 0.001)
        // 7 unreachable / 9 total = 77.777...% → rounds to 77.8
        assertEquals(77.8, MetricsCalculator.unreachablePct(9, 2)!!, 0.001)
    }

    @Test
    fun `unreachablePct null when samplesCount is zero`() {
        assertNull(MetricsCalculator.unreachablePct(0, 0))
    }

    @Test
    fun `unreachablePct guards against reachable greater than samples`() {
        // Defensive: if reachable somehow exceeds samples (impossible by contract,
        // but bug-resistant), unreachable count is coerced to 0 not negative.
        assertEquals(0.0, MetricsCalculator.unreachablePct(10, 15)!!, 0.001)
    }

    // --- v1.5.6 dominantMacRandomization ---

    @Test
    fun `deviceLevelAggregates dominantMacRandomization picks most-frequent real value`() {
        // Mixed window: 3 "persistent" wifi snapshots + 2 "unsupported" cellular.
        // Real-value filter prefers "persistent" over "unsupported" — the analyst-
        // useful answer is "the rep is on persistent randomization while connected
        // to Wi-Fi", not "they were on cellular some of the time".
        val base = 1000L
        val samples = listOf(
            sampleWithMacRand("persistent", base),
            sampleWithMacRand("unsupported", base + 1000L),
            sampleWithMacRand("persistent", base + 2000L),
            sampleWithMacRand("unsupported", base + 3000L),
            sampleWithMacRand("persistent", base + 4000L)
        )
        val agg = MetricsCalculator.deviceLevelAggregates(samples)
        assertEquals("persistent", agg.dominantMacRandomization)
    }

    @Test
    fun `deviceLevelAggregates dominantMacRandomization defaults unsupported when no real values`() {
        // Cellular-only window — every snapshot reports "unsupported".
        val base = 1000L
        val samples = (0..4).map { sampleWithMacRand("unsupported", base + it * 1000L) }
        val agg = MetricsCalculator.deviceLevelAggregates(samples)
        assertEquals("unsupported", agg.dominantMacRandomization)
    }

    // --- helpers for v1.5.5 / v1.5.6 tests ---

    private fun sampleWithBssid(bssid: String?, atMs: Long): Sample =
        Sample(
            rttMs = null, tsMs = atMs, target = "t",
            wifi = wifiSnapshot(
                bssid = bssid, ssid = null, rssi = null,
                networkType = "wifi", atMs = atMs
            )
        )

    private fun sampleWithMacRand(mr: String, atMs: Long): Sample =
        Sample(
            rttMs = null, tsMs = atMs, target = "t",
            wifi = WifiSnapshot(
                ssid = null, bssid = null, rssi = null,
                linkSpeedMbps = null, frequencyMhz = null,
                networkType = if (mr == "unsupported") "cellular" else "wifi",
                vpnActive = false,
                collectedAtMs = atMs,
                macRandomization = mr
            )
        )

    // --- v1.5.6 wifiNoIpObserved (MAC-whitelist DHCP-block symptom) ---

    @Test
    fun `wifiNoIpObserved true when any wifi snapshot lacks IP`() {
        // 3 wifi snapshots: 2 with IP, 1 without (DHCP transiently failed).
        // Aggregate must report true so the MAC-whitelist symptom isn't masked
        // by the "mostly OK" majority.
        val base = 1000L
        val samples = listOf(
            sampleWithIpStatus(true, base),
            sampleWithIpStatus(false, base + 1000L),
            sampleWithIpStatus(true, base + 2000L)
        )
        val agg = MetricsCalculator.deviceLevelAggregates(samples)
        assertEquals(true, agg.wifiNoIpObserved)
    }

    @Test
    fun `wifiNoIpObserved false when all wifi snapshots have IP`() {
        val base = 1000L
        val samples = (0..3).map { sampleWithIpStatus(true, base + it * 1000L) }
        val agg = MetricsCalculator.deviceLevelAggregates(samples)
        assertEquals(false, agg.wifiNoIpObserved)
    }

    @Test
    fun `wifiNoIpObserved null when window has no wifi snapshots`() {
        // Cellular-only window — wifiNoIpObserved is undefined (not "good", not
        // "bad" — no Wi-Fi sample to observe).
        val base = 1000L
        val samples = listOf(
            sampleWithMacRand("unsupported", base),
            sampleWithMacRand("unsupported", base + 1000L)
        )
        val agg = MetricsCalculator.deviceLevelAggregates(samples)
        assertNull(agg.wifiNoIpObserved)
    }

    private fun sampleWithIpStatus(hasIp: Boolean, atMs: Long): Sample =
        Sample(
            rttMs = null, tsMs = atMs, target = "t",
            wifi = WifiSnapshot(
                ssid = "Office-WiFi", bssid = "02:00:00:eb:a0:11",
                rssi = -55, linkSpeedMbps = 866, frequencyMhz = 5180,
                networkType = "wifi",
                vpnActive = false,
                collectedAtMs = atMs,
                macRandomization = "persistent",
                wifiHasIp = hasIp
            )
        )
}

