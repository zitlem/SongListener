package songlistener.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MatcherTest {
    private val config = Config()
    private val matcher = Matcher(config)
    private val takes = listOf(Synth.take("A", Synth.songA), Synth.take("B", Synth.songB))

    /** 12 s of [progression] as the query, rendered with its own noise so it is never the take itself. */
    private fun query(progression: List<Synth.Chord>, transpose: Int = 0, tempo: Double = 1.0) =
        Synth.chroma(Synth.render(progression, 13.0, transpose = transpose, tempo = tempo, startSeconds = 21.0, seed = 7))
            .takeLast(config.queryFrames)

    private fun assertRecognised(result: MatchResult, key: String) {
        val best = result.best!!
        assertEquals(key, best.songKey, "ranked: ${result.ranked}")
        assertTrue(best.score >= config.suggestScore, "score ${best.score}")
        assertTrue(result.margin >= config.suggestMargin, "margin ${result.margin}")
    }

    @Test
    fun `a passage matches the song it came from`() {
        assertRecognised(matcher.match(query(Synth.songA), takes), "A")
        assertRecognised(matcher.match(query(Synth.songB), takes), "B")
    }

    @Test
    fun `a transposed performance still matches, and reports the shift`() {
        val result = matcher.match(query(Synth.songA, transpose = 2), takes)
        assertRecognised(result, "A")
        // Played 2 up, so the query must be rotated 10 (= -2) to line up with the take.
        assertEquals(10, result.best!!.rotation)
    }

    @Test
    fun `a faster performance still matches`() {
        assertRecognised(matcher.match(query(Synth.songA, tempo = 1.06), takes), "A")
    }

    @Test
    fun `an unlearned song is not confidently recognised`() {
        val result = matcher.match(query(Synth.songC), takes)
        val best = result.best!!
        val confident = best.score >= config.suggestScore && result.margin >= config.suggestMargin
        assertTrue(!confident, "would suggest ${best.songKey} at ${best.score}, margin ${result.margin}")
    }

    @Test
    fun `no takes and no query give no match`() {
        assertEquals(null, matcher.match(query(Synth.songA), emptyList()).best)
        assertEquals(null, matcher.match(emptyList(), takes).best)
    }
}
