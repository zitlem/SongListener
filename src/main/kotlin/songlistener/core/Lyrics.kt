package songlistener.core

import kotlinx.serialization.Serializable
import java.security.MessageDigest

/** One section of a song's words. [kind] is `verse` or `chorus`; [label] is its heading, e.g. `Куплет 1`. */
@Serializable
data class LyricsSection(val label: String, val kind: String, val lines: List<String>)

/**
 * A song's words as they were when captured. [songbookCertain] is false when the same number and
 * title exist in more than one songbook with different words and which one was shown is unknown.
 */
@Serializable
data class LyricsSnapshot(
    val version: Int = 1,
    val songKey: String,
    val songLabel: String,
    val songbook: String?,
    val songbookCertain: Boolean,
    val number: Int,
    val title: String,
    val capturedAt: Long,
    /** `churchpresenter` (fetched live) or `song_cache` (read from ChurchPresenter's cache). */
    val source: String,
    /** When ChurchPresenter's copy was last edited, if known. */
    val sourceModified: Long? = null,
    val sections: List<LyricsSection>,
) {
    /** Identifies the words only: the same text gives the same hash however it was captured. */
    val hash: String get() = Lyrics.hash(sections)
}

/**
 * ChurchPresenter's lyrics rules, ported from `song-chords` `ChordTransposer` so sections split
 * exactly as ChurchPresenter splits them: a heading is a whole line in `[…]` or `{…}` (`{}` =
 * chorus), except a slide break (`[---]`), a background directive (`[background: …]`) or a line of
 * chords (`[Am]`, `[Cm] [Bb]`). Only the words are kept — inline chords are removed.
 */
object Lyrics {
    private val CHORD = Regex(
        "^[A-G][#b]?" + "(maj|min|dim|aug|sus|add|m|M)?" + "[0-9]*" +
            "(sus[24]|add[0-9]+|dim|aug|[b#]5)?" + "(/[A-G][#b]?)?$"
    )
    private val SLIDE_BREAK = Regex("^[\\[{][\\s-]*-[\\s-]*[\\]}]$")
    private val DIRECTIVE = Regex("^\\[([^:\\[\\]]+):([^\\[\\]]*)]$")
    private val BACKGROUND_KEY = Regex("^(lower-third-)?background(-[a-z-]+)?$")
    private val INLINE_CHORD = Regex("\\[([^\\]]*)]")

    fun isChord(token: String) = CHORD.matches(token.trim())
    fun isSlideBreak(line: String) = SLIDE_BREAK.matches(line.trim())
    fun isDirective(line: String): Boolean {
        val m = DIRECTIVE.matchEntire(line.trim()) ?: return false
        return BACKGROUND_KEY.matches(m.groupValues[1].trim().lowercase())
    }

    fun isHeader(line: String): Boolean {
        val t = line.trim()
        val bracketed = (t.startsWith("[") && t.endsWith("]")) || (t.startsWith("{") && t.endsWith("}"))
        if (!bracketed || isSlideBreak(t) || isDirective(t)) return false
        val inner = t.substring(1, t.length - 1)
        if (inner.any { it in "[]{}" }) return false
        return !isChord(inner)
    }

    /** A line's words: inline chords removed, spacing tidied; empty for a chord-only line. */
    fun words(line: String): String =
        INLINE_CHORD.replace(line) { m -> if (isChord(m.groupValues[1])) "" else m.value }
            .replace(Regex("\\s+"), " ").trim()

    /** Splits ChurchPresenter's flat lyric lines into sections. Lines before any heading form an unnamed verse. */
    fun sections(lines: List<String>): List<LyricsSection> {
        val out = mutableListOf<LyricsSection>()
        var label = ""
        var kind = "verse"
        var body = mutableListOf<String>()
        fun flush() {
            if (body.isNotEmpty()) out += LyricsSection(label, kind, body)
            body = mutableListOf()
        }
        for (raw in lines) {
            val t = raw.trim()
            when {
                isHeader(t) -> {
                    flush()
                    label = t.substring(1, t.length - 1).trim()
                    kind = if (t.startsWith("{")) "chorus" else "verse"
                }
                t.isEmpty() || isSlideBreak(t) || isDirective(t) -> Unit
                else -> words(t).takeIf { it.isNotEmpty() }?.let { body += it }
            }
        }
        flush()
        return out
    }

    /** Sections as ChurchPresenter's API sends them (`type` + `lines`), reduced to words. */
    fun fromApi(sections: List<Pair<String, List<String>>>): List<LyricsSection> =
        sections.map { (type, lines) ->
            LyricsSection("", if (type == "chorus") "chorus" else "verse", lines.map(::words).filter { it.isNotEmpty() })
        }.filter { it.lines.isNotEmpty() }

    /** A hash of the words alone — headings and kinds aside, so the API's and the cache's copies of one text agree. */
    fun hash(sections: List<LyricsSection>): String {
        val text = sections.flatMap { it.lines }.joinToString("\n") { it.lowercase() }
        return MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }.take(8)
    }
}
