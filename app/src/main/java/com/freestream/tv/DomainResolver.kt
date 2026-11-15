package com.freestream.tv

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.dnsoverhttps.DnsOverHttps
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.InetAddress
import java.net.URI
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object DomainResolver {
    private const val TAG = "DomainResolver"
    private const val PREFS = "domain_resolver"
    private const val KEY_LAST_DOMAIN = "last_domain"
    private const val RACE_TIMEOUT_MS = 9000L
    private const val CRT_FALLBACK_TIMEOUT_MS = 9000L
    private const val PEEK_BYTES = 64L * 1024
    private const val OFFICIAL_LABEL = "streamingcommunityz"
    private const val CRT_SH_URL = "https://crt.sh/?q=streamingcommunityz.%25&output=json"

    // Unica fonte di verità remota: domains.json mantenuto da GitHub Actions in questo repository
    private val GITHUB_JSON_URLS = listOf(
        "https://raw.githubusercontent.com/daddy91709/freestream/main/domains.json",
        "https://cdn.jsdelivr.net/gh/daddy91709/freestream@main/domains.json"
    )

    private val bootstrapClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .callTimeout(6, TimeUnit.SECONDS)
        .build()

    private val dohDns: Dns by lazy {
        DnsOverHttps.Builder()
            .client(bootstrapClient)
            .url("https://1.1.1.1/dns-query".toHttpUrl())
            .bootstrapDnsHosts(
                InetAddress.getByName("1.1.1.1"),
                InetAddress.getByName("1.0.0.1")
            )
            .build()
    }

    // Risolve prima col DNS di sistema; se bloccato/sinkholato dall'ISP passa automaticamente a Cloudflare DoH
    private val resilientDns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            if (hostname.equals("localhost", ignoreCase = true)) {
                return Dns.SYSTEM.lookup(hostname)
            }
            val systemAddresses = try {
                Dns.SYSTEM.lookup(hostname).filter { isValidPublicAddress(it) }
            } catch (_: UnknownHostException) {
                emptyList()
            }
            if (systemAddresses.isNotEmpty()) {
                return systemAddresses
            }
            try {
                val dohAddresses = dohDns.lookup(hostname).filter { isValidPublicAddress(it) }
                if (dohAddresses.isNotEmpty()) {
                    return dohAddresses
                }
            } catch (e: Exception) {
                Log.w(TAG, "DoH lookup fallito per $hostname: ${e.message}")
            }
            throw UnknownHostException("Impossibile risolvere $hostname né via DNS di sistema né via DoH")
        }
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .callTimeout(7, TimeUnit.SECONDS)
        .dns(resilientDns)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    /**
     * Mette in gara l'ultimo dominio funzionante in cache e i candidati di `domains.json`
     * (compreso `old_domain` che può reindirizzare via 301 al nuovo dominio).
     * Se entrambi falliscono, esegue un fallback su Certificate Transparency (`crt.sh`).
     */
    suspend fun resolveActiveDomain(context: Context): String {
        val cached = getLastDomain(context)
        val jsonFallback = AtomicReference<String?>(null)

        val winner = withTimeoutOrNull(RACE_TIMEOUT_MS) {
            race(cached, jsonFallback)
        }
        if (winner != null) {
            saveDomain(context, winner)
            return winner
        }

        // Fallback di emergenza via Certificate Transparency se cache e domains.json non rispondono
        val crtWinner = withTimeoutOrNull(CRT_FALLBACK_TIMEOUT_MS) {
            val crtCandidates = discoverFromCrtSh()
            if (crtCandidates.isEmpty()) null else raceCandidates(crtCandidates)
        }
        if (crtWinner != null) {
            Log.i(TAG, "Dominio attivo rilevato via crt.sh: $crtWinner")
            saveDomain(context, crtWinner)
            return crtWinner
        }

        val fallback = jsonFallback.get() ?: cached
        if (fallback != null) {
            Log.i(TAG, "Probe non concluso entro il timeout, uso fallback fidato: $fallback")
            saveDomain(context, fallback)
            return fallback
        }

        throw IOException("Nessun dominio attivo disponibile (né da GitHub, né da crt.sh, né in cache)")
    }

    /**
     * Verifica se l'host di [url] fallisce (o restituisce un IP sinkhole) sul DNS di sistema
     * ma viene risolto correttamente tramite DNS-over-HTTPS (tipico blocco DNS degli ISP).
     */
    suspend fun isBlockedBySystemDns(url: String?): Boolean = withContext(Dispatchers.IO) {
        isBlockedBySystemDns(
            url = url,
            systemLookup = { host -> Dns.SYSTEM.lookup(host) },
            dohLookup = { host -> dohDns.lookup(host) }
        )
    }

    internal fun isBlockedBySystemDns(
        url: String?,
        systemLookup: (String) -> List<InetAddress>,
        dohLookup: (String) -> List<InetAddress>
    ): Boolean {
        val host = hostOf(url ?: return false)?.takeIf { it.isNotBlank() } ?: return false
        val systemValid = try {
            systemLookup(host).any { isValidPublicAddress(it) }
        } catch (_: Exception) {
            false
        }
        if (systemValid) return false

        return try {
            dohLookup(host).any { isValidPublicAddress(it) }
        } catch (_: Exception) {
            false
        }
    }

    internal fun isValidPublicAddress(addr: InetAddress): Boolean {
        return !addr.isLoopbackAddress && !addr.isAnyLocalAddress && addr.hostAddress != "0.0.0.0"
    }

    /** Memorizza il dominio effettivamente in uso (es. dopo un redirect a un nuovo dominio durante la navigazione). */
    fun rememberIfSite(context: Context, url: String?) {
        val origin = originOf(url ?: return) ?: return
        if (!isOfficialHost(hostOf(origin))) return
        if (origin != getLastDomain(context)) saveDomain(context, origin)
    }

    /** true per "streamingcommunityz.<tld>" e i suoi sottodomini (es. cdn.streamingcommunityz.boo). */
    fun isOfficialHost(host: String?): Boolean {
        val labels = host?.trim()?.lowercase()?.trimEnd('.')?.split('.') ?: return false
        if (labels.size < 2 || labels.any { it.isEmpty() }) return false
        return labels[labels.size - 2] == OFFICIAL_LABEL
    }

    internal suspend fun race(
        cached: String?,
        jsonFallback: AtomicReference<String?>,
        jsonUrls: List<String> = GITHUB_JSON_URLS,
        clientOverride: OkHttpClient = client
    ): String? = coroutineScope {
        val results = Channel<String?>(Channel.UNLIMITED)
        val tried = java.util.Collections.synchronizedSet(mutableSetOf<String>())
        var pending = 0

        fun launchProbe(candidate: String) {
            val key = originOf(candidate) ?: return
            if (!isOfficialHost(hostOf(key))) {
                Log.w(TAG, "Scartato dominio non ufficiale: $candidate")
                return
            }
            if (!tried.add(key)) return
            pending++
            launch { results.send(probe(key, clientOverride)) }
        }

        cached?.let { launchProbe(it) }

        // Legge in parallelo domains.json; tutti i candidati (full_url, domain, old_domain) entrano subito in gara
        val jsonCandidates = Channel<List<String>>(Channel.UNLIMITED)
        var jsonPending = jsonUrls.size
        jsonUrls.forEach { url ->
            launch { jsonCandidates.send(fetchJsonCandidates(url, clientOverride)) }
        }

        var winner: String? = null
        while (winner == null && (pending > 0 || jsonPending > 0)) {
            kotlinx.coroutines.selects.select<Unit> {
                if (pending > 0) results.onReceive { r ->
                    pending--
                    if (r != null) winner = r
                }
                if (jsonPending > 0) jsonCandidates.onReceive { candidates ->
                    jsonPending--
                    if (candidates.isNotEmpty()) {
                        jsonFallback.compareAndSet(null, candidates.first())
                        candidates.forEach { launchProbe(it) }
                    }
                }
            }
        }
        // Annulla tutte le richieste ancora in corso
        coroutineContext[kotlinx.coroutines.Job]?.children?.forEach { it.cancel() }
        winner
    }

    internal suspend fun raceCandidates(
        candidates: List<String>,
        clientOverride: OkHttpClient = client
    ): String? = coroutineScope {
        val unique = candidates.mapNotNull { originOf(it) }.filter { isOfficialHost(hostOf(it)) }.distinct()
        if (unique.isEmpty()) return@coroutineScope null

        val results = Channel<String?>(Channel.UNLIMITED)
        var pending = unique.size
        unique.forEach { candidate ->
            launch { results.send(probe(candidate, clientOverride)) }
        }

        var winner: String? = null
        while (winner == null && pending > 0) {
            val r = results.receive()
            pending--
            if (r != null) winner = r
        }
        coroutineContext[kotlinx.coroutines.Job]?.children?.forEach { it.cancel() }
        winner
    }

    internal suspend fun probe(candidate: String, clientOverride: OkHttpClient = client): String? {
        return try {
            val request = Request.Builder()
                .url(candidate)
                .header("User-Agent", UserAgentProvider.DEFAULT_DESKTOP_UA)
                .header("Accept", "text/html")
                .build()
            clientOverride.newCall(request).await().use { response ->
                if (!response.isSuccessful) return null
                val finalUrl = originOf(response.request.url.toString()) ?: return null
                // Il redirect deve restare su un dominio ufficiale HTTPS
                if (!isOfficialHost(response.request.url.host)) return null
                // Firma del sito vero (app Laravel/Inertia con rotte Ziggy): scarta cloni e pagine di parcheggio
                val head = response.peekBody(PEEK_BYTES).string()
                if (!head.contains("const Ziggy") || !head.contains("\"titles.watch\"")) return null
                Log.i(TAG, "Dominio valido: $finalUrl (candidato $candidate)")
                finalUrl
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Fallito $candidate: ${e.message}")
            null
        }
    }

    internal fun parseJsonCandidates(body: String): List<String> {
        val sc = try {
            JSONObject(body).optJSONObject("streamingcommunity")
        } catch (_: Exception) {
            null
        } ?: return emptyList()

        fun normalizeTldOrUrl(rawValue: String): String? {
            val raw = rawValue.trim().trimStart('.')
            if (raw.isEmpty()) return null
            return when {
                raw.startsWith("https://", ignoreCase = true) -> raw
                raw.startsWith("$OFFICIAL_LABEL.", ignoreCase = true) -> "https://$raw/"
                else -> "https://$OFFICIAL_LABEL.$raw/"
            }
        }

        val candidates = buildList {
            sc.optString("full_url", "").trim().takeIf { it.isNotEmpty() }?.let { add(it) }
            normalizeTldOrUrl(sc.optString("domain", ""))?.let { add(it) }
            normalizeTldOrUrl(sc.optString("old_domain", ""))?.let { add(it) }
        }

        return candidates
            .mapNotNull { originOf(it) }
            .filter { isOfficialHost(hostOf(it)) }
            .distinct()
    }

    internal suspend fun fetchJsonCandidates(jsonUrl: String, clientOverride: OkHttpClient = client): List<String> {
        return try {
            val request = Request.Builder()
                .url(jsonUrl)
                .header("User-Agent", "Mozilla/5.0 FreeStreamTV")
                .header("Cache-Control", "no-cache")
                .build()
            clientOverride.newCall(request).await().use { response ->
                if (!response.isSuccessful) return emptyList()
                val body = response.body?.string().orEmpty()
                parseJsonCandidates(body)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Errore JSON $jsonUrl: ${e.message}")
            emptyList()
        }
    }

    internal suspend fun fetchJsonDomain(jsonUrl: String, clientOverride: OkHttpClient = client): String? {
        return fetchJsonCandidates(jsonUrl, clientOverride).firstOrNull()
    }

    internal fun parseCrtShCandidates(body: String, limit: Int = 8): List<String> {
        val arr = try {
            JSONArray(body)
        } catch (_: Exception) {
            return emptyList()
        }
        data class Entry(val id: Long, val names: String)
        val entries = ArrayList<Entry>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            entries.add(Entry(obj.optLong("id", 0L), obj.optString("name_value", "")))
        }
        entries.sortByDescending { it.id }

        val out = LinkedHashSet<String>()
        for (entry in entries) {
            for (line in entry.names.lineSequence()) {
                val host = line.trim().removePrefix("*.").lowercase().trimEnd('.')
                if (!isOfficialHost(host)) continue
                val labels = host.split('.')
                if (labels.size < 2) continue
                val rootHost = "${labels[labels.size - 2]}.${labels[labels.size - 1]}"
                val origin = originOf("https://$rootHost/") ?: continue
                out.add(origin)
                if (out.size >= limit) return out.toList()
            }
        }
        return out.toList()
    }

    internal suspend fun discoverFromCrtSh(
        crtUrl: String = CRT_SH_URL,
        clientOverride: OkHttpClient = client
    ): List<String> {
        return try {
            val request = Request.Builder()
                .url(crtUrl)
                .header("User-Agent", "Mozilla/5.0 FreeStreamTV")
                .header("Accept", "application/json")
                .build()
            clientOverride.newCall(request).await().use { response ->
                if (!response.isSuccessful) return emptyList()
                val body = response.body?.string().orEmpty()
                parseCrtShCandidates(body)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Errore crt.sh: ${e.message}")
            emptyList()
        }
    }

    /** Accetta esclusivamente origini HTTPS valide (mai cleartext HTTP). */
    internal fun originOf(url: String): String? {
        val uri = try {
            URI(url.trim())
        } catch (_: Exception) {
            return null
        }
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "https") return null
        val host = uri.host?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
        val port = if (uri.port != -1 && uri.port != 443) ":${uri.port}" else ""
        return "https://$host$port/"
    }

    private fun hostOf(url: String): String? = try {
        URI(url).host
    } catch (_: Exception) {
        null
    }

    private fun getLastDomain(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LAST_DOMAIN, null)
            ?.let { originOf(it) }
            ?.takeIf { isOfficialHost(hostOf(it)) }

    private fun saveDomain(context: Context, domain: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_LAST_DOMAIN, domain).apply()
    }

    // Esecuzione asincrona cancellabile: annullare la coroutine annulla anche la richiesta HTTP
    private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { runCatching { cancel() } }
        enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) {
                if (cont.isActive) cont.resume(response) else response.close()
            }

            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }
        })
    }
}
