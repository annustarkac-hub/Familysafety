package com.familysafety.backend.service

import com.familysafety.backend.audit.AuditAction
import com.familysafety.backend.audit.AuditService
import com.familysafety.backend.common.ApiException
import com.familysafety.backend.common.ConflictException
import com.familysafety.backend.common.ForbiddenException
import com.familysafety.backend.common.NotFoundException
import com.familysafety.backend.config.AppProperties
import com.familysafety.backend.model.AlertAudience
import com.familysafety.backend.model.AlertType
import com.familysafety.backend.model.PairingStatus
import com.familysafety.backend.model.Role
import com.familysafety.backend.model.ScreenShareStatus
import com.familysafety.backend.model.ScreenSharingSession
import com.familysafety.backend.realtime.ConnectionRegistry
import com.familysafety.backend.realtime.EventTypes
import com.familysafety.backend.realtime.RealtimeEvent
import com.familysafety.backend.realtime.afterCommit
import com.familysafety.backend.repository.DevicePairingRepository
import com.familysafety.backend.repository.SafetySettingsRepository
import com.familysafety.backend.repository.ScreenSharingSessionRepository
import com.familysafety.backend.repository.UserRepository
import com.familysafety.backend.security.AuthPrincipal
import com.familysafety.backend.web.IceServerDto
import com.familysafety.backend.web.ScreenShareSessionDto
import com.familysafety.backend.web.ScreenShareStatusResponse
import com.familysafety.backend.web.StopReason
import com.fasterxml.jackson.databind.JsonNode
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Consent-based screen sharing state machine:
 *
 *   REQUESTED --child approves--> APPROVED --child's first WebRTC offer--> ACTIVE
 *      |  \--child rejects--> REJECTED          |                           |
 *      \--timeout--> EXPIRED                    \--start timeout--> EXPIRED  \--max duration--> EXPIRED
 *   any open state --stop (either side)--> STOPPED | FAILED (client-reported failure)
 *   any open state --device revoked / participant disconnected--> STOPPED
 *
 * The server only relays signaling (SDP/ICE) between the two authorised participants of an APPROVED/ACTIVE
 * session. Media flows peer-to-peer (or via TURN) and is never recorded or stored by the server.
 */
