package songlistener.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.ln1p
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * A stored chroma frame: 12 pitch-class energies (C, C#, … B), mean-centred and L2-normalised, averaged over
 * [Config.framesPerFeature] raw frames. [voiced] is false when most of those raw frames were silent,
 * in which case [chroma] is all zeros.
 */
class ChromaFrame(val chroma: FloatArray, val voiced: Boolean)

/**
 * Streaming int16 PCM → [ChromaFrame]s. Feed any chunk size to [push]; it returns the stored frames
 * completed by that chunk. Not thread-safe.
 */
class ChromaExtractor(
    private val config: Config = Config(),
    /** Called for every raw 0.1 s frame: its normalised chroma, or null when it was silent. */
    private val onRawFrame: ((DoubleArray?) -> Unit)? = null,
    /**
     * What each frame contributes: `chroma` (the energy of all 12 notes — the harmony) or `melody`
     * (the note of the strongest single line — what the congregation sings in unison).
     */
    private val feature: String = "chroma",
) {
    private val binHz = config.sampleRate.toDouble() / config.fftSize
    /** Candidate melody pitches, MIDI 45–81 (110–880 Hz) in third-of-a-semitone steps. */
    private val melodyMidi = DoubleArray(((MELODY_TOP - MELODY_BOTTOM) * 3).toInt() + 1) { MELODY_BOTTOM + it / 3.0 }
    private val harmonicWeight = DoubleArray(MELODY_HARMONICS) { Math.pow(HARMONIC_DECAY, it.toDouble()) }

    private val fft = Fft(config.fftSize)
    private val window = DoubleArray(config.fftSize) { 0.5 - 0.5 * cos(2.0 * PI * it / (config.fftSize - 1)) }

    /** Pitch class of each FFT bin, or -1 outside [Config.minFreqHz]..[Config.maxFreqHz]. */
    private val binPitchClass = IntArray(config.fftSize / 2 + 1) { bin ->
        val freq = bin.toDouble() * config.sampleRate / config.fftSize
        if (freq < config.minFreqHz || freq > config.maxFreqHz) -1
        else Math.floorMod((12 * ln(freq / A4_HZ) / ln(2.0)).roundToInt() + A_PITCH_CLASS, 12)
    }

    /** Circular buffer of the last [Config.fftSize] samples; [ringPos] is the next write index. */
    private val ring = DoubleArray(config.fftSize)
    private var ringPos = 0
    private var ringFill = 0
    private var sinceLastHop = 0
    private val frameBuf = DoubleArray(config.fftSize)

    private val accum = DoubleArray(12)
    private var accumCount = 0
    private var accumVoiced = 0

    fun push(samples: ShortArray): List<ChromaFrame> {
        val out = ArrayList<ChromaFrame>()
        for (s in samples) {
            ring[ringPos] = s / 32768.0
            ringPos = (ringPos + 1) % ring.size
            if (ringFill < ring.size) ringFill++
            sinceLastHop++
            if (ringFill == ring.size && sinceLastHop >= config.hopSize) {
                sinceLastHop = 0
                rawFrame()?.let { out += it }
            }
        }
        return out
    }

    fun reset() {
        ring.fill(0.0); ringPos = 0; ringFill = 0; sinceLastHop = 0
        accum.fill(0.0); accumCount = 0; accumVoiced = 0
    }

    private fun rawFrame(): ChromaFrame? {
        // Unroll the ring oldest-first.
        for (i in 0 until config.fftSize) frameBuf[i] = ring[(ringPos + i) % ring.size]
        var energy = 0.0
        for (i in config.fftSize - config.hopSize until config.fftSize) energy += frameBuf[i] * frameBuf[i]
        val rms = sqrt(energy / config.hopSize)
        if (rms >= config.silenceRms) {
            for (i in 0 until config.fftSize) frameBuf[i] *= window[i]
            val mags = fft.magnitudes(frameBuf)
            // Power of spectral peaks only, then log-compressed per pitch class. Summing every bin
            // (or logging each bin) lets the noise floor across hundreds of bins outweigh the notes.
            val chroma = DoubleArray(12)
            for (bin in 1 until mags.size - 1) {
                val pc = binPitchClass[bin]
                val m = mags[bin]
                if (pc >= 0 && m > mags[bin - 1] && m >= mags[bin + 1]) chroma[pc] += m * m
            }
            val max = chroma.max()
            if (max > 0) for (i in 0 until 12) chroma[i] = ln1p(LOG_GAIN * chroma[i] / max)
            normalize(chroma)
            onRawFrame?.invoke(chroma.copyOf())
            if (feature == "melody") melody(mags)?.let { m -> for (i in 0 until 12) accum[i] += m[i] }
            else for (i in 0 until 12) accum[i] += chroma[i]
            accumVoiced++
        } else {
            onRawFrame?.invoke(null)
        }
        accumCount++
        if (accumCount < config.framesPerFeature) return null

        val voiced = accumVoiced * 2 > config.framesPerFeature
        val result = FloatArray(12)
        if (voiced) {
            // Mean-centred before normalising: an all-positive vector scores ~0.8 cosine against
            // anything, which would leave no room between a match and a miss.
            val mean = accum.average()
            for (i in 0 until 12) accum[i] -= mean
            normalize(accum)
            for (i in 0 until 12) result[i] = accum[i].toFloat()
        }
        accum.fill(0.0); accumCount = 0; accumVoiced = 0
        return ChromaFrame(result, voiced)
    }

    /**
     * The melody note of one frame, as a 12-note vector weighted by how clearly it stands out, or
     * null when nothing does. Each candidate pitch is scored by the energy at it and its harmonics
     * (harmonic summation): a sung line stacks harmonics on one fundamental, a spread chord does
     * not. Octaves fold together, so an octave mistake costs nothing.
     */
    private fun melody(mags: DoubleArray): DoubleArray? {
        var best = -1
        var bestScore = 0.0
        var total = 0.0
        for ((c, midi) in melodyMidi.withIndex()) {
            val f0 = A4_HZ * Math.pow(2.0, (midi - 69) / 12.0)
            var score = 0.0
            for (h in 0 until MELODY_HARMONICS) {
                val bin = ((h + 1) * f0 / binHz).roundToInt()
                if (bin + 1 >= mags.size) break
                score += harmonicWeight[h] * maxOf(mags[bin - 1], mags[bin], mags[bin + 1])
            }
            total += score
            if (score > bestScore) { bestScore = score; best = c }
        }
        if (best < 0 || total <= 0) return null
        // How far the winner stands out from the average candidate: 0 for a flat spectrum.
        val clarity = bestScore / (total / melodyMidi.size) - 1
        if (clarity <= 0) return null
        val pc = ((melodyMidi[best] % 12) + 12) % 12
        val lo = pc.toInt() % 12
        val frac = pc - pc.toInt()
        return DoubleArray(12).also {
            it[lo] += (1 - frac) * clarity
            it[(lo + 1) % 12] += frac * clarity
        }
    }

    private companion object {
        const val MELODY_BOTTOM = 45.0
        const val MELODY_TOP = 81.0
        const val MELODY_HARMONICS = 8
        const val HARMONIC_DECAY = 0.8
        const val A4_HZ = 440.0
        const val A_PITCH_CLASS = 9
        const val LOG_GAIN = 100.0

        fun normalize(v: DoubleArray) {
            var sum = 0.0
            for (x in v) sum += x * x
            val norm = sqrt(sum)
            if (norm > 0) for (i in v.indices) v[i] /= norm
        }
    }
}
