package songlistener.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LyricsTest {
    @Test
    fun `song names put the number first`() {
        assertEquals("#368 Ты познал ли силу крови", SongKeys.label(368, "Ты познал ли силу крови"))
        assertEquals("Вера, вера", SongKeys.label(0, "Вера, вера"))
        assertEquals("#12 Hark", SongKeys.labelOf("12|Hark"))
        assertEquals("Untitled", SongKeys.labelOf("0|Untitled"))
        assertEquals("#7 A | B", SongKeys.labelOf("7|A | B"))
        assertNull(SongKeys.labelOf("not a key"))
    }

    @Test
    fun `headings follow ChurchPresenter's rule`() {
        assertTrue(Lyrics.isHeader("[Куплет 1]"))
        assertTrue(Lyrics.isHeader("{Припев}"))
        assertFalse(Lyrics.isHeader("[---]"), "slide break")
        assertFalse(Lyrics.isHeader("[background: gradient]"), "background directive")
        assertFalse(Lyrics.isHeader("[Am]"), "a chord")
        assertFalse(Lyrics.isHeader("[Cm] [Bb] [Ab] [G]"), "a line of chords")
        assertFalse(Lyrics.isHeader("Glory [to] God"))
    }

    @Test
    fun `lyric lines become sections of words`() {
        val sections = Lyrics.sections(listOf(
            "Before any heading",
            "",
            "[Куплет 1]",
            "[G]Ты по[D]знал ли",
            "[---]",
            "силу крови",
            "[background: gradient]",
            "{Припев}",
            "[Am] [C]",
            "Есть сила",
        ))
        assertEquals(
            listOf(
                LyricsSection("", "verse", listOf("Before any heading")),
                LyricsSection("Куплет 1", "verse", listOf("Ты познал ли", "силу крови")),
                LyricsSection("Припев", "chorus", listOf("Есть сила")),
            ),
            sections,
        )
    }

    @Test
    fun `the same words hash the same from the API and from the cache`() {
        val fromCache = Lyrics.sections(listOf("[Куплет 1]", "Ты познал ли", "{Припев}", "Есть сила"))
        val fromApi = Lyrics.fromApi(listOf("verse" to listOf("Ты познал ли"), "chorus" to listOf("[C]Есть сила")))
        assertEquals(Lyrics.hash(fromCache), Lyrics.hash(fromApi))
        val edited = Lyrics.fromApi(listOf("verse" to listOf("Ты познал ли"), "chorus" to listOf("Есть сила!")))
        assertTrue(Lyrics.hash(edited) != Lyrics.hash(fromApi))
    }
}
