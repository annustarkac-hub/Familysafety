package com.familysafety.app.data.network

import com.familysafety.app.data.session.RefreshOutcome
import com.familysafety.app.data.session.SessionStore
import com.familysafety.app.data.session.TokenRefresher
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route

/** Adds the bearer token to API calls (never to the unauthenticated auth endpoints). */
class AuthHeaderInterceptor(private val store: SessionStore) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        val path = req.url.encodedPath
        val isPublic = path.endsWith("/auth/login") || path.endsWith("/auth/register") || path.endsWith("/auth/refresh")
        val token = store.accessToken
        val out = if (!isPublic && token != null && req.header("Authorization") == null) {
            req.newBuilder().header("Authorization", "Bearer $token").build()
        } else req
        return chain.proceed(out)
    }
}

/** On 401, rotates the refresh token once and retries; gives up (and signs out) if the server refuses. */
class TokenAuthenticator(private val refresher: TokenRefresher) : Authenticator {
    override fun authenticate(route: Route?, response: Response): Request? {
        if (response.request.url.encodedPath.contains("/auth/")) return null
        if (responseCount(response) >= 2) return null
        val failed = response.request.header("Authorization")?.removePrefix("Bearer ")
        return when (val outcome = refresher.refreshBlocking(failed)) {
            is RefreshOutcome.Refreshed -> response.request.newBuilder().header("Authorization", "Bearer ${outcome.accessToken}").build()
            else -> null
        }
    }

    private fun responseCount(response: Response): Int {
        var r: Response? = response
        var count = 1
        while (r?.priorResponse != null) { count++; r = r.priorResponse }
        return count
    }
}
