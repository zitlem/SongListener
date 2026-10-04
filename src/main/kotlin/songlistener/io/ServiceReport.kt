package songlistener.io

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import songlistener.core.SongKeys
import java.io.File
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Turns one day's review log into a self-contained HTML page: a timeline (on screen, music, match
 * scores), a per-song table, the suggestions made, and a music-detection summary.
 */
object ServiceReport {

    /** Writes a report for every log in [logDir] into [reportDir]; returns the newest one. */
    fun writeAll(logDir: File, reportDir: File): File? {
        val logs = logDir.listFiles { f -> f.name.endsWith(".jsonl") }.orEmpty().sortedBy { it.name }
        if (logs.isEmpty()) return null
        reportDir.mkdirs()
        return logs.map { log ->
            File(reportDir, log.name.removeSuffix(".jsonl") + ".html").also { it.writeText(render(log.name.removeSuffix(".jsonl"), log.readLines())) }
        }.last()
    }

    // ---- parsing ----

    internal class Ev(val time: LocalDateTime, val type: String, val o: JsonObject) {
        fun str(k: String) = o[k]?.jsonPrimitive?.contentOrNull
        fun num(k: String) = o[k]?.jsonPrimitive?.doubleOrNull
        fun int(k: String) = o[k]?.jsonPrimitive?.intOrNull
        val top: List<JsonObject> get() = o["top"]?.jsonArray?.map { it.jsonObject }.orEmpty()
        val topScore: Double get() = top.firstOrNull()?.get("score")?.jsonPrimitive?.doubleOrNull ?: 0.0
        val topSong: String get() = SongKeys.labelOf(topKey) ?: top.firstOrNull()?.get("song")?.jsonPrimitive?.contentOrNull.orEmpty()
        val topKey: String get() = top.firstOrNull()?.get("key")?.jsonPrimitive?.contentOrNull.orEmpty()
        val topSection: Int? get() = top.firstOrNull()?.get("section")?.jsonPrimitive?.intOrNull
    }

    internal class Span(val start: LocalDateTime, val end: LocalDateTime) {
        val seconds: Long get() = Duration.between(start, end).seconds
    }

    internal class SongOnScreen(val key: String, val label: String, val span: Span)

    private val TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private val CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss")
    private val SHORT = DateTimeFormatter.ofPattern("HH:mm")

    internal fun parse(lines: List<String>): List<Ev> = lines.mapNotNull { line ->
        runCatching {
            val o = Json.parseToJsonElement(line).jsonObject
            Ev(LocalDateTime.parse(o["time"]!!.jsonPrimitive.content, TIME), o["type"]!!.jsonPrimitive.content, o)
        }.getOrNull()
    }

    /** A song's name from its key (older logs wrote "Title (#n)"), else as logged. */
    private fun songName(e: Ev) = e.str("key")?.let { SongKeys.labelOf(it) } ?: e.str("song").orEmpty()

    /** Songs on screen: each `song_live` until the next song, the song leaving, a disconnect or the app stopping. */
    internal fun songsOnScreen(evs: List<Ev>): List<SongOnScreen> {
        val out = mutableListOf<SongOnScreen>()
        var current: Ev? = null
        val enders = setOf("song_live", "song_off_screen", "churchpresenter_disconnected", "app_stopped", "app_started")
        for (e in evs) {
            if (e.type in enders) {
                current?.let { out += SongOnScreen(it.str("key").orEmpty(), songName(it), Span(it.time, e.time)) }
                current = if (e.type == "song_live") e else null
            }
        }
        current?.let { out += SongOnScreen(it.str("key").orEmpty(), songName(it), Span(it.time, evs.last().time)) }
        return out
    }

    /** Music as STT reported it: each `music_started` until the next `music_stopped` (or disconnect). */
    internal fun musicSpans(evs: List<Ev>): List<Span> {
        val out = mutableListOf<Span>()
        var start: LocalDateTime? = null
        for (e in evs) {
            when (e.type) {
                "music_started" -> if (start == null) start = e.time
                "music_stopped", "stt_disconnected", "app_stopped" -> start?.let { out += Span(it, e.time); start = null }
            }
        }
        start?.let { out += Span(it, evs.last().time) }
        return out
    }

    // ---- rendering ----

