package songlistener.core

import kotlinx.serialization.Serializable

@Serializable
data class TimedSection(val at: Double, val section: Int)

@Serializable
data class MusicMark(val at: Double, val on: Boolean)

/**
 * What a song recording is: times are seconds from the start of the recording. [music] starts
 * with the state at 0 and lists every change after it, as STT reported it.
 */
@Serializable
data class ClipLabels(
    val version: Int = 1,
    val songKey: String,
    val songLabel: String,
    val takeRecordedAt: Long,
    /** Wall-clock time (ms) of the first sample. */
    val startedAt: Long,
    val sampleRate: Int,
    val goLiveSeconds: Double,
    /** When the song left the screen, if it did before the recording ended. */
    val offScreenSeconds: Double? = null,
    val endSeconds: Double,
    /** `off_screen`, `music_ended`, `max_length`, `next_song` or `app_stopped`. */
    val endReason: String,
    val sections: List<TimedSection>,
    val music: List<MusicMark>,
    /** The song's songbook, when the on-screen record knew it (ChurchPresenter history, play log). */
    val songbook: String? = null,
    /** The version of the words (see `LyricsStore`) this performance was sung from, if captured. */
    val lyricsVersion: String? = null,
    /** `live` (recorded by the app during a service) or `import` (cut from an STT backup afterwards). */
    val origin: String? = null,
    /** For imports, what said what was on screen: `songlistener_log`, `play_log` or `churchpresenter_history`. */
    val importSource: String? = null,
    /** Someone listened and confirmed the song is right ([reviewedAt] is when). */
    val reviewed: Boolean = false,
    val reviewedAt: Long? = null,
    /** The song this was first labelled as, when someone corrected it. */
    val relabelledFrom: String? = null,
)

class Clip(val samples: ShortArray, val labels: ClipLabels)

/**
 * Cuts one recording per song out of the raw audio, from just before its intro to just after it
 * leaves the screen (or the music stops), and hands it over only if the song's take was kept.
 *
 * Fed the same audio and events as the [Recognizer] — call [onAudio] after the recognizer has seen
 * the same chunk, so an event raised while it did is stamped at the start of that chunk. Counts
 * samples, not time; [clock] only stamps the wall-clock start. Not thread-safe.
 */
