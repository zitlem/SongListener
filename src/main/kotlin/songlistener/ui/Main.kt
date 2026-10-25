package songlistener.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import songlistener.core.Config
import songlistener.core.RecognizerState
import songlistener.core.slideCheck
import songlistener.core.SectionMark
import songlistener.io.AppSettings
import songlistener.io.LearnedSong
import songlistener.io.ListenerEngine
import songlistener.io.ServiceReport
import kotlinx.coroutines.delay
import java.awt.Desktop
import java.io.File
import songlistener.io.WebUi
import java.text.SimpleDateFormat
import java.util.Date

private val dataDir = File(System.getProperty("user.home"), ".songlistener")
private val settingsFile = File(dataDir, "config.json")
/** Built once at start: matching settings changed in the Tuning window apply from the next start. */
private val config = AppSettings.load(settingsFile).applyTo(Config())

/** The review window is open. */
private var reviewOpen by mutableStateOf(false)
/** The tuning window is open. */
private var tuningOpen by mutableStateOf(false)

fun main() {
    // Made before the window reads the settings file, so its Connect save keeps the id.
    val engine = ListenerEngine(dataDir, config, deviceId = AppSettings.deviceId(settingsFile))
    // Read-only browser view of the same three columns, at http://<this machine>:8780
    val web = WebUi(engine, config, AppSettings.load(settingsFile).webPort).also { it.start() }
    Runtime.getRuntime().addShutdownHook(Thread { engine.shutdown(); web.stop() })
    // Once addresses have been saved by Connect, connect at launch so it can be left running (or
    // started at login) and pick up whenever ChurchPresenter and STT come up.
    // Check the recordings in the background, so the Review button can say how many need a look.
    engine.refreshReview()
    if (settingsFile.exists()) {
        val saved = AppSettings.load(settingsFile)
        engine.connect(saved.churchPresenterUrl, saved.apiKey, saved.sttUrl)
    }
    application {
        if (reviewOpen) ReviewWindow(engine, web.reviewUrls().first()) { reviewOpen = false }
        if (tuningOpen) TuningWindow(engine) { tuningOpen = false }
        Window(
            onCloseRequest = { engine.shutdown(); exitApplication() },
            title = "SongListener",
            state = rememberWindowState(width = 1100.dp, height = 760.dp),
        ) {
            // Launched by Gradle, the JVM is not made the active app on macOS, so keystrokes keep
            // going to whatever was frontmost. Ask for the foreground once the window is up. Never
            // call window.requestFocus(): it makes the frame the AWT focus owner, and key events
            // then stop reaching the Compose text fields.
            LaunchedEffect(Unit) {
                runCatching { Desktop.getDesktop().requestForeground(true) }
            }
            MaterialTheme(colorScheme = darkColorScheme()) {
                FocusWedgeFix {
                    Surface(Modifier.fillMaxSize()) { App(engine, web) }
                }
            }
        }
    }
}

@Composable
private fun App(engine: ListenerEngine, web: WebUi) {
    val state by engine.state.collectAsState()
    val library by engine.library.collectAsState()
    val clips by engine.clipStats.collectAsState()
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ConnectionBar(engine)
        WrongSlideBanner(engine, state)
        StatusRow(engine, state, web)
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Panel("Hearing", Modifier.weight(1f)) { Hearing(engine, state) }
            Panel("Learned songs", Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Recordings: ${clips.imported} imported · ${clips.live} live · ${"%.0f".format(clips.bytes / 1_000_000.0)} MB",
                        style = MaterialTheme.typography.bodySmall, color = Color.Gray, modifier = Modifier.weight(1f),
                    )
                    if (library.isNotEmpty() || clips.count > 0) ForgetAll(library.size) { engine.forgetAll() }
                }
                Library(state, library, engine::forget)
            }
        }
    }
}

