package songlistener.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecognizerTest {
    /** One listener (steady window = quick window): the suggestion logic on its own. */
    private val config = Config(steadyFrames = 24)
    private var now = 1_000L
    private val saved = mutableListOf<SongTake>()
    private val events = mutableListOf<RecognizerEvent>()

    private fun recognizer(takes: List<SongTake> = emptyList()) =
        Recognizer(config, takes, clock = { now++ }, onTakeSaved = { saved += it }, onEvent = { events += it })

    private fun Recognizer.play(progression: List<Synth.Chord>, seconds: Double, seed: Int = 3, start: Double = 0.0) {
        Synth.chunks(Synth.render(progression, seconds, startSeconds = start, seed = seed)).forEach { onAudio(it) }
    }

    private val songA = LiveSong("1|A", "A (#1)", 0)
    private val songB = LiveSong("2|B", "B (#2)", 0)

    @Test
    fun `a take is kept only after 30 s of music`() {
        val r = recognizer()
        r.onMusicProb(1f)
        r.onLiveSong(songA)
        r.play(Synth.songA, 20.0)
        r.onLiveSong(null)
        assertTrue(saved.isEmpty())

        r.onLiveSong(songA)
        r.play(Synth.songA, 40.0)
        r.onLiveSong(null)
        assertEquals(1, saved.size)
        assertEquals("1|A", saved[0].songKey)
        assertTrue(saved[0].frames.size >= config.minTakeFrames)
        assertEquals(1, r.takesFor("1|A").size)
    }

    @Test
    fun `nothing is recorded while the room is not playing music`() {
        val r = recognizer()
        r.onMusicProb(0.1f)
        r.onLiveSong(songA)
        r.play(Synth.songA, 40.0)
        assertEquals(0, r.state.recordingFrames)
        r.onLiveSong(null)
        assertTrue(saved.isEmpty())
    }

    @Test
    fun `section changes are marked in the take`() {
        val r = recognizer()
        r.onMusicProb(1f)
        r.onLiveSong(songA)
        r.play(Synth.songA, 20.0)
        r.onLiveSong(songA.copy(section = 1))
        r.play(Synth.songA, 20.0, start = 20.0)
        r.onLiveSong(null)
        val marks = saved.single().sectionMarks
        assertEquals(listOf(0, 1), marks.map { it.section })
        assertEquals(0, marks[0].frame)
        assertTrue(marks[1].frame > 30)
    }

    @Test
    fun `only the newest takes per song are kept`() {
        val r = recognizer()
        r.onMusicProb(1f)
        repeat(config.maxTakesPerSong + 2) {
            r.onLiveSong(songA)
            r.play(Synth.songA, 31.0, seed = it)
            r.onLiveSong(null)
        }
        assertEquals(config.maxTakesPerSong, r.takesFor("1|A").size)
        assertEquals(saved.takeLast(config.maxTakesPerSong).map { it.recordedAt }, r.takesFor("1|A").map { it.recordedAt })
    }

    @Test
    fun `a learned song is suggested when played with nothing on screen`() {
        val r = recognizer(listOf(Synth.take("1|A", Synth.songA), Synth.take("2|B", Synth.songB)))
        r.onMusicProb(1f)
        r.play(Synth.songA, 20.0, start = 10.0)
        val suggestion = assertNotNull(r.state.lastSuggestion)
        assertEquals("1|A", suggestion.songKey)
    }

    @Test
    fun `the song just taken off screen is not suggested back`() {
        val r = recognizer(listOf(Synth.take("1|A", Synth.songA), Synth.take("2|B", Synth.songB)))
        r.onMusicProb(1f)
        r.onLiveSong(songA)
        r.onLiveSong(null)
        r.play(Synth.songA, 20.0, start = 10.0)
        assertNull(r.state.lastSuggestion)
    }

    @Test
    fun `a break in the music empties the query window`() {
        val r = recognizer(listOf(Synth.take("1|A", Synth.songA)))
        r.onMusicProb(1f)
        r.play(Synth.songA, 8.0)
        assertTrue(r.state.queryFrames > 0)
        r.onMusicProb(0f)
        r.play(Synth.songA, 3.0)
        assertEquals(0, r.state.queryFrames)
        assertEquals(1, events.count { it is RecognizerEvent.MusicBreak })
    }

    @Test
    fun `a dip of a second or two is bridged`() {
        val r = recognizer()
        r.onMusicProb(1f)
        r.onLiveSong(songA)
        r.play(Synth.songA, 8.0)
        val before = r.state.queryFrames
        r.onMusicProb(0.45f)
        r.play(Synth.songA, 2.0, start = 8.0)
        r.onMusicProb(0.9f)
        assertTrue(r.state.queryFrames > before, "query kept growing through the dip")
        assertTrue(events.none { it is RecognizerEvent.MusicBreak })
        r.play(Synth.songA, 25.0, start = 10.0)
        r.onLiveSong(null)
        // 35 s played, all of it kept, dip included.
        assertTrue(saved.single().frames.size >= 66, "frames ${saved.single().frames.size}")
    }

    @Test
    fun `accuracy is scored while a learned song is live`() {
        val r = recognizer(listOf(Synth.take("1|A", Synth.songA), Synth.take("2|B", Synth.songB)))
        r.onMusicProb(1f)
        r.onLiveSong(songB.copy(key = "2|B"))
        r.play(Synth.songB, 20.0, start = 5.0)
        val a = r.state.accuracy
        assertTrue(a.runs > 0)
        assertEquals(a.runs, a.top1)
        assertNull(r.state.lastSuggestion)
    }

    /** Plays [seconds] of audio that STT does not call music (an announcement). */
    private fun Recognizer.talk(seconds: Double) {
        onMusicProb(0f)
        play(Synth.songC, seconds)
        onMusicProb(1f)
    }

    @Test
    fun `music heard before a song goes live becomes its intro`() {
        val r = recognizer()
        r.onMusicProb(1f)
        r.play(Synth.songC, 20.0)
        val held = r.state.lookbackFrames
        assertTrue(held > 30, "held $held")
        r.onLiveSong(songA)
        assertEquals(held, r.state.recordingIntroFrames)
        assertEquals(0, r.state.lookbackFrames)
        r.play(Synth.songA, 31.0)
        r.onLiveSong(null)
        val take = saved.single()
        assertEquals(listOf(SectionMark(0, SectionMark.INTRO), SectionMark(held, 0)), take.sectionMarks)
        assertTrue(take.frames.size >= held + config.minTakeFrames)
    }

    @Test
    fun `a short break is bridged, a long one clears the look-back`() {
        val r = recognizer()
        r.onMusicProb(1f)
        r.play(Synth.songC, 10.0)
        r.talk(5.0)
        assertTrue(r.state.lookbackFrames > 0)
        r.talk(20.0)
        assertEquals(0, r.state.lookbackFrames)
    }

    @Test
    fun `the look-back keeps only the last 90 s`() {
        val r = recognizer()
        r.onMusicProb(1f)
        r.play(Synth.songC, 100.0)
        assertEquals(config.lookbackFrames, r.state.lookbackFrames)
    }

    @Test
    fun `an intro does not count toward the 30 s minimum`() {
        val r = recognizer()
        r.onMusicProb(1f)
        r.play(Synth.songC, 60.0)
        r.onLiveSong(songA)
        r.play(Synth.songA, 10.0)
        r.onLiveSong(null)
        assertTrue(saved.isEmpty())
    }

    @Test
    fun `a learned intro is recognised before the song is shown`() {
        val r = recognizer(listOf(Synth.take("2|B", Synth.songB)))
        r.onMusicProb(1f)
        // First time: the band plays an intro (songC's chords), then the song goes live.
        r.play(Synth.songC, 30.0)
        r.onLiveSong(songA)
        r.play(Synth.songA, 40.0)
        r.onLiveSong(null)
        r.talk(30.0) // announcements
        r.onLiveSong(songB)
        r.onLiveSong(null) // another song came and went, so A is no longer the one just taken off
        r.talk(30.0)
        // Next time: only the intro plays, nothing on screen.
        r.play(Synth.songC, 20.0, seed = 11, start = 4.0)
        val suggestion = assertNotNull(r.state.lastSuggestion)
        assertEquals("1|A", suggestion.songKey)
    }

    @Test
    fun `learning a song is logged start to finish`() {
        val r = recognizer()
        r.onMusicProb(1f)
        r.play(Synth.songC, 10.0)
        r.onLiveSong(songA)
        r.onLiveSong(songA.copy(section = 1))
        r.play(Synth.songA, 31.0)
        r.onLiveSong(null)
        r.onMusicProb(0f)

        val kinds = events.filterNot { it is RecognizerEvent.Match }.map { it::class.simpleName }
        assertEquals(
            listOf("MusicStarted", "LookbackStarted", "LiveChanged", "TakeStarted", "SectionChanged", "TakeSaved", "LiveChanged", "MusicStopped"),
            kinds,
        )
        val started = events.filterIsInstance<RecognizerEvent.TakeStarted>().single()
        assertTrue(started.introSeconds in 8.0..10.0, "intro ${started.introSeconds}")
        val taken = events.filterIsInstance<RecognizerEvent.TakeSaved>().single()
        assertEquals(started.introSeconds, taken.introSeconds)
        assertTrue(taken.songSeconds >= 30.0)
        assertEquals(1, taken.takesKept)
        assertNull(events.filterIsInstance<RecognizerEvent.LiveChanged>().last().song)
    }

    @Test
    fun `a short take and a cleared look-back are logged`() {
        val r = recognizer()
        r.onMusicProb(1f)
        r.play(Synth.songC, 10.0)
        r.talk(25.0)
        val cleared = events.filterIsInstance<RecognizerEvent.LookbackCleared>().single()
        assertTrue(cleared.heldSeconds > 3.0)

        r.onLiveSong(songA)
        r.play(Synth.songA, 10.0)
        r.onLiveSong(null)
        val discarded = events.filterIsInstance<RecognizerEvent.TakeDiscarded>().single()
        assertEquals("1|A", discarded.song.key)
        assertTrue(discarded.songSeconds < 30.0)
    }

    @Test
    fun `every match run is logged with its decision`() {
        val r = recognizer(listOf(Synth.take("1|A", Synth.songA), Synth.take("2|B", Synth.songB)))
        r.onMusicProb(1f)
        r.play(Synth.songA, 20.0, start = 10.0)
        val decisions = events.filterIsInstance<RecognizerEvent.Match>().filter { it.window == "steady" }.map { it.decision }
        assertEquals("streak 1/2", decisions.first())
        assertEquals(1, decisions.count { it == "suggested" })
        val first = events.filterIsInstance<RecognizerEvent.Match>().first { it.window == "steady" }
        assertNull(first.liveKey)
        assertEquals("1|A", first.top.first().songKey)
        assertTrue(first.top.size <= 3)

        r.onLiveSong(songB)
        r.play(Synth.songB, 20.0)
        assertTrue(events.filterIsInstance<RecognizerEvent.Match>().any { it.decision == "accuracy_hit" && it.liveKey == "2|B" })
    }

    @Test
    fun `nothing is matched before anything is learned`() {
        val r = recognizer()
        r.onMusicProb(1f)
        r.play(Synth.songA, 20.0)
        assertNull(r.state.lastMatch)
        assertTrue(events.none { it is RecognizerEvent.Match })
    }

    @Test
    fun `the just-shown block expires after two minutes`() {
        val r = recognizer(listOf(Synth.take("1|A", Synth.songA), Synth.take("2|B", Synth.songB)))
        r.onMusicProb(1f)
        r.onLiveSong(songA)
        r.onLiveSong(null)
        now += (config.suppressSeconds * 1000).toLong() + 1
        r.play(Synth.songA, 20.0, start = 10.0)
        assertEquals("1|A", r.state.lastSuggestion?.songKey)
    }

    @Test
    fun `a suggestion says which section the band has reached`() {
        // Three parts with different music: intro (songA's chords), section 0 (songC's), section 1
        // (songB's). A song whose verses share one tune cannot be placed this way — only parts that
        // sound different can.
        val intro = Synth.chroma(Synth.render(Synth.songA, 16.0))
        val verse = Synth.chroma(Synth.render(Synth.songC, 32.0))
        val chorus = Synth.chroma(Synth.render(Synth.songB, 16.0))
        val take = SongTake(
            "1|A", "A", 0, intro + verse + chorus,
            listOf(SectionMark(0, SectionMark.INTRO), SectionMark(intro.size, 0), SectionMark(intro.size + verse.size, 1)),
        )
        val r = recognizer(listOf(take))
        r.onMusicProb(1f)
        r.play(Synth.songC, 20.0, seed = 9, start = 1.0)
        val s = assertNotNull(r.state.lastSuggestion)
        assertEquals(0, s.section)
    }

    /** Two listeners, as the app runs: quick 12 s, steady 24 s (short, to keep the test quick). */
    private fun twoListeners(takes: List<SongTake>) =
        Recognizer(Config(steadyFrames = 48), takes, clock = { now++ }, onEvent = { events += it })

    @Test
    fun `the quick listener hints before the steady one suggests`() {
        val r = twoListeners(listOf(Synth.take("1|A", Synth.songA), Synth.take("2|B", Synth.songB)))
        r.onMusicProb(1f)
        r.play(Synth.songA, 20.0, start = 10.0)
        assertEquals("1|A", r.state.hint?.songKey)
        assertNull(r.state.lastSuggestion, "the steady window is not full yet")
        r.play(Synth.songA, 20.0, start = 30.0)
        assertEquals("1|A", r.state.lastSuggestion?.songKey)
        assertTrue(r.state.hint!!.at < r.state.lastSuggestion!!.at)
    }

    @Test
    fun `music that doesn't match the slide raises a warning, and the right slide doesn't`() {
        val takes = listOf(Synth.take("1|A", Synth.songA), Synth.take("2|B", Synth.songB))
        val wrong = twoListeners(takes)
        wrong.onMusicProb(1f)
        wrong.onLiveSong(songB)                        // B on screen…
        wrong.play(Synth.songA, 40.0, start = 5.0)     // …while A is sung
        val w = assertNotNull(wrong.state.wrongSlide)
        assertEquals("2|B", w.shown.key)
        assertEquals("1|A", w.heard.songKey)
        assertEquals(1, events.count { it is RecognizerEvent.WrongSlideWarning })

        events.clear()
        val right = twoListeners(takes)
        right.onMusicProb(1f)
        right.onLiveSong(songA)
        right.play(Synth.songA, 40.0, start = 5.0)
        assertNull(right.state.wrongSlide)
        assertTrue(events.none { it is RecognizerEvent.WrongSlideWarning })
    }

    @Test
    fun `changing the slide clears the warning`() {
        val r = twoListeners(listOf(Synth.take("1|A", Synth.songA), Synth.take("2|B", Synth.songB)))
        r.onMusicProb(1f)
        r.onLiveSong(songB)
        r.play(Synth.songA, 40.0, start = 5.0)
        assertNotNull(r.state.wrongSlide)
        r.onLiveSong(songA)
        assertNull(r.state.wrongSlide)
        assertEquals(1, events.count { it is RecognizerEvent.WrongSlideCleared })
    }

    @Test
    fun `an unlearned song on screen can't be checked`() {
        val r = twoListeners(listOf(Synth.take("1|A", Synth.songA)))
        r.onMusicProb(1f)
        r.onLiveSong(LiveSong("9|New", "#9 New", 0))
        r.play(Synth.songA, 40.0)
        assertTrue(r.state.cantCheckLive)
        assertNull(r.state.wrongSlide)
    }

    @Test
    fun `a slide that goes up long after its music is recorded as late, and whether it was seen coming`() {
        val r = recognizer(listOf(Synth.take("1|A", Synth.songA), Synth.take("2|B", Synth.songB)))
        r.onMusicProb(1f)
        now = 1_000_000L
        r.play(Synth.songA, 20.0, start = 10.0)   // the band plays A; nothing on screen; A is suggested
        assertEquals("1|A", r.state.lastSuggestion?.songKey)
        now = 1_000_000L + 35_000                 // the slide goes up 35 s after the music started
        r.onLiveSong(songA)
        val late = events.filterIsInstance<RecognizerEvent.LateSlide>().single()
        assertEquals("1|A", late.song.key)
        assertTrue(late.lateSeconds in 34.0..36.0, "late ${late.lateSeconds}")
        assertTrue(late.caught)
        assertTrue(late.suggestionLeadSeconds!! > 0)
    }

    @Test
    fun `a slide up in time is not late, and an unforeseen late one is marked missed`() {
        val r = recognizer()
        r.onMusicProb(1f)
        now = 2_000_000L
        r.play(Synth.songA, 5.0)
        now += 5_000
        r.onLiveSong(songA)                       // 5 s: in time
        r.onLiveSong(null)
        r.talk(30.0)                              // a long break clears the look-back
        now += 60_000
        r.play(Synth.songB, 25.0)
        now += 25_000
        r.onLiveSong(songB)                       // 25 s late, nothing learned to suggest it
        val late = events.filterIsInstance<RecognizerEvent.LateSlide>().single()
        assertEquals("2|B", late.song.key)
        assertFalse(late.caught)
    }
}
