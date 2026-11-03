package songlistener.core

import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The matcher on large libraries. Distractor takes are drawn straight as chroma (a random chord
 * progression with noise) rather than rendered, so hundreds of songs are cheap to build.
 */
class MatcherScaleTest {
    private val config = Config()

    /** A song of [seconds] at 2 frames/s: a random progression of triads, one chord per 2 s, with noise. */
    private fun drawn(key: String, random: Random, seconds: Int = 180, at: Long = 0L): SongTake {
        val chords = List(8) { val root = random.nextInt(12); intArrayOf(root, (root + if (random.nextBoolean()) 4 else 3) % 12, (root + 7) % 12) }
        val frames = List(seconds * 2) { i ->
            val v = FloatArray(12) { random.nextFloat() * 0.3f }
            chords[(i / 4) % chords.size].forEach { v[it] += 1f }
            val norm = sqrt(v.fold(0f) { s, x -> s + x * x })
            FloatArray(12) { v[it] / norm }
        }
        return SongTake(key, key, at, frames, emptyList())
    }

    /** The search before it was laid flat: kept here to hold the new one to the same scores. */
    private fun reference(query: List<FloatArray>, take: SongTake): Double {
        var best = Double.NEGATIVE_INFINITY
        for (rotation in Matcher.candidateRotations(sumProfile(query), take.profile)) {
            val rotated = query.map { Matcher.rotate(it, rotation) }
            for (stretch in config.tempoStretches) {
                val span = ((query.size - 1) * stretch).roundToInt() + 1
                if (span > take.frames.size) continue
                for (offset in 0..take.frames.size - span) {
                    var sum = 0.0
                    for (i in rotated.indices) sum += Matcher.dot(rotated[i], take.frames[offset + (i * stretch).roundToInt()])
                    best = maxOf(best, sum / rotated.size)
                }
            }
        }
        return best
    }

    private val random = Random(42)
    private val songs = listOf(Synth.take("A", Synth.songA), Synth.take("B", Synth.songB), Synth.take("C", Synth.songC))
    private fun query(progression: List<Synth.Chord>, frames: Int) =
        Synth.chroma(Synth.render(progression, frames / 2.0 + 1, startSeconds = 21.0, seed = 7)).takeLast(frames)

    @Test
    fun `laid flat, the search scores exactly as it did`() {
        val library = songs + List(5) { drawn("d$it", random) }
        for (q in listOf(query(Synth.songA, 24), query(Synth.songB, 72))) {
            for (take in library) assertEquals(reference(q, take), Matcher(config).bestAlignment(q, sumProfile(q), take)!!.score, 0.0, take.songKey)
        }
    }

    @Test
    fun `with a rough pass first, the right song and its score are the same as searching everything`() {
        val library = songs + List(300) { drawn("d$it", random) }
        val full = Matcher(config)
        val rough = Matcher(config.copy(coarseKeep = 12))
        for ((progression, key) in listOf(Synth.songA to "A", Synth.songB to "B", Synth.songC to "C")) {
            for (frames in listOf(24, 72)) {
                val q = query(progression, frames)
                val a = full.match(q, library).best!!
                val b = rough.match(q, library).best!!
                assertEquals(key, a.songKey)
                assertEquals(a.songKey, b.songKey)
                assertEquals(a.score, b.score, 0.0)
            }
        }
    }

    @Test
    fun `the song on screen is always searched, so its slide can still be checked`() {
        val library = songs + List(100) { drawn("d$it", random) }
        val q = query(Synth.songA, 72)
        val result = Matcher(config.copy(coarseKeep = 2)).match(q, library, keep = "d57")
        assertTrue(result.ranked.any { it.songKey == "d57" })
        assertEquals("A", result.best!!.songKey)
        assertTrue(result.ranked.size <= 3)
    }

    @Test
    fun `how long a search takes as the library grows`() {
        val q = query(Synth.songA, 72)
        val sizes = listOf(25, 250, 1000)
        val library = songs + List(sizes.last()) { drawn("d${it / 5}", random, at = it.toLong()) }  // 5 takes a song
        val times = sizes.associateWith { n ->
            val takes = library.take(n)
            listOf(0, 12).associateWith { keep ->
                val m = Matcher(config.copy(coarseKeep = keep))
                m.match(q, takes) // warm up
                val start = System.nanoTime()
                repeat(3) { m.match(q, takes) }
                (System.nanoTime() - start) / 3e6
            }
        }
        times.forEach { (n, t) -> println("matcher: %4d takes  everything %7.1f ms  rough pass first %6.1f ms".format(n, t[0], t[12])) }
        // The rough pass pays off once there are many songs.
        assertTrue(times.getValue(1000)[12]!! < times.getValue(1000)[0]!! / 3, "$times")
    }
}
