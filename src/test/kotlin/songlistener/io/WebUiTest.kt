package songlistener.io

import songlistener.core.Config
import java.net.URI
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WebUiTest {
    @Test
    fun servesPageAndState() {
        val engine = ListenerEngine(createTempDirectory().toFile())
        val web = WebUi(engine, Config(), port = 18781)
        web.start()
        try {
            val page = URI("http://localhost:18781/").toURL().readText()
            assertTrue("Learned songs" in page && "Slide check" in page && "Hearing" in page && "Accuracy (" !in page)
            val json = URI("http://localhost:18781/api/state").toURL().readText()
            assertTrue("\"library\":[]" in json && "\"accuracy\"" in json && "\"slideCheck\"" in json && "\"clipping\":false" in json && "\"peakDb\"" in json)
        } finally { web.stop() }
    }

    @Test
    fun `moves to another port, and says when a port is taken`() {
        val engine = ListenerEngine(createTempDirectory().toFile())
        val web = WebUi(engine, Config(), port = 18782)
        val blocker = java.net.ServerSocket(18783)
        try {
            web.start()
            assertTrue(web.running.value)
            web.restart(18783)                                  // taken
            assertTrue(!web.running.value)
            assertTrue(web.error.value!!.contains("18783"), web.error.value)
            blocker.close()
            web.restart(18784)
            assertTrue(web.running.value && web.error.value == null)
            assertTrue("Hearing" in URI("http://localhost:18784/").toURL().readText())
            assertTrue(web.urls().first().endsWith(":18784/"), web.urls().first())
        } finally { web.stop(); blocker.close() }
    }

    @Test
    fun `the port is kept in the settings without touching the rest`() {
        val f = java.io.File(createTempDirectory().toFile(), "config.json")
        AppSettings.save(f, AppSettings(churchPresenterUrl = "http://cp:8765", deviceId = "d1"))
        AppSettings.save(f, AppSettings.load(f).copy(webPort = 9000))
        val back = AppSettings.load(f)
        kotlin.test.assertEquals(9000, back.webPort)
        kotlin.test.assertEquals("d1", back.deviceId)
        kotlin.test.assertEquals(8780, AppSettings().webPort)
    }

    private fun post(path: String, body: String): Pair<Int, String> {
        val c = URI("http://localhost:18783$path").toURL().openConnection() as java.net.HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write(body.toByteArray()) }
        val code = c.responseCode
        return code to ((if (code < 400) c.inputStream else c.errorStream)?.readBytes()?.decodeToString() ?: "")
    }

    @Test
    fun `the web page has the app's controls`() {
        val dir = createTempDirectory().toFile()
        val engine = ListenerEngine(dir, Config())
        val web = WebUi(engine, Config(), port = 18783)
        web.start()
        try {
            val page = URI("http://localhost:18783/").toURL().readText()
            listOf("Connect", "API key", "STT server", "Review recordings", "/tuning", "/report", "/logs", "Port").forEach {
                assertTrue(it in page, "main page lacks $it")
            }
            val tuning = URI("http://localhost:18783/tuning").toURL().readText()
            listOf("Guide", "Matching settings", "Test recognition", "Clipping", "Run test").forEach { assertTrue(it in tuning, "tuning page lacks $it") }

            val state = URI("http://localhost:18783/api/state").toURL().readText()
            assertTrue("\"settings\"" in state && "\"review\"" in state && "\"take\"" in state, state.take(300))
            val t0 = URI("http://localhost:18783/api/tuning").toURL().readText()
            assertTrue("\"guide\"" in t0 && "\"test\"" in t0 && "\"clippingScan\"" in t0)

            // Saved from the web page: the app's shared settings change, and the file has them.
            assertEquals(200, post("/api/tuning/settings", """{"keepTakes":"best","maxTakes":"3","agreement":"0.5","intro":"drop","confirmed":"true"}""").first)
            val saved = engine.tuning.saved.value
            assertEquals(listOf("best", "3", "0.5", "drop", "true"), listOf(saved.keepTakes, "${saved.maxTakesPerSong}", "${saved.takeAgreement}", saved.introMode, "${saved.confirmedParts}"))
            assertEquals("drop", AppSettings.load(java.io.File(dir, "config.json")).introMode)
            assertTrue("\"restartNeeded\":true" in URI("http://localhost:18783/api/state").toURL().readText())

            assertEquals(400, post("/api/control/show", "{}").first)
            assertEquals(400, post("/api/control/webport", """{"port":"80"}""").first)
            assertEquals(200, post("/api/control/dismiss", "{}").first)
            assertEquals(200, post("/api/control/forget", """{"songKey":"1|A"}""").first)
            assertEquals(200, URI("http://localhost:18783/logs").toURL().openConnection().let { (it as java.net.HttpURLConnection).responseCode })
        } finally { web.stop(); engine.shutdown() }
    }
}
