package songlistener.core

/**
 * How a song is identified, from what ChurchPresenter shows: `number|title`, and a label for
 * display. One rule for the live client and every importer, so a song learned live and the same
 * song imported from a past service share a key.
 */
object SongKeys {
    fun key(number: Int, title: String) = "$number|$title"
    /** `#368 Title`: the number first, as it is called out; just the title when there is none. */
    fun label(number: Int, title: String) = if (number > 0) "#$number $title" else title

    /**
     * The label for a stored `number|title` key. Older takes, recordings and logs saved the label
     * as `Title (#n)`; deriving it from the key shows them all the same way. Null for a key that is
     * not in that form.
     */
    fun labelOf(key: String): String? {
        val bar = key.indexOf('|')
        if (bar < 0) return null
        val number = key.substring(0, bar).toIntOrNull() ?: return null
        return label(number, key.substring(bar + 1))
    }
    fun liveSong(number: Int, title: String, section: Int?): LiveSong? =
        if (title.isBlank() && number == 0) null else LiveSong(key(number, title), label(number, title), section)
}