@Composable
private fun ConnectionBar(engine: ListenerEngine) {
    // Follows changes saved from the web page too.
    val saved by engine.tuning.saved.collectAsState()
    var settings by remember(saved) { mutableStateOf(saved) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(settings.churchPresenterUrl, { settings = settings.copy(churchPresenterUrl = it) },
            label = { Text("ChurchPresenter") }, singleLine = true, modifier = Modifier.weight(1f))
        OutlinedTextField(settings.apiKey, { settings = settings.copy(apiKey = it) },
            label = { Text("API key (optional)") }, singleLine = true, modifier = Modifier.width(180.dp))
        OutlinedTextField(settings.sttUrl, { settings = settings.copy(sttUrl = it) },
            label = { Text("STT server") }, singleLine = true, modifier = Modifier.weight(1f))
        Button(onClick = {
            engine.tuning.saveConnection(settings.churchPresenterUrl, settings.apiKey, settings.sttUrl)
        }) { Text("Connect") }
        TextButton(onClick = {
            engine.logDir.mkdirs()
            runCatching { Desktop.getDesktop().open(engine.logDir) }
        }) { Text("Open logs") }
        TextButton(onClick = {
            Thread {
                runCatching {
                    ServiceReport.writeAll(engine.logDir, engine.reportDir)?.let { Desktop.getDesktop().browse(it.toURI()) }
                }
            }.start()
        }) { Text("Report") }
        TextButton(onClick = { tuningOpen = true }) { Text("Tuning") }
        val reviews by engine.reviews.collectAsState()
        val flagged = reviews.count { (it.check?.severity ?: 0) >= 1 && !it.labels.reviewed }
        val clipped = reviews.count { r -> r.labels.relearnRequestedAt == null && r.check?.flags?.any { it.code == "clipped" } == true }
        Button(
            onClick = { reviewOpen = true },
            colors = if (flagged + clipped > 0) ButtonDefaults.buttonColors(containerColor = Color(0xFF8A6D1F)) else ButtonDefaults.buttonColors(),
        ) {
            Text("Review recordings" + listOfNotNull(
                flagged.takeIf { it > 0 }?.let { "$it flagged" },
                clipped.takeIf { it > 0 }?.let { "$it clipped" },
            ).joinToString(" · ", " (", ")").takeIf { flagged + clipped > 0 }.orEmpty())
        }
    }
}

@Composable
private fun StatusRow(engine: ListenerEngine, state: RecognizerState, web: WebUi) {
    val cpConnected by engine.churchPresenter.connected.collectAsState()
    val cpError by engine.churchPresenter.error.collectAsState()
    val sttConnected by engine.stt.connected.collectAsState()
    val sttError by engine.stt.error.collectAsState()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Dot("ChurchPresenter", cpConnected, cpError)
            WebRow(engine, web)
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                Dot("STT audio", sttConnected, sttError)
                Text("Music", style = MaterialTheme.typography.labelLarge)
                LinearProgressIndicator(
                    progress = { state.musicProb.coerceIn(0f, 1f) },
                    modifier = Modifier.width(160.dp),
                    color = if (state.isMusic) Color(0xFF4CAF50) else Color.Gray,
                )
                Text("%.2f".format(state.musicProb))
                val level by engine.level.collectAsState()
                Text(
                    "Level " + (if (level.peakDb > -96) "%.1f dBFS".format(level.peakDb) else "–") +
                        if (level.clipping) "  ·  CLIPPING (${level.clippedRecently} of last 10 s) — turn the input down" else "",
                    color = when {
                        level.clipping -> Color(0xFFE57373); level.hot -> Color(0xFFFFD54F); else -> Color(0xFF81C784)
                    },
                )
            }
            val live = state.liveSong
            Text(
                if (live == null) "On screen: nothing (listening for songs)"
                else "On screen: ${live.label}" + (live.section?.let { "  ·  section $it" } ?: ""),
                style = MaterialTheme.typography.titleMedium,
            )
            slideCheck(state, config)?.let { c ->
                Text(
                    "Slide check: ${c.text}",
                    style = MaterialTheme.typography.titleSmall,
                    color = when (c.kind) {
                        "ok" -> Color(0xFF81C784); "wrong" -> Color(0xFFE57373); "doubt" -> Color(0xFFFFD54F); else -> Color.Gray
                    },
                )
            }
            if (live != null) {
                val intro = (state.recordingIntroFrames / config.featuresPerSecond).toInt()
                val secs = ((state.recordingFrames - state.recordingIntroFrames) / config.featuresPerSecond).toInt()
                val needed = (config.minTakeFrames / config.featuresPerSecond).toInt()
                Text(
                    "Recording take: ${secs}s of music" + (if (intro > 0) " + ${intro}s intro" else "") +
                        if (secs < needed) " (kept once it reaches ${needed}s)" else " ✓ will be kept",
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                val held = (state.lookbackFrames / config.featuresPerSecond).toInt()
                Text(
                    if (held > 0) "Holding ${held}s of music as the intro of whatever goes live next"
                    else "No music held for an intro",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            val a = state.accuracy
            Text(
                "Query window: ${state.queryFrames}/${config.queryFrames} frames" +
                    if (a.runs > 0) "  ·  Slide checks this session: ${a.top1} of ${a.runs} matched" else "",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** The web page's address (click to open), and its port, which can be changed here. */
@Composable
private fun WebRow(engine: ListenerEngine, web: WebUi) {
    val running by web.running.collectAsState()
    val error by web.error.collectAsState()
    var port by remember { mutableStateOf(web.port.toString()) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Dot("Web page", running, error)
        if (running) {
            val url = web.urls().first()
            Text(
                url, color = Color(0xFF9ECBFF), style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.clickable { runCatching { Desktop.getDesktop().browse(java.net.URI(url)) } },
            )
        }
        Text("Port", style = MaterialTheme.typography.labelLarge)
        OutlinedTextField(
            port, { port = it.filter(Char::isDigit).take(5) }, singleLine = true,
            modifier = Modifier.width(96.dp), textStyle = MaterialTheme.typography.bodyMedium,
        )
        val wanted = port.toIntOrNull()
        val valid = wanted != null && wanted in 1024..65535
        if (valid && (wanted != web.port || !running)) {
            TextButton(onClick = {
                Thread { web.restart(wanted!!) }.start()
                engine.tuning.saveWebPort(wanted!!)
            }) { Text(if (wanted == web.port) "Retry" else "Use port $wanted") }
        } else if (!valid) {
            Text("1024–65535", color = Color(0xFFE57373), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun Dot(label: String, on: Boolean, error: String?) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(12.dp).clip(CircleShape).background(if (on) Color(0xFF4CAF50) else Color(0xFFE53935)))
        Text(label)
        if (!on && error != null) Text(error.take(120), color = Color(0xFFE57373), style = MaterialTheme.typography.bodySmall, maxLines = 1)
    }
}

@Composable
private fun Panel(title: String, modifier: Modifier, content: @Composable () -> Unit) {
    Card(modifier.fillMaxHeight()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            HorizontalDivider()
            content()
        }
    }
}

/**
 * Red banner while the music clearly doesn't match the song on screen. Reports only: switching
 * takes a click, and ChurchPresenter asks its operator unless SongListener is already allowed.
 */
@Composable
private fun WrongSlideBanner(engine: ListenerEngine, state: RecognizerState) {
    val w = state.wrongSlide ?: return
    // Dismissed here or on the web page: hidden in both.
    val dismissedAt by engine.dismissedWrongSlide.collectAsState()
    if (dismissedAt == w.at) return
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF7F1D1D)),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text("Wrong slide?", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color.White)
                Text(
                    "Music sounds like ${w.heard.songLabel} (%.2f) — screen shows ${w.shown.label} (%.2f)".format(w.heard.score, w.shownScore),
                    color = Color.White,
                )
            }
            ShowButtons(engine, w.heard.songKey, label = "Show ${w.heard.songLabel.substringBefore(' ')}")
            TextButton(onClick = { engine.dismissed(w) }) { Text("Dismiss", color = Color.White) }
        }
    }
}

