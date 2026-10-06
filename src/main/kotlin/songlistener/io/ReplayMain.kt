package songlistener.io

import songlistener.core.Config
import songlistener.core.Replay
import songlistener.core.ReplayRow
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * `./gradlew replay [-PsuggestScore=0.75 ...]`: leave-one-out over every saved song recording.
 * Prints a row per recording and a summary, and writes the rows to reports/replay-<time>.csv.
 */
fun main() {
    val data = File(System.getProperty("user.home"), ".songlistener")
    val config = overrides(Config())
    val originFilter = System.getProperty("songlistener.origin")?.takeIf { it.isNotBlank() }
    val clips = ClipStore(File(data, "clips")).loadAll()
        .filter { originFilter == null || it.second.labels.origin == originFilter }
        .map { (file, clip) -> file.nameWithoutExtension to clip }
    if (clips.isEmpty()) {
        println("No song recordings yet in ${File(data, "clips")}" + (originFilter?.let { " with origin $it" } ?: "") + ".")
        return
    }
    println("Replaying ${clips.size} recording(s)" + (originFilter?.let { " ($it only)" } ?: "") + ": " +
        "quick ${config.queryFrames / config.featuresPerSecond}s ≥${config.suggestScore}, " +
        "steady ${config.steadyFrames / config.featuresPerSecond}s ≥${config.steadyScore}, " +
        "wrong-slide margin ${config.wrongSlideMargin} min ${config.wrongSlideMinScore}, cohort=${config.cohort}, feature=${config.feature}, transpose=${config.transpose}")
    val (rows, s) = Replay.run(clips, config)

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
    val csv = File(data, "reports/replay-${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss"))}.csv")
    csv.parentFile.mkdirs()
    csv.writeText(toCsv(rows))
    println("Rows written to $csv")
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
