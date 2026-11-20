package com.familysafety.app.feature.parent

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.SavedStateHandle
import com.familysafety.app.core.designsystem.FamilySafetyTheme
import com.familysafety.app.data.webrtc.WebRtcEngine
import com.familysafety.app.domain.model.AlertSeverity
import com.familysafety.app.domain.model.AlertType
import com.familysafety.app.domain.model.AppError
import com.familysafety.app.domain.model.AppResult
import com.familysafety.app.domain.model.ConnectionState
import com.familysafety.app.domain.model.PairingCode
import com.familysafety.app.domain.model.RealtimeEvent
import com.familysafety.app.domain.model.SafetyAlert
import com.familysafety.app.domain.model.ScreenShareSession
import com.familysafety.app.domain.model.ScreenShareStatus
import com.familysafety.app.domain.repository.PairingRepository
import com.familysafety.app.domain.repository.RealtimeRepository
import com.familysafety.app.domain.repository.ScreenShareRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

class FakeRealtime : RealtimeRepository {
    override val events = MutableSharedFlow<RealtimeEvent>(extraBufferCapacity = 16)
    override val connectionState = MutableStateFlow(ConnectionState.CONNECTED)
    override fun connect() {}
    override fun disconnect() {}
    override fun sendSignal(sessionId: String, payload: Map<String, Any?>) = true
}

@OptIn(ExperimentalCoroutinesApi::class)
class PairDeviceViewModelTest {
    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `code is shown and pairing completion is detected in real time`() = runTest {
        val repo = mockk<PairingRepository>()
        coEvery { repo.createCode("Casey") } returns AppResult.Success(PairingCode("ABCD-2345", Instant.now().plusSeconds(600), "Casey"))
        val rt = FakeRealtime()
        val vm = PairDeviceViewModel(repo, rt)
        vm.create("Casey")
        assertEquals("ABCD-2345", (vm.state.value as PairDeviceViewModel.UiState.ShowingCode).code.code)
        rt.events.emit(RealtimeEvent.PairingCompleted("d1", "Casey"))
        assertEquals(PairDeviceViewModel.UiState.Paired("Casey"), vm.state.value)
    }

    @Test
    fun `errors are surfaced`() = runTest {
        val repo = mockk<PairingRepository>()
        coEvery { repo.createCode(any()) } returns AppResult.Failure(AppError.Api(429, "TOO_MANY_ACTIVE_CODES", "Too many active pairing codes."))
        val vm = PairDeviceViewModel(repo, FakeRealtime())
        vm.create("")
        assertEquals(PairDeviceViewModel.UiState.Error("Too many active pairing codes."), vm.state.value)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ScreenViewerViewModelTest {
    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private fun session(status: ScreenShareStatus, reason: String? = null) =
        ScreenShareSession("s1", "d1", status, "Pat", Instant.now(), Instant.now().plusSeconds(90), null, null, null, reason, emptyList())

    @Test
    fun `request waits for consent and a rejection ends the session without connecting`() = runTest {
        val repo = mockk<ScreenShareRepository>(relaxed = true)
        coEvery { repo.request("d1") } returns AppResult.Success(session(ScreenShareStatus.REQUESTED))
        coEvery { repo.status("s1") } returns AppResult.Success(session(ScreenShareStatus.REQUESTED))
        val rt = FakeRealtime()
        val vm = ScreenViewerViewModel(SavedStateHandle(mapOf("deviceId" to "d1")), repo, rt,
            WebRtcEngine(androidx.test.core.app.ApplicationProvider.getApplicationContext()), TestScope())
        vm.request()
        assertTrue(vm.phase.value is ScreenViewerViewModel.Phase.WaitingForChild)
        rt.events.emit(RealtimeEvent.ScreenShareUpdated(session(ScreenShareStatus.REJECTED, "CHILD_REJECTED")))
        assertEquals(ScreenViewerViewModel.Phase.Ended("The request was declined."), vm.phase.value)
    }

    @Test
    fun `request failure is reported`() = runTest {
        val repo = mockk<ScreenShareRepository>(relaxed = true)
        coEvery { repo.request("d1") } returns AppResult.Failure(AppError.Api(403, "SCREEN_SHARING_DISABLED", "Screen sharing is disabled"))
        val vm = ScreenViewerViewModel(SavedStateHandle(mapOf("deviceId" to "d1")), repo, FakeRealtime(),
            WebRtcEngine(androidx.test.core.app.ApplicationProvider.getApplicationContext()), TestScope())
        vm.request()
        assertEquals(ScreenViewerViewModel.Phase.Error("Screen sharing is disabled"), vm.phase.value)
    }
}

@RunWith(RobolectricTestRunner::class)
class AlertRowUiTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `unacknowledged alert offers acknowledgement`() {
        var acked = false
        val alert = SafetyAlert("a1", "d1", AlertType.HELP_REQUEST, AlertSeverity.CRITICAL, "PARENTS", "Casey is asking for help", "I need help",
            Instant.now(), null)
        compose.setContent { FamilySafetyTheme(dynamicColor = false) { AlertRow(alert) { acked = true } } }
        compose.onNodeWithText("Casey is asking for help").assertIsDisplayed()
        compose.onNodeWithText("Mark as seen").performClick()
        assertTrue(acked)
    }
}