class ClipCutter(
    private val config: Config = Config(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val onClip: (Clip) -> Unit = {},
    private val onDropped: (song: LiveSong, reason: String) -> Unit = { _, _ -> },
) {
    private val rate = config.sampleRate
    private val preRoll = (config.clipPreRollSeconds * rate).toLong()
    private val tail = (config.clipTailSeconds * rate).toLong()
    private val quietLimit = (config.lookbackMaxGapSeconds * rate).toLong()
    private val maxSamples = (config.clipMaxSeconds * rate).toLong()

    /** Audio heard while idle: enough for the longest look-back plus the pre-roll. */
    private val ring = ShortArray(((config.lookbackFrames / config.featuresPerSecond + config.clipPreRollSeconds + 2) * rate).toInt())
    private var ringPos = 0
    private var ringCount = 0

    /** Total samples seen so far: the time base for everything here. */
    private var now = 0L
    private var music = false
    /** Music changes still inside the ring window, oldest first. */
    private val musicHistory = ArrayDeque<Pair<Long, Boolean>>()
    /** Where the music now held in the recognizer's look-back began. */
    private var holdStart: Long? = null

    private var open: OpenClip? = null

    private class OpenClip(val song: LiveSong, val start: Long, val goLive: Long, val startedAt: Long) {
        val audio = ShortBuilder()
        val sections = mutableListOf<TimedSection>()
        val music = mutableListOf<MusicMark>()
        var lastMusicEnd = goLive
        /**
         * Music has been heard since the song went live. Until it has, silence or talking is not
         * "the music ended": the slide often goes up during an announcement, before the singing.
         */
        var heardMusic = false
        var offScreen: Long? = null
        /** Once set, nothing more is appended. */
        var end: Long? = null
        var endReason: String? = null
        var takeRecordedAt: Long? = null
    }

    fun onEvent(event: RecognizerEvent) {
        when (event) {
            is RecognizerEvent.LookbackStarted -> holdStart = now
            is RecognizerEvent.LookbackCleared -> holdStart = null
            is RecognizerEvent.TakeStarted -> start(event.song)
            is RecognizerEvent.SectionChanged -> open?.takeIf { it.end == null }?.let { c ->
                event.song.section?.let { c.sections += TimedSection(seconds(now - c.start), it) }
            }
            is RecognizerEvent.LiveChanged -> open?.let { c ->
                if (c.offScreen == null) c.offScreen = now
                if (c.end == null) {
                    // Already a long silence: the tail would be trimmed off anyway, so end now.
                    if (c.heardMusic && now - c.lastMusicEnd > quietLimit) {
                        end(c, now, "off_screen")
                        emitIfDone()
                    }
                    // Otherwise keep recording the tail; [onAudio] closes it.
                    else c.endReason = "off_screen"
                }
            }
            is RecognizerEvent.TakeSaved -> open?.takeIf { it.song.key == event.song.key }?.let {
                it.takeRecordedAt = event.takeRecordedAt
                emitIfDone()
            }
            is RecognizerEvent.TakeDiscarded -> open?.takeIf { it.song.key == event.song.key }?.let {
                open = null
                onDropped(it.song, "take_discarded")
            }
            else -> Unit
        }
    }

    fun onAudio(samples: ShortArray, isMusic: Boolean) {
        if (isMusic != music) {
            music = isMusic
            musicHistory.addLast(now to isMusic)
            open?.takeIf { it.end == null }?.let { it.music += MusicMark(seconds(now - it.start), isMusic) }
        }
        pushRing(samples)
        val c = open
        if (c != null && c.end == null) {
            c.audio.append(samples)
            val chunkEnd = now + samples.size
            if (isMusic) {
                c.lastMusicEnd = chunkEnd
                if (now >= c.goLive) c.heardMusic = true
            }
            val offScreen = c.offScreen
            // A long pause with the slide still up (a prayer, a reading between verses) does not end
            // the recording: the song may carry on. Trailing silence is trimmed when it does end.
            when {
                offScreen != null && chunkEnd >= offScreen + tail -> end(c, offScreen + tail, "off_screen")
                chunkEnd - c.start >= maxSamples -> end(c, c.start + maxSamples, "max_length")
            }
        }
        now += samples.size
        while (musicHistory.size > 1 && musicHistory[1].first < now - ring.size) musicHistory.removeFirst()
        emitIfDone()
    }

    /** Ends the recording in progress now (shutdown); it is kept if its take was. */
    fun finish(reason: String) {
        val c = open ?: return
        if (c.end == null) end(c, now, reason)
        emitIfDone()
        // A take that never reported back is not kept.
        open?.let { open = null; onDropped(it.song, "no_take") }
    }

    private fun start(song: LiveSong) {
        open?.let { previous ->
            // Songs back to back: the previous one's tail ends here.
            if (previous.end == null) end(previous, now, "next_song")
            emitIfDone()
            open?.let { open = null; onDropped(it.song, "no_take") }
        }
        val oldest = now - ringCount
        val from = maxOf((holdStart ?: now) - preRoll, oldest, 0L)
        val c = OpenClip(song, from, now, clock() - (now - from) * 1000 / rate)
        c.audio.append(ringSince(from))
        c.music += MusicMark(0.0, musicAt(from))
        musicHistory.filter { it.first > from }.forEach { c.music += MusicMark(seconds(it.first - from), it.second) }
        song.section?.let { c.sections += TimedSection(seconds(now - from), it) }
        holdStart = null
        open = c
    }

    /** Ends [c] at [at], or 3 s after its last music when it ended in a long silence (`music_ended`). */
    private fun end(c: OpenClip, at: Long, reason: String) {
        if (c.heardMusic && at - c.lastMusicEnd > quietLimit) close(c, c.lastMusicEnd + tail, "music_ended")
        else close(c, at, reason)
    }

    private fun close(c: OpenClip, end: Long, reason: String) {
        val e = end.coerceIn(c.start, c.start + c.audio.size)
        c.audio.truncate((e - c.start).toInt())
        c.end = e
        c.endReason = reason
    }

    private fun emitIfDone() {
        val c = open ?: return
        val end = c.end ?: return
        val recordedAt = c.takeRecordedAt ?: return
        open = null
        onClip(
            Clip(
                c.audio.toArray(),
                ClipLabels(
                    songKey = c.song.key,
                    songLabel = c.song.label,
                    takeRecordedAt = recordedAt,
                    startedAt = c.startedAt,
                    sampleRate = rate,
                    goLiveSeconds = seconds(c.goLive - c.start),
                    offScreenSeconds = c.offScreen?.takeIf { it <= end }?.let { seconds(it - c.start) },
                    endSeconds = seconds(end - c.start),
                    endReason = c.endReason ?: "off_screen",
                    sections = c.sections.toList(),
                    music = c.music.filter { it.at <= seconds(end - c.start) },
                ),
            )
        )
    }

    private fun musicAt(sample: Long): Boolean =
        musicHistory.lastOrNull { it.first <= sample }?.second ?: musicHistory.firstOrNull()?.second?.not() ?: music

    private fun pushRing(samples: ShortArray) {
        for (s in samples) {
            ring[ringPos] = s
            ringPos = (ringPos + 1) % ring.size
        }
        ringCount = minOf(ring.size, ringCount + samples.size)
    }

    /** The ring's audio from absolute sample [from] up to [now]. */
    private fun ringSince(from: Long): ShortArray {
        val n = (now - from).toInt().coerceIn(0, ringCount)
        val out = ShortArray(n)
        val first = Math.floorMod(ringPos - n, ring.size)
        for (i in 0 until n) out[i] = ring[(first + i) % ring.size]
        return out
    }

    private fun seconds(samples: Long): Double = samples.toDouble() / rate
}

/** A growable ShortArray. */
internal class ShortBuilder {
    private var data = ShortArray(1 shl 16)
    var size = 0
        private set

    fun append(s: ShortArray) {
        if (size + s.size > data.size) data = data.copyOf(maxOf(data.size * 2, size + s.size))
        System.arraycopy(s, 0, data, size, s.size)
        size += s.size
    }

    fun truncate(n: Int) {
        size = n.coerceIn(0, size)
    }

    fun toArray(): ShortArray = data.copyOf(size)
}
