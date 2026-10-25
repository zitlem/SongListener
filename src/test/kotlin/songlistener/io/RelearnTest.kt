package songlistener.io

import songlistener.core.Clip
import songlistener.core.ClipLabels
import songlistener.core.Config
import songlistener.core.SongTake
import songlistener.core.Synth
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RelearnTest {
    private fun labels(key: String, at: Long) = ClipLabels(
        songKey = key, songLabel = key, takeRecordedAt = at, startedAt = at, sampleRate = 16_000,
        goLiveSeconds = 0.0, endSeconds = 60.0, endReason = "off_screen", sections = emptyList(), music = emptyList(),
    )

    private fun waitFor(what: String, ok: () -> Boolean) {
        val until = System.currentTimeMillis() + 60_000
        while (!ok()) { check(System.currentTimeMillis() < until) { "timed out waiting for $what" }; Thread.sleep(100) }
    }

    @Test
    fun `relearning clipped recordings drops their takes and keeps the recordings`() {
        val dir = createTempDirectory().toFile()
        val quiet = Synth.render(Synth.songA, 60.0)
        val hot = ShortArray(quiet.size) { (quiet[it] * 40.0).roundToInt().coerceIn(-32767, 32767).toShort() }
        val clips = ClipStore(File(dir, "clips"))
        val takes = TakeStore(File(dir, "takes"), 5)
        clips.save(Clip(hot, labels("1|A", 1_000)))
        clips.save(Clip(quiet, labels("2|B", 2_000)))
        val frames = Synth.take("x", Synth.songA).frames
        takes.save(SongTake("1|A", "1|A", 1_000, frames, emptyList()))
        takes.save(SongTake("2|B", "2|B", 2_000, frames, emptyList()))

        val engine = ListenerEngine(dir, Config())
        try {
            engine.refreshReview()
            waitFor("review checks") { engine.reviews.value.size == 2 && engine.reviews.value.all { it.check != null } }
            // Measured once and remembered in each recording's labels.
            assertEquals(listOf(2, 0), clips.labels().map { it.second.level?.severity })
            assertEquals(listOf("1|A"), engine.reviews.value.filter { r -> r.check!!.flags.any { it.code == "clipped" } }.map { it.labels.songKey })

            engine.relearnAllClipped()
            waitFor("relearn") { engine.reviews.value.any { it.labels.relearnRequestedAt != null && it.check != null } }
            assertEquals(listOf("2|B"), takes.loadAll().map { it.songKey })
            assertEquals(2, clips.labels().size)
            val a = engine.reviews.value.single { it.labels.songKey == "1|A" }
            assertTrue(a.check!!.flags.any { it.code == "relearn" } && a.check!!.flags.none { it.code == "no_take" }, a.check!!.flags.toString())
        } finally { engine.shutdown() }
    }
}
