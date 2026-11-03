package songlistener.core

/**
 * The slide on screen from [frame] onward, as ChurchPresenter's section index, [INTRO] for music
 * heard before the song went live, or [OUTRO] for the previous song still playing on after it left
 * the screen (held with the intro, but never this song's).
 */
data class SectionMark(val frame: Int, val section: Int) {
    companion object {
        const val INTRO = -1
        const val OUTRO = -2

        /** Whether [section] is music before the song's first slide rather than a slide. */
        fun isLead(section: Int) = section == INTRO || section == OUTRO
    }
}

/**
 * One performance of a song: the music-only chroma frames in order, learned while it was live and
 * preceded by any intro heard just before it went live (marked [SectionMark.INTRO]).
 * [songKey] identifies the song; [songLabel] is only for display.
 */
class SongTake(
    val songKey: String,
    val songLabel: String,
    val recordedAt: Long,
    val frames: List<FloatArray>,
    val sectionMarks: List<SectionMark>,
    /** Share of its seconds that clipped (input too hot), null if not measured. */
    val clipped: Double? = null,
    /**
     * Recorded at a rehearsal (see `RehearsalDetector`): kept until the song has a take from a real
     * performance, then replaced by it (see `RehearsalPolicy`).
     */
    val rehearsal: Boolean = false,
    /** Someone said whether this was a rehearsal; the detector never overrules them. */
    val markedByHand: Boolean = false,
) {
    /** The same take with [clipped] set. */
    fun withClipped(value: Double?) = SongTake(songKey, songLabel, recordedAt, frames, sectionMarks, value, rehearsal, markedByHand)

    /** The same take marked as a rehearsal or not; [byHand] when a person said so. */
    fun withRehearsal(value: Boolean, byHand: Boolean = markedByHand) =
        SongTake(songKey, songLabel, recordedAt, frames, sectionMarks, clipped, value, byHand)

    /** Frames at the start that are the previous song's outro (see [SectionMark.OUTRO]). */
    val outroFrames: Int
        get() = if (sectionMarks.firstOrNull()?.section != SectionMark.OUTRO) 0
        else sectionMarks.firstOrNull { it.section != SectionMark.OUTRO }?.frame ?: frames.size

    /**
     * Where the song itself starts: its first slide, after any outro and intro held before it. A take
     * with no slide marks (the slide carried no section) has no known end of intro: only an outro is skipped.
     */
    val songStart: Int
        get() = sectionMarks.firstOrNull { !SectionMark.isLead(it.section) }?.frame ?: outroFrames

    /** This take without the previous song's outro; itself when it has none. */
    fun withoutOutro(): SongTake {
        val cut = outroFrames
        if (cut == 0) return this
        val marks = sectionMarks.filter { it.frame >= cut && it.section != SectionMark.OUTRO }.map { SectionMark(it.frame - cut, it.section) }
        return SongTake(songKey, songLabel, recordedAt, frames.subList(cut, frames.size), marks, clipped, rehearsal, markedByHand)
    }

    /** The section on screen at [frame]: the last mark at or before it, or null if there is none. */
    fun sectionAt(frame: Int): Int? = sectionMarks.lastOrNull { it.frame <= frame }?.section

    /** Summed chroma over the whole take — its key profile, used to pick candidate transpositions. */
    val profile: FloatArray by lazy { sumProfile(frames) }

    /** [frames] laid end to end, 12 values each: what the matcher walks. */
    internal val flat: FloatArray by lazy { flatten(frames) }

    /** [frames] averaged [COARSE] at a time, for the matcher's first, rough pass. */
    internal val coarse: FloatArray by lazy { flatten(coarsen(frames)) }

    companion object {
        /** Frames averaged into one for the rough pass (4 = 2 s). */
        const val COARSE = 4
    }
}

internal fun flatten(frames: List<FloatArray>): FloatArray {
    val out = FloatArray(frames.size * 12)
    frames.forEachIndexed { i, f -> f.copyInto(out, i * 12) }
    return out
}

/** Every [SongTake.COARSE] frames averaged and scaled back to unit length, so scores stay cosines. */
internal fun coarsen(frames: List<FloatArray>): List<FloatArray> = frames.chunked(SongTake.COARSE).map { group ->
    val v = sumProfile(group)
    val norm = kotlin.math.sqrt(v.fold(0.0) { s, x -> s + x * x }).toFloat()
    if (norm > 0f) for (i in 0 until 12) v[i] /= norm
    v
}

internal fun sumProfile(frames: List<FloatArray>): FloatArray {
    val sum = FloatArray(12)
    for (f in frames) for (i in 0 until 12) sum[i] += f[i]
    return sum
}
