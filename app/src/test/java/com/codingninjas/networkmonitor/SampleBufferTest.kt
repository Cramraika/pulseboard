package com.codingninjas.networkmonitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SampleBufferTest {

    @Test
    fun `drain returns empty list when buffer is empty`() {
        val buffer = SampleBuffer()
        assertEquals(emptyList<Sample>(), buffer.drain())
    }

    @Test
    fun `add then drain returns inserted samples in insertion order`() {
        val buffer = SampleBuffer()
        buffer.add(Sample(rttMs = 10.0, tsMs = 1_000L))
        buffer.add(Sample(rttMs = 20.0, tsMs = 2_000L))
        val drained = buffer.drain()
        assertEquals(2, drained.size)
        assertEquals(10.0, drained[0].rttMs)
        assertEquals(20.0, drained[1].rttMs)
    }

    @Test
    fun `drain clears the buffer`() {
        val buffer = SampleBuffer()
        buffer.add(Sample(rttMs = 10.0, tsMs = 1_000L))
        buffer.drain()
        assertEquals(emptyList<Sample>(), buffer.drain())
    }

    @Test
    fun `prepend puts retained samples before existing samples`() {
        val buffer = SampleBuffer()
        buffer.add(Sample(rttMs = 30.0, tsMs = 3_000L))
        buffer.prepend(listOf(
            Sample(rttMs = 10.0, tsMs = 1_000L),
            Sample(rttMs = 20.0, tsMs = 2_000L)
        ))
        val drained = buffer.drain()
        assertEquals(3, drained.size)
        assertEquals(10.0, drained[0].rttMs)
        assertEquals(20.0, drained[1].rttMs)
        assertEquals(30.0, drained[2].rttMs)
    }

    @Test
    fun `add beyond capacity evicts oldest samples`() {
        val buffer = SampleBuffer(maxSize = 3)
        buffer.add(Sample(rttMs = 1.0, tsMs = 1L))
        buffer.add(Sample(rttMs = 2.0, tsMs = 2L))
        buffer.add(Sample(rttMs = 3.0, tsMs = 3L))
        buffer.add(Sample(rttMs = 4.0, tsMs = 4L))   // evicts rttMs=1.0
        val drained = buffer.drain()
        assertEquals(3, drained.size)
        assertEquals(listOf(2.0, 3.0, 4.0), drained.map { it.rttMs })
    }

    @Test
    fun `prepend beyond capacity evicts oldest combined samples`() {
        val buffer = SampleBuffer(maxSize = 3)
        buffer.add(Sample(rttMs = 3.0, tsMs = 3L))
        buffer.add(Sample(rttMs = 4.0, tsMs = 4L))
        // prepend 3 retained → combined 5 → evict 2 oldest, keep last 3: [2.0, 3.0, 4.0]
        buffer.prepend(listOf(
            Sample(rttMs = 0.0, tsMs = 0L),
            Sample(rttMs = 1.0, tsMs = 1L),
            Sample(rttMs = 2.0, tsMs = 2L)
        ))
        val drained = buffer.drain()
        assertEquals(3, drained.size)
        assertEquals(listOf(2.0, 3.0, 4.0), drained.map { it.rttMs })
    }

    @Test
    fun `null rttMs samples are preserved`() {
        val buffer = SampleBuffer()
        buffer.add(Sample(rttMs = null, tsMs = 1L))
        buffer.add(Sample(rttMs = 15.5, tsMs = 2L))
        buffer.add(Sample(rttMs = null, tsMs = 3L))
        val drained = buffer.drain()
        assertEquals(3, drained.size)
        assertEquals(null, drained[0].rttMs)
        assertEquals(15.5, drained[1].rttMs)
        assertEquals(null, drained[2].rttMs)
    }

    @Test
    fun `concurrent add and drain do not corrupt state`() {
        val buffer = SampleBuffer(maxSize = 10_000)
        val threads = (0..9).map { threadId ->
            Thread {
                repeat(1000) { i ->
                    buffer.add(Sample(rttMs = threadId.toDouble(), tsMs = i.toLong()))
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        val drained = buffer.drain()
        assertEquals(10_000, drained.size)   // no lost adds, no duplicates
        assertTrue(drained.all { it.rttMs != null })
    }
}
