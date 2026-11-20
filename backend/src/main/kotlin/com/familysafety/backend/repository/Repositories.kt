package com.familysafety.backend.repository

import com.familysafety.backend.model.AlertAudience
import com.familysafety.backend.model.AppUsageRecord
import com.familysafety.backend.model.AuditLog
import com.familysafety.backend.model.AuthSessionStatus
import com.familysafety.backend.model.AuthenticationSession
import com.familysafety.backend.model.DailyUsageSummary
import com.familysafety.backend.model.Device
import com.familysafety.backend.model.DevicePairing
import com.familysafety.backend.model.Family
import com.familysafety.backend.model.FamilyMember
import com.familysafety.backend.model.PairingStatus
import com.familysafety.backend.model.PairingToken
import com.familysafety.backend.model.Role
import com.familysafety.backend.model.SafetyAlert
import com.familysafety.backend.model.SafetySettings
import com.familysafety.backend.model.ScreenShareStatus
import com.familysafety.backend.model.ScreenSharingSession
import com.familysafety.backend.model.User
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import jakarta.persistence.LockModeType
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

interface UserRepository : JpaRepository<User, UUID> {
    @Query("select u from User u where lower(u.email) = lower(:email)")
    fun findByEmailIgnoreCase(@Param("email") email: String): User?
}

interface FamilyRepository : JpaRepository<Family, UUID>

interface FamilyMemberRepository : JpaRepository<FamilyMember, UUID> {
    fun findByUserId(userId: UUID): List<FamilyMember>
    fun findByFamilyId(familyId: UUID): List<FamilyMember>
    fun findByFamilyIdAndUserId(familyId: UUID, userId: UUID): FamilyMember?
    fun findByFamilyIdAndMemberRole(familyId: UUID, role: Role): List<FamilyMember>
}

interface DeviceRepository : JpaRepository<Device, UUID> {
    fun findByUserIdAndInstallationId(userId: UUID, installationId: String): Device?
    fun findByUserId(userId: UUID): List<Device>
}

interface PairingTokenRepository : JpaRepository<PairingToken, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from PairingToken t where t.codeHash = :hash")
    fun findByCodeHashForUpdate(@Param("hash") hash: String): PairingToken?

    @Query("select count(t) from PairingToken t where t.familyId = :familyId and t.consumedAt is null and t.revokedAt is null and t.expiresAt > :now")
    fun countActiveForFamily(@Param("familyId") familyId: UUID, @Param("now") now: Instant): Long

    @Modifying
    @Query("delete from PairingToken t where t.expiresAt < :cutoff")
    fun deleteExpiredBefore(@Param("cutoff") cutoff: Instant): Int
}

interface DevicePairingRepository : JpaRepository<DevicePairing, UUID> {
    fun findByDeviceIdAndStatus(deviceId: UUID, status: PairingStatus): DevicePairing?
    fun findByFamilyIdAndStatus(familyId: UUID, status: PairingStatus): List<DevicePairing>
    fun findByChildUserIdAndStatus(childUserId: UUID, status: PairingStatus): List<DevicePairing>
}

interface AppUsageRecordRepository : JpaRepository<AppUsageRecord, UUID> {
    fun findByDeviceIdAndUsageDate(deviceId: UUID, usageDate: LocalDate): List<AppUsageRecord>
    fun findByDeviceIdAndUsageDateBetween(deviceId: UUID, from: LocalDate, to: LocalDate): List<AppUsageRecord>

    @Modifying
    @Query("delete from AppUsageRecord r where r.deviceId = :deviceId and r.usageDate = :date")
    fun deleteByDeviceIdAndUsageDate(@Param("deviceId") deviceId: UUID, @Param("date") date: LocalDate): Int

    @Modifying
    @Query("delete from AppUsageRecord r where r.deviceId = :deviceId and r.usageDate < :cutoff")
    fun deleteOlderThan(@Param("deviceId") deviceId: UUID, @Param("cutoff") cutoff: LocalDate): Int
}

interface DailyUsageSummaryRepository : JpaRepository<DailyUsageSummary, UUID> {
    fun findByDeviceIdAndUsageDate(deviceId: UUID, usageDate: LocalDate): DailyUsageSummary?
    fun findByDeviceIdAndUsageDateBetweenOrderByUsageDate(deviceId: UUID, from: LocalDate, to: LocalDate): List<DailyUsageSummary>

