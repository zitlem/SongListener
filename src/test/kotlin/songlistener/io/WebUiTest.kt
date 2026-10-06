package songlistener.io

import songlistener.core.Config
import java.net.URI
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
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
            assertTrue("\"library\":[]" in json && "\"accuracy\"" in json && "\"slideCheck\"" in json)
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
}
