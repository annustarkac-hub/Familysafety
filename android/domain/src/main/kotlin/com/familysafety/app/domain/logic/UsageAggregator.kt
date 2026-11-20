package com.familysafety.app.domain.logic

import com.familysafety.app.domain.model.AppUsage
import com.familysafety.app.domain.model.DailyUsage
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Platform-independent representation of an android.app.usage.UsageEvents.Event. */
data class UsageEvent(val packageName: String, val timestampMs: Long, val type: Type) {
    enum class Type { FOREGROUND, BACKGROUND, SCREEN_OFF, DEVICE_SHUTDOWN }
}

data class UsageSession(val packageName: String, val startMs: Long, val endMs: Long) {
    val durationMs get() = endMs - startMs
}

/**
 * Converts the raw, per-activity event stream returned by UsageStatsManager.queryEvents into foreground
 * sessions and then per-day per-app totals.
 *
 * Rules:
 *  - FOREGROUND of package B while A is foreground implicitly ends A (event streams can miss PAUSED events).
 *  - Consecutive sessions of the same package separated by < [mergeGapMs] (activity-to-activity transitions
 *    inside one app) are merged and count as one launch.
 *  - SCREEN_OFF / DEVICE_SHUTDOWN end the current session.
 *  - A BACKGROUND event with no open session means the app was already in front when the window started:
 *    the session is counted from [windowStartMs].
 *  - A session still open at the end is closed at min(windowEndMs, nowMs).
 *  - Sessions crossing midnight (in [zone]) are split between days.
 */
class UsageAggregator(private val zone: ZoneId, private val mergeGapMs: Long = 2_000) {

    fun sessions(events: List<UsageEvent>, windowStartMs: Long, windowEndMs: Long, nowMs: Long): List<UsageSession> {
        val result = mutableListOf<UsageSession>()
        var currentPkg: String? = null
        var currentStart = 0L
        var sawAnyEvent = false

        fun close(at: Long) {
            val pkg = currentPkg ?: return
            val end = at.coerceAtMost(windowEndMs)
            if (end > currentStart) result += UsageSession(pkg, currentStart, end)
            currentPkg = null
        }

        for (e in events.filter { it.timestampMs in windowStartMs until windowEndMs }.sortedBy { it.timestampMs }) {
            when (e.type) {
                UsageEvent.Type.FOREGROUND -> {
                    if (currentPkg != null && currentPkg != e.packageName) close(e.timestampMs)
                    if (currentPkg == null) {
                        currentPkg = e.packageName
                        currentStart = e.timestampMs
                    }
                }
                UsageEvent.Type.BACKGROUND -> {
                    if (currentPkg == e.packageName) {
                        close(e.timestampMs)
                    } else if (currentPkg == null && !sawAnyEvent) {
                        // App was in the foreground before the query window began.
                        currentPkg = e.packageName
                        currentStart = windowStartMs
                        close(e.timestampMs)
                    }
                }
                UsageEvent.Type.SCREEN_OFF, UsageEvent.Type.DEVICE_SHUTDOWN -> close(e.timestampMs)
            }
            sawAnyEvent = true
        }
        close(minOf(windowEndMs, nowMs))
        return merge(result)
    }

    private fun merge(sessions: List<UsageSession>): List<UsageSession> {
        val out = mutableListOf<UsageSession>()
        for (s in sessions) {
            val last = out.lastOrNull()
            if (last != null && last.packageName == s.packageName && s.startMs - last.endMs < mergeGapMs) {
                out[out.lastIndex] = last.copy(endMs = maxOf(last.endMs, s.endMs))
            } else out += s
        }
        return out
    }

    /** Splits sessions at local midnight and aggregates per day and package. */
    fun aggregateByDay(sessions: List<UsageSession>, labels: (String) -> String? = { null }): List<DailyUsage> {
        data class Acc(var ms: Long = 0, var launches: Int = 0, var last: Long = 0)

        val perDay = sortedMapOf<LocalDate, MutableMap<String, Acc>>()
        val firstLast = mutableMapOf<LocalDate, LongArray>()
        for (s in sessions) {
            var start = s.startMs
            var first = true
            while (start < s.endMs) {
                val day = Instant.ofEpochMilli(start).atZone(zone).toLocalDate()
                val nextMidnight = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                val end = minOf(s.endMs, nextMidnight)
                val acc = perDay.getOrPut(day) { mutableMapOf() }.getOrPut(s.packageName) { Acc() }
                acc.ms += end - start
                if (first) acc.launches += 1
                acc.last = maxOf(acc.last, end)
                val fl = firstLast.getOrPut(day) { longArrayOf(Long.MAX_VALUE, Long.MIN_VALUE) }
                fl[0] = minOf(fl[0], start)
                fl[1] = maxOf(fl[1], end)
                first = false
                start = end
            }
        }
        return perDay.map { (day, apps) ->
            val list = apps.map { (pkg, a) -> AppUsage(pkg, labels(pkg), a.ms, a.launches, Instant.ofEpochMilli(a.last)) }
                .sortedWith(compareByDescending<AppUsage> { it.foregroundMs }.thenBy { it.packageName })
            val fl = firstLast.getValue(day)
            DailyUsage(day, list.sumOf { it.foregroundMs }, Instant.ofEpochMilli(fl[0]), Instant.ofEpochMilli(fl[1]), null, list)
        }
    }
}
