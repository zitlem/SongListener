package songlistener.io

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import songlistener.core.SongKeys
import java.io.File
import java.time.ZoneId

/** Something that happened on screen (or to the music), stamped on the wall clock in seconds. */
data class ClockEvent(
    val clockSec: Double,
    val type: String,
    val songKey: String? = null,
    val songLabel: String? = null,
    val section: Int? = null,
    val songbook: String? = null,
)

/**
 * The three places a past service's on-screen history can come from, each reduced to the same
 * [ClockEvent]s: `song_live`, `section_changed`, `song_off_screen`, and — from SongListener's own
 * log only — `music_started` / `music_stopped`.
 */
object OnScreenSources {
    private val ON_SCREEN = setOf("song_live", "section_changed", "song_off_screen", "churchpresenter_disconnected", "app_stopped")
    private val MUSIC = setOf("music_started", "music_stopped")

    /** SongListener's own review log for a day: on-screen events and STT's music on/off. */
    fun fromSongListenerLog(file: File, zone: ZoneId = ZoneId.systemDefault()): List<ClockEvent> =
        ServiceReport.parse(file.readLines()).filter { it.type in ON_SCREEN || it.type in MUSIC }.map { e ->
            ClockEvent(
                clockSec = e.time.atZone(zone).toEpochSecond().toDouble(),
                type = if (e.type == "churchpresenter_disconnected" || e.type == "app_stopped") "song_off_screen" else e.type,
                songKey = e.str("key"),
                songLabel = e.str("song"),
                section = e.int("section"),
            )
        }

    /**
     * ChurchPresenter's `live-content-<session>.jsonl`: one line per on-screen change. Song go-lives,
     * section changes and the song leaving the screen are derived from consecutive lines; every
     * other content type only matters as "the song is no longer on screen".
     */
    fun fromChurchPresenterHistory(file: File): List<ClockEvent> {
        val out = mutableListOf<ClockEvent>()
        var currentKey: String? = null
        var currentSection: Int? = null
        for (line in file.readLines()) {
            val o = runCatching { Json.parseToJsonElement(line).jsonObject }.getOrNull() ?: continue
            if (o["type"]?.jsonPrimitive?.contentOrNull == "session") continue
            val ts = o["ts_ms"]?.jsonPrimitive?.longOrNull ?: continue
            val clock = ts / 1000.0
            val song = if (o["contentType"]?.jsonPrimitive?.contentOrNull == "LYRICS") {
                SongKeys.liveSong(
                    number = o["songNumber"]?.jsonPrimitive?.intOrNull ?: 0,
                    title = o["songTitle"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    section = o["sectionIndex"]?.jsonPrimitive?.intOrNull,
                )
            } else null
            val songbook = o["songbook"]?.jsonPrimitive?.contentOrNull
            when {
                song == null && currentKey != null -> {
                    out += ClockEvent(clock, "song_off_screen")
                    currentKey = null
                    currentSection = null
                }
                song != null && song.key != currentKey -> {
                    out += ClockEvent(clock, "song_live", song.key, song.label, song.section, songbook)
                    currentKey = song.key
                    currentSection = song.section
                }
                song != null && song.section != currentSection -> {
                    out += ClockEvent(clock, "section_changed", song.key, song.label, song.section, songbook)
                    currentSection = song.section
                }
            }
        }
        return out
    }

    /**
     * ChurchPresenter's `play_log.json` between [fromSec] and [toSec]: go-lives only. Each song is
     * ended by the next one going live; when it really left the screen is unknown, so the recording
     * ends when its music does.
     */
    fun fromPlayLog(file: File, fromSec: Double, toSec: Double): List<ClockEvent> {
        val root = Json.parseToJsonElement(file.readText()).jsonObject
        return root["songEvents"]?.jsonArray.orEmpty().mapNotNull { el ->
            val o = el.jsonObject
            val clock = (o["timestamp"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null) / 1000.0
            if (clock < fromSec || clock > toSec) return@mapNotNull null
            val song = SongKeys.liveSong(
                number = o["songNumber"]?.jsonPrimitive?.intOrNull ?: 0,
                title = o["title"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                section = null,
            ) ?: return@mapNotNull null
            ClockEvent(clock, "song_live", song.key, song.label, null, o["songbook"]?.jsonPrimitive?.contentOrNull)
        }.sortedBy { it.clockSec }.let(::dropRepeats)
    }

    /** The play log often records one go-live twice, a second apart: keep the first. */
    internal fun dropRepeats(lives: List<ClockEvent>): List<ClockEvent> =
        lives.filterIndexed { i, e ->
            val prev = lives.getOrNull(i - 1)
            prev == null || prev.songKey != e.songKey || e.clockSec - prev.clockSec > REPEAT_SECONDS
        }

    private const val REPEAT_SECONDS = 10.0

    /** Whether [events] carry STT's music on/off (only SongListener's own log does). */
    fun hasMusic(events: List<ClockEvent>) = events.any { it.type in MUSIC }

    fun songLives(events: List<ClockEvent>) = events.filter { it.type == "song_live" }
}
