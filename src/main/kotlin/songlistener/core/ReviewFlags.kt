package songlistener.core

/** Why a recording may be labelled with the wrong song. [severity] 2 = likely wrong, 1 = worth a listen. */
data class ReviewFlag(val code: String, val text: String, val severity: Int)

/** A recording's check: how it scores against its own song and the best other song, and the flags. */
data class ReviewCheck(
    val ownScore: Double?,
    val rivalKey: String?,
    val rivalLabel: String?,
    val rivalScore: Double?,
    val flags: List<ReviewFlag>,
) {
    val severity: Int get() = flags.maxOfOrNull { it.severity } ?: 0
}

/**
 * Flags recordings whose label may be wrong — an operator who put up the wrong slide teaches the
 * listener the wrong song, so these are what a person should listen to and correct.
 */
object ReviewFlags {
    /** A rival this good, clearly ahead of the song's own recordings, means "sounds like another song". */
    const val RIVAL_SCORE = 0.75
    const val RIVAL_LEAD = 0.05
    /** On screen this briefly and then replaced by another song: maybe a wrong click. */
    const val SHORT_SECONDS = 90.0
    private const val WINDOWS = 3

    /**
     * Checks one recording, given the take learned from it ([take], null if none) and everything
     * learned ([all]). [editionUncertain] comes from the lyrics capture.
     */
    fun check(
        labels: ClipLabels,
        take: SongTake?,
        all: List<SongTake>,
        matcher: Matcher,
        config: Config = Config(),
        editionUncertain: Boolean = false,
    ): ReviewCheck {
        val flags = mutableListOf<ReviewFlag>()
        var own: Double? = null
        var rival: SongScore? = null
        if (take == null) {
            flags += ReviewFlag("no_take", "No take was learned from it, so it can't be checked", 1)
        } else {
            val others = all.filter { !(it.songKey == take.songKey && it.recordedAt == take.recordedAt) }
            val scores = HashMap<String, MutableList<SongScore>>()
            for (w in windows(take, config.steadyFrames)) {
                matcher.match(w, others).ranked.forEach { scores.getOrPut(it.songKey) { mutableListOf() } += it }
            }
            val avg = scores.mapValues { (_, v) -> v.first().copy(score = v.sumOf { it.score } / v.size) }
            own = avg[take.songKey]?.score
            rival = avg.values.filter { it.songKey != take.songKey }.maxByOrNull { it.score }
            val r = rival
            if (r != null && r.score >= RIVAL_SCORE && r.score >= (own ?: 0.0) + RIVAL_LEAD) {
                val vs = own?.let { " (%.2f, against %.2f for its own song's other recordings)".format(r.score, it) }
                    ?: " (%.2f; no other recording of this song to compare)".format(r.score)
                flags += ReviewFlag("sounds_like", "Sounds more like ${r.songLabel}$vs", if (own != null) 2 else 1)
            }
        }
        val onScreen = (labels.offScreenSeconds ?: labels.endSeconds) - labels.goLiveSeconds
        if (labels.endReason == "next_song" && onScreen < SHORT_SECONDS) {
            flags += ReviewFlag("short", "On screen only ${onScreen.toInt()} s before another song replaced it", 1)
        }
        if (editionUncertain) {
            flags += ReviewFlag("edition", "Songbook edition uncertain (same number and title in two songbooks)", 0)
        }
        return ReviewCheck(own, rival?.songKey, rival?.songLabel, rival?.score, flags)
    }

    /** Up to [WINDOWS] evenly spread windows of the song itself (after any intro). */
    internal fun windows(take: SongTake, length: Int): List<List<FloatArray>> {
        val introEnd = take.sectionMarks.firstOrNull { it.section != SectionMark.INTRO }?.frame ?: 0
        val song = take.frames.drop(introEnd)
        val n = minOf(length, song.size)
        if (n < 20) return emptyList()
        val span = song.size - n
        return (0 until WINDOWS).map { i -> val start = span * (i + 1) / (WINDOWS + 1); song.subList(start, start + n) }
            .distinctBy { System.identityHashCode(it.first()) }
    }
}
