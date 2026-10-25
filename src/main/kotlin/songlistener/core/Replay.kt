package songlistener.core

/**
 * One held-out recording's results. [leadSeconds] is how long before the song went live the right
 * song was first suggested — negative if only after. Null fields mean "never happened".
 */
data class ReplayRow(
    val name: String,
    val songKey: String,
    val songLabel: String,
    /** False when no other recording of this song exists, so there was nothing to learn from. */
    val testable: Boolean,
    val firstSuggestion: String? = null,
    val firstSuggestionRight: Boolean? = null,
    val leadSeconds: Double? = null,
    val suggestScore: Double? = null,
    val suggestMargin: Double? = null,
    val suggestSection: Int? = null,
    val wrongSuggestions: Int = 0,
    val bestRight: Double? = null,
    val bestWrong: Double? = null,
    val bestWrongSong: String? = null,
    /** Share of match runs, while the song was live, that ranked it first. */
    val top1Rate: Double? = null,
    /** Of those hits, the share that also named the section on screen. */
    val sectionRate: Double? = null,
    /** `live` or `import`. */
    val origin: String? = null,
    /** How long the music played before the slide went up. */
    val lateSeconds: Double = 0.0,
    /** The quick listener's first "sounds like…" hint. */
    val firstHint: String? = null,
    val firstHintRight: Boolean? = null,
    val hintLeadSeconds: Double? = null,
    val wrongHints: Int = 0,
    /** With another learned song on screen instead: did the wrong-slide warning name this one? */
    val warnedOnWrongSlide: Boolean? = null,
    /** With the right song on screen: did a wrong-slide warning fire anyway (a false alarm)? */
    val falseAlarm: Boolean = false,
)

data class ReplaySummary(
    val clips: Int,
    val testable: Int,
    val rightBeforeLive: Int,
    val rightAtAll: Int,
    val medianLeadSeconds: Double?,
    val wrongSuggestions: Int,
    val meanTop1: Double?,
    val meanSection: Double?,
    val hintsRightBeforeLive: Int = 0,
    val wrongHints: Int = 0,
    /** Recordings where the slide went up 20 s or more after the music. */
    val lateTestable: Int = 0,
    val lateCaught: Int = 0,
    val wrongSlideTests: Int = 0,
    val wrongSlideCaught: Int = 0,
    val falseAlarms: Int = 0,
)

/**
 * Offline evaluation over saved song recordings, leave-one-out: each recording is tested against
 * takes learned from all the *other* recordings, so a song is only ever recognised from a
 * different performance of it.
 */
object Replay {
    private const val CHUNK = 16_000
    /** A slide that went up this long after its music started counts as late. */
    const val LATE_SECONDS = 20.0

    /**
     * [onProgress] gets (recordings done, total) — learning counts as the first half — and may throw
     * to cancel. [learned] reuses takes from an earlier run with the same learning settings.
     */
    fun run(
        clips: List<Pair<String, Clip>>,
        config: Config = Config(),
        learned: List<SongTake?>? = null,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): Pair<List<ReplayRow>, ReplaySummary> {
        val total = clips.size * 2
        val takes = learned ?: clips.mapIndexed { i, (_, clip) -> learn(clip, config).also { onProgress(i + 1, total) } }
        val rows = clips.mapIndexed { i, (name, clip) ->
            onProgress(clips.size + i, total)
            val key = clip.labels.songKey
            // What the app would hold: each song's takes cut to the limit, as it would cut them.
            val others = takes.filterIndexed { j, t -> j != i && t != null }.filterNotNull()
                .groupBy { it.songKey }.values.flatMap { TakeSelection.keep(it, config) }
            if (others.none { it.songKey == key }) {
                ReplayRow(name, key, clip.labels.songLabel, testable = false)
            } else {
                test(name, clip, others, config)
            }
        }
        return rows to summarize(rows)
    }

    /** The take a recording would have produced when it was live, or null if too short. */
    fun learn(clip: Clip, config: Config = Config()): SongTake? {
        var take: SongTake? = null
        val r = Recognizer(config, clock = { clip.labels.takeRecordedAt }, onTakeSaved = { take = it })
        drive(clip, r, live = true)
        r.finishTake()
        return take
    }

