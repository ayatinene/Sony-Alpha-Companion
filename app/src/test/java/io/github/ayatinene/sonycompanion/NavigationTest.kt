package io.github.ayatinene.sonycompanion

import io.github.ayatinene.sonycompanion.data.CameraMode
import io.github.ayatinene.sonycompanion.ui.ConnectionPhase
import io.github.ayatinene.sonycompanion.ui.Destination
import io.github.ayatinene.sonycompanion.ui.ShellNav
import io.github.ayatinene.sonycompanion.ui.cancelEntry
import io.github.ayatinene.sonycompanion.ui.destinationsFor
import io.github.ayatinene.sonycompanion.ui.observe
import io.github.ayatinene.sonycompanion.ui.requestConnection
import io.github.ayatinene.sonycompanion.ui.workspaceFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NavigationTest {
    @Test fun offlineBarHasNoWorkspaceTab() {
        assertEquals(listOf(Destination.Home, Destination.Local, Destination.Settings),
            destinationsFor(connected = false, mode = CameraMode.REMOTE))
    }

    @Test fun onlyALiveSessionOffersTheWorkspaceTabForItsMode() {
        assertEquals(listOf(Destination.Home, Destination.Capture, Destination.Local, Destination.Settings),
            destinationsFor(connected = true, mode = CameraMode.REMOTE))
        assertEquals(listOf(Destination.Home, Destination.Gallery, Destination.Local, Destination.Settings),
            destinationsFor(connected = true, mode = CameraMode.JPEG))
    }

    @Test fun firstConnectionEntersTheWorkspaceOnlyOnceItIsLive() {
        var nav = ShellNav().requestConnection(CameraMode.REMOTE)
        assertEquals(Destination.Home, nav.destination)
        nav = nav.observe(connected = false, connecting = true, mode = CameraMode.REMOTE)
        assertEquals(Destination.Home, nav.destination)
        assertEquals(Destination.Capture, nav.entryTarget)
        nav = nav.observe(connected = true, connecting = false, mode = CameraMode.REMOTE)
        assertEquals(Destination.Capture, nav.destination)
        assertNull(nav.entryTarget)
    }

    @Test fun reconnectDoesNotEnterWhileTheOldSessionIsStillReportedConnected() {
        var nav = ShellNav(destination = Destination.Home, phase = ConnectionPhase.Connected).requestConnection(CameraMode.REMOTE)
        nav = nav.observe(connected = true, connecting = true, mode = CameraMode.REMOTE)
        assertEquals("stale connected must not consume the entry", Destination.Home, nav.destination)
        assertEquals(Destination.Capture, nav.entryTarget)
        nav = nav.observe(connected = false, connecting = true, mode = CameraMode.REMOTE)
        assertEquals(Destination.Home, nav.destination)
        nav = nav.observe(connected = true, connecting = false, mode = CameraMode.REMOTE)
        assertEquals(Destination.Capture, nav.destination)
        assertNull(nav.entryTarget)
    }

    @Test fun failedAttemptDropsThePendingEntrySoALaterConnectCannotGrabThePage() {
        var nav = ShellNav().requestConnection(CameraMode.JPEG)
        nav = nav.observe(connected = false, connecting = true, mode = CameraMode.JPEG)
        nav = nav.observe(connected = false, connecting = false, mode = CameraMode.JPEG)
        assertNull(nav.entryTarget)
        assertEquals(Destination.Home, nav.destination)
        nav = ShellNav(destination = Destination.Local, phase = ConnectionPhase.Idle)
        nav = nav.observe(connected = true, connecting = false, mode = CameraMode.JPEG)
        assertEquals(Destination.Local, nav.destination)
    }

    @Test fun disconnectFromTheWorkspaceReturnsHome() {
        var nav = ShellNav(destination = Destination.Capture, phase = ConnectionPhase.Connected)
        nav = nav.observe(connected = false, connecting = false, mode = CameraMode.REMOTE)
        assertEquals(Destination.Home, nav.destination)
        nav = ShellNav(destination = Destination.Gallery, phase = ConnectionPhase.Connected)
        nav = nav.observe(connected = false, connecting = false, mode = CameraMode.JPEG)
        assertEquals(Destination.Home, nav.destination)
    }

    @Test fun switchingPageCancelsAPendingEntry() {
        val nav = ShellNav().requestConnection(CameraMode.JPEG).cancelEntry()
        assertNull(nav.entryTarget)
    }

    @Test fun reconnectInAnotherModeMovesToTheMatchingWorkspace() {
        val nav = ShellNav(destination = Destination.Capture, phase = ConnectionPhase.Connected)
            .observe(connected = true, connecting = false, mode = CameraMode.JPEG)
        assertEquals(Destination.Gallery, nav.destination)
    }

    @Test fun anUnstartedRequestNeverArmsAnEntry() {
        // The shell arms only when the controller reports the attempt started; a rejected call stays Idle.
        val nav = ShellNav(destination = Destination.Home, phase = ConnectionPhase.Idle)
        assertNull(nav.entryTarget)
        assertEquals(Destination.Home, nav.observe(connected = false, connecting = false, mode = CameraMode.JPEG).destination)
    }

    @Test fun aRemoteSessionInTheGallerySwapsTheWorkspaceToGallery() {
        assertEquals(Destination.Capture, workspaceFor(CameraMode.REMOTE, remoteGallery = false))
        assertEquals(Destination.Gallery, workspaceFor(CameraMode.REMOTE, remoteGallery = true))
        // The flag is meaningless for the content-transfer modes, which always use the gallery.
        assertEquals(Destination.Gallery, workspaceFor(CameraMode.JPEG, remoteGallery = true))
        assertEquals(listOf(Destination.Home, Destination.Gallery, Destination.Local, Destination.Settings),
            destinationsFor(connected = true, mode = CameraMode.REMOTE, remoteGallery = true))
        assertEquals(listOf(Destination.Home, Destination.Capture, Destination.Local, Destination.Settings),
            destinationsFor(connected = true, mode = CameraMode.REMOTE, remoteGallery = false))
    }

    @Test fun switchingIntoTheRemoteGalleryMovesTheWorkspaceAndReturningMovesItBack() {
        var nav = ShellNav(destination = Destination.Capture, phase = ConnectionPhase.Connected)
        nav = nav.observe(connected = true, connecting = false, mode = CameraMode.REMOTE, remoteGallery = true)
        assertEquals(Destination.Gallery, nav.destination)
        nav = nav.observe(connected = true, connecting = false, mode = CameraMode.REMOTE, remoteGallery = false)
        assertEquals(Destination.Capture, nav.destination)
    }

    @Test fun aRemoteGallerySwitchNeverYanksHomeLocalOrSettings() {
        listOf(Destination.Home, Destination.Local, Destination.Settings).forEach { page ->
            val nav = ShellNav(destination = page, phase = ConnectionPhase.Connected)
                .observe(connected = true, connecting = false, mode = CameraMode.REMOTE, remoteGallery = true)
            assertEquals("page $page must not be forced into the workspace", page, nav.destination)
        }
    }
}
