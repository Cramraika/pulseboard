package com.pulseboard.core

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.InterruptedIOException
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit

/**
 * One HTTPS-download measurement — the v1.3 throughput signal.
 *
 * Populated once per 15-min flush window, attached to the row for the primary
 * ping target. The other four target rows in the same flush
 * leave these fields null.
 *
 * Why a single window-wide measurement instead of per-target:
 *  - Throughput is a property of the transport path, not of any specific
 *    probe target.
 *  - Running five parallel downloads would burn ~2.5 MB/window (125 MB/day
 *    per phone) — 5× what's needed for the same signal.
 */
data class ThroughputProbe(
    val url: String,
    val bytesReceived: Long,
    val durationMs: Long,
    val effectiveMbps: Double?,       // null if request failed
    val error: String?,               // short class name, null on success
    val atMs: Long
)

/**
 * HTTPS-throughput probe. Stateless; reuse the same instance across flushes
 * so OkHttp's connection pool amortises TLS setup across probes (the first
 * probe of a service lifetime eats ~200 ms of cold-connect cost, but every
 * probe thereafter is warm).
 *
 * v1.3 contract:
 *  - Single attempt, no retries — next flush window (15 min later) is
 *    effectively the retry.
 *  - [runProbe] is blocking by design; callers must invoke from a coroutine
 *    dispatcher or worker thread.
 *  - Connect timeout 10 s, read timeout 30 s. A 500 KB download on a healthy
 *    5-10 Mbps link finishes in well under 1 s; timeouts here mark genuine
 *    transport failures.
 */
