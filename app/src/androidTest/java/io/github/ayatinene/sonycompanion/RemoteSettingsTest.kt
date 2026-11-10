package io.github.ayatinene.sonycompanion

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ayatinene.sonycompanion.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real Compose coverage for the remote-shooting settings rows. */
@RunWith(AndroidJUnit4::class)
class RemoteSettingsTest {
    @get:Rule val compose=createComposeRule()

    private val sensorTag="remote-sensor-rotation"
    private val geotagTag="remote-geotagging"

    @Test fun remoteCaptureSettingsDefaultToSensorOnAndGeotaggingOff() {
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as CompanionApplication
        kotlinx.coroutines.runBlocking { app.controller.disconnect(false) }
        app.controller.prefs.edit().remove(REMOTE_SENSOR_ROTATION).remove(RemoteGeotaggingPolicy.KEY).apply()
        val model=CompanionViewModel(app)
        compose.setContent { CompanionApp(model,{}, {}) }
        compose.onNodeWithText("设置").performClick()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(REMOTE_SENSOR_ROTATION_TITLE))
        compose.onNodeWithTag(sensorTag).assertIsOn()
        compose.onNodeWithTag(geotagTag).assertIsOff()
    }

    @Test fun geotaggingOnRequestsEnableAndOffDisables() {
        var checked by mutableStateOf(false)
        var enableRequests=0; var disabled=false
        compose.setContent { MaterialTheme {
            RemoteGeotaggingSetting(checked,{ enableRequests++ },{ checked=false;disabled=true })
        } }
        compose.onNodeWithTag(geotagTag).performClick()
        compose.runOnIdle { assertEquals(1,enableRequests);assertFalse("the row stays off until granted",checked) }
        compose.runOnIdle { checked=true }
        compose.onNodeWithTag(geotagTag).performClick()
        compose.runOnIdle { assertFalse(checked);assertTrue(disabled) }
    }

    @Test fun sensorRotationRowTogglesThroughItsHoistedState() {
        val changes=mutableListOf<Boolean>()
        var checked by mutableStateOf(true)
        compose.setContent { MaterialTheme { RemoteSensorRotationSetting(checked){ checked=it;changes+=it } } }
        compose.onNodeWithTag(sensorTag).assertIsOn()
        compose.onNodeWithTag(sensorTag).performClick()
        compose.onNodeWithTag(sensorTag).assertIsOff()
        compose.runOnIdle { assertEquals(listOf(false),changes) }
    }
}
