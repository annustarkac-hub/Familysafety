package com.familysafety.backend

import com.familysafety.backend.repository.AuthenticationSessionRepository
import com.familysafety.backend.repository.PairingTokenRepository
import com.familysafety.backend.support.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Instant
import java.time.LocalDate

class PairingAndAuthorizationTest : IntegrationTestBase() {
    @Autowired lateinit var pairingTokens: PairingTokenRepository
    @Autowired lateinit var authSessions: AuthenticationSessionRepository

    @Test
    fun `parent creates code and child accepts it`() {
        val parent = register("PARENT", "Pat")
        val child = register("CHILD", "Casey")
        val created = post("/api/v1/pairing/create", mapOf("childLabel" to "Casey's phone"), parent.accessToken)
        assertThat(created.status).isEqualTo(201)
        val code = created.text("/code")
        assertThat(code).matches("^[2-9A-HJKMNP-Z]{4}-[2-9A-HJKMNP-Z]{4}$")
        // code is stored only as a keyed hash
        assertThat(pairingTokens.findAll().map { it.codeHash }).doesNotContain(code, code.replace("-", ""))

        val accepted = post("/api/v1/pairing/accept", mapOf("code" to code.lowercase().replace("-", " ")), child.accessToken)
        assertThat(accepted.status).isEqualTo(200)
        assertThat(accepted.uuid("/deviceId")).isEqualTo(child.deviceId)
        assertThat(accepted.body!!.at("/parents").map { it.asText() }).containsExactly("Pat")

        val devices = get("/api/v1/devices", parent.accessToken)
        assertThat(devices.status).isEqualTo(200)
        assertThat(devices.body!!.map { it.at("/id").asText() }).containsExactly(child.deviceId.toString())
        assertThat(devices.body!![0].at("/ownerDisplayName").asText()).isEqualTo("Casey")
        assertThat(get("/api/v1/auth/me", child.accessToken).body!!.at("/paired").asBoolean()).isTrue()
    }

    @Test
    fun `pairing code is single use`() {
        val parent = register("PARENT")
        val c1 = register("CHILD")
        val c2 = register("CHILD")
        val code = post("/api/v1/pairing/create", null, parent.accessToken).text("/code")
        assertThat(post("/api/v1/pairing/accept", mapOf("code" to code), c1.accessToken).status).isEqualTo(200)
        val second = post("/api/v1/pairing/accept", mapOf("code" to code), c2.accessToken)
        assertThat(second.status).isEqualTo(400)
        assertThat(second.text("/code")).isEqualTo("INVALID_PAIRING_CODE")
    }

    @Test
    fun `expired code is rejected`() {
        val parent = register("PARENT")
        val child = register("CHILD")
        val created = post("/api/v1/pairing/create", null, parent.accessToken)
        val token = pairingTokens.findById(created.uuid("/pairingTokenId")).get()
        token.expiresAt = Instant.now().minusSeconds(5)
        pairingTokens.save(token)
        val r = post("/api/v1/pairing/accept", mapOf("code" to created.text("/code")), child.accessToken)
        assertThat(r.status).isEqualTo(410)
        assertThat(r.text("/code")).isEqualTo("PAIRING_CODE_EXPIRED")
    }

    @Test
    fun `wrong code is rejected and attempts are rate limited per child`() {
        val child = register("CHILD")
        val statuses = (1..7).map { post("/api/v1/pairing/accept", mapOf("code" to "ZZZZ-ZZZZ"), child.accessToken).status }
        assertThat(statuses.take(5)).containsOnly(400)
        assertThat(statuses.drop(5)).containsOnly(429)
    }

    @Test
    fun `child cannot create pairing codes and parent cannot accept them`() {
        val parent = register("PARENT")
        val child = register("CHILD")
        assertThat(post("/api/v1/pairing/create", null, child.accessToken).status).isEqualTo(403)
        val code = post("/api/v1/pairing/create", null, parent.accessToken).text("/code")
        assertThat(post("/api/v1/pairing/accept", mapOf("code" to code), parent.accessToken).status).isEqualTo(403)
    }

