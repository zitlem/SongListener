package songlistener.core

/** The song ChurchPresenter has on screen. */
data class LiveSong(val key: String, val label: String, val section: Int?)

data class Suggestion(val songKey: String, val songLabel: String, val score: Double, val at: Long, val section: Int? = null)

/** The music doesn't match the song on screen: it sounds like [heard] instead of [shown]. */
data class WrongSlide(
    val shown: LiveSong,
    val shownScore: Double,
    val heard: SongScore,
    val at: Long,
)

/**
 * While a known song is live, what the matcher would have said. [top1] counts runs where the live
 * song ranked first; [confident] counts runs clearing the suggest thresholds, [confidentHits] those
 * that also named the live song.
 */
data class Accuracy(
    val runs: Int = 0,
    val top1: Int = 0,
    val confident: Int = 0,
    val confidentHits: Int = 0,
    val lastPrediction: SongScore? = null,
    val lastLiveKey: String? = null,
)

data class RecognizerState(
    val liveSong: LiveSong? = null,
    val musicProb: Float = 0f,
    val isMusic: Boolean = false,
    /** Stored frames of music in the take being recorded now, intro included. */
    val recordingFrames: Int = 0,
    /** Of [recordingFrames], how many were the look-back music heard before the song went live. */
    val recordingIntroFrames: Int = 0,
    /** Stored frames of music held in the look-back, waiting for a song to go live. */
    val lookbackFrames: Int = 0,
    /** Stored frames currently in the query window. */
    val queryFrames: Int = 0,
    /** The latest ranking, from the steady window when it is full, else the quick one. */
    val lastMatch: MatchResult? = null,
    /** The quick listener's early guess: "sounds like…". */
    val hint: Suggestion? = null,
    /** The steady listener's confirmed suggestion. */
    val lastSuggestion: Suggestion? = null,
    val accuracy: Accuracy = Accuracy(),
    /** Set while the music clearly doesn't match the song on screen. */
    val wrongSlide: WrongSlide? = null,
    /** The song on screen has no takes yet, so whether it is the right one can't be checked. */
    val cantCheckLive: Boolean = false,
    /** Seconds of music heard since the song on screen went up (the slide is judged after the steady window). */
    val secondsSinceLive: Double = 0.0,
    /** How long matching takes on the library as it is now. */
    val matchTiming: MatchTiming? = null,
)

/** Of the last [runs] searches (at most [MatchTiming.KEEP]), the median and 95th-percentile time, over [takes] takes. */
data class MatchTiming(val p50Ms: Double, val p95Ms: Double, val runs: Int, val takes: Int) {
    companion object {
        const val KEEP = 200

        fun of(ms: List<Double>, takes: Int): MatchTiming? {
            if (ms.isEmpty()) return null
            val sorted = ms.sorted()
            fun at(q: Double) = sorted[minOf(sorted.size - 1, (q * sorted.size).toInt())]
            return MatchTiming(at(0.5), at(0.95), ms.size, takes)
        }
    }
}

/** One line saying whether the music matches the song on screen. [kind] is `ok`, `doubt`, `wrong`, `wait` or `unknown`. */
data class SlideCheck(val kind: String, val text: String)

/**
 * The slide check for [s]: null while nothing is on screen. Shared by the window and the web page
 * so both say the same thing.
 */
fun slideCheck(s: RecognizerState, config: Config): SlideCheck? {
    val live = s.liveSong ?: return null
    val needed = config.steadyFrames / config.featuresPerSecond
    s.wrongSlide?.let { return SlideCheck("wrong", "✗ sounds like ${it.heard.songLabel} (%.2f)".format(it.heard.score)) }
    if (s.cantCheckLive) return SlideCheck("unknown", "can't check — ${live.label} hasn't been learned yet")
    if (s.secondsSinceLive < needed || s.accuracy.lastLiveKey != live.key) {
        return SlideCheck("wait", "checking… (%.0f of %.0f s of music heard)".format(minOf(s.secondsSinceLive, needed), needed))
    }
    val p = s.accuracy.lastPrediction ?: return SlideCheck("wait", "checking…")
    return if (p.songKey == live.key) SlideCheck("ok", "✓ music matches (%.2f)".format(p.score))
    else SlideCheck("doubt", "? best match right now is ${p.songLabel} (%.2f)".format(p.score))
}

