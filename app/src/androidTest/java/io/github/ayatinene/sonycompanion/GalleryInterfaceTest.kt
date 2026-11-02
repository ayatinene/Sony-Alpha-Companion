package io.github.ayatinene.sonycompanion

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.ayatinene.sonycompanion.data.*
import io.github.ayatinene.sonycompanion.ui.CameraGallery
import io.github.ayatinene.sonycompanion.ui.GalleryActions
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real Compose interaction coverage for the unified camera gallery, driven by injected fixtures. */
@RunWith(AndroidJUnit4::class)
class GalleryInterfaceTest {
    @get:Rule val compose=createComposeRule()

    private fun jpegResource()=Resource("http://fixture/photo.jpg","image/jpeg",profile="http-get:*:image/jpeg:DLNA.ORG_PN=JPEG_ORG")
    private fun jpegFile(id:String)=CameraFile(id,id,resources=listOf(jpegResource()),date="2026-01-01")
    private fun rawFile(id:String)=CameraFile(id,id,raw=true,handle=1L,date="2026-01-01")
    private fun computer(files:List<CameraFile>,scan:ScanState,queue:List<QueueItem> = emptyList(),generation:Long=0)=
        AppState(mode=CameraMode.COMPUTER,connected=true,files=files,scan=scan,queue=queue,sessionGeneration=generation)
    private fun jpeg(files:List<CameraFile>)=AppState(mode=CameraMode.JPEG,connected=true,files=files,scan=ScanState(phase=ScanPhase.Idle))

    private fun render(state:AppState,actions:GalleryActions=GalleryActions()) {
        compose.setContent { MaterialTheme { CameraGallery(state,actions) } }
    }

    @Test fun scanningWithNoFilesShowsSearchingInsteadOfNoPhotos() {
        render(computer(emptyList(),ScanState(phase=ScanPhase.Scanning,scanned=5,found=0)))
        compose.onNodeWithText("正在查找照片").assertExists()
        compose.onNodeWithText("没有照片").assertDoesNotExist()
    }

    @Test fun finishedScanWithNoFilesShowsNoPhotos() {
        render(computer(emptyList(),ScanState(phase=ScanPhase.Done,scanned=9,found=0)))
        compose.onNodeWithText("没有照片").assertExists()
    }

    @Test fun jpegIdleEmptyShowsNoFilesWithoutHanging() {
        render(jpeg(emptyList()))
        compose.onNodeWithText("没有文件").assertExists()
        compose.onNodeWithText("正在查找照片").assertDoesNotExist()
    }

    @Test fun scanCountCopyUsesTheCameraTotalWhenPresent() {
        var state by mutableStateOf(computer(emptyList(),ScanState(phase=ScanPhase.Scanning,scanned=7,total=null,found=3)))
        compose.setContent { MaterialTheme { CameraGallery(state,GalleryActions()) } }
        compose.onNodeWithText("已检查 7 项，发现 3 个文件").assertExists()
        compose.runOnIdle { state=state.copy(scan=state.scan.copy(total=20)) }
        compose.onNodeWithText("已检查 7/20 项，发现 3 个文件").assertExists()
    }

    @Test fun pausedScanCannotBeResumedWhileADownloadIsRunning() {
        var state by mutableStateOf(computer(emptyList(),ScanState(phase=ScanPhase.Scanning,paused=true)).copy(transferring=true))
        compose.setContent { MaterialTheme { CameraGallery(state,GalleryActions()) } }
        compose.onNodeWithText("下载进行中，查找已暂停").assertExists()
        compose.onNodeWithText("继续查找").assertIsNotEnabled()
        compose.runOnIdle { state=state.copy(transferring=false) }
        compose.onNodeWithText("查找已暂停").assertExists()
        compose.onNodeWithText("继续查找").assertIsEnabled()
    }

    @Test fun jpegKeepsRootAndSizeHintWhileRawKeepsTheArwFilter() {
        var state by mutableStateOf(jpeg(listOf(jpegFile("a"))))
        compose.setContent { MaterialTheme { CameraGallery(state,GalleryActions()) } }
        compose.onNodeWithContentDescription("返回根目录").assertExists()
        compose.onNodeWithText("ARW").assertDoesNotExist()
        compose.onNodeWithText("仅下载相机实际提供的 JPEG 尺寸",substring=true).assertExists()
        compose.runOnIdle { state=computer(listOf(rawFile("r")),ScanState(phase=ScanPhase.Done)) }
        compose.onNodeWithContentDescription("返回根目录").assertDoesNotExist()
        compose.onNodeWithText("ARW").assertExists()
    }

