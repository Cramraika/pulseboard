package com.pulseboard.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

class UdpDnsPingerTest {

    @Test
    fun `runQuery returns success with rtt when resolver responds with QR flag set`() {
        // Local UDP echo that flips the QR bit so the response looks valid.
        val server = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
        val serverPort = server.localPort

        val serverThread = Thread {
            val buf = ByteArray(512)
            val received = DatagramPacket(buf, buf.size)
            server.receive(received)
            // Flip QR bit on byte 2 (the flags high byte) to make this a response.
            buf[2] = (buf[2].toInt() or 0x80).toByte()
            val response = DatagramPacket(buf, received.length, received.address, received.port)
            server.send(response)
        }
        serverThread.isDaemon = true
        serverThread.start()

        val pinger = UdpDnsPinger(resolverIp = "127.0.0.1", port = serverPort, timeoutMs = 2000)
        val result = pinger.runQuery("example.com")

        serverThread.join(5_000)
        server.close()

        assertTrue("expected success, got rtts=${result.rtts} loss=${result.packetLoss}", result.success)
        assertEquals(1, result.rtts.size)
        assertTrue("rtt should be positive", result.rtts[0] > 0.0)
        assertEquals(0.0, result.packetLoss, 0.001)
    }

    @Test
    fun `runQuery returns failure on timeout when resolver never responds`() {
        // Bind a server socket but never send a reply → client times out.
        val server = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
        val serverPort = server.localPort

        val serverThread = Thread {
            val buf = ByteArray(512)
            try {
                server.receive(DatagramPacket(buf, buf.size))
            } catch (_: Exception) {
                // thread may be interrupted by server.close() after timeout
            }
            // Intentionally no response.
        }
        serverThread.isDaemon = true
        serverThread.start()

        val pinger = UdpDnsPinger(resolverIp = "127.0.0.1", port = serverPort, timeoutMs = 200)
        val result = pinger.runQuery("example.com")

        server.close()
        serverThread.join(500)

        assertFalse(result.success)
        assertTrue(result.rtts.isEmpty())
        assertEquals(100.0, result.packetLoss, 0.001)
    }

    @Test
    fun `runQuery returns failure when response lacks the QR response flag`() {
        // Server echoes the query without setting QR → malformed response.
        val server = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
        val serverPort = server.localPort

        val serverThread = Thread {
            val buf = ByteArray(512)
            val received = DatagramPacket(buf, buf.size)
            server.receive(received)
            // Do NOT flip QR bit — caller should reject.
            val response = DatagramPacket(buf, received.length, received.address, received.port)
            server.send(response)
        }
        serverThread.isDaemon = true
        serverThread.start()

        val pinger = UdpDnsPinger(resolverIp = "127.0.0.1", port = serverPort, timeoutMs = 2000)
        val result = pinger.runQuery("example.com")

        serverThread.join(5_000)
        server.close()

        assertFalse("response without QR flag must fail", result.success)
        assertTrue(result.rtts.isEmpty())
    }
}
