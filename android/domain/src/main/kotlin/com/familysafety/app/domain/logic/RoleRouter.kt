package com.familysafety.app.domain.logic

import com.familysafety.app.domain.model.AuthState
import com.familysafety.app.domain.model.Role

enum class Destination { SPLASH, AUTH, PARENT_HOME, CHILD_PAIRING, CHILD_HOME }

/**
 * Decides the top-level destination for the current auth state. This is a UX convenience only:
 * every action is authorised again by the backend using the role bound into the access token.
 */
object RoleRouter {
    fun destinationFor(state: AuthState): Destination = when (state) {
        AuthState.Loading -> Destination.SPLASH
        AuthState.SignedOut -> Destination.AUTH
        is AuthState.SignedIn -> when (state.user.role) {
            Role.PARENT -> Destination.PARENT_HOME
            Role.CHILD -> if (state.paired) Destination.CHILD_HOME else Destination.CHILD_PAIRING
        }
    }
}
