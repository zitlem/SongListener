package songlistener.io

import songlistener.core.SessionClassifier
import java.io.File
import java.nio.file.Files
import java.sql.DriverManager
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionsTest {
    private val dir = Files.createTempDirectory("sessions").toFile()

    // 2026-07-05 is a Sunday, 2026-07-08 a Wednesday, 2026-07-09 a Thursday.
    private fun at(s: String) = LocalDateTime.parse(s)
    private fun kind(start: String, minutes: Double, rows: Int, synthetic: Boolean = false) =
        SessionClassifier.classify(at(start), minutes, rows, synthetic).kind

    @Test
    fun `services, fragments, unusual sessions and tests, as STT decides`() {
        assertEquals("service", kind("2026-07-05T09:31:00", 125.0, 900))          // Sunday morning
        assertEquals("service", kind("2026-07-05T17:27:00", 90.0, 900))           // Sunday evening, exactly 90 min
        assertEquals("service", kind("2026-07-08T18:36:00", 94.0, 700))           // Wednesday
        assertEquals("fragment", kind("2026-07-05T17:27:00", 89.0, 900))          // in a window, too short
        assertEquals("service", kind("2026-07-05T08:00:00", 95.0, 10))            // window opens 2 h early
        assertEquals("test", kind("2026-07-05T07:59:00", 95.0, 10))               // …not earlier
        assertEquals("fragment", kind("2026-07-05T12:30:00", 30.0, 10))           // window stays open 2.5 h
        assertEquals("test", kind("2026-07-05T12:31:00", 30.0, 10))
        assertEquals("unusual", kind("2026-07-09T23:09:00", 43.0, 300))           // off schedule, 300 lines
        assertEquals("test", kind("2026-07-09T23:09:00", 83.0, 299))
        assertEquals("test", kind("2026-07-05T09:31:00", 125.0, 900, synthetic = true))
    }

    @Test
    fun `recordings pair with their database by name, else the nearest within 30 minutes`() {
        fun f(name: String) = File(dir, name).also { it.writeText("") }
        val sameName = f("2026-07-05_093218.wav")
        val nearby = f("2026-08-05_182905.wav")
        val alone = f("2026-09-01_120000.wav")
        val dbs = listOf(f("2026-07-05_093218.db"), f("2026-08-05_182841.db"), f("2026-08-05_190000.db"), f("2026-07-01_183426.db"))
        val found = pairSessions(listOf(sameName, nearby, alone), dbs)
        val byId = found.sessions.associateBy { it.id }
        assertEquals("2026-07-05_093218.db", byId.getValue("2026-07-05_093218").db?.name)
        assertEquals("2026-08-05_182841.db", byId.getValue("2026-08-05_182905").db?.name) // 24 s apart, not 31 min
        assertNull(byId.getValue("2026-09-01_120000").db)
        assertEquals(listOf("2026-07-01_183426.db", "2026-08-05_190000.db"), found.withoutWav.map { it.name })
    }

    @Test
    fun `a session database is read through a copy`() {
        val db = File(dir, "2026-07-05_093218.db")
        DriverManager.getConnection("jdbc:sqlite:${db.absolutePath}").use { c ->
            c.createStatement().use { st ->
                st.execute("CREATE TABLE transcriptions (id INTEGER PRIMARY KEY, timestamp TEXT, text TEXT, start_time REAL, end_time REAL, ts_ms INTEGER)")
                st.execute("CREATE TABLE session_meta (key TEXT PRIMARY KEY, value TEXT)")
                st.execute("INSERT INTO transcriptions (timestamp, end_time, ts_ms) VALUES ('2026-07-05 09:40:00', 500.0, 1783244400000)")
                st.execute("INSERT INTO transcriptions (timestamp, end_time, ts_ms) VALUES ('2026-07-05 11:36:30', 7000.0, 1783251390000)")
                st.execute("INSERT INTO transcriptions (timestamp, end_time, ts_ms) VALUES (' ', 0, NULL)")
                st.execute("INSERT INTO session_meta VALUES ('synthetic', '1')")
            }
        }
        val before = db.lastModified()
        SessionDb.open(db).use { s ->
            assertEquals(3, s.rowCount())
            assertEquals(at("2026-07-05T11:36:30"), s.lastTimestamp())  // the blank newest row is skipped
            assertTrue(s.isSynthetic())
            assertEquals(2, s.drift(1_783_243_938.0).size)
        }
        assertEquals(before, db.lastModified())
    }
}
