package com.pulseboard.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GatewayResolverTest {

    @Test
    fun `picks host address of the default IPv4 route`() {
        val entries = listOf(
            RouteEntry(isDefault = false, gatewayHost = "10.0.0.1"),   // LAN route, not default
            RouteEntry(isDefault = true,  gatewayHost = "192.168.1.1") // default route
        )
        assertEquals("192.168.1.1", pickDefaultIPv4Gateway(entries))
    }

    @Test
    fun `returns null when no default route exists`() {
        val entries = listOf(
            RouteEntry(isDefault = false, gatewayHost = "10.0.0.1"),
            RouteEntry(isDefault = false, gatewayHost = "10.0.0.2")
        )
        assertNull(pickDefaultIPv4Gateway(entries))
    }

    @Test
    fun `returns null when default route is IPv6-only (no IPv4 gateway host)`() {
        // IPv6-only default route → routeInfoToEntry produces null gatewayHost;
        // picker then skips it and returns null overall.
        val entries = listOf(
            RouteEntry(isDefault = true, gatewayHost = null)
        )
        assertNull(pickDefaultIPv4Gateway(entries))
    }

    @Test
    fun `picks first default IPv4 route when multiple exist`() {
        // Rare but possible on multi-homed devices.
        val entries = listOf(
            RouteEntry(isDefault = true, gatewayHost = "10.0.0.1"),
            RouteEntry(isDefault = true, gatewayHost = "192.168.1.1")
        )
        assertEquals("10.0.0.1", pickDefaultIPv4Gateway(entries))
    }

    // --- v1.2 cache-last-good helper ---

    @Test
    fun `resolveGatewayWithCache returns LIVE when live value present`() {
        val result = resolveGatewayWithCache(live = "192.168.1.1", cached = "192.168.1.1")
        assertEquals("192.168.1.1", result.ip)
        assertEquals(GatewaySource.LIVE, result.source)
    }

    @Test
    fun `resolveGatewayWithCache returns LIVE even when cache is stale`() {
        // Fresh live value wins over cache, regardless of whether they agree.
        val result = resolveGatewayWithCache(live = "10.0.0.1", cached = "192.168.1.1")
        assertEquals("10.0.0.1", result.ip)
        assertEquals(GatewaySource.LIVE, result.source)
    }

    @Test
    fun `resolveGatewayWithCache falls back to CACHED when live is null`() {
        val result = resolveGatewayWithCache(live = null, cached = "192.168.1.1")
        assertEquals("192.168.1.1", result.ip)
        assertEquals(GatewaySource.CACHED, result.source)
    }

    @Test
    fun `resolveGatewayWithCache returns NULL source when both are null`() {
        val result = resolveGatewayWithCache(live = null, cached = null)
        assertNull(result.ip)
        assertEquals(GatewaySource.NULL, result.source)
    }
}
