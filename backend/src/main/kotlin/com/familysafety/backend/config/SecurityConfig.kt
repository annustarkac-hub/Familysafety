package com.familysafety.backend.config

import com.familysafety.backend.security.HttpsEnforcementFilter
import com.familysafety.backend.security.RateLimitFilter
import com.familysafety.backend.security.RateLimiter
import com.familysafety.backend.security.SessionAwareJwtConverter
import com.familysafety.backend.security.TokenService
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.HttpStatusEntryPoint
import org.springframework.security.web.context.SecurityContextHolderFilter
import java.time.Clock

@Configuration
@EnableMethodSecurity
class SecurityConfig {

    @Bean
    fun clock(): Clock = Clock.systemUTC()

    /**
     * Argon2id with OWASP-recommended parameters (19 MiB memory, 2 iterations, 1 lane, 16-byte salt, 32-byte hash).
     */
    @Bean
    fun passwordEncoder(): PasswordEncoder = Argon2PasswordEncoder(16, 32, 1, 19 * 1024, 2)

    @Bean
    fun jwtDecoder(tokens: TokenService): JwtDecoder = tokens.decoder

    @Bean
    fun securityFilterChain(
        http: HttpSecurity,
        converter: SessionAwareJwtConverter,
        limiter: RateLimiter,
        props: AppProperties,
    ): SecurityFilterChain {
        http
            .csrf { it.disable() } // stateless bearer-token API; no cookies are used
            .cors { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .headers { h ->
                h.contentTypeOptions { }
                h.frameOptions { it.deny() }
                h.cacheControl { }
            }
            .authorizeHttpRequests {
                it.requestMatchers(HttpMethod.POST, "/api/v1/auth/register", "/api/v1/auth/login", "/api/v1/auth/refresh").permitAll()
                it.requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                // WebSocket handshake authenticates itself via the Authorization header in a HandshakeInterceptor.
                it.requestMatchers("/ws").permitAll()
                it.requestMatchers("/api/**").authenticated()
                it.anyRequest().denyAll()
            }
            .oauth2ResourceServer { rs ->
                rs.jwt { it.jwtAuthenticationConverter(converter) }
                rs.authenticationEntryPoint(HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED))
            }
            .exceptionHandling { it.authenticationEntryPoint(HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)) }
            .addFilterBefore(RateLimitFilter(limiter, props), BearerTokenAuthenticationFilter::class.java)
        if (props.security.requireHttps) {
            http.addFilterAfter(HttpsEnforcementFilter(), SecurityContextHolderFilter::class.java)
        }
        return http.build()
    }
}
