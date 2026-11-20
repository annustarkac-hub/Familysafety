package com.familysafety.app.data

import com.familysafety.app.data.db.DeviceDao
import com.familysafety.app.data.db.LocalUsageDao
import com.familysafety.app.data.db.NotifiedAlertDao
import com.familysafety.app.data.network.DeviceInfoBody
import com.familysafety.app.data.network.NetworkErrors
import com.familysafety.app.data.repository.AuthRepositoryImpl
import com.familysafety.app.data.repository.DeviceInfoProvider
import com.familysafety.app.data.repository.SessionLifecycleListener
import com.familysafety.app.data.server.BaseUrlInterceptor
import com.familysafety.app.data.server.HealthResult
import com.familysafety.app.data.server.ServerConfigStore
import com.familysafety.app.data.server.ServerHealthChecker
import com.familysafety.app.data.server.ServerUrlValidation
import com.familysafety.app.data.server.ServerUrls
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

class ServerUrlsTest {
    private fun valid(input: String, cleartext: Boolean = false) =
        (ServerUrls.validate(input, cleartext) as ServerUrlValidation.Valid).url.toString()

    @Test
    fun `adds https scheme and trailing slash`() {
        assertEquals("https://api.example.org/", valid("api.example.org"))
        assertEquals("https://fs.example.org/api/", valid("https://fs.example.org/api"))
        assertEquals("https://abc.trycloudflare.com/", valid("  https://abc.trycloudflare.com  "))
    }

    @Test
    fun `release builds reject cleartext, debug builds allow it`() {
        assertTrue(ServerUrls.validate("http://192.168.1.20:8080", allowCleartext = false) is ServerUrlValidation.Invalid)
        assertEquals("http://192.168.1.20:8080/", valid("http://192.168.1.20:8080", cleartext = true))
    }

    @Test
    fun `rejects junk`() {
        listOf("", "   ", "not a url", "https://", "https://host?x=1", "https://bad host.com", "ftp://x.org").forEach {
            assertTrue("expected invalid: '$it'", ServerUrls.validate(it, true) is ServerUrlValidation.Invalid)
        }
    }

    @Test
    fun `detects the shipped placeholder`() {
        assertTrue(ServerUrls.isPlaceholder("https://api.familysafety.example/".toHttpUrl()))
        assertFalse(ServerUrls.isPlaceholder("https://api.myfamily.org/".toHttpUrl()))
    }
}

class BaseUrlInterceptorTest {
    private val build = "https://api.familysafety.example/".toHttpUrl()

    @Test
    fun `rewrites api requests to the configured server keeping path and query`() {
        val out = BaseUrlInterceptor.rewrite("https://api.familysafety.example/api/v1/usage/daily?deviceId=d&date=2026-01-01".toHttpUrl(),
            build, "https://fs.example.org/prefix/".toHttpUrl())
        assertEquals("https://fs.example.org/prefix/api/v1/usage/daily?deviceId=d&date=2026-01-01", out.toString())
    }

    @Test
    fun `leaves other hosts alone`() {
        val url = "https://stun.example.net/x".toHttpUrl()
        assertEquals(url, BaseUrlInterceptor.rewrite(url, build, "https://fs.example.org/".toHttpUrl()))
    }

    @Test
    fun `requests really reach the configured server`() {
        val server = MockWebServer().apply { enqueue(MockResponse().setBody("ok")); start() }
        val client = OkHttpClient.Builder().addInterceptor(BaseUrlInterceptor(build) { server.url("/") }).build()
        client.newCall(Request.Builder().url("https://api.familysafety.example/api/v1/auth/me").build()).execute().use {
            assertEquals("ok", it.body!!.string())
        }
        assertEquals("/api/v1/auth/me", server.takeRequest().path)
        server.shutdown()
    }
}

class NetworkErrorsTest {
    @Test
    fun `messages are specific to the failure`() {
        assertTrue(NetworkErrors.describe(UnknownHostException(), "x.org").startsWith("Can't find the server (x.org)"))
        assertTrue(NetworkErrors.describe(SocketTimeoutException(), null).contains("too long"))
        assertTrue(NetworkErrors.describe(ConnectException(), null).contains("refused"))
        assertTrue(NetworkErrors.describe(SSLHandshakeException("bad"), null).contains("certificate"))
    }
}

class ServerHealthCheckerTest {
    private val checker = ServerHealthChecker(OkHttpClient(), Dispatchers.Unconfined)

    private fun withServer(response: MockResponse, block: (MockWebServer) -> Unit) {
        val s = MockWebServer().apply { enqueue(response); start() }
        try { block(s) } finally { s.shutdown() }
    }

    @Test
    fun `healthy family safety server is reachable`() = withServer(MockResponse().setBody("""{"status":"UP"}""")) { s ->
        assertEquals(HealthResult.Reachable, runBlocking { checker.check(s.url("/")) })
        assertEquals("/actuator/health", s.takeRequest().path)
    }

    @Test
    fun `other servers are explained`() {
        withServer(MockResponse().setResponseCode(404)) { s ->
            assertTrue((runBlocking { checker.check(s.url("/")) } as HealthResult.Unreachable).message.contains("no Family Safety API"))
        }
        withServer(MockResponse().setBody("<html>hello</html>")) { s ->
            assertTrue((runBlocking { checker.check(s.url("/")) } as HealthResult.Unreachable).message.contains("doesn't look like"))
        }
    }

    @Test
    fun `unreachable host is reported`() {
        val r = runBlocking { checker.check("http://127.0.0.1:1/".toHttpUrl()) }
        assertTrue(r is HealthResult.Unreachable)
    }
}

@RunWith(RobolectricTestRunner::class)
class ServerConfigStoreTest {
    @Test
    fun `saved address survives reload and reset restores default`() = runBlocking {
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val a = ServerConfigStore(ctx, "https://api.familysafety.example/", allowCleartext = false)
        assertTrue(a.isPlaceholder)
        a.save("https://fs.example.org/".toHttpUrl())
        val b = ServerConfigStore(ctx, "https://api.familysafety.example/", allowCleartext = false)
        b.load()
        assertEquals("https://fs.example.org/", b.current.toString())
        assertFalse(b.isPlaceholder)
        b.reset()
        assertTrue(b.isPlaceholder)
    }
}

@RunWith(RobolectricTestRunner::class)
class SessionEndedListenerTest {
    @Test
    fun `listeners are told when the session is cleared outside the repository`() = runBlocking {
        val env = TestEnv()
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Default)
        env.store.save(session())
        val listener = mockk<SessionLifecycleListener>(relaxed = true)
        AuthRepositoryImpl(env.api, env.store, DeviceInfoProvider { DeviceInfoBody("installation-1", "x") },
            mockk<LocalUsageDao>(relaxed = true), mockk<DeviceDao>(relaxed = true), mockk<NotifiedAlertDao>(relaxed = true),
            setOf(listener), scope)
        Thread.sleep(100) // let the collector observe the signed-in state first
        env.store.clear() // e.g. TokenRefresher after REFRESH_TOKEN_REUSED
        verify(timeout = 3_000) { listener.onSignedOut() }
        scope.coroutineContext[kotlinx.coroutines.Job]!!.cancel()
        env.shutdown()
    }
}
