package songlistener.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import songlistener.core.Config
import songlistener.io.AppSettings
import songlistener.io.ClipScan
import songlistener.io.ListenerEngine
import songlistener.io.Tuning
import java.io.File
import kotlin.math.roundToInt

private val muted = Color.Gray
private val good = Color(0xFF81C784)
private val warn = Color(0xFFFFD54F)
private val bad = Color(0xFFE57373)

/** The matching settings as edited in the window (saved to the settings file, used from the next start). */
private data class Matching(val keepTakes: String, val maxTakes: Int, val agreement: Double, val intro: String, val confirmed: Boolean) {
    fun applyTo(c: Config) = c.copy(keepTakes = keepTakes, maxTakesPerSong = maxTakes, takeAgreement = agreement, introMode = intro, confirmedParts = confirmed)
    val summary: String get() = "$keepTakes, $maxTakes takes, agreement ${if (agreement == 0.0) "off" else "%.1f".format(agreement)}, " +
        "intro $intro" + if (confirmed) ", confirmed parts" else ""
    companion object {
        fun of(c: Config) = Matching(c.keepTakes, c.maxTakesPerSong, c.takeAgreement, c.introMode, c.confirmedParts)
        fun of(s: AppSettings) = Matching(s.keepTakes, s.maxTakesPerSong, s.takeAgreement, s.introMode, s.confirmedParts)
    }
}

/** Matching settings, the recognition test that picks them, and the clipping check. */
@Composable
fun TuningWindow(engine: ListenerEngine, onClose: () -> Unit) {
    val running = engine.tuning.running
    Window(onCloseRequest = onClose, title = "SongListener — Tuning", state = rememberWindowState(width = 980.dp, height = 760.dp)) {
        MaterialTheme(colorScheme = darkColorScheme()) {
            Surface(Modifier.fillMaxSize()) {
                var tab by remember { mutableStateOf("guide") }
                // Saved from here or from the web page: both show the same.
                val savedSettings by engine.tuning.saved.collectAsState()
                val saved = Matching.of(savedSettings)
                var edit by remember(saved) { mutableStateOf(saved) }
                fun save(m: Matching) {
                    engine.tuning.saveMatching(m.keepTakes, m.maxTakes, m.agreement, m.intro, m.confirmed, "app")
                }
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("guide" to "Guide", "matching" to "Matching settings", "test" to "Test recognition", "clipping" to "Clipping").forEach { (k, label) ->
                            FilterChip(selected = tab == k, onClick = { tab = k }, label = { Text(label) })
                        }
                    }
                    HorizontalDivider()
                    when (tab) {
                        "guide" -> GuideTab(engine, saved, Matching.of(running)) { tab = it }
                        "matching" -> MatchingTab(edit, saved, Matching.of(running), onEdit = { edit = it }, onSave = { save(edit) })
                        "test" -> TestTab(engine, running, saved, onUse = { save(it); tab = "matching" })
                        else -> ClippingTab(engine)
                    }
                }
            }
        }
    }
}

