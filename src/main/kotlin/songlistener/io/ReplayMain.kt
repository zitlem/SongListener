package songlistener.io

import songlistener.core.ClipDetector
import songlistener.core.Config
import songlistener.core.Replay
import songlistener.core.ReplayRow
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * `./gradlew replay [-PsuggestScore=0.75 ...]`: leave-one-out over every saved song recording.
 * `-Pclean=true` leaves out clipped recordings; `-PkeepTakes=best -PmaxTakesPerSong=2 -PtakeAgreement=0.5`
 * try the take selection and scoring.
 * Prints a row per recording and a summary, and writes the rows to reports/replay-<time>.csv.
 */
fun main() {
    val data = File(System.getProperty("user.home"), ".songlistener")
    val config = overrides(Config())
    val originFilter = System.getProperty("songlistener.origin")?.takeIf { it.isNotBlank() }
    val cleanOnly = System.getProperty("songlistener.clean")?.toBoolean() == true
    val clips = ClipStore(File(data, "clips")).loadAll()
        .filter { originFilter == null || it.second.labels.origin == originFilter }
        // Clean only: leave out every clipped recording, as tests and as what is learned.
        .filter { !cleanOnly || ClipDetector.analyze(it.second.samples, it.second.labels.sampleRate).severity(config) == 0 }
        .map { (file, clip) -> file.nameWithoutExtension to clip }
    if (clips.isEmpty()) {
        println("No song recordings yet in ${File(data, "clips")}" + (originFilter?.let { " with origin $it" } ?: "") + ".")
        return
    }
    println("Replaying ${clips.size} recording(s)" + (originFilter?.let { " ($it only)" } ?: "") + (if (cleanOnly) " (clean only)" else "") + ": " +
        "quick ${config.queryFrames / config.featuresPerSecond}s ≥${config.suggestScore}, " +
        "steady ${config.steadyFrames / config.featuresPerSecond}s ≥${config.steadyScore}, " +
        "wrong-slide margin ${config.wrongSlideMargin} min ${config.wrongSlideMinScore}, cohort=${config.cohort}, feature=${config.feature}, transpose=${config.transpose}, " +
        "takes ${config.maxTakesPerSong} keep=${config.keepTakes} agreement=${config.takeAgreement} intro=${config.introMode} confirmedParts=${config.confirmedParts}")
    val learned = clips.map { (_, clip) -> Replay.learn(clip, config) }
    val (rows, s) = Replay.run(clips, config, learned)

    println()
    println("%-24s %-6s %-30s %-5s %-12s %-12s %-6s %-6s %-5s %-6s %-5s".format(
        "recording", "origin", "song", "late", "hint", "suggested", "right", "wrong", "top1", "wrongS", "false"))
    fun outcome(right: Boolean?, lead: Double?) = when (right) {
        true -> "right" + (lead?.let { " %+.0fs".format(it) } ?: "")
        false -> "WRONG"
        null -> "never"
    }
    for (r in rows) {
        if (!r.testable) {
            println("%-24s %-6s %-30s only recording of this song".format(r.name.take(24), r.origin ?: "-", r.songLabel.take(30)))
            continue
        }
        println("%-24s %-6s %-30s %-5s %-12s %-12s %-6s %-6s %-5s %-6s %-5s".format(
            r.name.take(24), r.origin ?: "-", r.songLabel.take(30), "%.0fs".format(r.lateSeconds),
            outcome(r.firstHintRight, r.hintLeadSeconds), outcome(r.firstSuggestionRight, r.leadSeconds),
            r.bestRight?.let { "%.2f".format(it) } ?: "-",
            r.bestWrong?.let { "%.2f".format(it) } ?: "-",
            r.top1Rate?.let { "%.0f%%".format(it * 100) } ?: "-",
            when (r.warnedOnWrongSlide) { true -> "caught"; false -> "missed"; null -> "-" },
            if (r.falseAlarm) "YES" else "no",
        ))
    }
    println()
    println("Recordings: ${s.clips}, testable: ${s.testable} (\"lead\" = seconds before the slide went up)")
    if (s.testable > 0) {
        println("Quick hints:  right before the slide ${s.hintsRightBeforeLive}/${s.testable}, wrong ${s.wrongHints}")
        println("Suggestions:  right ${s.rightAtAll}/${s.testable}, before the slide ${s.rightBeforeLive}/${s.testable}, wrong ${s.wrongSuggestions}" +
            (s.medianLeadSeconds?.let { ", median lead %.0fs".format(it) } ?: ""))
        println("Late slides (≥${Replay.LATE_SECONDS.toInt()}s): caught before the slide ${s.lateCaught}/${s.lateTestable}")
        println("Wrong slides: caught ${s.wrongSlideCaught}/${s.wrongSlideTests}, false alarms with the right slide ${s.falseAlarms}/${s.testable}")
        println("While on screen, ranked first: ${s.meanTop1?.let { "%.0f%%".format(it * 100) } ?: "-"}")
    }
    printByClipping(clips, rows, config)
    printIntros(learned.filterNotNull(), config)
    val csv = File(data, "reports/replay-${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss"))}.csv")
    csv.parentFile.mkdirs()
    csv.writeText(toCsv(rows))
    println("Rows written to $csv")
}