    @Test fun longPressSelectAllAndCancelDriveTheDownloadCount() {
        render(computer(listOf(rawFile("a"),rawFile("b")),ScanState(phase=ScanPhase.Done)))
        compose.onNodeWithTag("gallery-item-a").performTouchInput { longClick() }
        compose.onNodeWithText("下载 1 个文件").assertExists()
        compose.onNodeWithText("全选 2").performClick()
        compose.onNodeWithText("下载 2 个文件").assertExists()
        compose.onNodeWithText("取消选择").performClick()
        compose.onNodeWithText("下载 2 个文件").assertDoesNotExist()
    }

    @Test fun filterHiddenSelectionIsNeverDownloaded() {
        val enqueued=mutableListOf<List<CameraFile>>()
        render(computer(listOf(rawFile("a"),jpegFile("b")),ScanState(phase=ScanPhase.Done)),GalleryActions(enqueue={files,_->enqueued+=files}))
        compose.onNodeWithText("全选 2").performClick()
        compose.onNodeWithText("ARW").performClick()
        compose.onNodeWithText("另有 1 项被筛选隐藏，不会下载。").assertExists()
        compose.onNodeWithText("下载 1 个文件").performClick()
        compose.runOnIdle {
            assertEquals(1,enqueued.size)
            assertEquals(listOf("a"),enqueued.single().map { it.id })
        }
    }

    @Test fun appendingFilesKeepsSelectionWithoutSelectingNewOnes() {
        var state by mutableStateOf(computer(listOf(rawFile("a")),ScanState(phase=ScanPhase.Scanning,scanned=1,found=1)))
        compose.setContent { MaterialTheme { CameraGallery(state,GalleryActions()) } }
        compose.onNodeWithTag("gallery-item-a").performTouchInput { longClick() }
        compose.runOnIdle { state=state.copy(files=listOf(rawFile("a"),rawFile("b"))) }
        compose.onNodeWithText("下载 1 个文件").assertExists()
        compose.onAllNodesWithContentDescription("已选择").assertCountEquals(1)
    }

    @Test fun appendingFilesKeepsTheScrollAnchor() {
        val initial=(1..24).map { rawFile("f"+it.toString().padStart(2,'0')) }
        var state by mutableStateOf(computer(initial,ScanState(phase=ScanPhase.Scanning,scanned=24,found=24)))
        compose.setContent { MaterialTheme { CameraGallery(state,GalleryActions()) } }
        compose.onNodeWithTag("gallery-grid").performScrollToNode(hasTestTag("gallery-item-f20"))
        compose.onNodeWithTag("gallery-item-f20").assertIsDisplayed()
        compose.runOnIdle { state=state.copy(files=initial+rawFile("f25")) }
        compose.onNodeWithTag("gallery-item-f20").assertIsDisplayed()
    }

    @Test fun generationChangeClearsSelection() {
        var state by mutableStateOf(computer(listOf(rawFile("a")),ScanState(phase=ScanPhase.Done)))
        compose.setContent { MaterialTheme { CameraGallery(state,GalleryActions()) } }
        compose.onNodeWithTag("gallery-item-a").performTouchInput { longClick() }
        compose.onNodeWithText("下载 1 个文件").assertExists()
        compose.runOnIdle { state=state.copy(sessionGeneration=1) }
        compose.onNodeWithText("下载 1 个文件").assertDoesNotExist()
        compose.onAllNodesWithContentDescription("已选择").assertCountEquals(0)
    }

    @Test fun jpegDirectoryChangeClearsSelection() {
        var state by mutableStateOf(jpeg(listOf(jpegFile("a"))))
        compose.setContent { MaterialTheme { CameraGallery(state,GalleryActions()) } }
        compose.onNodeWithTag("gallery-item-a").performTouchInput { longClick() }
        compose.onNodeWithText("下载 1 个文件").assertExists()
        compose.runOnIdle { state=state.copy(folder="date-2") }
        compose.onNodeWithText("下载 1 个文件").assertDoesNotExist()
    }

    @Test fun capturedScopeAndGenerationAreForwardedVerbatim() {
        val calls=mutableListOf<Triple<String,String?,Long>>()
        var state by mutableStateOf(computer(listOf(rawFile("a")),ScanState(phase=ScanPhase.Done),generation=7))
        compose.setContent { MaterialTheme { CameraGallery(state,GalleryActions(thumbnail={f,s,g-> calls+=Triple(f.id,s,g);null })) } }
        compose.waitUntil(5_000) { calls.isNotEmpty() }
        compose.runOnIdle { assertEquals(7L,calls.first().third) }
        compose.runOnIdle { state=state.copy(sessionGeneration=8) }
        compose.waitUntil(5_000) { calls.any { it.third==8L } }
    }