/** What the listeners hear: an early hint, a confirmed suggestion, and the ranking — each with "Show it". */
@Composable
private fun Hearing(engine: ListenerEngine, state: RecognizerState) {
    val liveKey = state.liveSong?.key
    if (state.cantCheckLive && state.liveSong != null) {
        Text("Can't check the slide: ${state.liveSong!!.label} hasn't been learned yet.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
    }
    state.lastSuggestion?.let { s ->
        Text("Suggested", style = MaterialTheme.typography.labelLarge)
        Text(s.songLabel, style = MaterialTheme.typography.headlineSmall, color = Color(0xFF81C784))
        s.section?.let { Text("Band is at: ${sectionName(it)}", style = MaterialTheme.typography.titleMedium) }
        Text("score %.2f · %s".format(s.score, time(s.at)), style = MaterialTheme.typography.bodySmall)
        if (s.songKey != liveKey) ShowButtons(engine, s.songKey)
        Spacer(Modifier.height(8.dp))
    }
    state.hint?.takeIf { it.songKey != state.lastSuggestion?.songKey }?.let { h ->
        Text("Sounds like… (early guess)", style = MaterialTheme.typography.labelLarge)
        Text(h.songLabel, style = MaterialTheme.typography.titleLarge, color = Color(0xFFFFD54F))
        Text("score %.2f · %s".format(h.score, time(h.at)), style = MaterialTheme.typography.bodySmall)
        if (h.songKey != liveKey) ShowButtons(engine, h.songKey)
        Spacer(Modifier.height(8.dp))
    }
    val match = state.lastMatch
    if (match == null || match.ranked.isEmpty()) {
        Text("Listening — needs ${config.queryFrames / config.featuresPerSecond.toInt()}s of continuous music and at least one learned song.")
        return
    }
    Text("Top candidates (margin %.2f)".format(match.margin), style = MaterialTheme.typography.labelLarge)
    match.ranked.take(3).forEach { s ->
        Column {
            Text(s.songLabel + if (s.songKey == liveKey) "  · on screen" else "", maxLines = 1)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LinearProgressIndicator(
                    progress = { s.score.toFloat().coerceIn(0f, 1f) },
                    modifier = Modifier.weight(1f),
                    color = if (s.score >= config.steadyScore) Color(0xFF4CAF50) else Color(0xFFFFB300),
                )
                Text("%.2f".format(s.score), style = MaterialTheme.typography.bodySmall)
            }
            Text(
                "key %+d · tempo ×%.2f · at %ds".format(
                    signedRotation(s.rotation), s.stretch, (s.offsetFrame / config.featuresPerSecond).toInt(),
                ) + (s.section?.let { " · ${sectionName(it)}" } ?: ""),
                style = MaterialTheme.typography.bodySmall, color = Color.Gray,
            )
        }
    }
}

