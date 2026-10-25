package songlistener.core

import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ClipDetectorTest {
    private val clean = Synth.render(Synth.songA, 30.0)

    private fun gain(s: ShortArray, g: Double, ceiling: Int = 32767) =
        ShortArray(s.size) { (s[it] * g).roundToInt().coerceIn(-ceiling, ceiling).toShort() }

    /** Peak-normalises [s] to [target] of full scale, unclipped. */
    private fun normalise(s: ShortArray, target: Double): ShortArray {
        val peak = s.maxOf { kotlin.math.abs(it.toInt()) }
        return gain(s, target * 32767 / peak)
    }

    @Test
    fun `clean audio, even peaking near full scale, is clean`() {
        for (target in listOf(0.5, 0.98)) {
            val st = ClipDetector.analyze(normalise(clean, target), Synth.RATE)
            assertEquals(0, st.severity(), "$target: $st")
        }
    }

    @Test
    fun `audio driven into full scale is heavily clipped`() {
        val st = ClipDetector.analyze(gain(normalise(clean, 0.5), 4.0), Synth.RATE)
        assertEquals(2, st.severity(), st.toString())
        assertTrue(st.fullScale > 0.001)
    }

    @Test
    fun `clipping below full scale, softened by a low-pass, is still found`() {
        // Clipped at 70% upstream, then smoothed (as resampling does): never reaches 32767.
        val hard = gain(normalise(clean, 0.5), 4.0, ceiling = (0.7 * 32767).toInt())
        val soft = ShortArray(hard.size) { i -> ((hard[maxOf(0, i - 1)] + 2 * hard[i] + hard[minOf(hard.size - 1, i + 1)]) / 4).toShort() }
        val st = ClipDetector.analyze(normalise(soft, 0.99), Synth.RATE)
        assertEquals(0, st.fullScaleSamples)
        assertEquals(2, st.severity(), st.toString())
    }

    @Test
    fun `silence is clean`() {
        val st = ClipDetector.analyze(ShortArray(16_000), Synth.RATE)
        assertEquals(0, st.severity())
        assertEquals(-96.0, st.peakDb)
        assertEquals(0, ClipDetector.analyze(ShortArray(0), Synth.RATE).severity())
    }

    @Test
    fun `a live chunk counts only clipping at full scale`() {
        val chunk = normalise(clean, 0.6).copyOfRange(0, 16_000)
        assertEquals(0, ClipDetector.analyze(chunk, Synth.RATE, ClipDetector.LIVE_LEVEL).clipRuns)
        assertTrue(ClipDetector.analyze(gain(chunk, 4.0), Synth.RATE, ClipDetector.LIVE_LEVEL).clipRuns > 0)
    }
}
