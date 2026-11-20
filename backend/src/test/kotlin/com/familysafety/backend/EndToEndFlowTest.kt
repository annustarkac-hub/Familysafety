package com.familysafety.backend

import com.familysafety.backend.support.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit

/**
 * Server-side end-to-end scenario covering the 10 required steps over real HTTP + WebSocket.
 * Step 8 ("live screen transmission") is verified here at the signaling layer only (SDP/ICE relay between
 * the two authorised participants); actual video capture/transport requires physical devices — see TESTING.md.
 */
class EndToEndFlowTest : IntegrationTestBase() {
    @Test
    fun `parent and child complete the whole lifecycle`() {
        // 1. Parent registration, 2. Child registration
        val parent = register("PARENT", "Morgan")
        val child = register("CHILD", "Riley")
        val parentWs = WsClient(parent.accessToken)
        try {
            // 3. Device pairing
            val code = post("/api/v1/pairing/create", mapOf("childLabel" to "Riley"), parent.accessToken).text("/code")
            assertThat(post("/api/v1/pairing/accept", mapOf("code" to code), child.accessToken).status).isEqualTo(200)
            assertThat(parentWs.await("pairing.completed").at("/payload/childDisplayName").asText()).isEqualTo("Riley")
            val childWs = WsClient(child.accessToken)

            // 4. Usage-data synchronisation
            val today = LocalDate.now(ZoneOffset.UTC)
            val sync = post("/api/v1/usage/sync", mapOf("timezone" to "UTC", "days" to listOf(mapOf("date" to today.toString(),
                "apps" to listOf(mapOf("packageName" to "org.example.reader", "appLabel" to "Reader", "foregroundMs" to 900_000, "launchCount" to 3)))))
                , child.accessToken)
            assertThat(sync.status).isEqualTo(200)

            // 5. Parent dashboard updates (realtime notice + REST data)
            assertThat(parentWs.await("usage.synced").at("/payload/deviceId").asText()).isEqualTo(child.deviceId.toString())
            val devices = get("/api/v1/devices", parent.accessToken)
            assertThat(devices.body!![0].at("/latestUsageForegroundMs").asLong()).isEqualTo(900_000)
            assertThat(get("/api/v1/usage/daily?deviceId=${child.deviceId}&date=$today", parent.accessToken).body!!.at("/apps/0/appLabel").asText())
                .isEqualTo("Reader")

            // 6. Screen-sharing request
            val sessionId = post("/api/v1/screen-sharing/request", mapOf("deviceId" to child.deviceId), parent.accessToken).uuid("/id")
            assertThat(childWs.await("screen_share.requested").at("/payload/id").asText()).isEqualTo(sessionId.toString())

            // 7. Child approval
            assertThat(post("/api/v1/screen-sharing/approve", mapOf("sessionId" to sessionId), child.accessToken).text("/status")).isEqualTo("APPROVED")

            // 8. Live transmission negotiation (signaling relay)
            childWs.send(mapOf("type" to "signal", "sessionId" to sessionId, "payload" to mapOf("kind" to "offer", "sdp" to "OFFER")))
            assertThat(parentWs.await("signal").at("/payload/payload/sdp").asText()).isEqualTo("OFFER")
            parentWs.send(mapOf("type" to "signal", "sessionId" to sessionId, "payload" to mapOf("kind" to "answer", "sdp" to "ANSWER")))
            assertThat(childWs.await("signal").at("/payload/payload/sdp").asText()).isEqualTo("ANSWER")
            assertThat(get("/api/v1/screen-sharing/status?sessionId=$sessionId", parent.accessToken).text("/active/status")).isEqualTo("ACTIVE")

            // 9. Session termination (by parent)
            assertThat(post("/api/v1/screen-sharing/stop", mapOf("sessionId" to sessionId), parent.accessToken).text("/endReason"))
                .isEqualTo("STOPPED_BY_PARENT")
            assertThat(childWs.await("screen_share.updated").at("/payload/status").asText()).isEqualTo("STOPPED")

            // Safety: child check-in reaches the parent in real time
            assertThat(post("/api/v1/safety/alerts", mapOf("type" to "CHECK_IN", "message" to "At school"), child.accessToken).status).isEqualTo(201)
            assertThat(parentWs.await("safety.alert").at("/payload/type").asText()).isEqualTo("CHECK_IN")
            // ...and a parent's check-in request reaches the child
            assertThat(post("/api/v1/safety/alerts", mapOf("type" to "CHECK_IN_REQUEST", "deviceId" to child.deviceId), parent.accessToken).status).isEqualTo(201)
            assertThat(childWs.await("safety.alert").at("/payload/type").asText()).isEqualTo("CHECK_IN_REQUEST")

            // 10. Device revocation
            assertThat(call("DELETE", "/api/v1/devices/${child.deviceId}", null, parent.accessToken).status).isEqualTo(204)
            assertThat(childWs.await("device.revoked").path("type").asText()).isEqualTo("device.revoked")
            assertThat(childWs.closed.poll(5, TimeUnit.SECONDS)).isNotNull()
            assertThat(get("/api/v1/auth/me", child.accessToken).status).isEqualTo(401)
            assertThat(post("/api/v1/usage/sync", mapOf("days" to listOf(mapOf("date" to today.toString()))), child.accessToken).status).isEqualTo(401)
        } finally {
            parentWs.close()
        }
    }
}
