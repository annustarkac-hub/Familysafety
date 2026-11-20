package com.familysafety.backend

import com.familysafety.backend.model.ScreenShareStatus
import com.familysafety.backend.repository.ScreenSharingSessionRepository
import com.familysafety.backend.service.ScreenSharingService
import com.familysafety.backend.support.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Instant
import java.util.concurrent.TimeUnit

class ScreenSharingWebSocketTest : IntegrationTestBase() {
    @Autowired lateinit var sessions: ScreenSharingSessionRepository
    @Autowired lateinit var service: ScreenSharingService

    @Test
    fun `websocket handshake requires a valid bearer token`() {
        assertThatThrownBy { WsClient(null) }.isNotNull()
        assertThatThrownBy { WsClient("garbage") }.isNotNull()
        val acc = register("PARENT")
        val ws = WsClient(acc.accessToken)
        ws.send(mapOf("type" to "ping"))
        assertThat(ws.await("pong").path("type").asText()).isEqualTo("pong")
        ws.close()
    }

    @Test
    fun `full consent flow with signaling relay and stop`() {
        val (parent, child) = pairedFamily()
        val parentWs = WsClient(parent.accessToken)
        val childWs = WsClient(child.accessToken)
        try {
            val req = post("/api/v1/screen-sharing/request", mapOf("deviceId" to child.deviceId), parent.accessToken)
            assertThat(req.status).isEqualTo(201)
            assertThat(req.text("/status")).isEqualTo("REQUESTED")
            val sessionId = req.uuid("/id")

            val requested = childWs.await("screen_share.requested")
            assertThat(requested.at("/payload/id").asText()).isEqualTo(sessionId.toString())
            assertThat(requested.at("/payload/requestedByDisplayName").asText()).isEqualTo("Pat Parent")

            // Signaling is refused before the child approves.
            childWs.send(mapOf("type" to "signal", "sessionId" to sessionId, "payload" to mapOf("kind" to "offer", "sdp" to "v=0")))
            assertThat(childWs.await("error").at("/payload/code").asText()).isEqualTo("INVALID_SESSION_STATE")

            val approve = post("/api/v1/screen-sharing/approve", mapOf("sessionId" to sessionId), child.accessToken)
            assertThat(approve.status).isEqualTo(200)
            assertThat(approve.text("/status")).isEqualTo("APPROVED")
            assertThat(approve.body!!.at("/iceServers/0/urls/0").asText()).startsWith("stun:")
            assertThat(parentWs.await("screen_share.updated").at("/payload/status").asText()).isEqualTo("APPROVED")

            // Parent may not send an offer (only the capturing device can).
            parentWs.send(mapOf("type" to "signal", "sessionId" to sessionId, "payload" to mapOf("kind" to "offer", "sdp" to "x")))
            assertThat(parentWs.await("error").at("/payload/code").asText()).isEqualTo("INVALID_SIGNAL_DIRECTION")

            childWs.send(mapOf("type" to "signal", "sessionId" to sessionId, "payload" to mapOf("kind" to "offer", "sdp" to "v=0 offer")))
            // The ACTIVE status update is published before the relayed offer.
            assertThat(parentWs.await("screen_share.updated").at("/payload/status").asText()).isEqualTo("ACTIVE")
            val offer = parentWs.await("signal")
            assertThat(offer.at("/payload/payload/sdp").asText()).isEqualTo("v=0 offer")

            parentWs.send(mapOf("type" to "signal", "sessionId" to sessionId, "payload" to mapOf("kind" to "answer", "sdp" to "v=0 answer")))
            assertThat(childWs.await("signal").at("/payload/payload/sdp").asText()).isEqualTo("v=0 answer")
            parentWs.send(mapOf("type" to "signal", "sessionId" to sessionId, "payload" to mapOf("kind" to "ice", "candidate" to "c1", "sdpMid" to "0", "sdpMLineIndex" to 0)))
            assertThat(childWs.await("signal").at("/payload/payload/candidate").asText()).isEqualTo("c1")

            val status = get("/api/v1/screen-sharing/status?deviceId=${child.deviceId}", parent.accessToken)
            assertThat(status.text("/active/status")).isEqualTo("ACTIVE")

            val stop = post("/api/v1/screen-sharing/stop", mapOf("sessionId" to sessionId), child.accessToken)
            assertThat(stop.text("/status")).isEqualTo("STOPPED")
            assertThat(stop.text("/endReason")).isEqualTo("STOPPED_BY_CHILD")
            assertThat(parentWs.await("screen_share.updated").at("/payload/status").asText()).isEqualTo("STOPPED")

            // No relay after stop.
            childWs.send(mapOf("type" to "signal", "sessionId" to sessionId, "payload" to mapOf("kind" to "ice", "candidate" to "late")))
            assertThat(childWs.await("error").at("/payload/code").asText()).isEqualTo("INVALID_SESSION_STATE")
        } finally {
            parentWs.close(); childWs.close()
        }
    }

