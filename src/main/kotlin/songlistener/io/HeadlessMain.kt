package songlistener.io

import songlistener.core.Config
import java.io.File
import java.util.concurrent.CountDownLatch

/**
 * The app without its window, for a machine with no display (.62, under systemd). Does what
 * `ui/Main.kt` does before it opens the window; the web page on :8780 has the same controls.
 * SIGTERM runs the shutdown hook, which saves the take in progress like closing the window does.
 */
fun main() {
    val dataDir = File(System.getProperty("user.home"), ".songlistener")
    val settingsFile = File(dataDir, "config.json")
    val config = AppSettings.load(settingsFile).applyTo(Config())
    val engine = ListenerEngine(dataDir, config, deviceId = AppSettings.deviceId(settingsFile))
    val web = WebUi(engine, config, AppSettings.load(settingsFile).webPort).also { it.start() }
    val stopped = CountDownLatch(1)
    Runtime.getRuntime().addShutdownHook(Thread { engine.shutdown(); web.stop(); stopped.countDown() })
    engine.refreshReview()
    val saved = AppSettings.load(settingsFile)
    engine.connect(saved.churchPresenterUrl, saved.apiKey, saved.sttUrl)
    println("SongListener headless: ChurchPresenter ${saved.churchPresenterUrl}, STT ${saved.sttUrl}, web :${saved.webPort}")
    stopped.await()
}
