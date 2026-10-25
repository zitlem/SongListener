package songlistener.io

import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import songlistener.core.Config
import songlistener.core.SectionMark
import songlistener.core.SongScore
import songlistener.core.Suggestion
import java.net.InetSocketAddress

/**
 * The web page: the same controls as the desktop window — connection, live status, Hearing and
 * Learned songs, Review recordings and Tuning — served from [port] on every interface so a phone
 * or the sound desk can use it. Anyone on the network can make changes; each is logged with the
 * address it came from.
 */
class WebUi(private val engine: ListenerEngine, private val config: Config, port: Int = 8780) {
    private var server: HttpServer? = null
    /** The port it is (or was last asked to be) served on. */
    var port: Int = port
        private set
    private val _running = kotlinx.coroutines.flow.MutableStateFlow(false)
    val running: kotlinx.coroutines.flow.StateFlow<Boolean> = _running
    private val _error = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val error: kotlinx.coroutines.flow.StateFlow<String?> = _error

    /** Moves the page to [newPort]: stops it and starts it again there. */
    @Synchronized
    fun restart(newPort: Int) {
        stop()
        port = newPort
        start()
    }

    @Synchronized
    fun start() {
        val s = runCatching { HttpServer.create(InetSocketAddress(port), 0) }
            .onFailure { _error.value = if (it is java.net.BindException) "port $port is in use" else (it.message ?: it::class.simpleName) }
            .getOrNull() ?: return
        s.createContext("/api/state") { ex ->
            reply(ex, 200, "application/json; charset=utf-8", snapshot().toString())
        }
        s.createContext("/review") { ex -> reply(ex, 200, "text/html; charset=utf-8", REVIEW_PAGE) }
        s.createContext("/tuning") { ex -> reply(ex, 200, "text/html; charset=utf-8", TUNING_PAGE) }
        s.createContext("/api/tuning") { ex -> tuningApi(ex) }
        s.createContext("/api/control") { ex -> controlApi(ex) }
        s.createContext("/report") { ex ->
            val newest = runCatching { ServiceReport.writeAll(engine.logDir, engine.reportDir) }.getOrNull()
            if (newest == null) reply(ex, 200, "text/html; charset=utf-8", "<p>No logs yet.</p>")
            else reply(ex, 200, "text/html; charset=utf-8", newest.readText())
        }
        s.createContext("/logs") { ex ->
            val name = ex.requestURI.path.removePrefix("/logs").trim('/')
            val files = engine.logDir.listFiles { f -> f.name.endsWith(".jsonl") }.orEmpty().sortedByDescending { it.name }
            if (name.isEmpty()) {
                val list = files.joinToString("") { "<li><a href=\"/logs/${it.name}\">${it.name}</a> (${it.length() / 1024} KB)</li>" }
                reply(ex, 200, "text/html; charset=utf-8",
                    "<!doctype html><meta charset=utf-8><title>Logs</title><body style=\"font:15px system-ui;padding:16px\"><p><a href=\"/\">← SongListener</a></p><h1>Logs</h1><ul>$list</ul>")
            } else {
                val f = files.firstOrNull { it.name == name }
                if (f == null) reply(ex, 404, "text/plain", "not found") else reply(ex, 200, "text/plain; charset=utf-8", f.readText())
            }
        }
        s.createContext("/api/recordings") { ex ->
            val parts = ex.requestURI.path.removePrefix("/api/recordings").trim('/').split('/')
            when {
                ex.requestMethod == "GET" && parts == listOf("") -> {
                    if (engine.reviews.value.isEmpty()) engine.refreshReview()
                    reply(ex, 200, "application/json; charset=utf-8", recordingsJson().toString())
                }
                ex.requestMethod == "POST" && parts == listOf("relearn-clipped") -> {
                    engine.relearnAllClipped("web " + ex.remoteAddress.address.hostAddress)
                    reply(ex, 200, "application/json", "{\"ok\":true}")
                }
                ex.requestMethod == "POST" && parts.size == 2 -> correct(ex, java.net.URLDecoder.decode(parts[0], "UTF-8"), parts[1])
                else -> reply(ex, 404, "text/plain", "not found")
            }
        }
        s.createContext("/api/songs") { ex ->
            val q = ex.requestURI.rawQuery.orEmpty().split('&').firstOrNull { it.startsWith("q=") }
                ?.let { java.net.URLDecoder.decode(it.removePrefix("q="), "UTF-8") }.orEmpty().trim()
            val hits = if (q.isBlank()) emptyList() else kotlinx.coroutines.runBlocking { engine.knownSongs() }.filter { (k, label) ->
                label.contains(q, ignoreCase = true) || k.substringBefore('|') == q.trimStart('#')
            }.take(12)
            reply(ex, 200, "application/json; charset=utf-8",
                buildJsonArray { hits.forEach { (k, l) -> add(buildJsonObject { put("key", k); put("label", l) }) } }.toString())
        }
        s.createContext("/audio/") { ex ->
            val name = java.net.URLDecoder.decode(ex.requestURI.path.removePrefix("/audio/"), "UTF-8")
            val wav = engine.reviews.value.firstOrNull { it.name == name }?.wav
            if (wav == null || !wav.exists()) reply(ex, 404, "text/plain", "not found") else sendAudio(ex, wav)
        }
        s.createContext("/") { ex ->
            if (ex.requestURI.path != "/") reply(ex, 404, "text/plain", "not found")
            else reply(ex, 200, "text/html; charset=utf-8", PAGE)
        }
        s.executor = java.util.concurrent.Executors.newFixedThreadPool(2) { r -> Thread(r, "webui").apply { isDaemon = true } }
        s.start()
        server = s
        _running.value = true
        _error.value = null
    }

