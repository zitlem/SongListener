package songlistener.io

import songlistener.core.Clip
import songlistener.core.Config
import songlistener.core.MusicTimeline
import songlistener.core.Replay
import songlistener.core.ServiceImport
import songlistener.core.SessionClassifier
import songlistener.core.SongTake
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

/**
 * `./gradlew importService -Pwav=<file or folder> [-Pdb=<file or folder>] [options]`
 *
 * Cuts the song recordings the app would have made live out of STT's whole-service backups. A
 * service is one STT session: `<session>.wav`, its `<session>.db`, and a record of what was on
 * screen, taken from (first that exists, or `-Psource=`):
 *  - `cp`: ChurchPresenter's `live-content-<session>.jsonl` in `-PcpLogs=<folder>`;
 *  - `songlistener`: SongListener's own log for that day;
 *  - `playlog`: ChurchPresenter's `play_log.json` (`-PplayLog=`) — go-lives only.
 * Music on/off comes from SongListener's log when it is the source (STT's own reading), otherwise
 * from the audio itself ([MusicTimeline]); `-Pmusic=audio` forces the latter.
 *
 * The backup's clock drifts from the real one: each moment is mapped through the session database,
 * then songs with a saved take are fine-tuned by finding the take in the audio. `-PseedTakes=true`
 * also learns takes from the new recordings, so the live app recognises those songs right away.
 */
fun main() {
    val opts = Options.fromSystemProperties()
    val found = discoverSessions(opts.wav, opts.db)
    require(found.sessions.isNotEmpty() || found.withoutWav.isNotEmpty()) { "No STT sessions found at ${opts.wav}" }
    println("${found.sessions.size} session(s) with a recording" + if (opts.dryRun) " (dry run: nothing saved)" else "")
    found.withoutWav.forEach { println("  ${it.nameWithoutExtension}  skipped: no .wav (STT's .wav backup was off)") }

    val songCache = opts.songCache?.let {
        print("Reading ChurchPresenter's song cache… ")
        SongCache.load(it).also { c -> println("${c.size} songs") }
    }
    val skipped = sortedMapOf<String, Int>()
    var imported = 0
    var total = 0
    for (s in found.sessions) {
        val startedAt = LocalDateTime.parse(s.id, SESSION_TIME)
        val db = s.db?.let(SessionDb::open)
        db.use {
            // As STT does: the session starts when its database was named.
            val dbStart = s.db?.let { f -> SESSION_NAME.find(f.name)?.value?.let { v -> LocalDateTime.parse(v, SESSION_TIME) } } ?: startedAt
            val verdict = if (db == null) SessionClassifier.Verdict("unknown", "no session database")
            else SessionClassifier.classify(
                startedAt = dbStart,
                minutes = db.lastTimestamp()?.let { java.time.Duration.between(dbStart, it).seconds / 60.0 }?.coerceAtLeast(0.0) ?: 0.0,
                rows = db.rowCount(),
                synthetic = db.isSynthetic(),
            )
            val wanted = when (verdict.kind) {
                "service" -> true
                "unusual" -> opts.includeUnusual || opts.includeAll
                else -> opts.includeAll && db != null
            }
            if (!wanted) {
                println("  ${s.id}  skipped: ${verdict.kind} — ${verdict.why}")
                skipped.merge(verdict.kind, 1, Int::plus)
                return@use
            }
            val drift = db?.drift(startedAt.atZone(ZoneId.systemDefault()).toEpochSecond().toDouble())?.takeIf { d -> d.isNotEmpty() }
            println("\n=== ${s.id}: ${verdict.kind} — ${verdict.why} ===")
            val saved = importSession(s, drift, opts, songCache)
            if (saved == null) skipped.merge("service without songs on record", 1, Int::plus)
            else { imported++; total += saved }
        }
    }
    println("\nDone. Services with songs: $imported. Skipped: " +
        (if (skipped.isEmpty()) "none" else skipped.entries.joinToString { "${it.value} ${it.key}" }) +
        (if (found.withoutWav.isNotEmpty()) ", ${found.withoutWav.size} without a .wav" else "") +
        ". Song recordings ${if (opts.dryRun) "that would be saved" else "saved"}: $total." +
        if (!opts.dryRun && opts.seedTakes) " Restart SongListener to pick up the seeded takes." else "")
}

