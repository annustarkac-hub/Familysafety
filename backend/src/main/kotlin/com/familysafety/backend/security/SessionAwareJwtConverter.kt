package com.familysafety.backend.security

import com.familysafety.backend.model.AuthSessionStatus
import com.familysafety.backend.model.DeviceStatus
import com.familysafety.backend.model.Role
import com.familysafety.backend.repository.AuthenticationSessionRepository
import com.familysafety.backend.repository.DeviceRepository
import org.springframework.core.convert.converter.Converter
import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException
import org.springframework.stereotype.Component
import java.time.Clock
import java.util.UUID

/**
 * Converts a signature-valid JWT into an [AuthPrincipal] only if the backing authentication session is still
 * ACTIVE and the bound device has not been revoked. This makes logout and device revocation take effect
 * immediately instead of waiting for access-token expiry.
 */
@Component
class SessionAwareJwtConverter(
    private val sessions: AuthenticationSessionRepository,
    private val devices: DeviceRepository,
    private val clock: Clock,
) : Converter<Jwt, AbstractAuthenticationToken> {

    override fun convert(jwt: Jwt): AbstractAuthenticationToken = FamilyAuthentication(resolve(jwt), jwt)

    fun resolve(jwt: Jwt): AuthPrincipal {
        val userId = parseUuid(jwt.subject)
        val sessionId = parseUuid(jwt.getClaimAsString("sid"))
        val role = runCatching { Role.valueOf(jwt.getClaimAsString("role")) }.getOrElse { throw InvalidBearerTokenException("invalid role") }
        val deviceId = jwt.getClaimAsString("did")?.let { parseUuid(it) }

        val session = sessions.findById(sessionId).orElse(null)
        if (session == null || session.userId != userId || session.status != AuthSessionStatus.ACTIVE ||
            session.absoluteExpiresAt.isBefore(clock.instant())
        ) {
            throw InvalidBearerTokenException("session is no longer active")
        }
        if (deviceId != null) {
            val device = devices.findById(deviceId).orElse(null)
            if (device == null || device.status != DeviceStatus.ACTIVE || device.userId != userId) {
                throw InvalidBearerTokenException("device is revoked")
            }
        }
        return AuthPrincipal(userId, role, deviceId, sessionId)
    }

    private fun parseUuid(v: String?): UUID =
        runCatching { UUID.fromString(v) }.getOrElse { throw InvalidBearerTokenException("malformed token claims") }
}
