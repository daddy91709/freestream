package com.freestream.tv

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContinueWatchingStoreTest {

    private class InMemorySharedPreferences : SharedPreferences {
        private val data = LinkedHashMap<String, Any?>()

        override fun getAll(): MutableMap<String, *> = HashMap(data)
        override fun getString(key: String?, defValue: String?): String? =
            (data[key] as? String) ?: defValue

        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            @Suppress("UNCHECKED_CAST")
            ((data[key] as? Set<String>)?.toMutableSet() ?: defValues)

        override fun getInt(key: String?, defValue: Int): Int = (data[key] as? Int) ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = (data[key] as? Long) ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = (data[key] as? Float) ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = (data[key] as? Boolean) ?: defValue
        override fun contains(key: String?): Boolean = data.containsKey(key)
        override fun edit(): SharedPreferences.Editor = Editor(data)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        private class Editor(private val target: MutableMap<String, Any?>) : SharedPreferences.Editor {
            private val pending = LinkedHashMap<String, Any?>()
            private var clearAll = false

            override fun putString(key: String?, value: String?): SharedPreferences.Editor = apply {
                if (key != null) pending[key] = value
            }

            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = apply {
                if (key != null) pending[key] = values?.toSet()
            }

            override fun putInt(key: String?, value: Int): SharedPreferences.Editor = apply {
                if (key != null) pending[key] = value
            }

            override fun putLong(key: String?, value: Long): SharedPreferences.Editor = apply {
                if (key != null) pending[key] = value
            }

            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = apply {
                if (key != null) pending[key] = value
            }

            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = apply {
                if (key != null) pending[key] = value
            }

            override fun remove(key: String?): SharedPreferences.Editor = apply {
                if (key != null) pending[key] = null
            }

            override fun clear(): SharedPreferences.Editor = apply { clearAll = true }

            override fun commit(): Boolean {
                apply()
                return true
            }

            override fun apply() {
                if (clearAll) target.clear()
                for ((k, v) in pending) {
                    if (v == null) target.remove(k) else target[k] = v
                }
                pending.clear()
                clearAll = false
            }
        }
    }

    @Test
    fun `saves movie and tv entries sorted by most recent update`() {
        var now = 1_700_000_000_000L
        val store = ContinueWatchingStore(InMemorySharedPreferences()) { now }

        assertTrue(
            store.upsertFromJson(
                JSONObject()
                    .put("type", "cwProgress")
                    .put("titleId", 66468)
                    .put("slug", "a-story-of-derry")
                    .put("name", "A Story of Derry")
                    .put("mediaType", "movie")
                    .put("locale", "it")
                    .put("titlePath", "/it/titles/66468-a-story-of-derry")
                    .put("watchPath", "/it/watch/66468")
                    .put("coverFilename", "cover1.webp")
                    .put("posterFilename", "poster1.webp")
                    .put("subIta", false)
                    .put("positionSec", 420)
                    .put("durationSec", 1620)
                    .put("ended", false)
                    .toString()
            )
        )

        now += 5_000L
        assertTrue(
            store.upsertFromJson(
                JSONObject()
                    .put("type", "cwProgress")
                    .put("titleId", 7166)
                    .put("slug", "percy-jackson")
                    .put("name", "Percy Jackson")
                    .put("mediaType", "tv")
                    .put("locale", "it")
                    .put("titlePath", "/it/titles/7166-percy-jackson/season-1")
                    .put("watchPath", "/it/watch/7166?e=45838")
                    .put("coverFilename", "cover2.webp")
                    .put("posterFilename", "poster2.webp")
                    .put("subIta", true)
                    .put("episodeId", 45838)
                    .put("seasonNumber", 1)
                    .put("episodeNumber", 1)
                    .put("episodeName", "Episodio 1")
                    .put("positionSec", 600)
                    .put("durationSec", 2280)
                    .put("ended", false)
                    .toString()
            )
        )

        val entries = store.getActiveEntries()
        assertEquals(2, entries.size)
        assertEquals(7166L, entries[0].titleId)
        assertEquals("tv", entries[0].type)
        assertEquals(45838L, entries[0].episodeId)
        assertEquals(1, entries[0].seasonNumber)
        assertEquals(1, entries[0].episodeNumber)
        assertEquals(66468L, entries[1].titleId)
        assertEquals("movie", entries[1].type)
        assertNull(entries[1].episodeId)
    }

    @Test
    fun `ignores positions below 15 seconds and preserves existing entry`() {
        var now = 1_700_000_000_000L
        val store = ContinueWatchingStore(InMemorySharedPreferences()) { now }

        assertFalse(
            store.upsertFromJson(
                JSONObject()
                    .put("titleId", 101)
                    .put("slug", "short-start")
                    .put("name", "Short Start")
                    .put("mediaType", "movie")
                    .put("titlePath", "/it/titles/101-short-start")
                    .put("positionSec", 14)
                    .put("durationSec", 3600)
                    .toString()
            )
        )
        assertTrue(store.getActiveEntries().isEmpty())

        assertTrue(
            store.upsertFromJson(
                JSONObject()
                    .put("titleId", 101)
                    .put("slug", "short-start")
                    .put("name", "Short Start")
                    .put("mediaType", "movie")
                    .put("titlePath", "/it/titles/101-short-start")
                    .put("positionSec", 300)
                    .put("durationSec", 3600)
                    .toString()
            )
        )

        now += 10_000L
        assertFalse(
            store.upsertFromJson(
                JSONObject()
                    .put("titleId", 101)
                    .put("slug", "short-start")
                    .put("name", "Short Start")
                    .put("mediaType", "movie")
                    .put("titlePath", "/it/titles/101-short-start")
                    .put("positionSec", 3)
                    .put("durationSec", 3600)
                    .toString()
            )
        )

        val entries = store.getActiveEntries()
        assertEquals(1, entries.size)
        assertEquals(300, entries[0].positionSec)
    }

    @Test
    fun `removes entry when finished via ended flag or completion threshold`() {
        val store = ContinueWatchingStore(InMemorySharedPreferences()) { 1_700_000_000_000L }

        // Film di 60 minuti (3600s): margine 5% = 180s -> completato a >= 3420s
        store.upsertFromJson(
            JSONObject()
                .put("titleId", 201)
                .put("slug", "movie-one")
                .put("name", "Movie One")
                .put("mediaType", "movie")
                .put("titlePath", "/it/titles/201-movie-one")
                .put("positionSec", 1000)
                .put("durationSec", 3600)
                .toString()
        )
        assertEquals(1, store.getActiveEntries().size)

        assertTrue(
            store.upsertFromJson(
                JSONObject()
                    .put("titleId", 201)
                    .put("slug", "movie-one")
                    .put("name", "Movie One")
                    .put("mediaType", "movie")
                    .put("titlePath", "/it/titles/201-movie-one")
                    .put("positionSec", 3420)
                    .put("durationSec", 3600)
                    .toString()
            )
        )
        assertTrue(store.getActiveEntries().isEmpty())

        // Episodio TV rimosso su ended=true anche se duration=0
        store.upsertFromJson(
            JSONObject()
                .put("titleId", 202)
                .put("slug", "show-two")
                .put("name", "Show Two")
                .put("mediaType", "tv")
                .put("titlePath", "/it/titles/202-show-two/season-1")
                .put("episodeId", 9001)
                .put("seasonNumber", 1)
                .put("episodeNumber", 1)
                .put("positionSec", 500)
                .put("durationSec", 2400)
                .toString()
        )
        assertEquals(1, store.getActiveEntries().size)

        assertTrue(
            store.upsertFromJson(
                JSONObject()
                    .put("titleId", 202)
                    .put("mediaType", "tv")
                    .put("episodeId", 9001)
                    .put("positionSec", 0)
                    .put("durationSec", 0)
                    .put("ended", true)
                    .toString()
            )
        )
        assertTrue(store.getActiveEntries().isEmpty())
    }

    @Test
    fun `prunes entries older than 7 days and keeps entries within 7 days`() {
        var now = 1_700_000_000_000L
        val store = ContinueWatchingStore(InMemorySharedPreferences()) { now }

        store.upsertFromJson(
            JSONObject()
                .put("titleId", 301)
                .put("slug", "old-movie")
                .put("name", "Old Movie")
                .put("mediaType", "movie")
                .put("titlePath", "/it/titles/301-old-movie")
                .put("positionSec", 500)
                .put("durationSec", 3000)
                .toString()
        )

        now += ContinueWatchingStore.RETENTION_MS - 60_000L
        store.upsertFromJson(
            JSONObject()
                .put("titleId", 302)
                .put("slug", "recent-movie")
                .put("name", "Recent Movie")
                .put("mediaType", "movie")
                .put("titlePath", "/it/titles/302-recent-movie")
                .put("positionSec", 600)
                .put("durationSec", 3000)
                .toString()
        )

        assertEquals(2, store.getActiveEntries().size)
        assertFalse(store.pruneExpired())

        // Superiamo i 7 giorni per il primo titolo ma non per il secondo
        now += 120_000L
        assertTrue(store.pruneExpired())
        val active = store.getActiveEntries()
        assertEquals(1, active.size)
        assertEquals(302L, active[0].titleId)
    }

    @Test
    fun `preserves stored images across watch progress updates and updates tv episode`() {
        var now = 1_700_000_000_000L
        val store = ContinueWatchingStore(InMemorySharedPreferences()) { now }

        store.upsertFromJson(
            JSONObject()
                .put("titleId", 401)
                .put("slug", "series-x")
                .put("name", "Series X")
                .put("mediaType", "tv")
                .put("titlePath", "/it/titles/401-series-x/season-1")
                .put("watchPath", "/it/watch/401?e=10")
                .put("coverFilename", "abc-cover.webp")
                .put("posterFilename", "abc-poster.webp")
                .put("episodeId", 10)
                .put("seasonNumber", 1)
                .put("episodeNumber", 1)
                .put("positionSec", 200)
                .put("durationSec", 2400)
                .toString()
        )

        // Nuovo episodio sulla pagina /watch/ senza immagini incluse
        now += 5_000L
        assertTrue(
            store.upsertFromJson(
                JSONObject()
                    .put("titleId", 401)
                    .put("slug", "series-x")
                    .put("name", "Series X")
                    .put("mediaType", "tv")
                    .put("titlePath", "/it/titles/401-series-x/season-2")
                    .put("watchPath", "/it/watch/401?e=25")
                    .put("coverFilename", "")
                    .put("posterFilename", "")
                    .put("episodeId", 25)
                    .put("seasonNumber", 2)
                    .put("episodeNumber", 3)
                    .put("positionSec", 120)
                    .put("durationSec", 2500)
                    .toString()
            )
        )

        val entries = store.getActiveEntries()
        assertEquals(1, entries.size)
        assertEquals("abc-cover.webp", entries[0].coverFilename)
        assertEquals("abc-poster.webp", entries[0].posterFilename)
        assertEquals("/it/titles/401-series-x/season-2", entries[0].titlePath)
        assertEquals(25L, entries[0].episodeId)
        assertEquals(2, entries[0].seasonNumber)
        assertEquals(3, entries[0].episodeNumber)
        assertEquals(120, entries[0].positionSec)
    }

    @Test
    fun `updateMetaFromJson backfills missing images without altering timestamp or position`() {
        var now = 1_700_000_000_000L
        val store = ContinueWatchingStore(InMemorySharedPreferences()) { now }

        store.upsertFromJson(
            JSONObject()
                .put("titleId", 501)
                .put("slug", "movie-no-img")
                .put("name", "Movie No Img")
                .put("mediaType", "movie")
                .put("titlePath", "/it/titles/501-movie-no-img")
                .put("positionSec", 777)
                .put("durationSec", 3000)
                .toString()
        )

        now += 60_000L
        assertTrue(
            store.updateMetaFromJson(
                JSONObject()
                    .put("titleId", 501)
                    .put("coverFilename", "filled-cover.webp")
                    .put("posterFilename", "filled-poster.webp")
                    .put("subIta", true)
                    .toString()
            )
        )

        val entry = store.getActiveEntries().single()
        assertEquals("filled-cover.webp", entry.coverFilename)
        assertEquals("filled-poster.webp", entry.posterFilename)
        assertTrue(entry.subIta)
        assertEquals(777, entry.positionSec)
        assertEquals(1_700_000_000_000L, entry.updatedAt)
    }

    @Test
    fun `sanitizes paths and filenames and enforces max entries cap`() {
        assertEquals(
            "/it/titles/7166-percy-jackson?foo=bar",
            ContinueWatchingStore.sanitizeRelativePath("https://streamingcommunityz.dad/it/titles/7166-percy-jackson?foo=bar")
        )
        assertEquals("", ContinueWatchingStore.sanitizeRelativePath("https://evil.example.com/it/titles/1"))
        assertEquals("", ContinueWatchingStore.sanitizeRelativePath("javascript:alert(1)"))
        assertEquals("", ContinueWatchingStore.sanitizeRelativePath("//evil.com/phish"))
        assertEquals("", ContinueWatchingStore.sanitizeRelativePath("/it/../secret"))

        assertEquals("abc_123-xyz.webp", ContinueWatchingStore.sanitizeImageFilename("abc_123-xyz.webp"))
        assertEquals("", ContinueWatchingStore.sanitizeImageFilename("../evil.webp"))
        assertEquals("", ContinueWatchingStore.sanitizeImageFilename("https://evil.com/a.webp"))

        var now = 1_700_000_000_000L
        val store = ContinueWatchingStore(InMemorySharedPreferences()) { now }
        for (i in 1..35) {
            now += 1_000L
            store.upsertFromJson(
                JSONObject()
                    .put("titleId", i.toLong())
                    .put("slug", "title-$i")
                    .put("name", "Title $i")
                    .put("mediaType", "movie")
                    .put("titlePath", "/it/titles/$i-title-$i")
                    .put("positionSec", 100)
                    .put("durationSec", 3000)
                    .toString()
            )
        }

        val entries = store.getActiveEntries()
        assertEquals(ContinueWatchingStore.MAX_ENTRIES, entries.size)
        assertEquals(35L, entries.first().titleId)
        assertEquals(6L, entries.last().titleId)

        val serialized = JSONArray(store.toJsonArrayString())
        assertEquals(ContinueWatchingStore.MAX_ENTRIES, serialized.length())
    }
}
