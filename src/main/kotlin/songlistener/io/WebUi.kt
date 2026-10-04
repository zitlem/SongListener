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
 * Read-only web page with the same three columns as the desktop window: Hearing, Accuracy and
 * Learned songs. Served from [port] on every interface so a phone or the sound desk can watch.
 */
class WebUi(private val engine: ListenerEngine, private val config: Config, private val port: Int = 8780) {
    private var server: HttpServer? = null

    fun start() {
        val s = runCatching { HttpServer.create(InetSocketAddress(port), 0) }.getOrNull() ?: return
        s.createContext("/api/state") { ex ->
            reply(ex, 200, "application/json; charset=utf-8", snapshot().toString())
        }
        s.createContext("/") { ex ->
            if (ex.requestURI.path != "/") reply(ex, 404, "text/plain", "not found")
            else reply(ex, 200, "text/html; charset=utf-8", PAGE)
        }
        s.executor = java.util.concurrent.Executors.newFixedThreadPool(2) { r -> Thread(r, "webui").apply { isDaemon = true } }
        s.start()
        server = s
    }

    fun stop() { server?.stop(0) }

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
            put("live", st.liveSong?.let { it.label + (it.section?.let { s -> " · section $s" } ?: "") }?.let(::JsonPrimitive) ?: JsonNull)
            put("cantCheckLive", st.cantCheckLive)
            put("steadyScore", config.steadyScore)
            put("neededSeconds", config.queryFrames / config.featuresPerSecond)
            put("wrongSlide", st.wrongSlide?.let { w ->
                buildJsonObject { put("heard", w.heard.songLabel); put("heardScore", w.heard.score); put("shown", w.shown.label); put("shownScore", w.shownScore) }
            } ?: JsonNull)
            put("suggested", suggestion(st.lastSuggestion))
            put("hint", suggestion(st.hint?.takeIf { it.songKey != st.lastSuggestion?.songKey }))
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
                        put("label", s.label); put("takes", s.takes); put("seconds", s.seconds)
                        put("imported", s.recordings - s.liveRecordings); put("live", s.liveRecordings)
                        put("lastRecorded", s.lastRecorded); put("onScreen", s.key == liveKey)
                    })
                }
            })
        }
    }

    private companion object {
        val PAGE = """
<!doctype html><html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>SongListener</title>
<style>
:root{color-scheme:dark;--bg:#121212;--card:#1e1e1e;--fg:#e6e6e6;--mut:#8a8a8a;--ok:#81c784;--warn:#ffd54f;--bad:#e57373}
*{box-sizing:border-box}body{margin:0;padding:16px;background:var(--bg);color:var(--fg);font:15px system-ui,sans-serif}
.bar{display:flex;flex-wrap:wrap;gap:16px;align-items:center;margin-bottom:12px}
.dot{display:inline-block;width:12px;height:12px;border-radius:50%;margin-right:6px;background:var(--bad)}.dot.on{background:#4caf50}
meter,progress{width:160px;height:10px;vertical-align:middle}
.ban{background:#7f1d1d;border-radius:8px;padding:12px;margin-bottom:12px;display:none}
.cols{display:grid;grid-template-columns:repeat(3,1fr);gap:12px;align-items:start}
@media(max-width:800px){.cols{grid-template-columns:1fr}}
.card{background:var(--card);border-radius:10px;padding:12px}
h2{margin:0 0 8px;font-size:16px;border-bottom:1px solid #333;padding-bottom:8px}
.mut{color:var(--mut);font-size:13px}.big{font-size:22px}.ok{color:var(--ok)}.warn{color:var(--warn)}.bad{color:var(--bad)}
.row{margin:6px 0}.song{padding:6px 0;border-bottom:1px solid #2a2a2a}.on-screen{font-weight:700}
progress{width:100%}
</style></head><body>
<div class="bar"><span><i class="dot" id="cp"></i>ChurchPresenter</span><span><i class="dot" id="stt"></i>STT audio</span>
<span>Music <progress id="mp" max="1" value="0" style="width:160px"></progress> <span id="mpv"></span></span></div>
<div class="bar" id="live"></div>
<div class="ban" id="wrong"></div>
<div class="cols">
<div class="card"><h2>Hearing</h2><div id="hearing"></div></div>
<div class="card"><h2>Accuracy (while a learned song is live)</h2><div id="acc"></div></div>
<div class="card"><h2>Learned songs</h2><div id="lib"></div></div>
</div>
<script>
const e=s=>String(s??"").replace(/[&<>"]/g,c=>({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;"}[c]));
const pct=(n,d)=>d?Math.round(n*100/d)+"%":"–";
const t=ms=>new Date(ms).toLocaleString([], {month:"short",day:"numeric",hour:"2-digit",minute:"2-digit"});
function render(s){
 cp.className="dot"+(s.cpConnected?" on":"");stt.className="dot"+(s.sttConnected?" on":"");
 mp.value=s.musicProb;mpv.textContent=s.musicProb.toFixed(2);
 live.innerHTML="<b>"+(s.live?"On screen: "+e(s.live):"On screen: nothing (listening for songs)")+"</b>";
 wrong.style.display=s.wrongSlide?"block":"none";
 if(s.wrongSlide)wrong.innerHTML="<b>Wrong slide?</b> Music sounds like "+e(s.wrongSlide.heard)+" ("+s.wrongSlide.heardScore.toFixed(2)+") — screen shows "+e(s.wrongSlide.shown)+" ("+s.wrongSlide.shownScore.toFixed(2)+")";
 let h="";
 if(s.cantCheckLive&&s.live)h+='<div class="mut">Can\'t check the slide: this song hasn\'t been learned yet.</div>';
 if(s.suggested)h+='<div class="mut">Suggested</div><div class="big ok">'+e(s.suggested.label)+'</div>'+(s.suggested.section?'<div>Band is at: '+e(s.suggested.section)+'</div>':'')+'<div class="mut">score '+s.suggested.score.toFixed(2)+' · '+t(s.suggested.at)+'</div>';
 if(s.hint)h+='<div class="mut" style="margin-top:8px">Sounds like… (early guess)</div><div class="big warn">'+e(s.hint.label)+'</div><div class="mut">score '+s.hint.score.toFixed(2)+' · '+t(s.hint.at)+'</div>';
 const c=s.candidates;
 if(!c||!c.ranked.length)h+='<div class="row">Listening — needs '+Math.round(s.neededSeconds)+'s of continuous music and at least one learned song.</div>';
 else{h+='<div class="mut" style="margin-top:8px">Top candidates (margin '+c.margin.toFixed(2)+')</div>';
  c.ranked.forEach(r=>{h+='<div class="row"><div>'+e(r.label)+(r.onScreen?" · on screen":"")+'</div><progress max="1" value="'+Math.max(0,r.score)+'"></progress> '+r.score.toFixed(2)+'<div class="mut">key '+(r.key>0?"+":"")+r.key+' · tempo ×'+r.tempo.toFixed(2)+' · at '+r.at+'s'+(r.section?' · '+e(r.section):'')+'</div></div>'})}
 hearing.innerHTML=h;
 const a=s.accuracy;
 acc.innerHTML=!a.runs?"Runs once a song that already has a learned take is on screen and music is playing.":
  '<div>Match runs: '+a.runs+'</div><div>Top-1 correct: '+a.top1+' ('+pct(a.top1,a.runs)+')</div><div>Confident: '+a.confident+', of which correct: '+a.confidentHits+' ('+pct(a.confidentHits,a.confident)+')</div>'+
  (a.last?'<div class="mut" style="margin-top:8px">Last prediction</div><div class="'+(a.last.hit?"ok":"bad")+'">'+e(a.last.label)+' '+a.last.score.toFixed(2)+' '+(a.last.hit?"✓":"✗")+'</div>':"");
 const r=s.recordings;
 lib.innerHTML='<div class="mut">Recordings: '+r.imported+' imported · '+r.live+' live · '+Math.round(r.bytes/1e6)+' MB</div>'+
  (s.library.length?s.library.map(x=>'<div class="song"><div class="'+(x.onScreen?"on-screen":"")+'">'+e(x.label)+'</div><div class="mut">'+x.takes+' take(s) · '+x.imported+' imported · '+x.live+' live · '+x.seconds+'s · '+t(x.lastRecorded)+'</div></div>').join(""):"<div>Nothing learned yet.</div>");
}
async function tick(){try{render(await (await fetch("/api/state")).json())}catch(_){cp.className=stt.className="dot"}}
tick();setInterval(tick,1000);
</script></body></html>
""".trimIndent()
    }
}