/**
 * Learns takes while a song is live and suggests songs while none is. Driven entirely by the three
 * `on*` events, and by [clock] for timestamps only — the match cadence counts audio, not time — so
 * it runs the same from sockets as from a test. Not thread-safe: call it from one thread.
 */
class Recognizer(
    private val config: Config = Config(),
    initialTakes: List<SongTake> = emptyList(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val onTakeSaved: (SongTake) -> Unit = {},
    private val onEvent: (RecognizerEvent) -> Unit = {},
) {
    private val extractor = ChromaExtractor(config, feature = config.feature)
    private val matcher = Matcher(config)
    /** Takes as stored (whole), per song. */
    private val wholeTakes = initialTakes.groupBy { it.songKey }.mapValues { it.value.toMutableList() }.toMutableMap()
    /** What is matched on: each song's takes with their intro as [Config.introMode] says. */
    private val takes = wholeTakes.mapValues { matchable(it.value) }.toMutableMap()

    /** One song's whole takes as matched on: intro as [Config.introMode] says, then [Config.confirmedParts]. */
    private fun matchable(whole: List<SongTake>): MutableList<SongTake> =
        ConfirmedParts.apply(IntroFilter.applyAll(whole, config), config).toMutableList()

    private var recordingKey: LiveSong? = null
    private val recording = ArrayList<FloatArray>()
    private val recordingMarks = ArrayList<SectionMark>()
    private var recordingIntro = 0
    /** Seconds of audio heard while the take records, and how many of them clipped. */
    private var recordingChunks = 0
    private var recordingClippedChunks = 0

    private var recordingOutro = 0
    /** The song last recorded and the end of its music: what it would sound like playing on after it left. */
    private var lastKey: String? = null
    private var lastLabel: String? = null
    private var lastTail: List<FloatArray> = emptyList()
    /** A song just left the screen and no break has come since: what the look-back holds may be its outro. */
    private var outroOpen = false
    /** How many of the look-back's oldest frames were held while [outroOpen]. */
    private var outroCandidate = 0

    /** Music heard while nothing is live, oldest first; the intro of whatever goes live next. */
    private val lookback = ArrayDeque<FloatArray>()
    /** Clock time the music now held in the look-back started: how late a slide is, when it goes up. */
    private var musicSince: Long? = null
    private var samplesWithoutMusic = 0
    /** Music was playing and has not yet been gone longer than the dip tolerance. */
    private var inMusic = false

    /** Continuous music, newest last, as long as the steady window. The quick window is its tail. */
    private val query = ArrayDeque<FloatArray>()
    private var samplesSinceMatch = 0
    private var matchRuns = 0
    private var streakKey: String? = null
    private var streak = 0
    private var hintKey: String? = null
    private var hintStreak = 0
    /**
     * Stored frames of music heard since the song on screen went up. The steady window judges the
     * slide only once it holds nothing from before, or it would judge the previous music.
     */
    private var framesSinceLive = 0
    private var wrongKey: String? = null
    private var wrongStreak = 0
    /**
     * The last song taken off screen, not suggested again while the band plays on after it, until
     * [suppressedUntil] (clock time).
     */
    private var suppressedKey: String? = null
    private var suppressedUntil = 0L

    var state = RecognizerState()
        private set

    /** A song's takes as stored (whole, one per performance). */
    fun takesFor(songKey: String): List<SongTake> = wholeTakes[songKey].orEmpty()

    /** Replaces everything learned, after a correction. */
    fun setTakes(all: List<SongTake>) {
        takes.clear(); wholeTakes.clear()
        all.groupBy { it.songKey }.forEach { (k, v) ->
            wholeTakes[k] = v.toMutableList()
            takes[k] = matchable(v)
        }
    }
    /** Everything matched on (intro filtered; possibly split into confirmed pieces). */
    fun allTakes(): List<SongTake> = takes.values.flatten()
    /** Every take as stored, one per performance. */
    fun storedTakes(): List<SongTake> = wholeTakes.values.flatten()

    fun forget(songKey: String) {
        takes.remove(songKey)
        wholeTakes.remove(songKey)
    }

    fun onLiveSong(song: LiveSong?) {
        val previous = state.liveSong
        if (previous?.key != song?.key) {
            finishTake()
            if (previous != null) {
                suppressedKey = previous.key
                suppressedUntil = clock() + (config.suppressSeconds * 1000).toLong()
            }
            onEvent(RecognizerEvent.LiveChanged(song))
            if (song != null && previous == null && lookback.isNotEmpty()) reportIfLate(song)
            framesSinceLive = 0
            musicSince = null
            if (song != null) {
                recordingKey = song
                if (previous == null && lookback.isNotEmpty()) {
                    val outro = outroOf(song)
                    recordingMarks += SectionMark(0, if (outro > 0) SectionMark.OUTRO else SectionMark.INTRO)
                    if (outro in 1 until lookback.size) recordingMarks += SectionMark(outro, SectionMark.INTRO)
                    recording += lookback
                    recordingIntro = lookback.size
                    recordingOutro = outro
                    if (outro > 0) onEvent(RecognizerEvent.OutroMarked(lastLabel ?: lastKey.orEmpty(), song, seconds(outro)))
                }
                if (song.section != null) recordingMarks += SectionMark(recording.size, song.section)
                onEvent(RecognizerEvent.TakeStarted(song, seconds(recordingIntro - recordingOutro)))
            }
            // A song leaving the screen opens the outro window; the look-back starts empty either way.
            outroOpen = previous != null && song == null
            outroCandidate = 0
            lookback.clear()
            resetStreak()
            wrongKey = null; wrongStreak = 0
            if (state.wrongSlide != null) onEvent(RecognizerEvent.WrongSlideCleared(state.wrongSlide!!.shown, null))
            hintKey = null; hintStreak = 0
            state = state.copy(wrongSlide = null, cantCheckLive = false, hint = null, lastSuggestion = null, lastMatch = null)
        } else if (song != null && song.section != null && song.section != previous?.section) {
            recordingMarks += SectionMark(recording.size, song.section)
            onEvent(RecognizerEvent.SectionChanged(song))
        }
        publishCounts(song)
    }

    fun onMusicProb(prob: Float) {
        val isMusic = prob >= config.musicThreshold
        if (isMusic != state.isMusic) {
            onEvent(if (isMusic) RecognizerEvent.MusicStarted(prob) else RecognizerEvent.MusicStopped(prob))
        }
        state = state.copy(musicProb = prob, isMusic = isMusic)
    }

    fun onAudio(samples: ShortArray) {
        val frames = extractor.push(samples)
        if (recordingKey != null && state.isMusic) {
            recordingChunks++
            if (ClipDetector.analyze(samples, config.sampleRate, ClipDetector.LIVE_LEVEL).clipRuns > 0) recordingClippedChunks++
        }
        if (state.isMusic) {
            samplesWithoutMusic = 0
            inMusic = true
        } else {
            samplesWithoutMusic += samples.size
        }
        // A dip shorter than the tolerance, right after music, is still the song: keep its audio
        // and the match window. With no music before it, it is just not music.
        val bridged = !state.isMusic && inMusic &&
            samplesWithoutMusic <= config.musicDipToleranceSeconds * config.sampleRate
        if (!state.isMusic && !bridged) {
            inMusic = false
            // A real break breaks the query: the window must be one continuous passage.
            if (query.isNotEmpty()) onEvent(RecognizerEvent.MusicBreak(query.size))
            // Music after a break is not the previous song playing on.
            outroOpen = false
            query.clear()
            samplesSinceMatch = 0
            resetStreak()
            // A short break (a word over the intro) is bridged; a long one means what came before
            // was not this song's intro.
            if (samplesWithoutMusic > config.lookbackMaxGapSeconds * config.sampleRate && lookback.isNotEmpty()) {
                onEvent(RecognizerEvent.LookbackCleared(seconds(lookback.size)))
                lookback.clear()
                outroCandidate = 0
                musicSince = null
            }
            // Long silence: what the panel last heard is stale.
            if (samplesWithoutMusic > config.lookbackMaxGapSeconds * config.sampleRate &&
                (state.hint != null || state.lastSuggestion != null || state.lastMatch != null)
            ) {
                hintKey = null; hintStreak = 0
                state = state.copy(hint = null, lastSuggestion = null, lastMatch = null)
            }
            publishCounts(state.liveSong)
            return
        }
        for (frame in frames) {
            if (!frame.voiced) continue
            query.addLast(frame.chroma)
            while (query.size > maxOf(config.queryFrames, config.steadyFrames)) query.removeFirst()
            if (recordingKey != null) {
                if (recording.size < config.maxTakeFrames) recording += frame.chroma
                framesSinceLive++
            } else {
                if (lookback.isEmpty()) {
                    musicSince = clock()
                    onEvent(RecognizerEvent.LookbackStarted)
                }
                lookback.addLast(frame.chroma)
                if (outroOpen) outroCandidate++
                while (lookback.size > config.lookbackFrames) {
                    lookback.removeFirst()
                    if (outroCandidate > 0) outroCandidate--
                }
            }
        }
        samplesSinceMatch += samples.size
        publishCounts(state.liveSong)
        if (query.size >= config.queryFrames && samplesSinceMatch >= config.matchIntervalSeconds * config.sampleRate) {
            samplesSinceMatch = 0
            runMatch()
        }
    }

    /**
     * How many of the look-back's frames are the last song playing on into [song] (see [OutroSplit]).
     * A song coming back to the screen is just itself, so its music is kept as its intro.
     */
    private fun outroOf(song: LiveSong): Int {
        val prev = lastKey ?: return 0
        if (prev == song.key || outroCandidate == 0) return 0
        val tail = if (lastTail.isEmpty()) emptyList() else listOf(SongTake(prev, lastLabel ?: prev, 0, lastTail, emptyList()))
        val previous = tail + wholeTakes[prev].orEmpty().map { it.withoutOutro() }
        val next = wholeTakes[song.key].orEmpty().map { it.withoutOutro() }
        return OutroSplit.boundary(lookback.toList(), outroCandidate, previous, next, config)
    }

    /** Saves the take in progress, if long enough. Call on shutdown too. */
    fun finishTake() {
        val song = recordingKey
        // Only music played while the song was on screen counts toward the minimum, so a long
        // intro cannot turn a brief, mistaken go-live into a take.
        val introSeconds = seconds(recordingIntro - recordingOutro)
        val songSeconds = seconds(recording.size - recordingIntro)
        if (song != null && recording.size - recordingIntro >= config.minTakeFrames) {
            val clipped = if (recordingChunks > 0) recordingClippedChunks.toDouble() / recordingChunks else null
            val take = SongTake(song.key, song.label, clock(), recording.toList(), recordingMarks.toList(), clipped)
            val whole = wholeTakes.getOrPut(song.key) { mutableListOf() }
            whole += take
            if (whole.size > config.maxTakesPerSong) {
                val kept = TakeSelection.keep(whole, config, matcher)
                whole.clear(); whole += kept
            }
            takes[song.key] = matchable(whole)
            onTakeSaved(take)
            onEvent(RecognizerEvent.TakeSaved(song, introSeconds, songSeconds, whole.size, take.recordedAt))
        } else if (song != null) {
            onEvent(RecognizerEvent.TakeDiscarded(song, introSeconds, songSeconds))
        }
        if (song != null && recording.size > recordingIntro) {
            lastKey = song.key
            lastLabel = song.label
            lastTail = recording.subList(maxOf(recordingIntro, recording.size - OUTRO_REFERENCE_FRAMES), recording.size).toList()
        }
        recordingKey = null
        recordingOutro = 0
        recording.clear()
        recordingMarks.clear()
        recordingIntro = 0
        recordingChunks = 0
        recordingClippedChunks = 0
        publishCounts(state.liveSong)
    }

    private fun publishCounts(live: LiveSong?) {
        state = state.copy(
            liveSong = live,
            queryFrames = query.size,
            recordingFrames = recording.size,
            recordingIntroFrames = recordingIntro,
            lookbackFrames = lookback.size,
            secondsSinceLive = if (live == null) 0.0 else framesSinceLive / config.featuresPerSecond,
        )
    }

    /**
     * One run every 2 s of music: the quick window always, the steady window every other run once
     * it is full. Nothing is ever sent anywhere from here — suggestions and warnings are state for
     * a person to act on.
     */
    private fun runMatch() {
        if (takes.isEmpty()) return // nothing learned yet: no candidates, nothing to log
        matchRuns++
        val all = allTakes()
        val (quick, quickMs) = timed(all) { matcher.match(query.toList().takeLast(config.queryFrames), all, keep = state.liveSong?.key) }
        state = state.copy(lastMatch = quick)
        quick.best?.let { best ->
            val confident = best.score >= config.suggestScore && quick.margin >= config.suggestMargin
            report("quick", quick, best, confident, decideQuick(best, confident), quickMs)
        }
        // With no longer steady window configured, the steady checks run on the quick window.
        val single = config.steadyFrames <= config.queryFrames
        if (!single && (query.size < config.steadyFrames || matchRuns % 2 != 0)) return
        val (steady, steadyMs) = if (single) quick to quickMs else timed(all) { matcher.match(query.toList().takeLast(config.steadyFrames), all, keep = state.liveSong?.key) }
        state = state.copy(lastMatch = steady)
        val best = steady.best ?: return
        val confident = best.score >= config.steadyScore && steady.margin >= config.steadyMargin
        report("steady", steady, best, confident, decideSteady(steady, best, confident), steadyMs)
    }

    /** The last search times, newest last: how matching keeps up as the library grows. */
    private val matchMs = ArrayDeque<Double>()

    private fun timed(all: List<SongTake>, search: () -> MatchResult): Pair<MatchResult, Double> {
        val start = System.nanoTime()
        val result = search()
        val ms = (System.nanoTime() - start) / 1e6
        matchMs.addLast(ms)
        while (matchMs.size > MatchTiming.KEEP) matchMs.removeFirst()
        state = state.copy(matchTiming = MatchTiming.of(matchMs.toList(), all.size))
        return result to ms
    }

    private fun report(window: String, result: MatchResult, best: SongScore, confident: Boolean, decision: String, ms: Double? = null) {
        val live = state.liveSong
        onEvent(RecognizerEvent.Match(live?.key, live?.section, result.ranked.take(LOGGED_CANDIDATES), result.margin, confident, decision, window, ms))
    }

    /** Quick window: an early hint while nothing is live; nothing to decide while a song is. */
    private fun decideQuick(best: SongScore, confident: Boolean): String {
        if (state.liveSong != null) return "live"
        if (!confident) { hintKey = null; hintStreak = 0; return "below_threshold" }
        if (best.songKey == suppressedKey && clock() < suppressedUntil) return "suppressed"
        if (best.songKey == hintKey) hintStreak++ else { hintKey = best.songKey; hintStreak = 1 }
        if (hintStreak < config.suggestStreak) return "hint_streak $hintStreak/${config.suggestStreak}"
        if (state.hint?.songKey == best.songKey) return "already_hinted"
        state = state.copy(hint = Suggestion(best.songKey, best.songLabel, best.score, clock(), best.section))
        return "hint"
    }

    /** Steady window: suggests while nothing is live; checks the slide while a song is. */
    private fun decideSteady(result: MatchResult, best: SongScore, confident: Boolean): String {
        val live = state.liveSong
        if (live != null) {
            if (!takes.containsKey(live.key)) {
                state = state.copy(cantCheckLive = true)
                return "live_unlearned"
            }
            // Until the window holds only music heard since the slide went up, it is still judging
            // whatever played before — no accuracy, no wrong-slide check.
            if (framesSinceLive < config.steadyFrames) return "settling"
            recordAccuracy(live.key, best, confident)
            checkSlide(live, result)
            return if (best.songKey == live.key) "accuracy_hit" else "accuracy_miss"
        }
        if (!confident) { resetStreak(); return "below_threshold" }
        if (best.songKey == suppressedKey && clock() < suppressedUntil) { resetStreak(); return "suppressed" }
        if (best.songKey == streakKey) streak++ else { streakKey = best.songKey; streak = 1 }
        if (streak < config.suggestStreak) return "streak $streak/${config.suggestStreak}"
        if (state.lastSuggestion?.songKey == best.songKey) return "already_suggested"
        state = state.copy(lastSuggestion = Suggestion(best.songKey, best.songLabel, best.score, clock(), best.section))
        return "suggested"
    }

    /**
     * Warns when another learned song clearly beats the one on screen, run after run; clears the
     * warning once the song on screen is back within reach.
     */
    private fun checkSlide(live: LiveSong, result: MatchResult) {
        val shownScore = result.ranked.firstOrNull { it.songKey == live.key }?.score ?: return
        val rival = result.ranked.firstOrNull { it.songKey != live.key }
        val clear = rival == null || rival.score - shownScore < config.wrongSlideMargin ||
            rival.score < config.wrongSlideMinScore
        if (clear) {
            wrongKey = null; wrongStreak = 0
            state.wrongSlide?.let {
                onEvent(RecognizerEvent.WrongSlideCleared(live, shownScore))
                state = state.copy(wrongSlide = null)
            }
            return
        }
        if (rival!!.songKey == wrongKey) wrongStreak++ else { wrongKey = rival.songKey; wrongStreak = 1 }
        if (wrongStreak >= config.wrongSlideStreak && state.wrongSlide?.heard?.songKey != rival.songKey) {
            state = state.copy(wrongSlide = WrongSlide(live, shownScore, rival, clock()))
            onEvent(RecognizerEvent.WrongSlideWarning(live, shownScore, rival))
        }
    }

    /** Records a slide that went up well after its music started, and whether it was seen coming. */
    private fun reportIfLate(song: LiveSong) {
        val since = musicSince ?: return
        val late = (clock() - since) / 1000.0
        if (late < config.lateSlideSeconds) return
        val now = clock()
        fun lead(s: Suggestion?) = s?.takeIf { it.songKey == song.key && it.at >= since }?.let { (now - it.at) / 1000.0 }
        // Was it this song that played before the slide, or other music (a prelude, another song)?
        // Only knowable when the song has been learned.
        val own = takes[song.key]
        val heardBefore = own?.let {
            val window = lookback.toList().takeLast(maxOf(config.queryFrames, config.steadyFrames))
            (matcher.match(window, it).best?.score ?: 0.0) >= config.steadyScore
        }
        onEvent(RecognizerEvent.LateSlide(song, late, lead(state.hint), lead(state.lastSuggestion), heardBefore))
    }

    private fun seconds(frames: Int): Double = frames / config.featuresPerSecond

    private fun recordAccuracy(liveKey: String, best: SongScore, confident: Boolean) {
        val a = state.accuracy
        val hit = best.songKey == liveKey
        state = state.copy(
            accuracy = a.copy(
                runs = a.runs + 1,
                top1 = a.top1 + if (hit) 1 else 0,
                confident = a.confident + if (confident) 1 else 0,
                confidentHits = a.confidentHits + if (confident && hit) 1 else 0,
                lastPrediction = best,
                lastLiveKey = liveKey,
            )
        )
    }

    private fun resetStreak() {
        streakKey = null
        streak = 0
    }

    private companion object {
        const val LOGGED_CANDIDATES = 3
        /** How much of the end of the last song is kept to recognise it playing on (60 = 30 s). */
        const val OUTRO_REFERENCE_FRAMES = 60
    }
}
