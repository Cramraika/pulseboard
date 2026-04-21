package com.pulseboard.core

import java.io.BufferedReader
import java.io.InputStreamReader

data class PingResult(
    val rtts: List<Double>,
    val packetLoss: Double,
    val rawOutput: String,
    val success: Boolean
)

object PingEngine {
    private val rttRegex = Regex("""time=([\d.]+)""")
    private val lossRegex = Regex("""(\d+)% packet loss""")

    fun runPing(target: String, count: Int, timeoutSec: Int): PingResult {
        return try {
            val process = Runtime.getRuntime().exec(
                arrayOf("ping", "-c", count.toString(), "-W", timeoutSec.toString(), target)
            )
            val output = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
            process.waitFor()

            val rtts = rttRegex.findAll(output)
                .mapNotNull { it.groupValues[1].toDoubleOrNull() }
                .toList()

            val loss = lossRegex.find(output)
                ?.groupValues?.get(1)?.toDoubleOrNull() ?: 100.0

            if (rtts.isEmpty()) {
                PingResult(emptyList(), 100.0, "", false)
            } else {
                PingResult(rtts, loss, output, true)
            }
        } catch (e: Exception) {
            PingResult(emptyList(), 100.0, "", false)
        }
    }
}
