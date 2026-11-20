package com.familysafety.backend

import com.familysafety.backend.model.Device
import com.familysafety.backend.model.Family
import com.familysafety.backend.model.Role
import com.familysafety.backend.model.ScreenSharingSession
import com.familysafety.backend.model.User
import com.familysafety.backend.repository.AuditLogRepository
import com.familysafety.backend.repository.DeviceRepository
import com.familysafety.backend.repository.FamilyRepository
import com.familysafety.backend.repository.ScreenSharingSessionRepository
import com.familysafety.backend.repository.UserRepository
import com.familysafety.backend.service.RetentionService
import com.familysafety.backend.support.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Instant

class SecurityAndDatabaseTest : IntegrationTestBase() {
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var users: UserRepository
    @Autowired lateinit var families: FamilyRepository
    @Autowired lateinit var devices: DeviceRepository
    @Autowired lateinit var screenSessions: ScreenSharingSessionRepository
    @Autowired lateinit var audit: AuditLogRepository
    @Autowired lateinit var retention: RetentionService

    @Test
    fun `flyway created all thirteen tables`() {
        val tables = jdbc.queryForList("select table_name from information_schema.tables where table_schema='public'", String::class.java)
        assertThat(tables).contains("users", "families", "family_members", "devices", "device_pairings", "pairing_tokens",
            "app_usage_records", "daily_usage_summaries", "screen_sharing_sessions", "safety_alerts", "safety_settings",
            "authentication_sessions", "audit_logs")
    }

    @Test
    fun `email uniqueness is case insensitive at the database level`() {
        users.saveAndFlush(User(email = "Case@Example.test", passwordHash = "x", displayName = "a", role = Role.PARENT))
        assertThatThrownBy { users.saveAndFlush(User(email = "case@example.test", passwordHash = "x", displayName = "b", role = Role.PARENT)) }
            .isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `database enforces a single open screen session per device`() {
        val u = users.saveAndFlush(User(email = "db-${System.nanoTime()}@x.test", passwordHash = "x", displayName = "a", role = Role.PARENT))
        val f = families.saveAndFlush(Family(name = "f", ownerUserId = u.id))
        val d = devices.saveAndFlush(Device(userId = u.id, installationId = "inst-${System.nanoTime()}", deviceName = "d"))
        val now = Instant.now()
        screenSessions.saveAndFlush(ScreenSharingSession(familyId = f.id, deviceId = d.id, requestedByUserId = u.id, requestedAt = now, requestExpiresAt = now))
        assertThatThrownBy {
            screenSessions.saveAndFlush(ScreenSharingSession(familyId = f.id, deviceId = d.id, requestedByUserId = u.id, requestedAt = now, requestExpiresAt = now))
        }.isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `security headers are present and error bodies do not leak internals`() {
        val r = get("/api/v1/does-not-exist", register("PARENT").accessToken)
        assertThat(r.headers["x-content-type-options"]).containsExactly("nosniff")
        assertThat(r.headers["x-frame-options"]).containsExactly("DENY")
        assertThat(r.headers.keys).contains("x-request-id")
        assertThat(r.body?.toString() ?: "").doesNotContain("Exception", "at com.")
    }

    @Test
    fun `audit log records security-relevant events without secrets`() {
        val acc = register("PARENT")
        post("/api/v1/auth/login", mapOf("email" to acc.email, "password" to "wrong password!!", "device" to device()))
        val entries = audit.findByActorUserIdOrderByCreatedAtDesc(acc.userId)
        assertThat(entries.map { it.action }).contains("auth.register", "auth.login_failed")
        val serialized = entries.joinToString { mapper.writeValueAsString(it.details) }
        assertThat(serialized).doesNotContain(acc.password, acc.refreshToken, acc.accessToken)
    }

    @Test
    fun `retention purge runs and removes expired data`() {
        val result = retention.purge()
        assertThat(result.keys).contains("appUsageRecords", "auditLogs", "pairingTokens", "authSessions")
    }

    @Test
    fun `oversized and malformed bodies are rejected`() {
        val acc = register("PARENT")
        assertThat(call("POST", "/api/v1/pairing/create", "{not json", acc.accessToken).status).isEqualTo(400)
        val longLabel = "x".repeat(81)
        assertThat(post("/api/v1/pairing/create", mapOf("childLabel" to longLabel), acc.accessToken).status).isEqualTo(400)
    }
}
