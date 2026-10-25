package songlistener.io

import songlistener.core.ClipDetector
import songlistener.core.ClipLevel
import songlistener.core.Config
import java.io.File

/**
 * `./gradlew scanClipping`: checks every saved song recording for clipping (a signal too hot for
 * the input) and prints them worst first, then a summary per day. Uses the level the app saved
 * in a recording's labels when it has one. Reads only; changes nothing.
 */
fun main() {
    val data = File(System.getProperty("user.home"), ".songlistener")
    val config = Config()
    val rows = ClipStore(File(data, "clips")).labels().mapNotNull { (wav, labels) ->
        val level = labels.level ?: runCatching {
            ClipLevel.of(ClipDetector.analyze(ClipStore.readWav(wav), labels.sampleRate), config)
        }.getOrNull() ?: return@mapNotNull null
        ClipScan.Row(wav.nameWithoutExtension, labels, level)
    }
    if (rows.isEmpty()) {
        println("No song recordings yet in ${File(data, "clips")}.")
        return
    }
    println("%-26s %-6s %-6s %-6s %-7s %-9s %-11s %-6s %s".format(
        "recording", "origin", "length", "peak", "rms", "fullscale", "clipped s", "worst", "severity"))
    for (r in ClipScan.worstFirst(rows)) {
        val l = r.level
        println("%-26s %-6s %-6s %-6s %-7s %-9s %-11s %-6s %s".format(
            r.name.take(26), r.labels.origin ?: "-", ClipScan.mmss(r.labels.endSeconds.toInt()),
            "%.1f".format(l.peakDb), "%.1f".format(l.rmsDb), "%.3f%%".format(l.fullScale * 100),
            "%d (%.0f%%)".format(l.clippedSeconds, l.clippedFraction * 100), ClipScan.mmss(l.worstSecond),
            ClipScan.severityText(l.severity)))
    }
    println()
    println("Per day (peak/rms in dBFS):")
    for (d in ClipScan.days(rows)) {
        println("  %s  %2d recording(s)  clean %2d  light %2d  heavy %2d  loudest peak %.1f  median rms %.1f".format(
            d.day, d.recordings, d.clean, d.light, d.heavy, d.loudestPeakDb, d.medianRmsDb))
    }
    println()
    println("Total ${rows.size}: clean ${rows.count { it.level.severity == 0 }}, light ${rows.count { it.level.severity == 1 }}, heavy ${rows.count { it.level.severity == 2 }}")
}
