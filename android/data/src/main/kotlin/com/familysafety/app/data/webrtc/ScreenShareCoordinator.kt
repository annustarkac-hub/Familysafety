package com.familysafety.app.data.webrtc

import com.familysafety.app.data.notify.Notifier
import com.familysafety.app.domain.logic.ScreenShareLifecycle
import com.familysafety.app.domain.logic.ShareEvent
import com.familysafety.app.domain.logic.ShareState
import com.familysafety.app.domain.model.AppResult
import com.familysafety.app.domain.model.RealtimeEvent
import com.familysafety.app.domain.model.ScreenShareSession
import com.familysafety.app.domain.model.ScreenShareStatus
import com.familysafety.app.domain.model.StopReason
import com.familysafety.app.domain.repository.RealtimeRepository
import com.familysafety.app.domain.repository.ScreenShareRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Single source of truth for the child device's screen-sharing state. Combines realtime pushes and
 * REST polling (for when the socket was down) and enforces the consent sequence through [ScreenShareLifecycle].
 */
class ScreenShareCoordinator(
    private val repo: ScreenShareRepository,
    private val realtime: RealtimeRepository,
    private val notifier: Notifier,
    scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<ShareState>(ShareState.Idle)
    val state: StateFlow<ShareState> = _state.asStateFlow()

    /** Session approved by the child and awaiting/holding capture (includes ICE servers from approve). */
    var approvedSession: ScreenShareSession? = null
        private set

    init {
        scope.launch {
            realtime.events.collect { e ->
                when (e) {
                    is RealtimeEvent.ScreenShareRequested -> onRemote(e.session)
                    is RealtimeEvent.ScreenShareUpdated -> onRemote(e.session)
                    is RealtimeEvent.DeviceRevoked -> dispatch(ShareEvent.Failure("DEVICE_REVOKED"))
                    else -> Unit
                }
            }
        }
    }

    private fun onRemote(session: ScreenShareSession) {
        val before = _state.value
        dispatch(if (session.status == ScreenShareStatus.REQUESTED) ShareEvent.RequestReceived(session) else ShareEvent.ServerUpdate(session))
        val after = _state.value
        if (after is ShareState.Incoming && before !is ShareState.Incoming) notifier.showShareRequest(session)
        if (!session.status.isOpen) notifier.cancelShareRequest()
    }

    fun dispatch(event: ShareEvent) = _state.update { ScreenShareLifecycle.reduce(it, event) }

    /** Picks up a pending request after the app was in the background (no push provider). */
    suspend fun refreshFromServer() {
        val r = repo.statusForDevice(null)
        if (r is AppResult.Success) r.value?.let { onRemote(it) }
    }

    suspend fun approve(): AppResult<ScreenShareSession> {
        val incoming = _state.value as? ShareState.Incoming ?: return AppResult.Failure(
            com.familysafety.app.domain.model.AppError.Validation("There is no pending request"))
        val r = repo.approve(incoming.session.id)
        if (r is AppResult.Success) {
            approvedSession = r.value
            notifier.cancelShareRequest()
            dispatch(ShareEvent.ChildApproved(r.value))
        } else dispatch(ShareEvent.Failure("APPROVE_FAILED"))
        return r
    }

    suspend fun reject() {
        val id = _state.value.sessionId ?: return
        repo.reject(id)
        notifier.cancelShareRequest()
        dispatch(ShareEvent.ChildRejected)
    }

    /** Called with the result of the system MediaProjection consent dialog. */
    suspend fun onSystemPermission(granted: Boolean) {
        if (granted) dispatch(ShareEvent.SystemPermissionGranted) else {
            _state.value.sessionId?.let { repo.stop(it, StopReason.PERMISSION_DENIED) }
            dispatch(ShareEvent.SystemPermissionDenied)
        }
    }

    suspend fun stop(reason: StopReason = StopReason.USER_STOPPED) {
        val id = _state.value.sessionId ?: return
        if (_state.value !is ShareState.Ended) repo.stop(id, reason)
        dispatch(if (reason == StopReason.USER_STOPPED) ShareEvent.ChildStopped else ShareEvent.Failure(reason.name))
        approvedSession = null
    }

    fun dismiss() = dispatch(ShareEvent.Dismiss)
}
