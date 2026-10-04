package songlistener.io

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ShowSongTest {
    private class Seen(val path: String, val device: String?, val key: String?, val body: String)
    private val seen = mutableListOf<Seen>()

    private fun client(status: HttpStatusCode?) = ChurchPresenterClient(
        CoroutineScope(Dispatchers.Unconfined),
        HttpClient(MockEngine { req ->
            if (req.url.encodedPath == "/api/project") {
                seen += Seen(req.url.encodedPath, req.headers["X-Device-Id"], req.headers["X-Api-Key"], (req.body as TextContent).text)
            }
            if (status == null) throw java.io.IOException("connection refused")
            respond(if (status.value == 200) """{"ok":true}""" else """{"ok":false,"reason":"denied"}""", status, headersOf("Content-Type", "application/json"))
        }),
        deviceId = "songlistener-fixed",
    ).also { it.start("http://cp.local:8765", "secret"); it.stop() }

    @Test
    fun `an approved request puts the song on screen with this device's id`() = runBlocking {
        assertEquals(ShowResult.Approved, client(HttpStatusCode.OK).showSong(737, "Славьте Бога", "Песнь Возрождения 2001"))
        val s = seen.single()
        assertEquals("songlistener-fixed", s.device)
        assertEquals("secret", s.key)
        val item = Json.parseToJsonElement(s.body).jsonObject["item"]!!.jsonObject
        assertEquals("737", item["songNumber"]!!.jsonPrimitive.content)
        assertEquals("Славьте Бога", item["title"]!!.jsonPrimitive.content)
        assertEquals("Песнь Возрождения 2001", item["songbook"]!!.jsonPrimitive.content)
        assertEquals("song", item["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a declined or failed request says so`() = runBlocking {
        assertEquals(ShowResult.Declined, client(HttpStatusCode.Forbidden).showSong(1, "A", null))
        assertIs<ShowResult.Failed>(client(HttpStatusCode.InternalServerError).showSong(1, "A", null))
        val failed = assertIs<ShowResult.Failed>(client(null).showSong(1, "A", null))
        assertTrue(failed.reason.contains("refused"), failed.reason)
    }

    @Test
    fun `the device id is made once and kept`() {
        val file = File(Files.createTempDirectory("settings").toFile(), "config.json")
        AppSettings.save(file, AppSettings(churchPresenterUrl = "http://cp:8765"))
        val first = AppSettings.deviceId(file)
        assertTrue(first.startsWith("songlistener-"))
        assertEquals(first, AppSettings.deviceId(file))
        assertEquals("http://cp:8765", AppSettings.load(file).churchPresenterUrl)
    }
}
