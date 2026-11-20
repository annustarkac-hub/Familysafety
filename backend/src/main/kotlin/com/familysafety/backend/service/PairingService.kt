package com.familysafety.backend.service

import com.familysafety.backend.audit.AuditAction
import com.familysafety.backend.audit.AuditService
import com.familysafety.backend.common.ApiException
import com.familysafety.backend.common.BadRequestException
import com.familysafety.backend.common.ConflictException
import com.familysafety.backend.config.AppProperties
import com.familysafety.backend.model.AlertType
import com.familysafety.backend.model.AuditOutcome
import com.familysafety.backend.model.DevicePairing
import com.familysafety.backend.model.FamilyMember
import com.familysafety.backend.model.PairingStatus
import com.familysafety.backend.model.PairingToken
import com.familysafety.backend.model.Role
import com.familysafety.backend.model.SafetySettings
import com.familysafety.backend.realtime.ConnectionRegistry
import com.familysafety.backend.realtime.EventTypes
import com.familysafety.backend.realtime.RealtimeEvent
import com.familysafety.backend.realtime.afterCommit
import com.familysafety.backend.repository.DevicePairingRepository
import com.familysafety.backend.repository.DeviceRepository
import com.familysafety.backend.repository.FamilyMemberRepository
import com.familysafety.backend.repository.FamilyRepository
import com.familysafety.backend.repository.PairingTokenRepository
import com.familysafety.backend.repository.SafetySettingsRepository
import com.familysafety.backend.repository.UserRepository
import com.familysafety.backend.security.AuthPrincipal
import com.familysafety.backend.security.RateLimiter
import com.familysafety.backend.security.TokenService
import com.familysafety.backend.web.AcceptPairingResponse
import com.familysafety.backend.web.CreatePairingResponse
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/**
 * Two-party pairing:
 *  1. An authenticated PARENT explicitly creates a short-lived, single-use pairing code (authorisation #1).
 *  2. An authenticated CHILD enters that code on their own device (authorisation #2), binding that device.
 * Codes are 8 characters from a 31-symbol alphabet (~39.6 bits), valid for 10 minutes, stored only as an
 * HMAC with a server-side pepper, and attempts are rate-limited per child account.
 */
