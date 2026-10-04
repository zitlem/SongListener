package songlistener.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReplayTest {
    /** Records one performance the way the app would: talk, an intro, the song live, then off. */
    private fun perform(
        key: String,
        progression: List<Synth.Chord>,
        transpose: Int = 0,
        tempo: Double = 1.0,
        seed: Int = 1,
    ): Clip {
        var clip: Clip? = null
        val cutter = ClipCutter(clock = { 1_000L }, onClip = { clip = it })
        val r = Recognizer(onEvent = { cutter.onEvent(it) })
        fun feed(samples: ShortArray) = Synth.chunks(samples).forEach { r.onAudio(it); cutter.onAudio(it, r.state.isMusic) }
        r.onMusicProb(0f)
        feed(Synth.render(Synth.songC, 25.0, seed = seed + 100))
        r.onMusicProb(1f)
        val performance = Synth.render(progression, 60.0, transpose = transpose, tempo = tempo, seed = seed)
        feed(performance.copyOfRange(0, 20 * Synth.RATE))           // 20 s intro, nothing on screen
        r.onLiveSong(LiveSong(key, key, 0))
        feed(performance.copyOfRange(20 * Synth.RATE, performance.size))
        r.onLiveSong(null)
        r.onMusicProb(0f)
        feed(Synth.render(Synth.songC, 5.0, seed = seed + 200))
        return clip!!
    }

    private val songD = listOf(Synth.Am, Synth.Em, Synth.F, Synth.C, Synth.Dm, Synth.Am, Synth.E, Synth.Am)

    @Test
    fun `each performance is recognised from the other, before it goes live`() {
        val clips = listOf(
            "A1" to perform("A", Synth.songA, seed = 1),
            "A2" to perform("A", Synth.songA, transpose = 2, tempo = 1.05, seed = 2),
            "B1" to perform("B", Synth.songB, seed = 3),
            "B2" to perform("B", Synth.songB, transpose = 2, tempo = 1.05, seed = 4),
            "D1" to perform("D", songD, seed = 5),
        )
        // One listener: the synthetic intros are 20 s, shorter than the steady window.
        val (rows, summary) = Replay.run(clips, Config(steadyFrames = 24))
        val byName = rows.associateBy { it.name }
        for (name in listOf("A1", "A2", "B1", "B2")) {
            val row = byName.getValue(name)
            assertTrue(row.testable, name)
            assertEquals(true, row.firstSuggestionRight, "$name: $row")
            assertTrue((row.leadSeconds ?: -1.0) > 0, "$name suggested before going live: $row")
            assertEquals(0, row.wrongSuggestions, name)
            assertTrue(row.top1Rate!! > 0.8, "$name top-1 ${row.top1Rate}")
        }
        assertFalse(byName.getValue("D1").testable)
        assertEquals(5, summary.clips)
        assertEquals(4, summary.testable)
        assertEquals(4, summary.rightBeforeLive)
        assertEquals(0, summary.wrongSuggestions)
    }

    @Test
    fun `a recording replays into the take it was learned as`() {
        val clip = perform("A", Synth.songA)
        val take = Replay.learn(clip)!!
        assertEquals("A", take.songKey)
        assertEquals(clip.labels.takeRecordedAt, take.recordedAt)
        // 20 s intro + 40 s live, at 2 frames a second, give or take the edges.
        assertTrue(take.frames.size in 110..122, "frames ${take.frames.size}")
        assertEquals(SectionMark.INTRO, take.sectionMarks.first().section)
    }
}
