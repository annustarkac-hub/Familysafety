package com.familysafety.backend.security

import com.familysafety.backend.config.AppProperties
import com.familysafety.backend.model.Role
import com.nimbusds.jose.jwk.source.ImmutableSecret
import com.nimbusds.jose.proc.SecurityContext
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.JwsHeader
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.security.oauth2.jwt.JwtValidators
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class IssuedAccessToken(val token: String, val expiresAt: Instant)

/**
 * Issues short-lived HS256 access tokens and generates/hashes opaque refresh tokens.
 * Refresh tokens are 256-bit random values; only their SHA-256 hash is persisted.
 */
@Component
class TokenService(private val props: AppProperties, private val clock: Clock) {
    private val random = SecureRandom()
    private val key: SecretKeySpec
    private val pairingKey: SecretKeySpec

    init {
        val secret = decodeSecret(props.security.jwtSecret, "FAMILYSAFETY_SECURITY_JWT_SECRET")
        key = SecretKeySpec(secret, "HmacSHA256")
        pairingKey = SecretKeySpec(decodeSecret(props.security.pairingPepper, "FAMILYSAFETY_SECURITY_PAIRING_PEPPER"), "HmacSHA256")
    }

    private val encoder = NimbusJwtEncoder(ImmutableSecret<SecurityContext>(key))

    val decoder: JwtDecoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build().also {
        it.setJwtValidator(JwtValidators.createDefaultWithIssuer(props.security.jwtIssuer))
    }

    fun issueAccessToken(userId: UUID, role: Role, deviceId: UUID?, sessionId: UUID): IssuedAccessToken {
        val now = clock.instant()
        val exp = now.plus(props.security.accessTokenTtl)
        val claims = JwtClaimsSet.builder()
            .issuer(props.security.jwtIssuer)
            .subject(userId.toString())
            .issuedAt(now)
            .expiresAt(exp)
            .id(UUID.randomUUID().toString())
            .claim("role", role.name)
            .claim("sid", sessionId.toString())
            .apply { if (deviceId != null) claim("did", deviceId.toString()) }
            .build()
        val header = JwsHeader.with(MacAlgorithm.HS256).build()
        return IssuedAccessToken(encoder.encode(JwtEncoderParameters.from(header, claims)).tokenValue, exp)
    }

    /** Returns an opaque URL-safe refresh token (256 bits of entropy). */
    fun newRefreshToken(): String = randomToken(32)

    fun randomToken(bytes: Int): String {
        val buf = ByteArray(bytes)
        random.nextBytes(buf)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf)
    }

    fun sha256Hex(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    /** Keyed hash for low-entropy pairing codes so a database leak does not allow offline brute force. */
    fun pairingCodeHash(normalizedCode: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(pairingKey)
        return mac.doFinal(normalizedCode.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    fun nextInt(bound: Int): Int = random.nextInt(bound)

    private fun decodeSecret(value: String, envName: String): ByteArray {
        require(value.isNotBlank()) { "$envName must be configured (base64, >= 32 bytes)" }
        val bytes = runCatching { Base64.getDecoder().decode(value) }.getOrElse {
            throw IllegalStateException("$envName must be valid base64")
        }
        require(bytes.size >= 32) { "$envName must decode to at least 32 bytes" }
        return bytes
    }
}
