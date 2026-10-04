package songlistener.io

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.json.jsonArray
import songlistener.core.Lyrics
import songlistener.core.LyricsSection
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import songlistener.core.LiveSong
import songlistener.core.SongKeys
import java.util.UUID

/**
 * Follows ChurchPresenter's Companion WebSocket (`ws://host:8765/ws`) and reduces its
 * `live_state_changed` events to the song on screen. Reconnects until [stop].
 */
/** Words fetched for the song on screen; see [ChurchPresenterClient.fetchLyrics]. */
data class FetchedLyrics(val songbook: String?, val songbookCertain: Boolean, val sections: List<LyricsSection>)

/** What came of a "Show it": ChurchPresenter's operator approved it, declined it, or it failed. */
sealed interface ShowResult {
    data object Approved : ShowResult
    data object Declined : ShowResult
    data class Failed(val reason: String) : ShowResult
}

class ChurchPresenterClient(
    private val scope: CoroutineScope,
    private val client: HttpClient = HttpClient(CIO) { install(WebSockets) },
    /** Kept across launches so ChurchPresenter can remember this device as allowed. */
    private val deviceId: String = "songlistener-" + UUID.randomUUID().toString().take(8),
) {
    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected
    private val _liveSong = MutableStateFlow<LiveSong?>(null)
    val liveSong: StateFlow<LiveSong?> = _liveSong
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private var job: Job? = null

    private var httpBase: String? = null
    private var apiKey: String = ""
    /**
     * number|title → (songbook, catalog id) for every songbook holding it, from `/api/songs`. Ids
     * are positions in ChurchPresenter's list, valid until it reloads, so the map is dropped when it
     * says songs changed.
     */
    @Volatile private var catalog: Map<String, List<Pair<String, Int>>>? = null

    fun start(baseUrl: String, apiKey: String) {
        stop()
        httpBase = httpBaseOf(baseUrl)
        this.apiKey = apiKey
        catalog = null
        val wsUrl = baseUrl.trim().trimEnd('/')
            .replaceFirst("http://", "ws://").replaceFirst("https://", "wss://")
            .let { if (it.startsWith("ws")) it else "ws://$it" } + "/ws"
        job = scope.launch {
            while (isActive) {
                try {
                    client.webSocket(urlString = wsUrl, request = {
                        header(HEADER_DEVICE_ID, deviceId)
                        if (apiKey.isNotBlank()) parameter(QUERY_API_KEY, apiKey)
                    }) {
                        _connected.value = true
                        _error.value = null
                        for (frame in incoming) {
                            if (frame is Frame.Text) handle(frame.readText())
                        }
                    }
                } catch (e: Exception) {
                    _error.value = e.message ?: e::class.simpleName
                }
                _connected.value = false
                _liveSong.value = null
                delay(RECONNECT_MS)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        _connected.value = false
        _liveSong.value = null
    }

    private fun handle(text: String) {
        val msg = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
        msg["error"]?.let { _error.value = it.jsonPrimitive.contentOrNull; return }
        val type = msg["type"]?.jsonPrimitive?.contentOrNull
        if (type == TYPE_SONGS_UPDATED) { catalog = null; return }
        if (type != TYPE_LIVE_STATE) return
        val payload = msg["payload"]?.jsonPrimitive?.contentOrNull ?: return
        val dto = runCatching { Json.parseToJsonElement(payload).jsonObject }.getOrNull() ?: return
        _liveSong.value = liveSongOf(dto)
    }

    /**
     * The words of [number]/[title] as ChurchPresenter has them now, from the Companion API. The
     * song is found in the catalog and fetched by its id (`?id=`): asking by title instead matches
     * every unnumbered song first. The live state carries no songbook, so when several songbooks
     * hold the song (two editions of a hymnal, say) the first is fetched and marked uncertain.
     * Null when unavailable.
     */
    suspend fun fetchLyrics(number: Int, title: String): FetchedLyrics? {
        val base = httpBase ?: return null
        val books = (catalog ?: loadCatalog(base).also { catalog = it })[SongKeys.key(number, title)].orEmpty()
        val (_, id) = books.firstOrNull() ?: return null
        val response = client.get("$base/api/songs/id") {
            parameter("id", id)
            if (apiKey.isNotBlank()) header(HEADER_API_KEY, apiKey)
        }
        if (!response.status.isSuccess()) return null
        val o = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val gotNumber = o["number"]?.jsonPrimitive?.contentOrNull?.trim()?.toIntOrNull() ?: 0
        val gotTitle = o["title"]?.jsonPrimitive?.contentOrNull.orEmpty()
        // A title match from a different song (a different number) is not this song.
        if (gotNumber != number || !gotTitle.equals(title, ignoreCase = true)) return null
        val sections = o["sections"]?.jsonArray.orEmpty().map { sec ->
            val so = sec.jsonObject
            so["type"]?.jsonPrimitive?.contentOrNull.orEmpty() to so["lines"]?.jsonArray.orEmpty().map { it.jsonPrimitive.content }
        }
        return FetchedLyrics(
            songbook = o["songbook"]?.jsonPrimitive?.contentOrNull,
            songbookCertain = books.size <= 1,
            sections = Lyrics.fromApi(sections),
        )
    }

    /**
     * Asks ChurchPresenter to put a song on screen (`POST /api/project`). ChurchPresenter asks its
     * operator first unless this device is already allowed, and the request waits for that answer.
     */
    suspend fun showSong(number: Int, title: String, songbook: String?): ShowResult {
        val base = httpBase ?: return ShowResult.Failed("not connected to ChurchPresenter")
        val body = buildJsonObject {
            putJsonObject("item") {
                put("id", UUID.randomUUID().toString())
                put("type", "song")
                put("songNumber", number)
                put("title", title)
                songbook?.let { put("songbook", it) }
            }
        }.toString()
        return runCatching {
            val response = client.post("$base/api/project") {
                contentType(ContentType.Application.Json)
                header(HEADER_DEVICE_ID, deviceId)
                if (apiKey.isNotBlank()) header(HEADER_API_KEY, apiKey)
                setBody(body)
            }
            when {
                response.status.isSuccess() -> ShowResult.Approved
                response.status == HttpStatusCode.Forbidden -> ShowResult.Declined
                else -> ShowResult.Failed("ChurchPresenter answered ${response.status.value}")
            }
        }.getOrElse { ShowResult.Failed(it.message ?: it::class.simpleName.orEmpty()) }
    }

    /** The songbooks that hold [number]/[title], per ChurchPresenter's catalog. */
    suspend fun songbooksOf(number: Int, title: String): List<String> {
        val base = httpBase ?: return emptyList()
        return (catalog ?: loadCatalog(base).also { catalog = it })[SongKeys.key(number, title)].orEmpty().map { it.first }
    }

    private suspend fun loadCatalog(base: String): Map<String, List<Pair<String, Int>>> = runCatching {
        val response = client.get("$base/api/songs") { if (apiKey.isNotBlank()) header(HEADER_API_KEY, apiKey) }
        if (!response.status.isSuccess()) return@runCatching emptyMap()
        val out = HashMap<String, MutableList<Pair<String, Int>>>()
        Json.parseToJsonElement(response.bodyAsText()).jsonObject["song-book"]?.jsonArray.orEmpty().forEach { book ->
            val b = book.jsonObject
            val name = b["book-name"]?.jsonPrimitive?.contentOrNull.orEmpty()
            b["songs"]?.jsonArray.orEmpty().forEach { song ->
                val so = song.jsonObject
                val n = so["number"]?.jsonPrimitive?.contentOrNull?.trim()?.toIntOrNull() ?: 0
                val t = so["title"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val id = so["id"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: return@forEach
                out.getOrPut(SongKeys.key(n, t)) { mutableListOf() } += name to id
            }
        }
        out
    }.getOrDefault(emptyMap())

    internal companion object {
        const val TYPE_LIVE_STATE = "live_state_changed"
        const val TYPE_SONGS_UPDATED = "songs_updated"
        const val HEADER_API_KEY = "X-Api-Key"

        /** `http(s)://host:port` from whatever was typed (a bare host, or a ws:// URL). */
        fun httpBaseOf(baseUrl: String): String = baseUrl.trim().trimEnd('/')
            .replaceFirst("ws://", "http://").replaceFirst("wss://", "https://")
            .let { if (it.startsWith("http")) it else "http://$it" }
        const val HEADER_DEVICE_ID = "X-Device-Id"
        const val QUERY_API_KEY = "apiKey"
        const val RECONNECT_MS = 3000L

        /** A `LiveStateDto` → the song on screen, or null for anything that is not a song. */
        fun liveSongOf(dto: JsonObject): LiveSong? {
            if (dto["contentType"]?.jsonPrimitive?.contentOrNull != "LYRICS") return null
            return SongKeys.liveSong(
                number = dto["songNumber"]?.jsonPrimitive?.intOrNull ?: 0,
                title = dto["songTitle"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                section = dto["songSectionIndex"]?.jsonPrimitive?.intOrNull,
            )
        }
    }
}
