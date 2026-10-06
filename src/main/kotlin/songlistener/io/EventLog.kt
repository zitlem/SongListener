package songlistener.io

import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import songlistener.core.LiveSong
import songlistener.core.RecognizerEvent
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/**
 * The review log: one JSON object per line, one file per day (`logs/2026-10-04.jsonl`), each line
 * stamped with local time and a `type`. Song names, timings and scores only — never audio or
 * transcript text.
 */
class EventLog(private val dir: File, private val now: () -> LocalDateTime = LocalDateTime::now) {

    fun record(event: RecognizerEvent) = write(typeOf(event)) { fields(event) }

    /** An event from outside the recognizer: connections, app start and stop. */
    fun note(type: String, detail: String? = null) = write(type) { if (detail != null) put("detail", detail) }

    @Synchronized
    private fun write(type: String, body: JsonObjectBuilder.() -> Unit) {
        val time = now()
        val line = buildJsonObject {
            put("time", time.format(TIME))
            put("type", type)
            body()
        }
        runCatching {
            dir.mkdirs()
            File(dir, "${time.format(DAY)}.jsonl").appendText("$line\n")
        }
    }

    private fun JsonObjectBuilder.fields(e: RecognizerEvent) {
        when (e) {
            is RecognizerEvent.LiveChanged -> e.song?.let { song(it) }
            is RecognizerEvent.SectionChanged -> song(e.song)
            is RecognizerEvent.MusicStarted -> put("musicProb", round(e.prob.toDouble()))
            is RecognizerEvent.MusicStopped -> put("musicProb", round(e.prob.toDouble()))
            is RecognizerEvent.LookbackStarted -> Unit
            is RecognizerEvent.LookbackCleared -> put("heldSeconds", round(e.heldSeconds))
            is RecognizerEvent.MusicBreak -> put("queryFramesCleared", e.queryCleared)
            is RecognizerEvent.TakeStarted -> { song(e.song); put("introSeconds", round(e.introSeconds)) }
            is RecognizerEvent.TakeSaved -> {
                song(e.song)
                put("introSeconds", round(e.introSeconds))
                put("songSeconds", round(e.songSeconds))
                put("takesKept", e.takesKept)
                put("takeRecordedAt", e.takeRecordedAt)
            }
            is RecognizerEvent.TakeDiscarded -> {
                song(e.song)
                put("introSeconds", round(e.introSeconds))
                put("songSeconds", round(e.songSeconds))
            }
            is RecognizerEvent.WrongSlideWarning, is RecognizerEvent.WrongSlideCleared -> wrong(e)
            is RecognizerEvent.LateSlide -> {
                song(e.song)
                put("lateSeconds", round(e.lateSeconds))
                e.hintLeadSeconds?.let { put("hintLeadSeconds", round(it)) }
                e.suggestionLeadSeconds?.let { put("suggestionLeadSeconds", round(it)) }
                put("caught", e.caught)
                e.heardBefore?.let { put("heardBefore", it) }
            }
            is RecognizerEvent.Match -> {
                put("live", e.liveKey)
                put("window", e.window)
                e.liveSection?.let { put("liveSection", it) }
                put("decision", e.decision)
                put("confident", e.confident)
                put("margin", round(e.margin))
                putJsonArray("top") {
                    e.top.forEach { s ->
                        addJsonObject {
                            put("song", s.songLabel)
                            put("key", s.songKey)
                            put("score", round(s.score))
                            put("transpose", if (s.rotation > 6) s.rotation - 12 else s.rotation)
                            put("tempo", s.stretch)
                            s.section?.let { put("section", it) }
                        }
                    }
                }
            }
        }
    }

    private fun JsonObjectBuilder.wrong(e: RecognizerEvent) {
        when (e) {
            is RecognizerEvent.WrongSlideWarning -> {
                song(e.shown)
                put("shownScore", round(e.shownScore))
                put("heard", e.heard.songLabel)
                put("heardKey", e.heard.songKey)
                put("heardScore", round(e.heard.score))
            }
            is RecognizerEvent.WrongSlideCleared -> {
                song(e.shown)
                e.shownScore?.let { put("shownScore", round(it)) }
            }
            else -> Unit
        }
    }

    private fun JsonObjectBuilder.song(song: LiveSong) {
        put("song", song.label)
        put("key", song.key)
        song.section?.let { put("section", it) }
    }

    internal companion object {
        private val TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        private val DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd")

        fun typeOf(e: RecognizerEvent): String = when (e) {
            is RecognizerEvent.LiveChanged -> if (e.song == null) "song_off_screen" else "song_live"
            is RecognizerEvent.SectionChanged -> "section_changed"
            is RecognizerEvent.MusicStarted -> "music_started"
            is RecognizerEvent.MusicStopped -> "music_stopped"
            is RecognizerEvent.LookbackStarted -> "lookback_started"
            is RecognizerEvent.LookbackCleared -> "lookback_cleared"
            is RecognizerEvent.MusicBreak -> "music_break"
            is RecognizerEvent.TakeStarted -> "take_started"
            is RecognizerEvent.TakeSaved -> "take_saved"
            is RecognizerEvent.TakeDiscarded -> "take_discarded"
            is RecognizerEvent.Match -> "match"
            is RecognizerEvent.LateSlide -> "late_slide"
            is RecognizerEvent.WrongSlideWarning -> "wrong_slide_warning"
            is RecognizerEvent.WrongSlideCleared -> "wrong_slide_cleared"
        }

        fun round(v: Double): Double = (v * 1000).roundToInt() / 1000.0
    }
}
