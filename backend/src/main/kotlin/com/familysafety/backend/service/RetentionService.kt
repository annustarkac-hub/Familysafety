package com.familysafety.backend.service

import com.familysafety.backend.config.AppProperties
import com.familysafety.backend.repository.AppUsageRecordRepository
import com.familysafety.backend.repository.AuditLogRepository
import com.familysafety.backend.repository.AuthenticationSessionRepository
import com.familysafety.backend.repository.DailyUsageSummaryRepository
import com.familysafety.backend.repository.PairingTokenRepository
import com.familysafety.backend.repository.SafetyAlertRepository
import com.familysafety.backend.repository.SafetySettingsRepository
import com.familysafety.backend.repository.ScreenSharingSessionRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.LocalDate

/** Enforces data-retention limits (see DATABASE_SCHEMA.md "Retention policies"). Runs daily. */
@Service
class RetentionService(
    private val settings: SafetySettingsRepository,
    private val records: AppUsageRecordRepository,
    private val summaries: DailyUsageSummaryRepository,
    private val pairingTokens: PairingTokenRepository,
    private val authSessions: AuthenticationSessionRepository,
    private val screenSessions: ScreenSharingSessionRepository,
    private val alerts: SafetyAlertRepository,
    private val auditLogs: AuditLogRepository,
    private val props: AppProperties,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "\${familysafety.retention.cron:0 17 3 * * *}", zone = "UTC")
    @Transactional
    fun purge(): Map<String, Int> {
        val now = clock.instant()
        val today = LocalDate.now(clock)
        var usage = 0
        var daily = 0
        settings.findAll().forEach { s ->
            val cutoff = today.minusDays(s.usageRetentionDays.toLong())
            usage += records.deleteOlderThan(s.deviceId, cutoff)
            daily += summaries.deleteOlderThan(s.deviceId, cutoff)
        }
        val result = mapOf(
            "appUsageRecords" to usage,
            "dailySummaries" to daily,
            "pairingTokens" to pairingTokens.deleteExpiredBefore(now.minus(Duration.ofDays(1))),
            "authSessions" to authSessions.deleteExpiredBefore(now.minus(Duration.ofDays(props.retention.expiredAuthSessionDays))),
            "screenSessions" to screenSessions.deleteEndedBefore(now.minus(Duration.ofDays(props.retention.screenSessionDays))),
            "safetyAlerts" to alerts.deleteCreatedBefore(now.minus(Duration.ofDays(props.retention.alertDays))),
            "auditLogs" to auditLogs.deleteCreatedBefore(now.minus(Duration.ofDays(props.retention.auditLogDays))),
        )
        log.info("Retention purge completed: {}", result)
        return result
    }
}