    internal fun render(day: String, lines: List<String>): String {
        val evs = parse(lines)
        val songs = songsOnScreen(evs)
        val music = musicSpans(evs)
        val matches = evs.filter { it.type == "match" }
        val active = evs.filter { it.type in setOf("song_live", "music_started", "match") }
        val body = StringBuilder()
        body.append("<h1>Service report · ${esc(day)}</h1>")
        if (active.isEmpty()) {
            body.append("<p class=muted>No songs and no music in this log.</p>")
            return page(day, body.toString())
        }
        val from = active.first().time.minusMinutes(1)
        val to = active.last().time.plusMinutes(1)
        body.append("<p class=muted>${from.format(SHORT)} – ${to.format(SHORT)} · ${songs.size} song(s) on screen · ${matches.size} match runs</p>")
        body.append(timeline(Span(from, to), songs, music, matches, evs.filter { it.type == "music_break" }, lateSlides(evs, songs), evs.filter { it.type == "wrong_slide_warning" }))
        body.append(legend())
        body.append(songTable(songs, music, evs))
        val late = lateSlides(evs, songs)
        body.append(lateTable(late))
        body.append(wrongSlideTable(evs))
        body.append(suggestionTable(matches, songs))
        body.append(musicSummary(evs, music))
        return page(day, body.toString())
    }

    private fun timeline(range: Span, songs: List<SongOnScreen>, music: List<Span>, matches: List<Ev>, breaks: List<Ev>, late: List<Late>, warnings: List<Ev>): String {
        val w = 1000.0
        val left = 70.0
        val plotW = w - left - 10
        val total = range.seconds.coerceAtLeast(1).toDouble()
        fun x(t: LocalDateTime) = left + plotW * Duration.between(range.start, t).seconds / total
        val laneScreen = 20.0
        val laneMusic = 60.0
        val scoreTop = 100.0
        val scoreH = 160.0
        val axisY = scoreTop + scoreH + 18
        fun y(score: Double) = scoreTop + scoreH * (1 - score.coerceIn(0.0, 1.0))
        val sb = StringBuilder()
        sb.append("<div class=card><h2>Timeline</h2><svg viewBox='0 0 $w ${axisY + 20}' class=timeline role=img aria-label='Service timeline'>")
        sb.append("<text x=4 y=${laneScreen + 18} class=lane>On screen</text>")
        sb.append("<text x=4 y=${laneMusic + 18} class=lane>Music</text>")
        sb.append("<text x=4 y=${scoreTop + 10} class=lane>Score</text>")
        // score gridlines
        for (g in listOf(0.0, 0.5, 0.8, 1.0)) {
            val cls = if (g == 0.8) "threshold" else "grid"
            sb.append("<line x1=$left x2=${w - 10} y1=${y(g)} y2=${y(g)} class=$cls />")
            sb.append("<text x=${left - 4} y=${y(g) + 4} class=tick text-anchor=end>${"%.1f".format(g)}</text>")
        }
        for (l in late) {
            val x0 = x(l.musicStart)
            val x1 = maxOf(x(l.slideUp), x0 + 2)
            sb.append("<rect x=$x0 y=$laneScreen width=${x1 - x0} height=26 rx=3 class=late><title>${esc(l.label)}: music ${fmtSec(l.lateSeconds)} before the slide</title></rect>")
        }
        for (w in warnings) {
            val xw = x(w.time)
            sb.append("<path d='M${xw - 6},${laneScreen - 2} L${xw + 6},${laneScreen - 2} L$xw,${laneScreen + 9} Z' class=warn-mark><title>${w.time.format(CLOCK)} wrong slide? heard ${esc(SongKeys.labelOf(w.str("heardKey").orEmpty()) ?: w.str("heard").orEmpty())}</title></path>")
        }
        for (s in songs) {
            val x0 = x(s.span.start)
            val x1 = maxOf(x(s.span.end), x0 + 2)
            sb.append("<rect x=$x0 y=$laneScreen width=${x1 - x0} height=26 rx=3 class=screen><title>${esc(s.label)}\n${s.span.start.format(CLOCK)}–${s.span.end.format(CLOCK)}</title></rect>")
            if (x1 - x0 > 60) sb.append("<text x=${x0 + 4} y=${laneScreen + 17} class=onbar>${esc(s.label.take(((x1 - x0) / 7).toInt()))}</text>")
        }
        for (m in music) {
            val x0 = x(m.start)
            val x1 = maxOf(x(m.end), x0 + 1)
            sb.append("<rect x=$x0 y=$laneMusic width=${x1 - x0} height=26 rx=2 class=music><title>Music ${m.start.format(CLOCK)}–${m.end.format(CLOCK)}</title></rect>")
        }
        for (b in breaks) sb.append("<line x1=${x(b.time)} x2=${x(b.time)} y1=${laneMusic - 3} y2=${laneMusic + 29} class=break><title>Music break ${b.time.format(CLOCK)}</title></line>")
        for (m in matches) {
            val d = m.str("decision").orEmpty()
            val r = if (d == "suggested") 5 else 2.5
            val section = m.topSection?.let { " · ${sectionName(it)}" }.orEmpty()
            sb.append("<circle cx=${x(m.time)} cy=${y(m.topScore)} r=$r class='dot ${cssClass(d)}'><title>${m.time.format(CLOCK)} ${esc(d)}\n${esc(m.topSong)} ${"%.2f".format(m.topScore)}$section\nmargin ${"%.2f".format(m.num("margin") ?: 0.0)}</title></circle>")
        }
        // time axis
        val stepMin = listOf(1L, 2L, 5L, 10L, 15L, 30L, 60L).first { total / 60 / it <= 12 }
        var t = range.start.withSecond(0).plusMinutes(stepMin - range.start.minute % stepMin)
        while (t.isBefore(range.end)) {
            sb.append("<line x1=${x(t)} x2=${x(t)} y1=$scoreTop y2=${scoreTop + scoreH} class=grid />")
            sb.append("<text x=${x(t)} y=$axisY class=tick text-anchor=middle>${t.format(SHORT)}</text>")
            t = t.plusMinutes(stepMin)
        }
        sb.append("</svg></div>")
        return sb.toString()
    }

