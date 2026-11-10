package io.github.ayatinene.sonycompanion

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.ayatinene.sonycompanion.data.*
import io.github.ayatinene.sonycompanion.ui.CameraGallery
import io.github.ayatinene.sonycompanion.ui.GalleryActions
import io.github.ayatinene.sonycompanion.ui.galleryTimeText
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

    @Test fun sortMenuListsSixOptionsInOrderWithTheCurrentOneSelected() {
        render(computer(listOf(rawFile("a")),ScanState(phase=ScanPhase.Done)))
        compose.onNodeWithTag("gallery-sort-menu").performClick()
        listOf("拍摄时间：最新优先","拍摄时间：最早优先","文件名：升序","文件名：降序","文件大小：从小到大","文件大小：从大到小").forEach {
            compose.onNodeWithText(it).assertExists()
        }
        compose.onNodeWithTag("gallery-sort-Newest").assertIsSelected()
        val order=listOf("gallery-sort-Newest","gallery-sort-Oldest","gallery-sort-NameAscending","gallery-sort-NameDescending","gallery-sort-SizeAsc","gallery-sort-SizeDesc")
            .map { compose.onNodeWithTag(it).fetchSemanticsNode().boundsInRoot.top }
        assertTrue(order.zipWithNext().all { (top,bottom) -> top<bottom })
    }

    @Test fun choosingASizeSortUpdatesTheButtonDescription() {
        render(computer(listOf(rawFile("a")),ScanState(phase=ScanPhase.Done)))
        compose.onNodeWithTag("gallery-sort-menu").performClick()
        compose.onNodeWithTag("gallery-sort-SizeDesc").performClick()
        compose.onNodeWithContentDescription("排序：文件大小：从大到小").assertExists()
    }

    @Test fun choosingASortUpdatesTheButtonDescriptionAndSelection() {
        render(computer(listOf(rawFile("a")),ScanState(phase=ScanPhase.Done)))
        compose.onNodeWithContentDescription("排序：拍摄时间：最新优先").assertExists()
        compose.onNodeWithTag("gallery-sort-menu").performClick()
        compose.onNodeWithTag("gallery-sort-Oldest").performClick()
        compose.onNodeWithContentDescription("排序：拍摄时间：最早优先").assertExists()
        compose.onNodeWithTag("gallery-sort-menu").performClick()
        compose.onNodeWithTag("gallery-sort-Oldest").assertIsSelected()
        compose.onNodeWithTag("gallery-sort-Newest").assertIsNotSelected()
    }

    @Test fun layoutToggleShowsAListRowWithNameSecondaryLineAndInfo() {
        render(computer(listOf(rawFile("a")),ScanState(phase=ScanPhase.Done)))
        compose.onNodeWithTag("gallery-grid").assertExists()
        compose.onNodeWithContentDescription("切换为详情列表").assertExists()
        compose.onNodeWithTag("gallery-layout-toggle").performClick()
        compose.onNodeWithTag("gallery-list").assertExists()
        compose.onNodeWithTag("gallery-grid").assertDoesNotExist()
        compose.onNodeWithText("a").assertExists()
        compose.onNodeWithText("ARW · 未知大小",substring=true).assertExists()
        compose.onNodeWithContentDescription("照片信息 a").assertExists()
        compose.onNodeWithContentDescription("切换为网格").assertExists()
    }

    @Test fun folderRowsHaveNoInfoButton() {
        render(computer(listOf(CameraFile("dir","dir",container=true)),ScanState(phase=ScanPhase.Done)))
        compose.onNodeWithTag("gallery-layout-toggle").performClick()
        compose.onNodeWithText("文件夹").assertExists()
        compose.onNodeWithContentDescription("照片信息 dir").assertDoesNotExist()
    }

    @Test fun switchingLayoutKeepsSelectionAndFilter() {
        render(computer(listOf(rawFile("a"),jpegFile("b")),ScanState(phase=ScanPhase.Done)))
        compose.onNodeWithTag("gallery-item-a").performTouchInput { longClick() }
        compose.onNodeWithTag("gallery-item-b").performTouchInput { longClick() }
        compose.onNodeWithText("ARW").performClick()
        compose.onNodeWithText("另有 1 项被筛选隐藏，不会下载。").assertExists()
        compose.onNodeWithTag("gallery-layout-toggle").performClick()
        compose.onNodeWithTag("gallery-list").assertExists()
        compose.onNodeWithText("下载 1 个文件").assertExists()
        compose.onAllNodesWithContentDescription("已选择").assertCountEquals(1)
        compose.onNodeWithText("另有 1 项被筛选隐藏，不会下载。").assertExists()
    }

    private fun firstVisibleTag(containerTag: String, ids: List<String>): String {
        val container = compose.onNodeWithTag(containerTag).fetchSemanticsNode().boundsInRoot
        val visible = ids.mapNotNull { id ->
            val node = compose.onAllNodesWithTag("gallery-item-$id").fetchSemanticsNodes().firstOrNull() ?: return@mapNotNull null
            val bounds = node.boundsInRoot
            if (bounds.width <= 0f || bounds.height <= 0f || !bounds.overlaps(container)) return@mapNotNull null
            id to bounds
        }
        return visible.minWithOrNull(compareBy({ it.second.top }, { it.second.left }))?.first
            ?: error("no gallery item is visible")
    }

    @Test fun layoutSwitchKeepsTheFirstVisibleItemInBothDirections() {
        // Fixture must overflow tablet grids so the anchor is a non-zero first visible item.
        val ids=(1..60).map { "f"+it.toString().padStart(2,'0') }
        render(computer(ids.map { rawFile(it) },ScanState(phase=ScanPhase.Done)))
        compose.onNodeWithTag("gallery-grid").performScrollToIndex(40)
        val gridAnchor=firstVisibleTag("gallery-grid",ids)
        assertTrue(gridAnchor!=ids.first())
        compose.onNodeWithTag("gallery-layout-toggle").performClick()
        compose.onNodeWithTag("gallery-list").assertExists()
        compose.onNodeWithTag("gallery-item-$gridAnchor").assertIsDisplayed()
        compose.onNodeWithTag("gallery-list").performScrollToIndex(40)
        val listAnchor=firstVisibleTag("gallery-list",ids)
        assertTrue(listAnchor!=ids.first())
        compose.onNodeWithTag("gallery-layout-toggle").performClick()
        compose.onNodeWithTag("gallery-grid").assertExists()
        compose.onNodeWithTag("gallery-item-$listAnchor").assertIsDisplayed()
    }

    @Test fun listAppendKeepsTheScrollAnchor() {
        val initial=(1..24).map { rawFile("f"+it.toString().padStart(2,'0')) }
        var state by mutableStateOf(computer(initial,ScanState(phase=ScanPhase.Scanning,scanned=24,found=24)))
        compose.setContent { MaterialTheme { CameraGallery(state,GalleryActions()) } }
        compose.onNodeWithTag("gallery-layout-toggle").performClick()
        compose.onNodeWithTag("gallery-list").performScrollToIndex(19)
        compose.onNodeWithTag("gallery-item-f20").assertIsDisplayed()
        compose.runOnIdle { state=state.copy(files=initial+rawFile("f25")) }
        compose.onNodeWithTag("gallery-item-f20").assertIsDisplayed()
    }

    @Test fun activeSortChangeReturnsToTheTop() {
        val initial=(1..24).map { rawFile("f"+it.toString().padStart(2,'0')) }
        render(computer(initial,ScanState(phase=ScanPhase.Done)))
        compose.onNodeWithTag("gallery-layout-toggle").performClick()
        compose.onNodeWithTag("gallery-list").performScrollToIndex(19)
        compose.onNodeWithTag("gallery-item-f20").assertIsDisplayed()
        compose.onNodeWithTag("gallery-sort-menu").performClick()
        compose.onNodeWithTag("gallery-sort-NameAscending").performClick()
        compose.onNodeWithTag("gallery-item-f01").assertIsDisplayed()
    }

    @Test fun listPreviewDownloadCarriesTheGenerationCapturedWhenOpened() {
        val calls=mutableListOf<Pair<List<CameraFile>,Long>>()
        val state=computer(listOf(rawFile("a")),ScanState(phase=ScanPhase.Done),generation=5)
        compose.setContent { MaterialTheme { CameraGallery(state,GalleryActions(preview={_,_,_->null },enqueue={files,gen-> calls+=files to gen })) } }
        compose.onNodeWithTag("gallery-layout-toggle").performClick()
        compose.onNodeWithTag("gallery-item-a").performClick()
        compose.onNodeWithText("下载").performClick()
        compose.runOnIdle {
            assertEquals(5L,calls.single().second)
            assertEquals(listOf("a"),calls.single().first.map { it.id })
        }
    }

    @Test fun listLongPressDownloadCarriesTheComposedGeneration() {
        val calls=mutableListOf<Pair<List<CameraFile>,Long>>()
        val state=computer(listOf(rawFile("a")),ScanState(phase=ScanPhase.Done),generation=7)
        compose.setContent { MaterialTheme { CameraGallery(state,GalleryActions(enqueue={files,gen-> calls+=files to gen })) } }
        compose.onNodeWithTag("gallery-layout-toggle").performClick()
        compose.onNodeWithTag("gallery-item-a").performTouchInput { longClick() }
        compose.onNodeWithText("下载 1 个文件").performClick()
        compose.runOnIdle {
            assertEquals(7L,calls.single().second)
            assertEquals(listOf("a"),calls.single().first.map { it.id })
        }
    }

    @Test fun everySortReordersTheListItems() {
        val a=CameraFile("a","a",size=300L,date="2026-03-01T00:00:00Z")
        val b=CameraFile("b","b",size=100L,date="2026-01-01T00:00:00Z")
        val c=CameraFile("c","c",size=500L,date="2026-02-01T00:00:00Z")
        render(computer(listOf(a,b,c),ScanState(phase=ScanPhase.Done)))
        compose.onNodeWithTag("gallery-layout-toggle").performClick()
        fun assertOrder(vararg ids:String) {
            val tops=ids.map { compose.onNodeWithTag("gallery-item-$it").fetchSemanticsNode().boundsInRoot.top }
            assertTrue("order $ids", tops.zipWithNext().all { (top,bottom) -> top<bottom })
        }
        fun choose(name:String) { compose.onNodeWithTag("gallery-sort-menu").performClick(); compose.onNodeWithTag("gallery-sort-$name").performClick() }
        assertOrder("a","c","b")
        choose("Oldest"); assertOrder("b","c","a")
        choose("NameAscending"); assertOrder("a","b","c")
        choose("NameDescending"); assertOrder("c","b","a")
        choose("SizeAsc"); assertOrder("b","a","c")
        choose("SizeDesc"); assertOrder("c","a","b")
    }

    @Test fun foldersFollowTheSelectedSortAheadOfFiles() {
        // Date-titled folders sort by their title when they carry no date; the undated folder is unknown.
        val jan=CameraFile("jan","2026-01-01",container=true)
        val dec=CameraFile("dec","2026-12-31",container=true)
        val plain=CameraFile("plain","plain",container=true)
        val photo=jpegFile("p")
        render(jpeg(listOf(photo,plain,jan,dec)))
        compose.onNodeWithTag("gallery-layout-toggle").performClick()
        fun assertOrder(vararg ids:String) {
            val tops=ids.map { compose.onNodeWithTag("gallery-item-$it").fetchSemanticsNode().boundsInRoot.top }
            assertTrue("order $ids", tops.zipWithNext().all { (top,bottom) -> top<bottom })
        }
        fun choose(name:String) { compose.onNodeWithTag("gallery-sort-menu").performClick(); compose.onNodeWithTag("gallery-sort-$name").performClick() }
        // Default newest-first keeps folders first, newest date first and the undated folder last.
        assertOrder("dec","jan","plain","p")
        choose("Oldest"); assertOrder("jan","dec","plain","p")
        choose("NameAscending"); assertOrder("jan","dec","plain","p")
        choose("NameDescending"); assertOrder("plain","dec","jan","p")
        // Folders have no real size; both size directions keep a stable natural-name ascending order.
        choose("SizeAsc"); assertOrder("jan","dec","plain","p")
        choose("SizeDesc"); assertOrder("jan","dec","plain","p")
    }

    @Test fun listInfoOpensTheDetailDialog() {
        render(computer(listOf(rawFile("a")),ScanState(phase=ScanPhase.Done)))
        compose.onNodeWithTag("gallery-layout-toggle").performClick()
        compose.onNodeWithContentDescription("照片信息 a").performClick()
        compose.onNodeWithText("关闭").assertExists()
    }

    @Test fun listStreamingInsertBeforeTheAnchorKeepsItVisible() {
        val initial=(1..24).map { rawFile("f"+it.toString().padStart(2,'0')) }
        var state by mutableStateOf(computer(initial,ScanState(phase=ScanPhase.Scanning,scanned=24,found=24)))
        compose.setContent { MaterialTheme { CameraGallery(state,GalleryActions()) } }
        compose.onNodeWithTag("gallery-layout-toggle").performClick()
        compose.onNodeWithTag("gallery-list").performScrollToIndex(19)
        compose.onNodeWithTag("gallery-item-f20").assertIsDisplayed()
        // f00 sorts before every existing name, shifting the anchor's index down by one.
        compose.runOnIdle { state=state.copy(files=listOf(rawFile("f00"))+initial) }
        compose.onNodeWithTag("gallery-item-f20").assertIsDisplayed()
    }

    @Test fun jpegFolderTapBrowsesIntoTheDirectoryInListMode() {
        val browsed=mutableListOf<String>()
        render(jpeg(listOf(CameraFile("dir","dir",container=true),jpegFile("a"))),GalleryActions(browse={browsed+=it}))
        compose.onNodeWithTag("gallery-layout-toggle").performClick()
        compose.onNodeWithTag("gallery-list").assertExists()
        compose.onNodeWithTag("gallery-item-dir").performClick()
        compose.runOnIdle { assertEquals(listOf("dir"),browsed) }
    }

    @Test fun stateRestorationKeepsLayoutFilterAndSort() {
        val restoration=StateRestorationTester(compose)
        restoration.setContent { MaterialTheme { CameraGallery(computer(listOf(rawFile("a"),jpegFile("b")),ScanState(phase=ScanPhase.Done)),GalleryActions()) } }
        compose.onNodeWithTag("gallery-layout-toggle").performClick()
        compose.onNodeWithText("ARW").performClick()
        compose.onNodeWithTag("gallery-sort-menu").performClick()
        compose.onNodeWithTag("gallery-sort-Oldest").performClick()
        compose.onNodeWithTag("gallery-list").assertExists()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("gallery-list").assertExists()
        compose.onNodeWithContentDescription("排序：拍摄时间：最早优先").assertExists()
        compose.onNodeWithText("ARW").assertIsSelected()
    }

    @Test fun stateRestorationKeepsTheListScrollPosition() {
        val initial=(1..24).map { rawFile("f"+it.toString().padStart(2,'0')) }
        val restoration=StateRestorationTester(compose)
        restoration.setContent { MaterialTheme { CameraGallery(computer(initial,ScanState(phase=ScanPhase.Done)),GalleryActions()) } }
        compose.onNodeWithTag("gallery-layout-toggle").performClick()
        compose.onNodeWithTag("gallery-list").performScrollToIndex(19)
        compose.onNodeWithTag("gallery-item-f20").assertIsDisplayed()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("gallery-list").assertExists()
        compose.onNodeWithTag("gallery-item-f20").assertIsDisplayed()
    }

    @Test fun detailDialogShowsTheFormattedLocalTimeInsteadOfTheRawDateString() {
        val date="Sat Oct 24 13:00:00 GMT+08:00 2026"
        val formatted=galleryTimeText(date) ?: error("fixture must parse")
        render(computer(listOf(CameraFile("a","a",raw=true,handle=1L,date=date)),ScanState(phase=ScanPhase.Done)))
        compose.onNodeWithContentDescription("照片信息 a").performClick()
        compose.onNodeWithText(formatted).assertExists()
        compose.onNodeWithText(date).assertDoesNotExist()
        compose.onNodeWithText("ARW 原文件",substring=true).assertExists()
    }

    @Test fun detailDialogOmitsAnUnparseableDate() {
        val broken=CameraFile("b","b",raw=true,handle=1L,date="not a date")
        render(computer(listOf(broken),ScanState(phase=ScanPhase.Done)))
        compose.onNodeWithContentDescription("照片信息 b").performClick()
        compose.onNodeWithText("not a date").assertDoesNotExist()
    }

    // ---- Remote same-connection gallery -------------------------------------------------------

    private fun remote(files:List<CameraFile>,remoteState:RemoteState,connected:Boolean=true)=
        AppState(mode=CameraMode.REMOTE,connected=connected,files=files,remote=remoteState,scan=ScanState(phase=ScanPhase.Idle))

    private fun remoteGallery(files:List<CameraFile>,remoteState:RemoteState)=
        remote(files,remoteState.copy(galleryActive=true))

    @Test fun remoteGalleryNeverRendersWithoutTheGalleryFlag() {
        render(remote(emptyList(),RemoteState(canBrowseGallery=true,galleryActive=false)))
        compose.onNodeWithText("已连接 · 相机图库").assertDoesNotExist()
        compose.onNodeWithText("从首页连接 JPEG 传输，或已完成登记的 RAW / 原文件模式。").assertExists()
    }

    @Test fun switchingIntoTheGalleryShowsProgressInsteadOfTheList() {
        render(remoteGallery(emptyList(),RemoteState(canBrowseGallery=true,remoteSwitching=true)))
        compose.onNodeWithText("已连接 · 相机图库").assertExists()
        compose.onNodeWithText("正在打开相机图库…").assertExists()
        compose.onNodeWithTag("gallery-grid").assertDoesNotExist()
    }

    @Test fun enteringWhileTheCameraAlreadyReportsContentsTransferIsNotAReturn() {
        // A retry after a failed browse, or a camera left in Contents Transfer: the current function is
        // already Contents Transfer, but the switch target is the gallery, so this must not read as a return.
        render(remoteGallery(emptyList(),RemoteState(canBrowseGallery=true,remoteSwitching=true,
            switchTarget=RemoteFunction.CONTENTS_TRANSFER,remoteFunction=RemoteFunction.CONTENTS_TRANSFER)))
        compose.onNodeWithText("正在打开相机图库…").assertExists()
        compose.onNodeWithText("正在返回拍摄…").assertDoesNotExist()
        compose.onNodeWithTag("remote-gallery-returning").assertDoesNotExist()
    }

    @Test fun loadedCountUsesTheContentItemCountAndOffersTheNextPage() {
        val loads=mutableListOf<Unit>()
        render(remoteGallery(listOf(rawFile("a")),RemoteState(canBrowseGallery=true,loadedContentCount=20,totalFiles=40,hasMoreFiles=true)),
            GalleryActions(loadMore={loads+=Unit}))
        compose.onNodeWithText("已加载 20 / 40 项").assertExists()
        compose.onNodeWithText("加载更多（20 项）").performClick()
        compose.runOnIdle { assertEquals(1,loads.size) }
    }

    @Test fun anUnknownTotalIsReportedAsTheRecentCountWithoutInventingOne() {
        render(remoteGallery(listOf(rawFile("a")),RemoteState(canBrowseGallery=true,loadedContentCount=5,totalFiles=null,hasMoreFiles=false)))
        compose.onNodeWithText("已加载最近 5 项").assertExists()
        compose.onNodeWithText("加载更多（20 项）").assertDoesNotExist()
    }

    @Test fun loadingTheNextPageShowsAProgressLabelAndNoSecondRequest() {
        render(remoteGallery(listOf(rawFile("a")),RemoteState(canBrowseGallery=true,loadedContentCount=20,totalFiles=40,hasMoreFiles=true,loadingMore=true)))
        compose.onNodeWithText("正在加载…").assertExists()
        compose.onNodeWithText("加载更多（20 项）").assertDoesNotExist()
    }

    @Test fun remoteGalleryOffersTheJpegAndArwFilters() {
        render(remoteGallery(listOf(rawFile("a"),jpegFile("b")),RemoteState(canBrowseGallery=true,loadedContentCount=2)))
        compose.onNodeWithText("ARW").assertExists()
        compose.onNodeWithText("可按 JPEG / ARW 筛选并下载相机原文件。").assertExists()
        compose.onNodeWithText("ARW").performClick()
        compose.onNodeWithTag("gallery-item-a").assertExists()
        compose.onNodeWithTag("gallery-item-b").assertDoesNotExist()
    }

    @Test fun selectAllOnlyCoversTheLoadedContent() {
        render(remoteGallery(listOf(rawFile("a"),rawFile("b")),RemoteState(canBrowseGallery=true,loadedContentCount=20,totalFiles=40,hasMoreFiles=true)))
        compose.onNodeWithText("全选 2").assertExists()
    }

    @Test fun returningIsBlockedWhileADownloadIsRunning() {
        val returns=mutableListOf<Unit>()
        render(remoteGallery(listOf(rawFile("a")),RemoteState(canBrowseGallery=true,loadedContentCount=1)).copy(transferring=true),
            GalleryActions(returnToCapture={returns+=Unit}))
        compose.onNodeWithText("正在下载文件，请先等待完成或取消下载后再返回拍摄。").assertExists()
        compose.onNodeWithTag("remote-gallery-return").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0,returns.size) }
    }

    @Test fun returningShowsProgressAndCallsTheController() {
        val returns=mutableListOf<Unit>()
        var state by mutableStateOf(remoteGallery(listOf(rawFile("a")),RemoteState(canBrowseGallery=true,loadedContentCount=1)))
        compose.setContent { MaterialTheme { CameraGallery(state,GalleryActions(returnToCapture={returns+=Unit})) } }
        compose.onNodeWithTag("remote-gallery-return").performClick()
        // The backend reports the return by targeting Remote Shooting, not by the camera's current function.
        compose.runOnIdle { state=state.copy(remote=state.remote.copy(remoteSwitching=true,switchTarget=RemoteFunction.REMOTE_SHOOTING)) }
        compose.onNodeWithText("正在返回拍摄…").assertExists()
        compose.runOnIdle { assertEquals(1,returns.size) }
    }

    @Test fun aFailedReturnStaysInTheGalleryAndOffersReconnect() {
        val reconnects=mutableListOf<Unit>()
        var state by mutableStateOf(remoteGallery(listOf(rawFile("a")),RemoteState(canBrowseGallery=true,loadedContentCount=1)))
        compose.setContent { MaterialTheme { CameraGallery(state,GalleryActions(reconnect={reconnects+=Unit})) } }
        compose.onNodeWithTag("remote-gallery-return").performClick()
        compose.runOnIdle { state=state.copy(remote=state.remote.copy(remoteSwitching=true,switchTarget=RemoteFunction.REMOTE_SHOOTING)) }
        compose.runOnIdle { state=state.copy(remote=state.remote.copy(remoteSwitching=false),error="无法返回拍摄模式：内部原因") }
        compose.onNodeWithText("无法返回拍摄模式，请重试或重新连接相机。").assertExists()
        compose.onNodeWithText("重新连接相机").performClick()
        compose.runOnIdle { assertEquals(1,reconnects.size) }
    }

    @Test fun theReturnButtonIsAlsoBlockedWhileLoadingOrBusy() {
        val loading=remoteGallery(listOf(rawFile("a")),RemoteState(canBrowseGallery=true,loadedContentCount=1,loadingMore=true))
        var state by mutableStateOf(loading)
        compose.setContent { MaterialTheme { CameraGallery(state,GalleryActions()) } }
        compose.onNodeWithTag("remote-gallery-return").assertIsNotEnabled()
        compose.runOnIdle { state=state.copy(remote=state.remote.copy(loadingMore=false),busy=true) }
        compose.onNodeWithTag("remote-gallery-return").assertIsNotEnabled()
    }

    @Test fun anUnreadableRemoteGalleryOffersRetryReturnAndReconnect() {
        val retries=mutableListOf<Unit>(); val reconnects=mutableListOf<Unit>()
        render(remoteGallery(emptyList(),RemoteState(canBrowseGallery=true,loadedContentCount=0,totalFiles=null)).copy(error="无法进入相机图库：内部原因"),
            GalleryActions(refreshRemoteGallery={retries+=Unit},reconnect={reconnects+=Unit}))
        compose.onNodeWithText("无法读取相机文件，请重试或返回拍摄。").assertExists()
        compose.onNodeWithText("重试").performClick()
        compose.onNodeWithText("重新连接相机").performClick()
        compose.runOnIdle { assertEquals(1,retries.size); assertEquals(1,reconnects.size) }
    }

    @Test fun anEmptyRemoteCardShowsNoPhotosInsteadOfAnError() {
        render(remoteGallery(emptyList(),RemoteState(canBrowseGallery=true,loadedContentCount=0,totalFiles=0)))
        compose.onNodeWithText("没有照片").assertExists()
        compose.onNodeWithText("无法读取相机文件，请重试或返回拍摄。").assertDoesNotExist()
    }

    @Test fun aFirstPageWithoutSupportedFilesStillOffersTheNextPage() {
        // The first page held only unsupported content (movie / no original): not an error, keep loading.
        render(remoteGallery(emptyList(),RemoteState(canBrowseGallery=true,loadedContentCount=20,totalFiles=40,hasMoreFiles=true)))
        compose.onNodeWithText("没有可显示的照片").assertExists()
        compose.onNodeWithText("无法读取相机文件，请重试或返回拍摄。").assertDoesNotExist()
        compose.onNodeWithText("加载更多（20 项）").assertExists()
    }

    @Test fun anUnknownCountEmptyPageIsNotAnError() {
        render(remoteGallery(emptyList(),RemoteState(canBrowseGallery=true,loadedContentCount=0,totalFiles=null,hasMoreFiles=false))
            .copy(scan=ScanState(phase=ScanPhase.Done)))
        compose.onNodeWithText("没有可显示的照片").assertExists()
        compose.onNodeWithText("无法读取相机文件，请重试或返回拍摄。").assertDoesNotExist()
        compose.onNodeWithText("加载更多（20 项）").assertDoesNotExist()
    }

    @Test fun aFailedPageWithExistingFilesOffersAConciseFooterRetry() {
        var state by mutableStateOf(remoteGallery(listOf(rawFile("a")),RemoteState(canBrowseGallery=true,loadedContentCount=1,totalFiles=40,hasMoreFiles=true)))
        compose.setContent { MaterialTheme { CameraGallery(state,GalleryActions()) } }
        compose.onNodeWithText("加载更多失败，请重试。").assertDoesNotExist()
        compose.runOnIdle { state=state.copy(remote=state.remote.copy(loadMoreFailed=true),error="加载更多失败，请重试。") }
        compose.onNodeWithText("加载更多失败，请重试。").assertExists()
        compose.onNodeWithText("加载更多（20 项）").assertExists()
    }

    @Test fun theLoadMoreButtonIsBlockedWhileTransferring() {
        render(remoteGallery(listOf(rawFile("a")),RemoteState(canBrowseGallery=true,loadedContentCount=1,totalFiles=40,hasMoreFiles=true)).copy(transferring=true))
        compose.onNodeWithText("加载更多（20 项）").assertIsNotEnabled()
    }

    @Test fun aModeEpochChangeClearsSelectionAndPreview() {
        var state by mutableStateOf(remoteGallery(listOf(rawFile("a")),RemoteState(canBrowseGallery=true,loadedContentCount=1,epoch=0)))
        compose.setContent { MaterialTheme { CameraGallery(state,GalleryActions()) } }
        compose.onNodeWithTag("gallery-item-a").performTouchInput { longClick() }
        compose.onNodeWithText("下载 1 个文件").assertExists()
        compose.runOnIdle { state=state.copy(remote=state.remote.copy(epoch=1)) }
        compose.onNodeWithText("下载 1 个文件").assertDoesNotExist()
        compose.onAllNodesWithContentDescription("已选择").assertCountEquals(0)
    }
}
