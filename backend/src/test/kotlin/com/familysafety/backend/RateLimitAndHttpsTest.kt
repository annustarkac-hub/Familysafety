package com.familysafety.backend

import com.familysafety.backend.support.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.test.context.TestPropertySource

@TestPropertySource(properties = ["familysafety.security.require-https=true"])
class HttpsEnforcementTest : IntegrationTestBase() {
    @Test
    fun `plain http api requests are refused when https is required`() {
        val r = post("/api/v1/auth/login", mapOf("email" to "a@b.test", "password" to "password password", "device" to device()))
        assertThat(r.status).isEqualTo(403)
        assertThat(r.text("/code")).isEqualTo("HTTPS_REQUIRED")
        assertThat(get("/actuator/health").status).isEqualTo(200)
    }
}

@TestPropertySource(properties = ["familysafety.rate-limit.enabled=true", "familysafety.rate-limit.auth-per-minute=3"])
class RateLimitTest : IntegrationTestBase() {
    @Test
    fun `auth endpoints are rate limited per client ip with retry-after`() {
        val statuses = (1..5).map {
            post("/api/v1/auth/login", mapOf("email" to "rl@example.test", "password" to "password password", "device" to device()))
        }
        assertThat(statuses.take(3).map { it.status }).containsOnly(401)
        assertThat(statuses.last().status).isEqualTo(429)
        assertThat(statuses.last().headers["retry-after"]).isNotEmpty
    }
}
