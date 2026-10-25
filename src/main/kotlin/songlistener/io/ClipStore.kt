package songlistener.io

import kotlinx.serialization.json.Json
import songlistener.core.Clip
import songlistener.core.ClipLabels
import songlistener.core.SongKeys
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Song recordings: `<yyyy-MM-dd_HHmmss>_<song>.wav` (16-bit mono PCM) with a `.json` of its
 * [ClipLabels] beside it, under [dir]. Never pruned automatically — they are training data — and
 * deleted only per song, by [delete], or all at once, by [deleteAll]. Service audio: never commit these anywhere.
 */
class ClipStore(private val dir: File) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    data class Stats(
        val count: Int,
        val bytes: Long,
        val perSong: Map<String, Int>,
        val live: Int = 0,
        val imported: Int = 0,
        val livePerSong: Map<String, Int> = emptyMap(),
    )

    /** Writes [clip]; returns the `.wav` written. */
    fun save(clip: Clip): File {
        dir.mkdirs()
        val base = uniqueBase(baseName(clip.labels))
        val wav = File(dir, "$base.wav")
        val meta = File(dir, "$base.json")
        writeAtomically(wav) { writeWav(it, clip.samples, clip.labels.sampleRate) }
        writeAtomically(meta) { it.writeText(json.encodeToString(ClipLabels.serializer(), clip.labels)) }
        return wav
    }

    /** Every recording's labels, oldest first, without reading the audio. */
    fun labels(): List<Pair<File, ClipLabels>> =
        dir.listFiles { f -> f.name.endsWith(".json") }.orEmpty()
            .mapNotNull { f ->
                runCatching {
                    val labels = json.decodeFromString(ClipLabels.serializer(), f.readText())
                    // Older recordings stored "Title (#n)"; show every one the current way.
                    File(dir, f.name.removeSuffix(".json") + ".wav") to withDefaults(labels)
                }.getOrNull()
            }
            .filter { it.first.exists() }
            .sortedBy { it.second.startedAt }

    /** The labels of the recording [wav], read fresh. */
    fun labelsOf(wav: File): ClipLabels? = File(dir, wav.name.removeSuffix(".wav") + ".json").takeIf { it.exists() }
        ?.let { f -> runCatching { withDefaults(json.decodeFromString(ClipLabels.serializer(), f.readText())) }.getOrNull() }

    /** Every recording with its audio, oldest first. */
    fun loadAll(): List<Pair<File, Clip>> = labels().mapNotNull { (wav, labels) ->
        runCatching { wav to Clip(readWav(wav), labels) }.getOrNull()
    }

    /** Rewrites a recording's labels; renames its files when the song changed. Returns the new `.wav`. */
    fun update(wav: File, labels: ClipLabels): File {
        val oldJson = File(dir, wav.name.removeSuffix(".wav") + ".json")
        val stored = json.decodeFromString(ClipLabels.serializer(), oldJson.readText())
        val target = if (stored.songKey == labels.songKey) wav else File(dir, uniqueBase(baseName(labels)) + ".wav")
        if (target != wav) {
            wav.renameTo(target)
            oldJson.delete()
        }
        writeAtomically(File(dir, target.name.removeSuffix(".wav") + ".json")) {
            it.writeText(json.encodeToString(ClipLabels.serializer(), labels))
        }
        return target
    }

    /**
     * Removes one recording by moving it to a `deleted` folder beside this one, so a mistaken
     * delete (the review page is open to the whole network) can be undone by moving it back.
     */
    fun deleteOne(wav: File) {
        val trash = File(dir.parentFile, "deleted").also { it.mkdirs() }
        listOf(wav, File(dir, wav.name.removeSuffix(".wav") + ".json")).forEach { f ->
            if (f.exists() && !f.renameTo(File(trash, f.name))) f.delete()
        }
    }

    /** The recording file named [name] (with or without `.wav`), if it exists here. */
    fun find(name: String): File? =
        File(dir, name.removeSuffix(".wav") + ".wav").takeIf { it.exists() && it.parentFile == dir }

    fun delete(songKey: String) {
        labels().filter { it.second.songKey == songKey }.forEach { (wav, _) ->
            wav.delete()
            File(dir, wav.name.removeSuffix(".wav") + ".json").delete()
        }
    }

    /** Moves every recording into [trash], like [deleteOne] but for all of them. */
    fun deleteAll(trash: File) {
        trash.mkdirs()
        dir.listFiles { f -> f.name.endsWith(".wav") || f.name.endsWith(".json") }.orEmpty().forEach { f ->
            if (!f.renameTo(File(trash, f.name))) f.delete()
        }
    }

    fun stats(): Stats {
        val all = labels()
        val live = all.filter { it.second.origin == "live" }
        return Stats(
            count = all.size,
            bytes = all.sumOf { it.first.length() },
            perSong = all.groupingBy { it.second.songKey }.eachCount(),
            live = live.size,
            imported = all.size - live.size,
            livePerSong = live.groupingBy { it.second.songKey }.eachCount(),
        )
    }

    private fun baseName(l: ClipLabels): String {
        val time = Instant.ofEpochMilli(l.startedAt).atZone(ZoneId.systemDefault()).format(NAME_TIME)
        val number = l.songKey.substringBefore('|').toIntOrNull()?.takeIf { it > 0 }
        val song = number?.toString() ?: l.songLabel.filter { it.isLetterOrDigit() || it == ' ' }.trim().replace(' ', '-').take(24)
        return "${time}_$song"
    }

    private fun uniqueBase(base: String): String {
        var name = base
        var n = 2
        while (File(dir, "$name.wav").exists() || File(dir, "$name.json").exists()) name = "$base-${n++}"
        return name
    }

    private fun writeAtomically(target: File, write: (File) -> Unit) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        write(tmp)
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
    }

    internal companion object {
        /**
         * Labels as they are read: the label number first, and an origin for recordings saved before
         * origins existed — every one of those was imported, from SongListener's log on 2026-09-30
         * and from ChurchPresenter's play log otherwise.
         */
        fun withDefaults(l: ClipLabels): ClipLabels {
            val origin = l.origin ?: "import"
            val source = l.importSource ?: if (origin == "import") {
                val day = Instant.ofEpochMilli(l.startedAt).atZone(ZoneId.systemDefault()).toLocalDate().toString()
                if (day == "2026-09-30") "songlistener_log" else "play_log"
            } else null
            return l.copy(songLabel = SongKeys.labelOf(l.songKey) ?: l.songLabel, origin = origin, importSource = source)
        }

        private val NAME_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss")

        fun writeWav(file: File, samples: ShortArray, sampleRate: Int) {
            val dataBytes = samples.size * 2
            DataOutputStream(file.outputStream().buffered()).use { out ->
                val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
                header.put("RIFF".toByteArray()).putInt(36 + dataBytes).put("WAVE".toByteArray())
                header.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
                    .putInt(sampleRate).putInt(sampleRate * 2).putShort(2).putShort(16)
                header.put("data".toByteArray()).putInt(dataBytes)
                out.write(header.array())
                val body = ByteBuffer.allocate(dataBytes).order(ByteOrder.LITTLE_ENDIAN)
                body.asShortBuffer().put(samples)
                out.write(body.array())
            }
        }

        /** Reads 16-bit mono PCM, skipping any chunks before `data`. */
        fun readWav(file: File): ShortArray {
            val bytes = file.readBytes()
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            require(String(bytes, 0, 4) == "RIFF" && String(bytes, 8, 4) == "WAVE") { "not a WAV: $file" }
            var pos = 12
            while (pos + 8 <= bytes.size) {
                val id = String(bytes, pos, 4)
                val len = buf.getInt(pos + 4)
                if (id == "data") {
                    val n = minOf(len, bytes.size - pos - 8) / 2
                    val out = ShortArray(n)
                    ByteBuffer.wrap(bytes, pos + 8, n * 2).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(out)
                    return out
                }
                pos += 8 + len + (len and 1)
            }
            error("no data chunk: $file")
        }
    }
}
