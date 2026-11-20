package com.familysafety.backend.service

import com.familysafety.backend.audit.AuditAction
import com.familysafety.backend.audit.AuditService
import com.familysafety.backend.common.ForbiddenException
import com.familysafety.backend.common.NotFoundException
import com.familysafety.backend.model.AuditOutcome
import com.familysafety.backend.model.DevicePairing
import com.familysafety.backend.model.PairingStatus
import com.familysafety.backend.model.Role
import com.familysafety.backend.repository.DevicePairingRepository
import com.familysafety.backend.repository.FamilyMemberRepository
import com.familysafety.backend.security.AuthPrincipal
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Central server-side authorization rules. Every controller/service path that touches family data goes
 * through here; the Android client's role routing is only a UX convenience and is never trusted.
 */
@Component
class AccessPolicy(
    private val members: FamilyMemberRepository,
    private val pairings: DevicePairingRepository,
    private val audit: AuditService,
) {
    fun requireParent(p: AuthPrincipal) {
        if (p.role != Role.PARENT) deny(p, "role_parent_required")
    }

    fun requireChild(p: AuthPrincipal) {
        if (p.role != Role.CHILD) deny(p, "role_child_required")
    }

    /** The family a parent administers. Parents always have exactly one family (created at registration). */
    fun parentFamilyId(p: AuthPrincipal): UUID {
        requireParent(p)
        return members.findByUserId(p.userId).firstOrNull { it.memberRole == Role.PARENT }?.familyId
            ?: throw NotFoundException("FAMILY_NOT_FOUND", "No family found for this account")
    }

    /** The calling child's own device (bound into the access token at login). */
    fun childDeviceId(p: AuthPrincipal): UUID {
        requireChild(p)
        return p.deviceId ?: deny(p, "no_device_bound")
    }

    /** Active pairing for the calling child's device, if any. */
    fun childActivePairing(p: AuthPrincipal): DevicePairing? =
        pairings.findByDeviceIdAndStatus(childDeviceId(p), PairingStatus.ACTIVE)

    /**
     * A parent may access a device only if it has an ACTIVE pairing into the parent's family.
     * Returns 404 (not 403) so device ids of other families cannot be probed.
     */
    fun requireParentAccessToDevice(p: AuthPrincipal, deviceId: UUID): DevicePairing {
        val familyId = parentFamilyId(p)
        val pairing = pairings.findByDeviceIdAndStatus(deviceId, PairingStatus.ACTIVE)
        if (pairing == null || pairing.familyId != familyId) {
            audit.record(AuditAction.ACCESS_DENIED, p.userId, p.deviceId, "device", deviceId, AuditOutcome.DENIED,
                mapOf("reason" to "not_in_family"))
            throw NotFoundException("DEVICE_NOT_FOUND", "Device not found")
        }
        return pairing
    }

    /** Read access to a device's data: its paired parents, or the child on that very device. */
    fun requireReadAccessToDevice(p: AuthPrincipal, deviceId: UUID) {
        when (p.role) {
            Role.PARENT -> requireParentAccessToDevice(p, deviceId)
            Role.CHILD -> if (p.deviceId != deviceId) {
                audit.record(AuditAction.ACCESS_DENIED, p.userId, p.deviceId, "device", deviceId, AuditOutcome.DENIED,
                    mapOf("reason" to "child_foreign_device"))
                throw NotFoundException("DEVICE_NOT_FOUND", "Device not found")
            }
        }
    }

    private fun deny(p: AuthPrincipal, reason: String): Nothing {
        audit.record(AuditAction.ACCESS_DENIED, p.userId, p.deviceId, null, null, AuditOutcome.DENIED, mapOf("reason" to reason))
        throw ForbiddenException("FORBIDDEN_ROLE", "This action is not available for your account role")
    }
}
