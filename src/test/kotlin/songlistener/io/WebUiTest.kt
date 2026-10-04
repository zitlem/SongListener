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
            assertTrue("Learned songs" in page && "Accuracy" in page && "Hearing" in page)
            val json = URI("http://localhost:18781/api/state").toURL().readText()
            assertTrue("\"library\":[]" in json && "\"accuracy\"" in json)
        } finally { web.stop() }
    }
}
