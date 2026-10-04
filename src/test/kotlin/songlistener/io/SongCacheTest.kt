package songlistener.io

import songlistener.core.Clip
import songlistener.core.ClipLabels
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SongCacheTest {
    private val dir = Files.createTempDirectory("cache").toFile()
    private val file = File(dir, "song_cache.json").also {
        it.writeText("""
            {"storageDirectory":"/songs","songs":[],"cachedSongs":[
              {"song":{"number":"0368","title":"Ты познал","songbook":"PV 2001","lyrics":["[Куплет 1]","Слова 2001"]},"lastModified":1790000000000,"fileSize":1},
              {"song":{"number":"0368","title":"Ты познал","songbook":"PV 3300","lyrics":["[Куплет 1]","Слова 3300"]},"lastModified":1700000000000,"fileSize":1},
              {"song":{"number":"0001","title":"Same","songbook":"PV 2001","lyrics":["Одно"]},"lastModified":1,"fileSize":1},
              {"song":{"number":"0001","title":"Same","songbook":"PV 3300","lyrics":["Одно"]},"lastModified":1,"fileSize":1},
              {"song":{"number":"","title":"Без номера","songbook":"Other","lyrics":["Текст"]},"lastModified":1,"fileSize":1}
            ]}
        """.trimIndent())
    }
    private val cache = SongCache.load(file)

    @Test
    fun `zero-padded numbers match the live key`() {
        val found = assertIs<SongCache.Lookup.Found>(cache.lookup("368|Ты познал", "PV 2001"))
        assertEquals(listOf("Слова 2001"), cache.snapshot(found.entry, true, 0).sections.single().lines)
        assertIs<SongCache.Lookup.Found>(cache.lookup("0|Без номера", null))
        assertEquals(SongCache.Lookup.Missing, cache.lookup("999|Nope", null))
    }

    @Test
    fun `two editions with different words need the songbook`() {
        val ambiguous = assertIs<SongCache.Lookup.Ambiguous>(cache.lookup("368|Ты познал", null))
        assertEquals(listOf("PV 2001", "PV 3300"), ambiguous.songbooks)
        // Same words in both editions: no need to know which.
        val same = assertIs<SongCache.Lookup.Found>(cache.lookup("1|Same", null))
        assertEquals(false, same.songbookCertain)
    }

    @Test
    fun `an imported recording is stamped with its words, and edits after the service are noted`() {
        val store = LyricsStore(File(dir, "lyrics"))
        val clip = Clip(ShortArray(0), ClipLabels(
            songKey = "368|Ты познал", songLabel = "#368 Ты познал", takeRecordedAt = 0, startedAt = 1_750_000_000_000,
            sampleRate = 16_000, goLiveSeconds = 3.0, endSeconds = 100.0, endReason = "off_screen",
            sections = emptyList(), music = emptyList(), songbook = "PV 2001",
        ))
        val (stamped, note) = attachLyrics(clip, cache, store, save = true)
        assertTrue(stamped.labels.lyricsVersion != null)
        assertTrue(note!!.contains("after this service"), note)
        assertEquals(1, store.versions("368|Ты познал", "PV 2001").size)

        val unknownBook = clip.copyLabels(songbook = null)
        val (unstamped, why) = attachLyrics(unknownBook, cache, store, save = true)
        assertNull(unstamped.labels.lyricsVersion)
        assertTrue(why!!.contains("songbook unknown"), why)
    }

    private fun Clip.copyLabels(songbook: String?) = Clip(samples, labels.copy(songbook = songbook))
}
