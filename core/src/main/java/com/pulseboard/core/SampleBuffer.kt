package com.pulseboard.core

/**
 * One ping attempt's outcome plus the network context it was observed in.
 *
 * The three v1.1 fields (`target`, `unreachable`, `wifi`) are defaulted so that
 * v1.0-style `Sample(rttMs, tsMs)` construction still compiles and runs unchanged.
 *
 * `unreachable=true` signals that no target address was resolvable (e.g. gateway
 * isn't reachable on CGN mobile data). Aggregator excludes these from the loss
 * denominator — they represent "we had nothing to ping," not "we pinged and failed."
 */
data class Sample(
    val rttMs: Double?,
    val tsMs: Long,
    val target: String = "default",
    val unreachable: Boolean = false,
    val wifi: WifiSnapshot? = null
)

// Default sized at 5400 samples = 90 minutes at 1 Hz (matches v1.0 retain capacity).
// Callers override via maxSize when running at different cadences.
class SampleBuffer(private val maxSize: Int = 5400) {

    private val samples = ArrayDeque<Sample>()
    private val lock = Any()

    fun add(sample: Sample) = synchronized(lock) {
        samples.addLast(sample)
        while (samples.size > maxSize) samples.removeFirst()
    }

    fun drain(): List<Sample> = synchronized(lock) {
        val out = samples.toList()
        samples.clear()
        out
    }

    fun prepend(retained: List<Sample>) = synchronized(lock) {
        val merged = ArrayDeque<Sample>(retained.size + samples.size)
        merged.addAll(retained)
        merged.addAll(samples)
        while (merged.size > maxSize) merged.removeFirst()
        samples.clear()
        samples.addAll(merged)
    }
}
