package songlistener.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConfirmedPartsTest {
    private val config = Config(confirmedParts = true)

    private fun take(at: Long, frames: List<FloatArray>, marks: List<SectionMark> = emptyList()) = SongTake("1|A", "A", at, frames, marks)
    private fun chroma(music: List<Synth.Chord>, seconds: Double, seed: Int) = Synth.chroma(Synth.render(music, seconds, seed = seed))

    @Test
    fun `a stretch heard in only one performance is left out of the search`() {
        val a1 = chroma(Synth.songA, 40.0, 1)
        val solo = chroma(Synth.songC, 40.0, 2)      // only in this performance
        val a2 = chroma(Synth.songA, 40.0, 3)
        val first = take(1, a1 + solo + a2, listOf(SectionMark(0, 0), SectionMark(a1.size + solo.size, 2)))
        val second = take(2, chroma(Synth.songA, 80.0, 4))
        val pieces = ConfirmedParts.apply(listOf(first, second), config).filter { it.recordedAt == 1L }
        assertEquals(2, pieces.size, pieces.map { it.frames.size }.toString())
        assertTrue(pieces.sumOf { it.frames.size } < first.frames.size - solo.size / 2)
        // Each piece keeps the slide that was on screen where it starts.
        assertEquals(0, pieces[0].sectionAt(0))
        assertEquals(2, pieces[1].sectionAt(pieces[1].frames.size - 1))
    }

    @Test
    fun `off, a single take, or takes sharing nothing are searched whole`() {
        val a = take(1, chroma(Synth.songA, 60.0, 1))
        val b = take(2, chroma(Synth.songC, 60.0, 2))
        assertTrue(ConfirmedParts.apply(listOf(a, b), Config()).let { it.size == 2 && it[0] === a })
        assertEquals(listOf(a), ConfirmedParts.apply(listOf(a), config))
        assertEquals(listOf(1L, 2L), ConfirmedParts.apply(listOf(a, b), config).map { it.recordedAt })
    }

    @Test
    fun `pieces of one take count as one performance for agreement`() {
        val q = Synth.chroma(Synth.render(Synth.songA, 13.0, startSeconds = 21.0, seed = 7)).takeLast(24)
        val whole = chroma(Synth.songA, 60.0, 1)
        val split = listOf(take(1, whole.subList(0, 60)), take(1, whole.subList(60, whole.size)))
        val m = Matcher(Config(takeAgreement = 0.5))
        // Split or not, one performance: scored on it alone, not pulled down by its other piece.
        assertEquals(m.match(q, listOf(take(1, whole))).best!!.score, m.match(q, split).best!!.score, 0.02)
    }
}
