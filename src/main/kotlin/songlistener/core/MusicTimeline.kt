package songlistener.core

import kotlin.math.sqrt

/**
 * Tells music from speech in a recording without STT's music reading, for services imported after
 * the fact. Sustained chords keep the 12-note profile nearly the same from one 0.1 s frame to the
 * next; speech jumps around. On 2026-09-30 a threshold of 0.75 marked 96% of song seconds as music
 * and 2% of the (equally loud) sermon — loudness alone could not separate them.
 */
object MusicTimeline {
    const val DEFAULT_THRESHOLD = 0.75
    const val MIN_RUN_SECONDS = 3

    /** Per second: the mean cosine similarity between consecutive raw frames (0 where silent). */
    fun steadiness(samples: ShortArray, config: Config = Config()): DoubleArray {
        val seconds = samples.size / config.sampleRate
        val sum = DoubleArray(seconds + 1)
        val count = IntArray(seconds + 1)
        var previous: DoubleArray? = null
        var frame = 0
        val extractor = ChromaExtractor(config, onRawFrame = { raw: DoubleArray? ->
            val centred = raw?.let(::centre)
            val prev = previous
            // A frame's window ends at fftSize + frame·hop samples; place it at its centre.
            val at = ((config.fftSize / 2 + frame * config.hopSize) / config.sampleRate).coerceAtMost(seconds)
            sum[at] += if (centred != null && prev != null) dot(centred, prev) else 0.0
            count[at]++
            previous = centred
            frame++
        })
        var pos = 0
        while (pos < samples.size) {
            val end = minOf(pos + config.sampleRate, samples.size)
            extractor.push(samples.copyOfRange(pos, end))
            pos = end
        }
        return DoubleArray(seconds) { if (count[it] == 0) 0.0 else sum[it] / count[it] }
    }

    /**
     * Music on/off changes, as (second, on), from per-second [steadiness]: on at or above
     * [threshold], gaps up to [dipToleranceSeconds] bridged, runs shorter than [MIN_RUN_SECONDS]
     * dropped. Starts off.
     */
    fun changes(
        steadiness: DoubleArray,
        threshold: Double = DEFAULT_THRESHOLD,
        dipToleranceSeconds: Int = 2,
    ): List<Pair<Int, Boolean>> {
        val on = BooleanArray(steadiness.size) { steadiness[it] >= threshold }
        // Bridge short gaps between music.
        var i = 0
        while (i < on.size) {
            if (!on[i]) {
                var j = i
                while (j < on.size && !on[j]) j++
                if (i > 0 && j < on.size && j - i <= dipToleranceSeconds) for (k in i until j) on[k] = true
                i = j
            } else i++
        }
        // Drop runs too short to be a song.
        i = 0
        while (i < on.size) {
            if (on[i]) {
                var j = i
                while (j < on.size && on[j]) j++
                if (j - i < MIN_RUN_SECONDS) for (k in i until j) on[k] = false
                i = j
            } else i++
        }
        val out = mutableListOf<Pair<Int, Boolean>>()
        var state = false
        for (s in on.indices) if (on[s] != state) { state = on[s]; out += s to state }
        return out
    }

    private fun centre(v: DoubleArray): DoubleArray {
        val mean = v.average()
        val c = DoubleArray(12) { v[it] - mean }
        val n = sqrt(c.sumOf { it * it })
        if (n > 0) for (k in 0 until 12) c[k] /= n
        return c
    }

    private fun dot(a: DoubleArray, b: DoubleArray): Double {
        var s = 0.0
        for (k in 0 until 12) s += a[k] * b[k]
        return s
    }
}
