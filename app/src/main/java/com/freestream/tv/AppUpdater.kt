package com.freestream.tv

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.util.concurrent.TimeUnit

object AppUpdater {
    private const val TAG = "AppUpdater"
    private const val PREFS = "app_updater"
    private const val KEY_SKIPPED_VERSION = "skipped_version"
    private const val LATEST_RELEASE_URL = "https://api.github.com/repos/daddy91709/freestream/releases/latest"

    data class ReleaseInfo(
        val versionName: String,
        val downloadUrl: String,
        val releaseNotes: String
    )

    private val checkClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .build()

    private val downloadClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.MINUTES)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    suspend fun checkForUpdate(
        context: Context,
        currentVersion: String,
        clientOverride: OkHttpClient = checkClient
    ): ReleaseInfo? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(LATEST_RELEASE_URL)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "FreeStreamTV/$currentVersion")
                .build()
            clientOverride.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string().orEmpty()
                val release = parseLatestRelease(body, currentVersion) ?: return@withContext null
                val skipped = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(KEY_SKIPPED_VERSION, null)
                if (skipped == release.versionName) return@withContext null
                release
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Controllo aggiornamenti fallito: ${e.message}")
            null
        }
    }

    fun markVersionSkipped(context: Context, versionName: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SKIPPED_VERSION, versionName.removePrefix("v").removePrefix("V").trim())
            .apply()
    }

    suspend fun downloadAndInstall(
        context: Context,
        release: ReleaseInfo,
        clientOverride: OkHttpClient = downloadClient
    ): Boolean {
        if (!isTrustedReleaseUrl(release.downloadUrl)) return false

        val apkFile = withContext(Dispatchers.IO) {
            try {
                val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
                updatesDir.listFiles()?.forEach { old ->
                    if (old.isFile) old.delete()
                }

                val safeVersion = release.versionName.replace(Regex("[^0-9A-Za-z._-]"), "_")
                val partFile = File(updatesDir, "freestream-$safeVersion.apk.part")
                val targetFile = File(updatesDir, "freestream-$safeVersion.apk")

                val request = Request.Builder()
                    .url(release.downloadUrl)
                    .header("User-Agent", "FreeStreamTV")
                    .build()

                clientOverride.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext null
                    val body = response.body ?: return@withContext null
                    body.byteStream().use { input ->
                        partFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                }

                if (!partFile.exists() || partFile.length() == 0L) {
                    partFile.delete()
                    return@withContext null
                }
                if (targetFile.exists()) targetFile.delete()
                if (!partFile.renameTo(targetFile)) {
                    partFile.copyTo(targetFile, overwrite = true)
                    partFile.delete()
                }
                targetFile
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Download APK fallito: ${e.message}")
                null
            }
        } ?: return false

        return withContext(Dispatchers.Main) {
            try {
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    apkFile
                )
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                true
            } catch (e: Exception) {
                Log.w(TAG, "Avvio installer fallito: ${e.message}")
                false
            }
        }
    }

    internal fun parseLatestRelease(jsonBody: String, currentVersion: String): ReleaseInfo? {
        val json = try {
            JSONObject(jsonBody)
        } catch (_: Exception) {
            return null
        }

        if (json.optBoolean("draft", false) || json.optBoolean("prerelease", false)) {
            return null
        }

        val tagName = json.optString("tag_name", "").trim()
        val versionName = tagName.removePrefix("v").removePrefix("V").trim()
        if (!isNewerVersion(currentVersion, versionName)) return null

        val assets = json.optJSONArray("assets") ?: return null
        var downloadUrl: String? = null
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            val name = asset.optString("name", "").trim()
            val candidateUrl = asset.optString("browser_download_url", "").trim()
            if (name.endsWith(".apk", ignoreCase = true) && isTrustedReleaseUrl(candidateUrl)) {
                downloadUrl = candidateUrl
                break
            }
        }
        val finalUrl = downloadUrl ?: return null
        val notes = json.optString("body", "").trim()
        return ReleaseInfo(versionName = versionName, downloadUrl = finalUrl, releaseNotes = notes)
    }

    internal fun isNewerVersion(currentVersion: String, candidateVersion: String): Boolean {
        val currentParts = parseVersionParts(currentVersion) ?: return false
        val candidateParts = parseVersionParts(candidateVersion) ?: return false
        val maxLen = maxOf(currentParts.size, candidateParts.size)
        for (i in 0 until maxLen) {
            val c = currentParts.getOrElse(i) { 0 }
            val n = candidateParts.getOrElse(i) { 0 }
            if (n > c) return true
            if (n < c) return false
        }
        return false
    }

    internal fun isTrustedReleaseUrl(rawUrl: String?): Boolean {
        val raw = rawUrl?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        val uri = try {
            URI(raw)
        } catch (_: Exception) {
            return false
        }
        if (uri.scheme?.lowercase() != "https") return false
        val host = uri.host?.lowercase()?.trimEnd('.') ?: return false
        return host == "github.com" ||
            host.endsWith(".github.com") ||
            host == "githubusercontent.com" ||
            host.endsWith(".githubusercontent.com")
    }

    private fun parseVersionParts(version: String): List<Int>? {
        val cleaned = version.trim()
            .removePrefix("v")
            .removePrefix("V")
            .substringBefore('-')
            .substringBefore('+')
            .trim()
        if (cleaned.isEmpty()) return null
        val segments = cleaned.split('.')
        val nums = ArrayList<Int>(segments.size)
        for (seg in segments) {
            val value = seg.toIntOrNull() ?: return null
            if (value < 0) return null
            nums.add(value)
        }
        return nums
    }
}
