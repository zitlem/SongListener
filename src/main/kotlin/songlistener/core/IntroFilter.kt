package songlistener.core

/**
 * What of a take's intro — the music held before its slide went up — is used for matching.
 * Held music is not always the intro: another performance before the song, or a band playing on
 * without a break, gets held too. `keep` uses all of it; `drop` none; `match` keeps only the end
 * of it that sounds like the song itself — or like the intro of another performance of it, for a
 * song whose intro is its own tune — in its key and tempo, back to the first stretch that doesn't. Applied when takes are loaded, never to what is stored, so the choice can change.
 * On 2026-10-11's recordings `match` kept every short intro (1–18 s) and dropped all of four 63–90 s
 * ones that sounded nothing like their song.
 */
object IntroFilter {
    /** Length of each stretch of intro checked (12 s), and how far apart they start (6 s). */
    const val WINDOW = 24
    const val HOP = 12
    /** Intro shorter than this is kept as it is: too little to judge. */
    const val MIN_FRAMES = 8

    /** [take] as matched on; [siblings] are the song's other takes, stored whole. */
    fun apply(take: SongTake, config: Config, siblings: List<SongTake> = emptyList()): SongTake {
        val introEnd = introFrames(take)
        if (introEnd == 0 || config.introMode == "keep") return take
        val keepFrom = if (config.introMode == "drop") introEnd else matchingStart(take, introEnd, config, siblings)
        return if (keepFrom == 0) take else trim(take, keepFrom, introEnd)
    }

    /** Every take as matched on, each judged against its song's other takes. */
    fun applyAll(takes: List<SongTake>, config: Config): List<SongTake> {
        if (config.introMode == "keep") return takes
        val bySong = takes.groupBy { it.songKey }
        return takes.map { t -> apply(t, config, bySong.getValue(t.songKey).filter { it !== t }) }
    }

    /** Frames of intro at the start of [take]: up to its first mark that is not the intro. */
    fun introFrames(take: SongTake): Int {
        if (take.sectionMarks.firstOrNull()?.section != SectionMark.INTRO) return 0
        return take.sectionMarks.firstOrNull { it.section != SectionMark.INTRO }?.frame ?: 0
    }

    /** The first intro frame worth keeping: everything after it, up to the slide, sounds like the song. */
    internal fun matchingStart(take: SongTake, introEnd: Int, config: Config, siblings: List<SongTake> = emptyList()): Int {
        val body = take.frames.subList(introEnd, take.frames.size)
        if (introEnd < MIN_FRAMES || body.size < WINDOW) return 0
        val song = SongTake(take.songKey, take.songLabel, take.recordedAt, body, emptyList())
        val otherIntros = siblings.mapNotNull { s ->
            val n = introFrames(s)
            if (n < MIN_FRAMES) null else SongTake(s.songKey, s.songLabel, s.recordedAt, s.frames.subList(0, n), emptyList())
        }
        // In the song's own key and tempo: an intro is the band starting this song, not another.
        val strict = Matcher(config.copy(transpose = false, tempoStretches = listOf(1.0), cohort = false))
        var keepFrom = introEnd
        var end = introEnd
        while (end > 0) {
            val start = maxOf(0, end - WINDOW)
            if (end - start < MIN_FRAMES) break
            val w = take.frames.subList(start, end)
            val profile = sumProfile(w)
            val score = (listOf(song) + otherIntros).maxOf { strict.bestAlignment(w, profile, it)?.score ?: 0.0 }
            if (score < config.introMatchScore) break
            keepFrom = start
            end -= HOP
        }
        return keepFrom
    }

    private fun trim(take: SongTake, keepFrom: Int, introEnd: Int): SongTake {
        val frames = take.frames.subList(keepFrom, take.frames.size)
        val marks = take.sectionMarks.mapNotNull { m ->
            when {
                m.section == SectionMark.INTRO -> if (keepFrom < introEnd) SectionMark(0, SectionMark.INTRO) else null
                else -> SectionMark(m.frame - keepFrom, m.section)
            }
        }.distinct()
        return SongTake(take.songKey, take.songLabel, take.recordedAt, frames, marks, take.clipped)
    }
}
