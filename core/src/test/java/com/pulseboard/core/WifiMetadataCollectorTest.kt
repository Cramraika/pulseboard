package com.pulseboard.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WifiMetadataCollectorTest {

    @Test
    fun `stripSsidQuotes removes surrounding double quotes`() {
        assertEquals("CN-Office", stripSsidQuotes("\"CN-Office\""))
    }

    @Test
    fun `stripSsidQuotes preserves unquoted strings`() {
        assertEquals("CN-Office", stripSsidQuotes("CN-Office"))
    }

    @Test
    fun `stripSsidQuotes preserves the unknown-ssid sentinel unchanged`() {
        assertEquals("<unknown ssid>", stripSsidQuotes("<unknown ssid>"))
    }

    @Test
    fun `stripSsidQuotes returns null for null input`() {
        assertNull(stripSsidQuotes(null))
    }
}
