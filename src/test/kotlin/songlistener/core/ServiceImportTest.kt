package songlistener.core

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ServiceImportTest {
    private val rate = Synth.RATE

    /** 30 s of talk-like noise, a 60 s song, 30 s more: the song starts at 30 s in the recording. */
    private fun service(): ShortArray =
        Synth.render(Synth.songC, 30.0, seed = 3) + Synth.render(Synth.songA, 60.0, seed = 4) + Synth.render(Synth.songB, 30.0, seed = 5)

    @Test
    fun `a take is found in the recording, wherever the clock says it was`() {
        val recording = service()
        val take = Synth.take("A", Synth.songA, seconds = 60.0, seed = 9) // another performance
        val timeline = ServiceImport.chromaTimeline(recording)
        // The log claims it went live at 18 s; it really starts at 30 s in the recording.
        val (offset, score) = ServiceImport.align(timeline, take, nominalLive = 18.0)!!
        // songA loops every 16 s, so any whole-loop shift is an equally good answer.
        val residual = Math.floorMod(Math.round(offset - 12.0).toInt(), 16)
        assertTrue(residual <= 1 || residual >= 15, "offset $offset")
        assertTrue(score > 0.9, "score $score")
    }

    @Test
    fun `events are shifted by the offset of the song they belong to`() {
        val e = listOf(
            ServiceImport.Event(5.0, "music_started"),                       // before song 1: its intro
            ServiceImport.Event(10.0, "song_live", "1", "One", 0),
            ServiceImport.Event(50.0, "song_live", "2", "Two", 0),           // back to back
            ServiceImport.Event(90.0, "song_off_screen"),
            ServiceImport.Event(95.0, "music_stopped"),                      // after the last song
        )
        val shifted = ServiceImport.shift(e, listOf(
            ServiceImport.Alignment("1", "One", 10.0, 2.0, 0.9),
            ServiceImport.Alignment("2", "Two", 50.0, 5.0, 0.9),
        ))
        assertEquals(listOf(7.0, 12.0, 55.0, 95.0, 100.0), shifted.map { it.at })
    }

    @Test
    fun `a past service is cut into the same recording the app would have made`() {
        val recording = Synth.render(Synth.songC, 20.0, seed = 3) + Synth.render(Synth.songA, 60.0, seed = 4) + Synth.render(Synth.songC, 20.0, seed = 6)
        val events = listOf(
            ServiceImport.Event(20.0, "music_started"),
            ServiceImport.Event(35.0, "song_live", "1|A", "A (#1)", 0),
            ServiceImport.Event(60.0, "section_changed", "1|A", "A (#1)", 1),
            ServiceImport.Event(78.0, "song_off_screen"),
            ServiceImport.Event(80.0, "music_stopped"),
        )
        val clip = ServiceImport.cut(recording, events, startedAtMs = 1_000_000).single()
        val l = clip.labels
        assertEquals("1|A", l.songKey)
        assertTrue(abs(l.goLiveSeconds - 18.0) <= 1.0, "starts ~3 s before the intro at 20 s: ${l.goLiveSeconds}")
        assertEquals(listOf(0, 1), l.sections.map { it.section })
        assertEquals("off_screen", l.endReason)
        assertEquals(clip.samples.size.toDouble() / rate, l.endSeconds)
    }
}
