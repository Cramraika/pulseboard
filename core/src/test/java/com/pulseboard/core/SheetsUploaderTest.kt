package com.pulseboard.core

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SheetsUploaderTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    /**
     * Test factory: every SheetsUploader used in this file injects a no-op
     * sleeper by default, so the retry loop never triggers the default
     * `Thread.sleep(2s/8s/32s)` backoff ladder when a test makes a mistake
     * (e.g. enqueues the wrong MockResponse shape). Saves ~42 s per flaky
     * red-build.
     */
    private fun uploader(url: String): SheetsUploader =
        SheetsUploader(url, sleeper = { /* no-op */ })

    private fun fullPayload() = SheetPayload(
        windowStart = "2026-04-16T12:00:00Z",
        userId = "user@example.com",
        deviceModel = "Samsung SM-G998B",
        appVersion = "1.1",
        target = "primary",
        avgRttMs = 42.3,
        minRttMs = 18.1,
        maxRttMs = 812.0,
        p50RttMs = 38.0,
        p95RttMs = 74.5,
        p99RttMs = 340.0,
        jitterMs = 28.7,
        packetLossPct = 3.3,
        samplesCount = 900,
        reachableSamplesCount = 890,
        maxRttOffsetSec = 342,
        networkTypeDominant = "wifi"
    )

    private fun nullPayload() = fullPayload().copy(
        avgRttMs = null, minRttMs = null, maxRttMs = null,
        p50RttMs = null, p95RttMs = null, p99RttMs = null,
        jitterMs = null, maxRttOffsetSec = null,
        packetLossPct = 100.0
    )

    @Test
    fun `200 with status ok returns true`() {
        server.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody("""{"status":"ok"}""")
            .setHeader("Content-Type", "application/json"))
        val uploader = uploader(server.url("/exec").toString())
        assertTrue(uploader.upload(fullPayload()))
    }

    @Test
    fun `200 with status error returns false`() {
        server.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody("""{"status":"error","message":"whatever"}""")
            .setHeader("Content-Type", "application/json"))
        val uploader = uploader(server.url("/exec").toString())
        assertFalse(uploader.upload(fullPayload()))
    }

    @Test
    fun `200 with HTML body returns false`() {
        server.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody("<html><body>Error occurred</body></html>")
            .setHeader("Content-Type", "text/html"))
        val uploader = uploader(server.url("/exec").toString())
        assertFalse(uploader.upload(fullPayload()))
    }

    @Test
    fun `500 returns false even with status ok body`() {
        server.enqueue(MockResponse()
            .setResponseCode(500)
            .setBody("""{"status":"ok"}"""))
        val uploader = uploader(server.url("/exec").toString())
        assertFalse(uploader.upload(fullPayload()))
    }

    @Test
    fun `connection failure returns false`() {
        server.shutdown()   // close before call
        val uploader = uploader(server.url("/exec").toString())
        assertFalse(uploader.upload(fullPayload()))
    }

    @Test
    fun `payload serializes v1_1 fields with correct JSON keys`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"status":"ok"}"""))
        val uploader = uploader(server.url("/exec").toString())
        uploader.upload(fullPayload())
        val body = server.takeRequest().body.readUtf8()
        val json = JSONObject(body)

        assertEquals("2026-04-16T12:00:00Z", json.getString("window_start"))
        assertEquals("user@example.com", json.getString("user_id"))
        assertEquals("Samsung SM-G998B", json.getString("device_model"))
        assertEquals("primary", json.getString("target"))
        assertEquals("wifi", json.getString("network_type_dominant"))
        assertEquals(42.3, json.getDouble("avg_rtt_ms"), 0.001)
        assertEquals(18.1, json.getDouble("min_rtt_ms"), 0.001)
        assertEquals(812.0, json.getDouble("max_rtt_ms"), 0.001)
        assertEquals(38.0, json.getDouble("p50_rtt_ms"), 0.001)
        assertEquals(74.5, json.getDouble("p95_rtt_ms"), 0.001)
        assertEquals(340.0, json.getDouble("p99_rtt_ms"), 0.001)
        assertEquals(28.7, json.getDouble("jitter_ms"), 0.001)
        assertEquals(3.3, json.getDouble("packet_loss_pct"), 0.001)
        assertEquals(900, json.getInt("samples_count"))
        assertEquals(890, json.getInt("reachable_samples_count"))
        assertEquals(342, json.getInt("max_rtt_offset_sec"))
        assertEquals("1.1", json.getString("app_version"))
    }

    @Test
    fun `null RTT fields are serialized as JSON null`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"status":"ok"}"""))
        val uploader = uploader(server.url("/exec").toString())
        uploader.upload(nullPayload())
        val body = server.takeRequest().body.readUtf8()
        val json = JSONObject(body)

        assertTrue("avg should be null", json.isNull("avg_rtt_ms"))
        assertTrue("min should be null", json.isNull("min_rtt_ms"))
        assertTrue("max should be null", json.isNull("max_rtt_ms"))
        assertTrue("p50 should be null", json.isNull("p50_rtt_ms"))
        assertTrue("p95 should be null", json.isNull("p95_rtt_ms"))
        assertTrue("p99 should be null", json.isNull("p99_rtt_ms"))
        assertTrue("jitter should be null", json.isNull("jitter_ms"))
        assertTrue("max_rtt_offset_sec should be null", json.isNull("max_rtt_offset_sec"))
        assertEquals(100.0, json.getDouble("packet_loss_pct"), 0.001)
        assertEquals(900, json.getInt("samples_count"))
    }

    // --- v1.1 uploadBatch ---

    @Test
    fun `uploadBatch returns true on 200 plus status ok`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"status":"ok","rows_appended":3}"""))
        val uploader = uploader(server.url("/exec").toString())
        assertTrue(uploader.uploadBatch(listOf(fullPayload(), fullPayload(), fullPayload())))
    }

    @Test
    fun `uploadBatch returns false on 500 even with status ok body`() {
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"status":"ok"}"""))
        val uploader = uploader(server.url("/exec").toString())
        assertFalse(uploader.uploadBatch(listOf(fullPayload())))
    }

    @Test
    fun `uploadBatch returns false on 200 plus status error`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"status":"error","reason":"bad rows"}"""))
        val uploader = uploader(server.url("/exec").toString())
        assertFalse(uploader.uploadBatch(listOf(fullPayload(), fullPayload())))
    }

    @Test
    fun `uploadBatch returns false on connection failure`() {
        server.shutdown()
        val uploader = uploader(server.url("/exec").toString())
        assertFalse(uploader.uploadBatch(listOf(fullPayload())))
    }

    @Test
    fun `uploadBatch serializes payload as a JSON array with one element per row`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"status":"ok"}"""))
        val uploader = uploader(server.url("/exec").toString())
        val payloads = listOf(
            fullPayload().copy(avgRttMs = 10.0),
            fullPayload().copy(avgRttMs = 20.0),
            fullPayload().copy(avgRttMs = 30.0)
        )
        uploader.uploadBatch(payloads)
        val body = server.takeRequest().body.readUtf8()
        val array = org.json.JSONArray(body)
        assertEquals(3, array.length())
        assertEquals(10.0, array.getJSONObject(0).getDouble("avg_rtt_ms"), 0.001)
        assertEquals(20.0, array.getJSONObject(1).getDouble("avg_rtt_ms"), 0.001)
        assertEquals(30.0, array.getJSONObject(2).getDouble("avg_rtt_ms"), 0.001)
    }

    // --- v1.2 retry-with-backoff ---

    private fun noopSleeper(): (Long) -> Unit = { /* no wait in tests */ }

    @Test
    fun `upload succeeds on first attempt without sleeping`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"status":"ok"}"""))
        val sleeps = mutableListOf<Long>()
        val uploader = SheetsUploader(
            server.url("/exec").toString(),
            sleeper = { sleeps += it }
        )
        assertTrue(uploader.upload(fullPayload()))
        assertEquals(1, server.requestCount)
        assertEquals("no sleeps on first-attempt success", 0, sleeps.size)
    }

    @Test
    fun `upload retries after 500 and succeeds on second attempt`() {
        server.enqueue(MockResponse().setResponseCode(500).setBody("nope"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"status":"ok"}"""))
        val sleeps = mutableListOf<Long>()
        val uploader = SheetsUploader(
            server.url("/exec").toString(),
            sleeper = { sleeps += it }
        )
        assertTrue(uploader.upload(fullPayload()))
        assertEquals(2, server.requestCount)
        assertEquals(listOf(2_000L), sleeps)
    }

    @Test
    fun `upload retries up to max attempts and then gives up`() {
        // 3 consecutive failures — default attempts = 3 → returns false.
        repeat(3) {
            server.enqueue(MockResponse().setResponseCode(500).setBody("down"))
        }
        val sleeps = mutableListOf<Long>()
        val uploader = SheetsUploader(
            server.url("/exec").toString(),
            sleeper = { sleeps += it }
        )
        assertFalse(uploader.upload(fullPayload()))
        assertEquals(3, server.requestCount)
        // Two backoffs between 3 attempts: 2s, 8s. Last failure does not sleep.
        assertEquals(listOf(2_000L, 8_000L), sleeps)
    }

    @Test
    fun `uploadBatch retries on status error and succeeds on final attempt`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"status":"error","reason":"quota"}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"status":"error","reason":"quota"}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"status":"ok","rows_appended":4}"""))
        val sleeps = mutableListOf<Long>()
        val uploader = SheetsUploader(
            server.url("/exec").toString(),
            sleeper = { sleeps += it }
        )
        assertTrue(uploader.uploadBatch(listOf(fullPayload(), fullPayload(), fullPayload(), fullPayload())))
        assertEquals(3, server.requestCount)
        assertEquals(listOf(2_000L, 8_000L), sleeps)
    }

    @Test
    fun `payload serializes v1_2 new fields with correct JSON keys`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"status":"ok"}"""))
        val uploader = SheetsUploader(server.url("/exec").toString(), sleeper = noopSleeper())
        val row = fullPayload().copy(
            rowType = "sample",
            samplerWallGapSec = 47,
            permissionState = "loc=on;fine=1;nearby=1;bgloc=1;notif=1",
            gatewayResolverSource = "cached"
        )
        uploader.upload(row)
        val body = server.takeRequest().body.readUtf8()
        val json = JSONObject(body)
        assertEquals("sample", json.getString("row_type"))
        assertEquals(47, json.getInt("sampler_wall_gap_sec"))
        assertEquals("loc=on;fine=1;nearby=1;bgloc=1;notif=1", json.getString("permission_state"))
        assertEquals("cached", json.getString("gateway_resolver_source"))
    }

    // --- v1.3 throughput fields ---

    @Test
    fun `payload serializes v1_3 throughput fields with correct JSON keys on success`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"status":"ok"}"""))
        val uploader = SheetsUploader(server.url("/exec").toString(), sleeper = noopSleeper())
        val row = fullPayload().copy(
            throughputBytes = 500_000L,
            throughputDurationMs = 120L,
            throughputMbps = 33.3,
            throughputErr = null
        )
        uploader.upload(row)
        val body = server.takeRequest().body.readUtf8()
        val json = JSONObject(body)
        assertEquals(500_000L, json.getLong("throughput_bytes"))
        assertEquals(120L, json.getLong("throughput_duration_ms"))
        assertEquals(33.3, json.getDouble("throughput_mbps"), 0.001)
        assertTrue("null error should serialize as JSON null", json.isNull("throughput_err"))
    }

    @Test
    fun `payload serializes v1_3 skipped-cellular marker`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"status":"ok"}"""))
        val uploader = SheetsUploader(server.url("/exec").toString(), sleeper = noopSleeper())
        val row = fullPayload().copy(
            throughputBytes = 0L,
            throughputDurationMs = 0L,
            throughputMbps = null,
            throughputErr = "skipped_cellular"
        )
        uploader.upload(row)
        val body = server.takeRequest().body.readUtf8()
        val json = JSONObject(body)
        assertEquals(0L, json.getLong("throughput_bytes"))
        assertTrue("mbps null on cellular skip", json.isNull("throughput_mbps"))
        assertEquals("skipped_cellular", json.getString("throughput_err"))
    }

    @Test
    fun `payload with no throughput emits JSON null for all throughput keys`() {
        // Non-primary rows in a v1.3 batch omit throughput. Gson must
        // serialize the null fields (serializeNulls is enabled in the
        // uploader) so the Apps Script sees explicit nulls, not missing keys.
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"status":"ok"}"""))
        val uploader = SheetsUploader(server.url("/exec").toString(), sleeper = noopSleeper())
        uploader.upload(fullPayload())   // no throughput fields set
        val body = server.takeRequest().body.readUtf8()
        val json = JSONObject(body)
        assertTrue(json.isNull("throughput_bytes"))
        assertTrue(json.isNull("throughput_duration_ms"))
        assertTrue(json.isNull("throughput_mbps"))
        assertTrue(json.isNull("throughput_err"))
    }

    // --- v1.5 ASM-shape acceptance + X-API-Key wiring ---

    @Test
    fun `200 with ASM-shape ok true returns true`() {
        // ASM Fastify route returns {"status":"ok","rows_appended":N,"rows_duplicated":M}.
        // Parser should still accept this — it matches the status=ok branch.
        server.enqueue(MockResponse().setResponseCode(200)
            .setBody("""{"status":"ok","rows_appended":1,"rows_duplicated":0}"""))
        val uploader = SheetsUploader(server.url("/flush").toString(), sleeper = noopSleeper(), apiKey = "kX")
        assertTrue(uploader.upload(fullPayload()))
    }

    @Test
    fun `200 with alternate ok-true shape returns true`() {
        // Defensive: if ASM ever returns the Fastify-default {"ok":true}
        // shape (no "status" key), the OR branch in the parser should still
        // register it as success.
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true}"""))
        val uploader = SheetsUploader(server.url("/flush").toString(), sleeper = noopSleeper())
        assertTrue(uploader.upload(fullPayload()))
    }

    @Test
    fun `200 with ok false returns false`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":false}"""))
        val uploader = SheetsUploader(server.url("/flush").toString(), sleeper = noopSleeper())
        assertFalse(uploader.upload(fullPayload()))
    }

    @Test
    fun `X-API-Key header is attached on every retry not just first`() {
        // First attempt: 500 → retry. Second: 200. Confirm header present on both.
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"status":"error"}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"status":"ok"}"""))
        val uploader = SheetsUploader(server.url("/flush").toString(), sleeper = noopSleeper(), apiKey = "secret-abc")
        assertTrue(uploader.upload(fullPayload()))
        val first = server.takeRequest()
        val second = server.takeRequest()
        assertEquals("secret-abc", first.getHeader("X-API-Key"))
        assertEquals("secret-abc", second.getHeader("X-API-Key"))
    }

    @Test
    fun `no apiKey means no X-API-Key header attached`() {
        // Apps Script historical path: uploader constructed without apiKey.
        // Preserves backwards-compatibility; no phantom header attached.
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"status":"ok"}"""))
        val uploader = SheetsUploader(server.url("/exec").toString(), sleeper = noopSleeper())
        assertTrue(uploader.upload(fullPayload()))
        assertEquals(null, server.takeRequest().getHeader("X-API-Key"))
    }

    @Test
    fun `whitespace-only apiKey is treated as missing`() {
        // Guards against a build-property misconfiguration producing a blank
        // key. If a whitespace key were sent, ASM would 401 every request and
        // the retain-on-failure buffer would grow unbounded. Treat as absent.
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"status":"ok"}"""))
        val uploader = SheetsUploader(server.url("/flush").toString(), sleeper = noopSleeper(), apiKey = "   ")
        assertTrue(uploader.upload(fullPayload()))
        assertEquals(null, server.takeRequest().getHeader("X-API-Key"))
    }

    @Test
    fun `apiKey gets trimmed before header attachment`() {
        // Defensive: if a config edit ends with a trailing newline (e.g.
        // "secret\n" from a clipboard copy), trim it so the auth header
        // value matches the server's expected key.
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"status":"ok"}"""))
        val uploader = SheetsUploader(server.url("/flush").toString(), sleeper = noopSleeper(), apiKey = "  k123\n")
        assertTrue(uploader.upload(fullPayload()))
        assertEquals("k123", server.takeRequest().getHeader("X-API-Key"))
    }
}
