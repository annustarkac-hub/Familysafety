package com.familysafety.app.domain

import com.familysafety.app.domain.logic.Destination
import com.familysafety.app.domain.logic.Formatting
import com.familysafety.app.domain.logic.RoleRouter
import com.familysafety.app.domain.logic.ScreenShareLifecycle
import com.familysafety.app.domain.logic.ShareEvent
import com.familysafety.app.domain.logic.ShareState
import com.familysafety.app.domain.model.AuthState
import com.familysafety.app.domain.model.Role
import com.familysafety.app.domain.model.ScreenShareSession
import com.familysafety.app.domain.model.ScreenShareStatus
import com.familysafety.app.domain.model.UserProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class RoleRouterTest {
    private fun user(role: Role) = UserProfile("u", "e@x.test", "Name", role, null, null)

    @Test
    fun `routes by role and pairing state`() {
        assertEquals(Destination.SPLASH, RoleRouter.destinationFor(AuthState.Loading))
        assertEquals(Destination.AUTH, RoleRouter.destinationFor(AuthState.SignedOut))
        assertEquals(Destination.PARENT_HOME, RoleRouter.destinationFor(AuthState.SignedIn(user(Role.PARENT), "d", false)))
        assertEquals(Destination.CHILD_PAIRING, RoleRouter.destinationFor(AuthState.SignedIn(user(Role.CHILD), "d", false)))
        assertEquals(Destination.CHILD_HOME, RoleRouter.destinationFor(AuthState.SignedIn(user(Role.CHILD), "d", true)))
    }
}

class ScreenShareLifecycleTest {
    private fun session(id: String = "s1", status: ScreenShareStatus = ScreenShareStatus.REQUESTED, reason: String? = null) =
        ScreenShareSession(id, "dev", status, "Parent", Instant.EPOCH, Instant.EPOCH, null, null, null, reason, emptyList())

    private fun run(vararg events: ShareEvent) = events.fold<ShareEvent, ShareState>(ShareState.Idle) { s, e -> ScreenShareLifecycle.reduce(s, e) }

    @Test
    fun `happy path request approve permission connect stop`() {
        val s = session()
        assertTrue(run(ShareEvent.RequestReceived(s)) is ShareState.Incoming)
        assertTrue(run(ShareEvent.RequestReceived(s), ShareEvent.ChildApproved(s.copy(status = ScreenShareStatus.APPROVED))) is ShareState.AwaitingSystemPermission)
        val sharing = run(ShareEvent.RequestReceived(s), ShareEvent.ChildApproved(s), ShareEvent.SystemPermissionGranted, ShareEvent.PeerConnected)
        assertTrue(sharing is ShareState.Sharing)
        assertEquals(ShareState.Ended("s1", "STOPPED_BY_CHILD"), ScreenShareLifecycle.reduce(sharing, ShareEvent.ChildStopped))
    }

    @Test
    fun `cannot start capture without explicit child approval`() {
        val s = session()
        val state = run(ShareEvent.RequestReceived(s), ShareEvent.SystemPermissionGranted, ShareEvent.PeerConnected)
        assertTrue(state is ShareState.Incoming)
    }

    @Test
    fun `system permission denial ends the session`() {
        val s = session()
        assertEquals(ShareState.Ended("s1", "PERMISSION_DENIED"),
            run(ShareEvent.RequestReceived(s), ShareEvent.ChildApproved(s), ShareEvent.SystemPermissionDenied))
    }

    @Test
    fun `server-side termination ends the local session`() {
        val s = session()
        val state = run(ShareEvent.RequestReceived(s), ShareEvent.ChildApproved(s), ShareEvent.SystemPermissionGranted, ShareEvent.PeerConnected,
            ShareEvent.ServerUpdate(session(status = ScreenShareStatus.EXPIRED, reason = "MAX_DURATION_REACHED")))
        assertEquals(ShareState.Ended("s1", "MAX_DURATION_REACHED"), state)
    }

    @Test
    fun `new requests are ignored while sharing`() {
        val s = session()
        val sharing = run(ShareEvent.RequestReceived(s), ShareEvent.ChildApproved(s), ShareEvent.SystemPermissionGranted, ShareEvent.PeerConnected)
        assertEquals(sharing, ScreenShareLifecycle.reduce(sharing, ShareEvent.RequestReceived(session("s2"))))
    }

    @Test
    fun `reject and dismiss return to idle`() {
        val ended = run(ShareEvent.RequestReceived(session()), ShareEvent.ChildRejected)
        assertEquals(ShareState.Ended("s1", "REJECTED"), ended)
        assertEquals(ShareState.Idle, ScreenShareLifecycle.reduce(ended, ShareEvent.Dismiss))
    }
}

class FormattingTest {
    @Test
    fun `durations`() {
        assertEquals("0 min", Formatting.duration(0))
        assertEquals("< 1 min", Formatting.duration(30_000))
        assertEquals("45 min", Formatting.duration(45 * 60_000L))
        assertEquals("2 h 05 min", Formatting.duration(125 * 60_000L))
    }

    @Test
    fun `pairing code input handling`() {
        assertEquals("ABCD2345", Formatting.normalizePairingCode("abcd-2345"))
        assertEquals("ABCD-2345", Formatting.displayPairingCode("ABCD2345"))
        assertTrue(Formatting.isValidPairingCode("ABCD2345"))
        assertFalse(Formatting.isValidPairingCode("ABCD0O1I"))
        assertFalse(Formatting.isValidPairingCode("ABC"))
    }

    @Test
    fun `credential validation`() {
        assertTrue(Formatting.isValidEmail("a@b.co"))
        assertFalse(Formatting.isValidEmail("nope"))
        assertFalse(Formatting.isValidPassword("short"))
        assertTrue(Formatting.isValidPassword("twelve chars!"))
    }
}
