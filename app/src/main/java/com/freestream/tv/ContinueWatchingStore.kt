package com.freestream.tv

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

class ContinueWatchingStore(
    private val prefs: SharedPreferences,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    data class WatchEntry(
        val titleId: Long,
        val slug: String,
        val name: String,
        val type: String,
        val locale: String,
        val titlePath: String,
        val watchPath: String,
        val coverFilename: String,
        val posterFilename: String,
        val subIta: Boolean,
        val episodeId: Long?,
        val seasonNumber: Int?,
        val episodeNumber: Int?,
        val episodeName: String?,
        val positionSec: Int,
        val durationSec: Int,
        val updatedAt: Long
    )

    @Synchronized
    fun pruneExpired(): Boolean {
        val (active, removed) = loadAndPruneLocked()
        if (removed) {
            saveLocked(active)
        }
        return removed
    }

    @Synchronized
    fun getActiveEntries(): List<WatchEntry> {
        val (active, _) = loadAndPruneLocked()
        return active.sortedByDescending { it.updatedAt }
    }

    @Synchronized
    fun toJsonArrayString(): String {
        val array = JSONArray()
        for (entry in getActiveEntries()) {
            array.put(entryToJson(entry))
        }
        return array.toString()
    }

    @Synchronized
    fun upsertFromJson(rawJson: String): Boolean {
        val json = try {
            JSONObject(rawJson)
        } catch (_: Exception) {
            return false
        }

        val titleId = json.optLong("titleId", -1L)
        if (titleId <= 0L) return false

        val positionSec = json.optInt("positionSec", -1)
        val durationSec = json.optInt("durationSec", 0)
        val ended = json.optBoolean("ended", false)
        if (positionSec < 0) return false
        if (!ended && durationSec <= MIN_DURATION_SEC) return false

        val (active, expiredRemoved) = loadAndPruneLocked()
        val mutable = active.toMutableList()
        val existingIdx = mutable.indexOfFirst { it.titleId == titleId }
        val existing = if (existingIdx >= 0) mutable[existingIdx] else null

        val incomingEpisodeId = optPositiveLong(json, "episodeId")

        if (isCompleted(positionSec, durationSec, ended)) {
            val matchesExisting = existing != null && (
                existing.type != "tv" ||
                    incomingEpisodeId == null ||
                    existing.episodeId == null ||
                    existing.episodeId == incomingEpisodeId
                )
            if (matchesExisting) {
                mutable.removeAt(existingIdx)
                saveLocked(mutable)
                return true
            }
            if (expiredRemoved) {
                saveLocked(mutable)
                return true
            }
            return false
        }

        if (positionSec < MIN_POSITION_SEC) {
            if (expiredRemoved) {
                saveLocked(mutable)
                return true
            }
            return false
        }

        val rawType = json.optString("mediaType", "")
            .ifBlank { json.optString("type", "") }
            .trim()
            .lowercase()
        val type = when {
            rawType == "tv" -> "tv"
            rawType == "movie" -> "movie"
            incomingEpisodeId != null -> "tv"
            existing != null -> existing.type
            else -> "movie"
        }

        val locale = sanitizeLocale(json.optString("locale", ""))
            .ifBlank { existing?.locale ?: DEFAULT_LOCALE }
        val slug = json.optString("slug", "").trim().take(MAX_TEXT_LEN)
            .ifBlank { existing?.slug.orEmpty() }
        val name = json.optString("name", "").trim().take(MAX_TEXT_LEN)
            .ifBlank { existing?.name.orEmpty() }

        val episodeId = if (type == "tv") incomingEpisodeId ?: existing?.episodeId else null
        val sameEpisode = existing != null &&
            existing.type == "tv" &&
            (episodeId == null || existing.episodeId == null || existing.episodeId == episodeId)

        val seasonNumber = if (type == "tv") {
            optPositiveInt(json, "seasonNumber") ?: if (sameEpisode) existing?.seasonNumber else null
        } else {
            null
        }
        val episodeNumber = if (type == "tv") {
            optPositiveInt(json, "episodeNumber") ?: if (sameEpisode) existing?.episodeNumber else null
        } else {
            null
        }
        val episodeName = if (type == "tv") {
            json.optString("episodeName", "").trim().take(MAX_TEXT_LEN)
                .takeIf { it.isNotEmpty() }
                ?: if (sameEpisode) existing?.episodeName else null
        } else {
            null
        }

        val incomingTitlePath = sanitizeRelativePath(json.optString("titlePath", ""))
        val titlePath = incomingTitlePath
            .ifBlank { existing?.titlePath.orEmpty() }
            .ifBlank { buildFallbackTitlePath(locale, titleId, slug, type, seasonNumber) }

        val incomingWatchPath = sanitizeRelativePath(json.optString("watchPath", ""))
        val watchPath = incomingWatchPath
            .ifBlank { existing?.watchPath.orEmpty() }
            .ifBlank { buildFallbackWatchPath(locale, titleId, type, episodeId) }

        if (name.isEmpty() || titlePath.isEmpty()) {
            if (expiredRemoved) {
                saveLocked(mutable)
                return true
            }
            return false
        }

        val coverFilename = sanitizeImageFilename(json.optString("coverFilename", ""))
            .ifBlank { existing?.coverFilename.orEmpty() }
        val posterFilename = sanitizeImageFilename(json.optString("posterFilename", ""))
            .ifBlank { existing?.posterFilename.orEmpty() }
        val subIta = if (json.has("subIta")) {
            json.optBoolean("subIta", false)
        } else {
            existing?.subIta ?: false
        }

        val updated = WatchEntry(
            titleId = titleId,
            slug = slug,
            name = name,
            type = type,
            locale = locale,
            titlePath = titlePath,
            watchPath = watchPath,
            coverFilename = coverFilename,
            posterFilename = posterFilename,
            subIta = subIta,
            episodeId = episodeId,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,
            episodeName = episodeName,
            positionSec = positionSec,
            durationSec = durationSec,
            updatedAt = clock()
        )

        if (existingIdx >= 0) {
            mutable.removeAt(existingIdx)
        }
        mutable.add(0, updated)
        val trimmed = mutable.sortedByDescending { it.updatedAt }.take(MAX_ENTRIES)
        saveLocked(trimmed)
        return true
    }

    @Synchronized
    fun updateMetaFromJson(rawJson: String): Boolean {
        val json = try {
            JSONObject(rawJson)
        } catch (_: Exception) {
            return false
        }
        val titleId = json.optLong("titleId", -1L)
        if (titleId <= 0L) return false

        val (active, expiredRemoved) = loadAndPruneLocked()
        val mutable = active.toMutableList()
        val idx = mutable.indexOfFirst { it.titleId == titleId }
        if (idx < 0) {
            if (expiredRemoved) {
                saveLocked(mutable)
                return true
            }
            return false
        }

        val existing = mutable[idx]
        val incomingCover = sanitizeImageFilename(json.optString("coverFilename", ""))
        val incomingPoster = sanitizeImageFilename(json.optString("posterFilename", ""))
        val incomingTitlePath = sanitizeRelativePath(json.optString("titlePath", ""))
        val incomingName = json.optString("name", "").trim().take(MAX_TEXT_LEN)
        val incomingSlug = json.optString("slug", "").trim().take(MAX_TEXT_LEN)
        val incomingSubIta = json.has("subIta") && json.optBoolean("subIta", false)

        val newCover = if (existing.coverFilename.isBlank() && incomingCover.isNotEmpty()) incomingCover else existing.coverFilename
        val newPoster = if (existing.posterFilename.isBlank() && incomingPoster.isNotEmpty()) incomingPoster else existing.posterFilename
        val newTitlePath = if (existing.titlePath.isBlank() && incomingTitlePath.isNotEmpty()) incomingTitlePath else existing.titlePath
        val newName = if (existing.name.isBlank() && incomingName.isNotEmpty()) incomingName else existing.name
        val newSlug = if (existing.slug.isBlank() && incomingSlug.isNotEmpty()) incomingSlug else existing.slug
        val newSubIta = existing.subIta || incomingSubIta

        val changed = newCover != existing.coverFilename ||
            newPoster != existing.posterFilename ||
            newTitlePath != existing.titlePath ||
            newName != existing.name ||
            newSlug != existing.slug ||
            newSubIta != existing.subIta

        if (!changed) {
            if (expiredRemoved) {
                saveLocked(mutable)
                return true
            }
            return false
        }

        mutable[idx] = existing.copy(
            coverFilename = newCover,
            posterFilename = newPoster,
            titlePath = newTitlePath,
            name = newName,
            slug = newSlug,
            subIta = newSubIta
        )
        saveLocked(mutable)
        return true
    }

    private fun loadAndPruneLocked(): Pair<List<WatchEntry>, Boolean> {
        val raw = prefs.getString(KEY_ENTRIES, null) ?: return Pair(emptyList(), false)
        val array = try {
            JSONArray(raw)
        } catch (_: Exception) {
            return Pair(emptyList(), true)
        }
        val now = clock()
        val entries = ArrayList<WatchEntry>(array.length())
        var dropped = false
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i)
            val entry = obj?.let { jsonToEntry(it) }
            if (entry == null || now - entry.updatedAt > RETENTION_MS || entry.updatedAt > now + FUTURE_SKEW_MS) {
                dropped = true
            } else {
                entries.add(entry)
            }
        }
        return Pair(entries, dropped)
    }

    private fun saveLocked(entries: List<WatchEntry>) {
        val array = JSONArray()
        for (entry in entries) {
            array.put(entryToJson(entry))
        }
        prefs.edit().putString(KEY_ENTRIES, array.toString()).apply()
    }

    private fun entryToJson(entry: WatchEntry): JSONObject = JSONObject().apply {
        put("titleId", entry.titleId)
        put("slug", entry.slug)
        put("name", entry.name)
        put("type", entry.type)
        put("locale", entry.locale)
        put("titlePath", entry.titlePath)
        put("watchPath", entry.watchPath)
        put("coverFilename", entry.coverFilename)
        put("posterFilename", entry.posterFilename)
        put("subIta", entry.subIta)
        put("episodeId", entry.episodeId ?: JSONObject.NULL)
        put("seasonNumber", entry.seasonNumber ?: JSONObject.NULL)
        put("episodeNumber", entry.episodeNumber ?: JSONObject.NULL)
        put("episodeName", entry.episodeName ?: JSONObject.NULL)
        put("positionSec", entry.positionSec)
        put("durationSec", entry.durationSec)
        put("updatedAt", entry.updatedAt)
    }

    private fun jsonToEntry(obj: JSONObject): WatchEntry? {
        val titleId = obj.optLong("titleId", -1L)
        if (titleId <= 0L) return null
        val name = obj.optString("name", "").trim().take(MAX_TEXT_LEN)
        val titlePath = sanitizeRelativePath(obj.optString("titlePath", ""))
        val positionSec = obj.optInt("positionSec", -1)
        val durationSec = obj.optInt("durationSec", -1)
        val updatedAt = obj.optLong("updatedAt", -1L)
        if (name.isEmpty() || titlePath.isEmpty() || positionSec < MIN_POSITION_SEC || durationSec <= MIN_DURATION_SEC || updatedAt <= 0L) {
            return null
        }
        val type = if (obj.optString("type", "").trim().equals("tv", ignoreCase = true)) "tv" else "movie"
        return WatchEntry(
            titleId = titleId,
            slug = obj.optString("slug", "").trim().take(MAX_TEXT_LEN),
            name = name,
            type = type,
            locale = sanitizeLocale(obj.optString("locale", "")).ifBlank { DEFAULT_LOCALE },
            titlePath = titlePath,
            watchPath = sanitizeRelativePath(obj.optString("watchPath", "")),
            coverFilename = sanitizeImageFilename(obj.optString("coverFilename", "")),
            posterFilename = sanitizeImageFilename(obj.optString("posterFilename", "")),
            subIta = obj.optBoolean("subIta", false),
            episodeId = optPositiveLong(obj, "episodeId"),
            seasonNumber = optPositiveInt(obj, "seasonNumber"),
            episodeNumber = optPositiveInt(obj, "episodeNumber"),
            episodeName = obj.optString("episodeName", "").trim().take(MAX_TEXT_LEN).takeIf { it.isNotEmpty() },
            positionSec = positionSec,
            durationSec = durationSec,
            updatedAt = updatedAt
        )
    }

    private fun buildFallbackTitlePath(
        locale: String,
        titleId: Long,
        slug: String,
        type: String,
        seasonNumber: Int?
    ): String {
        if (slug.isBlank()) return ""
        val base = "/$locale/titles/$titleId-$slug"
        return if (type == "tv" && seasonNumber != null && seasonNumber > 0) {
            "$base/season-$seasonNumber"
        } else {
            base
        }
    }

    private fun buildFallbackWatchPath(
        locale: String,
        titleId: Long,
        type: String,
        episodeId: Long?
    ): String {
        return if (type == "tv" && episodeId != null) {
            "/$locale/watch/$titleId?e=$episodeId"
        } else {
            "/$locale/watch/$titleId"
        }
    }

    private fun sanitizeLocale(raw: String): String {
        val cleaned = raw.trim().lowercase().take(10)
        return if (LOCALE_REGEX.matches(cleaned)) cleaned else ""
    }

    private fun optPositiveLong(json: JSONObject, key: String): Long? {
        if (!json.has(key) || json.isNull(key)) return null
        val value = json.optLong(key, -1L)
        return if (value > 0L) value else null
    }

    private fun optPositiveInt(json: JSONObject, key: String): Int? {
        if (!json.has(key) || json.isNull(key)) return null
        val value = json.optInt(key, -1)
        return if (value > 0) value else null
    }

    companion object {
        const val PREFS_NAME = "continue_watching"
        const val KEY_ENTRIES = "entries_v1"
        const val RETENTION_MS = 7L * 24L * 60L * 60L * 1000L
        const val MIN_POSITION_SEC = 15
        const val MIN_DURATION_SEC = 60
        const val MAX_ENTRIES = 30
        private const val FUTURE_SKEW_MS = 24L * 60L * 60L * 1000L
        private const val MAX_PATH_LEN = 512
        private const val MAX_TEXT_LEN = 200
        private const val MAX_FILENAME_LEN = 128
        private const val DEFAULT_LOCALE = "it"
        private val FILENAME_REGEX = Regex("^[A-Za-z0-9._-]+$")
        private val LOCALE_REGEX = Regex("^[a-z]{2}(?:-[a-z0-9]{2,4})?$")

        fun from(context: Context): ContinueWatchingStore {
            return ContinueWatchingStore(
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            )
        }

        internal fun isCompleted(positionSec: Int, durationSec: Int, ended: Boolean): Boolean {
            if (ended) return true
            if (durationSec <= MIN_DURATION_SEC) return false
            val margin = (durationSec * 0.05).toInt().coerceIn(30, 180)
            return positionSec >= (durationSec - margin)
        }

        internal fun sanitizeRelativePath(raw: String?): String {
            val trimmed = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return ""
            if (trimmed.length > MAX_PATH_LEN) return ""
            if (trimmed.any { it.code < 32 || it.code == 127 }) return ""
            if (trimmed.contains("..")) return ""

            val lower = trimmed.lowercase()
            if (lower.startsWith("javascript:") || lower.startsWith("data:") || lower.startsWith("vbscript:")) {
                return ""
            }
            if (trimmed.startsWith("//")) return ""

            if (lower.startsWith("https://")) {
                val uri = try {
                    URI(trimmed)
                } catch (_: Exception) {
                    return ""
                }
                if (!DomainResolver.isOfficialHost(uri.host)) return ""
                val path = uri.rawPath.orEmpty().ifBlank { "/" }
                if (!path.startsWith("/") || path.startsWith("//") || path.contains("..")) return ""
                val query = uri.rawQuery
                val combined = if (!query.isNullOrBlank()) "$path?$query" else path
                return if (combined.length <= MAX_PATH_LEN) combined else ""
            }

            if (lower.startsWith("http://") || trimmed.contains("://")) return ""
            return if (trimmed.startsWith("/")) trimmed else ""
        }

        internal fun sanitizeImageFilename(raw: String?): String {
            val trimmed = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return ""
            if (trimmed.length > MAX_FILENAME_LEN || trimmed.contains("..")) return ""
            return if (FILENAME_REGEX.matches(trimmed)) trimmed else ""
        }
    }
}
