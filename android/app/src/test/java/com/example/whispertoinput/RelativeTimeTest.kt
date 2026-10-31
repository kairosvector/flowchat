package com.example.whispertoinput

import com.example.whispertoinput.keyboard.RelativeTime
import org.junit.Assert.assertEquals
import org.junit.Test

/** Round 11a: the relative time on a history row. */
class RelativeTimeTest {
    private val now = 1_730_368_000_000L
    private fun ago(ms: Long) = RelativeTime.of(now, now - ms)

    @Test
    fun lessThanAMinuteIsJustNow() {
        assertEquals(RelativeTime.JustNow, ago(0))
        assertEquals(RelativeTime.JustNow, ago(59_999))
        assertEquals(RelativeTime.JustNow, RelativeTime.of(now, now + 5_000))
    }

    @Test
    fun minutesAreWholeMinutes() {
        assertEquals(RelativeTime.MinutesAgo(1), ago(60_000))
        assertEquals(RelativeTime.MinutesAgo(59), ago(59 * 60_000L + 59_000))
    }

    @Test
    fun hoursAreWholeHoursUpToADay() {
        assertEquals(RelativeTime.HoursAgo(1), ago(60 * 60_000L))
        assertEquals(RelativeTime.HoursAgo(23), ago(23 * 3_600_000L + 59 * 60_000L))
    }

    @Test
    fun betweenOneAndTwoDaysIsYesterdayAndOlderIsADate() {
        assertEquals(RelativeTime.Yesterday, ago(24 * 3_600_000L))
        assertEquals(RelativeTime.Yesterday, ago(47 * 3_600_000L))
        assertEquals(RelativeTime.Older, ago(48 * 3_600_000L))
    }
}
