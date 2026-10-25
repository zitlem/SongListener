package songlistener.core

/**
 * Which of a song's takes to keep once it has more than [Config.maxTakesPerSong]. `newest` keeps
 * the latest. `best` keeps unclipped takes over clipped ones, then the takes that agree most with
 * the song's others (one that sounds unlike the rest was likely a wrong slide or a bad night),
 * then the newest — so a good old take is not pushed out just by being old.
 */
object TakeSelection {
    /** [takes] of one song, cut to [Config.maxTakesPerSong]; oldest first. */
    fun keep(takes: List<SongTake>, config: Config, matcher: Matcher = Matcher(config)): List<SongTake> {
        val sorted = takes.sortedBy { it.recordedAt }
        if (sorted.size <= config.maxTakesPerSong) return sorted
        if (config.keepTakes != "best") return sorted.takeLast(config.maxTakesPerSong)
        val agreement = sorted.associateWith { agreement(it, sorted, matcher, config) ?: 0.0 }
        return sorted.sortedWith(
            compareBy<SongTake> { if (heavilyClipped(it, config)) 1 else 0 }
                .thenByDescending { agreement.getValue(it) }
                .thenByDescending { it.recordedAt },
        ).take(config.maxTakesPerSong).sortedBy { it.recordedAt }
    }

    fun heavilyClipped(take: SongTake, config: Config): Boolean = (take.clipped ?: 0.0) >= config.clipHeavyFraction

    /** Mean score of [take]'s middle stretch against each of its siblings in [all]; null if none. */
    fun agreement(take: SongTake, all: List<SongTake>, matcher: Matcher, config: Config): Double? {
        val siblings = all.filter { it !== take && it.songKey == take.songKey }
        val windows = ReviewFlags.windows(take, config.steadyFrames)
        if (siblings.isEmpty() || windows.isEmpty()) return null
        val w = windows[windows.size / 2]
        val profile = sumProfile(w)
        return siblings.map { matcher.bestAlignment(w, profile, it)?.score ?: 0.0 }.average()
    }
}