    private fun legend(): String = """
        <div class=legend>
          <span><i class='sw screen'></i>Song on screen</span>
          <span><i class='sw music'></i>Music (STT)</span>
          <span><i class='sw brk'></i>Music break</span>
          <span><i class='sw late'></i>Music before a late slide</span>
          <span><i class='warn-sw'></i>Wrong-slide warning</span>
          <span><i class='dot-sw hint'></i>Early guess</span>
          <span><i class='dot-sw suggested'></i>Suggested</span>
          <span><i class='dot-sw streak'></i>Streak</span>
          <span><i class='dot-sw hit'></i>Accuracy hit</span>
          <span><i class='dot-sw miss'></i>Accuracy miss</span>
          <span><i class='dot-sw suppressed'></i>Suppressed</span>
          <span><i class='dot-sw low'></i>Below threshold / unlearned</span>
          <span><i class='sw thr'></i>0.80 suggest threshold</span>
        </div>""".trimIndent()

    private fun songTable(songs: List<SongOnScreen>, music: List<Span>, evs: List<Ev>): String {
        if (songs.isEmpty()) return ""
        val sb = StringBuilder("<div class=card><h2>Songs on screen</h2><table><thead><tr>")
        sb.append("<th>Song</th><th>On screen</th><th class=n>Music heard</th><th>Take</th><th class=n>Hits / misses</th><th class=n>Section right</th><th class=n>Best right-song score</th><th class=n>Best wrong-song score</th></tr></thead><tbody>")
        for (s in songs) {
            val inSpan = evs.filter { !it.time.isBefore(s.span.start) && !it.time.isAfter(s.span.end) }
            val heard = music.sumOf { overlap(it, s.span) }
            val take = inSpan.lastOrNull { (it.type == "take_saved" || it.type == "take_discarded") && it.str("key") == s.key }
                ?: evs.firstOrNull { (it.type == "take_saved" || it.type == "take_discarded") && it.str("key") == s.key && !it.time.isBefore(s.span.end) && Duration.between(s.span.end, it.time).seconds <= 2 }
            val takeText = when (take?.type) {
                "take_saved" -> "saved · ${fmtSec(take.num("songSeconds"))}" + (take.num("introSeconds")?.takeIf { it >= 1 }?.let { " + ${fmtSec(it)} intro" } ?: "")
                "take_discarded" -> "<span class=warn>discarded · ${fmtSec(take.num("songSeconds"))}</span>"
                else -> "<span class=muted>—</span>"
            }
            val ms = inSpan.filter { it.type == "match" && it.str("live") == s.key }
            val hits = ms.count { it.str("decision") == "accuracy_hit" }
            val misses = ms.count { it.str("decision") == "accuracy_miss" }
            val scored = hits + misses
            val sectionRight = ms.filter { it.str("decision") == "accuracy_hit" && it.topSection != null && it.int("liveSection") != null }
            val sectionText = if (sectionRight.isEmpty()) "<span class=muted>—</span>"
                else "${sectionRight.count { it.topSection == it.int("liveSection") }} / ${sectionRight.size}"
            val right = ms.flatMap { it.top }.filter { it["key"]?.jsonPrimitive?.contentOrNull == s.key }
                .maxOfOrNull { it["score"]!!.jsonPrimitive.doubleOrNull ?: 0.0 }
            val wrong = ms.flatMap { it.top }.filter { it["key"]?.jsonPrimitive?.contentOrNull != s.key }
                .maxByOrNull { it["score"]!!.jsonPrimitive.doubleOrNull ?: 0.0 }
            val wrongText = wrong?.let {
                val sc = it["score"]!!.jsonPrimitive.doubleOrNull ?: 0.0
                val cls = if (right != null && sc >= right) "warn" else if (sc >= 0.8) "warn" else ""
                "<span class='$cls'>${"%.2f".format(sc)}</span> <span class=muted>${esc((it["key"]?.jsonPrimitive?.contentOrNull?.let { k -> SongKeys.labelOf(k) } ?: it["song"]?.jsonPrimitive?.contentOrNull.orEmpty()).take(28))}</span>"
            } ?: "<span class=muted>—</span>"
            sb.append("<tr><td>${esc(s.label)}</td><td>${s.span.start.format(CLOCK)} – ${s.span.end.format(CLOCK)} <span class=muted>(${fmtSec(s.span.seconds.toDouble())})</span></td>")
            sb.append("<td class=n>${fmtSec(heard.toDouble())}</td><td class=nw>$takeText</td>")
            sb.append("<td class=n>${if (scored == 0) "<span class=muted>not learned yet</span>" else "$hits / $misses"}</td>")
            sb.append("<td class=n>$sectionText</td>")
            sb.append("<td class=n>${right?.let { "%.2f".format(it) } ?: "<span class=muted>—</span>"}</td><td class=n>$wrongText</td></tr>")
        }
        sb.append("</tbody></table></div>")
        return sb.toString()
    }

