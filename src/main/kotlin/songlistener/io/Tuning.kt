package songlistener.io

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import songlistener.core.Clip
import songlistener.core.Config
import songlistener.core.Replay
import songlistener.core.ReplaySummary
import java.util.concurrent.atomic.AtomicInteger

/**
 * The Tuning window's recognition test: the leave-one-out replay run with the current matching
 * settings and each alternative side by side, so the best can be picked on this church's audio.
 */
object Tuning {
    data class Variant(val name: String, val config: Config)
    data class Result(val variant: Variant, val summary: ReplaySummary)

    /** The current settings, the defaults, and each improvement on its own and together. */
    fun variants(current: Config): List<Variant> {
        val d = Config()
        return listOf(
            Variant("Current settings", current),
            Variant("Defaults", current.copy(keepTakes = d.keepTakes, maxTakesPerSong = d.maxTakesPerSong, takeAgreement = d.takeAgreement, introMode = d.introMode, confirmedParts = d.confirmedParts)),
            Variant("Intro: keep all held music", current.copy(introMode = "keep")),
            Variant("Intro: only what sounds like the song", current.copy(introMode = "match")),
            Variant("Intro: none", current.copy(introMode = "drop")),
            Variant("Agreement 0.5", current.copy(takeAgreement = 0.5)),
            Variant("Keep best takes", current.copy(keepTakes = "best")),
            Variant("Keep best + agreement 0.5", current.copy(keepTakes = "best", takeAgreement = 0.5)),
            Variant("Confirmed parts only", current.copy(confirmedParts = true)),
        ).distinctBy { listOf(it.config.keepTakes, it.config.maxTakesPerSong, it.config.takeAgreement, it.config.introMode, it.config.confirmedParts) }
    }

    /** Runs every variant over [clips]; [onProgress] gets 0..1. Cancel by cancelling the caller. */
    suspend fun run(clips: List<Pair<String, Clip>>, variants: List<Variant>, onProgress: (Double) -> Unit): List<Result> = coroutineScope {
        val job = coroutineContext.job
        // Learning doesn't depend on the matching settings: learn once, test every variant on it.
        val steps = clips.size * (1 + variants.size)
        val done = AtomicInteger()
        fun step() { job.ensureActive(); onProgress(done.incrementAndGet().toDouble() / steps) }
        val learned = clips.map { (_, clip) -> async(Dispatchers.Default) { Replay.learn(clip, variants.first().config).also { step() } } }.awaitAll()
        variants.map { v ->
            async(Dispatchers.Default) {
                var last = clips.size
                val (_, summary) = Replay.run(clips, v.config, learned) { at, _ ->
                    while (last < at) { last++; step() }
                }
                while (last < clips.size * 2) { last++; step() }
                Result(v, summary)
            }
        }.awaitAll()
    }
}