/**
 * "Show it" for one song: one button when the songbook is known, one per edition when it isn't,
 * then what ChurchPresenter answered.
 */
@Composable
private fun ShowButtons(engine: ListenerEngine, songKey: String, label: String = "Show it") {
    LaunchedEffect(songKey) { engine.prepareShow(songKey) }
    val editions by engine.editions.collectAsState()
    val statuses by engine.showStatus.collectAsState()
    val status = statuses[songKey]
    val books = editions[songKey]
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        when {
            status == "waiting" -> Text("Waiting for approval in ChurchPresenter…", style = MaterialTheme.typography.bodySmall)
            books == null -> Button(onClick = {}, enabled = false) { Text(label) }
            books.size == 1 -> Button(onClick = { engine.show(songKey, books.single()) }) { Text(label) }
            else -> books.forEach { b ->
                Button(onClick = { engine.show(songKey, b) }) { Text("$label (${b.substringAfterLast('/').takeLast(18)})") }
            }
        }
        when {
            status == "shown" -> Text("✓ Shown", color = Color(0xFF81C784))
            status == "declined" -> Text("Declined in ChurchPresenter", color = Color(0xFFE57373))
            status?.startsWith("failed") == true -> Text(status.removePrefix("failed: "), color = Color(0xFFE57373), style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Moves every take and recording aside to start fresh; two clicks, like Forget. */
@Composable
private fun ForgetAll(songs: Int, onConfirm: () -> Unit) {
    var confirming by remember { mutableStateOf(false) }
    if (confirming) {
        LaunchedEffect(Unit) { delay(4000); confirming = false }
        TextButton(onClick = { confirming = false; onConfirm() }) {
            Text("Confirm: forget all $songs songs", color = Color(0xFFE57373))
        }
    } else {
        TextButton(onClick = { confirming = true }) { Text("Forget all…") }
    }
}

@Composable
private fun Library(state: RecognizerState, library: List<LearnedSong>, onForget: (String) -> Unit) {
    if (library.isEmpty()) {
        Text("Nothing learned yet. Put a song on screen in ChurchPresenter while the band plays for 30s or more.")
        return
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items(library, key = { it.key }) { song ->
            val isLive = state.liveSong?.key == song.key
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(song.label, fontWeight = if (isLive) FontWeight.Bold else FontWeight.Normal, maxLines = 1)
                    Text(
                        "${song.takes} take(s) · ${song.recordings - song.liveRecordings} imported · ${song.liveRecordings} live · ${song.seconds}s · ${time(song.lastRecorded)}",
                        style = MaterialTheme.typography.bodySmall, color = Color.Gray,
                    )
                }
                // Forget deletes the song's takes and recordings for good, so it takes two clicks.
                var confirming by remember(song.key) { mutableStateOf(false) }
                if (confirming) {
                    LaunchedEffect(Unit) { delay(4000); confirming = false }
                    TextButton(onClick = { confirming = false; onForget(song.key) }) {
                        Text("Confirm delete", color = Color(0xFFE57373))
                    }
                } else {
                    TextButton(onClick = { confirming = true }) { Text("Forget") }
                }
            }
        }
    }
}

private fun signedRotation(r: Int) = if (r > 6) r - 12 else r
private fun sectionName(section: Int) = if (section == SectionMark.INTRO) "intro" else "section $section"
private fun pct(n: Int, d: Int) = if (d == 0) "–" else "${n * 100 / d}%"
private fun time(ms: Long) = SimpleDateFormat("MMM d HH:mm").format(Date(ms))
