package com.familysafety.app

import androidx.test.core.app.ApplicationProvider
import com.familysafety.app.data.server.ServerConfigStore
import com.familysafety.app.data.server.ServerHealthChecker
import com.familysafety.app.ui.auth.ConnectionCheck
import com.familysafety.app.ui.auth.ServerSettingsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class ServerSettingsViewModelTest {
    private val server = MockWebServer()
    private lateinit var store: ServerConfigStore

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        server.start()
        store = ServerConfigStore(ApplicationProvider.getApplicationContext(), "https://api.familysafety.example/", allowCleartext = true)
        kotlinx.coroutines.runBlocking { store.reset() }
    }
    @After fun tearDown() { Dispatchers.resetMain(); server.shutdown() }

    private fun vm() = ServerSettingsViewModel(store, ServerHealthChecker(OkHttpClient(), Dispatchers.Unconfined))

    @Test
    fun `placeholder default is not prefilled`() = assertEquals("", vm().state.value.input)

    @Test
    fun `save stores the address only after the server answers`() {
        server.enqueue(MockResponse().setBody("""{"status":"UP"}"""))
        val vm = vm()
        vm.onInput(server.url("/").toString())
        vm.save()
        // DataStore writes complete on its own IO thread.
        val deadline = System.currentTimeMillis() + 5_000
        while (!vm.state.value.saved && System.currentTimeMillis() < deadline) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper(); Thread.sleep(20)
        }
        assertTrue(vm.state.value.saved)
        assertEquals(server.url("/").toString(), store.current.toString())
    }

    @Test
    fun `unreachable server is not saved`() {
        val vm = vm()
        vm.onInput("http://127.0.0.1:1")
        vm.save()
        assertTrue(vm.state.value.check is ConnectionCheck.Failed)
        assertTrue(store.isPlaceholder)
    }

    @Test
    fun `invalid input shows a validation error without a network call`() {
        val vm = vm()
        vm.onInput("not a url")
        vm.test()
        assertTrue(vm.state.value.validationError != null)
        assertEquals(0, server.requestCount)
    }
}
