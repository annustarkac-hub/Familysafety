package com.familysafety.app.data

import com.familysafety.app.data.db.DeviceDao
import com.familysafety.app.data.db.LocalUsageDao
import com.familysafety.app.data.db.NotifiedAlertDao
import com.familysafety.app.data.network.DeviceInfoBody
import com.familysafety.app.data.network.httpError
import com.familysafety.app.data.repository.AuthRepositoryImpl
import com.familysafety.app.data.repository.DeviceInfoProvider
import com.familysafety.app.data.repository.SessionLifecycleListener
import com.familysafety.app.domain.model.AppError
import com.familysafety.app.domain.model.AppResult
import com.familysafety.app.domain.model.AuthState
import com.familysafety.app.domain.model.Role
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class TokenRefreshTest {
    private lateinit var env: TestEnv

    @Before fun setUp() { env = TestEnv(); runBlocking { env.store.clear(); env.store.save(session()) } }
    @After fun tearDown() = env.shutdown()

    @Test
    fun `401 triggers a single refresh and the request is retried with the new token`() = runBlocking {
        env.server.enqueue(MockResponse().setResponseCode(401))
        env.server.enqueue(MockResponse().setBody(tokenJson("access-2", "refresh-2")))
        env.server.enqueue(MockResponse().setBody("[]"))

        env.api.devices()

        val first = env.server.takeRequest()
        assertEquals("Bearer access-1", first.getHeader("Authorization"))
        val refresh = env.server.takeRequest()
        assertEquals("/api/v1/auth/refresh", refresh.path)
        assertTrue(refresh.body.readUtf8().contains("refresh-1"))
        val retry = env.server.takeRequest()
        assertEquals("Bearer access-2", retry.getHeader("Authorization"))
        assertEquals("refresh-2", env.store.session.value!!.refreshToken)
    }

    @Test
    fun `rejected refresh clears the local session`() = runBlocking {
        env.server.enqueue(MockResponse().setResponseCode(401))
        env.server.enqueue(MockResponse().setResponseCode(401).setBody("""{"code":"REFRESH_TOKEN_REUSED","message":"x","status":401}"""))
        val r = com.familysafety.app.data.network.safeApiCall { env.api.devices() }
        assertTrue((r as AppResult.Failure).error is AppError.Unauthorized)
        assertNull(env.store.session.value)
    }

    @Test
    fun `concurrent 401s rotate the refresh token only once`() {
        // Refresh tokens are single-use server-side; sending one twice would revoke the whole session family.
        val refreshCalls = java.util.concurrent.atomic.AtomicInteger()
        env.server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse = when {
                request.path == "/api/v1/auth/refresh" -> { refreshCalls.incrementAndGet(); Thread.sleep(100); MockResponse().setBody(tokenJson("access-2", "refresh-2")) }
                request.getHeader("Authorization") == "Bearer access-2" -> MockResponse().setBody("[]")
                else -> MockResponse().setResponseCode(401)
            }
        }
        val pool = Executors.newFixedThreadPool(4)
        val done = CountDownLatch(4)
        repeat(4) { pool.execute { runBlocking { runCatching { env.api.devices() } }; done.countDown() } }
        assertTrue(done.await(20, TimeUnit.SECONDS))
        assertEquals(1, refreshCalls.get())
        pool.shutdown()
    }

    @Test
    fun `auth endpoints never carry a bearer token`() = runBlocking {
        env.server.enqueue(MockResponse().setBody(tokenJson("a", "r")))
        env.api.login(com.familysafety.app.data.network.LoginBody("p@example.test", "pw", DeviceInfoBody("installation-1", "Phone")))
        assertNull(env.server.takeRequest().getHeader("Authorization"))
    }
}

@RunWith(RobolectricTestRunner::class)
class AuthRepositoryTest {
    private lateinit var env: TestEnv
    private val listener = mockk<SessionLifecycleListener>(relaxed = true)
    private val usageDao = mockk<LocalUsageDao>(relaxed = true)
    private val deviceDao = mockk<DeviceDao>(relaxed = true)
    private val notifiedDao = mockk<NotifiedAlertDao>(relaxed = true)

    @Before fun setUp() { env = TestEnv(); runBlocking { env.store.clear(); env.store.load() } }
    @After fun tearDown() = env.shutdown()