    @Test
    fun `child can reject and parent is told`() {
        val (parent, child) = pairedFamily()
        val sessionId = post("/api/v1/screen-sharing/request", mapOf("deviceId" to child.deviceId), parent.accessToken).uuid("/id")
        val rej = post("/api/v1/screen-sharing/reject", mapOf("sessionId" to sessionId), child.accessToken)
        assertThat(rej.text("/status")).isEqualTo("REJECTED")
        assertThat(post("/api/v1/screen-sharing/approve", mapOf("sessionId" to sessionId), child.accessToken).status).isEqualTo(409)
        val alerts = get("/api/v1/safety/alerts", parent.accessToken).body!!.at("/alerts").map { it.at("/type").asText() }
        assertThat(alerts).contains("SCREEN_SHARE_REJECTED")
    }

    @Test
    fun `only one open session per device and disabled setting blocks requests`() {
        val (parent, child) = pairedFamily()
        assertThat(post("/api/v1/screen-sharing/request", mapOf("deviceId" to child.deviceId), parent.accessToken).status).isEqualTo(201)
        val dup = post("/api/v1/screen-sharing/request", mapOf("deviceId" to child.deviceId), parent.accessToken)
        assertThat(dup.status).isEqualTo(409)
        assertThat(dup.text("/code")).isEqualTo("SESSION_ALREADY_OPEN")

        val (parent2, child2) = pairedFamily()
        call("PUT", "/api/v1/safety/settings", mapOf("deviceId" to child2.deviceId, "screenSharingEnabled" to false), parent2.accessToken)
        val blocked = post("/api/v1/screen-sharing/request", mapOf("deviceId" to child2.deviceId), parent2.accessToken)
        assertThat(blocked.status).isEqualTo(403)
        assertThat(blocked.text("/code")).isEqualTo("SCREEN_SHARING_DISABLED")
    }

    @Test
    fun `expired requests cannot be approved and sweeper expires stale sessions`() {
        val (parent, child) = pairedFamily()
        val sessionId = post("/api/v1/screen-sharing/request", mapOf("deviceId" to child.deviceId), parent.accessToken).uuid("/id")
        sessions.findById(sessionId).get().also { it.requestExpiresAt = Instant.now().minusSeconds(1); sessions.save(it) }
        val r = post("/api/v1/screen-sharing/approve", mapOf("sessionId" to sessionId), child.accessToken)
        assertThat(r.status).isEqualTo(410)
        assertThat(sessions.findById(sessionId).get().status).isEqualTo(ScreenShareStatus.EXPIRED)

        val s2 = post("/api/v1/screen-sharing/request", mapOf("deviceId" to child.deviceId), parent.accessToken).uuid("/id")
        post("/api/v1/screen-sharing/approve", mapOf("sessionId" to s2), child.accessToken)
        sessions.findById(s2).get().also { it.maxEndAt = Instant.now().minusSeconds(1); sessions.save(it) }
        service.expireOverdueSessions()
        val ended = sessions.findById(s2).get()
        assertThat(ended.status).isEqualTo(ScreenShareStatus.EXPIRED)
        assertThat(ended.endReason).isEqualTo("START_TIMEOUT")
    }

    @Test
    fun `device revocation terminates active session and disconnects the device socket`() {
        val (parent, child) = pairedFamily()
        val childWs = WsClient(child.accessToken)
        val parentWs = WsClient(parent.accessToken)
        try {
            val sessionId = post("/api/v1/screen-sharing/request", mapOf("deviceId" to child.deviceId), parent.accessToken).uuid("/id")
            post("/api/v1/screen-sharing/approve", mapOf("sessionId" to sessionId), child.accessToken)
            childWs.send(mapOf("type" to "signal", "sessionId" to sessionId, "payload" to mapOf("kind" to "offer", "sdp" to "x")))
            parentWs.await("signal")

            assertThat(call("DELETE", "/api/v1/devices/${child.deviceId}", null, parent.accessToken).status).isEqualTo(204)
            assertThat(childWs.await("device.revoked").at("/payload/deviceId").asText()).isEqualTo(child.deviceId.toString())
            assertThat(childWs.closed.poll(5, TimeUnit.SECONDS)).isNotNull()
            val s = sessions.findById(sessionId).get()
            assertThat(s.status).isEqualTo(ScreenShareStatus.STOPPED)
            assertThat(s.endReason).isEqualTo("DEVICE_REVOKED")
        } finally {
            childWs.close(); parentWs.close()
        }
    }

    @Test
    fun `child disconnecting websocket ends approved session`() {
        val (parent, child) = pairedFamily()
        val childWs = WsClient(child.accessToken)
        val sessionId = post("/api/v1/screen-sharing/request", mapOf("deviceId" to child.deviceId), parent.accessToken).uuid("/id")
        post("/api/v1/screen-sharing/approve", mapOf("sessionId" to sessionId), child.accessToken)
        childWs.close()
        val deadline = System.currentTimeMillis() + 5_000
        while (sessions.findById(sessionId).get().status.isOpen && System.currentTimeMillis() < deadline) Thread.sleep(100)
        assertThat(sessions.findById(sessionId).get().endReason).isEqualTo("PARTICIPANT_DISCONNECTED")
    }
}
