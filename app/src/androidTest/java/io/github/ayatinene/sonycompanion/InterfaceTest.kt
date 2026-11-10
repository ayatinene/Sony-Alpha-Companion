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
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ayatinene.sonycompanion.data.*
import io.github.ayatinene.sonycompanion.ui.*
import kotlinx.coroutines.runBlocking
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

    @Test fun landscapePlacesExposureLeftAndShutterRight() {
        compose.setContent { MaterialTheme(colorScheme=darkColorScheme()) {
            Box(Modifier.size(width=740.dp,height=340.dp)) { RemoteScreen(remote,controller,{}, {}) }
        } }
        val parameter=compose.onNodeWithText("ISO").fetchSemanticsNode().boundsInRoot.center
        val viewfinder=compose.onNodeWithTag("remote-viewfinder").fetchSemanticsNode().boundsInRoot.center
        val shutter=compose.onNodeWithContentDescription("拍照").assertIsEnabled().fetchSemanticsNode().boundsInRoot.center
        assertTrue(parameter.x<viewfinder.x);assertTrue(viewfinder.x<shutter.x)
        compose.onNodeWithContentDescription("开始录像").assertIsEnabled()
        assertBrightIcon(compose.onNodeWithContentDescription("返回工作台"))
        capture("remote-landscape",compose.onNodeWithTag("remote-screen"))
    }
    @Test fun portraitPlacesExposureAndShutterBelowTheViewfinder() {
        compose.setContent { MaterialTheme(colorScheme=darkColorScheme()) {
            Box(Modifier.size(width=360.dp,height=640.dp)) { RemoteScreen(remote,controller,{}, {}) }
        } }
        val parameter=compose.onNodeWithText("ISO").fetchSemanticsNode().boundsInRoot.center
        val viewfinder=compose.onNodeWithTag("remote-viewfinder").fetchSemanticsNode().boundsInRoot.center
        val shutter=compose.onNodeWithContentDescription("拍照").fetchSemanticsNode().boundsInRoot.center
        assertTrue(viewfinder.y<parameter.y);assertTrue(parameter.y<shutter.y)
        capture("remote-portrait",compose.onNodeWithTag("remote-screen"))
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
