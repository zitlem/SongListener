package songlistener.core

import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TakeSelectionTest {
    private val config = Config(steadyFrames = 48, maxTakesPerSong = 3)

    private fun take(at: Long, music: List<Synth.Chord> = Synth.songA, clipped: Double? = null) =
        SongTake("1|A", "A", at, Synth.take("1|A", music, seed = at.toInt()).frames, emptyList(), clipped)

    @Test
    fun `newest keeps the latest takes`() {
        val all = (1L..5L).map { take(it) }
        assertEquals(listOf(3L, 4L, 5L), TakeSelection.keep(all, config).map { it.recordedAt })
        assertEquals(listOf(1L, 2L), TakeSelection.keep(all.take(2), config).map { it.recordedAt })
    }

    @Test
    fun `best drops clipped takes first, then the one unlike the rest, and keeps a good old take`() {
        val best = config.copy(keepTakes = "best")
        val all = listOf(
            take(1),                            // old but good
            take(2, Synth.songB),               // labelled A, sounds like B: a wrong slide
            take(3, clipped = 0.9),             // heavily clipped
            take(4),
            take(5),
        )
        assertEquals(listOf(1L, 4L, 5L), TakeSelection.keep(all, best).map { it.recordedAt })
    }

    @Test
    fun `a song's score can lean on its second-best take`() {
        val good = Synth.take("A", Synth.songA)
        val odd = SongTake("A", "A", 1, Synth.take("A", Synth.songC).frames, emptyList())
        val query = Synth.chroma(Synth.render(Synth.songA, 13.0, startSeconds = 21.0, seed = 7)).takeLast(24)
        val alone = Matcher(Config()).match(query, listOf(good, odd)).best!!.score
        val leaning = Matcher(Config(takeAgreement = 0.5)).match(query, listOf(good, odd)).best!!.score
        assertTrue(leaning < alone - 0.05, "$leaning vs $alone")
        // One take: scored as before.
        assertEquals(Matcher(Config()).match(query, listOf(good)).best!!.score,
            Matcher(Config(takeAgreement = 0.5)).match(query, listOf(good)).best!!.score, 1e-9)
    }

    @Test
    fun `a take learned from clipped audio says so`() {
        val quiet = Synth.render(Synth.songA, 60.0)
        val hot = ShortArray(quiet.size) { (quiet[it] * 40.0).roundToInt().coerceIn(-32767, 32767).toShort() }
        fun labels() = ClipLabels(songKey = "1|A", songLabel = "A", takeRecordedAt = 1, startedAt = 0, sampleRate = 16_000,
            goLiveSeconds = 0.0, endSeconds = 60.0, endReason = "off_screen", sections = emptyList(), music = listOf(MusicMark(0.0, true)))
        val clean = Replay.learn(Clip(quiet, labels()), Config())!!
        val clipped = Replay.learn(Clip(hot, labels()), Config())!!
        assertEquals(0.0, clean.clipped)
        assertTrue(clipped.clipped!! > 0.9, "${clipped.clipped}")
    }
}
