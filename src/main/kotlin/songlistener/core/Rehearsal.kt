package songlistener.core

/**
 * Tells a rehearsal from a service by how the slides are run, not by the music: a band rehearses
 * at the tempo it plays, so the music alone cannot tell them apart (measured 2026-11-01: every take
 * matched its song's others at ×1.00, rehearsal or not). What does differ is the operator. At a
 * rehearsal they jump between the songs being practised, so songs go up with music playing and come
 * down again before a take could be kept. During a service each song goes up once.
 *
 * Such a jump is a *hop*. A take with at least [minHops] hops within [windowMs] before it started
 * or after it ended is a rehearsal. Hops after it can only be known later, so [hop] returns the
 * takes it newly marks; the caller rewrites them. Times are wall-clock milliseconds.
 */
class RehearsalDetector(private val windowMs: Long, private val minHops: Int) {
    constructor(config: Config) : this((config.rehearsalWindowMinutes * 60_000).toLong(), config.rehearsalHops)

    /** A take saved recently enough that a later hop can still make it a rehearsal. */
    data class TakeRef(val songKey: String, val recordedAt: Long)

    private class Recent(val ref: TakeRef, val startedAt: Long, var rehearsal: Boolean)

    private val hops = ArrayDeque<Long>()
    private val recent = ArrayList<Recent>()
    private var started: Pair<String, Long>? = null

    /** A take of [songKey] began recording at [now]. */
    fun takeStarted(songKey: String, now: Long) {
        started = songKey to now
    }

    /** A take was kept; returns whether it is a rehearsal by what is known so far. */
    fun takeSaved(songKey: String, recordedAt: Long, now: Long = recordedAt): Boolean {
        val start = started?.takeIf { it.first == songKey }?.second ?: recordedAt
        started = null
        forget(now)
        val r = Recent(TakeRef(songKey, recordedAt), start, false)
        r.rehearsal = hopsAround(r) >= minHops
        recent += r
        return r.rehearsal
    }

    /**
     * A take was dropped. It is a hop when it had any music (a song put up and taken down with
     * nothing played is just the operator finding it). Returns the recent takes this makes rehearsals.
     */
    fun takeDiscarded(songSeconds: Double, now: Long): List<TakeRef> {
        started = null
        if (songSeconds <= 0.0) return emptyList()
        hops.addLast(now)
        forget(now)
        return recent.filter { !it.rehearsal && hopsAround(it) >= minHops }.onEach { it.rehearsal = true }.map { it.ref }
    }

    private fun hopsAround(r: Recent): Int = hops.count { it >= r.startedAt - windowMs && it <= r.ref.recordedAt + windowMs }

    private fun forget(now: Long) {
        recent.removeAll { it.ref.recordedAt + windowMs < now }
        // A hop counts toward takes that start up to a window later and toward the recent ones,
        // which began at most a long take (10 min) plus its intro before they were kept.
        while (hops.isNotEmpty() && hops.first() < now - 2 * windowMs - MAX_TAKE_MS) hops.removeFirst()
    }

    private companion object {
        const val MAX_TAKE_MS = 15 * 60_000L
    }
}

/**
 * Rehearsal takes are kept until their song has a take from a real performance, and then replaced
 * by it. A take only counts as real once the detector can no longer change its mind about it — a
 * window after it was kept — or when someone marked it real by hand; otherwise the first song of a
 * rehearsal, kept before the hopping starts, would replace the rehearsal takes it should join.
 */
object RehearsalPolicy {
    /** Whether [take] settles its song as performed for real at [now]. */
    fun isReal(take: SongTake, now: Long, windowMs: Long): Boolean =
        !take.rehearsal && (take.markedByHand || take.recordedAt + windowMs <= now)

    /** The takes of one song to replace now: its rehearsals, once it has a real take. */
    fun toReplace(songTakes: List<SongTake>, now: Long, windowMs: Long): List<SongTake> =
        if (songTakes.any { isReal(it, now, windowMs) }) songTakes.filter { it.rehearsal } else emptyList()
}
