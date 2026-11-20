package com.familysafety.backend.web

import com.familysafety.backend.model.AlertAudience
import com.familysafety.backend.model.AlertSeverity
import com.familysafety.backend.model.AlertType
import com.familysafety.backend.model.Role
import com.familysafety.backend.model.ScreenShareStatus
import com.fasterxml.jackson.annotation.JsonInclude
import jakarta.validation.Valid
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.PositiveOrZero
import jakarta.validation.constraints.Size
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

// ---------- Auth ----------

data class DeviceInfoRequest(
    @field:NotBlank @field:Pattern(regexp = "^[A-Za-z0-9-]{8,64}$", message = "must be 8-64 characters [A-Za-z0-9-]")
    val installationId: String,
    @field:NotBlank @field:Size(max = 80) val deviceName: String,
    @field:Size(max = 80) val manufacturer: String? = null,
    @field:Size(max = 80) val model: String? = null,
    @field:Size(max = 32) val osVersion: String? = null,
    @field:Size(max = 32) val appVersion: String? = null,
)

data class RegisterRequest(
    @field:NotBlank @field:Email @field:Size(max = 254) val email: String,
    @field:NotBlank @field:Size(min = 12, max = 128, message = "must be between 12 and 128 characters") val password: String,
    @field:NotBlank @field:Size(min = 1, max = 80) val displayName: String,
    @field:NotNull val role: Role,
    @field:Valid @field:NotNull val device: DeviceInfoRequest,
)

data class LoginRequest(
    @field:NotBlank @field:Email @field:Size(max = 254) val email: String,
    @field:NotBlank @field:Size(max = 128) val password: String,
    @field:Valid @field:NotNull val device: DeviceInfoRequest,
)

data class RefreshRequest(@field:NotBlank @field:Size(max = 128) val refreshToken: String)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class UserDto(
    val id: UUID,
    val email: String,
    val displayName: String,
    val role: Role,
    val familyId: UUID?,
    val familyName: String?,
)

data class TokenResponse(
    val accessToken: String,
    val accessTokenExpiresAt: Instant,
    val refreshToken: String,
    val refreshTokenExpiresAt: Instant,
    val tokenType: String = "Bearer",
    val user: UserDto,
    val deviceId: UUID,
)

data class MeResponse(val user: UserDto, val deviceId: UUID?, val paired: Boolean)

// ---------- Pairing / devices ----------

data class CreatePairingRequest(@field:Size(max = 80) val childLabel: String? = null)

data class CreatePairingResponse(val pairingTokenId: UUID, val code: String, val expiresAt: Instant, val childLabel: String?)

data class AcceptPairingRequest(
    @field:NotBlank @field:Size(min = 8, max = 16) val code: String,
)

