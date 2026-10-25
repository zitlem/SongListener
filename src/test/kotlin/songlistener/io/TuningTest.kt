package songlistener.io

import kotlinx.coroutines.runBlocking
import songlistener.core.Clip
import songlistener.core.ClipLabels
import songlistener.core.Config
import songlistener.core.MusicMark
import songlistener.core.Synth
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TuningTest {
    @Test
    fun `matching settings are saved, and an older settings file gets the defaults`() {
        val f = createTempDirectory().resolve("config.json").toFile()
        f.writeText("""{"sttUrl":"http://x:80"}""")
        val old = AppSettings.load(f)
        assertEquals("http://x:80", old.sttUrl)
        assertEquals(Config().copy(), old.applyTo(Config()))
        AppSettings.save(f, old.copy(keepTakes = "best", maxTakesPerSong = 3, takeAgreement = 0.5))
        val c = AppSettings.load(f).applyTo(Config())
        assertEquals(Triple("best", 3, 0.5), Triple(c.keepTakes, c.maxTakesPerSong, c.takeAgreement))
        assertEquals("http://x:80", AppSettings.load(f).sttUrl)
    }

    @Test
    fun `variants are distinct and the test reports each`() {
        // Duplicates go: with the defaults, "Defaults" and the intro mode in use are "Current settings".
        assertEquals(7, Tuning.variants(Config()).size)
        assertEquals(8, Tuning.variants(Config(takeAgreement = 0.3)).size)

        fun clip(key: String, music: List<Synth.Chord>, seed: Int) = key to Clip(
            Synth.render(music, 50.0, seed = seed),
            ClipLabels(songKey = key, songLabel = key, takeRecordedAt = seed.toLong(), startedAt = 0, sampleRate = 16_000,
                goLiveSeconds = 0.0, endSeconds = 50.0, endReason = "off_screen", sections = emptyList(), music = listOf(MusicMark(0.0, true))),
        )
        val clips = listOf(clip("1|A", Synth.songA, 1), clip("1|A", Synth.songA, 2), clip("2|B", Synth.songB, 3), clip("2|B", Synth.songB, 4))
        val seen = mutableListOf<Double>()
        val results = runBlocking { Tuning.run(clips, Tuning.variants(Config())) { synchronized(seen) { seen += it } } }
        assertEquals(7, results.size)
        assertTrue(results.all { it.summary.testable == 4 })
        assertEquals(1.0, seen.max(), 1e-9)
    }
}
