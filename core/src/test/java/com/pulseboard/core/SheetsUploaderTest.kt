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

    private fun fullPayload() = SheetPayload(
        windowStart = "2026-04-16T12:00:00Z",
        userId = "chinmay.ramraika@codingninjas.com",
        deviceModel = "Samsung SM-G998B",
        appVersion = "1.1",
        target = "smartflo",
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
        val uploader = SheetsUploader(server.url("/exec").toString())
        assertTrue(uploader.upload(fullPayload()))
    }

    @Test
    fun `200 with status error returns false`() {
        server.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody("""{"status":"error","message":"whatever"}""")
            .setHeader("Content-Type", "application/json"))
        val uploader = SheetsUploader(server.url("/exec").toString())
        assertFalse(uploader.upload(fullPayload()))
    }

    @Test
    fun `200 with HTML body returns false`() {
        server.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody("<html><body>Error occurred</body></html>")
            .setHeader("Content-Type", "text/html"))
        val uploader = SheetsUploader(server.url("/exec").toString())
        assertFalse(uploader.upload(fullPayload()))
    }

    @Test
    fun `500 returns false even with status ok body`() {
        server.enqueue(MockResponse()
            .setResponseCode(500)
            .setBody("""{"status":"ok"}"""))
        val uploader = SheetsUploader(server.url("/exec").toString())
        assertFalse(uploader.upload(fullPayload()))
    }

    @Test
    fun `connection failure returns false`() {
        server.shutdown()   // close before call
        val uploader = SheetsUploader(server.url("/exec").toString())
        assertFalse(uploader.upload(fullPayload()))
    }

    @Test
    fun `payload serializes v1_1 fields with correct JSON keys`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"status":"ok"}"""))
        val uploader = SheetsUploader(server.url("/exec").toString())
        uploader.upload(fullPayload())
        val body = server.takeRequest().body.readUtf8()
        val json = JSONObject(body)

        assertEquals("2026-04-16T12:00:00Z", json.getString("window_start"))
        assertEquals("chinmay.ramraika@codingninjas.com", json.getString("user_id"))
        assertEquals("Samsung SM-G998B", json.getString("device_model"))
        assertEquals("smartflo", json.getString("target"))
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
        val uploader = SheetsUploader(server.url("/exec").toString())
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
        val uploader = SheetsUploader(server.url("/exec").toString())
        assertTrue(uploader.uploadBatch(listOf(fullPayload(), fullPayload(), fullPayload())))
    }

    @Test
    fun `uploadBatch returns false on 500 even with status ok body`() {
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"status":"ok"}"""))
        val uploader = SheetsUploader(server.url("/exec").toString())
        assertFalse(uploader.uploadBatch(listOf(fullPayload())))
    }

    @Test
    fun `uploadBatch returns false on 200 plus status error`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"status":"error","reason":"bad rows"}"""))
        val uploader = SheetsUploader(server.url("/exec").toString())
        assertFalse(uploader.uploadBatch(listOf(fullPayload(), fullPayload())))
    }

    @Test
    fun `uploadBatch returns false on connection failure`() {
        server.shutdown()
        val uploader = SheetsUploader(server.url("/exec").toString())
        assertFalse(uploader.uploadBatch(listOf(fullPayload())))
    }

    @Test
    fun `uploadBatch serializes payload as a JSON array with one element per row`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"status":"ok"}"""))
        val uploader = SheetsUploader(server.url("/exec").toString())
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
}
