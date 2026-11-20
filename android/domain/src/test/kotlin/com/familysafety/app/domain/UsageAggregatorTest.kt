package com.familysafety.app.domain

import com.familysafety.app.domain.logic.UsageAggregator
import com.familysafety.app.domain.logic.UsageEvent
import com.familysafety.app.domain.logic.UsageEvent.Type.BACKGROUND
import com.familysafety.app.domain.logic.UsageEvent.Type.FOREGROUND
import com.familysafety.app.domain.logic.UsageEvent.Type.SCREEN_OFF
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class UsageAggregatorTest {
    private val zone = ZoneOffset.UTC
    private val agg = UsageAggregator(zone)
    private val day = LocalDate.of(2026, 3, 10)
    private val dayStart = day.atStartOfDay(zone).toInstant().toEpochMilli()
    private fun t(h: Int, m: Int = 0, s: Int = 0) = dayStart + ((h * 60L + m) * 60 + s) * 1000
    private fun fg(pkg: String, at: Long) = UsageEvent(pkg, at, FOREGROUND)
    private fun bg(pkg: String, at: Long) = UsageEvent(pkg, at, BACKGROUND)
    private val end = t(23, 59, 59)

    @Test
    fun `simple foreground background pairs produce sessions`() {
        val s = agg.sessions(listOf(fg("a", t(8)), bg("a", t(8, 30)), fg("b", t(9)), bg("b", t(9, 10))), dayStart, end, end)
        assertEquals(listOf(30 * 60_000L, 10 * 60_000L), s.map { it.durationMs })
    }

    @Test
    fun `switching apps without background event closes the previous session`() {
        val s = agg.sessions(listOf(fg("a", t(8)), fg("b", t(8, 15)), bg("b", t(8, 20))), dayStart, end, end)
        assertEquals(listOf("a" to 15 * 60_000L, "b" to 5 * 60_000L), s.map { it.packageName to it.durationMs })
    }

    @Test
    fun `activity transitions within one app merge into a single launch`() {
        val events = listOf(fg("a", t(8)), bg("a", t(8, 5)), fg("a", t(8, 5, 1)), bg("a", t(8, 10)))
        val days = agg.aggregateByDay(agg.sessions(events, dayStart, end, end))
        assertEquals(1, days.single().apps.single().launchCount)
        assertEquals(10 * 60_000L, days.single().totalForegroundMs)
    }

    @Test
    fun `screen off ends the session`() {
        val s = agg.sessions(listOf(fg("a", t(8)), UsageEvent("android", t(8, 1), SCREEN_OFF), bg("a", t(9))), dayStart, end, end)
        assertEquals(60_000L, s.single().durationMs)
    }

    @Test
    fun `app already in foreground at window start is counted from the window start`() {
        val s = agg.sessions(listOf(bg("a", t(0, 20))), dayStart, end, end)
        assertEquals(20 * 60_000L, s.single().durationMs)
    }

    @Test
    fun `open session is closed at now`() {
        val now = t(10, 30)
        val s = agg.sessions(listOf(fg("a", t(10))), dayStart, end, now)
        assertEquals(30 * 60_000L, s.single().durationMs)
    }

    @Test
    fun `sessions crossing midnight are split across days`() {
        val nextDayEnd = t(23, 59) + 86_400_000
        val s = agg.sessions(listOf(fg("a", t(23, 30)), bg("a", t(23, 30) + 3_600_000)), dayStart, nextDayEnd, nextDayEnd)
        val days = agg.aggregateByDay(s)
        assertEquals(listOf(day, day.plusDays(1)), days.map { it.date })
        assertEquals(listOf(30 * 60_000L, 30 * 60_000L), days.map { it.totalForegroundMs })
        assertEquals(listOf(1, 0), days.map { it.apps.single().launchCount })
    }

    @Test
    fun `apps are ranked by time and labelled`() {
        val events = listOf(fg("a", t(8)), bg("a", t(8, 1)), fg("b", t(9)), bg("b", t(9, 30)), fg("a", t(10)), bg("a", t(10, 2)))
        val d = agg.aggregateByDay(agg.sessions(events, dayStart, end, end)) { it.uppercase() }.single()
        assertEquals(listOf("b", "a"), d.apps.map { it.packageName })
        assertEquals("B", d.apps[0].appLabel)
        assertEquals(2, d.apps[1].launchCount)
        assertEquals(t(8), d.firstActivityAt!!.toEpochMilli())
        assertEquals(t(10, 2), d.lastActivityAt!!.toEpochMilli())
    }

    @Test
    fun `events outside the window are ignored`() {
        val s = agg.sessions(listOf(fg("a", dayStart - 10_000), bg("a", dayStart - 5_000), fg("b", t(1)), bg("b", t(2))), dayStart, end, end)
        assertEquals(listOf("b"), s.map { it.packageName })
    }
}
