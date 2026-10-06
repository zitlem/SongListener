package songlistener.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.delay
import songlistener.io.ListenerEngine
import songlistener.io.ReviewItem
import java.text.SimpleDateFormat
import java.util.Date

/** Listen to each recording and correct it if the wrong song was on screen. */
@Composable
fun ReviewWindow(engine: ListenerEngine, webUrl: String?, onClose: () -> Unit) {
    Window(onCloseRequest = { WavPlayer.stop(); onClose() }, title = "SongListener — Review recordings",
        state = rememberWindowState(width = 900.dp, height = 760.dp)) {
        MaterialTheme(colorScheme = darkColorScheme()) {
            Surface(Modifier.fillMaxSize()) {
                LaunchedEffect(Unit) { engine.refreshReview() }
                val items by engine.reviews.collectAsState()
                val checking by engine.reviewChecking.collectAsState()
                var flaggedOnly by remember { mutableStateOf(true) }
                val shown = items.filter { !flaggedOnly || (it.check?.severity ?: 0) >= 1 && !it.labels.reviewed }
                    .sortedWith(compareByDescending<ReviewItem> { if (it.labels.reviewed) -1 else it.check?.severity ?: 0 }.thenByDescending { it.labels.startedAt })
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Recordings", style = MaterialTheme.typography.titleLarge)
                        Checkbox(flaggedOnly, { flaggedOnly = it })
                        Text("Only ones that need a listen")
                        Text(
                            (if (checking) "Checking… " else "") +
                                "${items.count { (it.check?.severity ?: 0) >= 1 && !it.labels.reviewed }} flagged of ${items.size}",
                            color = Color.Gray,
                        )
                    }
                    webUrl?.let { Text("Also in a browser: $it", style = MaterialTheme.typography.bodySmall, color = Color.Gray) }
                    HorizontalDivider()
                    if (shown.isEmpty()) Text(if (checking) "Checking recordings…" else "Nothing needs a listen.")
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(shown, key = { it.name }) { ReviewRow(engine, it) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReviewRow(engine: ListenerEngine, item: ReviewItem) {
    val l = item.labels
    val playing by WavPlayer.playing.collectAsState()
    val isPlaying = playing == item.wav
    var picking by remember(item.name) { mutableStateOf(false) }
    var confirmDelete by remember(item.name) { mutableStateOf(false) }
    val sev = item.check?.severity ?: 0
    val tint = when { l.reviewed -> Color(0xFF1E3A2A); sev >= 2 -> Color(0xFF4A1F1F); sev == 1 -> Color(0xFF3F3517); else -> Color(0xFF26262A) }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = tint)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(l.songLabel, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "${SimpleDateFormat("EEE d MMM HH:mm").format(Date(l.startedAt))} · ${l.origin ?: "import"} · " +
                            "%.0f s · slide up at %.0f s".format(l.endSeconds, l.goLiveSeconds) +
                            (l.relabelledFrom?.let { " · corrected from ${songlistener.core.SongKeys.labelOf(it) ?: it}" } ?: "") +
                            (if (l.reviewed) " · ✓ reviewed" else ""),
                        style = MaterialTheme.typography.bodySmall, color = Color.LightGray,
                    )
                }
                if (isPlaying) {
                    val pos by produceState(0.0 to 0.0, isPlaying) { while (true) { value = WavPlayer.position() ?: (0.0 to 0.0); delay(250) } }
                    Text("%d:%02d / %d:%02d".format(pos.first.toInt() / 60, pos.first.toInt() % 60, pos.second.toInt() / 60, pos.second.toInt() % 60),
                        style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { WavPlayer.stop() }) { Text("Stop") }
                } else {
                    TextButton(onClick = { WavPlayer.play(item.wav) }) { Text("Play") }
                    TextButton(onClick = { WavPlayer.play(item.wav, maxOf(0.0, l.goLiveSeconds - 2)) }) { Text("Play from slide") }
                }
            }
            when (val c = item.check) {
                null -> Text("Checking…", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                else -> c.flags.forEach { f ->
                    Text("⚑ ${f.text}", color = when (f.severity) { 2 -> Color(0xFFFF8A80); 1 -> Color(0xFFFFD54F); else -> Color.Gray },
                        style = MaterialTheme.typography.bodyMedium)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!l.reviewed) OutlinedButton(onClick = { engine.markReviewed(item.name) }) { Text("Looks right") }
                item.check?.rivalKey?.takeIf { (item.check.rivalScore ?: 0.0) >= 0.7 }?.let { rival ->
                    Button(onClick = { engine.relabel(item.name, rival) }) { Text("It's ${item.check.rivalLabel}") }
                }
                OutlinedButton(onClick = { picking = !picking }) { Text(if (picking) "Cancel" else "It's another song…") }
                if (confirmDelete) {
                    TextButton(onClick = { WavPlayer.stop(); engine.deleteRecording(item.name) }) { Text("Confirm delete", color = Color(0xFFE57373)) }
                    LaunchedEffect(Unit) { delay(4000); confirmDelete = false }
                } else TextButton(onClick = { confirmDelete = true }) { Text("Delete") }
            }
            if (picking) SongPicker(engine) { key -> picking = false; engine.relabel(item.name, key) }
        }
    }
}

/** Search by number or title across learned songs and ChurchPresenter's library. */
@Composable
private fun SongPicker(engine: ListenerEngine, onPick: (String) -> Unit) {
    val songs by produceState(emptyList<Pair<String, String>>()) { value = engine.knownSongs() }
    var q by remember { mutableStateOf("") }
    OutlinedTextField(q, { q = it }, label = { Text("Number or title") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    val hits = if (q.isBlank()) emptyList() else songs.filter { (k, label) ->
        label.contains(q, ignoreCase = true) || k.substringBefore('|') == q.trim().trimStart('#')
    }.take(8)
    Column(Modifier.heightIn(max = 260.dp)) {
        hits.forEach { (k, label) -> Text(label, Modifier.fillMaxWidth().clickable { onPick(k) }.padding(6.dp)) }
        if (q.isNotBlank() && hits.isEmpty()) Text("No match" + if (songs.isEmpty()) " (loading the song list…)" else "", color = Color.Gray)
    }
}