    @Test fun listDownloadCarriesTheComposedGeneration() {
        val calls=mutableListOf<Pair<List<CameraFile>,Long>>()
        var state by mutableStateOf(computer(listOf(rawFile("a")),ScanState(phase=ScanPhase.Done),generation=7))
        compose.setContent { MaterialTheme { CameraGallery(state,GalleryActions(enqueue={files,gen-> calls+=files to gen })) } }
        compose.onNodeWithTag("gallery-item-a").performTouchInput { longClick() }
        compose.onNodeWithText("下载 1 个文件").performClick()
        compose.runOnIdle {
            assertEquals(7L,calls.single().second)
            assertEquals(listOf("a"),calls.single().first.map { it.id })
        }
        // A later composition forwards its own generation; the captured callback never re-reads it.
        compose.runOnIdle { state=state.copy(sessionGeneration=8) }
        compose.onNodeWithTag("gallery-item-a").performTouchInput { longClick() }
        compose.onNodeWithText("下载 1 个文件").performClick()
        compose.runOnIdle { assertEquals(8L,calls.last().second) }
    }

    @Test fun previewDownloadCarriesTheGenerationCapturedWhenOpened() {
        val calls=mutableListOf<Pair<List<CameraFile>,Long>>()
        val state=computer(listOf(rawFile("a")),ScanState(phase=ScanPhase.Done),generation=5)
        compose.setContent { MaterialTheme { CameraGallery(state,GalleryActions(preview={_,_,_->null },enqueue={files,gen-> calls+=files to gen })) } }
        compose.onNodeWithTag("gallery-item-a").performClick()
        compose.onNodeWithText("下载").performClick()
        compose.runOnIdle {
            assertEquals(5L,calls.single().second)
            assertEquals(listOf("a"),calls.single().first.map { it.id })
        }
    }

    @Test fun removingThePreviewedFileClosesTheViewerInsteadOfCrashing() {
        var state by mutableStateOf(computer(listOf(rawFile("a"),rawFile("b")),ScanState(phase=ScanPhase.Done)))
        compose.setContent { MaterialTheme { CameraGallery(state,GalleryActions(preview={_,_,_->null})) } }
        compose.onNodeWithTag("gallery-item-a").performClick()
        compose.onNodeWithContentDescription("关闭预览").assertExists()
        compose.runOnIdle { state=state.copy(files=listOf(rawFile("b"))) }
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("关闭预览").fetchSemanticsNodes().isEmpty() }
    }

    @Test fun connectedButFailedScanSendsNoReadsAndStillOffersRetry() {
        var reads=0; var rescans=0
        render(computer(listOf(rawFile("a")),ScanState(phase=ScanPhase.Failed,error="读取中断")),
            GalleryActions(thumbnail={_,_,_-> reads++;null },rescan={rescans++}))
        compose.onNodeWithText("重试查找").assertExists().performClick()
        compose.onNodeWithTag("gallery-item-a").performTouchInput { longClick() }
        compose.onNodeWithText("下载 1 个文件").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0,reads); assertEquals(1,rescans) }
    }

    @Test fun queueContinueIsBlockedWhileRawScanningAndAllowedWhenDone() {
        val queue=listOf(QueueItem(rawFile("q"),state="失败"))
        var state by mutableStateOf(computer(listOf(rawFile("a")),ScanState(phase=ScanPhase.Scanning),queue))
        compose.setContent { MaterialTheme { CameraGallery(state,GalleryActions()) } }
        compose.onNodeWithText("继续未完成").assertIsNotEnabled()
        compose.runOnIdle { state=state.copy(scan=state.scan.copy(phase=ScanPhase.Done)) }
        compose.onNodeWithText("继续未完成").assertIsEnabled()
    }

    @Test fun jpegQueueContinueStaysAvailableWhileScanIsIdle() {
        render(jpeg(listOf(jpegFile("a"))).copy(queue=listOf(QueueItem(jpegFile("q"),state="失败"))))
        compose.onNodeWithText("继续未完成").assertIsEnabled()
    }

    @Test fun disconnectedFailureRunsNoNetworkReadAndOffersNoDownload() {
        var thumbnails=0
        val state=AppState(mode=CameraMode.COMPUTER,connected=false,files=listOf(rawFile("a")),
            scan=ScanState(phase=ScanPhase.Failed,error="相机连接已断开"))
        render(state,GalleryActions(thumbnail={_,_,_-> thumbnails++;null }))
        compose.onNodeWithText("相机图库").assertExists()
        compose.onNodeWithText("下载 1 个文件").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0,thumbnails) }
    }
}
