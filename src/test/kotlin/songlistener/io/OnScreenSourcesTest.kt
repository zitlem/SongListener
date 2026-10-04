package songlistener.io

import songlistener.core.ClipCutter
import songlistener.core.Recognizer
import songlistener.core.LiveSong
import songlistener.core.Synth
import songlistener.core.Clip
import java.io.File
import java.nio.file.Files
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OnScreenSourcesTest {
    private val dir = Files.createTempDirectory("sources").toFile()
    private fun file(name: String, text: String) = File(dir, name).also { it.writeText(text) }

    @Test
    fun `ChurchPresenter history becomes go-lives, section changes and leaving the screen`() {
        val f = file("live-content-2026-10-04_090000.jsonl", """
            {"type":"session","ts_ms":1000,"sessionId":"2026-10-04_090000"}
            {"ts_ms":10000,"contentType":"LYRICS","songId":"Hymns::12","songbook":"Hymns","songNumber":12,"songTitle":"Hark","sectionIndex":0}
            {"ts_ms":11000,"contentType":"LYRICS","songId":"Hymns::12","songbook":"Hymns","songNumber":12,"songTitle":"Hark","sectionIndex":0,"lineIndex":2}
            {"ts_ms":30000,"contentType":"LYRICS","songId":"Hymns::12","songbook":"Hymns","songNumber":12,"songTitle":"Hark","sectionIndex":1}
            {"ts_ms":90000,"contentType":"BIBLE","verseCode":"B043C003V016"}
            {"ts_ms":95000,"contentType":"LYRICS","songNumber":0,"songTitle":"Untitled","sectionIndex":0}
            {"ts_ms":120000,"contentType":"NONE"}
            not json
        """.trimIndent())
        val e = OnScreenSources.fromChurchPresenterHistory(f)
        assertEquals(
            listOf("song_live" to 10.0, "section_changed" to 30.0, "song_off_screen" to 90.0, "song_live" to 95.0, "song_off_screen" to 120.0),
            e.map { it.type to it.clockSec },
        )
        assertEquals("12|Hark", e[0].songKey)
        assertEquals("#12 Hark", e[0].songLabel)
        assertEquals("Hymns", e[0].songbook)
        assertEquals(1, e[1].section)
        assertEquals("0|Untitled", e[3].songKey)
    }

    @Test
    fun `the play log gives go-lives inside the window, oldest first, without repeats`() {
        val f = file("play_log.json", """
            {"songEvents":[
              {"songNumber":5,"title":"Later","songbook":"B","author":"","timestamp":300000},
              {"songNumber":4,"title":"First","songbook":"A","author":"","timestamp":100000},
              {"songNumber":4,"title":"First","songbook":"A","author":"","timestamp":101000},
              {"songNumber":9,"title":"Too late","songbook":"A","author":"","timestamp":900000}
            ],"verseEvents":[]}
        """.trimIndent())
        val e = OnScreenSources.fromPlayLog(f, fromSec = 50.0, toSec = 500.0)
        assertEquals(listOf("4|First", "5|Later"), e.map { it.songKey })
        assertEquals(listOf(100.0, 300.0), e.map { it.clockSec })
        assertEquals(listOf("A", "B"), e.map { it.songbook })
    }

    @Test
    fun `SongListener's own log keeps music and treats a disconnect as leaving the screen`() {
        val f = file("2026-10-04.jsonl", """
            {"time":"2026-10-04 09:00:00","type":"music_started","musicProb":0.8}
            {"time":"2026-10-04 09:00:05","type":"song_live","song":"Hark (#12)","key":"12|Hark","section":0}
            {"time":"2026-10-04 09:00:40","type":"match","live":"12|Hark","decision":"live_unlearned","confident":false,"margin":0.0,"top":[]}
            {"time":"2026-10-04 09:03:00","type":"churchpresenter_disconnected"}
            {"time":"2026-10-04 09:03:02","type":"music_stopped","musicProb":0.1}
        """.trimIndent())
        val e = OnScreenSources.fromSongListenerLog(f, ZoneOffset.UTC)
        assertEquals(listOf("music_started", "song_live", "song_off_screen", "music_stopped"), e.map { it.type })
        assertEquals(1_791_104_405.0, e[1].clockSec)
        assertEquals(true, OnScreenSources.hasMusic(e))
    }

    @Test
    fun `clock skew is the median difference over songs both sources saw`() {
        fun live(key: String, t: Double) = ClockEvent(t, "song_live", key, key)
        val cp = listOf(live("a", 103.0), live("b", 504.0), live("c", 910.0), live("x", 2000.0))
        val sl = listOf(live("a", 100.0), live("b", 500.0), live("c", 905.0))
        assertEquals(4.0, clockSkew(cp, sl))
        assertNull(clockSkew(listOf(live("x", 1.0)), sl))
    }

    @Test
    fun `seeding adds a take once per service and skips songs too short to learn`() {
        val store = TakeStore(Files.createTempDirectory("takes").toFile(), maxTakesPerSong = 5)
        val clip = record(seconds = 40.0)
        assertEquals(SeedResult.LEARNED, seedTake(clip, store))
        assertEquals(SeedResult.ALREADY_LEARNED, seedTake(clip, store))
        assertEquals(1, store.loadAll().size)
        assertEquals(SeedResult.TOO_SHORT, seedTake(record(seconds = 40.0, liveSeconds = 10.0), store))
    }

    /** A recording of one song, made by the same recognizer and cutter the app uses. */
    private fun record(seconds: Double, liveSeconds: Double = seconds): Clip {
        var clip: Clip? = null
        val cutter = ClipCutter(clock = { 1_000_000L }, onClip = { clip = it })
        val r = Recognizer(clock = { 1_000_000L }, onEvent = { cutter.onEvent(it) })
        r.onMusicProb(1f)
        r.onLiveSong(LiveSong("1|A", "A (#1)", 0))
        val audio = Synth.render(Synth.songA, seconds)
        Synth.chunks(audio).forEachIndexed { i, chunk ->
            if (i.toDouble() == liveSeconds) r.onLiveSong(null)
            r.onAudio(chunk); cutter.onAudio(chunk, r.state.isMusic)
        }
        r.onLiveSong(null)
        r.finishTake()
        cutter.finish("app_stopped")
        return clip ?: Clip(ShortArray(0), songlistener.core.ClipLabels(
            songKey = "1|A", songLabel = "A", takeRecordedAt = 2_000_000L, startedAt = 0, sampleRate = 16_000,
            goLiveSeconds = 0.0, endSeconds = 0.0, endReason = "off_screen", sections = emptyList(), music = emptyList(),
        ))
    }
}
