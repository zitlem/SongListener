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
 *
 * With more than [Config.coarseKeep] songs, a rough pass first ranks every song on frames averaged
 * [SongTake.COARSE] at a time, and only the best are searched in full. Their scores are exactly what
 * the full search gives; the rough pass only decides which songs are searched.
 */
class Matcher(private val config: Config = Config()) {

    /** [keep] is a song always searched in full, whatever the rough pass thinks: the one on screen. */
    fun match(query: List<FloatArray>, takes: List<SongTake>, keep: String? = null): MatchResult {
        if (query.isEmpty()) return MatchResult(emptyList())
        val queryProfile = sumProfile(query)
        val queryFlat = flatten(query)
        // Per song, the best alignment from each recording (a take may be split into pieces that
        // share its recording time; they count as one performance).
        val perRecording = HashMap<String, HashMap<Long, SongScore>>()
        for (take in shortlist(query, queryProfile, takes, keep)) {
            val best = bestAlignment(queryFlat, query.size, queryProfile, take) ?: continue
            val byRec = perRecording.getOrPut(take.songKey) { HashMap() }
            val current = byRec[take.recordedAt]
            if (current == null || best.score > current.score) byRec[take.recordedAt] = best
        }
        val bestPerSong = HashMap<String, SongScore>()
        for ((k, byRec) in perRecording) {
            val ranked = byRec.values.sortedByDescending { it.score }
            val b = ranked.first()
            val second = ranked.getOrNull(1)
            bestPerSong[k] = if (config.takeAgreement > 0 && second != null)
                b.copy(score = b.score - config.takeAgreement * (b.score - second.score)) else b
        }
        if (config.cohort && bestPerSong.size > 1) {
            val bias = Cohort.biases(this, takes, query.size)
            val mean = bias.values.average()
            for ((k, v) in bestPerSong) bias[k]?.let { bestPerSong[k] = v.copy(score = v.score - it + mean) }
        }
        return MatchResult(bestPerSong.values.sortedByDescending { it.score })
    }

    /** The takes to search in full: all of them, or those of the songs the rough pass ranks best. */
    internal fun shortlist(query: List<FloatArray>, queryProfile: FloatArray, takes: List<SongTake>, keep: String?): List<SongTake> {
        val k = config.coarseKeep
        if (k <= 0) return takes
        val coarse = coarsen(query)
        if (coarse.size < MIN_COARSE_FRAMES || takes.distinctBy { it.songKey }.size <= k) return takes
        val coarseFlat = flatten(coarse)
        val roughPerSong = HashMap<String, Double>()
        for (take in takes) {
            val n = take.coarse.size / 12
            val best = align(coarseFlat, coarse.size, queryProfile, take.coarse, n, take.profile) ?: continue
            roughPerSong.merge(take.songKey, best.score, ::maxOf)
        }
        val kept = roughPerSong.entries.sortedByDescending { it.value }.take(k).mapTo(HashSet()) { it.key }
        keep?.let { kept += it }
        return takes.filter { it.songKey in kept }
    }

    internal fun bestAlignment(query: List<FloatArray>, queryProfile: FloatArray, take: SongTake): SongScore? =
        bestAlignment(flatten(query), query.size, queryProfile, take)

    private fun bestAlignment(query: FloatArray, n: Int, queryProfile: FloatArray, take: SongTake): SongScore? {
        val best = align(query, n, queryProfile, take.flat, take.frames.size, take.profile) ?: return null
        return SongScore(take.songKey, take.songLabel, best.score, best.offset, best.rotation, best.stretch,
            take.sectionAt(best.offset + best.span - 1))
    }

    private class Alignment(val score: Double, val offset: Int, val rotation: Int, val stretch: Double, val span: Int)

    /**
     * The best alignment of [n] query frames against [m] take frames, both laid flat. Sums in the
     * same order as one frame's dot product at a time, so scores do not depend on the layout.
     */
    private fun align(query: FloatArray, n: Int, queryProfile: FloatArray, take: FloatArray, m: Int, takeProfile: FloatArray): Alignment? {
        var best: Alignment? = null
        val rotations = if (config.transpose) candidateRotations(queryProfile, takeProfile) else listOf(0)
        val rotated = FloatArray(n * 12)
        val at = IntArray(n)
        for (rotation in rotations) {
            for (i in 0 until n) for (c in 0 until 12) rotated[i * 12 + c] = query[i * 12 + Math.floorMod(c - rotation, 12)]
            for (stretch in config.tempoStretches) {
                val span = ((n - 1) * stretch).roundToInt() + 1
                if (span > m) continue
                for (i in 0 until n) at[i] = (i * stretch).roundToInt() * 12
                for (offset in 0..m - span) {
                    val base = offset * 12
                    var sum = 0.0
                    for (i in 0 until n) {
                        val q = i * 12
                        val t = base + at[i]
                        var d = 0.0
                        for (c in 0 until 12) d += rotated[q + c] * take[t + c]
                        sum += d
                    }
                    val score = sum / n
                    if (best == null || score > best.score) best = Alignment(score, offset, rotation, stretch, span)
                }
            }
        }
        return best
    }

    internal companion object {
        const val ROTATION_CANDIDATES = 3
        /** A rough query shorter than this (8 s) says too little to rank by: everything is searched. */
        const val MIN_COARSE_FRAMES = 4

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

/**
 * Cohort normalisation: how well each song scores, on average, against music that is *not* it.
 * A song with generic harmony scores well against everything and wins by default; subtracting
 * this bias (and adding back the mean, to keep the scale) lets a song win only by matching better
 * than it usually does. Pair scores are cached across matchers — replay rebuilds libraries many
 * times from the same takes.
 */
internal object Cohort {
    private const val WINDOWS_PER_TAKE = 2
    private val pairs = java.util.concurrent.ConcurrentHashMap<String, Double>()

    private fun id(t: SongTake) = "${t.songKey}@${t.recordedAt}"

    /** Song key → mean best score against windows of other songs' takes in [takes]. */
    fun biases(matcher: Matcher, takes: List<SongTake>, length: Int): MutableMap<String, Double> {
        val bySong = takes.groupBy { it.songKey }
        val out = HashMap<String, Double>()
        for ((song, own) in bySong) {
            val scores = takes.filter { it.songKey != song }.flatMap { other ->
                windows(other, length).indices.map { w -> own.maxOf { pair(matcher, other, w, it, length) } }
            }
            if (scores.isNotEmpty()) out[song] = scores.average()
        }
        return out
    }

    private fun windows(t: SongTake, length: Int): List<List<FloatArray>> {
        if (t.frames.size < length) return emptyList()
        val span = t.frames.size - length
        return (1..WINDOWS_PER_TAKE).map { i ->
            val start = span * i / (WINDOWS_PER_TAKE + 1)
            t.frames.subList(start, start + length)
        }
    }

    private fun pair(matcher: Matcher, from: SongTake, window: Int, against: SongTake, length: Int): Double =
        pairs.getOrPut("${id(from)}#$window#$length>${id(against)}") {
            val q = windows(from, length)[window]
            matcher.bestAlignment(q, sumProfile(q), against)?.score ?: 0.0
        }
}