    @Synchronized
    fun stop() {
        server?.stop(0)
        server = null
        _running.value = false
    }

    /** Where the main page can be opened: this Mac's network addresses, for a phone on the same network. */
    fun urls(): List<String> = reviewUrls().map { it.removeSuffix("review") }

    /** Where the review page can be opened: this Mac's network addresses (a phone on the same network can use them). */
    fun reviewUrls(): List<String> = runCatching {
        java.net.NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }.filterIsInstance<java.net.Inet4Address>()
            .map { "http://${it.hostAddress}:$port/review" }
    }.getOrDefault(emptyList()).ifEmpty { listOf("http://localhost:$port/review") }

    /** Applies a correction from the review page; every one is logged with the address it came from. */
    private fun correct(ex: com.sun.net.httpserver.HttpExchange, name: String, action: String) {
        if (engine.reviews.value.none { it.name == name }) return reply(ex, 404, "text/plain", "no such recording")
        val from = "web " + ex.remoteAddress.address.hostAddress
        when (action) {
            "reviewed" -> engine.markReviewed(name, from)
            "delete" -> engine.deleteRecording(name, from)
            "relearn" -> engine.relearn(name, from)
            "relabel" -> {
                val song = runCatching {
                    kotlinx.serialization.json.Json.parseToJsonElement(ex.requestBody.readBytes().decodeToString())
                        .let { (it as JsonObject)["songKey"]!!.let { v -> (v as JsonPrimitive).content } }
                }.getOrNull()
                if (song.isNullOrBlank() || !song.contains('|')) return reply(ex, 400, "text/plain", "songKey needed")
                engine.relabel(name, song, from)
            }
            else -> return reply(ex, 404, "text/plain", "not found")
        }
        reply(ex, 200, "application/json", "{\"ok\":true}")
    }

    private fun body(ex: com.sun.net.httpserver.HttpExchange): JsonObject =
        runCatching { kotlinx.serialization.json.Json.parseToJsonElement(ex.requestBody.readBytes().decodeToString()) as JsonObject }
            .getOrDefault(JsonObject(emptyMap()))

    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.content
    private fun from(ex: com.sun.net.httpserver.HttpExchange) = "web " + ex.remoteAddress.address.hostAddress
    private fun ok(ex: com.sun.net.httpserver.HttpExchange) = reply(ex, 200, "application/json", "{\"ok\":true}")

    /** The main page's controls: connect, web port, dismiss, show, forget, forget-all. Same actions as the app's. */
    private fun controlApi(ex: com.sun.net.httpserver.HttpExchange) {
        if (ex.requestMethod != "POST") return reply(ex, 405, "text/plain", "POST only")
        val b = body(ex)
        when (ex.requestURI.path.removePrefix("/api/control").trim('/')) {
            "connect" -> {
                val cp = b.str("churchPresenterUrl"); val stt = b.str("sttUrl")
                if (cp.isNullOrBlank() || stt.isNullOrBlank()) return reply(ex, 400, "text/plain", "addresses needed")
                engine.tuning.saveConnection(cp.trim(), b.str("apiKey").orEmpty().trim(), stt.trim(), from(ex))
            }
            "webport" -> {
                val p = b.str("port")?.toIntOrNull()?.takeIf { it in 1024..65535 } ?: return reply(ex, 400, "text/plain", "port 1024–65535")
                engine.tuning.saveWebPort(p, from(ex))
                reply(ex, 200, "application/json", "{\"port\":$p}")
                // After answering: the page moves to the new port.
                Thread { Thread.sleep(300); restart(p) }.start()
                return
            }
            "dismiss" -> engine.state.value.wrongSlide?.let { engine.dismissed(it, from(ex)) }
            "show" -> {
                val key = b.str("songKey")?.takeIf { it.contains('|') } ?: return reply(ex, 400, "text/plain", "songKey needed")
                engine.show(key, b.str("songbook").orEmpty(), from(ex))
            }
            "forget" -> {
                val key = b.str("songKey")?.takeIf { it.contains('|') } ?: return reply(ex, 400, "text/plain", "songKey needed")
                engine.forget(key, from(ex))
            }
            "forget-all" -> engine.forgetAll(from(ex))
            else -> return reply(ex, 404, "text/plain", "not found")
        }
        ok(ex)
    }

    /** The Tuning page: GET its state; POST settings, test, test/stop, clipping. */
    private fun tuningApi(ex: com.sun.net.httpserver.HttpExchange) {
        val t = engine.tuning
        if (ex.requestMethod == "GET") return reply(ex, 200, "application/json; charset=utf-8", tuningJson().toString())
        val b = body(ex)
        when (ex.requestURI.path.removePrefix("/api/tuning").trim('/')) {
            "settings" -> t.saveMatching(
                b.str("keepTakes") ?: "newest", b.str("maxTakes")?.toIntOrNull() ?: 5, b.str("agreement")?.toDoubleOrNull() ?: 0.0,
                b.str("intro") ?: "match", b.str("confirmed")?.toBoolean() ?: false, from(ex),
            )
            "test" -> t.startTest(b.str("cleanOnly")?.toBoolean() ?: true, from(ex))
            "test/stop" -> t.stopTest()
            "clipping" -> t.checkClipping()
            else -> return reply(ex, 404, "text/plain", "not found")
        }
        ok(ex)
    }

    private fun matchingJson(c: Config) = buildJsonObject {
        put("keepTakes", c.keepTakes); put("maxTakes", c.maxTakesPerSong); put("agreement", c.takeAgreement)
        put("intro", c.introMode); put("confirmed", c.confirmedParts)
    }

    fun tuningJson(): JsonObject {
        val t = engine.tuning
        val reviews = engine.reviews.value
        val perSong = engine.clipStats.value.perSong
        val test = t.test.value
        val best = t.best(test.results)
        val clipping = t.clipping.value
        return buildJsonObject {
            put("saved", matchingJson(t.savedConfig()))
            put("running", matchingJson(t.running))
            put("guide", buildJsonObject {
                put("sttConnected", engine.stt.connected.value)
                engine.level.value.let { put("clipping", it.clipping); put("hot", it.hot) }
                put("clipped", reviews.count { r -> r.labels.relearnRequestedAt == null && r.check?.flags?.any { it.code == "clipped" } == true })
                put("flagged", reviews.count { (it.check?.severity ?: 0) >= 1 && !it.labels.reviewed })
                put("twice", perSong.count { it.value >= 2 }); put("thrice", perSong.count { it.value >= 3 })
            })
            put("test", buildJsonObject {
                put("running", test.running); put("progress", test.progress); put("cleanOnly", test.cleanOnly)
                test.status?.let { put("status", it) }
                put("results", buildJsonArray {
                    test.results.forEach { r ->
                        val s = r.summary
                        add(buildJsonObject {
                            put("name", r.variant.name); put("matching", matchingJson(r.variant.config))
                            put("right", s.rightAtAll); put("testable", s.testable); put("wrong", s.wrongSuggestions)
                            s.meanTop1?.let { put("top1", it) }
                            put("wrongSlideCaught", s.wrongSlideCaught); put("wrongSlideTests", s.wrongSlideTests); put("falseAlarms", s.falseAlarms)
                            put("best", best != null && s.rightAtAll == best.summary.rightAtAll && s.wrongSuggestions == best.summary.wrongSuggestions)
                        })
                    }
                })
            })
            put("clippingScan", buildJsonObject {
                put("busy", clipping.busy)
                clipping.rows?.let { rows ->
                    put("days", buildJsonArray {
                        ClipScan.days(rows).reversed().forEach { d -> add(buildJsonObject {
                            put("day", d.day.toString()); put("recordings", d.recordings); put("clean", d.clean); put("light", d.light); put("heavy", d.heavy)
                            put("loudestPeakDb", d.loudestPeakDb); put("medianRmsDb", d.medianRmsDb)
                        }) }
                    })
                    put("rows", buildJsonArray {
                        ClipScan.worstFirst(rows).forEach { r -> add(buildJsonObject {
                            put("name", r.name); put("origin", r.labels.origin ?: "-"); put("peakDb", r.level.peakDb); put("rmsDb", r.level.rmsDb)
                            put("clippedFraction", r.level.clippedFraction); put("worst", ClipScan.mmss(r.level.worstSecond)); put("severity", r.level.severity)
                        }) }
                    })
                }
            })
        }
    }

    private fun showJson(songKey: String): JsonObject {
        engine.prepareShow(songKey)
        return buildJsonObject {
            put("songKey", songKey)
            engine.editions.value[songKey]?.let { books -> put("books", buildJsonArray { books.forEach { add(JsonPrimitive(it)) } }) }
            engine.showStatus.value[songKey]?.let { put("status", it) }
        }
    }

    /** Sends a recording, honouring a byte Range so the browser's player can seek. */
    private fun sendAudio(ex: com.sun.net.httpserver.HttpExchange, wav: java.io.File) {
        val size = wav.length()
        val range = ex.requestHeaders.getFirst("Range")?.let { Regex("bytes=(\\d*)-(\\d*)").find(it) }
        val start = range?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val end = (range?.groupValues?.get(2)?.toLongOrNull() ?: (size - 1)).coerceAtMost(size - 1)
        ex.responseHeaders.add("Content-Type", "audio/wav")
        ex.responseHeaders.add("Accept-Ranges", "bytes")
        if (range != null) ex.responseHeaders.add("Content-Range", "bytes $start-$end/$size")
        val length = end - start + 1
        ex.sendResponseHeaders(if (range != null) 206 else 200, if (ex.requestMethod == "HEAD") -1 else length)
        if (ex.requestMethod == "HEAD") return ex.close()
        java.io.RandomAccessFile(wav, "r").use { f ->
            f.seek(start)
            ex.responseBody.use { out ->
                val buf = ByteArray(64 * 1024)
                var left = length
                while (left > 0) {
                    val n = f.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (n < 0) break
                    out.write(buf, 0, n)
                    left -= n
                }
            }
        }
    }

    private fun recordingsJson(): JsonArray = buildJsonArray {
        for (item in engine.reviews.value) {
            val l = item.labels
            val c = item.check
            add(buildJsonObject {
                put("name", item.name)
                put("song", l.songLabel)
                put("key", l.songKey)
                put("startedAt", l.startedAt)
                put("origin", l.origin ?: "import")
                put("seconds", l.endSeconds)
                put("slideAt", l.goLiveSeconds)
                put("reviewed", l.reviewed)
                put("relearnRequested", l.relearnRequestedAt != null)
                l.relabelledFrom?.let { put("relabelledFrom", songlistener.core.SongKeys.labelOf(it) ?: it) }
                put("checked", c != null)
                c?.introSeconds?.let { put("introSeconds", it) }
                c?.introMatchedSeconds?.let { put("introMatchedSeconds", it) }
                put("severity", c?.severity ?: 0)
                c?.rivalKey?.let { put("rivalKey", it) }
                c?.rivalLabel?.let { put("rivalLabel", it) }
                c?.rivalScore?.let { put("rivalScore", it) }
                put("flags", buildJsonArray {
                    c?.flags?.forEach { f -> add(buildJsonObject { put("code", f.code); put("text", f.text); put("severity", f.severity) }) }
                })
            })
        }
    }

    private fun reply(ex: com.sun.net.httpserver.HttpExchange, code: Int, type: String, body: String) {
        val bytes = body.toByteArray()
        ex.responseHeaders.add("Content-Type", type)
        ex.responseHeaders.add("Cache-Control", "no-store")
        ex.sendResponseHeaders(code, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private fun section(s: Int?): JsonElement =
        if (s == null) JsonNull else JsonPrimitive(if (s == SectionMark.INTRO) "intro" else "section $s")

    private fun score(s: SongScore, liveKey: String?) = buildJsonObject {
        put("label", s.songLabel); put("score", s.score)
        put("onScreen", s.songKey == liveKey)
        put("key", s.rotation.let { if (it > 6) it - 12 else it })
        put("tempo", s.stretch)
        put("at", (s.offsetFrame / config.featuresPerSecond).toInt())
        put("section", section(s.section))
    }

    private fun suggestion(s: Suggestion?): JsonElement = if (s == null) JsonNull else buildJsonObject {
        put("label", s.songLabel); put("score", s.score); put("at", s.at); put("section", section(s.section))
    }

    fun snapshot(): JsonObject {
        val st = engine.state.value
        val liveKey = st.liveSong?.key
        val a = st.accuracy
        return buildJsonObject {
            put("cpConnected", engine.churchPresenter.connected.value)
            put("sttConnected", engine.stt.connected.value)
            put("musicProb", st.musicProb.toDouble()); put("isMusic", st.isMusic)
            engine.level.value.let { l -> put("peakDb", l.peakDb); put("clipping", l.clipping); put("clippedRecently", l.clippedRecently); put("hot", l.hot) }
            put("live", st.liveSong?.let { it.label + (it.section?.let { s -> " · section $s" } ?: "") }?.let(::JsonPrimitive) ?: JsonNull)
            put("cantCheckLive", st.cantCheckLive)
            put("slideCheck", songlistener.core.slideCheck(st, config)?.let { c -> buildJsonObject { put("kind", c.kind); put("text", c.text) } } ?: JsonNull)
            put("steadyScore", config.steadyScore)
            put("neededSeconds", config.queryFrames / config.featuresPerSecond)
            put("wrongSlide", st.wrongSlide?.takeIf { it.at != engine.dismissedWrongSlide.value }?.let { w ->
                buildJsonObject {
                    put("heard", w.heard.songLabel); put("heardScore", w.heard.score); put("shown", w.shown.label); put("shownScore", w.shownScore)
                    put("show", showJson(w.heard.songKey))
                }
            } ?: JsonNull)
            put("suggested", suggestion(st.lastSuggestion))
            st.lastSuggestion?.takeIf { it.songKey != liveKey }?.let { put("suggestedShow", showJson(it.songKey)) }
            val hint = st.hint?.takeIf { it.songKey != st.lastSuggestion?.songKey }
            put("hint", suggestion(hint))
            hint?.takeIf { it.songKey != liveKey }?.let { put("hintShow", showJson(it.songKey)) }
            val saved = engine.tuning.saved.value
            put("settings", buildJsonObject {
                put("churchPresenterUrl", saved.churchPresenterUrl); put("apiKey", saved.apiKey); put("sttUrl", saved.sttUrl)
                put("webPort", port); put("webUrls", buildJsonArray { urls().forEach { add(JsonPrimitive(it)) } })
                put("restartNeeded", engine.tuning.savedConfig() != engine.tuning.running)
            })
            engine.churchPresenter.error.value?.let { put("cpError", it) }
            engine.stt.error.value?.let { put("sttError", it) }
            val reviews = engine.reviews.value
            put("review", buildJsonObject {
                put("flagged", reviews.count { (it.check?.severity ?: 0) >= 1 && !it.labels.reviewed })
                put("clipped", reviews.count { r -> r.labels.relearnRequestedAt == null && r.check?.flags?.any { it.code == "clipped" } == true })
            })
            put("take", buildJsonObject {
                put("introSeconds", (st.recordingIntroFrames / config.featuresPerSecond).toInt())
                put("songSeconds", ((st.recordingFrames - st.recordingIntroFrames) / config.featuresPerSecond).toInt())
                put("neededSeconds", (config.minTakeFrames / config.featuresPerSecond).toInt())
                put("heldSeconds", (st.lookbackFrames / config.featuresPerSecond).toInt())
            })
            put("queryFrames", st.queryFrames); put("queryNeeded", config.queryFrames)
            put("candidates", st.lastMatch?.let { m ->
                buildJsonObject {
                    put("margin", m.margin)
                    put("ranked", JsonArray(m.ranked.take(3).map { score(it, liveKey) }))
                }
            } ?: JsonNull)
            put("accuracy", buildJsonObject {
                put("runs", a.runs); put("top1", a.top1); put("confident", a.confident); put("confidentHits", a.confidentHits)
                put("last", a.lastPrediction?.let { p ->
                    buildJsonObject { put("label", p.songLabel); put("score", p.score); put("hit", p.songKey == a.lastLiveKey) }
                } ?: JsonNull)
            })
            val clips = engine.clipStats.value
            put("recordings", buildJsonObject { put("imported", clips.imported); put("live", clips.live); put("bytes", clips.bytes) })
            put("library", buildJsonArray {
                engine.library.value.forEach { s ->
                    add(buildJsonObject {
                        put("key", s.key); put("label", s.label); put("takes", s.takes); put("seconds", s.seconds)
                        put("imported", s.recordings - s.liveRecordings); put("live", s.liveRecordings)
                        put("lastRecorded", s.lastRecorded); put("onScreen", s.key == liveKey)
                    })
                }
            })
        }
    }

    private companion object {
        /** Plain HTML and script, kept as a resource so its `${'$'}{…}` aren't Kotlin templates. */
        val REVIEW_PAGE: String by lazy { WebUi::class.java.getResource("/review.html")!!.readText() }

        val PAGE: String by lazy { WebUi::class.java.getResource("/index.html")!!.readText() }
        val TUNING_PAGE: String by lazy { WebUi::class.java.getResource("/tuning.html")!!.readText() }
    }
}
