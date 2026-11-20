package com.familysafety.app.domain.model

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

enum class Role { PARENT, CHILD }

data class UserProfile(
    val id: String,
    val email: String,
    val displayName: String,
    val role: Role,
    val familyId: String?,
    val familyName: String?,
)

/** Locally known authentication state. Tokens themselves never leave the data layer. */
sealed interface AuthState {
    data object Loading : AuthState
    data object SignedOut : AuthState
    data class SignedIn(val user: UserProfile, val deviceId: String, val paired: Boolean) : AuthState
}

data class DeviceSummary(
    val id: String,
    val deviceName: String,
    val manufacturer: String?,
    val model: String?,
    val osVersion: String?,
    val appVersion: String?,
    val status: String,
    val lastSeenAt: Instant?,
    val ownerUserId: String,
    val ownerDisplayName: String,
    val pairingId: String?,
    val pairedAt: Instant?,
    val familyName: String?,
    val latestUsageDate: LocalDate?,
    val latestUsageForegroundMs: Long?,
    val activeScreenShareSessionId: String?,
)

data class PairingCode(val code: String, val expiresAt: Instant, val childLabel: String?)

data class PairingResult(val familyName: String, val parents: List<String>, val pairedAt: Instant)

data class AppUsage(
    val packageName: String,
    val appLabel: String?,
    val foregroundMs: Long,
    val launchCount: Int,
    val lastUsedAt: Instant?,
)

data class DailyUsage(
    val date: LocalDate,
    val totalForegroundMs: Long,
    val firstActivityAt: Instant?,
    val lastActivityAt: Instant?,
    val dailyLimitMinutes: Int?,
    val apps: List<AppUsage>,
)

data class DayTotal(val date: LocalDate, val totalForegroundMs: Long, val appCount: Int)

data class AppRanking(
    val rank: Int,
    val packageName: String,
    val appLabel: String?,
    val totalForegroundMs: Long,
    val launchCount: Int,
    val lastUsedAt: Instant?,
    val daysUsed: Int,
)

data class PeriodUsage(
    val startDate: LocalDate,
    val endDate: LocalDate,
    val totalForegroundMs: Long,
    val averageDailyMs: Long,
    val daysWithData: Int,
    val days: List<DayTotal>,
    val topApps: List<AppRanking>,
)

enum class ScreenShareStatus {
    REQUESTED, APPROVED, ACTIVE, REJECTED, STOPPED, EXPIRED, FAILED;

    val isOpen get() = this == REQUESTED || this == APPROVED || this == ACTIVE
}

data class IceServer(val urls: List<String>, val username: String?, val credential: String?)

data class ScreenShareSession(
    val id: String,
    val deviceId: String,
    val status: ScreenShareStatus,
    val requestedByDisplayName: String?,
    val requestedAt: Instant,
    val requestExpiresAt: Instant,
    val startedAt: Instant?,
    val endedAt: Instant?,
    val maxEndAt: Instant?,
    val endReason: String?,
    val iceServers: List<IceServer>,
)

enum class StopReason { USER_STOPPED, CAPTURE_FAILED, CONNECTION_FAILED, PERMISSION_DENIED }

enum class AlertSeverity { INFO, LOW, MEDIUM, HIGH, CRITICAL }

enum class AlertType {
    CHECK_IN, CHECK_IN_REQUEST, INCIDENT_REPORT, HELP_REQUEST, USAGE_ACCESS_REVOKED, DISCONNECT_REQUEST,
    SCREEN_TIME_LIMIT_EXCEEDED, BEDTIME_USAGE, DEVICE_PAIRED, DEVICE_REVOKED, SCREEN_SHARE_REJECTED, UNKNOWN,
}

data class SafetyAlert(
    val id: String,
    val deviceId: String?,
    val type: AlertType,
    val severity: AlertSeverity,
    val audience: String,
    val title: String,
    val message: String?,
    val createdAt: Instant,
    val acknowledgedAt: Instant?,
)

data class SafetySettings(
    val deviceId: String,
    val dailyLimitMinutes: Int?,
    val bedtimeStart: LocalTime?,
    val bedtimeEnd: LocalTime?,
    val limitAlertsEnabled: Boolean,
    val bedtimeAlertsEnabled: Boolean,
    val screenSharingEnabled: Boolean,
    val usageRetentionDays: Int,
)

/** Realtime notifications pushed by the backend over the WebSocket. */
sealed interface RealtimeEvent {
    data class ScreenShareRequested(val session: ScreenShareSession) : RealtimeEvent
    data class ScreenShareUpdated(val session: ScreenShareSession) : RealtimeEvent
    data class Signal(val sessionId: String, val kind: String, val sdp: String?, val candidate: String?, val sdpMid: String?, val sdpMLineIndex: Int?) : RealtimeEvent
    data class PairingCompleted(val deviceId: String, val childDisplayName: String) : RealtimeEvent
    data class DeviceRevoked(val deviceId: String) : RealtimeEvent
    data class Alert(val alert: SafetyAlert) : RealtimeEvent
    data class UsageSynced(val deviceId: String) : RealtimeEvent
    data object SettingsUpdated : RealtimeEvent
    data class Error(val code: String) : RealtimeEvent
}

enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED }
