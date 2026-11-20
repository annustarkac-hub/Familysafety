package com.familysafety.backend.service

import com.familysafety.backend.audit.AuditAction
import com.familysafety.backend.audit.AuditService
import com.familysafety.backend.common.ApiException
import com.familysafety.backend.common.ConflictException
import com.familysafety.backend.common.UnauthorizedException
import com.familysafety.backend.config.AppProperties
import com.familysafety.backend.model.AuditOutcome
import com.familysafety.backend.model.AuthSessionStatus
import com.familysafety.backend.model.AuthenticationSession
import com.familysafety.backend.model.Device
import com.familysafety.backend.model.DeviceStatus
import com.familysafety.backend.model.Family
import com.familysafety.backend.model.FamilyMember
import com.familysafety.backend.model.PairingStatus
import com.familysafety.backend.model.Role
import com.familysafety.backend.model.User
import com.familysafety.backend.model.UserStatus
import com.familysafety.backend.realtime.ConnectionRegistry
import com.familysafety.backend.realtime.afterCommit
import com.familysafety.backend.repository.AuthenticationSessionRepository
import com.familysafety.backend.repository.DevicePairingRepository
import com.familysafety.backend.repository.DeviceRepository
import com.familysafety.backend.repository.FamilyMemberRepository
import com.familysafety.backend.repository.FamilyRepository
import com.familysafety.backend.repository.UserRepository
import com.familysafety.backend.security.AuthPrincipal
import com.familysafety.backend.security.TokenService
import com.familysafety.backend.web.DeviceInfoRequest
import com.familysafety.backend.web.LoginRequest
import com.familysafety.backend.web.MeResponse
import com.familysafety.backend.web.RegisterRequest
import com.familysafety.backend.web.TokenResponse
import com.familysafety.backend.web.UserDto
import org.springframework.http.HttpStatus
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

data class ClientContext(val ip: String?, val userAgent: String?)

@Service
class AuthService(
    private val users: UserRepository,
    private val families: FamilyRepository,
    private val members: FamilyMemberRepository,
    private val devices: DeviceRepository,
    private val pairings: DevicePairingRepository,
    private val sessions: AuthenticationSessionRepository,
    private val passwordEncoder: PasswordEncoder,
    private val tokens: TokenService,
    private val audit: AuditService,
    private val registry: ConnectionRegistry,
    private val props: AppProperties,
    private val clock: Clock,
) {
    /** Pre-computed hash used to equalise timing when the account does not exist. */
    private val dummyHash: String by lazy { passwordEncoder.encode("timing-equaliser-not-a-password") }

    @Transactional
    fun register(req: RegisterRequest, ctx: ClientContext): TokenResponse {
        val email = req.email.trim().lowercase()
        if (users.findByEmailIgnoreCase(email) != null) {
            throw ConflictException("EMAIL_ALREADY_REGISTERED", "An account with this email already exists")
        }
        val user = users.save(User(email = email, passwordHash = passwordEncoder.encode(req.password),
            displayName = req.displayName.trim(), role = req.role))
        if (req.role == Role.PARENT) {
            val family = families.save(Family(name = "${user.displayName}'s family", ownerUserId = user.id))
            members.save(FamilyMember(familyId = family.id, userId = user.id, memberRole = Role.PARENT))
        }
        val device = upsertDevice(user.id, req.device)
        audit.record(AuditAction.REGISTER, user.id, device.id, "user", user.id, details = mapOf("role" to user.role.name))
        return issueSession(user, device, UUID.randomUUID(), null, ctx)
    }

    @Transactional(noRollbackFor = [ApiException::class])
    fun login(req: LoginRequest, ctx: ClientContext): TokenResponse {
        val now = clock.instant()
        val user = users.findByEmailIgnoreCase(req.email.trim())
        if (user == null) {
            passwordEncoder.matches(req.password, dummyHash)
            audit.record(AuditAction.LOGIN_FAILED, null, outcome = AuditOutcome.FAILURE, details = mapOf("reason" to "unknown_account"))
            throw invalidCredentials()
        }
        if (user.lockedUntil?.isAfter(now) == true) {
            audit.record(AuditAction.LOGIN_FAILED, user.id, outcome = AuditOutcome.DENIED, details = mapOf("reason" to "locked"))
            throw ApiException(HttpStatus.LOCKED, "ACCOUNT_LOCKED", "Too many failed attempts. Try again later.")
        }
        if (!passwordEncoder.matches(req.password, user.passwordHash) || user.status != UserStatus.ACTIVE) {
            user.failedLoginAttempts += 1
            if (user.failedLoginAttempts >= props.security.maxFailedLogins) {
                user.lockedUntil = now.plus(props.security.lockoutDuration)
                user.failedLoginAttempts = 0
            }
            users.save(user)
            audit.record(AuditAction.LOGIN_FAILED, user.id, outcome = AuditOutcome.FAILURE, details = mapOf("reason" to "bad_password"))
            throw invalidCredentials()
        }
        user.failedLoginAttempts = 0
        user.lockedUntil = null
        if (passwordEncoder.upgradeEncoding(user.passwordHash)) user.passwordHash = passwordEncoder.encode(req.password)
        users.save(user)
        val device = upsertDevice(user.id, req.device)
        audit.record(AuditAction.LOGIN, user.id, device.id, "device", device.id)
        return issueSession(user, device, UUID.randomUUID(), null, ctx)
    }

    /**
     * Refresh-token rotation with reuse detection: each refresh token is single-use. Presenting an already
     * rotated token indicates theft/replay, so the whole token family (all descendants) is revoked.
     */
    @Transactional(noRollbackFor = [ApiException::class])
    fun refresh(refreshToken: String, ctx: ClientContext): TokenResponse {
        val now = clock.instant()
        val session = sessions.findByRefreshTokenHash(tokens.sha256Hex(refreshToken))
            ?: throw UnauthorizedException("INVALID_REFRESH_TOKEN", "Refresh token is invalid")
        when (session.status) {
            AuthSessionStatus.ROTATED -> {
                val chain = sessions.findByTokenFamilyIdAndStatus(session.tokenFamilyId, AuthSessionStatus.ACTIVE)
                chain.forEach { revoke(it, "REFRESH_TOKEN_REUSE") }
                audit.record(AuditAction.REFRESH_REUSE, session.userId, session.deviceId, "auth_session", session.id,
                    AuditOutcome.DENIED, mapOf("revokedSessions" to chain.size))
                throw UnauthorizedException("REFRESH_TOKEN_REUSED", "Refresh token was already used; all related sessions were revoked")
            }
            AuthSessionStatus.REVOKED -> throw UnauthorizedException("SESSION_REVOKED", "Session has been revoked")
            AuthSessionStatus.ACTIVE -> Unit
        }
        if (session.expiresAt.isBefore(now) || session.absoluteExpiresAt.isBefore(now)) {
            revoke(session, "EXPIRED")
            throw UnauthorizedException("REFRESH_TOKEN_EXPIRED", "Refresh token has expired; please sign in again")
        }
        val user = users.findById(session.userId).orElseThrow { UnauthorizedException("INVALID_REFRESH_TOKEN", "Refresh token is invalid") }
        val device = session.deviceId?.let { devices.findById(it).orElse(null) }
        if (device == null || device.status != DeviceStatus.ACTIVE || user.status != UserStatus.ACTIVE) {
            revoke(session, "DEVICE_REVOKED")
            throw UnauthorizedException("SESSION_REVOKED", "Session has been revoked")
        }
        val response = issueSession(user, device, session.tokenFamilyId, session.absoluteExpiresAt, ctx)
        session.status = AuthSessionStatus.ROTATED
        session.lastUsedAt = now
        session.replacedBySessionId = sessions.findByRefreshTokenHash(tokens.sha256Hex(response.refreshToken))?.id
        sessions.save(session)
        audit.record(AuditAction.REFRESH, user.id, device.id, "auth_session", session.id)
        return response
    }

    @Transactional
    fun logout(p: AuthPrincipal) {
        sessions.findById(p.sessionId).ifPresent { revoke(it, "LOGOUT") }
        audit.record(AuditAction.LOGOUT, p.userId, p.deviceId, "auth_session", p.sessionId)
        afterCommit { registry.closeAuthSession(p.sessionId, "logged_out") }
    }

    @Transactional(readOnly = true)
    fun me(p: AuthPrincipal): MeResponse {
        val user = users.findById(p.userId).orElseThrow()
        val paired = p.deviceId?.let { pairings.findByDeviceIdAndStatus(it, PairingStatus.ACTIVE) } != null
        return MeResponse(userDto(user), p.deviceId, paired)
    }

    fun userDto(user: User): UserDto {
        val member = members.findByUserId(user.id).firstOrNull()
        val family = member?.let { families.findById(it.familyId).orElse(null) }
        return UserDto(user.id, user.email, user.displayName, user.role, family?.id, family?.name)
    }

    private fun upsertDevice(userId: UUID, info: DeviceInfoRequest): Device {
        val existing = devices.findByUserIdAndInstallationId(userId, info.installationId)
        val device = existing ?: Device(userId = userId, installationId = info.installationId, deviceName = info.deviceName)
        device.deviceName = info.deviceName.trim()
        device.manufacturer = info.manufacturer
        device.model = info.model
        device.osVersion = info.osVersion
        device.appVersion = info.appVersion
        device.lastSeenAt = clock.instant()
        if (device.status == DeviceStatus.REVOKED) {
            // A revoked device may sign in again, but it comes back UNPAIRED: its old pairing stays revoked
            // and must be re-established through a new parent-issued pairing code.
            device.status = DeviceStatus.ACTIVE
            device.revokedAt = null
        }
        return devices.save(device)
    }

    private fun issueSession(user: User, device: Device, tokenFamilyId: UUID, absoluteExpiry: java.time.Instant?, ctx: ClientContext): TokenResponse {
        val now = clock.instant()
        val refreshToken = tokens.newRefreshToken()
        val absolute = absoluteExpiry ?: now.plus(props.security.refreshTokenAbsoluteTtl)
        val expires = minOf(now.plus(props.security.refreshTokenTtl), absolute)
        val session = sessions.save(AuthenticationSession(
            userId = user.id, deviceId = device.id, tokenFamilyId = tokenFamilyId,
            refreshTokenHash = tokens.sha256Hex(refreshToken), expiresAt = expires, absoluteExpiresAt = absolute,
            lastUsedAt = now, ipAddress = ctx.ip, userAgent = ctx.userAgent?.take(255),
        ))
        val access = tokens.issueAccessToken(user.id, user.role, device.id, session.id)
        return TokenResponse(access.token, access.expiresAt, refreshToken, expires, user = userDto(user), deviceId = device.id)
    }

    private fun revoke(session: AuthenticationSession, reason: String) {
        session.status = AuthSessionStatus.REVOKED
        session.revokedAt = clock.instant()
        session.revokeReason = reason
        sessions.save(session)
        afterCommit { registry.closeAuthSession(session.id, "session_revoked") }
    }

    private fun invalidCredentials() = UnauthorizedException("INVALID_CREDENTIALS", "Email or password is incorrect")
}
