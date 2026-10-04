package songlistener.io

import kotlinx.serialization.json.Json
import songlistener.core.LyricsSnapshot
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Song words over time: `<dir>/<sha(songKey)>/<songbook>/<yyyy-MM-dd_HHmmss>_<hash8>.json`. A
 * new file is written only when the words differ from that song's latest copy in that songbook,
 * so each folder is the song's edit history and a recording names the version it was sung from.
 */
class LyricsStore(private val dir: File) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    data class Saved(val version: String, val changed: Boolean, val file: File)

    fun saveIfChanged(s: LyricsSnapshot): Saved {
        val folder = folderFor(s.songKey, s.songbook)
        val latest = latestFile(folder)
        val hash = s.hash
        if (latest != null && latest.nameWithoutExtension.substringAfterLast('_') == hash) {
            return Saved(hash, changed = false, latest)
        }
        folder.mkdirs()
        val time = Instant.ofEpochMilli(s.capturedAt).atZone(ZoneId.systemDefault()).format(NAME_TIME)
        val file = File(folder, "${time}_$hash.json")
        val tmp = File(folder, file.name + ".tmp")
        tmp.writeText(json.encodeToString(LyricsSnapshot.serializer(), s))
        if (!tmp.renameTo(file)) { tmp.copyTo(file, overwrite = true); tmp.delete() }
        return Saved(hash, changed = true, file)
    }

    /** The latest words saved for a song in a songbook, if any. */
    fun latest(songKey: String, songbook: String?): LyricsSnapshot? =
        latestFile(folderFor(songKey, songbook))?.let { json.decodeFromString(LyricsSnapshot.serializer(), it.readText()) }

    /** Every version saved for a song in a songbook, oldest first. */
    fun versions(songKey: String, songbook: String?): List<File> =
        folderFor(songKey, songbook).listFiles { f -> f.name.endsWith(".json") }.orEmpty().sortedBy { it.name }

    private fun latestFile(folder: File) = folder.listFiles { f -> f.name.endsWith(".json") }.orEmpty().maxByOrNull { it.name }

    private fun folderFor(songKey: String, songbook: String?): File {
        val digest = MessageDigest.getInstance("SHA-1").digest(songKey.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)
        val book = songbook?.replace(Regex("[^\\p{L}\\p{N} ._-]"), "_")?.trim()?.take(60)?.ifBlank { null } ?: "_unknown"
        return File(File(dir, digest), book)
    }

    private companion object {
        val NAME_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss")
    }
}
