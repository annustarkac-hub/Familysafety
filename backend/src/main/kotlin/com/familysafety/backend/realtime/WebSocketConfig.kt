package com.familysafety.backend.realtime

import com.familysafety.backend.security.SessionAwareJwtConverter
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.http.server.ServerHttpRequest
import org.springframework.http.server.ServerHttpResponse
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.web.socket.WebSocketHandler
import org.springframework.web.socket.config.annotation.EnableWebSocket
import org.springframework.web.socket.config.annotation.WebSocketConfigurer
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry
import org.springframework.web.socket.server.HandshakeInterceptor
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean

const val PRINCIPAL_ATTR = "fs.principal"

/**
 * Authenticates the WebSocket upgrade with the same bearer access token (and the same active-session /
 * device-revocation checks) as the REST API. Tokens are accepted only in the Authorization header,
 * never in the query string, so they do not leak into access logs.
 */
class BearerHandshakeInterceptor(
    private val decoder: JwtDecoder,
    private val converter: SessionAwareJwtConverter,
) : HandshakeInterceptor {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun beforeHandshake(
        request: ServerHttpRequest, response: ServerHttpResponse, wsHandler: WebSocketHandler, attributes: MutableMap<String, Any>,
    ): Boolean {
        val header = request.headers.getFirst("Authorization")
        if (header == null || !header.startsWith("Bearer ")) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED); return false
        }
        return try {
            val principal = converter.resolve(decoder.decode(header.removePrefix("Bearer ").trim()))
            attributes[PRINCIPAL_ATTR] = principal
            true
        } catch (e: Exception) {
            log.debug("WebSocket handshake rejected: {}", e.message)
            response.setStatusCode(HttpStatus.UNAUTHORIZED); false
        }
    }

    override fun afterHandshake(request: ServerHttpRequest, response: ServerHttpResponse, wsHandler: WebSocketHandler, exception: Exception?) {}
}

@Configuration
@EnableWebSocket
class WebSocketConfig(
    private val handler: RealtimeWebSocketHandler,
    private val decoder: JwtDecoder,
    private val converter: SessionAwareJwtConverter,
) : WebSocketConfigurer {
    override fun registerWebSocketHandlers(registry: WebSocketHandlerRegistry) {
        // Native mobile clients do not send an Origin header; browsers are not a supported client.
        registry.addHandler(handler, "/ws")
            .addInterceptors(BearerHandshakeInterceptor(decoder, converter))
            .setAllowedOriginPatterns("*")
    }

    @Bean
    fun webSocketContainer() = ServletServerContainerFactoryBean().apply {
        setMaxTextMessageBufferSize(128 * 1024)
        setMaxSessionIdleTimeout(120_000L)
    }
}
