package com.familysafety.backend.support

import com.familysafety.backend.security.RateLimiter
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketHttpHeaders
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.client.standard.StandardWebSocketClient
import org.springframework.web.socket.handler.TextWebSocketHandler
import org.testcontainers.containers.PostgreSQLContainer
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "familysafety.security.jwt-secret=dGVzdC1qd3Qtc2VjcmV0LXRoYXQtaXMtYXQtbGVhc3QtMzItYnl0ZXMtbG9uZw==",
        "familysafety.security.pairing-pepper=dGVzdC1wYWlyaW5nLXBlcHBlci10aGF0LWlzLWF0LWxlYXN0LTMyLWJ5dGVz",
        "familysafety.security.require-https=false",
        "familysafety.rate-limit.enabled=false",
        "familysafety.screen-sharing.expiry-sweep-ms=600000",
    ],
)
abstract class IntegrationTestBase {
    companion object {
        @JvmStatic
        @ServiceConnection
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine").apply { start() }
    }

    @LocalServerPort
    var port: Int = 0

    @Autowired
    lateinit var mapper: ObjectMapper

    @Autowired
    lateinit var rateLimiter: RateLimiter

    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

    @BeforeEach
    fun resetLimiter() = rateLimiter.reset()

    data class Resp(val status: Int, val body: JsonNode?, val headers: Map<String, List<String>>) {
        fun text(path: String): String = body!!.at(path).asText()
        fun uuid(path: String): UUID = UUID.fromString(text(path))
    }

    fun call(method: String, path: String, body: Any? = null, token: String? = null): Resp {
        val builder = HttpRequest.newBuilder(URI("http://localhost:$port$path")).timeout(Duration.ofSeconds(20))
            .header("Content-Type", "application/json")
        token?.let { builder.header("Authorization", "Bearer $it") }
        val publisher = if (body == null) HttpRequest.BodyPublishers.noBody()
            else HttpRequest.BodyPublishers.ofString(if (body is String) body else mapper.writeValueAsString(body))
        builder.method(method, publisher)
        val response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        val json = response.body().takeIf { it.isNotBlank() }?.let { runCatching { mapper.readTree(it) }.getOrNull() }
        return Resp(response.statusCode(), json, response.headers().map())
    }

    fun get(path: String, token: String? = null) = call("GET", path, null, token)
    fun post(path: String, body: Any? = null, token: String? = null) = call("POST", path, body ?: emptyMap<String, Any>(), token)

    data class Account(val userId: UUID, val deviceId: UUID, val accessToken: String, val refreshToken: String, val email: String, val password: String)

    fun device(installationId: String = UUID.randomUUID().toString()) = mapOf(
        "installationId" to installationId, "deviceName" to "Test Pixel", "manufacturer" to "Google", "model" to "Pixel 8",
        "osVersion" to "14", "appVersion" to "1.0.0",
    )

    fun register(role: String, name: String = "$role user", installationId: String = UUID.randomUUID().toString()): Account {
        val email = "${role.lowercase()}-${UUID.randomUUID()}@example.test"
        val password = "correct horse battery staple"
        val r = post("/api/v1/auth/register", mapOf("email" to email, "password" to password, "displayName" to name,
            "role" to role, "device" to device(installationId)))
        check(r.status == 201) { "register failed: ${r.status} ${r.body}" }
        return Account(r.uuid("/user/id"), r.uuid("/deviceId"), r.text("/accessToken"), r.text("/refreshToken"), email, password)
    }

    /** Registers a parent and a child and pairs them. */
    fun pairedFamily(): Pair<Account, Account> {
        val parent = register("PARENT", "Pat Parent")
        val child = register("CHILD", "Casey Child")
        val code = post("/api/v1/pairing/create", mapOf("childLabel" to "Casey"), parent.accessToken).also { check(it.status == 201) }.text("/code")
        val accept = post("/api/v1/pairing/accept", mapOf("code" to code), child.accessToken)
        check(accept.status == 200) { "accept failed: ${accept.status} ${accept.body}" }
        return parent to child
    }

    // ---- WebSocket helper ----

    inner class WsClient(token: String?) {
        val messages = LinkedBlockingQueue<JsonNode>()
        val closed = LinkedBlockingQueue<CloseStatus>()
        val session: WebSocketSession

        init {
            val headers = WebSocketHttpHeaders()
            token?.let { headers.add("Authorization", "Bearer $it") }
            session = StandardWebSocketClient().execute(object : TextWebSocketHandler() {
                override fun handleTextMessage(session: WebSocketSession, message: TextMessage) {
                    messages.add(mapper.readTree(message.payload))
                }
                override fun afterConnectionClosed(session: WebSocketSession, status: CloseStatus) { closed.add(status) }
            }, headers, URI("ws://localhost:$port/ws")).get(10, TimeUnit.SECONDS)
        }

        fun send(obj: Any) = session.sendMessage(TextMessage(mapper.writeValueAsString(obj)))

        /** Waits for the next message of [type], skipping others. */
        fun await(type: String, timeoutMs: Long = 5_000): JsonNode {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (true) {
                val remaining = deadline - System.currentTimeMillis()
                check(remaining > 0) { "Timed out waiting for '$type'" }
                val m = messages.poll(remaining, TimeUnit.MILLISECONDS) ?: error("Timed out waiting for '$type'")
                if (m.path("type").asText() == type) return m
            }
        }

        fun close() = runCatching { session.close() }
    }
}
