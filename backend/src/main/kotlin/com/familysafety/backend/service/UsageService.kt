package com.familysafety.backend.service

import com.familysafety.backend.audit.AuditAction
import com.familysafety.backend.audit.AuditService
import com.familysafety.backend.common.BadRequestException
import com.familysafety.backend.common.ConflictException
import com.familysafety.backend.model.AlertType
import com.familysafety.backend.model.AppUsageRecord
import com.familysafety.backend.model.DailyUsageSummary
import com.familysafety.backend.model.SafetySettings
import com.familysafety.backend.realtime.ConnectionRegistry
import com.familysafety.backend.realtime.EventTypes
import com.familysafety.backend.realtime.RealtimeEvent
import com.familysafety.backend.realtime.afterCommit
import com.familysafety.backend.repository.AppUsageRecordRepository
import com.familysafety.backend.repository.DailyUsageSummaryRepository
import com.familysafety.backend.repository.DeviceRepository
import com.familysafety.backend.repository.SafetySettingsRepository
import com.familysafety.backend.repository.UserRepository
import com.familysafety.backend.security.AuthPrincipal
import com.familysafety.backend.web.AppRankingDto
import com.familysafety.backend.web.AppRankingResponse
import com.familysafety.backend.web.AppUsageDto
import com.familysafety.backend.web.DailyUsageResponse
import com.familysafety.backend.web.DayTotalDto
import com.familysafety.backend.web.PeriodUsageResponse
import com.familysafety.backend.web.UsageDayRequest
import com.familysafety.backend.web.UsageSyncRequest
import com.familysafety.backend.web.UsageSyncResponse
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID

const val MS_PER_DAY = 86_400_000L

