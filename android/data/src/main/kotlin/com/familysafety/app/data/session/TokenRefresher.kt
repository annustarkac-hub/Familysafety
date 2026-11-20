package com.familysafety.app.data.session

import com.familysafety.app.data.network.RefreshApi
import com.familysafety.app.data.network.RefreshBody
import com.familysafety.app.data.network.TokenResponseDto
import kotlinx.coroutines.runBlocking
import java.io.IOException

sealed interface RefreshOutcome {
    data class Refreshed(val accessToken: String) : RefreshOutcome
    /** The server rejected the refresh token: the session is over and local credentials were cleared. */
    data object SessionEnded : RefreshOutcome
    /** Transient failure (network); keep the session and retry later. */
    data object Transient : RefreshOutcome
}

/**
 * Serialises refresh-token rotation. The backend treats refresh tokens as single-use and revokes the whole
 * session family on reuse, so concurrent refreshes MUST NOT send the same token twice.
 */
class TokenRefresher(private val store: SessionStore, private val api: RefreshApi) {
    private val lock = Any()

    /**
     * @param failedAccessToken the token that was rejected; if another thread already rotated it, the newer
     * token is returned without a network call.
     */
    fun refreshBlocking(failedAccessToken: String?): RefreshOutcome = synchronized(lock) {
        val current = store.session.value ?: return RefreshOutcome.SessionEnded
        if (failedAccessToken != null && current.accessToken != failedAccessToken) {
            return RefreshOutcome.Refreshed(current.accessToken)
        }
        val response = try {
            api.refresh(RefreshBody(current.refreshToken)).execute()
        } catch (e: IOException) {
            return RefreshOutcome.Transient
        }
        val body = response.body()
        return when {
            response.isSuccessful && body != null -> {
                runBlocking { store.save(current.merge(body)) }
                RefreshOutcome.Refreshed(body.accessToken)
            }
            response.code() == 401 || response.code() == 403 -> {
                runBlocking { store.clear() }
                RefreshOutcome.SessionEnded
            }
            else -> RefreshOutcome.Transient
        }
    }
}

fun StoredSession?.merge(t: TokenResponseDto, paired: Boolean? = null) = StoredSession(
    accessToken = t.accessToken, accessTokenExpiresAt = t.accessTokenExpiresAt, refreshToken = t.refreshToken,
    userId = t.user.id, email = t.user.email, displayName = t.user.displayName, role = t.user.role,
    familyId = t.user.familyId, familyName = t.user.familyName, deviceId = t.deviceId,
    paired = paired ?: this?.paired ?: false,
)