    @Modifying
    @Query("delete from DailyUsageSummary s where s.deviceId = :deviceId and s.usageDate < :cutoff")
    fun deleteOlderThan(@Param("deviceId") deviceId: UUID, @Param("cutoff") cutoff: LocalDate): Int
}

interface ScreenSharingSessionRepository : JpaRepository<ScreenSharingSession, UUID> {
    @Query("select s from ScreenSharingSession s where s.deviceId = :deviceId and s.status in :statuses order by s.requestedAt desc")
    fun findByDeviceIdAndStatusIn(@Param("deviceId") deviceId: UUID, @Param("statuses") statuses: Collection<ScreenShareStatus>): List<ScreenSharingSession>

    @Query("select s from ScreenSharingSession s where s.status in :statuses")
    fun findByStatusIn(@Param("statuses") statuses: Collection<ScreenShareStatus>): List<ScreenSharingSession>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from ScreenSharingSession s where s.id = :id")
    fun findByIdForUpdate(@Param("id") id: UUID): ScreenSharingSession?

    fun findTop20ByDeviceIdOrderByRequestedAtDesc(deviceId: UUID): List<ScreenSharingSession>

    @Modifying
    @Query("delete from ScreenSharingSession s where s.endedAt is not null and s.endedAt < :cutoff")
    fun deleteEndedBefore(@Param("cutoff") cutoff: Instant): Int
}

interface SafetyAlertRepository : JpaRepository<SafetyAlert, UUID> {
    fun existsByDedupeKey(dedupeKey: String): Boolean

    @Query(
        """select a from SafetyAlert a where a.familyId = :familyId
           and a.audience in :audiences
           and (:unackOnly = false or a.acknowledgedAt is null)
           and a.createdAt < :before
           order by a.createdAt desc""",
    )
    fun searchFamily(
        @Param("familyId") familyId: UUID,
        @Param("audiences") audiences: Collection<AlertAudience>,
        @Param("unackOnly") unacknowledgedOnly: Boolean,
        @Param("before") before: Instant,
        pageable: Pageable,
    ): List<SafetyAlert>

    @Query(
        """select a from SafetyAlert a where a.familyId = :familyId
           and a.deviceId = :deviceId
           and a.audience in :audiences
           and (:unackOnly = false or a.acknowledgedAt is null)
           and a.createdAt < :before
           order by a.createdAt desc""",
    )
    fun searchDevice(
        @Param("familyId") familyId: UUID,
        @Param("deviceId") deviceId: UUID,
        @Param("audiences") audiences: Collection<AlertAudience>,
        @Param("unackOnly") unacknowledgedOnly: Boolean,
        @Param("before") before: Instant,
        pageable: Pageable,
    ): List<SafetyAlert>

    @Modifying
    @Query("delete from SafetyAlert a where a.createdAt < :cutoff")
    fun deleteCreatedBefore(@Param("cutoff") cutoff: Instant): Int
}

interface SafetySettingsRepository : JpaRepository<SafetySettings, UUID> {
    fun findByDeviceId(deviceId: UUID): SafetySettings?
}

interface AuthenticationSessionRepository : JpaRepository<AuthenticationSession, UUID> {
    fun findByRefreshTokenHash(hash: String): AuthenticationSession?
    fun findByTokenFamilyIdAndStatus(tokenFamilyId: UUID, status: AuthSessionStatus): List<AuthenticationSession>
    fun findByDeviceIdAndStatus(deviceId: UUID, status: AuthSessionStatus): List<AuthenticationSession>

    @Modifying
    @Query("delete from AuthenticationSession s where s.expiresAt < :cutoff")
    fun deleteExpiredBefore(@Param("cutoff") cutoff: Instant): Int
}

interface AuditLogRepository : JpaRepository<AuditLog, UUID> {
    fun findByActorUserIdOrderByCreatedAtDesc(actorUserId: UUID): List<AuditLog>
    fun findByActionOrderByCreatedAtDesc(action: String): List<AuditLog>

    @Modifying
    @Query("delete from AuditLog a where a.createdAt < :cutoff")
    fun deleteCreatedBefore(@Param("cutoff") cutoff: Instant): Int
}
