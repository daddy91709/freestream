package com.freestream.tv

object AdBlocker {
    // Reti pubblicitarie bloccate a livello di rete (anche dentro gli iframe del player)
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
        "a-ads.com"
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
}
