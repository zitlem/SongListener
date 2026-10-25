package songlistener.io

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import songlistener.core.Clip
import songlistener.core.ClipCutter
import songlistener.core.ClipDetector
import songlistener.core.ClipLevel
import songlistener.core.LiveSong
import songlistener.core.LyricsSnapshot
import songlistener.core.ClipLabels
import songlistener.core.ReviewCheck
import songlistener.core.SongKeys
import songlistener.core.WrongSlide
import songlistener.core.Config
import songlistener.core.Recognizer
import songlistener.core.RecognizerState
import songlistener.core.SongTake
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** One recording in the review list: its file, labels, and — once checked — its flags. */
data class ReviewItem(val name: String, val wav: File, val labels: ClipLabels, val check: ReviewCheck?)

/**
 * The incoming audio's level: [peakDb] of the last second (dBFS), and how many of the last
 * [LevelState.WINDOW] seconds clipped. [clipping] means the input gain is too hot right now.
 */
data class LevelState(val peakDb: Double = -96.0, val clippedRecently: Int = 0) {
    val clipping: Boolean get() = clippedRecently > 0
    /** Within 1 dB of full scale without clipping: about as hot as it should go. */
    val hot: Boolean get() = !clipping && peakDb > -1.0
    companion object { const val WINDOW = 10 }
}

/** What the listener has learned about one song, for the library list. */
data class LearnedSong(
    val key: String,
    val label: String,
    val takes: Int,
    val seconds: Int,
    val lastRecorded: Long,
    val recordings: Int = 0,
    /** Of [recordings], how many the app recorded live (the rest were imported from STT backups). */
    val liveRecordings: Int = 0,
)