    /** A slide that went up [lateSeconds] after its music started at [musicStart]. */
    internal class Late(
        val key: String,
        val label: String,
        val musicStart: LocalDateTime,
        val slideUp: LocalDateTime,
        val lateSeconds: Double,
        val hintLead: Double?,
        val suggestionLead: Double?,
        val showClicked: String?,
    )

    /**
     * Late slides: from the `late_slide` lines the app writes as they happen, or — for logs from
     * before those existed — worked out from when the music started (the look-back starting, else
     * music starting) and when the song went live, with any early guess or suggestion in between.
     */
    internal fun lateSlides(evs: List<Ev>, songs: List<SongOnScreen>, minSeconds: Double = 20.0): List<Late> {
        val logged = evs.filter { it.type == "late_slide" }
        if (logged.isNotEmpty()) return logged.map { e ->
            val late = e.num("lateSeconds") ?: 0.0
            val key = e.str("key").orEmpty()
            Late(key, songName(e), e.time.minusSeconds(late.toLong()), e.time, late,
                e.num("hintLeadSeconds"), e.num("suggestionLeadSeconds"), showsBetween(evs, key, e.time.minusSeconds(late.toLong()), e.time))
        }
        return songs.mapNotNull { s ->
            val before = evs.filter { it.time.isBefore(s.span.start) || it.time == s.span.start }
            // Only a song that went up with nothing on screen before it can be late.
            val lastScreen = before.lastOrNull { it.type in setOf("song_live", "song_off_screen", "churchpresenter_disconnected", "app_started") && it.time.isBefore(s.span.start) }
            if (lastScreen?.type == "song_live") return@mapNotNull null
            val since = lastScreen?.time ?: LocalDateTime.MIN
            val musicStart = before.lastOrNull { it.type == "lookback_started" && it.time.isAfter(since) }?.time
                ?: before.lastOrNull { it.type == "music_started" && it.time.isAfter(since) }?.time
                ?: return@mapNotNull null
            val late = Duration.between(musicStart, s.span.start).seconds.toDouble()
            if (late < minSeconds) return@mapNotNull null
            fun lead(decisions: Set<String>) = evs.firstOrNull {
                it.type == "match" && it.str("decision") in decisions && it.topKey == s.key &&
                    !it.time.isBefore(musicStart) && it.time.isBefore(s.span.start)
            }?.let { Duration.between(it.time, s.span.start).seconds.toDouble() }
            Late(s.key, s.label, musicStart, s.span.start, late, lead(setOf("hint")), lead(setOf("suggested")), showsBetween(evs, s.key, musicStart, s.span.start))
        }
    }

