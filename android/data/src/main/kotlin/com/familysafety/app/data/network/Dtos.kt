package com.familysafety.app.data.network

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class DeviceInfoBody(
    val installationId: String,
    val deviceName: String,
    val manufacturer: String? = null,
    val model: String? = null,
    val osVersion: String? = null,
    val appVersion: String? = null,
)

@Serializable data class RegisterBody(val email: String, val password: String, val displayName: String, val role: String, val device: DeviceInfoBody)
@Serializable data class LoginBody(val email: String, val password: String, val device: DeviceInfoBody)
@Serializable data class RefreshBody(val refreshToken: String)

@Serializable
data class UserDto(
    val id: String,
    val email: String,
    val displayName: String,
    val role: String,
    val familyId: String? = null,
    val familyName: String? = null,
)

@Serializable
data class TokenResponseDto(
    val accessToken: String,
    val accessTokenExpiresAt: String,
    val refreshToken: String,
    val refreshTokenExpiresAt: String,
    val tokenType: String = "Bearer",
    val user: UserDto,
    val deviceId: String,
)

@Serializable data class MeDto(val user: UserDto, val deviceId: String? = null, val paired: Boolean = false)

@Serializable data class CreatePairingBody(val childLabel: String? = null)
@Serializable data class CreatePairingDto(val pairingTokenId: String, val code: String, val expiresAt: String, val childLabel: String? = null)
@Serializable data class AcceptPairingBody(val code: String)
@Serializable
data class AcceptPairingDto(
    val pairingId: String, val familyId: String, val familyName: String, val deviceId: String, val pairedAt: String,
    val parents: List<String> = emptyList(),
)

@Serializable
data class DeviceDto(
    val id: String,
    val deviceName: String,
    val manufacturer: String? = null,
    val model: String? = null,
    val osVersion: String? = null,
    val appVersion: String? = null,
    val status: String,
    val lastSeenAt: String? = null,
    val ownerUserId: String,
    val ownerDisplayName: String,
    val pairingId: String? = null,
    val pairingStatus: String? = null,
    val pairedAt: String? = null,
    val familyName: String? = null,
    val latestUsageDate: String? = null,
    val latestUsageForegroundMs: Long? = null,
    val activeScreenShareSessionId: String? = null,
)

@Serializable data class RevokeBody(val reason: String? = null)

@Serializable
data class UsageAppBody(val packageName: String, val appLabel: String? = null, val foregroundMs: Long, val launchCount: Int, val lastUsedAt: String? = null)

@Serializable
data class UsageDayBody(val date: String, val firstActivityAt: String? = null, val lastActivityAt: String? = null, val apps: List<UsageAppBody>)

@Serializable data class UsageSyncBody(val timezone: String?, val days: List<UsageDayBody>)
@Serializable data class UsageSyncDto(val acceptedDays: Int, val acceptedRecords: Int, val serverTime: String)

@Serializable
data class AppUsageDto(val packageName: String, val appLabel: String? = null, val foregroundMs: Long, val launchCount: Int, val lastUsedAt: String? = null)

@Serializable
data class DailyUsageDto(
    val deviceId: String, val date: String, val totalForegroundMs: Long, val appCount: Int,
    val firstActivityAt: String? = null, val lastActivityAt: String? = null, val dailyLimitMinutes: Int? = null,
    val apps: List<AppUsageDto> = emptyList(),
)

@Serializable data class DayTotalDto(val date: String, val totalForegroundMs: Long, val appCount: Int)

@Serializable
data class AppRankingDto(
    val rank: Int, val packageName: String, val appLabel: String? = null, val totalForegroundMs: Long, val launchCount: Int,
    val lastUsedAt: String? = null, val daysUsed: Int,
)

@Serializable
data class PeriodUsageDto(
    val deviceId: String, val startDate: String, val endDate: String, val totalForegroundMs: Long, val averageDailyMs: Long,
    val daysWithData: Int, val days: List<DayTotalDto>, val topApps: List<AppRankingDto> = emptyList(),
)

@Serializable data class AppRankingResponseDto(val deviceId: String, val from: String, val to: String, val applications: List<AppRankingDto>)

@Serializable data class ScreenShareRequestBody(val deviceId: String)
@Serializable data class SessionRefBody(val sessionId: String)
@Serializable data class StopBody(val sessionId: String, val reason: String? = null)
@Serializable data class IceServerDto(val urls: List<String>, val username: String? = null, val credential: String? = null)

@Serializable
data class ScreenShareSessionDto(
    val id: String, val deviceId: String, val status: String, val requestedByUserId: String? = null,
    val requestedByDisplayName: String? = null, val requestedAt: String, val requestExpiresAt: String,
    val approvedAt: String? = null, val startedAt: String? = null, val endedAt: String? = null, val maxEndAt: String? = null,
    val endReason: String? = null, val iceServers: List<IceServerDto>? = null,
)

@Serializable data class ScreenShareStatusDto(val active: ScreenShareSessionDto? = null, val recent: List<ScreenShareSessionDto> = emptyList())

@Serializable data class CreateAlertBody(val type: String, val message: String? = null, val deviceId: String? = null, val category: String? = null)

@Serializable
data class SafetyAlertDto(
    val id: String, val familyId: String, val deviceId: String? = null, val createdByUserId: String? = null, val type: String,
    val severity: String, val audience: String, val title: String, val message: String? = null, val createdAt: String,
    val acknowledgedAt: String? = null,
)

@Serializable data class SafetyAlertListDto(val alerts: List<SafetyAlertDto>, val nextBefore: String? = null)

@Serializable
data class SafetySettingsDto(
    val deviceId: String, val dailyLimitMinutes: Int? = null, val bedtimeStart: String? = null, val bedtimeEnd: String? = null,
    val limitAlertsEnabled: Boolean = true, val bedtimeAlertsEnabled: Boolean = false, val screenSharingEnabled: Boolean = true,
    val usageRetentionDays: Int = 90, val updatedAt: String? = null,
)

@Serializable data class ApiErrorDto(val code: String = "UNKNOWN", val message: String = "", val status: Int = 0)

/** Envelope of messages received on the realtime WebSocket. */
@Serializable data class RealtimeEnvelope(val type: String, val payload: kotlinx.serialization.json.JsonElement? = null)

@Serializable data class SignalEnvelope(val sessionId: String, val payload: JsonObject)
