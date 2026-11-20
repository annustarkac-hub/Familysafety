package com.familysafety.backend.realtime

import com.familysafety.backend.model.Role
import com.familysafety.backend.repository.FamilyMemberRepository
import com.familysafety.backend.security.AuthPrincipal
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Server-to-client realtime message envelope. */
data class RealtimeEvent(val type: String, val payload: Any?)

object EventTypes {
    const val SCREEN_SHARE_REQUESTED = "screen_share.requested"
    const val SCREEN_SHARE_UPDATED = "screen_share.updated"
    const val SIGNAL = "signal"
    const val PAIRING_COMPLETED = "pairing.completed"
    const val DEVICE_REVOKED = "device.revoked"
    const val SAFETY_ALERT = "safety.alert"
    const val USAGE_SYNCED = "usage.synced"
    const val SETTINGS_UPDATED = "safety.settings_updated"
    const val PONG = "pong"
    const val ERROR = "error"
}

class Connection(val principal: AuthPrincipal, val session: WebSocketSession)

/**
 * Tracks live WebSocket connections per user, device and auth session. Delivery is best-effort: clients
 * must also poll the REST status endpoints (e.g. on resume) because mobile connections drop frequently.
 */
@Component
class ConnectionRegistry(
    private val mapper: ObjectMapper,
    private val members: FamilyMemberRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val connections = ConcurrentHashMap<String, Connection>()

    fun register(principal: AuthPrincipal, raw: WebSocketSession): Connection {
        val conn = Connection(principal, ConcurrentWebSocketSessionDecorator(raw, 5_000, 256 * 1024))
        connections[raw.id] = conn
        return conn
    }

    fun unregister(sessionId: String): Connection? = connections.remove(sessionId)

    fun get(sessionId: String): Connection? = connections[sessionId]

    fun isDeviceOnline(deviceId: UUID) = connections.values.any { it.principal.deviceId == deviceId }
    fun isUserOnline(userId: UUID) = connections.values.any { it.principal.userId == userId }

    fun sendToUser(userId: UUID, event: RealtimeEvent) = send(event) { it.principal.userId == userId }
    fun sendToDevice(deviceId: UUID, event: RealtimeEvent) = send(event) { it.principal.deviceId == deviceId }

    fun sendToFamilyParents(familyId: UUID, event: RealtimeEvent) {
        val parentIds = members.findByFamilyIdAndMemberRole(familyId, Role.PARENT).map { it.userId }.toSet()
        send(event) { it.principal.userId in parentIds && it.principal.role == Role.PARENT }
    }

    fun sendTo(conn: Connection, event: RealtimeEvent) = deliver(conn, mapper.writeValueAsString(event))

    fun closeDevice(deviceId: UUID, reason: String) = close(reason) { it.principal.deviceId == deviceId }
    fun closeAuthSession(authSessionId: UUID, reason: String) = close(reason) { it.principal.sessionId == authSessionId }

    private fun send(event: RealtimeEvent, filter: (Connection) -> Boolean): Int {
        val json = mapper.writeValueAsString(event)
        return connections.values.filter(filter).count { deliver(it, json) }
    }

    private fun deliver(conn: Connection, json: String): Boolean = try {
        if (conn.session.isOpen) {
            conn.session.sendMessage(TextMessage(json)); true
        } else false
    } catch (e: Exception) {
        log.debug("Failed to deliver realtime message to {}: {}", conn.session.id, e.message)
        false
    }

    private fun close(reason: String, filter: (Connection) -> Boolean) {
        connections.values.filter(filter).forEach {
            runCatching { it.session.close(CloseStatus.POLICY_VIOLATION.withReason(reason)) }
        }
    }
}

/** Defers realtime delivery until the surrounding transaction commits, so clients never see rolled-back state. */
fun afterCommit(block: () -> Unit) {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
        TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
            override fun afterCommit() = block()
        })
    } else block()
}