    private fun test(name: String, clip: Clip, library: List<SongTake>, config: Config): ReplayRow {
        val key = clip.labels.songKey

        // 1. Nothing on screen: would it have been suggested, and when?
        val matches = mutableListOf<Pair<Double, RecognizerEvent.Match>>()
        var t = 0.0
        val idle = Recognizer(config, library, clock = { (t * 1000).toLong() }, onEvent = { e ->
            if (e is RecognizerEvent.Match) matches += t to e
        })
        drive(clip, idle, live = false, onTime = { t = it })
        val suggested = matches.filter { it.second.decision == "suggested" }
        val first = suggested.firstOrNull()
        val tops = matches.flatMap { it.second.top }
        val right = tops.filter { it.songKey == key }.maxOfOrNull { it.score }
        val wrong = tops.filter { it.songKey != key }.maxByOrNull { it.score }

        val hints = matches.filter { it.second.decision == "hint" }
        val firstHint = hints.firstOrNull()

        // 2. With the song on screen as it really was: top-1 and section accuracy, and no false alarm.
        val live = mutableListOf<RecognizerEvent.Match>()
        var falseAlarm = false
        val onScreen = Recognizer(config, library, onEvent = { e ->
            if (e is RecognizerEvent.Match) live += e
            if (e is RecognizerEvent.WrongSlideWarning) falseAlarm = true
        })
        drive(clip, onScreen, live = true)

        // 3. With another learned song on screen instead: does the warning name this one?
        val stand = library.map { it.songKey }.distinct().filter { it != key }.sorted().firstOrNull()
        val warned = stand?.let { other ->
            var caught = false
            val wrongScreen = Recognizer(config, library, onEvent = { e ->
                if (e is RecognizerEvent.WrongSlideWarning && e.heard.songKey == key) caught = true
            })
            val otherLabel = library.first { it.songKey == other }.songLabel
            drive(clip, wrongScreen, live = true, liveAs = LiveSong(other, otherLabel, null))
            caught
        }
        val scored = live.filter { it.decision == "accuracy_hit" || it.decision == "accuracy_miss" }
        val hits = scored.filter { it.decision == "accuracy_hit" }
        val withSection = hits.filter { it.liveSection != null && it.top.first().section != null }

        return ReplayRow(
            name = name,
            songKey = key,
            songLabel = clip.labels.songLabel,
            testable = true,
            firstSuggestion = first?.second?.top?.first()?.songLabel,
            firstSuggestionRight = first?.second?.top?.first()?.songKey?.let { it == key },
            leadSeconds = first?.let { (fst, m) -> if (m.top.first().songKey == key) clip.labels.goLiveSeconds - fst else null },
            suggestScore = first?.second?.top?.first()?.score,
            suggestMargin = first?.second?.margin,
            suggestSection = first?.second?.top?.first()?.section,
            wrongSuggestions = suggested.count { it.second.top.first().songKey != key },
            bestRight = right,
            bestWrong = wrong?.score,
            bestWrongSong = wrong?.songLabel,
            top1Rate = if (scored.isEmpty()) null else hits.size.toDouble() / scored.size,
            sectionRate = if (withSection.isEmpty()) null else withSection.count { it.top.first().section == it.liveSection }.toDouble() / withSection.size,
            origin = clip.labels.origin,
            lateSeconds = lateness(clip),
            firstHint = firstHint?.second?.top?.first()?.songLabel,
            firstHintRight = firstHint?.second?.top?.first()?.songKey?.let { it == key },
            hintLeadSeconds = firstHint?.let { (t0, m) -> if (m.top.first().songKey == key) clip.labels.goLiveSeconds - t0 else null },
            wrongHints = hints.count { it.second.top.first().songKey != key },
            warnedOnWrongSlide = warned,
            falseAlarm = falseAlarm,
        )
    }

    /** How long the music played before the slide went up. */
    fun lateness(clip: Clip): Double {
        val firstMusic = clip.labels.music.firstOrNull { it.on }?.at ?: return 0.0
        return (clip.labels.goLiveSeconds - firstMusic).coerceAtLeast(0.0)
    }

    /**
     * Plays [clip] into [r] in the 1-second chunks STT sends, replaying STT's music on/off and,
     * when [live], the song going on and off screen and its section changes, each at its time.
     */
    internal fun drive(clip: Clip, r: Recognizer, live: Boolean, onTime: (Double) -> Unit = {}, liveAs: LiveSong? = null) {
        val l = clip.labels
        val rate = l.sampleRate.toDouble()
        val song = liveAs ?: LiveSong(l.songKey, l.songLabel, null)
        val events = buildList<Pair<Double, () -> Unit>> {
            l.music.forEach { m -> add(m.at to { r.onMusicProb(if (m.on) 1f else 0f) }) }
            if (live) {
                val firstSection = l.sections.firstOrNull { it.at <= l.goLiveSeconds + 0.01 }?.section
                add(l.goLiveSeconds to { r.onLiveSong(song.copy(section = firstSection)) })
                l.sections.filter { it.at > l.goLiveSeconds + 0.01 }.forEach { s ->
                    add(s.at to { r.onLiveSong(song.copy(section = s.section)) })
                }
                l.offScreenSeconds?.let { add(it to { r.onLiveSong(null) }) }
            }
        }.sortedBy { it.first }
        var next = 0
        var pos = 0
        while (pos < clip.samples.size) {
            val at = pos / rate
            onTime(at)
            while (next < events.size && events[next].first <= at + 1e-6) events[next++].second()
            val end = minOf(pos + CHUNK, clip.samples.size)
            r.onAudio(clip.samples.copyOfRange(pos, end))
            pos = end
        }
        onTime(pos / rate)
        while (next < events.size) events[next++].second()
    }

    private fun summarize(rows: List<ReplayRow>): ReplaySummary {
        val tested = rows.filter { it.testable }
        val leads = tested.mapNotNull { it.leadSeconds }.sorted()
        val late = tested.filter { it.lateSeconds >= LATE_SECONDS }
        val wrongTests = tested.filter { it.warnedOnWrongSlide != null }
        return ReplaySummary(
            clips = rows.size,
            testable = tested.size,
            rightBeforeLive = tested.count { (it.leadSeconds ?: -1.0) > 0 },
            rightAtAll = tested.count { it.firstSuggestionRight == true },
            medianLeadSeconds = leads.takeIf { it.isNotEmpty() }?.let { it[it.size / 2] },
            wrongSuggestions = tested.sumOf { it.wrongSuggestions },
            meanTop1 = tested.mapNotNull { it.top1Rate }.takeIf { it.isNotEmpty() }?.average(),
            meanSection = tested.mapNotNull { it.sectionRate }.takeIf { it.isNotEmpty() }?.average(),
            hintsRightBeforeLive = tested.count { (it.hintLeadSeconds ?: -1.0) > 0 },
            wrongHints = tested.sumOf { it.wrongHints },
            lateTestable = late.size,
            lateCaught = late.count { (it.hintLeadSeconds ?: -1.0) > 0 || (it.leadSeconds ?: -1.0) > 0 },
            wrongSlideTests = wrongTests.size,
            wrongSlideCaught = wrongTests.count { it.warnedOnWrongSlide == true },
            falseAlarms = tested.count { it.falseAlarm },
        )
    }
}
