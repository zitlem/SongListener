package songlistener.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClipCutterTest {
    private val clips = mutableListOf<Clip>()
    private val dropped = mutableListOf<String>()

    /** A recognizer and a cutter wired as the engine wires them. */
    private inner class Rig(config: Config = Config()) {
        private val cutter = ClipCutter(config, clock = { 1_000_000L }, onClip = { clips += it }, onDropped = { _, why -> dropped += why })
        val r = Recognizer(config, onEvent = { cutter.onEvent(it) })

        fun play(progression: List<Synth.Chord>, seconds: Double, music: Boolean, start: Double = 0.0) {
            r.onMusicProb(if (music) 1f else 0f)
            Synth.chunks(Synth.render(progression, seconds, startSeconds = start)).forEach {
                r.onAudio(it)
                cutter.onAudio(it, r.state.isMusic)
            }
        }

        fun live(song: LiveSong?) = r.onLiveSong(song)
        fun stop() { r.finishTake(); cutter.finish("app_stopped") }
    }

    private val song = LiveSong("7|Song", "Song (#7)", 0)

    @Test
    fun `a recording starts 3 s before its intro and ends 3 s after the song leaves`() {
        val rig = Rig()
        rig.play(Synth.songC, 5.0, music = false)    // talking: 0–5 s
        rig.play(Synth.songA, 20.0, music = true)    // intro: 5–25 s
        rig.live(song)
        rig.play(Synth.songA, 35.0, music = true, start = 20.0) // 25–60 s
        rig.live(null)
        rig.play(Synth.songC, 5.0, music = false)    // 60–65 s
        val c = clips.single()
        assertEquals(23.0, c.labels.goLiveSeconds)   // starts at 2 s
        assertEquals(58.0, c.labels.offScreenSeconds)
        assertEquals(61.0, c.labels.endSeconds)
        assertEquals("off_screen", c.labels.endReason)
        assertEquals(61 * 16_000, c.samples.size)
        assertEquals(listOf(MusicMark(0.0, false), MusicMark(3.0, true), MusicMark(58.0, false)), c.labels.music)
        assertEquals(listOf(TimedSection(23.0, 0)), c.labels.sections)
        assertEquals("7|Song", c.labels.songKey)
        assertTrue(c.labels.takeRecordedAt > 0)
    }

    @Test
    fun `with no intro it starts 3 s before the song went live`() {
        val rig = Rig()
        rig.play(Synth.songC, 10.0, music = false)
        rig.live(song)
        rig.play(Synth.songA, 35.0, music = true)
        rig.live(null)
        rig.play(Synth.songC, 4.0, music = false)
        assertEquals(3.0, clips.single().labels.goLiveSeconds)
    }

    @Test
    fun `a slide left up after the music stops is cut 3 s after the last music`() {
        val rig = Rig()
        rig.play(Synth.songC, 10.0, music = false)
        rig.live(song)
        rig.play(Synth.songA, 35.0, music = true)   // music until 45 s
        rig.play(Synth.songC, 30.0, music = false)  // sermon, slide still up
        assertTrue(clips.isEmpty(), "kept back until the take is known")
        rig.live(null)
        val c = clips.single()
        assertEquals("music_ended", c.labels.endReason)
        assertEquals(41.0, c.labels.endSeconds)     // 45 + 3 − 7
        assertNull(c.labels.offScreenSeconds)
    }

    @Test
    fun `a recording stops at the length cap`() {
        val rig = Rig(Config(clipMaxSeconds = 40.0))
        rig.play(Synth.songC, 10.0, music = false)
        rig.live(song)
        rig.play(Synth.songA, 60.0, music = true)
        rig.live(null)
        rig.play(Synth.songC, 4.0, music = false)
        val c = clips.single()
        assertEquals("max_length", c.labels.endReason)
        assertEquals(40.0, c.labels.endSeconds)
    }

    @Test
    fun `a discarded take keeps no recording`() {
        val rig = Rig()
        rig.live(song)
        rig.play(Synth.songA, 10.0, music = true)
        rig.live(null)
        rig.play(Synth.songC, 5.0, music = false)
        assertTrue(clips.isEmpty())
        assertEquals(listOf("take_discarded"), dropped)
    }

    @Test
    fun `section changes are timed and songs back to back split cleanly`() {
        val rig = Rig()
        rig.play(Synth.songC, 10.0, music = false)
        rig.live(song)
        rig.play(Synth.songA, 20.0, music = true)
        rig.live(song.copy(section = 1))
        rig.play(Synth.songA, 20.0, music = true, start = 20.0)
        rig.live(LiveSong("8|Next", "Next (#8)", 0))
        rig.play(Synth.songB, 35.0, music = true)
        rig.stop()
        val (first, second) = clips
        assertEquals(listOf(TimedSection(3.0, 0), TimedSection(23.0, 1)), first.labels.sections)
        assertEquals("next_song", first.labels.endReason)
        assertEquals(43.0, first.labels.endSeconds)
        assertEquals("8|Next", second.labels.songKey)
        assertEquals("app_stopped", second.labels.endReason)
        assertEquals(3.0, second.labels.goLiveSeconds)
    }

    @Test
    fun `a slide put up before the singing waits for the music`() {
        val rig = Rig()
        rig.play(Synth.songC, 10.0, music = false)
        rig.live(song)                               // slide up at 10 s, during an announcement
        rig.play(Synth.songC, 40.0, music = false)   // 40 s of talking with the slide up
        rig.play(Synth.songA, 35.0, music = true)    // then the singing, 50–85 s
        rig.live(null)
        rig.play(Synth.songC, 4.0, music = false)
        val c = clips.single()
        assertEquals("off_screen", c.labels.endReason)
        assertEquals(3.0, c.labels.goLiveSeconds)
        assertEquals(81.0, c.labels.endSeconds)     // 85 + 3 − 7: the whole song is in it
    }
}
