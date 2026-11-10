package io.github.ayatinene.sonycompanion

import android.graphics.Bitmap
import android.content.ContentValues
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ayatinene.sonycompanion.data.*
import io.github.ayatinene.sonycompanion.ui.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.net.URI

@RunWith(AndroidJUnit4::class)
class InterfaceTest {
    @get:Rule val compose=createComposeRule()
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val controller get()=(context.applicationContext as CompanionApplication).controller
    private val remote=AppState(mode=CameraMode.REMOTE,connected=true,device=Device("ILCE-7RM2","ILCE-7RM2",URI("http://fixture")),remote=RemoteState(
        apis=setOf("actTakePicture","actHalfPressShutter","cancelHalfPressShutter","startMovieRec"),
        settings=listOf(Setting("IsoSpeedRate","ISO","100",emptyList(),false),
            Setting("ShutterSpeed","快门","1/125",emptyList(),false),Setting("FNumber","光圈","5.6",emptyList(),false),
            Setting("ExposureCompensation","曝光补偿","0",emptyList(),false,3)),status="IDLE"))

    private fun actions(onBack:()->Unit={},onDisconnect:()->Unit={})=RemoteActions(
        onBack=onBack,onDisconnect=onDisconnect,onToggleGrid={},onTools={},onFocus={},onCapture={},onMovie={},onSetSetting={_,_->})

    private fun landscapeImage()=Bitmap.createBitmap(600,400,Bitmap.Config.ARGB_8888).apply { eraseColor(0xff3d566e.toInt()) }
    private fun bounds(tag:String)=compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    @Test fun landscapeMaximizesTheImageAndFloatsControlsAtTheEdges() {
        compose.setContent { MaterialTheme(colorScheme=darkColorScheme()) {
            Box(Modifier.size(740.dp,340.dp)) { RemoteWorkspace(remote,landscapeImage(),false,WorkspaceInsets(),actions(),remember { SnackbarHostState() }) }
        } }
        val screen=bounds("remote-screen"); val viewfinder=bounds("remote-viewfinder")
        assertEquals("3:2 keeps its aspect",1.5f,viewfinder.width/viewfinder.height,0.02f)
        assertEquals("height limited",screen.height,viewfinder.height,3f)
        assertEquals("image stays centred",screen.center.x,viewfinder.center.x,3f)
        val parameter=bounds("remote-params"); val shutter=bounds("remote-controls")
        assertTrue("exposure stays at the left edge",parameter.right<=viewfinder.center.x)
        assertTrue("controls stay at the right edge",shutter.left>=viewfinder.center.x)
        capture("remote-landscape",compose.onNodeWithTag("remote-screen"))
    }

    @Test fun landscapeHeaderStaysAtTheSafeTopHidesAndReturnsWithoutMovingCaptureControls() {
        compose.mainClock.autoAdvance=false
        val insets=WorkspaceInsets(top=20.dp,left=8.dp,right=8.dp)
        compose.setContent { MaterialTheme(colorScheme=darkColorScheme()) {
            Box(Modifier.size(740.dp,340.dp)) { RemoteWorkspace(remote,landscapeImage(),false,insets,actions(),remember { SnackbarHostState() }) }
        } }
        compose.mainClock.advanceTimeByFrame()
        val screen=bounds("remote-screen"); val header=bounds("remote-header")
        val shutter=bounds("remote-controls")
        val safeTop=with(compose.density) { 24.dp.toPx() }
        assertEquals("header is pinned to the safe top",screen.top+safeTop,header.top,2f)
        assertTrue("header must not expand to screen height",header.height<=with(compose.density) { 56.dp.toPx() })
        compose.mainClock.advanceTimeBy(4_500)
        compose.onNodeWithTag("remote-header").assertDoesNotExist()
        assertEquals(shutter,bounds("remote-controls"))
        compose.onNodeWithTag("remote-viewfinder").performTouchInput { click(center) }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("remote-header").assertExists()
        assertEquals(header.top,bounds("remote-header").top,1f)
        compose.mainClock.advanceTimeBy(3_000)
        compose.onNodeWithTag("remote-header").assertExists()
        compose.onNodeWithTag("remote-viewfinder").performTouchInput { click(center) }
        compose.mainClock.advanceTimeBy(2_000)
        compose.onNodeWithTag("remote-header").assertExists()
        compose.mainClock.advanceTimeBy(2_500)
        compose.onNodeWithTag("remote-header").assertDoesNotExist()
    }

