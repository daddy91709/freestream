package com.freestream.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdaterTest {

    @Test
    fun `isNewerVersion compares semantic versions accurately`() {
        assertTrue(AppUpdater.isNewerVersion("1.4.0", "1.5.0"))
        assertTrue(AppUpdater.isNewerVersion("1.4.0", "v1.4.1"))
        assertTrue(AppUpdater.isNewerVersion("1.4", "1.4.1"))
        assertTrue(AppUpdater.isNewerVersion("1.4.0", "2.0.0"))

        assertFalse(AppUpdater.isNewerVersion("1.5.0", "1.5.0"))
        assertFalse(AppUpdater.isNewerVersion("1.5.0", "v1.5.0"))
        assertFalse(AppUpdater.isNewerVersion("1.5.0", "1.4.9"))
        assertFalse(AppUpdater.isNewerVersion("1.5.0", "1.5"))
        assertFalse(AppUpdater.isNewerVersion("1.5.0", "1.5.0-beta"))
        assertFalse(AppUpdater.isNewerVersion("1.5.0", ""))
        assertFalse(AppUpdater.isNewerVersion("1.5.0", "invalid"))
    }

    @Test
    fun `isTrustedReleaseUrl restricts downloads to github HTTPS origins`() {
        assertTrue(AppUpdater.isTrustedReleaseUrl("https://github.com/daddy91709/freestream/releases/download/v1.5.0/app.apk"))
        assertTrue(AppUpdater.isTrustedReleaseUrl("https://objects.githubusercontent.com/github-production-release-asset/app.apk"))

        assertFalse(AppUpdater.isTrustedReleaseUrl("http://github.com/daddy91709/freestream/app.apk"))
        assertFalse(AppUpdater.isTrustedReleaseUrl("https://evil-github.com/app.apk"))
        assertFalse(AppUpdater.isTrustedReleaseUrl("https://example.com/app.apk"))
        assertFalse(AppUpdater.isTrustedReleaseUrl(null))
    }

    @Test
    fun `parseLatestRelease extracts apk asset for newer release and rejects invalid cases`() {
        val validJson = """
            {
              "tag_name": "v1.5.0",
              "draft": false,
              "prerelease": false,
              "body": "Miglioramenti player e DoH",
              "assets": [
                {
                  "name": "checksums.txt",
                  "browser_download_url": "https://github.com/daddy91709/freestream/releases/download/v1.5.0/checksums.txt"
                },
                {
                  "name": "FreeStream-v1.5.0-release.apk",
                  "browser_download_url": "https://github.com/daddy91709/freestream/releases/download/v1.5.0/FreeStream-v1.5.0-release.apk"
                }
              ]
            }
        """.trimIndent()

        val release = AppUpdater.parseLatestRelease(validJson, "1.4.0")
        assertNotNull(release)
        assertEquals("1.5.0", release!!.versionName)
        assertEquals(
            "https://github.com/daddy91709/freestream/releases/download/v1.5.0/FreeStream-v1.5.0-release.apk",
            release.downloadUrl
        )
        assertEquals("Miglioramenti player e DoH", release.releaseNotes)

        // Versione già aggiornata
        assertNull(AppUpdater.parseLatestRelease(validJson, "1.5.0"))
        assertNull(AppUpdater.parseLatestRelease(validJson, "1.6.0"))

        // Draft / prerelease / senza APK / JSON malformato
        assertNull(AppUpdater.parseLatestRelease(validJson.replace("\"draft\": false", "\"draft\": true"), "1.4.0"))
        assertNull(AppUpdater.parseLatestRelease(validJson.replace("\"prerelease\": false", "\"prerelease\": true"), "1.4.0"))
        assertNull(AppUpdater.parseLatestRelease("""{"tag_name":"v1.5.0","assets":[]}""", "1.4.0"))
        assertNull(AppUpdater.parseLatestRelease("{broken", "1.4.0"))
    }
}
