package songlistener.core

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * How loud a stretch of audio is and whether it clipped. [peak] and [rmsDb] are of full scale;
 * a "clip run" is [ClipDetector.MIN_RUN] or more samples in a row stuck at the clip level.
 */
data class ClipStats(
    val samples: Int,
    val sampleRate: Int,
    /** Loudest sample, 0..1 of full scale. */
    val peak: Double,
    val rmsDb: Double,
    /** Samples at digital full scale (±32767, or −32768). */
    val fullScaleSamples: Int,
    val clipRuns: Int,
    /** Whole seconds holding at least one clip run, and the worst of them (seconds from the start). */
    val clippedSeconds: Int,
    val worstSecond: Int?,
    val worstSecondRuns: Int,
) {
    val seconds: Double get() = samples.toDouble() / sampleRate
    val fullScale: Double get() = if (samples == 0) 0.0 else fullScaleSamples.toDouble() / samples
    val peakDb: Double get() = if (peak <= 0) -96.0 else 20 * log10(peak)
    val clippedFraction: Double get() = if (seconds <= 0) 0.0 else clippedSeconds / seconds

    /** 0 clean, 1 light (worth a listen), 2 heavy. */
    fun severity(config: Config = Config()): Int = when {
        clippedSeconds >= config.clipHeavySeconds && clippedFraction >= config.clipHeavyFraction -> 2
        clippedSeconds >= config.clipLightSeconds -> 1
        else -> 0
    }
}

/**
 * Finds clipping in 16-bit audio. Clipping shows as flat tops: runs of samples held at one level.
 * At exactly full scale when the clip happened in this signal's own 16 bits; just under the
 * recording's own peak when it happened earlier (a hot preamp or 48 kHz capture) and resampling
 * to 16 kHz softened the edges — so the clip level is the recording's own peak unless given.
 */
object ClipDetector {
    /** Samples in a row at the clip level that make a clip run; clean audio rarely holds a peak this long. */
    const val MIN_RUN = 3
    /** How close to the clip level a sample must be to count as held there. */
    const val LEVEL_TOLERANCE = 0.005
    /** A recording whose own peak is below this can't have clipped. */
    const val MIN_CLIP_PEAK = 0.9

    /**
     * Measures [samples]. [clipLevel] (0..1 of full scale) fixes the level counted as clipped —
     * a 1 s live chunk has too little audio to trust its own peak, so it passes [LIVE_LEVEL].
     */
    fun analyze(samples: ShortArray, sampleRate: Int, clipLevel: Double? = null): ClipStats {
        var peakAbs = 0
        var energy = 0.0
        var fullScale = 0
        for (s in samples) {
            val a = abs(s.toInt())
            if (a > peakAbs) peakAbs = a
            if (a >= 32767) fullScale++
            energy += s.toDouble() * s
        }
        val peak = peakAbs / 32768.0
        val rms = if (samples.isEmpty()) 0.0 else sqrt(energy / samples.size) / 32768.0
        val rmsDb = if (rms <= 0) -96.0 else 20 * log10(rms)

        val level = clipLevel ?: peak.takeIf { it >= MIN_CLIP_PEAK }
        var runs = 0
        val perSecond = HashMap<Int, Int>()
        if (level != null && peak >= level * (1 - LEVEL_TOLERANCE)) {
            val threshold = (level * (1 - LEVEL_TOLERANCE) * 32768).toInt()
            var runStart = -1
            var runSign = 0
            fun close(end: Int) {
                if (runStart >= 0 && end - runStart >= MIN_RUN) {
                    runs++
                    perSecond.merge(runStart / sampleRate, 1, Int::plus)
                }
                runStart = -1
            }
            for (i in samples.indices) {
                val s = samples[i].toInt()
                val sign = if (s > 0) 1 else -1
                if (abs(s) >= threshold) {
                    if (runStart >= 0 && sign != runSign) close(i)
                    if (runStart < 0) { runStart = i; runSign = sign }
                } else close(i)
            }
            close(samples.size)
        }
        val worst = perSecond.maxByOrNull { it.value }
        return ClipStats(samples.size, sampleRate, peak, rmsDb, fullScale, runs, perSecond.size, worst?.key, worst?.value ?: 0)
    }

    /** The clip level used live: only runs at (nearly) digital full scale count. */
    const val LIVE_LEVEL = 0.99
}
