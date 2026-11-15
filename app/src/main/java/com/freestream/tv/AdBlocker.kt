package com.freestream.tv

import java.net.URI

object AdBlocker {
    // Reti pubblicitarie e tracker bloccati a livello di rete (anche dentro gli iframe del player)
    private val AD_HOSTS = hashSetOf(
        "spbgc.com",
        "doubleclick.net",
        "googlesyndication.com",
        "adservice.google.com",
        "popads.net",
        "popcash.net",
        "propellerads.com",
        "adsterra.com",
        "exoclick.com",
        "juicyads.com",
        "hilltopads.net",
        "onclickads.net",
        "adcash.com",
        "mgid.com",
        "a-ads.com",
        "realsrv.com",
        "tsyndicate.com",
        "trafficjunky.net",
        "adtng.com",
        "clksite.com",
        "onclasrv.com",
        "acscdn.com",
        "pushame.com",
        "richpush.co",
        "evadav.com",
        "galaksion.com",
        "clickadu.com",
        "admaven.com",
        "bidvertiser.com",
        "outbrain.com",
        "taboola.com",
        "zedo.com",
        "revcontent.com",
        "histats.com",
        "yandex.ru",
        "google-analytics.com",
        "googletagmanager.com",
        "connect.facebook.net",
        "scorecardresearch.com",
        "quantserve.com",
        "criteo.com",
        "criteo.net",
        "rubiconproject.com",
        "pubmatic.com",
        "openx.net",
        "adnxs.com",
        "smartadserver.com",
        "betrad.com"
    )

    fun isAdHost(rawHost: String?): Boolean {
        val host = rawHost?.trim()?.lowercase()?.trimEnd('.')?.takeIf { it.isNotEmpty() } ?: return false
        if (host in AD_HOSTS) return true
        var dotIndex = host.indexOf('.')
        while (dotIndex != -1) {
            val suffix = host.substring(dotIndex + 1)
            if (suffix in AD_HOSTS) return true
            dotIndex = host.indexOf('.', dotIndex + 1)
        }
        return false
    }

    /**
     * Consente nel main frame solo URL HTTPS del dominio ufficiale.
     * Negli iframe (es. embed del player video come vixcloud/CDN) consente URL HTTPS non pubblicitari.
     */
    fun shouldAllowNavigation(url: String?, isMainFrame: Boolean): Boolean {
        val raw = url?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        val uri = try {
            URI(raw)
        } catch (_: Exception) {
            return false
        }
        if (uri.scheme?.lowercase() != "https") return false
        val host = uri.host?.lowercase()?.takeIf { it.isNotEmpty() } ?: return false
        if (isAdHost(host)) return false
        return if (isMainFrame) DomainResolver.isOfficialHost(host) else true
    }

    /**
     * Mostra la schermata di errore nativa solo per errori 5xx sul main frame prima che il sito
     * abbia completato almeno un caricamento valido (evita falsi blocchi su 404/403/429 interni).
     */
    fun isFatalMainFrameHttpError(statusCode: Int, isMainFrame: Boolean, hasLoadedSite: Boolean): Boolean {
        return isMainFrame && !hasLoadedSite && statusCode >= 500
    }
}
