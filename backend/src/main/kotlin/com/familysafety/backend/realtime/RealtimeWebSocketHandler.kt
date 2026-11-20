package com.familysafety.backend.realtime

import com.familysafety.backend.common.ApiException
import com.familysafety.backend.config.AppProperties
import com.familysafety.backend.security.AuthPrincipal
import com.familysafety.backend.service.DeviceService
import com.familysafety.backend.service.ScreenSharingService
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Component
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.handler.TextWebSocketHandler
import java.util.UUID

/**
 * Client → server messages:
 *  {"type":"ping"}
 *  {"type":"signal","sessionId":"<uuid>","payload":{"kind":"offer|answer|ice|bye", ...}}
 */
@Component
class RealtimeWebSocketHandler(
    private val registry: ConnectionRegistry,
    private val mapper: ObjectMapper,
    @Lazy private val screenSharing: ScreenSharingService,
    @Lazy private val devices: DeviceService,
    private val props: AppProperties,
) : TextWebSocketHandler() {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun afterConnectionEstablished(session: WebSocketSession) {
        val principal = session.attributes[PRINCIPAL_ATTR] as? AuthPrincipal ?: run {
            session.close(CloseStatus.POLICY_VIOLATION); return
        }
        registry.register(principal, session)
        principal.deviceId?.let { runCatching { devices.touch(it) } }
    }

    override fun handleTextMessage(session: WebSocketSession, message: TextMessage) {
        val conn = registry.get(session.id) ?: return
        if (message.payloadLength > props.screenSharing.maxSignalBytes) {
            registry.sendTo(conn, RealtimeEvent(EventTypes.ERROR, mapOf("code" to "MESSAGE_TOO_LARGE")))
            return
        }
        val node: JsonNode = runCatching { mapper.readTree(message.payload) }.getOrNull() ?: run {
            registry.sendTo(conn, RealtimeEvent(EventTypes.ERROR, mapOf("code" to "MALFORMED_MESSAGE"))); return
        }
        when (node.path("type").asText()) {
            "ping" -> registry.sendTo(conn, RealtimeEvent(EventTypes.PONG, null))
            "signal" -> handleSignal(conn, node)
            else -> registry.sendTo(conn, RealtimeEvent(EventTypes.ERROR, mapOf("code" to "UNKNOWN_TYPE")))
        }
    }

    private fun handleSignal(conn: Connection, node: JsonNode) {
        val sessionId = runCatching { UUID.fromString(node.path("sessionId").asText()) }.getOrNull()
        val payload = node.path("payload")
        val kind = payload.path("kind").asText()
        if (sessionId == null || payload.isMissingNode || kind !in setOf("offer", "answer", "ice", "bye")) {
            registry.sendTo(conn, RealtimeEvent(EventTypes.ERROR, mapOf("code" to "INVALID_SIGNAL"))); return
        }
        try {
            screenSharing.relaySignal(conn.principal, sessionId, kind, payload)
        } catch (e: ApiException) {
            registry.sendTo(conn, RealtimeEvent(EventTypes.ERROR, mapOf("code" to e.code, "sessionId" to sessionId.toString())))
        }
    }

    override fun afterConnectionClosed(session: WebSocketSession, status: CloseStatus) {
        val conn = registry.unregister(session.id) ?: return
        runCatching { screenSharing.onConnectionLost(conn.principal) }
            .onFailure { log.warn("Error cleaning up screen sessions after disconnect: {}", it.message) }
    }

    override fun handleTransportError(session: WebSocketSession, exception: Throwable) {
        log.debug("WebSocket transport error {}: {}", session.id, exception.message)
    }
}
