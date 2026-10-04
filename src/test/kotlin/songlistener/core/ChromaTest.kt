package songlistener.core

import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChromaTest {

    @Test
    fun `fft puts a pure sine in its bin`() {
        val fft = Fft(1024)
        val mags = fft.magnitudes(DoubleArray(1024) { sin(2 * PI * 50 * it / 1024) })
        assertEquals(50, mags.indices.maxBy { mags[it] })
    }

    @Test
    fun `a major triad lights its three pitch classes`() {
        val frames = Synth.chroma(Synth.render(listOf(Synth.C), 3.0, secondsPerChord = 10.0, noise = 0.0))
        assertTrue(frames.isNotEmpty())
        val top3 = frames.last().withIndex().sortedByDescending { it.value }.take(3).map { it.index }.toSet()
        assertEquals(setOf(0, 4, 7), top3) // C, E, G
    }

    @Test
    fun `frames are two per second, unit length, and silence is unvoiced`() {
        val config = Config()
        val frames = ChromaExtractor(config).push(Synth.render(Synth.songA, 10.0))
        // The first FFT window needs 4096 samples before the first hop.
        assertTrue(frames.size in 18..20, "got ${frames.size}")
        val norm = frames.last().chroma.sumOf { (it * it).toDouble() }
        assertEquals(1.0, norm, 1e-4)

        val silent = ChromaExtractor(config).push(ShortArray(Synth.RATE * 3))
        assertTrue(silent.isNotEmpty())
        assertFalse(silent.any { it.voiced })
    }
}
