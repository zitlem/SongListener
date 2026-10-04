package songlistener.io

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import songlistener.core.LiveSong
import songlistener.core.RecognizerEvent
import songlistener.core.SongScore
import java.io.File
import java.nio.file.Files
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals

class EventLogTest {
    private val dir = Files.createTempDirectory("logs").toFile()
    private var time = LocalDateTime.of(2026, 10, 4, 10, 15, 30)
    private val log = EventLog(dir) { time }

    private fun lines(day: String) = File(dir, "$day.jsonl").readLines().map { Json.parseToJsonElement(it).jsonObject }

    @Test
    fun `events are written one JSON object per line, one file per day`() {
        val song = LiveSong("12|Hark", "Hark (#12)", 2)
        log.record(RecognizerEvent.TakeSaved(song, 14.2, 181.04, 3, 1_790_000_000_000))
        log.record(
            RecognizerEvent.Match(
                null, null, listOf(SongScore("12|Hark", "Hark (#12)", 0.91234, 8, 10, 1.04)), 0.2, true, "suggested",
            )
        )
        time = time.plusDays(1)
        log.note("app_stopped")

        val day1 = lines("2026-10-04")
        assertEquals(2, day1.size)
        val saved = day1[0]
        assertEquals("2026-10-04 10:15:30", saved["time"]!!.jsonPrimitive.content)
        assertEquals("take_saved", saved["type"]!!.jsonPrimitive.content)
        assertEquals("Hark (#12)", saved["song"]!!.jsonPrimitive.content)
        assertEquals("2", saved["section"]!!.jsonPrimitive.content)
        assertEquals("181.04", saved["songSeconds"]!!.jsonPrimitive.content)

        val match = day1[1]
        assertEquals("suggested", match["decision"]!!.jsonPrimitive.content)
        val top = match["top"]!!.jsonArray.single().jsonObject
        assertEquals("0.912", top["score"]!!.jsonPrimitive.content)
        assertEquals("-2", top["transpose"]!!.jsonPrimitive.content)

        assertEquals("app_stopped", lines("2026-10-05").single()["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a song leaving the screen is its own event type`() {
        log.record(RecognizerEvent.LiveChanged(null))
        assertEquals("song_off_screen", lines("2026-10-04").single()["type"]!!.jsonPrimitive.content)
    }
}
