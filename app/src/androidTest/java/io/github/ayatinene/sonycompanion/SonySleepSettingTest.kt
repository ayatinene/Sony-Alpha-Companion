package io.github.ayatinene.sonycompanion

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.ayatinene.sonycompanion.ui.SONY_SLEEP_DESCRIPTION
import io.github.ayatinene.sonycompanion.ui.SONY_SLEEP_TITLE
import io.github.ayatinene.sonycompanion.ui.SettingRow
import io.github.ayatinene.sonycompanion.ui.SonySleepSetting
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Compose coverage for the RAW-section camera-sleep toggle, driven by hoisted state. */
@RunWith(AndroidJUnit4::class)
class SonySleepSettingTest {
    @get:Rule val compose=createComposeRule()

    private val tag="sony-sleep-on-disconnect"
    private val switchTag="$tag-switch"

    @Test fun sleepSettingDefaultsToOff() {
        var checked by mutableStateOf(false)
        compose.setContent { MaterialTheme { SonySleepSetting(checked) { checked=it } } }
        compose.onNodeWithTag(tag).assertIsOff()
        // The row owns the toggle semantics; the switch is a decorative, non-interactive reflection of
        // the same hoisted state (onCheckedChange=null), so it exposes no ToggleableState of its own.
        compose.onNodeWithTag(switchTag,useUnmergedTree=true).assertIsDisplayed()
        compose.runOnIdle { assertFalse(checked) }
    }

    @Test fun sleepSettingTogglesOnThenOffThroughTheRowSurface() {
        val changes=mutableListOf<Boolean>()
        var checked by mutableStateOf(false)
        compose.setContent { MaterialTheme { SonySleepSetting(checked) { checked=it;changes+=it } } }
        compose.onNodeWithTag(tag).performClick()
        compose.onNodeWithTag(tag).assertIsOn()
        compose.runOnIdle { assertTrue(checked) }
        compose.onNodeWithTag(tag).performClick()
        compose.onNodeWithTag(tag).assertIsOff()
        compose.runOnIdle { assertFalse(checked) }
        compose.runOnIdle { assertEquals(listOf(true,false),changes) }
    }

    @Test fun sleepSettingRecomposesWhenStateChangesFromTheOutside() {
        var checked by mutableStateOf(false)
        compose.setContent { MaterialTheme { SonySleepSetting(checked) { checked=it } } }
        compose.onNodeWithTag(tag).assertIsOff()
        compose.runOnIdle { checked=true }
        compose.onNodeWithTag(tag).assertIsOn()
        // Same as above: the switch mirrors the row's state but has no independent toggle semantics.
        compose.onNodeWithTag(switchTag,useUnmergedTree=true).assertIsDisplayed()
        compose.runOnIdle { checked=false }
        compose.onNodeWithTag(tag).assertIsOff()
    }

    @Test fun aLongTitleWrapsAndKeepsTheSwitchVisibleAtTheMinimumTouchHeight() {
        val longTitle=SONY_SLEEP_TITLE.repeat(4)
        compose.setContent { MaterialTheme {
            Box(Modifier.width(220.dp).testTag("row-host")) {
                SettingRow(longTitle,SONY_SLEEP_DESCRIPTION,false,{},"long-row")
            }
        } }
        val density=compose.density.density
        val row=compose.onNodeWithTag("long-row").fetchSemanticsNode().boundsInRoot
        val switch=compose.onNodeWithTag("long-row-switch",useUnmergedTree=true).fetchSemanticsNode().boundsInRoot
        assertTrue("row keeps the 48dp minimum touch height",row.height>=48*density-1f)
        assertTrue("switch stays inside the row",switch.left>=row.left && switch.right<=row.right+1f)
        assertTrue("switch is not squeezed away",switch.width>=40*density-1f)
        compose.onNodeWithTag("long-row-switch",useUnmergedTree=true).assertIsDisplayed()
    }
}
