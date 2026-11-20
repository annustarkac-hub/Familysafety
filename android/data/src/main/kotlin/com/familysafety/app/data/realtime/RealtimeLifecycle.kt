package com.familysafety.app.data.realtime

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.familysafety.app.data.repository.SessionLifecycleListener
import com.familysafety.app.data.session.SessionStore
import com.familysafety.app.domain.model.Role
import com.familysafety.app.domain.repository.RealtimeRepository
import java.util.concurrent.atomic.AtomicInteger

/**
 * Keeps the realtime connection open while the app is visible, or while a component (e.g. the screen-sharing
 * foreground service) holds a keep-alive. This respects Android background limits: no hidden persistent socket.
 */
class RealtimeLifecycle(
    private val realtime: RealtimeRepository,
    private val store: SessionStore,
) : DefaultLifecycleObserver, SessionLifecycleListener {
    private val holds = AtomicInteger(0)
    @Volatile private var foreground = false

    fun start() = ProcessLifecycleOwner.get().lifecycle.addObserver(this)

    override fun onStart(owner: LifecycleOwner) { foreground = true; evaluate() }
    override fun onStop(owner: LifecycleOwner) { foreground = false; evaluate() }

    fun acquire() { holds.incrementAndGet(); evaluate() }
    fun release() { holds.updateAndGet { (it - 1).coerceAtLeast(0) }; evaluate() }

    override fun onSignedIn(role: Role) = evaluate()
    override fun onSignedOut() = realtime.disconnect()

    private fun evaluate() {
        if (store.accessToken != null && (foreground || holds.get() > 0)) realtime.connect() else realtime.disconnect()
    }
}