@Service
class ScreenSharingService(
    private val sessions: ScreenSharingSessionRepository,
    private val pairings: DevicePairingRepository,
    private val settings: SafetySettingsRepository,
    private val users: UserRepository,
    private val safety: SafetyService,
    private val access: AccessPolicy,
    private val registry: ConnectionRegistry,
    private val audit: AuditService,
    private val props: AppProperties,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val openStatuses = ScreenShareStatus.entries.filter { it.isOpen }

    @Transactional
    fun request(p: AuthPrincipal, deviceId: UUID): ScreenShareSessionDto {
        val pairing = access.requireParentAccessToDevice(p, deviceId)
        if (settings.findByDeviceId(deviceId)?.screenSharingEnabled == false) {
            throw ForbiddenException("SCREEN_SHARING_DISABLED", "Screen sharing is disabled for this device in safety settings")
        }
        expireOverdue(deviceId)
        if (sessions.findByDeviceIdAndStatusIn(deviceId, openStatuses).isNotEmpty()) {
            throw ConflictException("SESSION_ALREADY_OPEN", "A screen-sharing session is already pending or active for this device")
        }
        val now = clock.instant()
        val session = sessions.saveAndFlush(ScreenSharingSession(familyId = pairing.familyId, deviceId = deviceId,
            requestedByUserId = p.userId, requestedAt = now, requestExpiresAt = now.plus(props.screenSharing.requestTtl)))
        audit.record(AuditAction.SCREEN_SHARE_REQUEST, p.userId, p.deviceId, "screen_session", session.id, details = mapOf("deviceId" to deviceId.toString()))
        val dto = toDto(session)
        afterCommit { registry.sendToDevice(deviceId, RealtimeEvent(EventTypes.SCREEN_SHARE_REQUESTED, dto)) }
        return dto
    }

    @Transactional(noRollbackFor = [ApiException::class])
    fun approve(p: AuthPrincipal, sessionId: UUID): ScreenShareSessionDto {
        val session = loadForChild(p, sessionId)
        val now = clock.instant()
        if (session.status == ScreenShareStatus.REQUESTED && !session.requestExpiresAt.isAfter(now)) {
            end(session, ScreenShareStatus.EXPIRED, "REQUEST_TIMEOUT", null)
            throw ApiException(HttpStatus.GONE, "SESSION_EXPIRED", "The screen-sharing request has expired")
        }
        if (session.status != ScreenShareStatus.REQUESTED) throw invalidState(session)
        session.status = ScreenShareStatus.APPROVED
        session.approvedAt = now
        session.maxEndAt = now.plus(props.screenSharing.approvalToStartTtl)
        sessions.save(session)
        audit.record(AuditAction.SCREEN_SHARE_APPROVE, p.userId, p.deviceId, "screen_session", session.id)
        broadcast(session)
        return toDto(session, withIce = p)
    }

    @Transactional
    fun reject(p: AuthPrincipal, sessionId: UUID): ScreenShareSessionDto {
        val session = loadForChild(p, sessionId)
        if (session.status != ScreenShareStatus.REQUESTED) throw invalidState(session)
        end(session, ScreenShareStatus.REJECTED, "CHILD_REJECTED", p.userId)
        audit.record(AuditAction.SCREEN_SHARE_REJECT, p.userId, p.deviceId, "screen_session", session.id)
        return toDto(session)
    }

    @Transactional
    fun stop(p: AuthPrincipal, sessionId: UUID, reason: StopReason?): ScreenShareSessionDto {
        val session = loadForParticipant(p, sessionId)
        if (!session.status.isOpen) return toDto(session) // idempotent
        val r = reason ?: StopReason.USER_STOPPED
        val status = if (r == StopReason.USER_STOPPED) ScreenShareStatus.STOPPED else ScreenShareStatus.FAILED
        val by = if (p.role == Role.PARENT) "PARENT" else "CHILD"
        end(session, status, if (r == StopReason.USER_STOPPED) "STOPPED_BY_$by" else "${r.name}_$by", p.userId)
        return toDto(session)
    }

    @Transactional(readOnly = true)
    fun status(p: AuthPrincipal, sessionId: UUID?, deviceId: UUID?): ScreenShareStatusResponse {
        if (sessionId != null) {
            val s = loadForParticipant(p, sessionId, lock = false)
            return ScreenShareStatusResponse(if (s.status.isOpen) toDto(s, withIce = p) else null, listOf(toDto(s)))
        }
        val target = deviceId ?: p.deviceId.takeIf { p.role == Role.CHILD }
            ?: throw ApiException(HttpStatus.BAD_REQUEST, "DEVICE_ID_REQUIRED", "deviceId or sessionId is required")
        access.requireReadAccessToDevice(p, target)
        val recent = sessions.findTop20ByDeviceIdOrderByRequestedAtDesc(target)
        val now = clock.instant()
        val active = recent.firstOrNull { it.status.isOpen && !isOverdue(it, now) }
        return ScreenShareStatusResponse(active?.let { toDto(it, withIce = p) }, recent.map { toDto(it) })
    }

    /** Relays a WebRTC signaling message between the two participants of an approved session. */
    @Transactional(noRollbackFor = [ApiException::class])
    fun relaySignal(p: AuthPrincipal, sessionId: UUID, kind: String, payload: JsonNode) {
        val session = sessions.findByIdForUpdate(sessionId) ?: throw NotFoundException("SESSION_NOT_FOUND", "Session not found")
        val now = clock.instant()
        if (isOverdue(session, now)) {
            end(session, ScreenShareStatus.EXPIRED, "TIMEOUT", null)
            throw ApiException(HttpStatus.GONE, "SESSION_EXPIRED", "Session has expired")
        }
        if (session.status != ScreenShareStatus.APPROVED && session.status != ScreenShareStatus.ACTIVE) throw invalidState(session)
        // Re-check the pairing on every message so revocation is enforced even mid-session.
        val pairing = pairings.findByDeviceIdAndStatus(session.deviceId, PairingStatus.ACTIVE)
        if (pairing == null || pairing.familyId != session.familyId) {
            end(session, ScreenShareStatus.STOPPED, "AUTHORIZATION_REVOKED", null)
            throw ForbiddenException("AUTHORIZATION_REVOKED", "Pairing is no longer active")
        }
        val fromChild = p.role == Role.CHILD && p.deviceId == session.deviceId
        val fromParent = p.role == Role.PARENT && p.userId == session.requestedByUserId
        if (!fromChild && !fromParent) throw NotFoundException("SESSION_NOT_FOUND", "Session not found")
        // The capturing (child) side is always the offerer.
        if (kind == "offer" && !fromChild) throw ForbiddenException("INVALID_SIGNAL_DIRECTION", "Only the sharing device may send an offer")
        if (kind == "answer" && !fromParent) throw ForbiddenException("INVALID_SIGNAL_DIRECTION", "Only the viewer may send an answer")

        if (kind == "offer" && session.status == ScreenShareStatus.APPROVED) {
            session.status = ScreenShareStatus.ACTIVE
            session.startedAt = now
            session.maxEndAt = now.plus(props.screenSharing.maxSessionDuration)
            sessions.save(session)
            audit.record(AuditAction.SCREEN_SHARE_START, p.userId, p.deviceId, "screen_session", session.id)
            broadcast(session)
        }
        val event = RealtimeEvent(EventTypes.SIGNAL, mapOf("sessionId" to sessionId, "payload" to payload))
        afterCommit {
            val delivered = if (fromChild) registry.sendToUser(session.requestedByUserId!!, event)
                            else registry.sendToDevice(session.deviceId, event)
            if (delivered == 0) log.debug("Signal for session {} had no live recipient", sessionId)
        }
    }

    /** Ends open sessions whose participant has no remaining realtime connection. */
    @Transactional
    fun onConnectionLost(p: AuthPrincipal) {
        val candidates = when (p.role) {
            Role.CHILD -> p.deviceId?.takeUnless { registry.isDeviceOnline(it) }
                ?.let { sessions.findByDeviceIdAndStatusIn(it, listOf(ScreenShareStatus.APPROVED, ScreenShareStatus.ACTIVE)) }
                .orEmpty()
            Role.PARENT -> if (registry.isUserOnline(p.userId)) emptyList()
                else sessions.findByStatusIn(listOf(ScreenShareStatus.ACTIVE)).filter { it.requestedByUserId == p.userId }
        }
        candidates.forEach { end(it, ScreenShareStatus.STOPPED, "PARTICIPANT_DISCONNECTED", null) }
    }

    @Scheduled(fixedDelayString = "\${familysafety.screen-sharing.expiry-sweep-ms:10000}")
    @Transactional
    fun expireOverdueSessions() {
        val now = clock.instant()
        sessions.findByStatusIn(openStatuses).filter { isOverdue(it, now) }.forEach {
            end(it, ScreenShareStatus.EXPIRED, expiryReason(it), null)
        }
    }

    private fun expireOverdue(deviceId: UUID) {
        val now = clock.instant()
        sessions.findByDeviceIdAndStatusIn(deviceId, openStatuses).filter { isOverdue(it, now) }.forEach {
            end(it, ScreenShareStatus.EXPIRED, expiryReason(it), null)
        }
        sessions.flush()
    }

    private fun isOverdue(s: ScreenSharingSession, now: java.time.Instant) = when (s.status) {
        ScreenShareStatus.REQUESTED -> !s.requestExpiresAt.isAfter(now)
        ScreenShareStatus.APPROVED, ScreenShareStatus.ACTIVE -> s.maxEndAt?.isAfter(now) == false
        else -> false
    }

    private fun expiryReason(s: ScreenSharingSession) = when (s.status) {
        ScreenShareStatus.REQUESTED -> "REQUEST_TIMEOUT"
        ScreenShareStatus.APPROVED -> "START_TIMEOUT"
        else -> "MAX_DURATION_REACHED"
    }

    private fun end(session: ScreenSharingSession, status: ScreenShareStatus, reason: String, by: UUID?) {
        session.status = status
        session.endedAt = clock.instant()
        session.endReason = reason
        session.endedByUserId = by
        sessions.save(session)
        audit.record(AuditAction.SCREEN_SHARE_END, by, null, "screen_session", session.id,
            details = mapOf("status" to status.name, "reason" to reason))
        if (status == ScreenShareStatus.REJECTED) {
            safety.raiseSystemAlert(session.familyId, session.deviceId, AlertType.SCREEN_SHARE_REJECTED,
                "Screen-sharing request was declined", "The child declined the request.", audience = AlertAudience.PARENTS)
        }
        broadcast(session)
    }

    private fun broadcast(session: ScreenSharingSession) {
        val dto = toDto(session)
        val event = RealtimeEvent(EventTypes.SCREEN_SHARE_UPDATED, dto)
        afterCommit {
            registry.sendToDevice(session.deviceId, event)
            session.requestedByUserId?.let { registry.sendToUser(it, event) }
        }
    }

    private fun loadForChild(p: AuthPrincipal, sessionId: UUID): ScreenSharingSession {
        val deviceId = access.childDeviceId(p)
        val s = sessions.findByIdForUpdate(sessionId)
        if (s == null || s.deviceId != deviceId) throw NotFoundException("SESSION_NOT_FOUND", "Session not found")
        return s
    }

    private fun loadForParticipant(p: AuthPrincipal, sessionId: UUID, lock: Boolean = true): ScreenSharingSession {
        val s = (if (lock) sessions.findByIdForUpdate(sessionId) else sessions.findById(sessionId).orElse(null)) ?: throw NotFoundException("SESSION_NOT_FOUND", "Session not found")
        val ok = when (p.role) {
            Role.CHILD -> p.deviceId == s.deviceId
            Role.PARENT -> s.familyId == access.parentFamilyId(p)
        }
        if (!ok) throw NotFoundException("SESSION_NOT_FOUND", "Session not found")
        return s
    }

    private fun invalidState(s: ScreenSharingSession) =
        ConflictException("INVALID_SESSION_STATE", "Session is ${s.status} and cannot perform this transition")

    private fun toDto(s: ScreenSharingSession, withIce: AuthPrincipal? = null): ScreenShareSessionDto {
        val requester = s.requestedByUserId?.let { users.findById(it).orElse(null) }
        val ice = if (withIce != null && (s.status == ScreenShareStatus.APPROVED || s.status == ScreenShareStatus.ACTIVE)) iceServers(withIce) else null
        return ScreenShareSessionDto(s.id, s.deviceId, s.status, s.requestedByUserId, requester?.displayName, s.requestedAt,
            s.requestExpiresAt, s.approvedAt, s.startedAt, s.endedAt, s.maxEndAt, s.endReason, ice)
    }

    /** STUN servers plus, when configured, TURN with coturn "use-auth-secret" time-limited credentials. */
    fun iceServers(p: AuthPrincipal): List<IceServerDto> {
        val cfg = props.screenSharing
        val list = mutableListOf<IceServerDto>()
        if (cfg.stunUrls.isNotEmpty()) list += IceServerDto(cfg.stunUrls)
        if (cfg.turnUrls.isNotEmpty() && cfg.turnSecret.isNotBlank()) {
            val expiry = clock.instant().plus(cfg.turnCredentialTtl).epochSecond
            val username = "$expiry:${p.userId}"
            val mac = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(cfg.turnSecret.toByteArray(), "HmacSHA1")) }
            list += IceServerDto(cfg.turnUrls, username, Base64.getEncoder().encodeToString(mac.doFinal(username.toByteArray())))
        }
        return list
    }
}