/**
 * The same results split by clipping: is this recording clipped, and are the other recordings of
 * its song (what it was matched against) clipped. Shows whether clipped audio hurts recognition,
 * and whether clean audio still matches songs learned only from clipped recordings.
 */
private fun printByClipping(clips: List<Pair<String, songlistener.core.Clip>>, rows: List<ReplayRow>, config: Config) {
    val clipped = clips.associate { (name, clip) ->
        name to (ClipDetector.analyze(clip.samples, clip.labels.sampleRate).severity(config) > 0)
    }
    val songOf = clips.associate { (name, clip) -> name to clip.labels.songKey }
    fun others(r: ReplayRow): String {
        val o = clipped.filterKeys { it != r.name && songOf[it] == r.songKey }.values
        return when { o.all { it } -> "clipped"; o.none { it } -> "clean"; else -> "mixed" }
    }
    val groups = rows.filter { it.testable }.groupBy { (if (clipped[it.name] == true) "clipped" else "clean") to others(it) }
    if (groups.isEmpty()) return
    println()
    println("By clipping (this recording / its song's other recordings):")
    println("  %-20s %-4s %-12s %-6s %-6s %-9s %-9s".format("group", "n", "suggested", "wrong", "top1", "own song", "best other"))
    fun mean(xs: List<Double>) = if (xs.isEmpty()) "-" else "%.2f".format(xs.average())
    for ((k, g) in groups.toSortedMap(compareBy({ it.first }, { it.second }))) {
        println("  %-20s %-4d %-12s %-6d %-6s %-9s %-9s".format(
            "${k.first} / ${k.second}", g.size,
            "${g.count { it.firstSuggestionRight == true }}/${g.size}", g.sumOf { it.wrongSuggestions },
            g.mapNotNull { it.top1Rate }.let { if (it.isEmpty()) "-" else "%.0f%%".format(it.average() * 100) },
            mean(g.mapNotNull { it.bestRight }), mean(g.mapNotNull { it.bestWrong })))
    }
}

/** How much intro the takes hold, and how much of it [Config.introMode] `match` would keep. */
private fun printIntros(takes: List<songlistener.core.SongTake>, config: Config) {
    val withIntro = takes.filter { songlistener.core.IntroFilter.introFrames(it) > 0 }
    println()
    if (withIntro.isEmpty()) { println("Intros: none of the ${takes.size} takes has one."); return }
    fun sec(frames: Int) = frames / config.featuresPerSecond
    val match = config.copy(introMode = "match")
    val kept = withIntro.map { it to songlistener.core.IntroFilter.introFrames(songlistener.core.IntroFilter.apply(it, match)) }
    println("Intros: ${withIntro.size} of ${takes.size} takes have one, %.0f s in all; \"match\" keeps %.0f s (≥%.2f):".format(
        sec(withIntro.sumOf { songlistener.core.IntroFilter.introFrames(it) }), sec(kept.sumOf { it.second }), config.introMatchScore))
    for ((t, k) in kept) println("  %-34s intro %3.0f s  kept %3.0f s".format(t.songLabel.take(34), sec(songlistener.core.IntroFilter.introFrames(t)), sec(k)))
}

