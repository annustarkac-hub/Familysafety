package com.familysafety.app.data.server

import com.familysafety.app.data.network.NetworkErrors
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

sealed interface HealthResult {
    data object Reachable : HealthResult
    data class Unreachable(val message: String) : HealthResult
}

/** "Test connection": checks that a Family Safety backend answers its health endpoint at [url]. */
class ServerHealthChecker(base: OkHttpClient, private val io: CoroutineDispatcher) {
    private val client = base.newBuilder()
        .connectTimeout(8, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).callTimeout(12, TimeUnit.SECONDS)
        .build()

    suspend fun check(url: HttpUrl): HealthResult = withContext(io) {
        val health = url.newBuilder().addPathSegments("actuator/health").build()
        try {
            client.newCall(Request.Builder().url(health).get().build()).execute().use { r ->
                val body = r.body?.string().orEmpty()
                when {
                    r.isSuccessful && body.contains("\"UP\"") -> HealthResult.Reachable
                    r.isSuccessful -> HealthResult.Unreachable("The server answered, but it doesn't look like a Family Safety server.")
                    r.code == 404 -> HealthResult.Unreachable("The server answered, but no Family Safety API was found at this address.")
                    r.code == 403 && body.contains("HTTPS_REQUIRED") -> HealthResult.Unreachable("The server requires HTTPS. Use an https:// address.")
                    else -> HealthResult.Unreachable("The server responded with an error (HTTP ${r.code}).")
                }
            }
        } catch (e: IOException) {
            HealthResult.Unreachable(NetworkErrors.describe(e, url.host))
        } catch (e: IllegalArgumentException) {
            HealthResult.Unreachable("That address can't be used.")
        }
    }
}
