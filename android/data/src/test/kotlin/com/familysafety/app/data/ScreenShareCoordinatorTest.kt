package com.familysafety.app.data

import com.familysafety.app.data.notify.Notifier
import com.familysafety.app.data.webrtc.ScreenShareCoordinator
import com.familysafety.app.domain.logic.ShareState
import com.familysafety.app.domain.model.AppResult
import com.familysafety.app.domain.model.ConnectionState
import com.familysafety.app.domain.model.RealtimeEvent
import com.familysafety.app.domain.model.ScreenShareSession
import com.familysafety.app.domain.model.ScreenShareStatus
import com.familysafety.app.domain.model.StopReason
import com.familysafety.app.domain.repository.RealtimeRepository
import com.familysafety.app.domain.repository.ScreenShareRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ScreenShareCoordinatorTest {
    private fun session(status: ScreenShareStatus, reason: String? = null) =
        ScreenShareSession("s1", "d1", status, "Pat", Instant.EPOCH, Instant.now().plusSeconds(60), null, null, null, reason, emptyList())

    private val events = MutableSharedFlow<RealtimeEvent>(extraBufferCapacity = 8)
    private val realtime = object : RealtimeRepository {
        override val events = this@ScreenShareCoordinatorTest.events
        override val connectionState = MutableStateFlow(ConnectionState.CONNECTED)
        override fun connect() {}
        override fun disconnect() {}
        override fun sendSignal(sessionId: String, payload: Map<String, Any?>) = true
    }
    private val repo = mockk<ScreenShareRepository>(relaxed = true)
    private val notifier = mockk<Notifier>(relaxed = true)

    @Test
    fun `request then approve then system denial reports permission denied`() = runTest(UnconfinedTestDispatcher()) {
        val c = ScreenShareCoordinator(repo, realtime, notifier, backgroundScope)
        events.emit(RealtimeEvent.ScreenShareRequested(session(ScreenShareStatus.REQUESTED)))
        assertTrue(c.state.value is ShareState.Incoming)
        verify { notifier.showShareRequest(any()) }

        coEvery { repo.approve("s1") } returns AppResult.Success(session(ScreenShareStatus.APPROVED))
        c.approve()
        assertTrue(c.state.value is ShareState.AwaitingSystemPermission)

        c.onSystemPermission(false)
        coVerify { repo.stop("s1", StopReason.PERMISSION_DENIED) }
        assertEquals(ShareState.Ended("s1", "PERMISSION_DENIED"), c.state.value)
    }

    @Test
    fun `server-side termination and revocation end the session`() = runTest(UnconfinedTestDispatcher()) {
        val c = ScreenShareCoordinator(repo, realtime, notifier, backgroundScope)
        events.emit(RealtimeEvent.ScreenShareRequested(session(ScreenShareStatus.REQUESTED)))
        events.emit(RealtimeEvent.ScreenShareUpdated(session(ScreenShareStatus.EXPIRED, "REQUEST_TIMEOUT")))
        assertEquals(ShareState.Ended("s1", "REQUEST_TIMEOUT"), c.state.value)
        verify { notifier.cancelShareRequest() }

        c.dismiss()
        events.emit(RealtimeEvent.ScreenShareRequested(session(ScreenShareStatus.REQUESTED)))
        events.emit(RealtimeEvent.DeviceRevoked("d1"))
        assertEquals(ShareState.Ended("s1", "DEVICE_REVOKED"), c.state.value)
    }

    @Test
    fun `reject calls the backend`() = runTest(UnconfinedTestDispatcher()) {
        val c = ScreenShareCoordinator(repo, realtime, notifier, backgroundScope)
        events.emit(RealtimeEvent.ScreenShareRequested(session(ScreenShareStatus.REQUESTED)))
        c.reject()
        coVerify { repo.reject("s1") }
        assertEquals(ShareState.Ended("s1", "REJECTED"), c.state.value)
    }

    @Test
    fun `approve without a pending request is refused`() = runTest(UnconfinedTestDispatcher()) {
        val c = ScreenShareCoordinator(repo, realtime, notifier, backgroundScope)
        assertTrue(c.approve() is AppResult.Failure)
        coVerify(exactly = 0) { repo.approve(any()) }
    }
}
