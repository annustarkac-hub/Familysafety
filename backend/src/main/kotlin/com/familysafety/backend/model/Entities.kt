package com.familysafety.backend.model

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.MappedSuperclass
import jakarta.persistence.PrePersist
import jakarta.persistence.PreUpdate
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

enum class Role { PARENT, CHILD }
enum class UserStatus { ACTIVE, DISABLED }
enum class DeviceStatus { ACTIVE, REVOKED }
enum class PairingStatus { ACTIVE, REVOKED }
enum class AuthSessionStatus { ACTIVE, ROTATED, REVOKED }
enum class AuditOutcome { SUCCESS, FAILURE, DENIED }
enum class AlertSeverity { INFO, LOW, MEDIUM, HIGH, CRITICAL }
enum class AlertAudience { PARENTS, CHILD }

enum class ScreenShareStatus {
    REQUESTED, APPROVED, ACTIVE, REJECTED, STOPPED, EXPIRED, FAILED;

    val isOpen: Boolean get() = this == REQUESTED || this == APPROVED || this == ACTIVE
}

/**
 * Alert types. [childCreatable] types may be raised by a child account, [parentCreatable] by a parent;
 * the remaining types are raised only by the server itself.
 */
enum class AlertType(val childCreatable: Boolean, val parentCreatable: Boolean, val defaultSeverity: AlertSeverity) {
    CHECK_IN(true, false, AlertSeverity.INFO),
    CHECK_IN_REQUEST(false, true, AlertSeverity.LOW),
    INCIDENT_REPORT(true, false, AlertSeverity.HIGH),
    HELP_REQUEST(true, false, AlertSeverity.CRITICAL),
    USAGE_ACCESS_REVOKED(true, false, AlertSeverity.MEDIUM),
    DISCONNECT_REQUEST(false, false, AlertSeverity.MEDIUM),
    SCREEN_TIME_LIMIT_EXCEEDED(false, false, AlertSeverity.MEDIUM),
    BEDTIME_USAGE(false, false, AlertSeverity.LOW),
    DEVICE_PAIRED(false, false, AlertSeverity.INFO),
    DEVICE_REVOKED(false, false, AlertSeverity.MEDIUM),
    SCREEN_SHARE_REJECTED(false, false, AlertSeverity.INFO),
}

@MappedSuperclass
abstract class Timestamped {
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now()

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()

    @PrePersist
    fun onCreate() {
        val now = Instant.now()
        createdAt = now
        updatedAt = now
    }

    @PreUpdate
    fun onUpdate() {
        updatedAt = Instant.now()
    }
}

@Entity
@Table(name = "users")
class User(
    @Id var id: UUID = UUID.randomUUID(),
    var email: String,
    @Column(name = "password_hash") var passwordHash: String,
    @Column(name = "display_name") var displayName: String,
    @Enumerated(EnumType.STRING) var role: Role,
    @Enumerated(EnumType.STRING) var status: UserStatus = UserStatus.ACTIVE,
    @Column(name = "failed_login_attempts") var failedLoginAttempts: Int = 0,
    @Column(name = "locked_until") var lockedUntil: Instant? = null,
) : Timestamped()

@Entity
@Table(name = "families")
class Family(
    @Id var id: UUID = UUID.randomUUID(),
    var name: String,
    @Column(name = "owner_user_id") var ownerUserId: UUID,
) : Timestamped()

@Entity
@Table(name = "family_members")
class FamilyMember(
    @Id var id: UUID = UUID.randomUUID(),
    @Column(name = "family_id") var familyId: UUID,
    @Column(name = "user_id") var userId: UUID,
    @Enumerated(EnumType.STRING) @Column(name = "member_role") var memberRole: Role,
) : Timestamped()

