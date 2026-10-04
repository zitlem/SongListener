package songlistener.core

/** Something the recognizer did, for the review log. Durations are seconds of stored music. */
sealed interface RecognizerEvent {
    data class LiveChanged(val song: LiveSong?) : RecognizerEvent
    data class SectionChanged(val song: LiveSong) : RecognizerEvent
    data class MusicStarted(val prob: Float) : RecognizerEvent
    data class MusicStopped(val prob: Float) : RecognizerEvent
    /** The look-back went from empty to holding music: a possible intro has begun. */
    data object LookbackStarted : RecognizerEvent
    data class LookbackCleared(val heldSeconds: Double) : RecognizerEvent
    /** No music for longer than the dip tolerance: the match window restarts. */
    data class MusicBreak(val queryCleared: Int) : RecognizerEvent
    data class TakeStarted(val song: LiveSong, val introSeconds: Double) : RecognizerEvent
    /** [takeRecordedAt] is the saved [SongTake.recordedAt], which identifies its `.take` file. */
    data class TakeSaved(
        val song: LiveSong,
        val introSeconds: Double,
        val songSeconds: Double,
        val takesKept: Int,
        val takeRecordedAt: Long,
    ) : RecognizerEvent
    data class TakeDiscarded(val song: LiveSong, val introSeconds: Double, val songSeconds: Double) : RecognizerEvent

    /**
     * One matcher run. [liveKey] is the song on screen, if any. [decision] says what came of it:
     * `suggested`, `streak n/m`, `below_threshold`, `suppressed` (the song just taken off screen),
     * `already_suggested`, `accuracy_hit`, `accuracy_miss`, or `live_unlearned` (the song on screen
     * has no takes, so there is nothing to score against).
     */
    data class Match(
        val liveKey: String?,
        /** The section actually on screen, to check a match's section against. */
        val liveSection: Int?,
        val top: List<SongScore>,
        val margin: Double,
        val confident: Boolean,
        val decision: String,
        /** Which listener ran: `quick` (short window, hints) or `steady` (long window, suggestions and slide checks). */
        val window: String = "quick",
    ) : RecognizerEvent

    /**
     * [song] went on screen [lateSeconds] after its music started. The lead times say how long
     * before the slide the early guess and the confirmed suggestion named it — null if they didn't.
     */
    data class LateSlide(
        val song: LiveSong,
        val lateSeconds: Double,
        val hintLeadSeconds: Double?,
        val suggestionLeadSeconds: Double?,
    ) : RecognizerEvent {
        val caught: Boolean get() = hintLeadSeconds != null || suggestionLeadSeconds != null
    }

    /** The music sounds like [heard], not the song on screen. */
    data class WrongSlideWarning(val shown: LiveSong, val shownScore: Double, val heard: SongScore) : RecognizerEvent
    /** The warning no longer holds ([shownScore] null when the slide changed). */
    data class WrongSlideCleared(val shown: LiveSong, val shownScore: Double?) : RecognizerEvent
}
