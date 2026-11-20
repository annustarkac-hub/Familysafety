package com.familysafety.backend.service

import com.familysafety.backend.audit.AuditAction
import com.familysafety.backend.audit.AuditService
import com.familysafety.backend.common.ConflictException
import com.familysafety.backend.common.NotFoundException
import com.familysafety.backend.model.AlertType
import com.familysafety.backend.model.AuthSessionStatus
import com.familysafety.backend.model.Device
import com.familysafety.backend.model.DevicePairing
import com.familysafety.backend.model.DeviceStatus
import com.familysafety.backend.model.PairingStatus
import com.familysafety.backend.model.Role
import com.familysafety.backend.model.ScreenShareStatus
import com.familysafety.backend.realtime.ConnectionRegistry
import com.familysafety.backend.realtime.EventTypes
import com.familysafety.backend.realtime.RealtimeEvent
import com.familysafety.backend.realtime.afterCommit
import com.familysafety.backend.repository.AuthenticationSessionRepository
import com.familysafety.backend.repository.DailyUsageSummaryRepository
import com.familysafety.backend.repository.DevicePairingRepository
import com.familysafety.backend.repository.DeviceRepository
import com.familysafety.backend.repository.FamilyMemberRepository
import com.familysafety.backend.repository.FamilyRepository
import com.familysafety.backend.repository.ScreenSharingSessionRepository
import com.familysafety.backend.repository.UserRepository
import com.familysafety.backend.security.AuthPrincipal
import com.familysafety.backend.web.DeviceDto
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.util.UUID

