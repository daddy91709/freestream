package com.freestream.tv

import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicReference

class DomainResolverTest {

    @Test
    fun `isOfficialHost matches root and subdomains only`() {
        assertTrue(DomainResolver.isOfficialHost("streamingcommunityz.nexus"))
        assertTrue(DomainResolver.isOfficialHost("cdn.streamingcommunityz.boo"))
        assertTrue(DomainResolver.isOfficialHost("STREAMINGCOMMUNITYZ.IT."))

        assertFalse(DomainResolver.isOfficialHost("notstreamingcommunityz.nexus"))
        assertFalse(DomainResolver.isOfficialHost("streamingcommunityz.evil.com"))
        assertFalse(DomainResolver.isOfficialHost("streamingcommunityz"))
        assertFalse(DomainResolver.isOfficialHost(null))
        assertFalse(DomainResolver.isOfficialHost(""))
    }

    @Test
    fun `originOf accepts only https and normalizes origin`() {
        assertEquals(
            "https://streamingcommunityz.nexus/",
            DomainResolver.originOf("https://streamingcommunityz.nexus/titles/123?q=a#top")
        )
        assertEquals(
            "https://streamingcommunityz.nexus:8443/",
            DomainResolver.originOf("https://STREAMINGCOMMUNITYZ.NEXUS:8443/home")
        )
        assertEquals(
            "https://streamingcommunityz.nexus/",
            DomainResolver.originOf("https://streamingcommunityz.nexus:443/")
        )

        assertNull(DomainResolver.originOf("http://streamingcommunityz.nexus/"))
        assertNull(DomainResolver.originOf("ftp://streamingcommunityz.nexus/"))
        assertNull(DomainResolver.originOf("not a valid url"))
        assertNull(DomainResolver.originOf(""))
    }