private fun overrides(c: Config): Config {
    fun prop(name: String) = System.getProperty("songlistener.$name")?.takeIf { it.isNotBlank() }
    return c.copy(
        suggestScore = prop("suggestScore")?.toDouble() ?: c.suggestScore,
        suggestMargin = prop("suggestMargin")?.toDouble() ?: c.suggestMargin,
        suggestStreak = prop("suggestStreak")?.toInt() ?: c.suggestStreak,
        queryFrames = prop("queryFrames")?.toInt() ?: c.queryFrames,
        musicDipToleranceSeconds = prop("musicDipToleranceSeconds")?.toDouble() ?: c.musicDipToleranceSeconds,
        tempoStretches = prop("tempoStretches")?.split(',')?.map { it.trim().toDouble() } ?: c.tempoStretches,
        feature = prop("feature") ?: c.feature,
        transpose = prop("transpose")?.toBoolean() ?: c.transpose,
        steadyFrames = prop("steadyFrames")?.toInt() ?: c.steadyFrames,
        steadyScore = prop("steadyScore")?.toDouble() ?: c.steadyScore,
        steadyMargin = prop("steadyMargin")?.toDouble() ?: c.steadyMargin,
        wrongSlideMargin = prop("wrongSlideMargin")?.toDouble() ?: c.wrongSlideMargin,
        wrongSlideMinScore = prop("wrongSlideMinScore")?.toDouble() ?: c.wrongSlideMinScore,
        cohort = prop("cohort")?.toBoolean() ?: c.cohort,
        framesPerFeature = prop("framesPerFeature")?.toInt() ?: c.framesPerFeature,
        maxTakesPerSong = prop("maxTakesPerSong")?.toInt() ?: c.maxTakesPerSong,
        keepTakes = prop("keepTakes") ?: c.keepTakes,
        takeAgreement = prop("takeAgreement")?.toDouble() ?: c.takeAgreement,
        introMode = prop("introMode") ?: c.introMode,
        introMatchScore = prop("introMatchScore")?.toDouble() ?: c.introMatchScore,
        confirmedParts = prop("confirmedParts")?.toBoolean() ?: c.confirmedParts,
        confirmScore = prop("confirmScore")?.toDouble() ?: c.confirmScore,
    )
}

internal fun toCsv(rows: List<ReplayRow>): String = buildString {
    appendLine("recording,origin,song,testable,late_s,first_hint,hint_right,hint_lead_s,wrong_hints,first_suggestion,first_right,lead_s,suggest_score,suggest_margin,suggest_section,wrong_suggestions,best_right,best_wrong,best_wrong_song,top1_rate,section_rate,wrong_slide_caught,false_alarm")
    fun q(s: String?) = s?.let { "\"" + it.replace("\"", "\"\"") + "\"" } ?: ""
    fun n(d: Double?) = d?.let { "%.3f".format(it) } ?: ""
    for (r in rows) {
        appendLine(listOf(
            q(r.name), r.origin ?: "", q(r.songLabel), r.testable.toString(), n(r.lateSeconds),
            q(r.firstHint), r.firstHintRight?.toString() ?: "", n(r.hintLeadSeconds), r.wrongHints.toString(),
            q(r.firstSuggestion), r.firstSuggestionRight?.toString() ?: "",
            n(r.leadSeconds), n(r.suggestScore), n(r.suggestMargin), r.suggestSection?.toString() ?: "", r.wrongSuggestions.toString(),
            n(r.bestRight), n(r.bestWrong), q(r.bestWrongSong), n(r.top1Rate), n(r.sectionRate),
            r.warnedOnWrongSlide?.toString() ?: "", r.falseAlarm.toString(),
        ).joinToString(","))
    }
}
