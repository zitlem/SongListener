package songlistener.io

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import songlistener.core.Config
import java.io.File

/**
 * The Tuning controls' work, shared by the app and the web page so both show the same test, the
 * same clipping check and the same saved settings, whoever started them.
 */
class TuningJobs(
    private val engine: ListenerEngine,
    /** The settings the listener is running with (matching settings saved since apply at the next start). */
    val running: Config,
    private val settingsFile: File,
    private val log: EventLog,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    data class TestState(
        val running: Boolean = false,
        val progress: Double = 0.0,
        val status: String? = null,
        val cleanOnly: Boolean = true,
        val results: List<Tuning.Result> = emptyList(),
    )

    private val _test = MutableStateFlow(TestState())
    val test: StateFlow<TestState> = _test
    private var testJob: Job? = null

    data class ClippingState(val busy: Boolean = false, val rows: List<ClipScan.Row>? = null)

    private val _clipping = MutableStateFlow(ClippingState())
    val clipping: StateFlow<ClippingState> = _clipping

    private val _saved = MutableStateFlow(AppSettings.load(settingsFile))
    /** The settings file as last saved (matching settings included). */
    val saved: StateFlow<AppSettings> = _saved

    /** The matching settings as saved, applied to what is running. */
    fun savedConfig(): Config = _saved.value.applyTo(running)

    fun saveMatching(keepTakes: String, maxTakes: Int, agreement: Double, intro: String, confirmed: Boolean, from: String) {
        val s = AppSettings.load(settingsFile).copy(
            keepTakes = if (keepTakes == "best") "best" else "newest",
            maxTakesPerSong = maxTakes.coerceIn(1, 20),
            takeAgreement = (Math.round(agreement.coerceIn(0.0, 1.0) * 10) / 10.0),
            introMode = intro.takeIf { it in listOf("keep", "match", "drop") } ?: "match",
            confirmedParts = confirmed,
        )
        AppSettings.save(settingsFile, s)
        _saved.value = s
        log.note("tuning_saved", "keep=${s.keepTakes} takes=${s.maxTakesPerSong} agreement=${s.takeAgreement} intro=${s.introMode} confirmed=${s.confirmedParts} (from $from)")
    }

    /** Saves the connection addresses and connects to them. */
    fun saveConnection(churchPresenterUrl: String, apiKey: String, sttUrl: String, from: String = "app") {
        // Only its own fields: the rest may have changed since the caller loaded them.
        val s = AppSettings.load(settingsFile).copy(churchPresenterUrl = churchPresenterUrl, apiKey = apiKey, sttUrl = sttUrl)
        AppSettings.save(settingsFile, s)
        _saved.value = s
        engine.connect(churchPresenterUrl, apiKey, sttUrl, from)
    }

    fun saveWebPort(port: Int, from: String = "app") {
        log.note("web_port", "$port (from $from)")
        val s = AppSettings.load(settingsFile).copy(webPort = port)
        AppSettings.save(settingsFile, s)
        _saved.value = s
    }

    fun startTest(cleanOnly: Boolean, from: String) {
        if (testJob?.isActive == true) return
        _test.value = TestState(running = true, status = "Loading recordings…", cleanOnly = cleanOnly)
        log.note("tuning_test_started", "clean=$cleanOnly (from $from)")
        testJob = scope.launch {
            try {
                val clips = engine.recordingsForTest(cleanOnly)
                if (clips.isEmpty()) { _test.value = _test.value.copy(running = false, status = "No recordings to test."); return@launch }
                _test.value = _test.value.copy(status = "Testing ${clips.size} recordings…")
                val results = Tuning.run(clips, Tuning.variants(savedConfig())) { p -> _test.value = _test.value.copy(progress = p) }
                _test.value = _test.value.copy(running = false, status = null, results = results, progress = 1.0)
                log.note("tuning_test_finished", results.joinToString("; ") { "${it.variant.name}: ${it.summary.rightAtAll}/${it.summary.testable}" })
            } catch (e: CancellationException) {
                _test.value = _test.value.copy(running = false, status = "Stopped.")
            } catch (e: Throwable) {
                _test.value = _test.value.copy(running = false, status = "Test failed: ${e.message ?: e::class.simpleName}")
            }
        }
    }

    fun stopTest() { testJob?.cancel() }

    fun checkClipping() {
        if (_clipping.value.busy) return
        _clipping.value = _clipping.value.copy(busy = true)
        scope.launch {
            try { _clipping.value = ClippingState(rows = engine.clipScan()) }
            catch (e: Throwable) { _clipping.value = _clipping.value.copy(busy = false) }
        }
    }

    /** The best result: most recognised, then fewest wrong suggestions, then most ranked first. */
    fun best(results: List<Tuning.Result>): Tuning.Result? = results.maxWithOrNull(
        compareBy<Tuning.Result> { it.summary.rightAtAll }.thenBy { -it.summary.wrongSuggestions }.thenBy { it.summary.meanTop1 ?: 0.0 },
    )

    fun shutdown() { scope.coroutineContext[Job]?.cancel() }
}
