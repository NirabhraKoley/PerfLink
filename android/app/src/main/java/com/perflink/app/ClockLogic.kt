package com.perflink.app

import java.time.DayOfWeek
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

// Pure time / calendar helpers for the Clock page (no Android classes, so they are unit-tested on the JVM).

/** Stopwatch reading with hundredths: "MM:SS.hh", or "H:MM:SS.hh" from one hour on. */
fun stopwatchText(ms: Long): String {
    val t = ms.coerceAtLeast(0)
    val hund = (t / 10) % 100
    val s = (t / 1000) % 60
    val m = (t / 60_000) % 60
    val h = t / 3_600_000
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d.%02d", h, m, s, hund)
    else String.format(Locale.US, "%02d:%02d.%02d", m, s, hund)
}

/** Countdown reading, whole seconds rounded up (it shows 00:01 until the timer really ends):
 *  "MM:SS", or "H:MM:SS" from one hour on. */
fun timerText(ms: Long): String {
    val secs = (ms.coerceAtLeast(0) + 999) / 1000
    val h = secs / 3600; val m = secs / 60 % 60; val s = secs % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.US, "%02d:%02d", m, s)
}

/** "07:05" on a 24-hour clock, "7:05" on a 12-hour one (0 and 12 o'clock read 12). */
fun digitalTime(hour: Int, minute: Int, use24: Boolean): String =
    if (use24) String.format(Locale.US, "%02d:%02d", hour, minute)
    else String.format(Locale.US, "%d:%02d", (hour % 12).let { if (it == 0) 12 else it }, minute)

fun amPm(hour: Int) = if (hour < 12) "AM" else "PM"

/** Clock hand angles in degrees, clockwise from 12. */
data class HandAngles(val hour: Float, val minute: Float, val second: Float)

fun handAngles(hour: Int, minute: Int, second: Int): HandAngles = HandAngles(
    hour = ((hour % 12) + minute / 60f + second / 3600f) * 30f,
    minute = (minute + second / 60f) * 6f,
    second = second * 6f,
)

/** Hours, minutes and seconds of a duration. */
fun hms(ms: Long): Triple<Int, Int, Int> {
    val secs = ms.coerceAtLeast(0) / 1000
    return Triple((secs / 3600).toInt(), (secs / 60 % 60).toInt(), (secs % 60).toInt())
}

fun hmsToMs(h: Int, m: Int, s: Int): Long = ((h * 3600L) + m * 60L + s) * 1000L

const val TIMER_MAX_HOURS = 23

/** Timer setter: step one field (0 = hours, 1 = minutes, 2 = seconds) by [delta]; each field wraps on its own
 *  (59 → 0 seconds without touching the minutes, like a phone's timer wheel). */
fun stepTimer(ms: Long, field: Int, delta: Int): Long {
    var (h, m, s) = hms(ms)
    when (field) {
        0 -> h = Math.floorMod(h + delta, TIMER_MAX_HOURS + 1)
        1 -> m = Math.floorMod(m + delta, 60)
        else -> s = Math.floorMod(s + delta, 60)
    }
    return hmsToMs(h, m, s)
}

/** Each lap's own time, from the stopwatch totals recorded at each lap. */
fun lapSplits(totals: List<Long>): List<Long> = totals.mapIndexed { i, t -> t - (if (i == 0) 0L else totals[i - 1]) }

/** The days of [month] in weeks starting on [firstDay]: null for the blanks before day 1 and after the last
 *  day, padded to whole weeks (so it has 4 to 6 rows of 7). */
fun monthGrid(month: YearMonth, firstDay: DayOfWeek): List<Int?> {
    val lead = Math.floorMod(month.atDay(1).dayOfWeek.value - firstDay.value, 7)
    val cells: List<Int?> = List<Int?>(lead) { null } + (1..month.lengthOfMonth())
    val trail = Math.floorMod(-cells.size, 7)
    return cells + List<Int?>(trail) { null }
}

/** Column headings for [monthGrid], e.g. "Mo Tu We Th Fr Sa Su". */
fun weekdayHeaders(firstDay: DayOfWeek, locale: Locale = Locale.getDefault()): List<String> =
    (0L until 7L).map { firstDay.plus(it).getDisplayName(TextStyle.SHORT, locale).take(2) }

/** The weekdays of [monthGrid]'s columns, in order. */
fun weekdayColumns(firstDay: DayOfWeek): List<DayOfWeek> = (0L until 7L).map { firstDay.plus(it) }
