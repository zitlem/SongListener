package songlistener.io

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import songlistener.core.Lyrics
import songlistener.core.LyricsSnapshot
import songlistener.core.SongKeys
import java.io.File

/**
 * ChurchPresenter's `song_cache.json`: every song in the library, as ChurchPresenter last loaded
 * it from the song folder, with the file's last-modified time. Indexed by SongListener's song key
 * (numbers are zero-padded there — "0368" — and keys use the number, so both become 368).
 */
class SongCache private constructor(private val byKey: Map<String, List<Entry>>) {

    data class Entry(val key: String, val number: Int, val title: String, val songbook: String, val lines: List<String>, val lastModified: Long?)

    sealed interface Lookup {
        data class Found(val entry: Entry, val songbookCertain: Boolean) : Lookup
        data class Ambiguous(val songbooks: List<String>) : Lookup
        data object Missing : Lookup
    }

    val size: Int get() = byKey.values.sumOf { it.size }

    /**
     * The song for [key], in [songbook] when given. Without a songbook, a song that exists in
     * several songbooks is only found when they all have the same words.
     */
    fun lookup(key: String, songbook: String?): Lookup {
        val all = byKey[key].orEmpty()
        if (all.isEmpty()) return Lookup.Missing
        if (songbook != null) all.firstOrNull { it.songbook == songbook }?.let { return Lookup.Found(it, true) }
        val texts = all.map { Lyrics.hash(Lyrics.sections(it.lines)) }.toSet()
        return if (texts.size == 1) Lookup.Found(all.first(), all.size == 1) else Lookup.Ambiguous(all.map { it.songbook })
    }

    fun snapshot(e: Entry, songbookCertain: Boolean, capturedAt: Long) = LyricsSnapshot(
        songKey = e.key,
        songLabel = SongKeys.label(e.number, e.title),
        songbook = e.songbook,
        songbookCertain = songbookCertain,
        number = e.number,
        title = e.title,
        capturedAt = capturedAt,
        source = "song_cache",
        sourceModified = e.lastModified,
        sections = Lyrics.sections(e.lines),
    )

    companion object {
        fun load(file: File): SongCache {
            val root = Json.parseToJsonElement(file.readText()).jsonObject
            val entries = (root["cachedSongs"]?.jsonArray.orEmpty().map { it.jsonObject["song"]!!.jsonObject to it.jsonObject["lastModified"]?.jsonPrimitive?.longOrNull } +
                root["songs"]?.jsonArray.orEmpty().map { it.jsonObject to null })
                .mapNotNull { (s, modified) ->
                    val title = s["title"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val number = s["number"]?.jsonPrimitive?.contentOrNull?.trim()?.toIntOrNull() ?: 0
                    if (title.isBlank() && number == 0) return@mapNotNull null
                    Entry(
                        key = SongKeys.key(number, title),
                        number = number,
                        title = title,
                        songbook = s["songbook"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        lines = s["lyrics"]?.jsonArray.orEmpty().map { it.jsonPrimitive.content },
                        lastModified = modified,
                    )
                }
            return SongCache(entries.groupBy { it.key })
        }
    }
}
