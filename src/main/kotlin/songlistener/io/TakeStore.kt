package songlistener.io

import songlistener.core.SectionMark
import songlistener.core.SongKeys
import songlistener.core.SongTake
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest

/**
 * One binary file per take: `<sha1(songKey)>-<recordedAt>.take` under [dir]. Takes are derived from
 * service audio, so they live only here, never in the repository.
 */
class TakeStore(private val dir: File, private val maxTakesPerSong: Int) {

    fun loadAll(): List<SongTake> =
        dir.listFiles { f -> f.name.endsWith(EXT) }.orEmpty()
            .mapNotNull { runCatching { read(it) }.getOrNull() }
            .sortedBy { it.recordedAt }

    fun save(take: SongTake) {
        dir.mkdirs()
        val file = File(dir, "${prefix(take.songKey)}${take.recordedAt}$EXT")
        val tmp = File(dir, file.name + ".tmp")
        DataOutputStream(tmp.outputStream().buffered()).use { out ->
            out.writeInt(MAGIC)
            out.writeUTF(take.songKey)
            out.writeUTF(take.songLabel)
            out.writeLong(take.recordedAt)
            out.writeInt(take.frames.size)
            for (f in take.frames) for (v in f) out.writeFloat(v)
            out.writeInt(take.sectionMarks.size)
            for (m in take.sectionMarks) { out.writeInt(m.frame); out.writeInt(m.section) }
        }
        tmp.renameTo(file)
        prune(take.songKey)
    }

    fun delete(songKey: String) {
        filesFor(songKey).forEach { it.delete() }
    }

    private fun prune(songKey: String) {
        filesFor(songKey).sortedBy { it.name }.dropLast(maxTakesPerSong).forEach { it.delete() }
    }

    private fun filesFor(songKey: String): List<File> {
        val p = prefix(songKey)
        return dir.listFiles { f -> f.name.startsWith(p) && f.name.endsWith(EXT) }.orEmpty().toList()
    }

    private fun read(file: File): SongTake = DataInputStream(file.inputStream().buffered()).use { inp ->
        require(inp.readInt() == MAGIC) { "not a take file: $file" }
        val key = inp.readUTF()
        // Older takes stored "Title (#n)"; show every take the current way.
        val label = inp.readUTF().let { SongKeys.labelOf(key) ?: it }
        val recordedAt = inp.readLong()
        val frames = List(inp.readInt()) { FloatArray(12) { inp.readFloat() } }
        val marks = List(inp.readInt()) { SectionMark(inp.readInt(), inp.readInt()) }
        SongTake(key, label, recordedAt, frames, marks)
    }

    private companion object {
        const val EXT = ".take"
        const val MAGIC = 0x534C5431 // "SLT1"

        fun prefix(songKey: String): String {
            val digest = MessageDigest.getInstance("SHA-1").digest(songKey.toByteArray())
            return digest.joinToString("") { "%02x".format(it) }.take(16) + "-"
        }
    }
}