class ThroughputProber(
    connectTimeoutSec: Long = DEFAULT_CONNECT_SEC,
    readTimeoutSec: Long = DEFAULT_READ_SEC,
    private val userAgent: String = DEFAULT_USER_AGENT
) {

    private val tag = "PingCore.Throughput"

    // Shared base client via HttpClients — same connection pool + dispatcher
    // + TLS cache as SheetsUploader, with probe-specific timeouts + redirect
    // policy applied on top.
    private val client: OkHttpClient = HttpClients.newBuilder()
        .connectTimeout(connectTimeoutSec, TimeUnit.SECONDS)
        .readTimeout(readTimeoutSec, TimeUnit.SECONDS)
        // Redirects would add uncontrolled RTT to the duration measurement
        // (the whole point of the probe is to measure a single transfer).
        // Treat any 3xx as an error so the failure is visible in the Sheet.
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    /**
     * Executes one HEAD-less GET of [url], reading the response body to
     * completion. Returns a [ThroughputProbe] with populated duration and
     * byte-count fields on success, or with a non-null [ThroughputProbe.error]
     * on any failure (connection reset, TLS, timeout, HTTP non-2xx, etc.).
     */
    fun runProbe(url: String): ThroughputProbe {
        val atMs = System.currentTimeMillis()
        val startNanos = System.nanoTime()
        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", userAgent)
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L
                    Log.w(tag, "probe $url returned HTTP ${response.code}")
                    return ThroughputProbe(
                        url = url,
                        bytesReceived = 0L,
                        durationMs = elapsedMs,
                        effectiveMbps = null,
                        error = "http_${response.code}",
                        atMs = atMs
                    )
                }
                // Drain the body. OkHttp's source.readAll() hands us the
                // byte count without buffering everything to RAM.
                val body = response.body
                    ?: return ThroughputProbe(
                        url = url,
                        bytesReceived = 0L,
                        durationMs = (System.nanoTime() - startNanos) / 1_000_000L,
                        effectiveMbps = null,
                        error = "null_body",
                        atMs = atMs
                    )
                var received = 0L
                val buffer = ByteArray(8 * 1024)
                body.source().use { source ->
                    while (true) {
                        val n = source.read(buffer)
                        if (n == -1) break
                        received += n
                    }
                }
                val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L
                val mbps = computeMbps(received, elapsedMs)
                Log.i(tag, "probe $url ok — ${received}B in ${elapsedMs}ms = ${"%.2f".format(mbps ?: 0.0)} Mbps")
                ThroughputProbe(
                    url = url,
                    bytesReceived = received,
                    durationMs = elapsedMs,
                    effectiveMbps = mbps,
                    error = null,
                    atMs = atMs
                )
            }
        } catch (e: Exception) {
            // Honour structured concurrency: a cancelled coroutine
            // mid-probe (service teardown) must bubble the cancellation,
            // not fabricate a synthetic probe row labelled "CancellationException"
            // or "InterruptedIOException". Both extend Exception in the
            // standard library (CancellationException → RuntimeException;
            // InterruptedIOException fires when an OkHttp blocking call is
            // interrupted via Thread.interrupt()).
            if (e is CancellationException ||
                e is InterruptedException ||
                (e is InterruptedIOException && Thread.currentThread().isInterrupted)) {
                throw e
            }
            val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L
            val klass = e.javaClass.simpleName.ifBlank { "Exception" }
            Log.w(tag, "probe $url threw — ${klass}: ${e.message}")
            ThroughputProbe(
                url = url,
                bytesReceived = 0L,
                durationMs = elapsedMs,
                effectiveMbps = null,
                error = klass,
                atMs = atMs
            )
        }
    }

    companion object {
        const val DEFAULT_CONNECT_SEC = 10L
        const val DEFAULT_READ_SEC = 30L
        const val DEFAULT_USER_AGENT = "Pulseboard/1.5.7 (network diagnostic)"

        /**
         * Pure helper: effective Mbps from bytes + milliseconds. Returns null
         * when either input is non-positive (avoids divide-by-zero / negative
         * durations that could come from a system-clock skip).
         */
        fun computeMbps(bytes: Long, durationMs: Long): Double? {
            if (bytes <= 0L || durationMs <= 0L) return null
            // bits / seconds / 1e6 = Mbps
            return (bytes.toDouble() * 8.0) / (durationMs.toDouble() / 1000.0) / 1_000_000.0
        }

        /**
         * Pure helper: the ms-wait until the next "pre-flush" slot given a
         * wall-clock [nowMs], a flush interval [quarterMs], and a pre-flush
         * offset [offsetMs] (how far before each boundary the probe should fire).
         *
         * Always returns the wait to the NEXT still-in-the-future pre-flush
         * slot. Handles three edge cases correctly:
         *   - `nowMs` IS a flush boundary → wait targets the NEXT boundary
         *     (don't probe 30 s before a boundary we're standing on).
         *   - `nowMs` is inside the current pre-flush window (boundary - offset
         *     < now < boundary) → advance to the following window's pre-flush.
         *   - `nowMs` is pre-first-boundary → straightforward positive wait.
         *
         * Returned value is always >= 0.
         */
        fun nextPreFlushWaitMs(nowMs: Long, quarterMs: Long, offsetMs: Long): Long {
            require(quarterMs > 0L) { "quarterMs must be positive" }
            require(offsetMs in 0L until quarterMs) { "offsetMs must be in [0, quarterMs)" }
            val msIntoQuarter = nowMs % quarterMs
            // When msIntoQuarter == 0 the expression still evaluates to
            // `nowMs + quarterMs`, i.e. the NEXT boundary — so no special case.
            val nextBoundary = nowMs + (quarterMs - msIntoQuarter)
            var probeAt = nextBoundary - offsetMs
            // If the pre-flush slot is already past (we're inside the
            // `boundary - offset .. boundary` window, or at the slot exactly),
            // advance one quarter so we target the NEXT pre-flush slot.
            if (probeAt <= nowMs) probeAt += quarterMs
            return (probeAt - nowMs).coerceAtLeast(0L)
        }

        /**
         * Pure helper: is a probe with [probeAtMs] stale relative to the
         * flush starting at [flushStartMs] with window length [quarterMs]?
         * A probe belongs to flush N if it fired during flush N's window
         * (flushStart - quarter to flushStart). Anything older is stale and
         * should be dropped to avoid wrong-window attribution.
         */
        fun isStaleProbe(probeAtMs: Long, flushStartMs: Long, quarterMs: Long): Boolean =
            probeAtMs < flushStartMs - quarterMs
    }
}
