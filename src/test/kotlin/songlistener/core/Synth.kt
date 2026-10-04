package songlistener.core

import kotlin.math.PI
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/** Synthesised test audio: chord progressions as sine triads with harmonics, a bass note and noise. */
object Synth {
    const val RATE = 16_000

    /** A chord as MIDI note numbers; the first is the root. */
    class Chord(vararg val notes: Int)

    private fun major(root: Int) = Chord(root, root + 4, root + 7)
    private fun minor(root: Int) = Chord(root, root + 3, root + 7)

    // Roots around middle C (C4 = 60).
    val C = major(60); val D = major(62); val E = major(64); val F = major(65); val G = major(67)
    val A = major(69); val Bb = major(70)
    val Am = minor(69); val Dm = minor(62); val Em = minor(64); val Bm = minor(71); val Cm = minor(60)
    val Fm = minor(65); val Gm = minor(67)

    /** I–V–vi–IV–I–IV–V–vi */
    val songA = listOf(C, G, Am, F, C, F, G, Am)
    /** Dm–Bb–F–C–Gm–Dm–A–Dm: a different harmonic path */
    val songB = listOf(Dm, Bb, F, C, Gm, Dm, A, Dm)
    /** Em–Bm–Cm–Fm–E–D–Bm–Em: never learned, used to test rejection */
    val songC = listOf(Em, Bm, Cm, Fm, E, D, Bm, Em)

    /**
     * Renders [seconds] of [progression] looped, one chord per [secondsPerChord] / [tempo], every note
     * raised [transpose] semitones, starting [startSeconds] into the (untempo'd) song.
     */
    fun render(
        progression: List<Chord>,
        seconds: Double,
        secondsPerChord: Double = 2.0,
        transpose: Int = 0,
        tempo: Double = 1.0,
        startSeconds: Double = 0.0,
        noise: Double = 0.02,
        seed: Int = 1,
    ): ShortArray {
        val random = Random(seed)
        val n = (seconds * RATE).toInt()
        val chordLen = secondsPerChord / tempo
        val out = ShortArray(n)
        for (i in 0 until n) {
            val t = i.toDouble() / RATE
            val songT = startSeconds / tempo + t
            val index = (songT / chordLen).toInt()
            val chord = progression[index % progression.size]
            val sinceChord = songT - index * chordLen
            val env = min(1.0, sinceChord / 0.02) * (0.6 + 0.4 * (1 - sinceChord / chordLen))
            var v = 0.0
            for (note in chord.notes) v += tone(note + transpose, t, 0.12)
            v += tone(chord.notes[0] + transpose - 24, t, 0.10)
            v = v * env + noise * (random.nextDouble() * 2 - 1)
            out[i] = (v.coerceIn(-1.0, 1.0) * 32767).toInt().toShort()
        }
        return out
    }

    private fun tone(midi: Int, t: Double, amp: Double): Double {
        val f = 440.0 * 2.0.pow((midi - 69) / 12.0)
        return amp * (sin(2 * PI * f * t) + 0.5 * sin(4 * PI * f * t) + 0.25 * sin(6 * PI * f * t))
    }

    fun chroma(samples: ShortArray, config: Config = Config()): List<FloatArray> =
        ChromaExtractor(config).push(samples).filter { it.voiced }.map { it.chroma }

    fun take(key: String, progression: List<Chord>, seconds: Double = 60.0, seed: Int = 1) =
        SongTake(key, key, 0L, chroma(render(progression, seconds, seed = seed)), emptyList())

    /** Splits into the 1-second chunks the STT server sends. */
    fun chunks(samples: ShortArray): List<ShortArray> =
        (samples.indices step RATE).map { samples.copyOfRange(it, min(it + RATE, samples.size)) }
}
