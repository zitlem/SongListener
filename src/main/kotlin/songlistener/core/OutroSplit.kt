package songlistener.core

/**
 * How much of the music held before a song went live is the previous song still playing on. When
 * a song leaves the screen and the band carries straight on into the next, the look-back holds the
 * end of the first song before the start of the second; kept as the second's intro, it teaches the
 * second song the first one's music.
 *
 * Only music held before the first real break since the previous song left can be its outro (the
 * caller passes that as `candidate`). Within it, a stretch is the previous song's while it sounds
 * like that song — its last recorded seconds or another performance of it — in its key and tempo,
 * and more like it than like the song now live. The previous song was just heard, so even a
 * song never learned before can be judged.
 */
object OutroSplit {
    /** Length of each stretch checked (12 s) and how far apart they start (6 s), as [IntroFilter]. */
    private const val WINDOW = IntroFilter.WINDOW
    private const val HOP = IntroFilter.HOP
    /** Shorter than this (2 s) is too little to judge at all. */
    const val MIN_FRAMES = 4

    /**
     * Frames at the start of [held] that are the previous song's outro: 0 when none are. [candidate]
     * is how many of [held]'s leading frames came before the first break; [previous] is the
     * previous song's music (its last recorded frames, as a take, and its learned takes), [next]
     * the learned takes of the song going live.
     */
    fun boundary(held: List<FloatArray>, candidate: Int, previous: List<SongTake>, next: List<SongTake>, config: Config): Int {
        val limit = minOf(candidate, held.size)
        if (limit < MIN_FRAMES || previous.isEmpty()) return 0
        val strict = Matcher(config.copy(transpose = false, tempoStretches = listOf(1.0), cohort = false))
        fun best(w: List<FloatArray>, refs: List<SongTake>): Double {
            val profile = sumProfile(w)
            return refs.maxOfOrNull { strict.bestAlignment(w, profile, it)?.score ?: 0.0 } ?: 0.0
        }
        var boundary = 0
        var start = 0
        while (start < limit) {
            val end = minOf(start + WINDOW, limit)
            // A short last piece is judged with the frames before it, so it is never too little.
            val from = if (end - start < WINDOW) maxOf(0, end - WINDOW) else start
            if (end - from < MIN_FRAMES) break
            val w = held.subList(from, end)
            val asPrevious = best(w, previous)
            if (asPrevious < config.introMatchScore || asPrevious <= best(w, next)) break
            boundary = end
            if (end == limit) break
            start += HOP
        }
        return boundary
    }
}
