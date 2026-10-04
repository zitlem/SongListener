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
import songlistener.core.LiveSong
import songlistener.core.LyricsSnapshot
import songlistener.core.WrongSlide
import songlistener.core.Config
import songlistener.core.Recognizer
import songlistener.core.RecognizerState
import songlistener.core.SongTake
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

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
class ListenerEngine(dataDir: File, private val config: Config = Config(), deviceId: String = "songlistener-unset") {
    private val worker = Dispatchers.Default.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + worker)
    private val store = TakeStore(File(dataDir, "takes"), config.maxTakesPerSong)
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
            val clip = Clip(
                recorded.samples,
                recorded.labels.copy(
                    origin = "live",
                    lyricsVersion = words?.first,
                    songbook = recorded.labels.songbook ?: words?.second,
                ),
            )
            val file = clipStore.save(clip)
            log.note("clip_saved", "${file.name} ${"%.0f".format(clip.labels.endSeconds)}s ${clip.labels.endReason}")
            refreshClipStats()
        },
        onDropped = { song, reason -> log.note("clip_dropped", "${song.label}: $reason") },
    )
    private val recognizer = Recognizer(
        config, store.loadAll(),
        onTakeSaved = { store.save(it) },
        onEvent = { log.record(it); cutter.onEvent(it) },
    )

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
        scope.launch { stt.musicProb.collect { recognizer.onMusicProb(it); publish() } }
        scope.launch {
            stt.audio.collect {
                recognizer.onAudio(it)
                // After the recognizer: events it raised for this chunk are stamped at its start.
                cutter.onAudio(it, recognizer.state.isMusic)
                publish()
            }
        }
    }

    fun connect(churchPresenterUrl: String, apiKey: String, sttUrl: String) {
        log.note("connect", "churchpresenter=$churchPresenterUrl stt=$sttUrl")
        churchPresenter.start(churchPresenterUrl, apiKey)
        stt.start(sttUrl)
    }

    fun forget(songKey: String) {
        scope.launch {
            log.note("song_forgotten", songKey)
            recognizer.forget(songKey)
            // Its recordings go too: a song is usually forgotten because it was mislabelled.
            withContext(Dispatchers.IO) { store.delete(songKey); clipStore.delete(songKey) }
            refreshClipStats()
            publish()
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
    fun dismissed(w: WrongSlide) {
        log.note("wrong_slide_dismissed", "${w.shown.key} heard=${w.heard.songKey}")
    }

    /** Asks ChurchPresenter to put [songKey] on screen, from [songbook] ("" = let it choose). */
    fun show(songKey: String, songbook: String) {
        val number = songKey.substringBefore('|').toIntOrNull() ?: 0
        val title = songKey.substringAfter('|')
        _showStatus.value = _showStatus.value + (songKey to "waiting")
        log.note("show_requested", "$songKey" + if (songbook.isNotBlank()) " songbook=$songbook" else "")
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

    private fun refreshClipStats() {
        _clipStats.value = clipStore.stats()
        _library.value = library()
    }

    private fun publish() {
        _state.value = recognizer.state
        _library.value = library()
    }

    private fun library(): List<LearnedSong> =
        recognizer.allTakes().groupBy { it.songKey }.map { (key, takes) -> summarize(key, takes) }
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
