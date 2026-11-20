package com.familysafety.app.data.usage

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import com.familysafety.app.data.db.LocalAppUsageEntity
import com.familysafety.app.data.db.LocalDaySummaryEntity
import com.familysafety.app.data.db.LocalUsageDao
import com.familysafety.app.data.network.ApiService
import com.familysafety.app.data.network.UsageAppBody
import com.familysafety.app.data.network.UsageDayBody
import com.familysafety.app.data.network.UsageSyncBody
import com.familysafety.app.data.network.safeApiCall
import com.familysafety.app.domain.logic.UsageAggregator
import com.familysafety.app.domain.logic.UsageEvent
import com.familysafety.app.domain.model.AppError
import com.familysafety.app.domain.model.AppResult
import com.familysafety.app.domain.model.AppUsage
import com.familysafety.app.domain.model.DailyUsage
import com.familysafety.app.domain.model.map
import com.familysafety.app.domain.repository.UsageCollector
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Collects foreground app usage via [UsageStatsManager.queryEvents]. Requires the user to grant
 * "Usage access" (PACKAGE_USAGE_STATS) in system Settings; nothing is collected without it.
 *
 * Platform limitations (documented in ARCHITECTURE.md):
 *  - Data is computed on demand from the system's event log; it is not a live feed. WorkManager runs the
 *    sync at most every 15 minutes and may be deferred by Doze / App Standby buckets.
 *  - The OS keeps detailed events for a limited period (typically about a week), so only recent days are collected.
 *  - Only foreground activity time per app is available. In-app content is NOT and cannot be observed.
 */
class AndroidUsageCollector(
    private val context: Context,
    private val dao: LocalUsageDao,
    private val api: ApiService,
    private val io: CoroutineDispatcher,
    private val clock: Clock = Clock.systemDefaultZone(),
) : UsageCollector {

    private val usm get() = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager

    override fun hasUsageAccess(): Boolean {
        val ops = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    override suspend fun collect(days: Int): List<DailyUsage> = withContext(io) {
        if (!hasUsageAccess()) return@withContext emptyList()
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(clock.withZone(zone))
        val start = today.minusDays((days - 1).toLong()).atStartOfDay(zone).toInstant().toEpochMilli()
        val now = clock.millis()
        val events = readEvents(start, now)
        val excluded = excludedPackages()
        val aggregator = UsageAggregator(zone)
        val sessions = aggregator.sessions(events.filter { it.packageName !in excluded || it.type != UsageEvent.Type.FOREGROUND && it.type != UsageEvent.Type.BACKGROUND }, start, now, now)
        val result = aggregator.aggregateByDay(sessions, ::labelFor)
        // Days with no activity are still stored so the summary shows an explicit zero.
        val byDate = result.associateBy { it.date }
        val all = (0 until days).map { today.minusDays(it.toLong()) }.map { d -> byDate[d] ?: DailyUsage(d, 0, null, null, null, emptyList()) }
        all.forEach { day ->
            dao.replaceDay(
                LocalDaySummaryEntity(day.date.toString(), day.totalForegroundMs, day.firstActivityAt?.toEpochMilli(),
                    day.lastActivityAt?.toEpochMilli(), now, null),
                day.apps.map { LocalAppUsageEntity(day.date.toString(), it.packageName, it.appLabel, it.foregroundMs, it.launchCount, it.lastUsedAt?.toEpochMilli()) },
            )
        }
        dao.pruneApps(today.minusDays(30).toString())
        dao.pruneSummaries(today.minusDays(30).toString())
        all
    }

    override suspend fun syncNow(): AppResult<Int> {
        if (!hasUsageAccess()) return AppResult.Failure(AppError.Validation("Usage access has not been granted on this device."))
        val days = collect(7)
        val body = UsageSyncBody(
            timezone = ZoneId.systemDefault().id,
            days = days.map { d ->
                UsageDayBody(d.date.toString(), d.firstActivityAt?.toString(), d.lastActivityAt?.toString(),
                    d.apps.take(500).map { UsageAppBody(it.packageName, it.appLabel?.take(255), it.foregroundMs, it.launchCount, it.lastUsedAt?.toString()) })
            },
        )
        return safeApiCall { api.syncUsage(body) }.map {
            dao.markSynced(days.map { d -> d.date.toString() }, clock.millis())
            it.acceptedRecords
        }
    }

    override fun observeLocalToday(): Flow<DailyUsage?> {
        val today = LocalDate.now(clock.withZone(ZoneId.systemDefault())).toString()
        return combine(dao.observeSummary(today), dao.observeAppsFor(today)) { s, apps ->
            s?.let {
                DailyUsage(LocalDate.parse(it.date), it.totalForegroundMs, it.firstActivityAtMs?.let(Instant::ofEpochMilli),
                    it.lastActivityAtMs?.let(Instant::ofEpochMilli), null,
                    apps.map { a -> AppUsage(a.packageName, a.appLabel, a.foregroundMs, a.launchCount, a.lastUsedAtMs?.let(Instant::ofEpochMilli)) })
            }
        }
    }

    private fun readEvents(start: Long, end: Long): List<UsageEvent> {
        val out = ArrayList<UsageEvent>(4096)
        val events = usm.queryEvents(start, end) ?: return out
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            val type = mapType(e.eventType) ?: continue
            out += UsageEvent(e.packageName ?: continue, e.timeStamp, type)
        }
        return out
    }

    /** The launcher and System UI are not "apps the child used". */
    private fun excludedPackages(): Set<String> {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val launcher = context.packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
        return setOfNotNull(launcher, "com.android.systemui")
    }

    private fun labelFor(pkg: String): String? = try {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    companion object {
        /** Maps UsageEvents.Event types to the platform-independent model (ACTIVITY_RESUMED=1, ACTIVITY_PAUSED=2). */
        fun mapType(eventType: Int): UsageEvent.Type? = when (eventType) {
            UsageEvents.Event.ACTIVITY_RESUMED -> UsageEvent.Type.FOREGROUND
            UsageEvents.Event.ACTIVITY_PAUSED -> UsageEvent.Type.BACKGROUND
            UsageEvents.Event.SCREEN_NON_INTERACTIVE -> UsageEvent.Type.SCREEN_OFF
            UsageEvents.Event.DEVICE_SHUTDOWN -> UsageEvent.Type.DEVICE_SHUTDOWN
            else -> null
        }
    }
}