    /** The outcome of a Show-it click for [key] between [from] and [to], if there was one. */
    private fun showsBetween(evs: List<Ev>, key: String, from: LocalDateTime, to: LocalDateTime): String? {
        val asked = evs.firstOrNull { it.type == "show_requested" && it.str("detail").orEmpty().startsWith(key) && !it.time.isBefore(from) && !it.time.isAfter(to) }
            ?: return null
        val result = evs.firstOrNull { it.type == "show_result" && it.str("detail").orEmpty().startsWith(key) && !it.time.isBefore(asked.time) }
        return result?.str("detail")?.removePrefix(key)?.trim() ?: "sent"
    }

    private fun lateTable(late: List<Late>): String {
        val sb = StringBuilder("<div class=card><h2>Late slides</h2>")
        if (late.isEmpty()) return sb.append("<p class=muted>No slide went up 20 s or more after its music.</p></div>").toString()
        sb.append("<table><thead><tr><th>Slide up</th><th>Song</th><th class=n>Music before the slide</th><th>Early guess</th><th>Suggestion</th><th>Show it</th></tr></thead><tbody>")
        fun lead(v: Double?) = v?.let { "<span class=ok>${fmtSec(it)} before</span>" } ?: "<span class=warn>missed</span>"
        for (l in late) {
            sb.append("<tr><td>${l.slideUp.format(CLOCK)}</td><td>${esc(l.label)}</td><td class=n>${fmtSec(l.lateSeconds)}</td>")
            sb.append("<td>${lead(l.hintLead)}</td><td>${lead(l.suggestionLead)}</td><td>${l.showClicked?.let(::esc) ?: "<span class=muted>—</span>"}</td></tr>")
        }
        val caught = late.count { it.hintLead != null || it.suggestionLead != null }
        sb.append("</tbody></table><p class=muted>Named before the slide: $caught of ${late.size}.</p></div>")
        return sb.toString()
    }

    /** Each wrong-slide warning, and what came of it. */
    private fun wrongSlideTable(evs: List<Ev>): String {
        val warnings = evs.filter { it.type == "wrong_slide_warning" }
        val sb = StringBuilder("<div class=card><h2>Wrong slides</h2>")
        if (warnings.isEmpty()) return sb.append("<p class=muted>No wrong-slide warnings.</p></div>").toString()
        sb.append("<table><thead><tr><th>Time</th><th>On screen</th><th>Music sounded like</th><th class=n>Scores</th><th>What happened</th></tr></thead><tbody>")
        for (w in warnings) {
            val shownKey = w.str("key").orEmpty()
            val heardKey = w.str("heardKey").orEmpty()
            val after = evs.filter { it.time.isAfter(w.time) || (it.time == w.time && it !== w) }
            val outcome = after.firstNotNullOfOrNull { e ->
                val secs = fmtSec(Duration.between(w.time, e.time).seconds.toDouble())
                when {
                    e.type == "show_requested" && e.str("detail").orEmpty().startsWith(heardKey) ->
                        "Show it clicked after $secs — " + (showsBetween(evs, heardKey, e.time, e.time.plusMinutes(10)) ?: "sent")
                    e.type == "wrong_slide_dismissed" -> "dismissed after $secs"
                    e.type == "song_live" -> "slide changed to ${esc(songName(e))} after $secs"
                    e.type == "song_off_screen" -> "slide taken down after $secs"
                    e.type == "wrong_slide_cleared" && e.str("key") == shownKey -> "music matched the slide again after $secs"
                    else -> null
                }
            } ?: "<span class=muted>nothing further logged</span>"
            sb.append("<tr><td>${w.time.format(CLOCK)}</td><td>${esc(songName(w))}</td>")
            sb.append("<td>${esc(SongKeys.labelOf(heardKey) ?: w.str("heard").orEmpty())}</td>")
            sb.append("<td class=n>${"%.2f".format(w.num("shownScore") ?: 0.0)} vs ${"%.2f".format(w.num("heardScore") ?: 0.0)}</td><td>$outcome</td></tr>")
        }
        sb.append("</tbody></table></div>")
        return sb.toString()
    }

