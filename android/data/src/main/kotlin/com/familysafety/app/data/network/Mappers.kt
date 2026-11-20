package com.familysafety.app.data.network

import com.familysafety.app.data.db.DeviceEntity
import com.familysafety.app.domain.model.AlertSeverity
import com.familysafety.app.domain.model.AlertType
import com.familysafety.app.domain.model.AppRanking
import com.familysafety.app.domain.model.AppUsage
import com.familysafety.app.domain.model.DailyUsage
import com.familysafety.app.domain.model.DayTotal
import com.familysafety.app.domain.model.DeviceSummary
import com.familysafety.app.domain.model.IceServer
import com.familysafety.app.domain.model.PeriodUsage
import com.familysafety.app.domain.model.Role
import com.familysafety.app.domain.model.SafetyAlert
import com.familysafety.app.domain.model.SafetySettings
import com.familysafety.app.domain.model.ScreenShareSession
import com.familysafety.app.domain.model.ScreenShareStatus
import com.familysafety.app.domain.model.UserProfile
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

internal fun String?.toInstantOrNull(): Instant? = this?.let { runCatching { Instant.parse(it) }.getOrNull() }

fun UserDto.toDomain() = UserProfile(id, email, displayName, Role.valueOf(role), familyId, familyName)

fun DeviceDto.toEntity() = DeviceEntity(id, deviceName, manufacturer, model, osVersion, appVersion, status, lastSeenAt, ownerUserId,
    ownerDisplayName, pairingId, pairedAt, familyName, latestUsageDate, latestUsageForegroundMs, activeScreenShareSessionId)

fun DeviceEntity.toDomain() = DeviceSummary(id, deviceName, manufacturer, model, osVersion, appVersion, status, lastSeenAt.toInstantOrNull(),
    ownerUserId, ownerDisplayName, pairingId, pairedAt.toInstantOrNull(), familyName, latestUsageDate?.let(LocalDate::parse),
    latestUsageForegroundMs, activeScreenShareSessionId)

fun DeviceDto.toDomain() = toEntity().toDomain()

fun AppUsageDto.toDomain() = AppUsage(packageName, appLabel, foregroundMs, launchCount, lastUsedAt.toInstantOrNull())

fun DailyUsageDto.toDomain() = DailyUsage(LocalDate.parse(date), totalForegroundMs, firstActivityAt.toInstantOrNull(),
    lastActivityAt.toInstantOrNull(), dailyLimitMinutes, apps.map { it.toDomain() })

fun AppRankingDto.toDomain() = AppRanking(rank, packageName, appLabel, totalForegroundMs, launchCount, lastUsedAt.toInstantOrNull(), daysUsed)

fun PeriodUsageDto.toDomain() = PeriodUsage(LocalDate.parse(startDate), LocalDate.parse(endDate), totalForegroundMs, averageDailyMs,
    daysWithData, days.map { DayTotal(LocalDate.parse(it.date), it.totalForegroundMs, it.appCount) }, topApps.map { it.toDomain() })

fun ScreenShareSessionDto.toDomain() = ScreenShareSession(
    id = id, deviceId = deviceId,
    status = runCatching { ScreenShareStatus.valueOf(status) }.getOrDefault(ScreenShareStatus.FAILED),
    requestedByDisplayName = requestedByDisplayName,
    requestedAt = requestedAt.toInstantOrNull() ?: Instant.EPOCH,
    requestExpiresAt = requestExpiresAt.toInstantOrNull() ?: Instant.EPOCH,
    startedAt = startedAt.toInstantOrNull(), endedAt = endedAt.toInstantOrNull(), maxEndAt = maxEndAt.toInstantOrNull(),
    endReason = endReason,
    iceServers = iceServers.orEmpty().map { IceServer(it.urls, it.username, it.credential) },
)

fun SafetyAlertDto.toDomain() = SafetyAlert(
    id, deviceId, runCatching { AlertType.valueOf(type) }.getOrDefault(AlertType.UNKNOWN),
    runCatching { AlertSeverity.valueOf(severity) }.getOrDefault(AlertSeverity.INFO), audience, title, message,
    createdAt.toInstantOrNull() ?: Instant.EPOCH, acknowledgedAt.toInstantOrNull(),
)

fun SafetySettingsDto.toDomain() = SafetySettings(deviceId, dailyLimitMinutes, bedtimeStart?.let(LocalTime::parse), bedtimeEnd?.let(LocalTime::parse),
    limitAlertsEnabled, bedtimeAlertsEnabled, screenSharingEnabled, usageRetentionDays)

fun SafetySettings.toDto() = SafetySettingsDto(deviceId, dailyLimitMinutes, bedtimeStart?.toString(), bedtimeEnd?.toString(),
    limitAlertsEnabled, bedtimeAlertsEnabled, screenSharingEnabled, usageRetentionDays)
