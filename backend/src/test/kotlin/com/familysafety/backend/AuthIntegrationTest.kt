package com.familysafety.backend

import com.familysafety.backend.model.AuthSessionStatus
import com.familysafety.backend.repository.AuditLogRepository
import com.familysafety.backend.repository.AuthenticationSessionRepository
import com.familysafety.backend.repository.UserRepository
import com.familysafety.backend.support.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID

class AuthIntegrationTest : IntegrationTestBase() {
    @Autowired lateinit var users: UserRepository
    @Autowired lateinit var sessions: AuthenticationSessionRepository
    @Autowired lateinit var audit: AuditLogRepository

    @Test
    fun `parent registration creates family and returns tokens`() {
        val parent = register("PARENT", "Alex")
        val me = get("/api/v1/auth/me", parent.accessToken)
        assertThat(me.status).isEqualTo(200)
        assertThat(me.text("/user/role")).isEqualTo("PARENT")
        assertThat(me.text("/user/familyName")).isEqualTo("Alex's family")
        assertThat(me.body!!.at("/paired").asBoolean()).isFalse()
    }

    @Test
    fun `passwords are stored with argon2id and never in plain text`() {
        val acc = register("CHILD")
        val hash = users.findById(acc.userId).get().passwordHash
        assertThat(hash).startsWith("\$argon2id\$").doesNotContain(acc.password)
    }

    @Test
    fun `duplicate email is rejected case-insensitively`() {
        val acc = register("PARENT")
        val r = post("/api/v1/auth/register", mapOf("email" to acc.email.uppercase(), "password" to "another long password",
            "displayName" to "Dup", "role" to "PARENT", "device" to device()))
        assertThat(r.status).isEqualTo(409)
        assertThat(r.text("/code")).isEqualTo("EMAIL_ALREADY_REGISTERED")
    }

    @Test
    fun `registration validates input`() {
        val r = post("/api/v1/auth/register", mapOf("email" to "not-an-email", "password" to "short", "displayName" to "",
            "role" to "PARENT", "device" to mapOf("installationId" to "x", "deviceName" to "")))
        assertThat(r.status).isEqualTo(400)
        assertThat(r.text("/code")).isEqualTo("VALIDATION_FAILED")
        val fields = r.body!!.at("/fieldErrors").map { it.at("/field").asText() }
        assertThat(fields).contains("email", "password", "displayName", "device.installationId", "device.deviceName")
    }

    @Test
    fun `malformed role is rejected`() {
        val r = post("/api/v1/auth/register", mapOf("email" to "a@b.test", "password" to "long enough password",
            "displayName" to "x", "role" to "ADMIN", "device" to device()))
        assertThat(r.status).isEqualTo(400)
    }

    @Test
    fun `login succeeds with correct password and fails generically otherwise`() {
        val acc = register("PARENT")
        val ok = post("/api/v1/auth/login", mapOf("email" to acc.email, "password" to acc.password, "device" to device()))
        assertThat(ok.status).isEqualTo(200)
        assertThat(ok.text("/tokenType")).isEqualTo("Bearer")

        val bad = post("/api/v1/auth/login", mapOf("email" to acc.email, "password" to "wrong password!!", "device" to device()))
        assertThat(bad.status).isEqualTo(401)
        assertThat(bad.text("/code")).isEqualTo("INVALID_CREDENTIALS")

        val unknown = post("/api/v1/auth/login", mapOf("email" to "nobody-${UUID.randomUUID()}@example.test", "password" to "whatever password", "device" to device()))
        assertThat(unknown.status).isEqualTo(401)
        assertThat(unknown.text("/code")).isEqualTo("INVALID_CREDENTIALS")
    }

    @Test
    fun `account locks after repeated failures`() {
        val acc = register("PARENT")
        repeat(5) { post("/api/v1/auth/login", mapOf("email" to acc.email, "password" to "wrong password!!", "device" to device())) }
        val locked = post("/api/v1/auth/login", mapOf("email" to acc.email, "password" to acc.password, "device" to device()))
        assertThat(locked.status).isEqualTo(423)
        assertThat(locked.text("/code")).isEqualTo("ACCOUNT_LOCKED")
    }

    @Test
    fun `refresh rotates tokens and stores only hashes`() {
        val acc = register("PARENT")
        val r1 = post("/api/v1/auth/refresh", mapOf("refreshToken" to acc.refreshToken))
        assertThat(r1.status).isEqualTo(200)
        val newRefresh = r1.text("/refreshToken")
        assertThat(newRefresh).isNotEqualTo(acc.refreshToken)
        assertThat(sessions.findAll().map { it.refreshTokenHash }).doesNotContain(acc.refreshToken, newRefresh)
        assertThat(get("/api/v1/auth/me", r1.text("/accessToken")).status).isEqualTo(200)
        // old access token is bound to the rotated session and is no longer accepted
        assertThat(get("/api/v1/auth/me", acc.accessToken).status).isEqualTo(401)
    }

    @Test
    fun `refresh token reuse revokes the whole token family`() {
        val acc = register("PARENT")
        val r1 = post("/api/v1/auth/refresh", mapOf("refreshToken" to acc.refreshToken))
        val latestAccess = r1.text("/accessToken")
        val replay = post("/api/v1/auth/refresh", mapOf("refreshToken" to acc.refreshToken))
        assertThat(replay.status).isEqualTo(401)
        assertThat(replay.text("/code")).isEqualTo("REFRESH_TOKEN_REUSED")
        // The legitimately rotated descendant is revoked too.
        assertThat(get("/api/v1/auth/me", latestAccess).status).isEqualTo(401)
        assertThat(post("/api/v1/auth/refresh", mapOf("refreshToken" to r1.text("/refreshToken"))).status).isEqualTo(401)
        assertThat(audit.findByActionOrderByCreatedAtDesc("auth.refresh_token_reuse")).isNotEmpty
    }

    @Test
    fun `logout revokes the session immediately`() {
        val acc = register("CHILD")
        assertThat(post("/api/v1/auth/logout", null, acc.accessToken).status).isEqualTo(204)
        assertThat(get("/api/v1/auth/me", acc.accessToken).status).isEqualTo(401)
        assertThat(post("/api/v1/auth/refresh", mapOf("refreshToken" to acc.refreshToken)).status).isEqualTo(401)
        assertThat(sessions.findAll().filter { it.userId == acc.userId }.map { it.status }).containsOnly(AuthSessionStatus.REVOKED)
    }

    @Test
    fun `invalid refresh token is rejected`() {
        val r = post("/api/v1/auth/refresh", mapOf("refreshToken" to "definitely-not-a-valid-token"))
        assertThat(r.status).isEqualTo(401)
        assertThat(r.text("/code")).isEqualTo("INVALID_REFRESH_TOKEN")
    }
}