/**
 * Wires the two clients to one [Recognizer]. Every recognizer call runs on [worker], a single
 * thread, since the recognizer is not thread-safe; the UI only reads the published state flows.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ListenerEngine(private val dataDir: File, private val config: Config = Config(), deviceId: String = "songlistener-unset") {
    private val worker = Dispatchers.Default.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + worker)
    private val store = TakeStore(File(dataDir, "takes"), config.maxTakesPerSong) { songlistener.core.TakeSelection.keep(it, config) }
    val logDir = File(dataDir, "logs")
    val reportDir = File(dataDir, "reports")
    private val log = EventLog(logDir)
    private val clipStore = ClipStore(File(dataDir, "clips"))
    private val lyricsStore = LyricsStore(File(dataDir, "lyrics"))
    /** The words captured for each song this run: (version, songbook). Read and written on [worker]. */
    private val lyricsBySong = HashMap<String, Pair<String, String?>>()
    private val cutter = ClipCutter(
        config,
        onClip = { recorded ->
            val words = lyricsBySong[recorded.labels.songKey]
            val level = ClipLevel.of(ClipDetector.analyze(recorded.samples, recorded.labels.sampleRate), config)
            val clip = Clip(
                recorded.samples,
                recorded.labels.copy(
                    origin = "live",
                    level = level,
                    lyricsVersion = words?.first,
                    songbook = recorded.labels.songbook ?: words?.second,
                ),
            )
            val file = clipStore.save(clip)
            log.note("clip_saved", "${file.name} ${"%.0f".format(clip.labels.endSeconds)}s ${clip.labels.endReason}" +
                if (level.severity > 0) " clipped ${level.clippedSeconds}s" else "")
            refreshClipStats()
        },
        onDropped = { song, reason -> log.note("clip_dropped", "${song.label}: $reason") },
    )
    private val recognizer = Recognizer(
        config, store.loadAll(),
        onTakeSaved = { store.save(it) },
        onEvent = { log.record(it); cutter.onEvent(it) },
    )

    /** Tuning's settings, recognition test and clipping check, shared by the app and the web page. */
    val tuning = TuningJobs(this, config, File(dataDir, "config.json"), log)

    private val _dismissedWrongSlide = MutableStateFlow<Long?>(null)
    /** When the wrong-slide warning now dismissed was raised: hidden in the app and on the web page alike. */
    val dismissedWrongSlide: StateFlow<Long?> = _dismissedWrongSlide

    val churchPresenter = ChurchPresenterClient(CoroutineScope(SupervisorJob() + Dispatchers.IO), deviceId = deviceId)
    val stt = SttClient()

    private val _state = MutableStateFlow(recognizer.state)
    val state: StateFlow<RecognizerState> = _state
    /** Per song key: how a "Show it" went — `waiting`, `shown`, `declined`, or `failed: <why>`. */
    private val _showStatus = MutableStateFlow<Map<String, String>>(emptyMap())
    val showStatus: StateFlow<Map<String, String>> = _showStatus
    /** Per song key: the songbook(s) a "Show it" can use — one when known, each edition when not. */
    private val _editions = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    val editions: StateFlow<Map<String, List<String>>> = _editions

    private val _level = MutableStateFlow(LevelState())
    /** The incoming audio's level and whether it is clipping. */
    val level: StateFlow<LevelState> = _level
    /** Per second of the last [LevelState.WINDOW]: did it clip. Touched only on [worker]. */
    private val recentClips = ArrayDeque<Boolean>()

    private val _clipStats = MutableStateFlow(clipStore.stats())
    /** Song recordings on disk: count, size, and count per song. */
    val clipStats: StateFlow<ClipStore.Stats> = _clipStats
    private val _library = MutableStateFlow(library())
    val library: StateFlow<List<LearnedSong>> = _library

    init {
        log.note("app_started")
        scope.launch { churchPresenter.connected.drop(1).collect { log.note(if (it) "churchpresenter_connected" else "churchpresenter_disconnected") } }
        scope.launch { stt.connected.drop(1).collect { log.note(if (it) "stt_connected" else "stt_disconnected") } }
        scope.launch {
            var previousKey: String? = null
            churchPresenter.liveSong.collect {
                recognizer.onLiveSong(it)
                if (it != null && it.key != previousKey) captureLyrics(it)
                previousKey = it?.key
                publish()
            }
        }
        scope.launch { backfillTakeClipping() }
        scope.launch { stt.musicProb.collect { recognizer.onMusicProb(it); publish() } }
        scope.launch {
            stt.audio.collect {
                measureLevel(it)
                recognizer.onAudio(it)
                // After the recognizer: events it raised for this chunk are stamped at its start.
                cutter.onAudio(it, recognizer.state.isMusic)
                publish()
            }
        }
    }

    private fun measureLevel(chunk: ShortArray) {
        val stats = ClipDetector.analyze(chunk, config.sampleRate, ClipDetector.LIVE_LEVEL)
        recentClips.addLast(stats.clipRuns > 0)
        while (recentClips.size > LevelState.WINDOW) recentClips.removeFirst()
        val was = _level.value.clipping
        _level.value = LevelState(stats.peakDb, recentClips.count { it })
        if (_level.value.clipping != was) log.note(if (was) "clipping_stopped" else "clipping_started")
    }

    /**
     * Takes learned before takes measured their own clipping get it from their recording, so the
     * `best` take selection can prefer clean ones. Each is done once; later runs find nothing to do.
     */
    private suspend fun backfillTakeClipping() {
        val updated = withContext(Dispatchers.IO) {
            val missing = store.loadAll().filter { it.clipped == null }
            if (missing.isEmpty()) return@withContext 0
            val recordings = clipStore.labels().associateBy { (_, l) -> l.songKey to l.takeRecordedAt }
            missing.count { take ->
                val (wav, l) = recordings[take.songKey to take.recordedAt] ?: return@count false
                val level = levelOf(wav, l) ?: return@count false
                store.write(take.withClipped(level.clippedFraction))
                true
            }
        }
        if (updated > 0) {
            log.note("takes_measured", "$updated take(s) given their recording's clipping")
            recognizer.setTakes(store.loadAll())
        }
    }

    fun connect(churchPresenterUrl: String, apiKey: String, sttUrl: String, from: String = "app") {
        log.note("connect", "churchpresenter=$churchPresenterUrl stt=$sttUrl" + if (from != "app") " (from $from)" else "")
        churchPresenter.start(churchPresenterUrl, apiKey)
        stt.start(sttUrl)
    }

    fun forget(songKey: String, from: String = "app") {
        scope.launch {
            log.note("song_forgotten", "$songKey (from $from)")
            recognizer.forget(songKey)
            // Its recordings go too: a song is usually forgotten because it was mislabelled.
            withContext(Dispatchers.IO) { store.delete(songKey); clipStore.delete(songKey) }
            refreshClipStats()
            publish()
        }
    }

    /**
     * Starts over: every take and recording is moved to `deleted/all-<time>/` (so it can be moved
     * back) and nothing is recognised until songs are learned again.
     */
    fun forgetAll(from: String = "app") {
        scope.launch {
            val trash = File(dataDir, "deleted/all-" + java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss")))
            val songs = recognizer.storedTakes().map { it.songKey }.distinct().size
            withContext(Dispatchers.IO) {
                synchronized(labelLock) {
                    store.deleteAll(File(trash, "takes"))
                    clipStore.deleteAll(File(trash, "clips"))
                }
            }
            log.note("songs_forgotten_all", "$songs song(s), moved to ${trash.name} (from $from)")
            recognizer.setTakes(emptyList())
            reviewCache.clear()
            refreshClipStats()
            publish()
            refreshReview()
        }
    }

    private val stopped = AtomicBoolean(false)

    /** Saves the take in progress and disconnects. Safe to call twice (window close, then exit hook). */
    fun shutdown() {
        if (!stopped.compareAndSet(false, true)) return
        // Save and stop listening before disconnecting: a disconnect clears the live song, which
        // would otherwise be logged as the song leaving the screen.
        runBlocking(worker) {
            recognizer.finishTake()
            cutter.finish("app_stopped")
        }
        scope.cancel()
        tuning.shutdown()
        churchPresenter.stop()
        stt.stop()
        log.note("app_stopped")
    }

    /**
     * Works out which songbook a "Show it" for [songKey] should use: the one this song was last
     * recorded or captured from, else the catalog's only one, else every edition (the operator picks).
     */
    fun prepareShow(songKey: String) {
        if (_editions.value.containsKey(songKey)) return
        val number = songKey.substringBefore('|').toIntOrNull() ?: 0
        val title = songKey.substringAfter('|')
        scope.launch(Dispatchers.IO) {
            val known = clipStore.labels().map { it.second }.filter { it.songKey == songKey && it.songbook != null }
                .maxByOrNull { it.startedAt }?.songbook
            val books = if (known != null) listOf(known)
            else runCatching { churchPresenter.songbooksOf(number, title) }.getOrDefault(emptyList()).ifEmpty { listOf("") }
            _editions.value = _editions.value + (songKey to books)
        }
    }

    /** The operator dismissed a wrong-slide warning: recorded so the report can show what came of it. */
    fun dismissed(w: WrongSlide, from: String = "app") {
        _dismissedWrongSlide.value = w.at
        log.note("wrong_slide_dismissed", "${w.shown.key} heard=${w.heard.songKey} (from $from)")
    }

    /** Asks ChurchPresenter to put [songKey] on screen, from [songbook] ("" = let it choose). */
    fun show(songKey: String, songbook: String, from: String = "app") {
        val number = songKey.substringBefore('|').toIntOrNull() ?: 0
        val title = songKey.substringAfter('|')
        _showStatus.value = _showStatus.value + (songKey to "waiting")
        log.note("show_requested", "$songKey" + (if (songbook.isNotBlank()) " songbook=$songbook" else "") + " (from $from)")
        scope.launch(Dispatchers.IO) {
            val result = churchPresenter.showSong(number, title, songbook.ifBlank { null })
            val status = when (result) {
                ShowResult.Approved -> "shown"
                ShowResult.Declined -> "declined"
                is ShowResult.Failed -> "failed: ${result.reason}"
            }
            _showStatus.value = _showStatus.value + (songKey to status)
            log.note("show_result", "$songKey $status")
        }
    }

    /** Fetches the song's words from ChurchPresenter and keeps a copy if they changed. Never blocks recording. */
    private fun captureLyrics(song: LiveSong) {
        val number = song.key.substringBefore('|').toIntOrNull() ?: 0
        val title = song.key.substringAfter('|')
        scope.launch(Dispatchers.IO) {
            val fetched = runCatching { churchPresenter.fetchLyrics(number, title) }.getOrNull()
            if (fetched == null || fetched.sections.isEmpty()) {
                log.note("lyrics_unavailable", song.key)
                return@launch
            }
            val saved = lyricsStore.saveIfChanged(
                LyricsSnapshot(
                    songKey = song.key, songLabel = song.label, songbook = fetched.songbook,
                    songbookCertain = fetched.songbookCertain, number = number, title = title,
                    capturedAt = System.currentTimeMillis(), source = "churchpresenter", sections = fetched.sections,
                )
            )
            withContext(worker) { lyricsBySong[song.key] = saved.version to fetched.songbook }
            log.note(
                "lyrics_saved",
                "${song.key} version=${saved.version} ${if (saved.changed) "new" else "unchanged"}" +
                    (fetched.songbook?.let { " songbook=$it" } ?: "") + if (fetched.songbookCertain) "" else " (songbook uncertain)",
            )
        }
    }

    /** Held while a recording's labels are read and rewritten, so two writers never lose each other's change. */
    private val labelLock = Any()

    /**
     * A recording's loudness and clipping: from its labels, or measured once and saved into them,
     * so it is never measured again (not even after a restart).
     */
    private fun levelOf(wav: File, labels: ClipLabels): ClipLevel? {
        labels.level?.let { return it }
        val level = runCatching { ClipLevel.of(ClipDetector.analyze(ClipStore.readWav(wav), labels.sampleRate), config) }.getOrNull()
            ?: return null
        synchronized(labelLock) {
            val fresh = clipStore.labelsOf(wav) ?: return level
            if (fresh.level == null) runCatching { clipStore.update(wav, fresh.copy(level = level)) }
        }
        return level
    }

    /** Every recording's clipping, measuring (and saving) any not measured yet. Newest first. */
    suspend fun clipScan(): List<ClipScan.Row> = withContext(Dispatchers.IO) {
        clipStore.labels().reversed().mapNotNull { (wav, l) -> levelOf(wav, l)?.let { ClipScan.Row(wav.nameWithoutExtension, l, it) } }
    }

    /** Every recording with its audio, for a recognition test; [cleanOnly] leaves out clipped ones. */
    suspend fun recordingsForTest(cleanOnly: Boolean): List<Pair<String, Clip>> = withContext(Dispatchers.IO) {
        clipStore.labels().filter { (wav, l) -> !cleanOnly || (levelOf(wav, l)?.severity ?: 0) == 0 }
            .mapNotNull { (wav, l) -> runCatching { wav.nameWithoutExtension to Clip(ClipStore.readWav(wav), l) }.getOrNull() }
    }

    private val _reviews = MutableStateFlow<List<ReviewItem>>(emptyList())
    /** Every recording, newest first, with its flags once checked. */
    val reviews: StateFlow<List<ReviewItem>> = _reviews
    private val _reviewChecking = MutableStateFlow(false)
    val reviewChecking: StateFlow<Boolean> = _reviewChecking
    private val reviewCache = java.util.concurrent.ConcurrentHashMap<String, ReviewCheck>()
    private var reviewJob: kotlinx.coroutines.Job? = null

    /**
     * Rebuilds the review list and checks every recording in the background (about a second each).
     * A check is kept until anything learned changes.
     */
    fun refreshReview() {
        reviewJob?.cancel()
        reviewJob = scope.launch(Dispatchers.Default) {
            _reviewChecking.value = true
            try {
                val takes = store.loadAll()
                val signature = takes.joinToString { "${it.songKey}@${it.recordedAt}" }.hashCode()
                val items = clipStore.labels().reversed().map { (wav, l) -> ReviewItem(wav.nameWithoutExtension, wav, l, reviewCache["$signature/${wav.name}"]) }
                _reviews.value = items
                val matcher = songlistener.core.Matcher(config)
                for (item in items) {
                    if (item.check != null) continue
                    val l = item.labels
                    val take = takes.firstOrNull { it.songKey == l.songKey && it.recordedAt == l.takeRecordedAt }
                    val uncertain = runCatching { lyricsStore.latest(l.songKey, l.songbook)?.songbookCertain == false }.getOrDefault(false)
                    val level = levelOf(item.wav, l)
                    val check = songlistener.core.ReviewFlags.check(l, take, takes, matcher, config, uncertain, level)
                    reviewCache["$signature/${item.wav.name}"] = check
                    _reviews.value = _reviews.value.map { if (it.name == item.name) it.copy(check = check) else it }
                }
            } finally {
                _reviewChecking.value = false
            }
        }
    }

    /** Songs a recording can be corrected to: everything learned, plus ChurchPresenter's library. */
    suspend fun knownSongs(): List<Pair<String, String>> {
        val learned = store.loadAll().map { it.songKey }
        val catalog = runCatching { churchPresenter.catalogKeys() }.getOrDefault(emptyList())
        return (learned + catalog).distinct().map { it to (SongKeys.labelOf(it) ?: it) }.sortedBy { it.second.lowercase() }
    }

    /** Someone listened: the song is right. */
    fun markReviewed(name: String, from: String = "app") = correct(name, "recording_reviewed", from) { wav, l ->
        clipStore.update(wav, l.copy(reviewed = true, reviewedAt = System.currentTimeMillis()))
    }

    /**
     * The recording is of [songKey], not what it was labelled: relabel it, drop the take learned
     * under the wrong song and learn it as the right one.
     */
    fun relabel(name: String, songKey: String, from: String = "app") = correct(name, "recording_relabelled to $songKey", from) { wav, l ->
        if (songKey == l.songKey) return@correct
        store.deleteTake(l.songKey, l.takeRecordedAt)
        val fixed = l.copy(
            songKey = songKey, songLabel = SongKeys.labelOf(songKey) ?: songKey, songbook = null, lyricsVersion = null,
            relabelledFrom = l.relabelledFrom ?: l.songKey, reviewed = true, reviewedAt = System.currentTimeMillis(),
        )
        val moved = clipStore.update(wav, fixed)
        songlistener.core.Replay.learn(Clip(ClipStore.readWav(moved), fixed), config)?.let { store.save(it) }
    }

    /** Deletes a recording and the take learned from it. */
    fun deleteRecording(name: String, from: String = "app") = correct(name, "recording_deleted", from) { wav, l ->
        store.deleteTake(l.songKey, l.takeRecordedAt)
        clipStore.deleteOne(wav)
    }

    /**
     * The recording was too hot: drop the take learned from it, keep the recording, and let the
     * song be learned afresh the next time it is played.
     */
    fun relearn(name: String, from: String = "app") = correct(name, "recording_relearn", from) { wav, l ->
        store.deleteTake(l.songKey, l.takeRecordedAt)
        clipStore.update(wav, l.copy(relearnRequestedAt = System.currentTimeMillis()))
    }

    /** [relearn] for every recording the review found clipped. */
    fun relearnAllClipped(from: String = "app") {
        scope.launch {
            val names = _reviews.value.filter { item ->
                item.labels.relearnRequestedAt == null && item.check?.flags?.any { it.code == "clipped" } == true
            }.map { it.name }
            if (names.isEmpty()) return@launch
            withContext(Dispatchers.IO) {
                for (name in names) {
                    val wav = clipStore.find(name) ?: continue
                    synchronized(labelLock) {
                        val l = clipStore.labelsOf(wav) ?: return@synchronized
                        store.deleteTake(l.songKey, l.takeRecordedAt)
                        clipStore.update(wav, l.copy(relearnRequestedAt = System.currentTimeMillis()))
                    }
                }
            }
            log.note("recordings_relearn", "${names.size} clipped recording(s) (from $from)")
            recognizer.setTakes(store.loadAll())
            refreshClipStats()
            publish()
            refreshReview()
        }
    }

    private fun correct(name: String, event: String, from: String, action: (File, ClipLabels) -> Unit) {
        scope.launch {
            val wav = clipStore.find(name) ?: return@launch
            val labels = withContext(Dispatchers.IO) {
                synchronized(labelLock) { clipStore.labelsOf(wav)?.also { action(wav, it) } }
            } ?: return@launch
            val (type, detail) = event.split(' ', limit = 2).let { it[0] to it.getOrNull(1) }
            log.note(type, "$name was ${labels.songKey}" + (detail?.let { " $it" } ?: "") + " (from $from)")
            recognizer.setTakes(store.loadAll())
            refreshClipStats()
            publish()
            refreshReview()
        }
    }

    private fun refreshClipStats() {
        _clipStats.value = clipStore.stats()
        _library.value = library()
    }

    private fun publish() {
        _state.value = recognizer.state
        _library.value = library()
    }

    private fun library(): List<LearnedSong> =
        recognizer.storedTakes().groupBy { it.songKey }.map { (key, takes) -> summarize(key, takes) }
            .sortedBy { it.label.lowercase() }

    private fun summarize(key: String, takes: List<SongTake>) = LearnedSong(
        key = key,
        label = takes.last().songLabel,
        takes = takes.size,
        seconds = (takes.sumOf { it.frames.size } / config.featuresPerSecond).toInt(),
        lastRecorded = takes.maxOf { it.recordedAt },
        recordings = _clipStats.value.perSong[key] ?: 0,
        liveRecordings = _clipStats.value.livePerSong[key] ?: 0,
    )
}
