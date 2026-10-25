package songlistener.core

/**
 * Searches only the parts of a song that more than one performance of it shares. Each stretch of
 * a take is checked against the song's other takes (any key or tempo: the band may play it
 * differently each time); stretches found in no other take — talking, applause, a solo, other
 * music — are left out, and what remains is matched as separate pieces so no search window spans
 * a gap. A song with one take, or a take sharing nothing with the others (which is wrong can't be
 * told), is used whole. Applied when takes are loaded; stored takes stay whole.
 */
object ConfirmedParts {
    /** Length of each stretch checked (12 s) and how far apart they start (4 s). */
    const val WINDOW = 24
    const val HOP = 8

    /** One song's [takes], cut to their confirmed pieces when [Config.confirmedParts] is on. */
    fun apply(takes: List<SongTake>, config: Config, matcher: Matcher = Matcher(config)): List<SongTake> {
        if (!config.confirmedParts || takes.size < 2) return takes
        return takes.flatMap { t -> pieces(t, takes.filter { it !== t }, config, matcher) }
    }

    /** Every song's takes, each song judged on its own. */
    fun applyAll(takes: List<SongTake>, config: Config): List<SongTake> {
        if (!config.confirmedParts) return takes
        val matcher = Matcher(config)
        return takes.groupBy { it.songKey }.values.flatMap { apply(it, config, matcher) }
    }

    /**
     * Results by take and siblings: replay builds a recogniser per recording tested, each loading
     * the same takes, and checking a song's takes against each other takes seconds.
     */
    private val cache = java.util.concurrent.ConcurrentHashMap<String, List<SongTake>>()

    internal fun pieces(take: SongTake, siblings: List<SongTake>, config: Config, matcher: Matcher): List<SongTake> {
        fun id(t: SongTake) = "${t.recordedAt}:${t.frames.size}"
        val key = "${take.songKey}|${id(take)}|${siblings.map(::id).sorted()}|${config.confirmScore}|${config.queryFrames}|" +
            "${config.transpose}|${config.tempoStretches}"
        return cache.getOrPut(key) { computePieces(take, siblings, config, matcher) }
    }

    private fun computePieces(take: SongTake, siblings: List<SongTake>, config: Config, matcher: Matcher): List<SongTake> {
        val n = take.frames.size
        if (siblings.isEmpty() || n < WINDOW) return listOf(take)
        val confirmed = BooleanArray(n)
        var start = 0
        while (true) {
            val end = minOf(start + WINDOW, n)
            val w = take.frames.subList(start, end)
            val profile = sumProfile(w)
            if (siblings.any { (matcher.bestAlignment(w, profile, it)?.score ?: 0.0) >= config.confirmScore }) {
                for (i in start until end) confirmed[i] = true
            }
            if (end == n) break
            start = minOf(start + HOP, n - WINDOW)
        }
        val runs = mutableListOf<IntRange>()
        var i = 0
        while (i < n) {
            if (!confirmed[i]) { i++; continue }
            var j = i
            while (j < n && confirmed[j]) j++
            if (j - i >= config.queryFrames) runs += i until j
            i = j
        }
        if (runs.isEmpty()) return listOf(take)
        if (runs.size == 1 && runs[0].first == 0 && runs[0].last == n - 1) return listOf(take)
        return runs.map { r ->
            val marks = buildList {
                take.sectionAt(r.first)?.let { add(SectionMark(0, it)) }
                take.sectionMarks.filter { it.frame > r.first && it.frame <= r.last }.forEach { add(SectionMark(it.frame - r.first, it.section)) }
            }
            SongTake(take.songKey, take.songLabel, take.recordedAt, take.frames.subList(r.first, r.last + 1), marks, take.clipped)
        }
    }
}
