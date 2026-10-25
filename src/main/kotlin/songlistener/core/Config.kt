package songlistener.core

/** Every tuning constant in one place. Starting values — tune against real services. */
data class Config(
    val sampleRate: Int = 16_000,
    /** What songs are matched on: `chroma` (harmony) or `melody` (the sung line). */
    val feature: String = "chroma",
    val fftSize: Int = 4096,
    /** 1600 samples at 16 kHz = 10 raw frames per second. */
    val hopSize: Int = 1600,
    val minFreqHz: Double = 55.0,
    val maxFreqHz: Double = 4000.0,
    /** Raw frames averaged into one stored frame: 5 × 0.1 s = 2 stored frames per second. */
    val framesPerFeature: Int = 5,
    /** RMS (0..1 of full scale) below which a raw frame counts as silence. */
    val silenceRms: Double = 0.003,
    /** A recording with clip runs (see `ClipDetector`) in at least this many seconds is worth a listen. */
    val clipLightSeconds: Int = 3,
    /** Heavily clipped: clip runs in at least this many seconds, and this share of all its seconds. */
    val clipHeavySeconds: Int = 10,
    val clipHeavyFraction: Double = 0.02,

    /** The quick listener's window, in stored frames (24 = 12 s): early "sounds like…" hints. */
    val queryFrames: Int = 24,
    /**
     * The steady listener's window (72 = 36 s): confirms suggestions, scores accuracy and catches
     * wrong slides. Longer windows separate similar hymns better (replay: right song first 48% at
     * 12 s, 64% at 24 s, 70% at 36 s) but take longer to fill.
     */
    val steadyFrames: Int = 72,
    val tempoStretches: List<Double> = listOf(0.92, 0.96, 1.0, 1.04, 1.08),
    /** Try other keys when matching. Off means a song only matches in the key it was learned in. */
    val transpose: Boolean = true,

    /** PANNs music probability at or above which audio counts as music. */
    val musicThreshold: Float = 0.5f,
    /**
     * A dip below [musicThreshold] this short is treated as still music: the detector flickers for
     * a second between verses, and each flicker would otherwise restart the match window.
     */
    val musicDipToleranceSeconds: Double = 2.0,
    /** Minimum music in a take before it is kept, in stored frames (60 = 30 s). */
    val minTakeFrames: Int = 60,
    /** Longest take kept, in stored frames (1200 = 10 min); later frames are dropped. */
    val maxTakeFrames: Int = 1200,
    val maxTakesPerSong: Int = 5,
    /**
     * Which takes a song keeps beyond [maxTakesPerSong]: `newest`, or `best` (unclipped first, then
     * the one agreeing most with the song's other takes, then the newest; see `TakeSelection`).
     */
    val keepTakes: String = "newest",
    /**
     * How much a song's score leans on its second-best take: 0 scores by the best take alone, 0.5 by
     * the mean of the best two. Above 0, a song wins by several of its performances matching, not one
     * by luck. Songs with one take are scored as before. Off until replay on clean recordings shows it helps.
     */
    val takeAgreement: Double = 0.0,
    /**
     * Music heard while nothing is on screen, held so it can become the intro of the next song
     * that goes live (180 = 90 s).
     */
    val lookbackFrames: Int = 180,
    /** A break without music longer than this clears the look-back: that music was something else. */
    val lookbackMaxGapSeconds: Double = 20.0,
    /**
     * What of a take's intro is matched on: `keep` all the held music, `match` only the end of it
     * that sounds like the song (see `IntroFilter`), or `drop` it. Takes are stored whole either way.
     */
    val introMode: String = "match",
    /** For `match`: how well a 12 s stretch of intro must match the song, in its key and tempo, to be kept. */
    val introMatchScore: Double = 0.6,
    /**
     * Search only the parts of a song that at least two of its performances share (see
     * `ConfirmedParts`). Off until replay, with songs recorded three or more times, shows it helps.
     */
    val confirmedParts: Boolean = false,
    /**
     * For [confirmedParts]: how well a 12 s stretch must match another performance to count as
     * shared. Measured 2026-10-11 on 12 s stretches of the takes: the same song's other take scored
     * median 0.70 (p10 0.55, p90 0.81), the best of 8 other songs median 0.58 (p90 0.66). At 0.70
     * half of the real song was dropped; 0.65 drops mostly what is clearly something else.
     */
    val confirmScore: Double = 0.65,

    /** Seconds of audio between two matcher runs. */
    val matchIntervalSeconds: Double = 2.0,
    /** The quick listener's bar for a "sounds like…" hint. */
    val suggestScore: Double = 0.80,
    val suggestMargin: Double = 0.05,
    /**
     * The steady listener's bar for a suggestion (scores run lower over a longer window). Chosen by
     * replay sweep on 37 recordings (2026-10-01): 4/6 right, 1 wrong; 0.70 gave none right.
     */
    val steadyScore: Double = 0.64,
    val steadyMargin: Double = 0.05,
    /** How far another song must beat the one on screen, over the steady window, to warn of a wrong slide. */
    val wrongSlideMargin: Double = 0.06,
    /**
     * The song the music sounds like must itself score at least this before a wrong-slide warning:
     * all three of 2026-10-04's false alarms were rivals at 0.53–0.60, beating a poorly matching slide.
     */
    val wrongSlideMinScore: Double = 0.64,
    /**
     * Score each song relative to how it scores on other songs' recordings (cohort normalisation),
     * so songs with generic harmony stop winning by default. Off until replay shows it helps.
     */
    val cohort: Boolean = false,
    /** Consecutive steady runs that must agree before a wrong-slide warning shows. */
    val wrongSlideStreak: Int = 2,
    /** Consecutive matcher runs the same song must win before it is suggested. */
    val suggestStreak: Int = 2,
    /** Audio kept before a recording's first music, and after the song leaves the screen. */
    val clipPreRollSeconds: Double = 3.0,
    val clipTailSeconds: Double = 3.0,
    /** Longest song recording kept. */
    val clipMaxSeconds: Double = 600.0,
    /** A slide that goes up this long after its music started counts as late. */
    val lateSlideSeconds: Double = 20.0,
    /** How long a song just taken off screen is kept from being suggested back. */
    val suppressSeconds: Double = 120.0,
) {
    val featuresPerSecond: Double get() = sampleRate.toDouble() / hopSize / framesPerFeature
}