    private fun suggestionTable(matches: List<Ev>, songs: List<SongOnScreen>): String {
        val sugg = matches.filter { it.str("decision") == "suggested" }
        val sb = StringBuilder("<div class=card><h2>Suggestions</h2>")
        if (sugg.isEmpty()) {
            sb.append("<p class=muted>No suggestions were made.</p></div>")
            return sb.toString()
        }
        sb.append("<table><thead><tr><th>Time</th><th>Suggested</th><th class=n>Score</th><th class=n>Margin</th><th>Section</th><th>What went on screen next</th></tr></thead><tbody>")
        for (m in sugg) {
            val next = songs.firstOrNull { !it.span.start.isBefore(m.time) && Duration.between(m.time, it.span.start).toMinutes() < 10 }
            val verdict = when {
                next == null -> "<span class=muted>nothing within 10 min</span>"
                next.key == m.topKey -> "<span class=ok>✓ ${esc(next.label)}</span> <span class=muted>after ${fmtSec(Duration.between(m.time, next.span.start).seconds.toDouble())}</span>"
                else -> "<span class=warn>✗ ${esc(next.label)}</span>"
            }
            sb.append("<tr><td>${m.time.format(CLOCK)}</td><td>${esc(m.topSong)}</td><td class=n>${"%.2f".format(m.topScore)}</td><td class=n>${"%.2f".format(m.num("margin") ?: 0.0)}</td>")
            sb.append("<td>${m.topSection?.let { sectionName(it) } ?: "<span class=muted>—</span>"}</td><td>$verdict</td></tr>")
        }
        sb.append("</tbody></table></div>")
        return sb.toString()
    }

    private fun musicSummary(evs: List<Ev>, music: List<Span>): String {
        val starts = evs.filter { it.type == "music_started" }
        val stops = evs.filter { it.type == "music_stopped" }
        // A dip: music stopped and started again within the 2 s tolerance.
        val dips = stops.count { stop -> starts.any { it.time.isAfter(stop.time.minusSeconds(1)) && Duration.between(stop.time, it.time).seconds in 0..2 } }
        val breaks = evs.count { it.type == "music_break" }
        val total = music.sumOf { it.seconds }
        return """
            <div class=card><h2>Music detection</h2>
            <div class=stats>
              <div><b>${fmtSec(total.toDouble())}</b><span>music heard</span></div>
              <div><b>${starts.size}</b><span>times music started</span></div>
              <div><b>$dips</b><span>short dips (≤ 2 s)</span></div>
              <div><b>$breaks</b><span>real breaks (matching restarted)</span></div>
            </div></div>""".trimIndent()
    }

    // ---- helpers ----

    private fun overlap(a: Span, b: Span): Long {
        val s = maxOf(a.start, b.start)
        val e = minOf(a.end, b.end)
        return if (e.isAfter(s)) Duration.between(s, e).seconds else 0
    }

    private fun sectionName(s: Int) = if (s == -1) "intro" else "section $s"

    private fun cssClass(decision: String) = when {
        decision == "suggested" -> "suggested"
        decision == "hint" -> "hint"
        decision.startsWith("streak") || decision == "already_suggested" -> "streak"
        decision == "accuracy_hit" -> "hit"
        decision == "accuracy_miss" -> "miss"
        decision == "suppressed" -> "suppressed"
        else -> "low"
    }

    private fun fmtSec(s: Double?): String {
        val v = (s ?: 0.0).toLong()
        return if (v >= 60) "${v / 60}m ${v % 60}s" else "${v}s"
    }

    internal fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("'", "&#39;").replace("\"", "&quot;")

