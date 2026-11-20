package com.familysafety.backend.security

import com.familysafety.backend.common.ClientIp
import com.familysafety.backend.common.RateLimitedException
import com.familysafety.backend.config.AppProperties
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.web.filter.OncePerRequestFilter

/** Per-IP limits for unauthenticated auth endpoints and a general per-IP ceiling for the whole API. */
class RateLimitFilter(private val limiter: RateLimiter, private val props: AppProperties) : OncePerRequestFilter() {

    override fun shouldNotFilter(request: HttpServletRequest) =
        !props.rateLimit.enabled || !request.requestURI.startsWith("/api/")

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val ip = ClientIp.of(request) ?: "unknown"
        try {
            if (request.requestURI.startsWith("/api/v1/auth/") && request.method == "POST") {
                limiter.check("auth:$ip", props.rateLimit.authPerMinute)
            }
            limiter.check("general:$ip", props.rateLimit.generalPerMinute)
        } catch (e: RateLimitedException) {
            response.status = 429
            response.setHeader("Retry-After", e.retryAfterSeconds.toString())
            response.contentType = "application/json"
            response.writer.write("""{"code":"RATE_LIMITED","message":"Too many requests","status":429}""")
            return
        }
        chain.doFilter(request, response)
    }
}

/** Rejects plain-HTTP API traffic when TLS enforcement is enabled (TLS terminated here or at a trusted proxy). */
class HttpsEnforcementFilter : OncePerRequestFilter() {
    override fun shouldNotFilter(request: HttpServletRequest) = request.requestURI.startsWith("/actuator/health")

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        if (!request.isSecure) {
            response.status = 403
            response.contentType = "application/json"
            response.writer.write("""{"code":"HTTPS_REQUIRED","message":"TLS is required","status":403}""")
            return
        }
        response.setHeader("Strict-Transport-Security", "max-age=31536000; includeSubDomains")
        chain.doFilter(request, response)
    }
}
