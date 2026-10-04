package songlistener.io

import songlistener.core.LyricsSection
import songlistener.core.LyricsSnapshot
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LyricsStoreTest {
    private val store = LyricsStore(Files.createTempDirectory("lyrics").toFile())

    private fun snap(text: String, at: Long, songbook: String? = "PV 2001") = LyricsSnapshot(
        songKey = "368|Song", songLabel = "#368 Song", songbook = songbook, songbookCertain = true,
        number = 368, title = "Song", capturedAt = at, source = "churchpresenter",
        sections = listOf(LyricsSection("Куплет 1", "verse", listOf(text))),
    )

    @Test
    fun `a copy is written only when the words change`() {
        val first = store.saveIfChanged(snap("words", 1_790_000_000_000))
        assertTrue(first.changed)
        val again = store.saveIfChanged(snap("words", 1_790_000_600_000))
        assertFalse(again.changed)
        assertEquals(first.version, again.version)
        val edited = store.saveIfChanged(snap("new words", 1_790_001_000_000))
        assertTrue(edited.changed)
        assertEquals(2, store.versions("368|Song", "PV 2001").size)
        assertEquals(listOf("new words"), store.latest("368|Song", "PV 2001")!!.sections.single().lines)
    }

    @Test
    fun `each songbook keeps its own history`() {
        store.saveIfChanged(snap("2001 words", 1_790_000_000_000, "PV 2001"))
        store.saveIfChanged(snap("3300 words", 1_790_000_000_000, "PV 3300"))
        store.saveIfChanged(snap("unknown book", 1_790_000_000_000, null))
        assertEquals(1, store.versions("368|Song", "PV 2001").size)
        assertEquals(1, store.versions("368|Song", "PV 3300").size)
        assertEquals(listOf("unknown book"), store.latest("368|Song", null)!!.sections.single().lines)
    }
}
