package io.github.ayatinene.sonycompanion

import io.github.ayatinene.sonycompanion.data.RemoteFunction
import io.github.ayatinene.sonycompanion.ui.isReturningToCapture
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gallery's "returning to capture" decision is pure and unit tested here: it must follow the switch
 * target, never the camera's current function, so a camera already on Contents Transfer is not mistaken
 * for an in-progress return.
 */
class RemoteGalleryReturnTest {
    @Test fun aSwitchTargetingRemoteShootingIsAReturn() {
        assertTrue(isReturningToCapture(remote = true, switching = true, switchTarget = RemoteFunction.REMOTE_SHOOTING))
    }

    @Test fun enteringWhileTheCameraAlreadyReportsContentsTransferIsNotAReturn() {
        // Current function would be Contents Transfer, but the switch target is the gallery.
        assertFalse(isReturningToCapture(remote = true, switching = true, switchTarget = RemoteFunction.CONTENTS_TRANSFER))
    }

    @Test fun anIdleOrNonRemoteGalleryIsNeverReturning() {
        assertFalse(isReturningToCapture(remote = true, switching = false, switchTarget = RemoteFunction.REMOTE_SHOOTING))
        assertFalse(isReturningToCapture(remote = false, switching = true, switchTarget = RemoteFunction.REMOTE_SHOOTING))
        assertFalse(isReturningToCapture(remote = true, switching = true, switchTarget = ""))
    }

    @Test fun theSwitchTargetComparisonIsCaseInsensitive() {
        assertTrue(isReturningToCapture(remote = true, switching = true, switchTarget = "remote shooting"))
    }
}
