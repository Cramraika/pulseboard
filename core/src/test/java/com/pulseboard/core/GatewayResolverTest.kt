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
}
