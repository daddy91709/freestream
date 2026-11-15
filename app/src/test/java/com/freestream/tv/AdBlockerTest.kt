package com.freestream.tv

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdBlockerTest {

    @Test
    fun `blocks exact ad hosts`() {
        assertTrue(AdBlocker.isAdHost("doubleclick.net"))
        assertTrue(AdBlocker.isAdHost("spbgc.com"))
        assertTrue(AdBlocker.isAdHost("a-ads.com"))
    }

    @Test
    fun `blocks subdomains of ad hosts case-insensitively`() {
        assertTrue(AdBlocker.isAdHost("ads.doubleclick.net"))
        assertTrue(AdBlocker.isAdHost("a.b.adservice.google.com"))
        assertTrue(AdBlocker.isAdHost("  CDN.PopAds.NET. "))
    }

    @Test
    fun `allows legitimate hosts and avoids partial suffix matches`() {
        assertFalse(AdBlocker.isAdHost("my-doubleclick.net"))
        assertFalse(AdBlocker.isAdHost("streamingcommunityz.nexus"))
        assertFalse(AdBlocker.isAdHost("google.com"))
        assertFalse(AdBlocker.isAdHost(null))
        assertFalse(AdBlocker.isAdHost(""))
        assertFalse(AdBlocker.isAdHost("   "))
    }
}