private class Options(
    val wav: File,
    val db: File?,
    val cpLogs: File?,
    val playLog: File?,
    val songCache: File?,
    val source: String,
    val music: String,
    val seedTakes: Boolean,
    val includeUnusual: Boolean,
    val includeAll: Boolean,
    val dryRun: Boolean,
) {
    companion object {
        fun fromSystemProperties(): Options {
            fun p(n: String) = System.getProperty("songlistener.$n")?.takeIf { it.isNotBlank() }
            return Options(
                wav = File(p("wav") ?: error("Pass -Pwav=<file.wav or folder>")),
                db = p("db")?.let(::File),
                cpLogs = p("cpLogs")?.let(::File),
                playLog = p("playLog")?.let(::File),
                songCache = p("songCache")?.let(::File),
                source = p("source") ?: "auto",
                music = p("music") ?: "auto",
                seedTakes = p("seedTakes") == "true",
                includeUnusual = p("includeUnusual") == "true",
                includeAll = p("includeAll") == "true",
                dryRun = p("dryRun") == "true",
            )
        }
    }
}

internal data class Session(val id: String, val wav: File, val db: File?)

internal class Discovery(val sessions: List<Session>, val withoutWav: List<File>)

private val SESSION_NAME = Regex("""\d{4}-\d{2}-\d{2}_\d{6}""")
private val SESSION_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss")

/** Every `<session>.wav` under [wav] (subfolders included), each paired with its database. */
private fun discoverSessions(wav: File, db: File?): Discovery {
    fun walk(root: File, ext: String) =
        if (root.isDirectory) root.walkTopDown().filter { it.isFile && it.name.endsWith(ext) && SESSION_NAME.containsMatchIn(it.name) }.toList()
        else listOf(root).filter { it.name.endsWith(ext) }
    val wavs = walk(wav, ".wav")
    val dbs = (walk(if (wav.isDirectory) wav else wav.parentFile, ".db") + (db?.let { walk(it, ".db") } ?: emptyList()))
        .distinctBy { it.canonicalPath }
    return pairSessions(wavs, dbs, reportUnpaired = wav.isDirectory)
}

/**
 * Pairs each recording with its database: the same name, else the nearest database within 30
 * minutes (STT's own `nearest_db` rule — a recording and its database are named a few seconds
 * apart). With [reportUnpaired], databases with no recording are returned too.
 */
internal fun pairSessions(wavs: List<File>, dbs: List<File>, reportUnpaired: Boolean = true): Discovery {
    fun timeOf(f: File) = SESSION_NAME.find(f.name)?.value?.let { LocalDateTime.parse(it, SESSION_TIME) }
    val used = mutableSetOf<File>()
    val sessions = wavs.sortedBy { it.name }.mapNotNull { w ->
        val id = SESSION_NAME.find(w.name)?.value ?: return@mapNotNull null
        val at = timeOf(w)!!
        val match = dbs.firstOrNull { it.nameWithoutExtension == w.nameWithoutExtension }
            ?: dbs.mapNotNull { d -> timeOf(d)?.let { d to abs(java.time.Duration.between(at, it).seconds) } }
                .filter { it.second <= 30 * 60 }.minByOrNull { it.second }?.first
        match?.let { used += it }
        Session(id, w, match)
    }
    val withoutWav = if (reportUnpaired) dbs.filter { it !in used }.sortedBy { it.name } else emptyList()
    return Discovery(sessions, withoutWav)
}

private const val MIN_ALIGN_SCORE = 0.7
private const val SAME_SERVICE_MS = 15 * 60_000L

