package songlistener.core

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MusicTimelineTest {
    /**
     * Speech-like: a voiced sound whose pitch jumps every 0.1 s (syllables), over light noise.
     * Pure noise is a poor stand-in — it leaves the same fixed chroma pattern every frame.
     */
    private fun babble(seconds: Double): ShortArray {
        val r = Random(5)
        val n = (seconds * Synth.RATE).toInt()
        var f0 = 150.0
        var phase = 0.0
        return ShortArray(n) { i ->
            if (i % 1600 == 0) f0 = 90.0 + 160.0 * r.nextDouble()
            phase += 2 * Math.PI * f0 / Synth.RATE
            var v = 0.0
            for (h in 1..5) v += kotlin.math.sin(phase * h) / h
            v = 0.15 * v + 0.01 * (r.nextDouble() * 2 - 1)
            (v.coerceIn(-1.0, 1.0) * 32767).toInt().toShort()
        }
    }

    @Test
    fun `sustained chords are steady, babble is not`() {
        val music = MusicTimeline.steadiness(Synth.render(Synth.songA, 20.0, noise = 0.0))
        val talk = MusicTimeline.steadiness(babble(20.0))
        assertTrue(music.drop(2).average() > 0.85, "music ${music.average()}")
        assertTrue(talk.drop(2).average() < 0.6, "talk ${talk.average()}")
    }

    @Test
    fun `a recording is split into music and not`() {
        val audio = babble(30.0) + Synth.render(Synth.songA, 40.0, noise = 0.0) + babble(30.0)
        val changes = MusicTimeline.changes(MusicTimeline.steadiness(audio))
        assertEquals(2, changes.size, "$changes")
        val (start, stop) = changes
        assertTrue(start.second && kotlin.math.abs(start.first - 30) <= 1, "starts at ${start.first}")
        assertTrue(!stop.second && kotlin.math.abs(stop.first - 70) <= 1, "stops at ${stop.first}")
    }

    @Test
    fun `short dips are bridged and short bursts dropped`() {
        val s = DoubleArray(30) { 0.0 }
        for (i in 5 until 15) s[i] = 0.9
        s[9] = 0.2; s[10] = 0.3          // a 2 s dip inside music: bridged
        s[20] = 0.9; s[21] = 0.9          // a 2 s burst: dropped
        assertEquals(listOf(5 to true, 15 to false), MusicTimeline.changes(s))
    }

    @Test
    fun `a go-live-only record ends each song where its music ends`() {
        val screen = listOf(
            ServiceImport.Event(10.0, "song_live", "1", "One"),
            ServiceImport.Event(300.0, "song_live", "2", "Two"),
        )
        val music = listOf(
            ServiceImport.Event(8.0, "music_started"),
            ServiceImport.Event(100.0, "music_stopped"),
            ServiceImport.Event(105.0, "music_started"), // 5 s gap: same song
            ServiceImport.Event(190.0, "music_stopped"), // then the sermon
            ServiceImport.Event(295.0, "music_started"),
            ServiceImport.Event(400.0, "music_stopped"),
        )
        val ended = ServiceImport.endAtMusic(screen, music, gapSeconds = 20.0)
        assertEquals(
            listOf("song_live" to 10.0, "song_off_screen" to 190.0, "song_live" to 300.0, "song_off_screen" to 400.0),
            ended.map { it.type to it.at },
        )
    }
}
