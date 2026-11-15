package com.freestream.tv

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object DomainResolver {
    private const val TAG = "DomainResolver"
    private const val PREFS = "domain_resolver"
    private const val KEY_LAST_DOMAIN = "last_domain"
    private const val RACE_TIMEOUT_MS = 9000L
    private const val PEEK_BYTES = 64L * 1024
    private const val OFFICIAL_LABEL = "streamingcommunityz"

    // Unica fonte di verità remota: domains.json mantenuto da GitHub Actions in questo repository
    private val GITHUB_JSON_URLS = listOf(
        "https://raw.githubusercontent.com/daddy91709/freestream/main/domains.json",
        "https://cdn.jsdelivr.net/gh/daddy91709/freestream@main/domains.json"
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .callTimeout(7, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    /**
     * Mette in gara esclusivamente l'ultimo dominio funzionante in cache e il dominio pubblicato
     * nel nostro `domains.json` (aggiornato da GitHub Actions).
     * Vince il primo che risponde con una pagina valida del sito; in caso di timeout del probe,
     * usa il dominio del nostro JSON o quello in cache.
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

        val fallback = jsonFallback.get() ?: cached
        if (fallback != null) {
            Log.i(TAG, "Probe non concluso entro il timeout, uso fallback fidato: $fallback")
            saveDomain(context, fallback)
            return fallback
        }

        throw IOException("Nessun dominio attivo disponibile (né da GitHub né in cache)")
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

        // Legge in parallelo il nostro domains.json; il dominio trovato entra subito nella gara
        val jsonDomains = Channel<String?>(Channel.UNLIMITED)
        var jsonPending = jsonUrls.size
        jsonUrls.forEach { url ->
            launch { jsonDomains.send(fetchJsonDomain(url, clientOverride)) }
        }

        var winner: String? = null
        while (winner == null && (pending > 0 || jsonPending > 0)) {
            kotlinx.coroutines.selects.select<Unit> {
                if (pending > 0) results.onReceive { r ->
                    pending--
                    if (r != null) winner = r
                }
                if (jsonPending > 0) jsonDomains.onReceive { d ->
                    jsonPending--
                    if (d != null) {
                        val clean = originOf(d)
                        if (clean != null && isOfficialHost(hostOf(clean))) {
                            jsonFallback.compareAndSet(null, clean)
                            launchProbe(clean)
                        }
                    }
                }
            }
        }
        // Annulla tutte le richieste ancora in corso
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

    internal suspend fun fetchJsonDomain(jsonUrl: String, clientOverride: OkHttpClient = client): String? {
        return try {
            val request = Request.Builder()
                .url(jsonUrl)
                .header("User-Agent", "Mozilla/5.0 FreeStreamTV")
                .header("Cache-Control", "no-cache")
                .build()
            clientOverride.newCall(request).await().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string().orEmpty()
                val sc = JSONObject(body).optJSONObject("streamingcommunity") ?: return null
                sc.optString("full_url", "").takeIf { it.isNotEmpty() }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Errore JSON $jsonUrl: ${e.message}")
            null
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