@Composable
private fun MatchingTab(edit: Matching, saved: Matching, running: Matching, onEdit: (Matching) -> Unit, onSave: () -> Unit) {
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Help(
            "How the listener recognises songs. Each time a song is played, what was heard is kept as a \"take\"; " +
                "music is matched against every song's takes. These settings change which takes are kept and how they are matched. " +
                "Change them only after \"Test recognition\" shows a setting does better on your recordings, then Save and restart SongListener.",
        )
        Column {
            Text("Which takes a song keeps", fontWeight = FontWeight.SemiBold)
            Help(
                "When a song has more takes than the limit below, one has to go. Newest drops the oldest. " +
                    "Best drops a clipped take first (input too hot), then one that sounds unlike the song's other takes " +
                    "(often a wrong slide), so a good old take isn't lost just for being old. Try Best once songs have several clean takes.",
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = edit.keepTakes != "best", onClick = { onEdit(edit.copy(keepTakes = "newest")) }, label = { Text("Newest") })
                FilterChip(selected = edit.keepTakes == "best", onClick = { onEdit(edit.copy(keepTakes = "best")) },
                    label = { Text("Best — unclipped first, then the ones that agree with each other") })
            }
        }
        Column {
            Text("Intro — music heard before the slide went up", fontWeight = FontWeight.SemiBold)
            Help(
                "Music playing before a song's slide goes up is learned as its intro, so the song can be recognised early. " +
                    "But it isn't always the intro: another performance before the song gets held too. " +
                    "\"Only what sounds like the song\" (recommended) keeps the part that matches the song, or that has come before it in " +
                    "another performance. \"All\" learns everything held; \"None\" never learns an intro. " +
                    "Check what was learned with \"Play intro\" in Review recordings.",
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("match" to "Only what sounds like the song", "keep" to "All held music", "drop" to "None").forEach { (k, label) ->
                    FilterChip(selected = edit.intro == k, onClick = { onEdit(edit.copy(intro = k)) }, label = { Text(label) })
                }
            }
        }
        Column {
            Text("Takes per song", fontWeight = FontWeight.SemiBold)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onEdit(edit.copy(maxTakes = (edit.maxTakes - 1).coerceAtLeast(1))) }) { Text("−") }
                Text("${edit.maxTakes}", style = MaterialTheme.typography.titleMedium)
                OutlinedButton(onClick = { onEdit(edit.copy(maxTakes = (edit.maxTakes + 1).coerceAtMost(20))) }) { Text("+") }
                Help("More takes cover more ways a song gets played (tempo, key, who leads); matching takes a little longer. 5 is a good start.")
            }
        }
        Column {
            Text("Search only the parts performances share", fontWeight = FontWeight.SemiBold)
            Help(
                "For a song recorded more than once: compare its takes and search only the stretches that appear in at least two of " +
                    "them, leaving out what happened only once (talking, applause, a solo, other music). Mainly cuts wrong suggestions " +
                    "and false wrong-slide warnings. Songs with one recording aren't affected. Turn on once Test recognition shows it helps — " +
                    "that needs songs recorded three or more times.",
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !edit.confirmed, onClick = { onEdit(edit.copy(confirmed = false)) }, label = { Text("Off — search whole takes") })
                FilterChip(selected = edit.confirmed, onClick = { onEdit(edit.copy(confirmed = true)) }, label = { Text("On — shared parts only") })
            }
        }
        Column {
            Text("Agreement between takes: " + if (edit.agreement == 0.0) "off" else "%.1f".format(edit.agreement), fontWeight = FontWeight.SemiBold)
            Help(
                "Off scores a song by its single best-matching take. At 0.5 it uses the average of its best two, " +
                    "so a song wins because several of its performances match, not one by luck. Songs with one take are unaffected. " +
                    "Try 0.5 in Test recognition once many songs have two or more takes.",
            )
            Slider(
                value = edit.agreement.toFloat(), onValueChange = { onEdit(edit.copy(agreement = (it * 10).roundToInt() / 10.0)) },
                valueRange = 0f..1f, steps = 9, modifier = Modifier.width(420.dp),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onSave, enabled = edit != saved) { Text("Save") }
            if (edit != saved) TextButton(onClick = { onEdit(saved) }) { Text("Undo changes") }
            Text(
                when {
                    edit != saved -> "Not saved yet."
                    saved != running -> "Saved. Restart SongListener to use these settings."
                    else -> "In use."
                },
                color = if (edit == saved && saved != running) warn else muted,
            )
        }
        Text("Use \"Test recognition\" to see which settings recognise your recordings best before changing them.",
            color = muted, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun TestTab(engine: ListenerEngine, running: Config, saved: Matching, onUse: (Matching) -> Unit) {
    // One test at a time, shared with the web page: started from either, shown in both.
    val test by engine.tuning.test.collectAsState()
    var cleanOnly by remember(test.cleanOnly) { mutableStateOf(test.cleanOnly) }
    val busy = test.running
    val progress = test.progress
    val status = test.status
    val results = test.results

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "What it does: plays every saved recording back as if live, against what was learned from the other recordings, " +
                "once for each setting below, and counts how often the right song was found. " +
                "How to use: run it when no service is on (a few minutes, heavy on the CPU). Keep \"Clean recordings only\" ticked " +
                "so clipped audio doesn't skew it. A song counts only if it has been recorded at least twice, so results get " +
                "trustworthy as songs repeat — aim for 10+ tested. Pick the green row and press \"Use these\", then restart.",
            color = muted, style = MaterialTheme.typography.bodySmall,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Checkbox(cleanOnly, { cleanOnly = it }, enabled = !busy)
            Text("Clean recordings only (leave out clipped ones)")
            if (!busy) Button(onClick = { engine.tuning.startTest(cleanOnly, "app") }) { Text("Run test") }
            else OutlinedButton(onClick = { engine.tuning.stopTest() }) { Text("Stop") }
        }
        if (busy) LinearProgressIndicator(progress = { progress.toFloat() }, modifier = Modifier.fillMaxWidth())
        status?.let { Text(it, color = muted) }
        if (results.isNotEmpty()) {
            val testable = results.first().summary.testable
            if (testable < 10) Text(
                "Only $testable recording(s) could be tested (a song needs at least two recordings). " +
                    "Too few to trust a difference — run it again after more services.",
                color = warn,
            )
            val best = engine.tuning.best(results)
            ResultRow(listOf("Settings", "Recognised", "Wrong", "Ranked first", "Wrong slides caught", "False alarms", ""), header = true)
            HorizontalDivider()
            for (r in results) {
                val s = r.summary
                val m = Matching.of(r.variant.config)
                val tied = best != null && s.rightAtAll == best.summary.rightAtAll && s.wrongSuggestions == best.summary.wrongSuggestions
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ResultRow(listOf(
                        r.variant.name + "\n" + m.summary,
                        "${s.rightAtAll} / ${s.testable}",
                        "${s.wrongSuggestions}",
                        s.meanTop1?.let { "%.0f%%".format(it * 100) } ?: "–",
                        "${s.wrongSlideCaught} / ${s.wrongSlideTests}",
                        "${s.falseAlarms}",
                    ), color = if (tied) good else Color.Unspecified, modifier = Modifier.weight(1f))
                    if (m == saved) Text("in settings", color = muted, modifier = Modifier.width(110.dp))
                    else TextButton(onClick = { onUse(m) }, modifier = Modifier.width(110.dp)) { Text("Use these") }
                }
            }
            Text("Green = most recognised with the fewest wrong suggestions. \"Use these\" saves them; restart to apply.",
                color = muted, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** A description under a heading: what it is and how to use it. */
@Composable
private fun Help(text: String) = Text(text, color = muted, style = MaterialTheme.typography.bodySmall)

/** What to do now, worked out from this listener's own state, and how the parts fit together. */
@Composable
private fun GuideTab(engine: ListenerEngine, saved: Matching, running: Matching, go: (String) -> Unit) {
    val level by engine.level.collectAsState()
    val stt by engine.stt.connected.collectAsState()
    val reviews by engine.reviews.collectAsState()
    val stats by engine.clipStats.collectAsState()
    val clipped = reviews.count { r -> r.labels.relearnRequestedAt == null && r.check?.flags?.any { it.code == "clipped" } == true }
    val flagged = reviews.count { (it.check?.severity ?: 0) >= 1 && !it.labels.reviewed }
    val twice = stats.perSong.count { it.value >= 2 }
    val thrice = stats.perSong.count { it.value >= 3 }

    @Composable
    fun Step(state: String, title: String, text: String, action: Pair<String, () -> Unit>? = null) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(when (state) { "ok" -> "✓"; "todo" -> "●"; else -> "!" }, color = when (state) { "ok" -> good; "todo" -> warn; else -> bad },
                style = MaterialTheme.typography.titleMedium, modifier = Modifier.width(18.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Help(text)
            }
            action?.let { (label, onClick) -> OutlinedButton(onClick = onClick) { Text(label) } }
        }
    }

    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("What to do now", style = MaterialTheme.typography.titleMedium)
        when {
            !stt -> Step("todo", "Connect to the STT server", "No audio is coming in, so the input level can't be checked. Connect in the main window.")
            level.clipping -> Step("bad", "Turn the input down — it is clipping now",
                "The audio is too hot: loud parts are cut off flat, which distorts what the listener learns. Lower the gain on the " +
                    "mixer or the STT server until the main window's Level stays out of red during the loudest song.")
            level.hot -> Step("todo", "Input is close to clipping",
                "Peaks are within 1 dB of the maximum. Lower the gain a little: aim for peaks around −6 to −3 dBFS.")
            else -> Step("ok", "Input level is fine", "No clipping right now. Watch the Level in the main window during the loudest song of a service.")
        }
        if (clipped > 0) Step("todo", "$clipped recording(s) were clipped",
            "Recorded while the input was too hot (from 13 Sep). They still help recognition, so keep them for now. " +
                "Once the gain is fixed and a song has been recorded cleanly, relearn its clipped recordings: Review recordings → Clipped → Relearn. " +
                "Check day by day in the Clipping tab.", "Clipping" to { go("clipping") })
        else Step("ok", "No clipped recordings waiting", "Nothing recorded too hot is being learned from.")
        if (flagged > 0) Step("todo", "$flagged recording(s) need a listen",
            "The listener thinks these may have had the wrong song on screen. Listen and press Looks right, or correct the song, " +
                "in Review recordings (button in the main window).")
        Step(if (thrice >= 5) "ok" else "todo", "Songs recorded more than once: $twice (three or more times: $thrice)",
            "Recognition — and the test that tunes it — improves as songs are played again: every play adds a take. " +
                if (thrice >= 5) "There are enough repeats to run Test recognition." else "Run Test recognition after a few more services; until then its numbers are too small to trust.",
            "Test recognition" to { go("test") })
        if (saved != running) Step("bad", "Restart SongListener", "Matching settings were saved but take effect only after a restart.")

        HorizontalDivider()
        Text("How it works", style = MaterialTheme.typography.titleMedium)
        Help(
            "Listening: audio comes from the STT server. When music plays while a song's slide is on screen, the listener learns that " +
                "song from it (a \"take\", kept once it has 30 s of music) and saves a recording. Music before the slide goes up is kept as the intro.",
        )
        Help(
            "Recognising: music is compared with every song's takes. With nothing on screen it suggests the song (\"Sounds like…\"); " +
                "with a slide up it checks it is the right one and warns if another song fits better (\"Wrong slide?\").",
        )
        Help(
            "Review recordings: listen to recordings and correct any learned under the wrong song, delete bad ones, and relearn clipped ones. " +
                "Corrections change what is learned straight away.",
        )
        Help(
            "Tuning: Matching settings change how songs are matched; Test recognition measures the settings on your own recordings so you can " +
                "pick the best; Clipping shows, day by day, whether the input was too hot. Measurements are remembered, so checks are quick.",
        )
    }
}

