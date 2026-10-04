package songlistener.io

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ClientParsingTest {
    private fun dto(s: String) = Json.parseToJsonElement(s).jsonObject

    @Test
    fun `a live song section becomes a LiveSong`() {
        val song = ChurchPresenterClient.liveSongOf(
            dto("""{"contentType":"LYRICS","songTitle":"Amazing Grace","songNumber":12,"songSectionIndex":3}""")
        )
        assertEquals("12|Amazing Grace", song?.key)
        assertEquals("#12 Amazing Grace", song?.label)
        assertEquals(3, song?.section)
    }

    @Test
    fun `anything but a song is no song`() {
        assertNull(ChurchPresenterClient.liveSongOf(dto("""{"contentType":"BIBLE","bookName":"John"}""")))
        assertNull(ChurchPresenterClient.liveSongOf(dto("""{"contentType":"LYRICS"}""")))
    }

    @Test
    fun `pcm is little-endian int16`() {
        assertContentEquals(shortArrayOf(1, -2), SttClient.pcm16le(byteArrayOf(1, 0, -2, -1)))
    }

    @Test
    fun `music probability falls back to the energy detector`() {
        assertEquals(0.7f, SttClient.musicProbOf(JSONObject("""{"music_prob":0.7,"audio_type":"Speaking"}""")))
        assertEquals(1f, SttClient.musicProbOf(JSONObject("""{"music_prob":null,"audio_type":"Music"}""")))
        assertEquals(0f, SttClient.musicProbOf(JSONObject("""{"audio_type":"Quiet"}""")))
    }
}