    private fun page(day: String, body: String) = """
        <!doctype html><html lang=en><head><meta charset=utf-8>
        <meta name=viewport content='width=device-width, initial-scale=1'>
        <title>Service Report ${esc(day)}</title>
        <style>
        :root{--bg:#f7f7f5;--card:#fff;--fg:#1d1d1f;--muted:#6e6e73;--line:#e3e3e0;--screen:#5b7cfa;--music:#3fae6a;--ok:#2e8b57;--warn:#c4472d;--sugg:#2e8b57;--streak:#8fd19e;--hit:#3f6fd8;--miss:#d9534f;--supp:#e0a030;--low:#b5b5b5;--thr:#e0a030;--hint:#e8b923;--late:#f08c3a}
        @media (prefers-color-scheme:dark){:root{--bg:#161618;--card:#1f1f22;--fg:#ececf0;--muted:#9a9aa2;--line:#333338;--screen:#7b93ff;--music:#4cc47e;--ok:#5fd08a;--warn:#ff7a5c;--sugg:#5fd08a;--streak:#2f6e42;--hit:#6f95ff;--miss:#ff6b6b;--supp:#f0b44c;--low:#5a5a60;--thr:#f0b44c;--hint:#f5cd4a;--late:#ff9d52}}
        *{box-sizing:border-box}body{margin:0;padding:24px 16px;background:var(--bg);color:var(--fg);font:15px/1.45 -apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif}
        main{max-width:1100px;margin:0 auto}h1{font-size:24px;margin:0 0 4px}h2{font-size:16px;margin:0 0 12px}
        .muted{color:var(--muted)}.ok{color:var(--ok)}.warn{color:var(--warn)}
        .card{background:var(--card);border:1px solid var(--line);border-radius:12px;padding:16px;margin:16px 0;overflow-x:auto}
        table{border-collapse:collapse;width:100%;font-size:14px}th,td{text-align:left;padding:8px 10px;border-bottom:1px solid var(--line);vertical-align:top}
        th{font-weight:600;color:var(--muted);font-size:12px;text-transform:uppercase;letter-spacing:.03em}td.n,th.n{text-align:right;white-space:nowrap}td.nw{white-space:nowrap}
        svg.timeline{width:100%;min-width:700px;height:auto}
        .lane{font-size:12px;fill:var(--muted)}.tick{font-size:11px;fill:var(--muted)}
        .grid{stroke:var(--line);stroke-width:1}.threshold{stroke:var(--thr);stroke-width:1.5;stroke-dasharray:5 4}
        .screen{fill:var(--screen)}.music{fill:var(--music);opacity:.8}.break{stroke:var(--warn);stroke-width:2}
        .onbar{font-size:11px;fill:#fff;pointer-events:none}
        .dot.suggested{fill:var(--sugg);stroke:var(--card);stroke-width:1.5}.dot.hint{fill:var(--hint);stroke:var(--card);stroke-width:1}.late{fill:var(--late);opacity:.55}.warn-mark{fill:var(--warn)}.dot.streak{fill:var(--streak)}.dot.hit{fill:var(--hit)}.dot.miss{fill:var(--miss)}.dot.suppressed{fill:var(--supp)}.dot.low{fill:var(--low)}
        .legend{display:flex;flex-wrap:wrap;gap:6px 16px;font-size:13px;color:var(--muted);margin:-6px 0 0}
        .legend span{display:inline-flex;align-items:center;gap:6px}
        .sw{display:inline-block;width:14px;height:10px;border-radius:2px}.sw.screen{background:var(--screen)}.sw.music{background:var(--music)}.sw.brk{width:3px;background:var(--warn)}.sw.thr{height:0;border-top:2px dashed var(--thr)}
        .dot-sw{display:inline-block;width:10px;height:10px;border-radius:50%}.dot-sw.suggested{background:var(--sugg)}.dot-sw.hint{background:var(--hint)}.sw.late{background:var(--late);opacity:.55}.warn-sw{display:inline-block;width:0;height:0;border-left:6px solid transparent;border-right:6px solid transparent;border-top:9px solid var(--warn)}.dot-sw.streak{background:var(--streak)}.dot-sw.hit{background:var(--hit)}.dot-sw.miss{background:var(--miss)}.dot-sw.suppressed{background:var(--supp)}.dot-sw.low{background:var(--low)}
        .stats{display:grid;grid-template-columns:repeat(auto-fit,minmax(150px,1fr));gap:12px}
        .stats div{display:flex;flex-direction:column}.stats b{font-size:22px}.stats span{color:var(--muted);font-size:13px}
        </style></head><body><main>$body</main></body></html>
    """.trimIndent()
}
