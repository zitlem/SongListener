package songlistener.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReviewFlagsTest {
    private val config = Config(steadyFrames = 48)
    private val matcher = Matcher(config)

    private fun labels(key: String, endReason: String = "off_screen", go: Double = 3.0, off: Double? = 60.0, end: Double = 63.0) =
        ClipLabels(songKey = key, songLabel = key, takeRecordedAt = 1, startedAt = 0, sampleRate = 16_000,
            goLiveSeconds = go, offScreenSeconds = off, endSeconds = end, endReason = endReason,
            sections = emptyList(), music = emptyList())

    private fun take(key: String, music: List<Synth.Chord>, at: Long, seed: Int) =
        Synth.take(key, music, seed = seed).let { SongTake(key, key, at, it.frames, it.sectionMarks) }

    @Test
    fun `a recording labelled A that sounds like B is flagged`() {
        val a1 = take("1|A", Synth.songA, 10, 1)
        val b1 = take("2|B", Synth.songB, 20, 2)
        val wrong = take("1|A", Synth.songB, 30, 3)   // the operator had A on screen; the band played B
        val check = ReviewFlags.check(labels("1|A"), wrong, listOf(a1, b1, wrong), matcher, config)
        assertEquals("2|B", check.rivalKey)
        assertEquals(2, check.severity, check.flags.toString())
        assertTrue(check.flags.any { it.code == "sounds_like" })
    }

    @Test
    fun `a right recording is not flagged, and a short replaced slide is`() {
        val a1 = take("1|A", Synth.songA, 10, 1)
        val b1 = take("2|B", Synth.songB, 20, 2)
        val a2 = take("1|A", Synth.songA, 30, 3)
        assertEquals(0, ReviewFlags.check(labels("1|A"), a2, listOf(a1, b1, a2), matcher, config).severity)
        val short = ReviewFlags.check(labels("1|A", "next_song", off = null, end = 50.0), a2, listOf(a1, b1, a2), matcher, config)
        assertTrue(short.flags.any { it.code == "short" }, short.flags.toString())
    }

    @Test
    fun `no take, or an uncertain edition, is noted`() {
        val c = ReviewFlags.check(labels("1|A"), null, emptyList(), matcher, config, editionUncertain = true)
        assertEquals(listOf("no_take", "edition"), c.flags.map { it.code })
    }
}
