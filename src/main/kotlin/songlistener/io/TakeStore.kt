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
class TakeStore(
    private val dir: File,
    private val maxTakesPerSong: Int,
    /** Which of a song's takes to keep when it has too many (see `TakeSelection`); null keeps the newest. */
    private val select: ((List<SongTake>) -> List<SongTake>)? = null,
) {

    fun loadAll(): List<SongTake> =
        dir.listFiles { f -> f.name.endsWith(EXT) }.orEmpty()
            .mapNotNull { runCatching { read(it) }.getOrNull() }
            .sortedBy { it.recordedAt }

    fun save(take: SongTake) {
        write(take)
        prune(take.songKey)
    }

    /** Rewrites a take in place (e.g. to add what was measured since), without pruning. */
    fun write(take: SongTake) {
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
            // Appended after the original layout, so older builds still read the file (and ignore it).
            out.writeDouble(take.clipped ?: Double.NaN)
        }
        tmp.renameTo(file)
    }

    /** Deletes the one take of [songKey] recorded at [recordedAt]. */
    fun deleteTake(songKey: String, recordedAt: Long) {
        filesFor(songKey).filter { it.name == "${prefix(songKey)}$recordedAt$EXT" }.forEach { it.delete() }
    }

    fun delete(songKey: String) {
        filesFor(songKey).forEach { it.delete() }
    }

    /** Moves every take into [trash] (a mistaken "forget all" can be undone by moving them back). */
    fun deleteAll(trash: File) {
        trash.mkdirs()
        dir.listFiles { f -> f.name.endsWith(EXT) }.orEmpty().forEach { f ->
            if (!f.renameTo(File(trash, f.name))) f.delete()
        }
    }

    private fun prune(songKey: String) {
        val files = filesFor(songKey)
        if (files.size <= maxTakesPerSong) return
        if (select == null) {
            files.sortedBy { it.name }.dropLast(maxTakesPerSong).forEach { it.delete() }
            return
        }
        val takes = files.mapNotNull { f -> runCatching { f to read(f) }.getOrNull() }
        val kept = select.invoke(takes.map { it.second }).map { it.recordedAt }.toSet()
        takes.filter { it.second.recordedAt !in kept }.forEach { it.first.delete() }
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
        val clipped = if (inp.available() >= 8) inp.readDouble().takeIf { !it.isNaN() } else null
        SongTake(key, label, recordedAt, frames, marks, clipped)
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
