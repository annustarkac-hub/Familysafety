package com.familysafety.backend.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "familysafety")
data class AppProperties(
    val security: Security = Security(),
    val pairing: Pairing = Pairing(),
    val screenSharing: ScreenSharing = ScreenSharing(),
    val retention: Retention = Retention(),
    val rateLimit: RateLimit = RateLimit(),
) {
    data class Security(
        /** Base64-encoded HMAC secret, at least 32 bytes after decoding. Required. */
        val jwtSecret: String = "",
        val jwtIssuer: String = "family-safety",
        val accessTokenTtl: Duration = Duration.ofMinutes(15),
        val refreshTokenTtl: Duration = Duration.ofDays(30),
        /** Hard upper bound for a refresh-token chain regardless of rotation. */
        val refreshTokenAbsoluteTtl: Duration = Duration.ofDays(90),
        /** Server-side pepper for hashing pairing codes (HMAC-SHA256). Required. */
        val pairingPepper: String = "",
        val maxFailedLogins: Int = 5,
        val lockoutDuration: Duration = Duration.ofMinutes(15),
        /** When true, requests not arriving over HTTPS (directly or via X-Forwarded-Proto) are rejected. */
        val requireHttps: Boolean = false,
    )

    data class Pairing(
        val codeTtl: Duration = Duration.ofMinutes(10),
        val maxActiveCodesPerFamily: Int = 5,
        /** Per-child-account cap on pairing attempts per hour (in addition to the per-minute limit). */
        val maxAttemptsPerHour: Int = 20,
    )

    data class ScreenSharing(
        val requestTtl: Duration = Duration.ofSeconds(90),
        val approvalToStartTtl: Duration = Duration.ofMinutes(2),
        val maxSessionDuration: Duration = Duration.ofMinutes(30),
        val stunUrls: List<String> = listOf("stun:stun.l.google.com:19302"),
        val turnUrls: List<String> = emptyList(),
        /** Shared secret for coturn "use-auth-secret" ephemeral credentials. Empty disables TURN credentials. */
        val turnSecret: String = "",
        val turnCredentialTtl: Duration = Duration.ofHours(1),
        val maxSignalBytes: Int = 64 * 1024,
    )

    data class Retention(
        val auditLogDays: Long = 365,
        val alertDays: Long = 180,
        val screenSessionDays: Long = 90,
        val expiredAuthSessionDays: Long = 7,
    )

    data class RateLimit(
        val enabled: Boolean = true,
        val authPerMinute: Int = 10,
        val pairingAcceptPerMinute: Int = 5,
        val generalPerMinute: Int = 240,
    )
}
