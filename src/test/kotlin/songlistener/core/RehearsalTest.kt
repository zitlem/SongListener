package songlistener.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RehearsalTest {
    private val min = 60_000L
    private val window = 20 * min

    /**
     * Replays a timeline through a detector: `take(key, start, end)` keeps a take, `hop(at, s)`
     * drops one that had [s] seconds of music. Returns the takes that ended up rehearsals.
     */
    private class Timeline(window: Long) {
        val detector = RehearsalDetector(window, minHops = 2)
        val rehearsals = mutableSetOf<String>()
        val all = mutableListOf<String>()
        fun take(key: String, start: Long, end: Long) {
            detector.takeStarted(key, start)
            all += "$key@$end"
            if (detector.takeSaved(key, end)) rehearsals += "$key@$end"
        }
        fun hop(at: Long, songSeconds: Double = 10.0, key: String = "hop") {
            detector.takeStarted(key, at - 5_000)
            detector.takeDiscarded(songSeconds, at).forEach { rehearsals += "${it.songKey}@${it.recordedAt}" }
        }
    }

    @Test
    fun `a service is not a rehearsal, and the songs practised after it are`() {
        // Shaped like the Wednesday of 2026-10-28: five hymns, one at a time, then a rehearsal of
        // the next Sunday's songs with the operator jumping between them.
        val t = Timeline(window)
        t.take("h1", 0, 2 * min); t.take("h2", 8 * min, 11 * min); t.take("h3", 14 * min, 17 * min)
        t.hop(18 * min, songSeconds = 0.0)   // a slide put up and taken down with nothing played
        t.take("h4", 47 * min, 50 * min); t.take("h5", 50 * min, 53 * min)
        t.take("a", 63 * min, 71 * min)      // first song of the rehearsal: nothing hopped yet
        t.hop(71 * min)
        t.take("a", 71 * min, 75 * min); t.take("b", 75 * min, 77 * min)
        repeat(4) { t.hop(77 * min + it * 1_000) }
        t.take("c", 78 * min, 84 * min); t.take("b", 84 * min, 88 * min)
        t.hop(88 * min)
        t.take("d", 88 * min, 91 * min)
        assertEquals(t.all.filter { it[0] in "abcd" }.toSet(), t.rehearsals)
    }

    @Test
    fun `one stray hop in a service marks nothing`() {
        val t = Timeline(window)
        t.take("a", 0, 3 * min)
        t.hop(4 * min, songSeconds = 3.0)
        t.take("b", 4 * min, 7 * min)
        assertTrue(t.rehearsals.isEmpty())
    }

    @Test
    fun `hops a window away from a take do not reach it`() {
        val t = Timeline(window)
        t.take("a", 0, 3 * min)
        t.hop(3 * min + window + min); t.hop(3 * min + window + 2 * min)
        assertTrue(t.rehearsals.isEmpty())
        // ...but they do reach a take that starts within a window of them.
        t.take("b", 3 * min + window + 2 * min + window - min, 3 * min + 2 * window + 5 * min)
        assertEquals(setOf("b@${3 * min + 2 * window + 5 * min}"), t.rehearsals)
    }

    private fun take(at: Long, rehearsal: Boolean = false, byHand: Boolean = false) =
        SongTake("1|A", "A", at, emptyList(), emptyList(), rehearsal = rehearsal, markedByHand = byHand)

    @Test
    fun `rehearsal takes are replaced once the song has a settled real take`() {
        val rehearsals = listOf(take(1 * min, rehearsal = true), take(2 * min, rehearsal = true))
        val real = take(100 * min)
        // Kept just now: the detector could still find it was a rehearsal too.
        assertTrue(RehearsalPolicy.toReplace(rehearsals + real, now = 101 * min, window).isEmpty())
        assertEquals(rehearsals, RehearsalPolicy.toReplace(rehearsals + real, now = 100 * min + window, window))
        // Marked real by hand: settled at once.
        assertEquals(rehearsals, RehearsalPolicy.toReplace(rehearsals + take(100 * min, byHand = true), now = 101 * min, window))
    }

    @Test
    fun `a song with only rehearsal takes keeps them`() {
        val takes = listOf(take(1 * min, rehearsal = true), take(2 * min, rehearsal = true, byHand = true))
        assertTrue(RehearsalPolicy.toReplace(takes, now = 1_000 * min, window).isEmpty())
        assertFalse(RehearsalPolicy.isReal(takes[1], 1_000 * min, window))
    }
}
