package songlistener.core

import kotlin.math.roundToInt

/**
 * A song's best alignment against the query. [rotation] is the transposition in semitones.
 * [section] is the slide that was on screen in the learned take at the point the band has reached
 * now (the end of the query), [SectionMark.INTRO] for the intro, or null if the take has no marks.
 */
data class SongScore(
    val songKey: String,
    val songLabel: String,
    val score: Double,
    val offsetFrame: Int,
    val rotation: Int,
    val stretch: Double,
    val section: Int? = null,
)

/** Songs best-first; [margin] is how far the top song leads the second (its whole score if alone). */
data class MatchResult(val ranked: List<SongScore>) {
    val best: SongScore? get() = ranked.firstOrNull()
    val margin: Double
        get() = when (ranked.size) {
            0 -> 0.0
            1 -> ranked[0].score
            else -> ranked[0].score - ranked[1].score
        }
}

/**
 * Aligns a short query of chroma frames against every learned take: 12 transpositions (narrowed to
 * the [ROTATION_CANDIDATES] most likely per take), a few tempo stretches, every offset. The score
 * is the mean cosine similarity over the aligned frames, -1..1.
 */
class Matcher(private val config: Config = Config()) {

    fun match(query: List<FloatArray>, takes: List<SongTake>): MatchResult {
        if (query.isEmpty()) return MatchResult(emptyList())
        val queryProfile = sumProfile(query)
        val bestPerSong = HashMap<String, SongScore>()
        for (take in takes) {
            val best = bestAlignment(query, queryProfile, take) ?: continue
            val current = bestPerSong[take.songKey]
            if (current == null || best.score > current.score) bestPerSong[take.songKey] = best
        }
        return MatchResult(bestPerSong.values.sortedByDescending { it.score })
    }

    internal fun bestAlignment(query: List<FloatArray>, queryProfile: FloatArray, take: SongTake): SongScore? {
        var best: SongScore? = null
        val rotations = if (config.transpose) candidateRotations(queryProfile, take.profile) else listOf(0)
        for (rotation in rotations) {
            val rotated = query.map { rotate(it, rotation) }
            for (stretch in config.tempoStretches) {
                val span = ((query.size - 1) * stretch).roundToInt() + 1
                if (span > take.frames.size) continue
                for (offset in 0..take.frames.size - span) {
                    var sum = 0.0
                    for (i in rotated.indices) {
                        sum += dot(rotated[i], take.frames[offset + (i * stretch).roundToInt()])
                    }
                    val score = sum / rotated.size
                    if (best == null || score > best.score) {
                        best = SongScore(take.songKey, take.songLabel, score, offset, rotation, stretch,
                            take.sectionAt(offset + span - 1))
                    }
                }
            }
        }
        return best
    }

    internal companion object {
        const val ROTATION_CANDIDATES = 3

        /**
         * The transpositions r for which the query's key profile, shifted up r semitones, best
         * matches the take's: a transposed performance must line up there first.
         */
        fun candidateRotations(queryProfile: FloatArray, takeProfile: FloatArray): List<Int> =
            (0 until 12).sortedByDescending { dot(rotate(queryProfile, it), takeProfile) }
                .take(ROTATION_CANDIDATES)

        /** [v] transposed up [semitones]: the energy of pitch class p moves to p + semitones. */
        fun rotate(v: FloatArray, semitones: Int): FloatArray =
            FloatArray(12) { v[Math.floorMod(it - semitones, 12)] }

        fun dot(a: FloatArray, b: FloatArray): Double {
            var s = 0.0
            for (i in 0 until 12) s += a[i] * b[i]
            return s
        }
    }
}
