package com.familysafety.backend.security

import com.familysafety.backend.common.RateLimitedException
import org.springframework.stereotype.Component
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory fixed-window rate limiter. Suitable for a single backend instance; a horizontally scaled
 * deployment must replace this with a shared store (e.g. Redis) — see SECURITY.md.
 */
@Component
class RateLimiter(private val clock: Clock) {
    private data class Window(val startMs: Long, var count: Int)

    private val windows = ConcurrentHashMap<String, Window>()

    /** @throws RateLimitedException when [key] exceeded [limit] within [windowMs]. */
    fun check(key: String, limit: Int, windowMs: Long = 60_000) {
        val now = clock.millis()
        val w = windows.compute(key) { _, existing ->
            if (existing == null || now - existing.startMs >= windowMs) Window(now, 1)
            else existing.also { it.count++ }
        }!!
        if (w.count > limit) {
            val retry = ((w.startMs + windowMs - now) / 1000).coerceAtLeast(1)
            throw RateLimitedException(retry)
        }
        if (windows.size > 100_000) evict(now, windowMs)
    }

    private fun evict(now: Long, windowMs: Long) {
        windows.entries.removeIf { now - it.value.startMs >= windowMs }
    }

    fun reset() = windows.clear()
}
