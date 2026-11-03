package io.github.ayatinene.sonycompanion

import android.content.pm.ActivityInfo
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.ayatinene.sonycompanion.ui.OrientationHost
import io.github.ayatinene.sonycompanion.ui.RemoteSensorOrientation
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real Compose coverage for the capture-page sensor rotation effect: enter/exit and recreation. */
@RunWith(AndroidJUnit4::class)
class RemoteOrientationEffectTest {
    @get:Rule val compose=createComposeRule()

    private class FakeHost(initial: Int = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) : OrientationHost {
        override var requestedOrientation: Int = initial
        override var isChangingConfigurations: Boolean = false
    }

    @Test fun enteringFollowsTheSensorAndLeavingRestoresTheOriginal() {
        val host=FakeHost(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
        var enabled by mutableStateOf(true)
        compose.setContent { RemoteSensorOrientation(enabled,host) }
        compose.runOnIdle { assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR,host.requestedOrientation) }
        compose.runOnIdle { enabled=false }
        compose.runOnIdle { assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,host.requestedOrientation) }
    }

    @Test fun disabledNeverOverridesTheSystemOrientation() {
        val host=FakeHost(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
        compose.setContent { RemoteSensorOrientation(false,host) }
        compose.runOnIdle { assertEquals(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,host.requestedOrientation) }
    }

    @Test fun configurationRecreationKeepsTheSensorAndNeverLosesTheOriginal() {
        val host=FakeHost(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED)
        val tester=StateRestorationTester(compose)
        var enabled by mutableStateOf(true)
        tester.setContent { RemoteSensorOrientation(enabled,host) }
        compose.runOnIdle { assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR,host.requestedOrientation) }
        // A real configuration change disposes the old composition while the Activity is changing
        // configurations; the saveable original must survive and the sensor must not be restored away.
        host.isChangingConfigurations=true
        tester.emulateSavedInstanceStateRestore()
        host.isChangingConfigurations=false
        compose.runOnIdle { assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR,host.requestedOrientation) }
        compose.runOnIdle { enabled=false }
        compose.runOnIdle { assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,host.requestedOrientation) }
    }
}
