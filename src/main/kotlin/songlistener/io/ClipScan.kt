package songlistener.io

import songlistener.core.ClipLabels
import songlistener.core.ClipLevel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** The clipping scan's rows and per-day summary, shared by `./gradlew scanClipping` and the app. */
object ClipScan {
    data class Row(val name: String, val labels: ClipLabels, val level: ClipLevel) {
        val day: LocalDate get() = Instant.ofEpochMilli(labels.startedAt).atZone(ZoneId.systemDefault()).toLocalDate()
    }

    data class Day(val day: LocalDate, val recordings: Int, val clean: Int, val light: Int, val heavy: Int, val loudestPeakDb: Double, val medianRmsDb: Double)

    fun days(rows: List<Row>): List<Day> = rows.groupBy { it.day }.toSortedMap().map { (day, r) ->
        Day(
            day, r.size, r.count { it.level.severity == 0 }, r.count { it.level.severity == 1 }, r.count { it.level.severity == 2 },
            r.maxOf { it.level.peakDb }, r.map { it.level.rmsDb }.sorted()[r.size / 2],
        )
    }

    /** Worst first. */
    fun worstFirst(rows: List<Row>): List<Row> =
        rows.sortedWith(compareByDescending<Row> { it.level.severity }.thenByDescending { it.level.clippedFraction })

    fun severityText(severity: Int) = when (severity) { 2 -> "HEAVY"; 1 -> "light"; else -> "clean" }

    fun mmss(s: Int?) = s?.let { "%d:%02d".format(it / 60, it % 60) } ?: "-"
}