@Service
class UsageService(
    private val records: AppUsageRecordRepository,
    private val summaries: DailyUsageSummaryRepository,
    private val settings: SafetySettingsRepository,
    private val devices: DeviceRepository,
    private val users: UserRepository,
    private val safety: SafetyService,
    private val access: AccessPolicy,
    private val registry: ConnectionRegistry,
    private val audit: AuditService,
    private val clock: Clock,
) {
    /**
     * Snapshot semantics: for each submitted day the client sends the complete per-app totals computed from
     * UsageStatsManager; the server replaces that day's records. Re-sending the same payload is idempotent,
     * so retries (WorkManager) and replays cannot inflate totals.
     */
    @Transactional
    fun sync(p: AuthPrincipal, req: UsageSyncRequest): UsageSyncResponse {
        val deviceId = access.childDeviceId(p)
        val pairing = access.childActivePairing(p)
            ?: throw ConflictException("DEVICE_NOT_PAIRED", "Usage data is only accepted from paired devices")
        val zone = req.timezone?.let { runCatching { ZoneId.of(it) }.getOrNull() }
        val today = LocalDate.now(zone ?: ZoneId.of("UTC"))
        if (req.days.map { it.date }.toSet().size != req.days.size) throw BadRequestException("DUPLICATE_DAYS", "Each date may appear only once")

        var recordCount = 0
        val deviceSettings = settings.findByDeviceId(deviceId)
        for (day in req.days) {
            validateDay(day, today)
            records.deleteByDeviceIdAndUsageDate(deviceId, day.date)
            records.flush()
            val merged = day.apps.groupBy { it.packageName }.map { (pkg, list) ->
                AppUsageRecord(deviceId = deviceId, usageDate = day.date, packageName = pkg, appLabel = list.firstNotNullOfOrNull { it.appLabel },
                    foregroundMs = list.sumOf { it.foregroundMs }.coerceAtMost(MS_PER_DAY), launchCount = list.sumOf { it.launchCount },
                    lastUsedAt = list.mapNotNull { it.lastUsedAt }.maxOrNull())
            }.filter { it.foregroundMs > 0 || it.launchCount > 0 }
            records.saveAll(merged)
            recordCount += merged.size
            val total = merged.sumOf { it.foregroundMs }
            if (total > MS_PER_DAY) throw BadRequestException("INVALID_USAGE_TOTAL", "Total usage for ${day.date} exceeds 24 hours")

            val summary = summaries.findByDeviceIdAndUsageDate(deviceId, day.date)
                ?: DailyUsageSummary(deviceId = deviceId, usageDate = day.date, totalForegroundMs = 0, appCount = 0,
                    firstActivityAt = null, lastActivityAt = null, timezone = null)
            summary.totalForegroundMs = total
            summary.appCount = merged.size
            summary.firstActivityAt = day.firstActivityAt
            summary.lastActivityAt = day.lastActivityAt
            summary.timezone = zone?.id
            summaries.save(summary)
            deviceSettings?.let { evaluateRiskAlerts(pairing.familyId, deviceId, p.userId, day, total, it, zone) }
        }
        devices.findById(deviceId).ifPresent { it.lastSeenAt = clock.instant(); devices.save(it) }
        audit.record(AuditAction.USAGE_SYNC, p.userId, deviceId, "device", deviceId,
            details = mapOf("days" to req.days.size, "records" to recordCount))
        val dates = req.days.map { it.date.toString() }
        afterCommit {
            registry.sendToFamilyParents(pairing.familyId, RealtimeEvent(EventTypes.USAGE_SYNCED, mapOf("deviceId" to deviceId, "dates" to dates)))
        }
        return UsageSyncResponse(req.days.size, recordCount, clock.instant())
    }

    private fun validateDay(day: UsageDayRequest, today: LocalDate) {
        if (day.date.isAfter(today.plusDays(1))) throw BadRequestException("INVALID_USAGE_DATE", "Usage date ${day.date} is in the future")
        if (day.date.isBefore(today.minusDays(35))) throw BadRequestException("INVALID_USAGE_DATE", "Usage date ${day.date} is too old")
        if (day.firstActivityAt != null && day.lastActivityAt != null && day.firstActivityAt.isAfter(day.lastActivityAt)) {
            throw BadRequestException("INVALID_ACTIVITY_RANGE", "firstActivityAt must not be after lastActivityAt")
        }
    }

    private fun evaluateRiskAlerts(familyId: UUID, deviceId: UUID, childUserId: UUID, day: UsageDayRequest, totalMs: Long, s: SafetySettings, zone: ZoneId?) {
        val childName by lazy { users.findById(childUserId).map { it.displayName }.orElse("Your child") }
        val limit = s.dailyLimitMinutes
        if (s.limitAlertsEnabled && limit != null && totalMs > limit * 60_000L) {
            safety.raiseSystemAlert(familyId, deviceId, AlertType.SCREEN_TIME_LIMIT_EXCEEDED,
                "$childName exceeded the daily screen-time limit",
                "Used ${totalMs / 60_000} min on ${day.date} (limit $limit min).",
                dedupeKey = "limit:$deviceId:${day.date}")
        }
        val start = s.bedtimeStart
        val end = s.bedtimeEnd
        val last = day.lastActivityAt
        if (s.bedtimeAlertsEnabled && start != null && end != null && last != null && zone != null) {
            val local = last.atZone(zone).toLocalTime()
            if (inWindow(local, start, end)) {
                safety.raiseSystemAlert(familyId, deviceId, AlertType.BEDTIME_USAGE, "$childName used their device during bedtime",
                    "Activity recorded at ${local.truncatedTo(ChronoUnit.MINUTES)} on ${day.date}.",
                    dedupeKey = "bedtime:$deviceId:${day.date}")
            }
        }
    }

    @Transactional(readOnly = true)
    fun daily(p: AuthPrincipal, deviceId: UUID, date: LocalDate): DailyUsageResponse {
        access.requireReadAccessToDevice(p, deviceId)
        val summary = summaries.findByDeviceIdAndUsageDate(deviceId, date)
        val apps = records.findByDeviceIdAndUsageDate(deviceId, date).sortedByDescending { it.foregroundMs }
            .map { AppUsageDto(it.packageName, it.appLabel, it.foregroundMs, it.launchCount, it.lastUsedAt) }
        return DailyUsageResponse(deviceId, date, summary?.totalForegroundMs ?: 0, summary?.appCount ?: 0,
            summary?.firstActivityAt, summary?.lastActivityAt, settings.findByDeviceId(deviceId)?.dailyLimitMinutes, apps)
    }

    @Transactional(readOnly = true)
    fun weekly(p: AuthPrincipal, deviceId: UUID, endDate: LocalDate): PeriodUsageResponse =
        period(p, deviceId, endDate.minusDays(6), endDate)

    @Transactional(readOnly = true)
    fun monthly(p: AuthPrincipal, deviceId: UUID, month: YearMonth): PeriodUsageResponse =
        period(p, deviceId, month.atDay(1), month.atEndOfMonth())

    private fun period(p: AuthPrincipal, deviceId: UUID, from: LocalDate, to: LocalDate): PeriodUsageResponse {
        access.requireReadAccessToDevice(p, deviceId)
        val byDate = summaries.findByDeviceIdAndUsageDateBetweenOrderByUsageDate(deviceId, from, to).associateBy { it.usageDate }
        val days = generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }
            .map { d -> DayTotalDto(d, byDate[d]?.totalForegroundMs ?: 0, byDate[d]?.appCount ?: 0) }.toList()
        val total = days.sumOf { it.totalForegroundMs }
        val withData = byDate.size
        return PeriodUsageResponse(deviceId, from, to, total, if (withData == 0) 0 else total / withData, withData, days,
            rank(deviceId, from, to, 10))
    }

    @Transactional(readOnly = true)
    fun applications(p: AuthPrincipal, deviceId: UUID, from: LocalDate, to: LocalDate, limit: Int): AppRankingResponse {
        if (from.isAfter(to)) throw BadRequestException("INVALID_RANGE", "from must not be after to")
        if (ChronoUnit.DAYS.between(from, to) > 92) throw BadRequestException("INVALID_RANGE", "Range must not exceed 93 days")
        access.requireReadAccessToDevice(p, deviceId)
        return AppRankingResponse(deviceId, from, to, rank(deviceId, from, to, limit.coerceIn(1, 200)))
    }

    private fun rank(deviceId: UUID, from: LocalDate, to: LocalDate, limit: Int): List<AppRankingDto> =
        UsageRanking.rank(records.findByDeviceIdAndUsageDateBetween(deviceId, from, to), limit)
}

/** Pure aggregation helper (unit-tested separately). */
object UsageRanking {
    fun rank(records: List<AppUsageRecord>, limit: Int): List<AppRankingDto> =
        records.groupBy { it.packageName }.map { (pkg, list) ->
            AppRankingDto(0, pkg, list.mapNotNull { it.appLabel }.lastOrNull(), list.sumOf { it.foregroundMs }, list.sumOf { it.launchCount },
                list.mapNotNull { it.lastUsedAt }.maxOrNull(), list.map { it.usageDate }.distinct().size)
        }.sortedWith(compareByDescending<AppRankingDto> { it.totalForegroundMs }.thenBy { it.packageName })
            .take(limit)
            .mapIndexed { i, dto -> dto.copy(rank = i + 1) }
}

/** True when [t] lies inside [start, end), handling windows that wrap past midnight (e.g. 21:00–07:00). */
fun inWindow(t: LocalTime, start: LocalTime, end: LocalTime): Boolean =
    if (start.isBefore(end)) !t.isBefore(start) && t.isBefore(end) else !t.isBefore(start) || t.isBefore(end)

