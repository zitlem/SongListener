package songlistener.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IntroFilterTest {
    private val fps = Config().featuresPerSecond

    /** A take of song A with [introSeconds] of [intro] music held before its slide went up. */
    private fun take(intro: List<Synth.Chord>, introSeconds: Double): SongTake {
        val held = Synth.chroma(Synth.render(intro, introSeconds, seed = 5))
        val body = Synth.chroma(Synth.render(Synth.songA, 90.0, seed = 6))
        return SongTake("1|A", "A", 1, held + body, listOf(SectionMark(0, SectionMark.INTRO), SectionMark(held.size, 0), SectionMark(held.size + 40, 1)))
    }

    @Test
    fun `match keeps an intro that is the song and drops another performance`() {
        val config = Config(introMode = "match")
        val real = take(Synth.songA, 20.0)
        assertEquals(IntroFilter.introFrames(real), IntroFilter.introFrames(IntroFilter.apply(real, config)))

        val other = take(Synth.songC, 60.0)
        val trimmed = IntroFilter.apply(other, config)
        assertEquals(0, IntroFilter.introFrames(trimmed))
        assertEquals(other.frames.size - IntroFilter.introFrames(other), trimmed.frames.size)
        // Section marks move with the frames.
        assertEquals(listOf(SectionMark(0, 0), SectionMark(40, 1)), trimmed.sectionMarks)
    }

    @Test
    fun `other music before a real intro is cut where the song starts`() {
        val held = Synth.chroma(Synth.render(Synth.songC, 40.0, seed = 5)) + Synth.chroma(Synth.render(Synth.songA, 24.0, seed = 7))
        val body = Synth.chroma(Synth.render(Synth.songA, 90.0, seed = 6))
        val t = SongTake("1|A", "A", 1, held + body, listOf(SectionMark(0, SectionMark.INTRO), SectionMark(held.size, 0)))
        val kept = IntroFilter.introFrames(IntroFilter.apply(t, Config(introMode = "match"))) / fps
        assertTrue(kept in 12.0..36.0, "kept $kept s of 64 s")
    }

    @Test
    fun `keep and drop do as they say, and a take without intro is untouched`() {
        val t = take(Synth.songC, 30.0)
        assertTrue(IntroFilter.apply(t, Config(introMode = "keep")) === t)
        assertEquals(0, IntroFilter.introFrames(IntroFilter.apply(t, Config(introMode = "drop"))))
        val plain = Synth.take("1|A", Synth.songA)
        assertTrue(IntroFilter.apply(plain, Config(introMode = "match")) === plain)
    }
}