/** Imports one service; returns how many recordings it saved (or would save), or null with no songs on record. */
private fun importSession(s: Session, drift: List<Pair<Double, Double>>?, o: Options, songCache: SongCache?): Int? {
    val data = File(System.getProperty("user.home"), ".songlistener")
    val config = Config()
    val zone = ZoneId.systemDefault()
    val fileStart = LocalDateTime.parse(s.id, SESSION_TIME)
    val fileStartSec = fileStart.atZone(zone).toEpochSecond().toDouble()

    val samples = ClipStore.readWav(s.wav)
    val durationSec = samples.size / config.sampleRate.toDouble()
    if (drift != null) {
        val sessionMinutes = (drift.maxOf { it.first } - fileStartSec) / 60
        if (durationSec / 60 < sessionMinutes * 0.5) {
            println("The recording holds only %.0f of the session's %.0f minutes — STT's .wav is incomplete.".format(durationSec / 60, sessionMinutes))
        }
    }
    println("Recording: %.0f min. ".format(durationSec / 60) + (drift?.let {
        "Session database: recording runs %.0f–%.0f s ahead of the clock.".format(it.minOf { d -> d.second }, it.maxOf { d -> d.second })
    } ?: "No session database: using the file name's time and a wide search."))
    fun ahead(clock: Double) = drift?.let { aheadAt(it, clock) } ?: 0.0
    fun position(clock: Double) = clock - fileStartSec + ahead(clock)
    val windowFrom = fileStartSec - 60
    val windowTo = fileStartSec + durationSec + 60

    // 1. What was on screen.
    val cpFile = o.cpLogs?.let { File(it, "live-content-${s.id}.jsonl") }?.takeIf { it.exists() }
    val slFile = File(data, "logs/${fileStart.toLocalDate()}.jsonl").takeIf { it.exists() }
    val slEvents = slFile?.let { OnScreenSources.fromSongListenerLog(it, zone) }
        ?.filter { it.clockSec in windowFrom..windowTo }
        ?.takeIf { OnScreenSources.songLives(it).isNotEmpty() }
    val (source, onScreen) = when (o.source) {
        "cp" -> "cp" to (cpFile?.let { OnScreenSources.fromChurchPresenterHistory(it) } ?: error("No live-content-${s.id}.jsonl in ${o.cpLogs}"))
        "songlistener" -> "songlistener" to (slEvents ?: error("No SongListener log covers ${s.id}"))
        "playlog" -> "playlog" to OnScreenSources.fromPlayLog(o.playLog ?: error("Pass -PplayLog=<play_log.json>"), windowFrom, windowTo)
        else -> when {
            cpFile != null -> "cp" to OnScreenSources.fromChurchPresenterHistory(cpFile)
            slEvents != null -> "songlistener" to slEvents
            o.playLog != null -> "playlog" to OnScreenSources.fromPlayLog(o.playLog, windowFrom, windowTo)
            else -> null to emptyList()
        }
    }
    if (source == null || OnScreenSources.songLives(onScreen).isEmpty()) {
        println("No on-screen record of songs for this service — skipped.")
        return null
    }
    println("On screen from: " + when (source) {
        "cp" -> "ChurchPresenter history (${cpFile!!.name})"
        "songlistener" -> "SongListener log (${slFile!!.name})"
        else -> "play_log.json (go-lives only)"
    })

    // 2. ChurchPresenter's clock against SongListener's (which shares STT's mapping) when both exist.
    var screen = onScreen.filter { it.clockSec in windowFrom..windowTo && it.type != "music_started" && it.type != "music_stopped" }
    if (source != "songlistener" && slEvents != null) {
        val skew = clockSkew(OnScreenSources.songLives(screen), OnScreenSources.songLives(slEvents))
        if (skew != null) {
            println("ChurchPresenter's clock vs SongListener's: %+.1f s over the same songs".format(skew) +
                if (abs(skew) > 2) " — corrected" else " — within 2 s, left as is")
            if (abs(skew) > 2) screen = screen.map { it.copy(clockSec = it.clockSec - skew) }
        }
    }

    // 3. Music on/off.
    val useLoggedMusic = o.music != "audio" && source == "songlistener" && OnScreenSources.hasMusic(onScreen)
    val music: List<ServiceImport.Event> = if (useLoggedMusic) {
        println("Music on/off: STT's reading, from SongListener's log")
        onScreen.filter { it.type == "music_started" || it.type == "music_stopped" }
            .map { ServiceImport.Event(position(it.clockSec), it.type) }
    } else {
        print("Music on/off: from the audio (harmonic steadiness)… ")
        val changes = MusicTimeline.changes(MusicTimeline.steadiness(samples, config))
        println("${changes.count { it.second }} stretch(es) of music")
        changes.map { (sec, on) -> ServiceImport.Event(sec.toDouble(), if (on) "music_started" else "music_stopped") }
    }

    // 4. On screen, onto the recording's time line; fine-tune each song by its take.
    val screenEvents = screen.map {
        ServiceImport.Event(position(it.clockSec), it.type, it.songKey, it.songLabel, it.section)
    }.filter { it.at >= 0 }
    val songbooks = screen.filter { it.songKey != null && it.songbook != null }.associate { it.songKey!! to it.songbook!! }
    val takes = TakeStore(File(data, "takes"), config.maxTakesPerSong).loadAll()
    val timeline = if (takes.isNotEmpty()) ServiceImport.chromaTimeline(samples, config) else emptyList()
    val search = if (drift != null) 30.0 else 900.0
    println("%-34s %-9s %-9s %-9s %s".format("song", "live at", "db ahead", "fine-tune", "take match"))
    val alignments = screenEvents.filter { it.type == "song_live" }.map { live ->
        val clock = fileStartSec + live.at - ahead(fileStartSec + live.at)
        val take = takeFor(takes, live.songKey!!, clock)
        val found = take?.let { ServiceImport.align(timeline, it, live.at, config, search) }
        val accepted = found?.takeIf { it.second >= MIN_ALIGN_SCORE }
        println("%-34s %-9s %-9s %-9s %s".format(
            live.songLabel!!.take(34),
            LocalDateTime.ofInstant(Instant.ofEpochSecond(clock.toLong()), zone).toLocalTime(),
            drift?.let { "%+.0fs".format(ahead(clock)) } ?: "-",
            accepted?.let { "%+.1fs".format(it.first) } ?: "-",
            when {
                take == null -> "no take yet"
                found == null -> "take too short to place"
                accepted == null -> "weak (%.2f), not used".format(found.second)
                else -> "%.2f".format(found.second)
            },
        ))
        ServiceImport.Alignment(live.songKey, live.songLabel, live.at, accepted?.first ?: 0.0, accepted?.second)
    }
    val shiftedMusic = if (useLoggedMusic) ServiceImport.shift(music, alignments) else music
    val shiftedScreen = ServiceImport.shift(screenEvents, alignments).let {
        // Go-lives only: end each song where its music ends.
        if (source == "playlog") ServiceImport.endAtMusic(it, shiftedMusic, config.lookbackMaxGapSeconds) else it
    }

    // 5. Cut, label, save, and optionally learn takes.
    val clips = ServiceImport.cut(samples, shiftedScreen + shiftedMusic, (fileStartSec * 1000).toLong(), config)
    val store = ClipStore(File(data, "clips"))
    val existing = store.labels().map { it.second }
    val takeStore = TakeStore(File(data, "takes"), config.maxTakesPerSong)
    var saved = 0
    for (clip in clips) {
        val l = clip.labels
        val realStart = l.startedAt - (ahead(l.startedAt / 1000.0) * 1000).toLong()
        val desc = "${l.songLabel}: %.0f s, live at %.0f s, ended: ${l.endReason}".format(l.endSeconds, l.goLiveSeconds)
        if (existing.any { it.songKey == l.songKey && abs(it.startedAt - realStart) < SAME_SERVICE_MS }) {
            println("skip  $desc — already has a recording from this service")
            continue
        }
        val take = takeFor(takes, l.songKey, realStart / 1000.0 + l.goLiveSeconds)
        val unlabelled = Clip(clip.samples, l.copy(
            startedAt = realStart,
            takeRecordedAt = take?.recordedAt ?: (realStart + (l.endSeconds * 1000).toLong()),
            songbook = songbooks[l.songKey],
            origin = "import",
            importSource = when (source) {
                "cp" -> "churchpresenter_history"
                "songlistener" -> "songlistener_log"
                else -> "play_log"
            },
        ))
        val (labelled, wordsNote) = attachLyrics(unlabelled, songCache, LyricsStore(File(data, "lyrics")), save = !o.dryRun)
        if (o.dryRun) {
            println("would save  $desc" + (wordsNote?.let { "\n      lyrics: $it" } ?: ""))
            saved++
            continue
        }
        val file = store.save(labelled)
        saved++
        println("saved ${file.name}  $desc")
        wordsNote?.let { println("      lyrics: $it") }
        if (o.seedTakes) {
            println("      " + when (seedTake(labelled, takeStore, config)) {
                SeedResult.LEARNED -> "take learned"
                SeedResult.TOO_SHORT -> "no take: under 30 s of music on screen"
                SeedResult.ALREADY_LEARNED -> "take already learned live at this service"
            })
        }
    }
    return saved
}