@Entity
@Table(name = "devices")
class Device(
    @Id var id: UUID = UUID.randomUUID(),
    @Column(name = "user_id") var userId: UUID,
    @Column(name = "installation_id") var installationId: String,
    @Column(name = "device_name") var deviceName: String,
    var manufacturer: String? = null,
    var model: String? = null,
    @Column(name = "os_version") var osVersion: String? = null,
    @Column(name = "app_version") var appVersion: String? = null,
    @Enumerated(EnumType.STRING) var status: DeviceStatus = DeviceStatus.ACTIVE,
    @Column(name = "last_seen_at") var lastSeenAt: Instant? = null,
    @Column(name = "revoked_at") var revokedAt: Instant? = null,
) : Timestamped()

@Entity
@Table(name = "pairing_tokens")
class PairingToken(
    @Id var id: UUID = UUID.randomUUID(),
    @Column(name = "family_id") var familyId: UUID,
    @Column(name = "created_by_user_id") var createdByUserId: UUID,
    @Column(name = "code_hash") var codeHash: String,
    @Column(name = "child_label") var childLabel: String? = null,
    @Column(name = "expires_at") var expiresAt: Instant,
    @Column(name = "consumed_at") var consumedAt: Instant? = null,
    @Column(name = "consumed_by_device_id") var consumedByDeviceId: UUID? = null,
    @Column(name = "revoked_at") var revokedAt: Instant? = null,
) : Timestamped() {
    fun isUsable(now: Instant) = consumedAt == null && revokedAt == null && expiresAt.isAfter(now)
}

@Entity
@Table(name = "device_pairings")
class DevicePairing(
    @Id var id: UUID = UUID.randomUUID(),
    @Column(name = "family_id") var familyId: UUID,
    @Column(name = "device_id") var deviceId: UUID,
    @Column(name = "child_user_id") var childUserId: UUID,
    @Column(name = "paired_by_user_id") var pairedByUserId: UUID?,
    @Column(name = "pairing_token_id") var pairingTokenId: UUID?,
    @Enumerated(EnumType.STRING) var status: PairingStatus = PairingStatus.ACTIVE,
    @Column(name = "paired_at") var pairedAt: Instant,
    @Column(name = "revoked_at") var revokedAt: Instant? = null,
    @Column(name = "revoked_by_user_id") var revokedByUserId: UUID? = null,
    @Column(name = "revocation_reason") var revocationReason: String? = null,
) : Timestamped()

@Entity
@Table(name = "app_usage_records")
class AppUsageRecord(
    @Id var id: UUID = UUID.randomUUID(),
    @Column(name = "device_id") var deviceId: UUID,
    @Column(name = "usage_date") var usageDate: LocalDate,
    @Column(name = "package_name") var packageName: String,
    @Column(name = "app_label") var appLabel: String?,
    @Column(name = "foreground_ms") var foregroundMs: Long,
    @Column(name = "launch_count") var launchCount: Int,
    @Column(name = "last_used_at") var lastUsedAt: Instant?,
) : Timestamped()

@Entity
@Table(name = "daily_usage_summaries")
class DailyUsageSummary(
    @Id var id: UUID = UUID.randomUUID(),
    @Column(name = "device_id") var deviceId: UUID,
    @Column(name = "usage_date") var usageDate: LocalDate,
    @Column(name = "total_foreground_ms") var totalForegroundMs: Long,
    @Column(name = "app_count") var appCount: Int,
    @Column(name = "first_activity_at") var firstActivityAt: Instant?,
    @Column(name = "last_activity_at") var lastActivityAt: Instant?,
    var timezone: String?,
) : Timestamped()

@Entity
@Table(name = "screen_sharing_sessions")
class ScreenSharingSession(
    @Id var id: UUID = UUID.randomUUID(),
    @Column(name = "family_id") var familyId: UUID,
    @Column(name = "device_id") var deviceId: UUID,
    @Column(name = "requested_by_user_id") var requestedByUserId: UUID?,
    @Enumerated(EnumType.STRING) var status: ScreenShareStatus = ScreenShareStatus.REQUESTED,
    @Column(name = "requested_at") var requestedAt: Instant,
    @Column(name = "request_expires_at") var requestExpiresAt: Instant,
    @Column(name = "approved_at") var approvedAt: Instant? = null,
    @Column(name = "started_at") var startedAt: Instant? = null,
    @Column(name = "ended_at") var endedAt: Instant? = null,
    @Column(name = "max_end_at") var maxEndAt: Instant? = null,
    @Column(name = "end_reason") var endReason: String? = null,
    @Column(name = "ended_by_user_id") var endedByUserId: UUID? = null,
) : Timestamped()

