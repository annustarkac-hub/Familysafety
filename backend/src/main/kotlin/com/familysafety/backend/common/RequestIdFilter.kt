package com.familysafety.backend.common

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

/** Assigns a request id (or accepts a well-formed client one) for log correlation. Never logs bodies or tokens. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class RequestIdFilter : OncePerRequestFilter() {
    private val allowed = Regex("^[A-Za-z0-9-]{8,64}$")

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val incoming = request.getHeader("X-Request-Id")
        val id = if (incoming != null && allowed.matches(incoming)) incoming else UUID.randomUUID().toString()
        MDC.put("requestId", id)
        response.setHeader("X-Request-Id", id)
        try {
            chain.doFilter(request, response)
        } finally {
            MDC.remove("requestId")
        }
    }
}

object ClientIp {
    /** Uses the servlet remote address; X-Forwarded-For is honoured only via server.forward-headers-strategy. */
    fun of(request: HttpServletRequest?): String? = request?.remoteAddr?.take(64)
}