    @Test
    fun `fetchJsonDomain parses valid domains json and handles errors`() = runTest {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"streamingcommunity":{"full_url":"https://streamingcommunityz.nexus/"}}""")
        )
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setResponseCode(200).setBody("{invalid-json"))
        server.start()

        try {
            val client = OkHttpClient()
            assertEquals(
                "https://streamingcommunityz.nexus/",
                DomainResolver.fetchJsonDomain(server.url("/domains.json").toString(), client)
            )
            assertNull(DomainResolver.fetchJsonDomain(server.url("/domains.json").toString(), client))
            assertNull(DomainResolver.fetchJsonDomain(server.url("/domains.json").toString(), client))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `probe validates ziggy signature and official redirects`() = runTest {
        val validHtml = "<html><script>const Ziggy = {\"routes\":{\"titles.watch\":{}}};</script></html>"
        val parkingHtml = "<html><body>Domain parked</body></html>"

        val client = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val req = chain.request()
                val url = req.url.toString()
                when {
                    url == "https://streamingcommunityz.nexus/" -> Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(validHtml.toResponseBody("text/html".toMediaType()))
                        .build()

                    url == "https://streamingcommunityz.boo/" -> Response.Builder()
                        .request(req.newBuilder().url("https://streamingcommunityz.nexus/").build())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(validHtml.toResponseBody("text/html".toMediaType()))
                        .build()

                    url == "https://streamingcommunityz.old/" -> Response.Builder()
                        .request(req.newBuilder().url("https://evil.example.com/").build())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(validHtml.toResponseBody("text/html".toMediaType()))
                        .build()

                    url == "https://streamingcommunityz.fake/" -> Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(parkingHtml.toResponseBody("text/html".toMediaType()))
                        .build()

                    else -> Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(503)
                        .message("Service Unavailable")
                        .body("".toResponseBody("text/plain".toMediaType()))
                        .build()
                }
            })
            .build()

        assertEquals(
            "https://streamingcommunityz.nexus/",
            DomainResolver.probe("https://streamingcommunityz.nexus/", client)
        )
        assertEquals(
            "https://streamingcommunityz.nexus/",
            DomainResolver.probe("https://streamingcommunityz.boo/", client)
        )
        assertNull(DomainResolver.probe("https://streamingcommunityz.old/", client))
        assertNull(DomainResolver.probe("https://streamingcommunityz.fake/", client))
        assertNull(DomainResolver.probe("https://streamingcommunityz.down/", client))
    }

    @Test
    fun `race selects valid domain from json when cached domain fails`() = runTest {
        val validHtml = "<html><script>const Ziggy = {\"routes\":{\"titles.watch\":{}}};</script></html>"
        val jsonUrl = "https://raw.githubusercontent.com/daddy91709/freestream/main/domains.json"

        val client = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val req = chain.request()
                val url = req.url.toString()
                when (url) {
                    jsonUrl -> Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body("""{"streamingcommunity":{"full_url":"https://streamingcommunityz.nexus/"}}""".toResponseBody("application/json".toMediaType()))
                        .build()

                    "https://streamingcommunityz.nexus/" -> Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(validHtml.toResponseBody("text/html".toMediaType()))
                        .build()

                    else -> Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(502)
                        .message("Bad Gateway")
                        .body("".toResponseBody("text/plain".toMediaType()))
                        .build()
                }
            })
            .build()

        val jsonFallback = AtomicReference<String?>(null)
        val winner = DomainResolver.race(
            cached = "https://streamingcommunityz.down/",
            jsonFallback = jsonFallback,
            jsonUrls = listOf(jsonUrl),
            clientOverride = client
        )

        assertEquals("https://streamingcommunityz.nexus/", winner)
        assertEquals("https://streamingcommunityz.nexus/", jsonFallback.get())
    }

    @Test
    fun `parseJsonCandidates extracts full_url, domain and old_domain without duplicates`() {
        val json = """
            {
              "streamingcommunity": {
                "domain": "nexus",
                "full_url": "https://streamingcommunityz.nexus/",
                "old_domain": "boo"
              }
            }
        """.trimIndent()

        assertEquals(
            listOf(
                "https://streamingcommunityz.nexus/",
                "https://streamingcommunityz.boo/"
            ),
            DomainResolver.parseJsonCandidates(json)
        )
        assertEquals(emptyList<String>(), DomainResolver.parseJsonCandidates("{bad-json"))
    }

    @Test
    fun `parseCrtShCandidates sorts by id descending, strips wildcards and deduplicates`() {
        val crtJson = """
            [
              {"id": 100, "name_value": "streamingcommunityz.boo\n*.streamingcommunityz.boo"},
              {"id": 300, "name_value": "*.streamingcommunityz.nexus\ncdn.streamingcommunityz.nexus"},
              {"id": 200, "name_value": "evil.example.com\nstreamingcommunityz.lol"}
            ]
        """.trimIndent()

        assertEquals(
            listOf(
                "https://streamingcommunityz.nexus/",
                "https://streamingcommunityz.lol/",
                "https://streamingcommunityz.boo/"
            ),
            DomainResolver.parseCrtShCandidates(crtJson)
        )
    }

    @Test
    fun `race follows redirect from old_domain when full_url fails`() = runTest {
        val validHtml = "<html><script>const Ziggy = {\"routes\":{\"titles.watch\":{}}};</script></html>"
        val jsonUrl = "https://raw.githubusercontent.com/daddy91709/freestream/main/domains.json"
        val jsonPayload = """
            {
              "streamingcommunity": {
                "domain": "nexus",
                "full_url": "https://streamingcommunityz.nexus/",
                "old_domain": "boo"
              }
            }
        """.trimIndent()

        val client = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val req = chain.request()
                when (req.url.toString()) {
                    jsonUrl -> Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(jsonPayload.toResponseBody("application/json".toMediaType()))
                        .build()

                    "https://streamingcommunityz.boo/" -> Response.Builder()
                        .request(req.newBuilder().url("https://streamingcommunityz.live/").build())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(validHtml.toResponseBody("text/html".toMediaType()))
                        .build()

                    else -> Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(503)
                        .message("Unavailable")
                        .body("".toResponseBody("text/plain".toMediaType()))
                        .build()
                }
            })
            .build()

        val jsonFallback = AtomicReference<String?>(null)
        val winner = DomainResolver.race(
            cached = null,
            jsonFallback = jsonFallback,
            jsonUrls = listOf(jsonUrl),
            clientOverride = client
        )

        assertEquals("https://streamingcommunityz.live/", winner)
    }

    @Test
    fun `isBlockedBySystemDns detects ISP DNS failure or sinkhole when DoH succeeds`() {
        val publicIp = InetAddress.getByAddress("streamingcommunityz.nexus", byteArrayOf(104, 21, 10, 1))
        val loopbackIp = InetAddress.getByAddress("streamingcommunityz.nexus", byteArrayOf(127, 0, 0, 1))
        val zeroIp = InetAddress.getByAddress("streamingcommunityz.nexus", byteArrayOf(0, 0, 0, 0))

        assertTrue(
            DomainResolver.isBlockedBySystemDns(
                url = "https://streamingcommunityz.nexus/",
                systemLookup = { throw UnknownHostException("NXDOMAIN") },
                dohLookup = { listOf(publicIp) }
            )
        )
        assertTrue(
            DomainResolver.isBlockedBySystemDns(
                url = "https://streamingcommunityz.nexus/",
                systemLookup = { listOf(loopbackIp, zeroIp) },
                dohLookup = { listOf(publicIp) }
            )
        )
        assertFalse(
            DomainResolver.isBlockedBySystemDns(
                url = "https://streamingcommunityz.nexus/",
                systemLookup = { listOf(publicIp) },
                dohLookup = { listOf(publicIp) }
            )
        )
        assertFalse(
            DomainResolver.isBlockedBySystemDns(
                url = "https://streamingcommunityz.nexus/",
                systemLookup = { throw UnknownHostException("Offline") },
                dohLookup = { throw UnknownHostException("Offline") }
            )
        )
    }
}
