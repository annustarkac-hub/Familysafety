package com.familysafety.app.feature.child

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.familysafety.app.core.designsystem.FamilySafetyTheme
import com.familysafety.app.domain.model.AlertType
import com.familysafety.app.domain.model.AppError
import com.familysafety.app.domain.model.AppResult
import com.familysafety.app.domain.model.ConnectionState
import com.familysafety.app.domain.model.PairingResult
import com.familysafety.app.domain.model.RealtimeEvent
import com.familysafety.app.domain.model.SafetyAlert
import com.familysafety.app.domain.repository.AuthRepository
import com.familysafety.app.domain.repository.PairingRepository
import com.familysafety.app.domain.repository.RealtimeRepository
import com.familysafety.app.domain.repository.SafetyRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class ChildPairingViewModelTest {
    private val pairing = mockk<PairingRepository>()
    private val auth = mockk<AuthRepository>(relaxed = true)

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `input is normalised and invalid codes never reach the server`() {
        val vm = ChildPairingViewModel(pairing, auth)
        vm.onCodeChange("ab-0o")
        assertEquals("AB0O", vm.code.value)
        vm.submit()
        assertEquals("Enter the 8-character code shown on your parent's phone.", vm.error.value)
        coVerify(exactly = 0) { pairing.acceptCode(any()) }
    }

    @Test
    fun `valid code is accepted`() {
        coEvery { pairing.acceptCode("ABCD2345") } returns AppResult.Success(PairingResult("Pat's family", listOf("Pat"), Instant.now()))
        val vm = ChildPairingViewModel(pairing, auth)
        vm.onCodeChange("abcd-2345")
        vm.submit()
        coVerify { pairing.acceptCode("ABCD2345") }
        assertNull(vm.error.value)
    }

    @Test
    fun `expired code message is shown`() {
        coEvery { pairing.acceptCode(any()) } returns AppResult.Failure(AppError.Api(410, "PAIRING_CODE_EXPIRED", "That pairing code has expired. Ask your parent for a new one."))
        val vm = ChildPairingViewModel(pairing, auth)
        vm.onCodeChange("ABCD2345")
        vm.submit()
        assertEquals("That pairing code has expired. Ask your parent for a new one.", vm.error.value)
        assertEquals(false, vm.busy.value)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ChildSafetyViewModelTest {
    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private val realtime = object : RealtimeRepository {
        override val events = MutableSharedFlow<RealtimeEvent>()
        override val connectionState = MutableStateFlow(ConnectionState.CONNECTED)
        override fun connect() {}
        override fun disconnect() {}
        override fun sendSignal(sessionId: String, payload: Map<String, Any?>) = true
    }

    @Test
    fun `help request is sent with the right type`() {
        val safety = mockk<SafetyRepository>()
        coEvery { safety.alerts(any(), any()) } returns AppResult.Success(emptyList())
        coEvery { safety.createAlert(AlertType.HELP_REQUEST, "I need help", null, null) } returns
            AppResult.Success(mockk<SafetyAlert>(relaxed = true))
        val vm = ChildSafetyViewModel(safety, realtime)
        vm.send(AlertType.HELP_REQUEST, "I need help")
        assertEquals("Sent to your parents", vm.message.value)
    }
}

@RunWith(RobolectricTestRunner::class)
class ChildTransparencyUiTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `child is told what is and is not shared before pairing`() {
        compose.setContent { FamilySafetyTheme(dynamicColor = false) { WhatIsSharedCard() } }
        compose.onNodeWithText("What your parents can see").assertIsDisplayed()
        compose.onNodeWithText("What they can never see").assertIsDisplayed()
    }

    @Test
    fun `sharing banner is announced`() {
        compose.setContent { FamilySafetyTheme(dynamicColor = false) { SharingBanner {} } }
        compose.onNodeWithText("Your screen is being shared. Tap to stop.").assertIsDisplayed()
    }
}
