package songlistener.io

import java.io.File
import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Read access to an STT session database, through a temporary copy: the database is in WAL mode,
 * may sit on a network or archive drive, and the original must never be opened or touched.
 */
class SessionDb private constructor(private val tmp: File, private val c: Connection) : AutoCloseable {

    fun rowCount(): Int = query("SELECT COUNT(*) FROM transcriptions") { it.getInt(1) }.firstOrNull() ?: 0

    /** The newest row timestamp that parses, from the last 200 rows (STT's own rule). */
    fun lastTimestamp(): LocalDateTime? =
        query("SELECT timestamp FROM transcriptions ORDER BY id DESC LIMIT 200") { it.getString(1) }
            .firstNotNullOfOrNull { v -> v?.let { runCatching { LocalDateTime.parse(it.trim(), ROW_TIME) }.getOrNull() } }

    /** True when STT generated this session as a demo (`session_meta.synthetic = '1'`). */
    fun isSynthetic(): Boolean = runCatching {
        query("SELECT value FROM session_meta WHERE key = 'synthetic'") { it.getString(1) }.any { it == "1" }
    }.getOrDefault(false)

    /** (clock seconds, how far the recording runs ahead of the clock there) for every line. */
    fun drift(fileStartSec: Double): List<Pair<Double, Double>> =
        query("SELECT ts_ms, end_time FROM transcriptions WHERE ts_ms IS NOT NULL AND end_time > 0 ORDER BY ts_ms") {
            val clock = it.getLong(1) / 1000.0
            clock to (fileStartSec + it.getDouble(2) - clock)
        }

    private fun <T> query(sql: String, row: (java.sql.ResultSet) -> T): List<T> =
        c.createStatement().use { st ->
            st.executeQuery(sql).use { rs ->
                val out = mutableListOf<T>()
                while (rs.next()) out += row(rs)
                out
            }
        }

    override fun close() {
        runCatching { c.close() }
        tmp.deleteRecursively()
    }

    companion object {
        private val ROW_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

        fun open(db: File): SessionDb {
            val tmp = Files.createTempDirectory("songlistener-db").toFile()
            val copy = File(tmp, "session.db")
            db.copyTo(copy)
            listOf("-wal", "-shm").forEach { ext -> File(db.path + ext).takeIf { it.exists() }?.copyTo(File(copy.path + ext)) }
            return SessionDb(tmp, DriverManager.getConnection("jdbc:sqlite:${copy.absolutePath}"))
        }
    }
}
