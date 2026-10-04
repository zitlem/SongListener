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
) {
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
