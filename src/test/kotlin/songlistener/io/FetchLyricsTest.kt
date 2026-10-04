package songlistener.io

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FetchLyricsTest {
    private val requests = mutableListOf<String>()

    private val catalog = """{"song-book":[
        {"book-name":"PV 2001","song-total":2,"songs":[{"id":0,"number":"0368","title":"Ты познал"},{"id":1,"number":"0005","title":"Один"}]},
        {"book-name":"PV 3300","song-total":1,"songs":[{"id":2,"number":"0368","title":"Ты познал"}]}
    ],"songBooks":2,"total":3}"""

    private fun client(detail: (String) -> Pair<HttpStatusCode, String>) = ChurchPresenterClient(
        CoroutineScope(Dispatchers.Unconfined),
        HttpClient(MockEngine { req ->
            requests += req.url.encodedPathAndQuery + " key=" + req.headers["X-Api-Key"]
            val json = headersOf("Content-Type", "application/json")
            if (req.url.encodedPath == "/api/songs") respond(catalog, HttpStatusCode.OK, json)
            else detail(req.url.encodedPathAndQuery).let { (status, body) -> respond(body, status, json) }
        }),
    ).also { it.start("http://cp.local:8765", "secret"); it.stop() }

    @Test
    fun `a song in one songbook is fetched from it, certain`() = runBlocking {
        val c = client { HttpStatusCode.OK to """{"number":"0005","title":"Один","songbook":"PV 2001","section-total":1,"sections":[{"type":"verse","lines":["[G]Слова"]}]}""" }
        val got = c.fetchLyrics(5, "Один")!!
        assertEquals("PV 2001", got.songbook)
        assertEquals(true, got.songbookCertain)
        assertEquals(listOf("Слова"), got.sections.single().lines)
        // Fetched by catalog id, never by title (which matches unnumbered songs first).
        assertEquals("/api/songs/id?id=1 key=secret", requests.last())
    }

    @Test
    fun `a song in two songbooks is kept but marked uncertain`() = runBlocking {
        val c = client { HttpStatusCode.OK to """{"number":"0368","title":"Ты познал","songbook":"PV 2001","sections":[{"type":"chorus","lines":["Есть сила"]}]}""" }
        val got = c.fetchLyrics(368, "Ты познал")!!
        assertEquals(false, got.songbookCertain)
        assertEquals("chorus", got.sections.single().kind)
    }

    @Test
    fun `a wrong answer, a missing song, or one not in the catalog gives nothing`() = runBlocking {
        assertNull(client { HttpStatusCode.OK to """{"number":"","title":"Other","songbook":"Christian Hymns","sections":[]}""" }.fetchLyrics(5, "Один"))
        assertNull(client { HttpStatusCode.NotFound to """{"error":"song not found"}""" }.fetchLyrics(5, "Один"))
        assertNull(client { HttpStatusCode.OK to "{}" }.fetchLyrics(999, "Not in the catalog"))
    }
}