@Entity
@Table(name = "safety_alerts")
class SafetyAlert(
    @Id var id: UUID = UUID.randomUUID(),
    @Column(name = "family_id") var familyId: UUID,
    @Column(name = "device_id") var deviceId: UUID?,
    @Column(name = "created_by_user_id") var createdByUserId: UUID?,
    @Enumerated(EnumType.STRING) @Column(name = "alert_type") var alertType: AlertType,
    @Enumerated(EnumType.STRING) var severity: AlertSeverity,
    @Enumerated(EnumType.STRING) var audience: AlertAudience,
    var title: String,
    var message: String?,
    @Column(name = "dedupe_key") var dedupeKey: String? = null,
    @Column(name = "acknowledged_at") var acknowledgedAt: Instant? = null,
    @Column(name = "acknowledged_by_user_id") var acknowledgedByUserId: UUID? = null,
) : Timestamped()

@Entity
@Table(name = "safety_settings")
class SafetySettings(
    @Id var id: UUID = UUID.randomUUID(),
    @Column(name = "family_id") var familyId: UUID,
    @Column(name = "device_id") var deviceId: UUID,
    @Column(name = "daily_limit_minutes") var dailyLimitMinutes: Int? = null,
    @Column(name = "bedtime_start") var bedtimeStart: LocalTime? = null,
    @Column(name = "bedtime_end") var bedtimeEnd: LocalTime? = null,
    @Column(name = "limit_alerts_enabled") var limitAlertsEnabled: Boolean = true,
    @Column(name = "bedtime_alerts_enabled") var bedtimeAlertsEnabled: Boolean = false,
    @Column(name = "screen_sharing_enabled") var screenSharingEnabled: Boolean = true,
    @Column(name = "usage_retention_days") var usageRetentionDays: Int = 90,
) : Timestamped()

@Entity
@Table(name = "authentication_sessions")
class AuthenticationSession(
    @Id var id: UUID = UUID.randomUUID(),
    @Column(name = "user_id") var userId: UUID,
    @Column(name = "device_id") var deviceId: UUID?,
    @Column(name = "token_family_id") var tokenFamilyId: UUID,
    @Column(name = "refresh_token_hash") var refreshTokenHash: String,
    @Enumerated(EnumType.STRING) var status: AuthSessionStatus = AuthSessionStatus.ACTIVE,
    @Column(name = "expires_at") var expiresAt: Instant,
    @Column(name = "absolute_expires_at") var absoluteExpiresAt: Instant,
    @Column(name = "last_used_at") var lastUsedAt: Instant? = null,
    @Column(name = "replaced_by_session_id") var replacedBySessionId: UUID? = null,
    @Column(name = "revoked_at") var revokedAt: Instant? = null,
    @Column(name = "revoke_reason") var revokeReason: String? = null,
    @Column(name = "ip_address") var ipAddress: String? = null,
    @Column(name = "user_agent") var userAgent: String? = null,
) : Timestamped()

@Entity
@Table(name = "audit_logs")
class AuditLog(
    @Id var id: UUID = UUID.randomUUID(),
    @Column(name = "actor_user_id") var actorUserId: UUID?,
    @Column(name = "actor_device_id") var actorDeviceId: UUID?,
    var action: String,
    @Column(name = "target_type") var targetType: String?,
    @Column(name = "target_id") var targetId: String?,
    @Enumerated(EnumType.STRING) var outcome: AuditOutcome,
    @Column(name = "ip_address") var ipAddress: String?,
    @JdbcTypeCode(SqlTypes.JSON) var details: Map<String, Any?>?,
    @Column(name = "created_at") var createdAt: Instant = Instant.now(),
)
