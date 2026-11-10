package io.github.ayatinene.sonycompanion

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.remember
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ayatinene.sonycompanion.data.*
import io.github.ayatinene.sonycompanion.ui.RemoteScreen
import java.net.URI
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real Compose coverage for the capture page's same-connection camera gallery entry. */
@RunWith(AndroidJUnit4::class)
class RemoteGalleryEntryTest {
    @get:Rule val compose=createComposeRule()
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val controller get()=(context.applicationContext as CompanionApplication).controller

    private fun capture(canBrowseGallery:Boolean=true,transferring:Boolean=false)=AppState(
        mode=CameraMode.REMOTE,connected=true,transferring=transferring,
        device=Device("ILCE-7RM2","ILCE-7RM2",URI("http://fixture")),
        remote=RemoteState(apis=setOf("actTakePicture"),status="IDLE",canBrowseGallery=canBrowseGallery))

    private fun render(state:AppState) {
        compose.setContent { MaterialTheme(colorScheme=darkColorScheme()) {
            RemoteScreen(state,controller,remember { SnackbarHostState() },{},{}) } }
    }

    @Test fun capturePageOffersTheGalleryEntryWithAPauseExplanation() {
        render(capture())
        compose.onNodeWithContentDescription("相机图库").assertExists().performClick()
        compose.onNodeWithText("进入相机图库？").assertExists()
        compose.onNodeWithText("进入相机图库时将暂时停止实时取景和遥控拍摄，返回拍摄后可继续使用。").assertExists()
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithText("进入相机图库？").assertDoesNotExist()
    }

    @Test fun anUnsupportedCameraAppExplainsInsteadOfOpeningTheGallery() {
        render(capture(canBrowseGallery=false))
        compose.onNodeWithContentDescription("相机图库").performClick()
        compose.onNodeWithText("进入相机图库？").assertDoesNotExist()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("当前机内应用不支持相机图库，可使用 JPEG 传输或原文件连接。").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test fun theGalleryEntryIsBlockedWhileDownloading() {
        render(capture(transferring=true))
        compose.onNodeWithContentDescription("相机图库").assertIsNotEnabled()
    }
}