/**
 * Stamps [clip] with the words ChurchPresenter's cache holds for its song (saving a snapshot
 * when [save]), and says what it did. The cache has today's words: when they were edited after the
 * service, the note says so.
 */
internal fun attachLyrics(clip: Clip, cache: SongCache?, store: LyricsStore, save: Boolean): Pair<Clip, String?> {
    if (cache == null) return clip to null
    val l = clip.labels
    return when (val found = cache.lookup(l.songKey, l.songbook)) {
        SongCache.Lookup.Missing -> clip to "not in the song cache"
        is SongCache.Lookup.Ambiguous -> clip to "${found.songbooks.size} editions with different words " +
            "(${found.songbooks.joinToString()}), songbook unknown — not attached"
        is SongCache.Lookup.Found -> {
            val snapshot = cache.snapshot(found.entry, found.songbookCertain, capturedAt = System.currentTimeMillis())
            val version = if (save) store.saveIfChanged(snapshot).version else snapshot.hash
            val edited = found.entry.lastModified?.takeIf { it > l.startedAt + (l.endSeconds * 1000).toLong() }
            Clip(clip.samples, l.copy(lyricsVersion = version, songbook = l.songbook ?: found.entry.songbook)) to
                "version $version from ${found.entry.songbook}" +
                (edited?.let { " — edited ${java.time.Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate()}, after this service" } ?: "")
        }
    }
}

