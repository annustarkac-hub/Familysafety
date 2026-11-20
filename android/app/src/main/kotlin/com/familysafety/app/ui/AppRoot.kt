package com.familysafety.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.familysafety.app.core.designsystem.LoadingState
import com.familysafety.app.data.notify.Notifier
import com.familysafety.app.domain.logic.Destination
import com.familysafety.app.domain.logic.RoleRouter
import com.familysafety.app.domain.model.AuthState
import com.familysafety.app.domain.repository.AuthRepository
import com.familysafety.app.feature.child.ChildPairingScreen
import com.familysafety.app.feature.child.ChildRoot
import com.familysafety.app.feature.child.ChildRoutes
import com.familysafety.app.feature.parent.ParentRoot
import com.familysafety.app.ui.auth.AuthScreen
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class RootViewModel @Inject constructor(auth: AuthRepository) : ViewModel() {
    val authState = auth.authState
}

/** Top-level role routing (UX only — the backend authorises every request independently). */
@Composable
fun AppRoot(deepLink: String?, vm: RootViewModel = hiltViewModel()) {
    val state by vm.authState.collectAsStateWithLifecycle()
    RoleContent(state, deepLink)
}

/** Snapshot of what to render, so an outgoing animated screen never reads the *new* auth state. */
private data class Route(val destination: Destination, val userId: String?)

@Composable
fun RoleContent(state: AuthState, deepLink: String?) {
    val route = Route(RoleRouter.destinationFor(state), (state as? AuthState.SignedIn)?.user?.id)
    AnimatedContent(
        targetState = route,
        transitionSpec = { (fadeIn(tween(300)) + scaleIn(tween(300), initialScale = 0.98f)) togetherWith fadeOut(tween(200)) },
        contentKey = { it.destination to it.userId },
        label = "roleRoute",
    ) { r ->
        when (r.destination) {
            Destination.SPLASH -> LoadingState(message = "Starting…")
            Destination.AUTH -> AuthScreen()
            Destination.PARENT_HOME -> ParentRoot(startOnAlerts = deepLink == Notifier.DEST_ALERTS)
            Destination.CHILD_PAIRING -> ChildPairingScreen()
            Destination.CHILD_HOME -> ChildRoot(when (deepLink) {
                Notifier.DEST_SCREEN_SHARE_CONSENT -> ChildRoutes.CONSENT
                Notifier.DEST_PERMISSIONS -> ChildRoutes.PERMISSIONS
                Notifier.DEST_ALERTS -> ChildRoutes.SAFETY
                else -> null
            })
        }
    }
}
