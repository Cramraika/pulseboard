package com.pulseboard.core

import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Measures round-trip time of a UDP DNS `A` query against a public resolver.
 *
 * Purpose: ICMP pings (the existing [PingEngine]) are a proxy for RTP traffic,
 * but many enterprise firewalls and carrier middleboxes deprioritize ICMP
 * relative to UDP. A UDP DNS probe takes a packet path closer to RTP and
 * diverges from ICMP under exactly the conditions we care about — middlebox
 * congestion, NAT session pressure, CoS deprioritization. When ICMP-Smartflo
 * looks clean but UDP-DNS shows loss/jitter, the issue is UDP-path-specific.
 *
 * The resolver (default `1.1.1.1`) always returns a <100 byte UDP response
 * to a single-domain `A` query, so no echo server is needed.
 */
class UdpDnsPinger(
    private val resolverIp: String = "1.1.1.1",
    private val port: Int = 53,
    private val timeoutMs: Int = 2000
) {

    fun runQuery(domain: String = "cloudflare.com"): PingResult {
        val txnId = ((Math.random() * 0xFFFF).toInt() and 0xFFFF).toShort()
        val query = buildDnsQuery(domain, txnId)
        return try {
            DatagramSocket().use { socket ->
                socket.soTimeout = timeoutMs
                val address = InetAddress.getByName(resolverIp)
                val requestPacket = DatagramPacket(query, query.size, address, port)
                val t0Ns = System.nanoTime()
                socket.send(requestPacket)

                val responseBuf = ByteArray(512)
                val responsePacket = DatagramPacket(responseBuf, responseBuf.size)
                socket.receive(responsePacket)
                val rttMs = (System.nanoTime() - t0Ns) / 1_000_000.0

                if (isValidDnsResponse(responseBuf, responsePacket.length)) {
                    PingResult(rtts = listOf(rttMs), packetLoss = 0.0, rawOutput = "", success = true)
                } else {
                    PingResult(rtts = emptyList(), packetLoss = 100.0, rawOutput = "", success = false)
                }
            }
        } catch (e: Exception) {
            PingResult(rtts = emptyList(), packetLoss = 100.0, rawOutput = "", success = false)
        }
    }
}

/**
 * Builds a minimal DNS query packet: 12-byte header + variable-length question
 * section. Hand-rolled to avoid an external DNS library dep.
 *
 * Header: txnId (2B), flags=0x0100 (standard query, recursion desired),
 *         QDCOUNT=1, ANCOUNT=0, NSCOUNT=0, ARCOUNT=0 (each 2B).
 * Question: domain labels (length-prefixed, 0-terminated) + QTYPE=A + QCLASS=IN.
 */
internal fun buildDnsQuery(domain: String, txnId: Short): ByteArray {
    val buf = ByteArrayOutputStream()
    // Header
    buf.write((txnId.toInt() ushr 8) and 0xFF)
    buf.write(txnId.toInt() and 0xFF)
    buf.write(0x01)  // Flags high: RD (recursion desired)
    buf.write(0x00)  // Flags low: all zero
    buf.write(0x00); buf.write(0x01)   // QDCOUNT = 1
    buf.write(0x00); buf.write(0x00)   // ANCOUNT = 0
    buf.write(0x00); buf.write(0x00)   // NSCOUNT = 0
    buf.write(0x00); buf.write(0x00)   // ARCOUNT = 0
    // Question: domain name as length-prefixed labels
    for (label in domain.split('.')) {
        val bytes = label.toByteArray(Charsets.US_ASCII)
        buf.write(bytes.size and 0xFF)
        buf.write(bytes)
    }
    buf.write(0)                         // End of name
    buf.write(0x00); buf.write(0x01)     // QTYPE = A (IPv4 address)
    buf.write(0x00); buf.write(0x01)     // QCLASS = IN
    return buf.toByteArray()
}

/**
 * Bare-minimum DNS response validation: length sanity + QR (response) flag set.
 * We don't parse answers because all we care about is "did the resolver
 * acknowledge the query" — that's the RTT proxy we're measuring.
 */
internal fun isValidDnsResponse(buf: ByteArray, len: Int): Boolean {
    if (len < 12) return false
    // Byte 2 holds the flags high byte; top bit (0x80) is QR (response = 1).
    return (buf[2].toInt() and 0x80) != 0
}