    @Test fun holdingAFingerDownKeepsTheLandscapeHeaderVisibleUntilItIsLifted() {
        compose.mainClock.autoAdvance=false
        compose.setContent { MaterialTheme(colorScheme=darkColorScheme()) {
            Box(Modifier.size(740.dp,340.dp)) { RemoteWorkspace(remote,landscapeImage(),false,WorkspaceInsets(),actions(),remember { SnackbarHostState() }) }
        } }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("remote-viewfinder").performTouchInput { down(center) }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(6_000)
        compose.onNodeWithTag("remote-header").assertExists()
        compose.onNodeWithTag("remote-viewfinder").performTouchInput { up() }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(4_500)
        compose.onNodeWithTag("remote-header").assertDoesNotExist()
    }

    @Test fun resumingAfterLosingFocusWhileHoldingReArmsTheLandscapeHeaderAutoHide() {
        compose.mainClock.autoAdvance=false
        val owner=TestLifecycleOwner()
        compose.runOnUiThread { owner.registry.currentState=Lifecycle.State.RESUMED }
        compose.setContent { MaterialTheme(colorScheme=darkColorScheme()) {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                Box(Modifier.size(740.dp,340.dp)) { RemoteWorkspace(remote,landscapeImage(),false,WorkspaceInsets(),actions(),remember { SnackbarHostState() }) }
            }
        } }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("remote-header").assertExists()
        // Focus is lost while a finger is down, so the cancelled pointer stream never sends an up event.
        compose.onNodeWithTag("remote-viewfinder").performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithTag("remote-header").assertExists()
        compose.runOnUiThread { owner.registry.currentState=Lifecycle.State.CREATED }
        compose.mainClock.advanceTimeBy(1_000)
        compose.runOnUiThread { owner.registry.currentState=Lifecycle.State.RESUMED }
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithTag("remote-header").assertExists()
        compose.mainClock.advanceTimeBy(4_500)
        compose.onNodeWithTag("remote-header").assertDoesNotExist()
        compose.onNodeWithTag("remote-viewfinder").performTouchInput { up() }
    }

    @Test fun touchExplorationPinsTheLandscapeHeaderAndRestartsTheTimerOnceTurnedOff() {
        compose.mainClock.autoAdvance=false
        var exploring by mutableStateOf(true)
        compose.setContent { MaterialTheme(colorScheme=darkColorScheme()) {
            CompositionLocalProvider(LocalTouchExplorationEnabled provides exploring) {
                Box(Modifier.size(740.dp,340.dp)) { RemoteWorkspace(remote,landscapeImage(),false,WorkspaceInsets(),actions(),remember { SnackbarHostState() }) }
            }
        } }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("remote-header").assertExists()
        // Touch exploration sends no pressed pointer event, so the header must never auto-hide.
        compose.mainClock.advanceTimeBy(20_000)
        compose.onNodeWithTag("remote-header").assertExists()
        compose.runOnUiThread { exploring=false }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(3_000)
        compose.onNodeWithTag("remote-header").assertExists()
        compose.mainClock.advanceTimeBy(2_500)
        compose.onNodeWithTag("remote-header").assertDoesNotExist()
    }

    /** Drives the workspace lifecycle directly so focus loss and resume can be reproduced. */
    private class TestLifecycleOwner : LifecycleOwner {
        val registry=LifecycleRegistry(this)
        override val lifecycle get()=registry
    }

    @Test fun portraitUsesTheFullWidthAndStacksThePanelBelowTheImage() {
        compose.setContent { MaterialTheme(colorScheme=darkColorScheme()) {
            Box(Modifier.size(360.dp,640.dp)) { RemoteWorkspace(remote,landscapeImage(),false,WorkspaceInsets(),actions(),remember { SnackbarHostState() }) }
        } }
        val screen=bounds("remote-screen"); val viewfinder=bounds("remote-viewfinder"); val region=bounds("remote-region"); val panel=bounds("remote-panel")
        assertEquals("viewfinder uses the full width",screen.width,viewfinder.width,3f)
        assertEquals("3:2 keeps its aspect",1.5f,viewfinder.width/viewfinder.height,0.02f)
        assertTrue("image fits the frame region",viewfinder.bottom<=region.bottom+1f)
        assertTrue("panel never covers the image",viewfinder.bottom<=panel.top+1f)
        val parameter=bounds("remote-panel")
        val shutter=compose.onNodeWithContentDescription("拍照").fetchSemanticsNode().boundsInRoot
        assertTrue(viewfinder.center.y<shutter.center.y)
        assertFalse("controls must not cover the frame centre",parameter.contains(viewfinder.center))
        assertFalse(shutter.contains(viewfinder.center))
        capture("remote-portrait",compose.onNodeWithTag("remote-screen"))
    }

    @Test fun gridFollowsTheRenderedImageRect() {
        compose.setContent { MaterialTheme(colorScheme=darkColorScheme()) {
            Box(Modifier.size(740.dp,340.dp)) { RemoteWorkspace(remote,landscapeImage(),true,WorkspaceInsets(),actions(),remember { SnackbarHostState() }) }
        } }
        val viewfinder=bounds("remote-viewfinder"); val grid=bounds("remote-grid")
        assertEquals(viewfinder.left,grid.left,0.5f)
        assertEquals(viewfinder.top,grid.top,0.5f)
        assertEquals(viewfinder.width,grid.width,0.5f)
        assertEquals(viewfinder.height,grid.height,0.5f)
    }

    @Test fun smallLandscapeKeepsColumnsBelowTheHeaderAndScrollable() {
        val insets=WorkspaceInsets(top=20.dp,bottom=12.dp,left=8.dp,right=8.dp)
        compose.setContent { MaterialTheme(colorScheme=darkColorScheme()) {
            Box(Modifier.size(320.dp,240.dp)) { RemoteWorkspace(remote,landscapeImage(),false,insets,actions(),remember { SnackbarHostState() }) }
        } }
        val screen=bounds("remote-screen"); val header=bounds("remote-header")
        val parameter=bounds("remote-params"); val controls=bounds("remote-controls"); val viewfinder=bounds("remote-viewfinder")
        val density=screen.width/320f
        assertTrue("params clear the header",parameter.top>=header.bottom-1f)
        assertTrue("controls clear the header",controls.top>=header.bottom-1f)
        assertTrue("params stay inside the bottom inset",parameter.bottom<=screen.bottom-12*density+1f)
        assertTrue("controls stay inside the bottom inset",controls.bottom<=screen.bottom-12*density+1f)
        assertTrue("params stay off the centre",parameter.right<=viewfinder.center.x+1f)
        assertTrue("controls stay off the centre",controls.left>=viewfinder.center.x-1f)
        assertTrue("columns do not overlap",parameter.right<=controls.left+1f)
        capture("remote-small-landscape",compose.onNodeWithTag("remote-screen"))
    }

    @Test fun landscapeInsetsPushControlsAwayFromTheEdges() {
        val insets=WorkspaceInsets(top=0.dp,bottom=0.dp,left=40.dp,right=40.dp)
        compose.setContent { MaterialTheme(colorScheme=darkColorScheme()) {
            Box(Modifier.size(740.dp,340.dp)) { RemoteWorkspace(remote,landscapeImage(),false,insets,actions(),remember { SnackbarHostState() }) }
        } }
        val screen=bounds("remote-screen"); val parameter=bounds("remote-params"); val controls=bounds("remote-controls")
        val density=screen.width/740f
        assertTrue(parameter.left>=screen.left+40*density-1f)
        assertTrue(controls.right<=screen.right-40*density+1f)
    }

    @Test fun shortScreenPanelLeavesTheWholeImageVisible() {
        compose.setContent { MaterialTheme(colorScheme=darkColorScheme()) {
            Box(Modifier.size(360.dp,480.dp)) { RemoteWorkspace(remote,landscapeImage(),false,WorkspaceInsets(),actions(),remember { SnackbarHostState() }) }
        } }
        val region=bounds("remote-region"); val viewfinder=bounds("remote-viewfinder"); val panel=bounds("remote-panel")
        assertEquals("aspect preserved",1.5f,viewfinder.width/viewfinder.height,0.02f)
        assertTrue("whole image inside the region",viewfinder.bottom<=region.bottom+1f)
        assertTrue("panel below the image",viewfinder.bottom<=panel.top+1f)
    }

    @Test fun veryShortScreenShrinksTheImageInsteadOfCropping() {
        compose.setContent { MaterialTheme(colorScheme=darkColorScheme()) {
            Box(Modifier.size(360.dp,420.dp)) { RemoteWorkspace(remote,landscapeImage(),false,WorkspaceInsets(),actions(),remember { SnackbarHostState() }) }
        } }
        val region=bounds("remote-region"); val viewfinder=bounds("remote-viewfinder"); val panel=bounds("remote-panel")
        assertEquals("aspect preserved",1.5f,viewfinder.width/viewfinder.height,0.02f)
        assertTrue("image uses the remaining height",viewfinder.height<=region.height+1f)
        assertTrue("panel below the image",viewfinder.bottom<=panel.top+1f)
    }

    @Test fun connectingWorkspaceShowsThePlaceholderAndDisablesTheShutter() {
        val connecting=remote.copy(connected=false,connecting=true)
        compose.setContent { MaterialTheme(colorScheme=darkColorScheme()) {
            Box(Modifier.size(360.dp,640.dp)) { RemoteWorkspace(connecting,null,false,WorkspaceInsets(),actions(),remember { SnackbarHostState() }) }
        } }
        compose.onNodeWithTag("remote-placeholder").assertExists()
        compose.onNodeWithContentDescription("拍照").assertIsNotEnabled()
    }

    @Test fun connectedWorkspaceShowsTheTranslatedCameraStatus() {
        compose.setContent { MaterialTheme(colorScheme=darkColorScheme()) {
            Box(Modifier.size(360.dp,640.dp)) { RemoteWorkspace(remote,landscapeImage(),false,WorkspaceInsets(),actions(),remember { SnackbarHostState() }) }
        } }
        compose.onNodeWithText("待机").assertExists()
    }

    @Test fun workspaceBackReturnsWithoutDisconnectingTheSession() {
        var back=false; var disconnected=false
        compose.setContent { MaterialTheme(colorScheme=darkColorScheme()) {
            Box(Modifier.size(360.dp,640.dp)) { RemoteWorkspace(remote,landscapeImage(),false,WorkspaceInsets(),actions(onBack={back=true},onDisconnect={disconnected=true}),remember { SnackbarHostState() }) }
        } }
        compose.onNodeWithContentDescription("返回工作台").performClick()
        compose.runOnIdle { assertTrue(back);assertFalse("returning must not tear down the session",disconnected) }
    }

    @Test fun immersiveWorkspaceShowsSnackbarErrors() {
        val host=SnackbarHostState()
        compose.setContent { MaterialTheme(colorScheme=darkColorScheme()) {
            Box(Modifier.size(360.dp,640.dp)) {
                RemoteWorkspace(remote,landscapeImage(),false,WorkspaceInsets(),actions(),host)
                LaunchedEffect(Unit) { host.showSnackbar("连接失败，请重试") }
            }
        } }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("连接失败，请重试").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("连接失败，请重试").assertExists()
    }

    @Test fun disconnectedShellHasNoCameraTabAndShowsNotConnected() {
        runBlocking { controller.disconnect(false) }
        val app=context.applicationContext as CompanionApplication
        val model=CompanionViewModel(app)
        compose.setContent { CompanionApp(model,{}, {}) }
        compose.onNodeWithText("首页").assertExists()
        compose.onNodeWithText("本地").assertExists()
        compose.onNodeWithText("设置").assertExists()
        compose.onNodeWithText("相机").assertDoesNotExist()
        compose.onNodeWithText("未连接").assertExists()
    }

    @Test fun settingsPageKeepsTheAboutAndLicencesSection() {
        runBlocking { controller.disconnect(false) }
        val app=context.applicationContext as CompanionApplication
        val model=CompanionViewModel(app)
        compose.setContent { CompanionApp(model,{}, {}) }
        compose.onNodeWithText("设置").performClick()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("关于与许可"))
        compose.onNodeWithText("关于与许可").assertExists()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("GPL-3.0", substring=true))
        compose.onNodeWithText("GPL-3.0", substring=true).assertExists()
    }

    @Test fun homeStatusCardFillsItsAvailableWidthInEveryStateAndCopyLength() {
        var state by mutableStateOf(AppState(message="请连接相机"))
        compose.setContent { MaterialTheme(colorScheme=darkColorScheme()) {
            Box(Modifier.width(320.dp).height(240.dp).testTag("home-status-host")) { HomeStatusCard(state) }
        } }
        val host=bounds("home-status-host")
        assertEquals(host.width,bounds("home-status-card").width,1f)
        compose.runOnIdle { state=AppState(connecting=true,message="查找相机…") }
        assertEquals(host.width,bounds("home-status-card").width,1f)
        compose.runOnIdle { state=AppState(connected=true,device=Device("ILCE-7RM2","ILCE-7RM2",URI("http://fixture")),message="已连接 · 遥控拍摄") }
        assertEquals(host.width,bounds("home-status-card").width,1f)
        compose.runOnIdle { state=AppState(message="一条很长的状态说明，用来确认状态卡不会因为文案长度而改变宽度。".repeat(8)) }
        assertEquals(host.width,bounds("home-status-card").width,1f)
    }

    @Test fun homeStatusCardMatchesTheModeCardWidth() {
        runBlocking { controller.disconnect(false) }
        val model=CompanionViewModel(context.applicationContext as CompanionApplication)
        compose.setContent { CompanionApp(model,{}, {}) }
        val status=bounds("home-status-card")
        val mode=compose.onAllNodesWithTag("home-mode-card").onFirst().fetchSemanticsNode().boundsInRoot
        assertEquals("status card spans the same list width as a mode card",mode.width,status.width,1f)
        assertEquals(mode.left,status.left,1f)
    }

    @Test fun viewerSupportsDoubleTapZoomResetAndNextImage() {
        val bitmap=Bitmap.createBitmap(600,400,Bitmap.Config.ARGB_8888).apply { eraseColor(0xff5b7794.toInt()) }
        var dismissed=false
        compose.setContent { PhotoViewer(listOf(ViewerImage("a","A.JPG"),ViewerImage("b","B.JPG")),"a",
            onDismiss={dismissed=true},load={bitmap}) { } }
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("A.JPG").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("A.JPG").performTouchInput { doubleClick() }
        compose.onNodeWithText("还原缩放").assertExists().performClick()
        compose.onNodeWithContentDescription("下一张").performClick()
        compose.onNodeWithText("2 / 2").assertExists()
        compose.onNodeWithContentDescription("B.JPG").assertExists()
        assertBrightIcon(compose.onNodeWithContentDescription("关闭预览"))
        capture("photo-viewer",compose.onNode(isDialog()))
        compose.onNodeWithContentDescription("关闭预览").performClick()
        compose.runOnIdle { assertTrue(dismissed) }
    }

    @Test fun fileDeletionRemovesMediaAndImportRecord() {
        val bytes=ByteArrayOutputStream().also { out -> Bitmap.createBitmap(40,30,Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG,90,out) }.toByteArray()
        val photo=runBlocking { controller.photos.import("UI_${System.nanoTime()}.JPG",false,bytes.size.toLong(),"ui-test","界面测试") { it.write(bytes) } }
        try {
            compose.setContent { MaterialTheme { val state by controller.state.collectAsStateWithLifecycle();LocalLibraryScreen(state,controller) } }
            compose.waitUntil(10_000) { controller.state.value.local.any { it.uri==photo.uri } }
            compose.onNodeWithText(photo.name).assertExists()
            capture("local-gallery",compose.onRoot())
            compose.onNodeWithContentDescription("文件操作").performClick()
            compose.onNodeWithText("删除照片文件").performClick()
            compose.onNodeWithText("删除文件").performClick()
            compose.waitUntil(10_000) { controller.state.value.local.none { it.uri==photo.uri } }
            assertFalse(controller.photos.exists(photo))
            assertFalse(controller.photos.list().any { it.uri==photo.uri })
        } finally { runCatching { controller.photos.deleteFile(photo) } }
    }

    private fun assertBrightIcon(node: SemanticsNodeInteraction) {
        val bitmap=node.captureToImage().asAndroidBitmap()
        var bright=0
        for(y in 0 until bitmap.height) for(x in 0 until bitmap.width) {
            val pixel=bitmap.getPixel(x,y)
            if(android.graphics.Color.red(pixel)>160 && android.graphics.Color.green(pixel)>160 && android.graphics.Color.blue(pixel)>160) bright++
        }
        assertTrue("Dark camera surfaces must retain visible foreground icons",bright>10)
    }
    private fun capture(name: String, node: SemanticsNodeInteraction) {
        val resolver=context.contentResolver
        val uri=resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME,"$name.png")
            put(MediaStore.Images.Media.MIME_TYPE,"image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH,Environment.DIRECTORY_PICTURES+"/AlphaCompanionUiCaptures")
            put(MediaStore.Images.Media.IS_PENDING,1)
        })!!
        resolver.openOutputStream(uri)!!.use { node.captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it) }
        resolver.update(uri,ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING,0) },null,null)
    }
}