internal enum class SeedResult { LEARNED, TOO_SHORT, ALREADY_LEARNED }

/**
 * Learns a take from an imported [clip] and adds it to [store] (newest 5 per song), unless the
 * live app already learned this song at this same service.
 */
internal fun seedTake(clip: Clip, store: TakeStore, config: Config = Config()): SeedResult {
    val learned = Replay.learn(clip, config) ?: return SeedResult.TOO_SHORT
    val l = clip.labels
    if (store.loadAll().any { it.songKey == l.songKey && abs(it.recordedAt - l.takeRecordedAt) < SAME_SERVICE_MS }) {
        return SeedResult.ALREADY_LEARNED
    }
    store.save(learned)
    return SeedResult.LEARNED
}

/** Median of (ChurchPresenter go-live − SongListener go-live) over songs both saw, or null. */
internal fun clockSkew(cp: List<ClockEvent>, sl: List<ClockEvent>): Double? {
    val diffs = cp.mapNotNull { c ->
        sl.filter { it.songKey == c.songKey }.minByOrNull { abs(it.clockSec - c.clockSec) }
            ?.takeIf { abs(it.clockSec - c.clockSec) < 300 }?.let { c.clockSec - it.clockSec }
    }.sorted()
    return diffs.takeIf { it.isNotEmpty() }?.let { it[it.size / 2] }
}

/** The take saved for [songKey] at the service around [liveClockSec]: within 15 min after it went live. */
private fun takeFor(takes: List<SongTake>, songKey: String, liveClockSec: Double): SongTake? =
    takes.filter { it.songKey == songKey && it.recordedAt / 1000.0 in liveClockSec..(liveClockSec + 15 * 60) }
        .minByOrNull { it.recordedAt }

/** How far ahead the recording runs at [clockSec]: the median over database lines within 5 min. */
private fun aheadAt(drift: List<Pair<Double, Double>>, clockSec: Double): Double {
    val near = drift.filter { abs(it.first - clockSec) <= 300 }.map { it.second }.sorted()
    if (near.isNotEmpty()) return near[near.size / 2]
    return drift.minByOrNull { abs(it.first - clockSec) }!!.second
}
