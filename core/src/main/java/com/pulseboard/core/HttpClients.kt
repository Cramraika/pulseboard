package com.pulseboard.core

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Shared OkHttp client factory for the whole app.
 *
 * Every consumer that wants HTTP gets a client derived from [base] via
 * [newBuilder]. This lets us:
 *   - Share a single connection pool + dispatcher + thread pool across
 *     [SheetsUploader] and [ThroughputProber] (~200 KB memory saved per
 *     extra client that would otherwise be standalone).
 *   - Share a single TLS session cache — the first HTTPS request pays the
 *     cold-handshake cost; every subsequent request on the same host
 *     (the upload endpoint, speed.cloudflare.com) skips it.
 *   - Keep per-consumer timeout overrides trivial (just call
 *     `.connectTimeout(...)` on the builder).
 *
 * The [base] client itself has no request-side timeouts configured so
 * consumers can't accidentally inherit a timeout that was set for
 * someone else's use case — each consumer MUST set its own.
 */
object HttpClients {

    /**
     * Base shared client. Holds the ConnectionPool + Dispatcher + TLS
     * cache. Has a generous connect/read/write timeout floor of
     * [MAX_SAFETY_TIMEOUT_SEC] seconds — any consumer that wants stricter
     * timeouts should call `.connectTimeout(...)` / `.readTimeout(...)` on
     * the builder they derive.
     */
    val base: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(MAX_SAFETY_TIMEOUT_SEC, TimeUnit.SECONDS)
        .readTimeout(MAX_SAFETY_TIMEOUT_SEC, TimeUnit.SECONDS)
        .writeTimeout(MAX_SAFETY_TIMEOUT_SEC, TimeUnit.SECONDS)
        .build()

    /**
     * Returns a new builder pre-seeded with the base client's connection
     * pool and dispatcher. Consumers apply their own timeouts and
     * redirect policies on top.
     */
    fun newBuilder(): OkHttpClient.Builder = base.newBuilder()

    /**
     * Safety-net timeout. Much longer than any legitimate consumer needs;
     * ensures a pathological request can't hang forever even if the
     * consumer forgot to set its own timeout.
     */
    private const val MAX_SAFETY_TIMEOUT_SEC = 120L
}
