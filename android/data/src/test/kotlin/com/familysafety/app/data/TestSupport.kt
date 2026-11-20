package com.familysafety.app.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.familysafety.app.core.security.SecretCipher
import com.familysafety.app.data.network.ApiService
import com.familysafety.app.data.network.AuthHeaderInterceptor
import com.familysafety.app.data.network.RefreshApi
import com.familysafety.app.data.network.TokenAuthenticator
import com.familysafety.app.data.session.SessionStore
import com.familysafety.app.data.session.StoredSession
import com.familysafety.app.data.session.TokenRefresher
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.Base64

/** Reversible stand-in for the Keystore cipher (AndroidKeyStore is unavailable on the JVM). */
class FakeCipher : SecretCipher {
    override fun encrypt(plain: String): String = Base64.getEncoder().encodeToString(plain.reversed().toByteArray())
    override fun decrypt(encoded: String): String = String(Base64.getDecoder().decode(encoded)).reversed()
}

val testJson = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }

class TestEnv(val server: MockWebServer = MockWebServer().also { it.start() }) {
    val context: Context = ApplicationProvider.getApplicationContext()
    val store = SessionStore(context, FakeCipher(), testJson)
    private val base = OkHttpClient()
    private val factory = testJson.asConverterFactory("application/json".toMediaType())
    val refreshApi: RefreshApi = Retrofit.Builder().baseUrl(server.url("/")).client(base).addConverterFactory(factory).build().create(RefreshApi::class.java)
    val refresher = TokenRefresher(store, refreshApi)
    val api: ApiService = Retrofit.Builder().baseUrl(server.url("/"))
        .client(base.newBuilder().addInterceptor(AuthHeaderInterceptor(store)).authenticator(TokenAuthenticator(refresher)).build())
        .addConverterFactory(factory).build().create(ApiService::class.java)

    fun shutdown() = server.shutdown()
}

fun session(access: String = "access-1", refresh: String = "refresh-1", role: String = "PARENT", paired: Boolean = false) = StoredSession(
    accessToken = access, accessTokenExpiresAt = "2030-01-01T00:00:00Z", refreshToken = refresh, userId = "u1", email = "p@example.test",
    displayName = "Pat", role = role, familyId = "f1", familyName = "Pat's family", deviceId = "d1", paired = paired,
)

fun tokenJson(access: String, refresh: String, role: String = "PARENT") = """
    {"accessToken":"$access","accessTokenExpiresAt":"2030-01-01T00:00:00Z","refreshToken":"$refresh",
     "refreshTokenExpiresAt":"2030-02-01T00:00:00Z","tokenType":"Bearer",
     "user":{"id":"u1","email":"p@example.test","displayName":"Pat","role":"$role","familyId":"f1","familyName":"Pat's family"},
     "deviceId":"d1"}
""".trimIndent()
