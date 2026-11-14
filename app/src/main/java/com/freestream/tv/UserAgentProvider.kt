package com.freestream.tv

import android.content.Context
import android.webkit.WebSettings

object UserAgentProvider {
    private const val FALLBACK_CHROME_VERSION = "125.0.0.0"
    private val CHROME_VERSION_REGEX = Regex("""Chrome/([0-9.]+)""")

    const val DEFAULT_DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$FALLBACK_CHROME_VERSION Safari/537.36"

    fun getUserAgent(context: Context, isTv: Boolean): String? {
        if (!isTv) return null
        val defaultUa = runCatching { WebSettings.getDefaultUserAgent(context) }.getOrNull()
        val chromeVersion = defaultUa
            ?.let { CHROME_VERSION_REGEX.find(it)?.groupValues?.getOrNull(1) }
            ?.takeIf { it.isNotBlank() }
            ?: FALLBACK_CHROME_VERSION
        return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$chromeVersion Safari/537.36"
    }
}
