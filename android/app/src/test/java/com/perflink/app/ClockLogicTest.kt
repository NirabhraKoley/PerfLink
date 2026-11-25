package com.perflink.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.DayOfWeek
import java.time.YearMonth
import java.util.Locale

class ClockLogicTest {
    @Test fun stopwatchShowsHundredths() {
        assertEquals("00:00.00", stopwatchText(0))
        assertEquals("00:01.23", stopwatchText(1_234))
        assertEquals("01:05.09", stopwatchText(65_099))
        assertEquals("59:59.99", stopwatchText(3_599_999))
        assertEquals("1:00:00.00", stopwatchText(3_600_000))
        assertEquals("00:00.00", stopwatchText(-50))
    }

    @Test fun timerRoundsUpToWholeSeconds() {
        assertEquals("00:00", timerText(0))
        assertEquals("00:01", timerText(1))
        assertEquals("00:01", timerText(1_000))
        assertEquals("05:00", timerText(300_000))
        assertEquals("05:00", timerText(299_001))
        assertEquals("1:00:00", timerText(3_600_000))
        assertEquals("1:30:05", timerText(hmsToMs(1, 30, 5)))
    }

    @Test fun digitalTime12And24() {
        assertEquals("00:05", digitalTime(0, 5, true))
        assertEquals("12:05", digitalTime(0, 5, false))
        assertEquals("12:30", digitalTime(12, 30, false))
        assertEquals("7:45", digitalTime(19, 45, false))
        assertEquals("19:45", digitalTime(19, 45, true))
        assertEquals("AM", amPm(0)); assertEquals("PM", amPm(12)); assertEquals("PM", amPm(23))
    }

    @Test fun handAnglesFromTime() {
        assertEquals(HandAngles(0f, 0f, 0f), handAngles(12, 0, 0))
        assertEquals(90f, handAngles(3, 0, 0).hour, 0.001f)
        assertEquals(105f, handAngles(15, 30, 0).hour, 0.001f)   // half past three
        assertEquals(180f, handAngles(15, 30, 0).minute, 0.001f)
        assertEquals(270f, handAngles(0, 0, 45).second, 0.001f)
        assertEquals(3f, handAngles(0, 0, 30).minute, 0.001f)     // the minute hand creeps with the seconds
    }

    @Test fun timerFieldsStepAndWrap() {
        val five = hmsToMs(0, 5, 0)
        assertEquals(hmsToMs(0, 6, 0), stepTimer(five, 1, 1))
        assertEquals(hmsToMs(0, 5, 59), stepTimer(five, 2, -1))        // seconds wrap, minutes untouched
        assertEquals(hmsToMs(1, 0, 0), stepTimer(hmsToMs(1, 59, 0), 1, 1))   // minutes wrap 59 -> 0, hours untouched
        assertEquals(hmsToMs(TIMER_MAX_HOURS, 5, 0), stepTimer(five, 0, -1))
        assertEquals(Triple(1, 2, 3), hms(hmsToMs(1, 2, 3)))
    }

    @Test fun lapSplitsAreDifferences() {
        assertEquals(emptyList<Long>(), lapSplits(emptyList()))
        assertEquals(listOf(1_000L, 2_500L, 500L), lapSplits(listOf(1_000L, 3_500L, 4_000L)))
    }

    @Test fun monthGridNovember2026MondayFirst() {
        // 1 Nov 2026 is a Sunday: six blanks before it when weeks start on Monday
        val g = monthGrid(YearMonth.of(2026, 11), DayOfWeek.MONDAY)
        assertEquals(0, g.size % 7)
        assertEquals(42, g.size)
        (0 until 6).forEach { assertNull(g[it]) }
        assertEquals(1, g[6])
        assertEquals(30, g.filterNotNull().last())
        assertEquals(30, g.count { it != null })
    }

    @Test fun monthGridSundayFirst() {
        val g = monthGrid(YearMonth.of(2026, 11), DayOfWeek.SUNDAY)
        assertEquals(1, g[0])          // starts right on day 1
        assertEquals(35, g.size)
        // Feb 2026 starts on a Sunday and has exactly four weeks
        assertEquals(28, monthGrid(YearMonth.of(2026, 2), DayOfWeek.SUNDAY).size)
        // leap year
        assertEquals(29, monthGrid(YearMonth.of(2028, 2), DayOfWeek.MONDAY).count { it != null })
    }

    @Test fun weekdayHeadersFollowFirstDay() {
        assertEquals(listOf("Mo", "Tu", "We", "Th", "Fr", "Sa", "Su"), weekdayHeaders(DayOfWeek.MONDAY, Locale.ENGLISH))
        assertEquals("Su", weekdayHeaders(DayOfWeek.SUNDAY, Locale.ENGLISH).first())
        assertEquals(DayOfWeek.SATURDAY, weekdayColumns(DayOfWeek.SUNDAY).last())
    }
}
