package com.familysafety.app.domain.logic

import com.familysafety.app.domain.model.ScreenShareSession
import com.familysafety.app.domain.model.ScreenShareStatus

/** Child-side screen-sharing lifecycle. Pure reducer so every transition is unit-testable. */
sealed interface ShareState {
    data object Idle : ShareState
    data class Incoming(val session: ScreenShareSession) : ShareState
    data class AwaitingSystemPermission(val session: ScreenShareSession) : ShareState
    data class Starting(val session: ScreenShareSession) : ShareState
    data class Sharing(val session: ScreenShareSession) : ShareState
    data class Ended(val endedSessionId: String, val reason: String) : ShareState

    val sessionId: String? get() = when (this) {
        is Incoming -> session.id
        is AwaitingSystemPermission -> session.id
        is Starting -> session.id
        is Sharing -> session.id
        is Ended -> endedSessionId
        Idle -> null
    }
}

sealed interface ShareEvent {
    data class RequestReceived(val session: ScreenShareSession) : ShareEvent
    data class ChildApproved(val session: ScreenShareSession) : ShareEvent
    data object ChildRejected : ShareEvent
    data object SystemPermissionGranted : ShareEvent
    data object SystemPermissionDenied : ShareEvent
    data object PeerConnected : ShareEvent
    data object ChildStopped : ShareEvent
    data class ServerUpdate(val session: ScreenShareSession) : ShareEvent
    data class Failure(val reason: String) : ShareEvent
    data object Dismiss : ShareEvent
}

object ScreenShareLifecycle {
    fun reduce(state: ShareState, event: ShareEvent): ShareState = when (event) {
        is ShareEvent.RequestReceived ->
            if (state is ShareState.Idle || state is ShareState.Ended) {
                if (event.session.status == ScreenShareStatus.REQUESTED) ShareState.Incoming(event.session) else state
            } else state // ignore new requests while one is in progress
        is ShareEvent.ChildApproved ->
            if (state is ShareState.Incoming && state.session.id == event.session.id) ShareState.AwaitingSystemPermission(event.session) else state
        ShareEvent.ChildRejected -> state.sessionId?.let { ShareState.Ended(it, "REJECTED") } ?: state
        ShareEvent.SystemPermissionGranted ->
            if (state is ShareState.AwaitingSystemPermission) ShareState.Starting(state.session) else state
        ShareEvent.SystemPermissionDenied -> state.sessionId?.let { ShareState.Ended(it, "PERMISSION_DENIED") } ?: state
        ShareEvent.PeerConnected -> if (state is ShareState.Starting) ShareState.Sharing(state.session) else state
        ShareEvent.ChildStopped -> state.sessionId?.let { ShareState.Ended(it, "STOPPED_BY_CHILD") } ?: state
        is ShareEvent.Failure -> state.sessionId?.let { ShareState.Ended(it, event.reason) } ?: state
        is ShareEvent.ServerUpdate -> {
            val s = event.session
            if (state.sessionId != s.id) {
                if (s.status == ScreenShareStatus.REQUESTED && (state is ShareState.Idle || state is ShareState.Ended)) ShareState.Incoming(s) else state
            } else if (!s.status.isOpen) {
                if (state is ShareState.Ended) state else ShareState.Ended(s.id, s.endReason ?: s.status.name)
            } else state
        }
        ShareEvent.Dismiss -> if (state is ShareState.Ended || state is ShareState.Incoming) ShareState.Idle else state
    }
}
