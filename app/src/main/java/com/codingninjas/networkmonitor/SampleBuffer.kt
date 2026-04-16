package com.codingninjas.networkmonitor

data class Sample(
    val rttMs: Double?,
    val tsMs: Long
)

class SampleBuffer(private val maxSize: Int = Constants.MAX_BUFFER_SAMPLES) {

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