@Composable
private fun ResultRow(cells: List<String>, header: Boolean = false, color: Color = Color.Unspecified, modifier: Modifier = Modifier) {
    val widths = listOf(300, 100, 70, 100, 140, 100)
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        cells.take(widths.size).forEachIndexed { i, c ->
            Text(c, Modifier.width(widths[i].dp).padding(vertical = 4.dp), color = if (header) muted else color,
                fontWeight = if (header || i == 0) FontWeight.SemiBold else FontWeight.Normal,
                style = if (i == 0 && !header) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun ClippingTab(engine: ListenerEngine) {
    val clipping by engine.tuning.clipping.collectAsState()
    val rows = clipping.rows
    val busy = clipping.busy
    fun check() = engine.tuning.checkClipping()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = ::check, enabled = !busy) { Text(if (rows == null) "Check recordings" else "Check again") }
            Text(
                if (busy) "Checking… (each recording is measured once, then remembered)"
                else "Shows, day by day, whether the input was too hot. A good level peaks around −6 to −3 dBFS and is never clipped.",
                color = muted, style = MaterialTheme.typography.bodySmall,
            )
        }
        val r = rows ?: return@Column
        if (r.isEmpty()) { Text("No recordings yet."); return@Column }
        Text("Total ${r.size}: clean ${r.count { it.level.severity == 0 }} · light ${r.count { it.level.severity == 1 }} · heavy ${r.count { it.level.severity == 2 }}",
            fontWeight = FontWeight.SemiBold)
        val mono = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
        Text("Day          Recs  Clean  Light  Heavy  Loudest peak  Median loudness", style = mono, color = muted)
        LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(ClipScan.days(r).reversed()) { d ->
                Text(
                    "%-12s %4d  %5d  %5d  %5d  %7.1f dBFS  %9.1f dBFS".format(d.day, d.recordings, d.clean, d.light, d.heavy, d.loudestPeakDb, d.medianRmsDb),
                    style = mono, color = when { d.heavy > 0 -> bad; d.light > 0 -> warn; else -> good },
                )
            }
            item { HorizontalDivider(Modifier.padding(vertical = 8.dp)); Text("Worst first", color = muted) }
            items(ClipScan.worstFirst(r)) { row ->
                val l = row.level
                Text(
                    "%-28s %-6s peak %6.1f  loudness %6.1f  clipped %3.0f%%  worst %5s  %s".format(
                        row.name.take(28), row.labels.origin ?: "-", l.peakDb, l.rmsDb, l.clippedFraction * 100,
                        ClipScan.mmss(l.worstSecond), ClipScan.severityText(l.severity)),
                    style = mono, color = when (l.severity) { 2 -> bad; 1 -> warn; else -> Color.Unspecified },
                )
            }
        }
    }
}