    private fun TestScope.repo() = AuthRepositoryImpl(env.api, env.store, DeviceInfoProvider { DeviceInfoBody("installation-123", "Test phone") },
        usageDao, deviceDao, notifiedDao, setOf(listener), backgroundScope)

    @Test
    fun `register stores the session and signs in with the chosen role`() = runTest(UnconfinedTestDispatcher()) {
        env.server.enqueue(MockResponse().setResponseCode(201).setBody(tokenJson("a1", "r1", role = "CHILD")))
        val repo = repo()
        val r = repo.register("kid@example.test", "a long enough password", "Kid", Role.CHILD)
        assertTrue(r is AppResult.Success)
        val state = repo.authState.first { it is AuthState.SignedIn } as AuthState.SignedIn
        assertEquals(Role.CHILD, state.user.role)
        assertEquals(false, state.paired)
        val body = env.server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"role\":\"CHILD\"") && body.contains("installation-123"))
        verify { listener.onSignedIn(Role.CHILD) }
    }

    @Test
    fun `login fetches pairing status`() = runTest(UnconfinedTestDispatcher()) {
        env.server.enqueue(MockResponse().setBody(tokenJson("a1", "r1", role = "CHILD")))
        env.server.enqueue(MockResponse().setBody("""{"user":{"id":"u1","email":"k@x.test","displayName":"Kid","role":"CHILD","familyId":"f9","familyName":"Fam"},"deviceId":"d1","paired":true}"""))
        val repo = repo()
        repo.login("k@x.test", "password")
        val state = repo.authState.first { it is AuthState.SignedIn && it.paired } as AuthState.SignedIn
        assertEquals("Fam", state.user.familyName)
    }

    @Test
    fun `invalid credentials map to a friendly API error`() = runTest(UnconfinedTestDispatcher()) {
        env.server.enqueue(MockResponse().setResponseCode(401).setBody("""{"code":"INVALID_CREDENTIALS","message":"Email or password is incorrect","status":401}"""))
        val r = repo().login("p@example.test", "wrong")
        val e = (r as AppResult.Failure).error as AppError.Api
        assertEquals("INVALID_CREDENTIALS", e.code)
    }

    @Test
    fun `network failure maps to Network error`() = runTest(UnconfinedTestDispatcher()) {
        env.server.shutdown()
        val r = repo().login("p@example.test", "pw")
        assertTrue((r as AppResult.Failure).error is AppError.Network)
    }

    @Test
    fun `logout clears local data even if the server is unreachable`() = runTest(UnconfinedTestDispatcher()) {
        env.store.save(session())
        val repo = repo()
        env.server.shutdown()
        repo.logout()
        assertEquals(AuthState.SignedOut, repo.authState.first { it == AuthState.SignedOut })
        coVerify { usageDao.clearApps(); deviceDao.clear(); notifiedDao.clear() }
        verify { listener.onSignedOut() }
    }

    @Test
    fun `session is persisted only in encrypted form and survives reload`() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<String>()
        val recording = object : com.familysafety.app.core.security.SecretCipher {
            val inner = FakeCipher()
            override fun encrypt(plain: String) = inner.encrypt(plain).also { recorded += it }
            override fun decrypt(encoded: String) = inner.decrypt(encoded)
        }
        val store = com.familysafety.app.data.session.SessionStore(env.context, recording, testJson)
        store.save(session(access = "secret-access-token"))
        assertTrue(recorded.isNotEmpty())
        assertTrue(recorded.none { it.contains("secret-access-token") })

        val other = com.familysafety.app.data.session.SessionStore(env.context, FakeCipher(), testJson)
        other.load()
        assertEquals("secret-access-token", other.accessToken)
    }
}

class ErrorMappingTest {
    @Test
    fun `maps backend codes and statuses`() {
        assertTrue(httpError(401, null) is AppError.Unauthorized)
        assertEquals("PAIRING_CODE_EXPIRED", (httpError(410, """{"code":"PAIRING_CODE_EXPIRED","message":"m","status":410}""") as AppError.Api).code)
        assertEquals("RATE_LIMITED", (httpError(429, null) as AppError.Api).code)
        assertEquals("SERVER_ERROR", (httpError(503, "<html>") as AppError.Api).code)
    }
}