@Service
class PairingService(
    private val tokensRepo: PairingTokenRepository,
    private val pairings: DevicePairingRepository,
    private val members: FamilyMemberRepository,
    private val families: FamilyRepository,
    private val devices: DeviceRepository,
    private val users: UserRepository,
    private val settings: SafetySettingsRepository,
    private val safety: SafetyService,
    private val access: AccessPolicy,
    private val tokens: TokenService,
    private val limiter: RateLimiter,
    private val registry: ConnectionRegistry,
    private val audit: AuditService,
    private val props: AppProperties,
    private val clock: Clock,
) {
    companion object {
        const val ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ"
        const val CODE_LENGTH = 8

        fun normalize(code: String): String = code.uppercase().filter { it.isLetterOrDigit() }

        fun format(code: String) = code.substring(0, 4) + "-" + code.substring(4)
    }

    @Transactional
    fun create(p: AuthPrincipal, childLabel: String?): CreatePairingResponse {
        val familyId = access.parentFamilyId(p)
        val now = clock.instant()
        if (tokensRepo.countActiveForFamily(familyId, now) >= props.pairing.maxActiveCodesPerFamily) {
            throw ApiException(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_ACTIVE_CODES",
                "Too many active pairing codes. Wait for existing codes to expire.")
        }
        var code: String
        var hash: String
        do {
            code = (1..CODE_LENGTH).map { ALPHABET[tokens.nextInt(ALPHABET.length)] }.joinToString("")
            hash = tokens.pairingCodeHash(code)
        } while (tokensRepo.findByCodeHashForUpdate(hash) != null)

        val token = tokensRepo.save(PairingToken(familyId = familyId, createdByUserId = p.userId, codeHash = hash,
            childLabel = childLabel?.trim()?.takeIf { it.isNotEmpty() }, expiresAt = now.plus(props.pairing.codeTtl)))
        audit.record(AuditAction.PAIRING_CREATE, p.userId, p.deviceId, "pairing_token", token.id)
        return CreatePairingResponse(token.id, format(code), token.expiresAt, token.childLabel)
    }

    @Transactional(noRollbackFor = [ApiException::class])
    fun accept(p: AuthPrincipal, rawCode: String): AcceptPairingResponse {
        val deviceId = access.childDeviceId(p)
        limiter.check("pairing-accept:${p.userId}", props.rateLimit.pairingAcceptPerMinute)
        limiter.check("pairing-accept-hour:${p.userId}", props.pairing.maxAttemptsPerHour, 3_600_000)

        val code = normalize(rawCode)
        val now = clock.instant()
        val token = if (code.length == CODE_LENGTH && code.all { it in ALPHABET }) {
            tokensRepo.findByCodeHashForUpdate(tokens.pairingCodeHash(code))
        } else null

        if (token == null || token.consumedAt != null || token.revokedAt != null) {
            fail(p, "invalid_code")
            throw BadRequestException("INVALID_PAIRING_CODE", "The pairing code is invalid")
        }
        if (!token.expiresAt.isAfter(now)) {
            fail(p, "expired_code")
            throw ApiException(HttpStatus.GONE, "PAIRING_CODE_EXPIRED", "The pairing code has expired. Ask your parent for a new one.")
        }
        val existingMembership = members.findByUserId(p.userId).firstOrNull { it.memberRole == Role.CHILD }
        if (existingMembership != null && existingMembership.familyId != token.familyId) {
            fail(p, "child_in_other_family")
            throw ConflictException("ALREADY_IN_ANOTHER_FAMILY", "This account is already linked to another family")
        }
        if (pairings.findByDeviceIdAndStatus(deviceId, PairingStatus.ACTIVE) != null) {
            fail(p, "device_already_paired")
            throw ConflictException("DEVICE_ALREADY_PAIRED", "This device is already paired")
        }

        token.consumedAt = now
        token.consumedByDeviceId = deviceId
        tokensRepo.save(token)
        if (existingMembership == null) {
            members.save(FamilyMember(familyId = token.familyId, userId = p.userId, memberRole = Role.CHILD))
        }
        val pairing = pairings.save(DevicePairing(familyId = token.familyId, deviceId = deviceId, childUserId = p.userId,
            pairedByUserId = token.createdByUserId, pairingTokenId = token.id, status = PairingStatus.ACTIVE, pairedAt = now))
        val s = settings.findByDeviceId(deviceId) ?: SafetySettings(familyId = token.familyId, deviceId = deviceId)
        s.familyId = token.familyId
        settings.save(s)

        val family = families.findById(token.familyId).orElseThrow()
        val child = users.findById(p.userId).orElseThrow()
        val device = devices.findById(deviceId).orElseThrow()
        val parentNames = members.findByFamilyIdAndMemberRole(family.id, Role.PARENT)
            .mapNotNull { users.findById(it.userId).orElse(null)?.displayName }

        audit.record(AuditAction.PAIRING_ACCEPT, p.userId, deviceId, "device_pairing", pairing.id,
            details = mapOf("familyId" to family.id.toString()))
        safety.raiseSystemAlert(family.id, deviceId, AlertType.DEVICE_PAIRED, "${child.displayName}'s device was paired",
            "${device.deviceName} is now connected to your family.")
        afterCommit {
            registry.sendToFamilyParents(family.id, RealtimeEvent(EventTypes.PAIRING_COMPLETED,
                mapOf("deviceId" to deviceId, "pairingId" to pairing.id, "childDisplayName" to child.displayName,
                    "deviceName" to device.deviceName)))
        }
        return AcceptPairingResponse(pairing.id, family.id, family.name, deviceId, now, parentNames)
    }

    private fun fail(p: AuthPrincipal, reason: String) =
        audit.record(AuditAction.PAIRING_ACCEPT_FAILED, p.userId, p.deviceId, outcome = AuditOutcome.FAILURE, details = mapOf("reason" to reason))
}
