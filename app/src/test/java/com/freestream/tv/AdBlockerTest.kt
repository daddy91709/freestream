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

    @Test
    fun `blocks newly added ad and tracker hosts`() {
        assertTrue(AdBlocker.isAdHost("cdn.realsrv.com"))
        assertTrue(AdBlocker.isAdHost("tsyndicate.com"))
        assertTrue(AdBlocker.isAdHost("www.googletagmanager.com"))
        assertTrue(AdBlocker.isAdHost("scripts.admaven.com"))
        assertTrue(AdBlocker.isAdHost("static.criteo.net"))
    }

    @Test
    fun `shouldAllowNavigation distinguishes main frame vs player iframes and blocks ads`() {
        assertTrue(AdBlocker.shouldAllowNavigation("https://streamingcommunityz.nexus/watch/123", true))
        assertFalse(AdBlocker.shouldAllowNavigation("https://vixcloud.co/embed/123", true))
        assertTrue(AdBlocker.shouldAllowNavigation("https://vixcloud.co/embed/123", false))

        assertFalse(AdBlocker.shouldAllowNavigation("https://ads.popads.net/pop.html", false))
        assertFalse(AdBlocker.shouldAllowNavigation("http://vixcloud.co/embed/123", false))
        assertFalse(AdBlocker.shouldAllowNavigation("intent://open", true))
        assertFalse(AdBlocker.shouldAllowNavigation(null, false))
    }

    @Test
    fun `isFatalMainFrameHttpError triggers only on initial 5xx main frame responses`() {
        assertTrue(AdBlocker.isFatalMainFrameHttpError(502, isMainFrame = true, hasLoadedSite = false))
        assertTrue(AdBlocker.isFatalMainFrameHttpError(500, isMainFrame = true, hasLoadedSite = false))

        assertFalse(AdBlocker.isFatalMainFrameHttpError(404, isMainFrame = true, hasLoadedSite = false))
        assertFalse(AdBlocker.isFatalMainFrameHttpError(403, isMainFrame = true, hasLoadedSite = false))
        assertFalse(AdBlocker.isFatalMainFrameHttpError(429, isMainFrame = true, hasLoadedSite = false))
        assertFalse(AdBlocker.isFatalMainFrameHttpError(503, isMainFrame = true, hasLoadedSite = true))
        assertFalse(AdBlocker.isFatalMainFrameHttpError(503, isMainFrame = false, hasLoadedSite = false))
    }
}
