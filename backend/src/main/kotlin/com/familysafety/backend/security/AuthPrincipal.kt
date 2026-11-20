package com.familysafety.backend.security

import com.familysafety.backend.model.Role
import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.jwt.Jwt
import java.util.UUID

/** Identity extracted from a validated access token whose server-side session is still active. */
data class AuthPrincipal(
    val userId: UUID,
    val role: Role,
    val deviceId: UUID?,
    val sessionId: UUID,
) {
    val isParent get() = role == Role.PARENT
    val isChild get() = role == Role.CHILD
}

class FamilyAuthentication(private val principal: AuthPrincipal, private val jwt: Jwt) :
    AbstractAuthenticationToken(listOf(SimpleGrantedAuthority("ROLE_${principal.role.name}"))) {
    init { isAuthenticated = true }
    override fun getCredentials(): Any = jwt
    override fun getPrincipal(): AuthPrincipal = principal
    override fun getName(): String = principal.userId.toString()
}
