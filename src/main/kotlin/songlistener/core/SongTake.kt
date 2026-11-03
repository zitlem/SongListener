package songlistener.core

/**
 * The slide on screen from [frame] onward, as ChurchPresenter's section index, or [INTRO] for music
 * heard before the song went live.
 */
data class SectionMark(val frame: Int, val section: Int) {
    companion object {
        const val INTRO = -1
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

    /** The section on screen at [frame]: the last mark at or before it, or null if there is none. */
    fun sectionAt(frame: Int): Int? = sectionMarks.lastOrNull { it.frame <= frame }?.section

    /** Summed chroma over the whole take — its key profile, used to pick candidate transpositions. */
    val profile: FloatArray by lazy { sumProfile(frames) }
}

internal fun sumProfile(frames: List<FloatArray>): FloatArray {
    val sum = FloatArray(12)
    for (f in frames) for (i in 0 until 12) sum[i] += f[i]
    return sum
}