data class AcceptPairingResponse(
    val pairingId: UUID,
    val familyId: UUID,
    val familyName: String,
    val deviceId: UUID,
    val pairedAt: Instant,
    val parents: List<String>,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class DeviceDto(
    val id: UUID,
    val deviceName: String,
    val manufacturer: String?,
    val model: String?,
    val osVersion: String?,
    val appVersion: String?,
    val status: String,
    val lastSeenAt: Instant?,
    val ownerUserId: UUID,
    val ownerDisplayName: String,
    val pairingId: UUID?,
    val pairingStatus: String?,
    val pairedAt: Instant?,
    val familyName: String?,
    val latestUsageDate: LocalDate?,
    val latestUsageForegroundMs: Long?,
    val activeScreenShareSessionId: UUID?,
)

data class RevokeDeviceRequest(@field:Size(max = 64) val reason: String? = null)

// ---------- Usage ----------

data class UsageAppRequest(
    @field:NotBlank @field:Size(max = 255) @field:Pattern(regexp = "^[A-Za-z0-9_.]+$", message = "must be a valid package name")
    val packageName: String,
    @field:Size(max = 255) val appLabel: String? = null,
    @field:PositiveOrZero @field:Max(86_400_000) val foregroundMs: Long,
    @field:PositiveOrZero @field:Max(100_000) val launchCount: Int = 0,
    val lastUsedAt: Instant? = null,
)

data class UsageDayRequest(
    @field:NotNull val date: LocalDate,
    val firstActivityAt: Instant? = null,
    val lastActivityAt: Instant? = null,
    @field:Valid @field:Size(max = 500) val apps: List<UsageAppRequest> = emptyList(),
)

data class UsageSyncRequest(
    @field:Size(max = 64) val timezone: String? = null,
    @field:Valid @field:NotNull @field:Size(min = 1, max = 31) val days: List<UsageDayRequest>,
)

data class UsageSyncResponse(val acceptedDays: Int, val acceptedRecords: Int, val serverTime: Instant)

data class AppUsageDto(
    val packageName: String,
    val appLabel: String?,
    val foregroundMs: Long,
    val launchCount: Int,
    val lastUsedAt: Instant?,
)

data class DailyUsageResponse(
    val deviceId: UUID,
    val date: LocalDate,
    val totalForegroundMs: Long,
    val appCount: Int,
    val firstActivityAt: Instant?,
    val lastActivityAt: Instant?,
    val dailyLimitMinutes: Int?,
    val apps: List<AppUsageDto>,
)

data class DayTotalDto(val date: LocalDate, val totalForegroundMs: Long, val appCount: Int)

data class PeriodUsageResponse(
    val deviceId: UUID,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val totalForegroundMs: Long,
    val averageDailyMs: Long,
    val daysWithData: Int,
    val days: List<DayTotalDto>,
    val topApps: List<AppRankingDto>,
)

data class AppRankingDto(
    val rank: Int,
    val packageName: String,
    val appLabel: String?,
    val totalForegroundMs: Long,
    val launchCount: Int,
    val lastUsedAt: Instant?,
    val daysUsed: Int,
)

data class AppRankingResponse(val deviceId: UUID, val from: LocalDate, val to: LocalDate, val applications: List<AppRankingDto>)

// ---------- Screen sharing ----------

data class ScreenShareRequest(@field:NotNull val deviceId: UUID)
data class ScreenShareSessionRef(@field:NotNull val sessionId: UUID)

enum class StopReason { USER_STOPPED, CAPTURE_FAILED, CONNECTION_FAILED, PERMISSION_DENIED }

data class ScreenShareStopRequest(@field:NotNull val sessionId: UUID, val reason: StopReason? = null)

data class IceServerDto(val urls: List<String>, val username: String? = null, val credential: String? = null)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ScreenShareSessionDto(
    val id: UUID,
    val deviceId: UUID,
    val status: ScreenShareStatus,
    val requestedByUserId: UUID?,
    val requestedByDisplayName: String?,
    val requestedAt: Instant,
    val requestExpiresAt: Instant,
    val approvedAt: Instant?,
    val startedAt: Instant?,
    val endedAt: Instant?,
    val maxEndAt: Instant?,
    val endReason: String?,
    val iceServers: List<IceServerDto>? = null,
)

data class ScreenShareStatusResponse(val active: ScreenShareSessionDto?, val recent: List<ScreenShareSessionDto>)

// ---------- Safety ----------

data class CreateAlertRequest(
    @field:NotNull val type: AlertType,
    @field:Size(max = 1000) val message: String? = null,
    /** Required for parent-created alerts (target child device); ignored for child-created alerts. */
    val deviceId: UUID? = null,
    @field:Size(max = 40) val category: String? = null,
)

data class SafetyAlertDto(
    val id: UUID,
    val familyId: UUID,
    val deviceId: UUID?,
    val createdByUserId: UUID?,
    val type: AlertType,
    val severity: AlertSeverity,
    val audience: AlertAudience,
    val title: String,
    val message: String?,
    val createdAt: Instant,
    val acknowledgedAt: Instant?,
)

data class SafetyAlertListResponse(val alerts: List<SafetyAlertDto>, val nextBefore: Instant?)

data class SafetySettingsDto(
    val deviceId: UUID,
    val dailyLimitMinutes: Int?,
    val bedtimeStart: LocalTime?,
    val bedtimeEnd: LocalTime?,
    val limitAlertsEnabled: Boolean,
    val bedtimeAlertsEnabled: Boolean,
    val screenSharingEnabled: Boolean,
    val usageRetentionDays: Int,
    val updatedAt: Instant,
)

data class UpdateSafetySettingsRequest(
    @field:NotNull val deviceId: UUID,
    @field:Min(15) @field:Max(1440) val dailyLimitMinutes: Int? = null,
    val bedtimeStart: LocalTime? = null,
    val bedtimeEnd: LocalTime? = null,
    val limitAlertsEnabled: Boolean = true,
    val bedtimeAlertsEnabled: Boolean = false,
    val screenSharingEnabled: Boolean = true,
    @field:Min(7) @field:Max(365) val usageRetentionDays: Int = 90,
)
