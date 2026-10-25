package songlistener.core

/**
 * Why a recording may be labelled with the wrong song. [severity] 2 = likely wrong, 1 = worth a
 * listen, 0 = a note about the recording.
 */
data class ReviewFlag(val code: String, val text: String, val severity: Int)

/** A recording's check: how it scores against its own song and the best other song, and the flags. */
data class ReviewCheck(
    val ownScore: Double?,
    val rivalKey: String?,
    val rivalLabel: String?,
    val rivalScore: Double?,
    val flags: List<ReviewFlag>,
    /** Music held before the slide went up and learned as the intro, and how much of it sounds like the song. */
    val introSeconds: Double? = null,
    val introMatchedSeconds: Double? = null,
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
    /** Held music this much longer than the part that sounds like the song gets a note. */
    const val LONG_UNMATCHED_INTRO = 15.0

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
        level: ClipLevel? = null,
    ): ReviewCheck {
        val flags = mutableListOf<ReviewFlag>()
        var own: Double? = null
        var rival: SongScore? = null
        if (take == null && labels.relearnRequestedAt != null) {
            flags += ReviewFlag("relearn", "What was learned from it was dropped (too hot); the song relearns the next time it's played", 0)
        } else if (take == null) {
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
        var introSeconds: Double? = null
        var introMatched: Double? = null
        if (take != null) {
            val frames = IntroFilter.introFrames(take)
            if (frames > 0) {
                val matched = IntroFilter.introFrames(IntroFilter.apply(take, config.copy(introMode = "match")))
                introSeconds = frames / config.featuresPerSecond
                introMatched = matched / config.featuresPerSecond
                if (introSeconds - introMatched >= LONG_UNMATCHED_INTRO) {
                    flags += ReviewFlag(
                        "intro",
                        "%.0f s of music before the slide, %.0f s of it sounding like this song: the rest was likely something else".format(introSeconds, introMatched) +
                            if (config.introMode == "keep") " (and is being learned)" else " (not used for matching)",
                        0,
                    )
                }
            }
        }
        if (level != null && level.severity > 0) {
            // Informational only (severity 0): clipping doesn't make the label wrong, and replay
            // showed clipped recordings still recognised; it does tell the input gain was too hot.
            val worst = level.worstSecond?.let { " — worst at %d:%02d".format(it / 60, it % 60) } ?: ""
            flags += ReviewFlag(
                "clipped",
                "${if (level.severity >= 2) "Heavily clipped" else "Clipped"}: in %.0f%% of its seconds%s (input too hot)".format(level.clippedFraction * 100, worst),
                0,
            )
        }
        return ReviewCheck(own, rival?.songKey, rival?.songLabel, rival?.score, flags, introSeconds, introMatched)
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
