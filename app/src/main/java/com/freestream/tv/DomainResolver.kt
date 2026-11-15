package com.freestream.tv

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object DomainResolver {
    private const val TAG = "DomainResolver"
    private const val DEFAULT_DOMAIN = "https://streamingcommunityz.boo/"

    // Lista di endpoint da testare
    private val SEED_URLS = listOf(
        "https://streamingcommunityz.boo/",
        "https://streaming-community.moe/",
        "https://streamingcommunity.broker/"
    )

    private val GITHUB_JSON_URLS = listOf(
        "https://raw.githubusercontent.com/MrZaKaRiA/streamingcommunity/main/.github/.domain/domains.json",
        "https://cdn.jsdelivr.net/gh/MrZaKaRiA/streamingcommunity@main/.github/.domain/domains.json"
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    suspend fun resolveActiveDomain(): String = withContext(Dispatchers.IO) {
        // 1. Prova a verificare prima i domini seme diretti
        for (candidate in SEED_URLS) {
            try {
                Log.d(TAG, "Test candidato: $candidate")
                val request = Request.Builder()
                    .url(candidate)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val finalUrl = response.request.url.toString()
                        Log.i(TAG, "Dominio valido trovato: $finalUrl")
                        return@withContext finalUrl
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Fallito tentativo su $candidate: ${e.message}")
            }
        }

        // 2. Prova a interrogare i file JSON su GitHub/CDN
        for (jsonUrl in GITHUB_JSON_URLS) {
            try {
                Log.d(TAG, "Tentativo lettura domains.json da: $jsonUrl")
                val request = Request.Builder()
                    .url(jsonUrl)
                    .header("User-Agent", "Mozilla/5.0 FreeStreamTV")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string()
                        if (!body.isNullOrEmpty()) {
                            val json = JSONObject(body)
                            if (json.has("streamingcommunity")) {
                                val sc = json.getJSONObject("streamingcommunity")
                                val fullUrl = sc.optString("full_url", "")
                                if (fullUrl.isNotEmpty()) {
                                    Log.i(TAG, "Dominio ricavato da JSON: $fullUrl")
                                    return@withContext fullUrl
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Errore recupero JSON: ${e.message}")
            }
        }

        // 3. Fallback di sicurezza garantito
        Log.i(TAG, "Uso fallback predefinito: $DEFAULT_DOMAIN")
        return@withContext DEFAULT_DOMAIN
    }
}