    @Test
    fun `unauthenticated and forged requests are rejected`() {
        assertThat(get("/api/v1/devices").status).isEqualTo(401)
        assertThat(get("/api/v1/devices", "not.a.jwt").status).isEqualTo(401)
        // Token signed with a different key
        val forged = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIwMDAwMDAwMC0wMDAwLTAwMDAtMDAwMC0wMDAwMDAwMDAwMDAiLCJyb2xlIjoiUEFSRU5UIn0.c2lnbmF0dXJl"
        assertThat(get("/api/v1/devices", forged).status).isEqualTo(401)
    }

    @Test
    fun `parent of another family cannot see or act on a device`() {
        val (_, child) = pairedFamily()
        val stranger = register("PARENT", "Stranger")
        val today = LocalDate.now()
        assertThat(get("/api/v1/devices/${child.deviceId}", stranger.accessToken).status).isEqualTo(404)
        assertThat(get("/api/v1/usage/daily?deviceId=${child.deviceId}&date=$today", stranger.accessToken).status).isEqualTo(404)
        assertThat(call("DELETE", "/api/v1/devices/${child.deviceId}", null, stranger.accessToken).status).isEqualTo(404)
        assertThat(post("/api/v1/screen-sharing/request", mapOf("deviceId" to child.deviceId), stranger.accessToken).status).isEqualTo(404)
        assertThat(get("/api/v1/devices", stranger.accessToken).body!!.size()).isZero()
    }

    @Test
    fun `child cannot read another child's data or use parent-only endpoints`() {
        val (_, child) = pairedFamily()
        val (_, otherChild) = pairedFamily()
        assertThat(get("/api/v1/usage/daily?deviceId=${otherChild.deviceId}&date=${LocalDate.now()}", child.accessToken).status).isEqualTo(404)
        assertThat(call("DELETE", "/api/v1/devices/${child.deviceId}", null, child.accessToken).status).isEqualTo(403)
        assertThat(post("/api/v1/screen-sharing/request", mapOf("deviceId" to child.deviceId), child.accessToken).status).isEqualTo(403)
        assertThat(call("PUT", "/api/v1/safety/settings", mapOf("deviceId" to child.deviceId, "dailyLimitMinutes" to 600), child.accessToken).status).isEqualTo(403)
    }

    @Test
    fun `device revocation unpairs, revokes sessions and blocks the device token`() {
        val (parent, child) = pairedFamily()
        val del = call("DELETE", "/api/v1/devices/${child.deviceId}", mapOf("reason" to "Lost phone"), parent.accessToken)
        assertThat(del.status).isEqualTo(204)
        assertThat(get("/api/v1/auth/me", child.accessToken).status).isEqualTo(401)
        assertThat(post("/api/v1/auth/refresh", mapOf("refreshToken" to child.refreshToken)).status).isEqualTo(401)
        assertThat(get("/api/v1/devices", parent.accessToken).body!!.size()).isZero()
        assertThat(get("/api/v1/devices/${child.deviceId}", parent.accessToken).status).isEqualTo(404)

        // The child can sign in again, but the device comes back unpaired and must be re-paired with a new code.
        val relogin = post("/api/v1/auth/login", mapOf("email" to child.email, "password" to child.password, "device" to device()))
        assertThat(relogin.status).isEqualTo(200)
        assertThat(get("/api/v1/auth/me", relogin.text("/accessToken")).body!!.at("/paired").asBoolean()).isFalse()
        val alerts = get("/api/v1/safety/alerts", parent.accessToken).body!!.at("/alerts").map { it.at("/type").asText() }
        assertThat(alerts).contains("DEVICE_REVOKED", "DEVICE_PAIRED")
    }

    @Test
    fun `child disconnect request notifies parents`() {
        val (parent, child) = pairedFamily()
        assertThat(post("/api/v1/devices/${child.deviceId}/disconnect-request", null, child.accessToken).status).isEqualTo(202)
        val types = get("/api/v1/safety/alerts", parent.accessToken).body!!.at("/alerts").map { it.at("/type").asText() }
        assertThat(types).contains("DISCONNECT_REQUEST")
    }
}
