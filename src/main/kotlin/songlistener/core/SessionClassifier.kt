package songlistener.core

import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDateTime

/**
 * Whether an STT session was a real service, ported from STT's `stt/session_cleanup.py` `classify`
 * with the same slots and thresholds, so both tools reach the same verdict:
 *  - `service`  — starts in a service window and ran at least 90 minutes;
 *  - `fragment` — in a window but shorter (a restart or false start);
 *  - `unusual`  — outside every window but with 300+ transcript lines (a wedding, a gathering);
 *  - `test`     — anything else, and any generated demo session.
 */
object SessionClassifier {
    data class Slot(val day: DayOfWeek, val hour: Int, val minute: Int) {
        val label: String get() = "${day.name.take(3).lowercase().replaceFirstChar { it.uppercase() }}@%02d:%02d".format(hour, minute)
    }

    data class Verdict(val kind: String, val why: String)

    val DEFAULT_SLOTS = listOf(
        Slot(DayOfWeek.SUNDAY, 10, 0),
        Slot(DayOfWeek.SUNDAY, 18, 0),
        Slot(DayOfWeek.WEDNESDAY, 19, 0),
    )
    const val SERVICE_MINUTES = 90.0
    const val LEAD_MINUTES = 120.0
    const val SPAN_MINUTES = 150.0
    const val SUBSTANTIAL_ROWS = 300

    /** The service window [startedAt] falls in, if any: from 2 h before a slot to 2.5 h after. */
    fun inWindow(startedAt: LocalDateTime, slots: List<Slot> = DEFAULT_SLOTS): Slot? = slots.firstOrNull { slot ->
        if (startedAt.dayOfWeek != slot.day) return@firstOrNull false
        val slotAt = startedAt.withHour(slot.hour).withMinute(slot.minute).withSecond(0).withNano(0)
        val minutes = Duration.between(slotAt, startedAt).seconds / 60.0
        minutes in -LEAD_MINUTES..SPAN_MINUTES
    }

    fun classify(startedAt: LocalDateTime, minutes: Double, rows: Int, synthetic: Boolean = false, slots: List<Slot> = DEFAULT_SLOTS): Verdict {
        if (synthetic) return Verdict("test", "generated demo session")
        val slot = inWindow(startedAt, slots)
            ?: return if (rows >= SUBSTANTIAL_ROWS) Verdict("unusual", "no service scheduled, but $rows lines over %.0f min".format(minutes))
            else Verdict("test", "outside every service window")
        return if (minutes >= SERVICE_MINUTES) Verdict("service", "${slot.label} service, %.0f min".format(minutes))
        else Verdict("fragment", "${slot.label} window but only %.0f min".format(minutes))
    }
}
