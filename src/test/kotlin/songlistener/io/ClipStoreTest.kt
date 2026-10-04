package songlistener.io

import songlistener.core.Clip
import songlistener.core.ClipLabels
import songlistener.core.MusicMark
import songlistener.core.TimedSection
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ClipStoreTest {
    private val dir = Files.createTempDirectory("clips").toFile()
    private val store = ClipStore(dir)

    private fun clip(key: String, label: String, at: Long) = Clip(
        ShortArray(48_000) { (it % 200 - 100).toShort() },
        ClipLabels(
            songKey = key, songLabel = label, takeRecordedAt = at + 60_000, startedAt = at, sampleRate = 16_000,
            goLiveSeconds = 1.5, offScreenSeconds = 2.5, endSeconds = 3.0, endReason = "off_screen",
            sections = listOf(TimedSection(1.5, 0)), music = listOf(MusicMark(0.0, true)),
        ),
    )

    @Test
    fun `a recording round-trips as WAV and JSON`() {
        val original = clip("12|Hark", "Hark (#12)", 1_790_000_000_000)
        val wav = store.save(original)
        assertTrue(wav.name.endsWith("_12.wav"), wav.name)
        assertTrue(java.io.File(dir, wav.name.removeSuffix(".wav") + ".json").exists())
        assertEquals("RIFF", String(wav.readBytes(), 0, 4))
        assertEquals(44L + 96_000, wav.length())
        val (file, loaded) = store.loadAll().single()
        assertEquals(wav, file)
        assertContentEquals(original.samples, loaded.samples)
        // Saved with the old "Title (#n)" label and no origin; read back number first, as an import.
        assertEquals(original.labels.copy(songLabel = "#12 Hark", origin = "import", importSource = "play_log"), loaded.labels)
        assertTrue(dir.listFiles()!!.none { it.name.endsWith(".tmp") })
    }

    @Test
    fun `stats count per song, and delete removes one song's recordings only`() {
        store.save(clip("12|Hark", "Hark (#12)", 1_790_000_000_000))
        store.save(clip("12|Hark", "Hark (#12)", 1_790_000_000_000)) // same second: gets a -2 suffix
        store.save(clip("0|Untitled hymn", "Untitled hymn", 1_790_000_100_000))
        val s = store.stats()
        assertEquals(3, s.count)
        assertEquals(mapOf("12|Hark" to 2, "0|Untitled hymn" to 1), s.perSong)
        assertTrue(store.labels().any { it.first.name.endsWith("_Untitled-hymn.wav") })

        store.delete("12|Hark")
        assertEquals(listOf("0|Untitled hymn"), store.labels().map { it.second.songKey })
        assertEquals(2, dir.listFiles()!!.size)
    }

    @Test
    fun `live recordings keep their origin and are counted apart from imports`() {
        store.save(clip("12|Hark", "#12 Hark", 1_790_000_000_000))
        val live = clip("12|Hark", "#12 Hark", 1_790_100_000_000)
        store.save(Clip(live.samples, live.labels.copy(origin = "live")))
        val s = store.stats()
        assertEquals(1, s.live)
        assertEquals(1, s.imported)
        assertEquals(mapOf("12|Hark" to 1), s.livePerSong)
        assertEquals(listOf("import", "live"), store.labels().map { it.second.origin })
        assertEquals(listOf("play_log", null), store.labels().map { it.second.importSource })
    }

    @Test
    fun `old recordings from 2026-09-30 are marked as cut using SongListener's log`() {
        val sep30 = java.time.LocalDateTime.of(2026, 9, 30, 19, 1).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        assertEquals("songlistener_log", ClipStore.withDefaults(clip("1|A", "A", sep30).labels).importSource)
    }
}
