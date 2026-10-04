package songlistener.core

/**
 * Turns a full-service recording (STT's backup `.wav`) plus that day's review log into the song
 * recordings the app would have made live.
 *
 * The backup's file name gives only a nominal start time, and audio dropped along the way shifts
 * everything after it, so each song is first located by its own take: the take was learned from
 * this same audio, so it lines up almost exactly.
 */
object ServiceImport {

    /** A log event placed on the recording's nominal time line (seconds from its first sample). */
    data class Event(val at: Double, val type: String, val songKey: String? = null, val songLabel: String? = null, val section: Int? = null)

    data class Alignment(val songKey: String, val songLabel: String, val nominalLive: Double, val offsetSeconds: Double?, val score: Double?)

    /** The recording as stored chroma frames; null where unvoiced. Index i is at [frameTime] (i). */
    fun chromaTimeline(samples: ShortArray, config: Config = Config()): List<FloatArray?> {
        val extractor = ChromaExtractor(config)
        val out = ArrayList<FloatArray?>()
        var pos = 0
        while (pos < samples.size) {
            val end = minOf(pos + config.sampleRate, samples.size)
            extractor.push(samples.copyOfRange(pos, end)).forEach { out += if (it.voiced) it.chroma else null }
            pos = end
        }
        return out
    }

    /** Centre time of stored frame [i]: the middle of the raw windows averaged into it. */
    fun frameTime(i: Int, config: Config = Config()): Double {
        val rawMiddle = i * config.framesPerFeature + config.framesPerFeature / 2
        return (config.fftSize + rawMiddle * config.hopSize - config.fftSize / 2).toDouble() / config.sampleRate
    }

    /**
     * Where [take] sits in [timeline]: the offset (recording time − nominal time) at which 30 s of
     * the take from just after it went live matches best, searching ±[searchSeconds] around
     * [nominalLive]. Score is the mean cosine similarity, near 1 for the same audio.
     */
    fun align(
        timeline: List<FloatArray?>,
        take: SongTake,
        nominalLive: Double,
        config: Config = Config(),
        searchSeconds: Double = 900.0,
    ): Pair<Double, Double>? {
        val intro = take.sectionMarks.firstOrNull { it.section != SectionMark.INTRO }?.frame ?: 0
        val skip = 4
        val probe = take.frames.drop(intro + skip).take(60)
        if (probe.size < 20) return null
        val fps = config.featuresPerSecond
        val nominalFrame = ((nominalLive + skip / fps) * fps).toInt()
        val reach = (searchSeconds * fps).toInt()
        var best = -2.0
        var bestAt = -1
        for (p in maxOf(0, nominalFrame - reach)..minOf(timeline.size - probe.size, nominalFrame + reach)) {
            var sum = 0.0
            var n = 0
            for (i in probe.indices) {
                val f = timeline[p + i] ?: continue
                sum += Matcher.dot(probe[i], f)
                n++
            }
            if (n < probe.size / 2) continue
            val score = sum / n
            if (score > best) { best = score; bestAt = p }
        }
        if (bestAt < 0) return null
        return (frameTime(bestAt, config) - (nominalLive + skip / fps)) to best
    }

    /**
     * Replays [samples] with [events] (already shifted onto the recording's real time line) through
     * the same [Recognizer] and [ClipCutter] the app runs, and returns the recordings it cuts.
     * [startedAtMs] is the wall-clock time of the first sample.
     */
    fun cut(samples: ShortArray, events: List<Event>, startedAtMs: Long, config: Config = Config()): List<Clip> {
        val clips = mutableListOf<Clip>()
        var pos = 0
        val rate = config.sampleRate
        val cutter = ClipCutter(config, clock = { startedAtMs + pos * 1000L / rate }, onClip = { clips += it })
        val r = Recognizer(config, clock = { startedAtMs + pos * 1000L / rate }, onEvent = { cutter.onEvent(it) })
        val sorted = events.sortedBy { it.at }
        var next = 0
        fun apply(e: Event) {
            when (e.type) {
                "music_started" -> r.onMusicProb(1f)
                "music_stopped" -> r.onMusicProb(0f)
                "song_live", "section_changed" -> r.onLiveSong(LiveSong(e.songKey!!, e.songLabel!!, e.section))
                "song_off_screen", "churchpresenter_disconnected", "app_stopped" -> r.onLiveSong(null)
            }
        }
        while (pos < samples.size) {
            val at = pos.toDouble() / rate
            while (next < sorted.size && sorted[next].at <= at + 1e-6) apply(sorted[next++])
            val end = minOf(pos + rate, samples.size)
            val chunk = samples.copyOfRange(pos, end)
            r.onAudio(chunk)
            cutter.onAudio(chunk, r.state.isMusic)
            pos = end
        }
        r.onLiveSong(null)
        r.finishTake()
        cutter.finish("app_stopped")
        return clips
    }

    /**
     * Shifts each event by the offset of the song it belongs to: everything after the previous
     * song left the screen, up to this song leaving it, belongs to this song (its intro included).
     */
    fun shift(events: List<Event>, alignments: List<Alignment>): List<Event> {
        val known = alignments.filter { it.offsetSeconds != null }
        if (known.isEmpty()) return events
        val fallback = known.map { it.offsetSeconds!! }.sorted()[known.size / 2]
        val lives = events.filter { it.type == "song_live" }
        // The end of each song's window: when it, or the song after it, took over.
        val windows = lives.mapIndexed { i, live ->
            val off = events.firstOrNull { it.at > live.at && it.type in ENDERS }?.at ?: Double.MAX_VALUE
            Triple(live, off, alignments.firstOrNull { it.songKey == live.songKey && it.nominalLive == live.at }?.offsetSeconds ?: fallback)
        }
        return events.map { e ->
            // A song going live at the exact moment the previous one ends starts its own window.
            val owner = windows.firstOrNull { e.at < it.second || (e.at == it.second && e.type != "song_live") }
                ?: windows.lastOrNull()
            e.copy(at = e.at + (owner?.third ?: fallback))
        }
    }

    /**
     * For a record that only says when songs went live (the play log): ends each song where its
     * music ends — the first stop followed by more than [gapSeconds] without music — if that comes
     * before the next song goes live. Without this a song would stay "on screen" through the sermon
     * and its take would soak up the next song's intro.
     */
    fun endAtMusic(screen: List<Event>, music: List<Event>, gapSeconds: Double): List<Event> {
        val lives = screen.filter { it.type == "song_live" }.sortedBy { it.at }
        val sortedMusic = music.sortedBy { it.at }
        val ends = lives.mapIndexedNotNull { i, live ->
            val next = lives.getOrNull(i + 1)?.at ?: Double.MAX_VALUE
            if (screen.any { it.type in ENDERS && it.type != "song_live" && it.at > live.at && it.at < next }) return@mapIndexedNotNull null
            val stop = sortedMusic.withIndex().firstOrNull { (j, m) ->
                m.type == "music_stopped" && m.at > live.at &&
                    (sortedMusic.getOrNull(j + 1)?.let { it.type == "music_started" && it.at - m.at > gapSeconds } ?: true)
            }?.value ?: return@mapIndexedNotNull null
            if (stop.at < next) Event(stop.at, "song_off_screen") else null
        }
        return (screen + ends).sortedBy { it.at }
    }

    private val ENDERS = setOf("song_live", "song_off_screen", "churchpresenter_disconnected", "app_stopped")
}