@Service
class DeviceService(
    private val devices: DeviceRepository,
    private val pairings: DevicePairingRepository,
    private val members: FamilyMemberRepository,
    private val families: FamilyRepository,
    private val users: UserRepository,
    private val summaries: DailyUsageSummaryRepository,
    private val screenSessions: ScreenSharingSessionRepository,
    private val authSessions: AuthenticationSessionRepository,
    private val safety: SafetyService,
    private val access: AccessPolicy,
    private val registry: ConnectionRegistry,
    private val audit: AuditService,
    private val clock: Clock,
) {
    @Transactional(readOnly = true)
    fun list(p: AuthPrincipal): List<DeviceDto> = when (p.role) {
        Role.PARENT -> pairings.findByFamilyIdAndStatus(access.parentFamilyId(p), PairingStatus.ACTIVE)
            .mapNotNull { pairing -> devices.findById(pairing.deviceId).orElse(null)?.let { toDto(it, pairing) } }
            .sortedBy { it.ownerDisplayName.lowercase() }
        Role.CHILD -> devices.findByUserId(p.userId).filter { it.id == p.deviceId || it.status == DeviceStatus.ACTIVE }
            .map { toDto(it, pairings.findByDeviceIdAndStatus(it.id, PairingStatus.ACTIVE)) }
    }

    @Transactional(readOnly = true)
    fun get(p: AuthPrincipal, deviceId: UUID): DeviceDto {
        access.requireReadAccessToDevice(p, deviceId)
        val device = devices.findById(deviceId).orElseThrow { NotFoundException("DEVICE_NOT_FOUND", "Device not found") }
        return toDto(device, pairings.findByDeviceIdAndStatus(deviceId, PairingStatus.ACTIVE))
    }

    /**
     * Parent-initiated revocation. Effects (all in one transaction, realtime notices after commit):
     * pairing → REVOKED, device → REVOKED, every auth session on the device → REVOKED, open screen-sharing
     * sessions → STOPPED, child membership removed when no other device of the child remains paired.
     */
    @Transactional
    fun revoke(p: AuthPrincipal, deviceId: UUID, reason: String?) {
        val pairing = access.requireParentAccessToDevice(p, deviceId)
        val now = clock.instant()
        pairing.status = PairingStatus.REVOKED
        pairing.revokedAt = now
        pairing.revokedByUserId = p.userId
        pairing.revocationReason = (reason ?: "REVOKED_BY_PARENT").take(64)
        pairings.saveAndFlush(pairing)

        val device = devices.findById(deviceId).orElseThrow()
        device.status = DeviceStatus.REVOKED
        device.revokedAt = now
        devices.save(device)

        authSessions.findByDeviceIdAndStatus(deviceId, AuthSessionStatus.ACTIVE).forEach {
            it.status = AuthSessionStatus.REVOKED
            it.revokedAt = now
            it.revokeReason = "DEVICE_REVOKED"
            authSessions.save(it)
        }
        val endedSessions = screenSessions.findByDeviceIdAndStatusIn(deviceId, ScreenShareStatus.entries.filter { it.isOpen }).onEach {
            it.status = ScreenShareStatus.STOPPED
            it.endedAt = now
            it.endReason = "DEVICE_REVOKED"
            it.endedByUserId = p.userId
            screenSessions.save(it)
        }
        if (pairings.findByChildUserIdAndStatus(pairing.childUserId, PairingStatus.ACTIVE).none { it.familyId == pairing.familyId }) {
            members.findByFamilyIdAndUserId(pairing.familyId, pairing.childUserId)?.let { members.delete(it) }
        }
        val childName = users.findById(pairing.childUserId).map { it.displayName }.orElse("Child")
        safety.raiseSystemAlert(pairing.familyId, null, AlertType.DEVICE_REVOKED, "$childName's device was disconnected",
            "${device.deviceName} no longer shares data with your family.")
        audit.record(AuditAction.DEVICE_REVOKE, p.userId, p.deviceId, "device", deviceId,
            details = mapOf("reason" to pairing.revocationReason, "endedScreenSessions" to endedSessions.size))

        afterCommit {
            val event = RealtimeEvent(EventTypes.DEVICE_REVOKED, mapOf("deviceId" to deviceId))
            registry.sendToDevice(deviceId, event)
            registry.sendToFamilyParents(pairing.familyId, event)
            registry.closeDevice(deviceId, "device_revoked")
        }
    }

    /** Child asks parents to disconnect the device; a parent completes it via DELETE /devices/{id}. */
    @Transactional
    fun requestDisconnect(p: AuthPrincipal, deviceId: UUID) {
        if (access.childDeviceId(p) != deviceId) throw NotFoundException("DEVICE_NOT_FOUND", "Device not found")
        val pairing = pairings.findByDeviceIdAndStatus(deviceId, PairingStatus.ACTIVE)
            ?: throw ConflictException("DEVICE_NOT_PAIRED", "This device is not paired")
        val childName = users.findById(p.userId).map { it.displayName }.orElse("Your child")
        safety.raiseSystemAlert(pairing.familyId, deviceId, AlertType.DISCONNECT_REQUEST,
            "$childName asked to disconnect their device",
            "Review the request and remove the device from Family Safety if you agree.",
            dedupeKey = "disconnect:$deviceId:${LocalDate.now(clock)}")
        audit.record(AuditAction.DEVICE_DISCONNECT_REQUEST, p.userId, deviceId, "device", deviceId)
    }

    /** Updates last-seen at most once per minute to avoid write amplification. */
    @Transactional
    fun touch(deviceId: UUID) {
        val device = devices.findById(deviceId).orElse(null) ?: return
        val now = clock.instant()
        if (device.lastSeenAt == null || Duration.between(device.lastSeenAt, now).toSeconds() >= 60) {
            device.lastSeenAt = now
            devices.save(device)
        }
    }

    private fun toDto(device: Device, pairing: DevicePairing?): DeviceDto {
        val owner = users.findById(device.userId).orElseThrow()
        val family = pairing?.let { families.findById(it.familyId).orElse(null) }
        val latest = summaries.findByDeviceIdAndUsageDateBetweenOrderByUsageDate(device.id, LocalDate.now(clock).minusDays(1), LocalDate.now(clock).plusDays(1)).lastOrNull()
        val activeShare = screenSessions.findByDeviceIdAndStatusIn(device.id, ScreenShareStatus.entries.filter { it.isOpen }).firstOrNull()
        return DeviceDto(device.id, device.deviceName, device.manufacturer, device.model, device.osVersion, device.appVersion,
            device.status.name, device.lastSeenAt, owner.id, owner.displayName, pairing?.id, pairing?.status?.name, pairing?.pairedAt,
            family?.name, latest?.usageDate, latest?.totalForegroundMs, activeShare?.id)
    }
}
