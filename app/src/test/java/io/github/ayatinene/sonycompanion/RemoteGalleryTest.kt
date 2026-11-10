package io.github.ayatinene.sonycompanion

import io.github.ayatinene.sonycompanion.data.*
import io.github.ayatinene.sonycompanion.protocol.AV_CONTENT_FORMAT_JPEG
import io.github.ayatinene.sonycompanion.protocol.AV_CONTENT_FORMAT_RAW
import io.github.ayatinene.sonycompanion.protocol.AvContentItem
import io.github.ayatinene.sonycompanion.protocol.AvContentOriginal
import io.github.ayatinene.sonycompanion.protocol.AvContentPage
import io.github.ayatinene.sonycompanion.protocol.CameraXml
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.URI

/** Pure JVM tests for the same-connection gallery state machine, pagination and identity mapping. */
class RemoteGalleryTest {
    @get:Rule val temporary = TemporaryFolder()

    private val udn = "uuid:11111111-2222-3333-4444-555555555555"
    private fun device(host: String = "camera") =
        Device("Sony", "ILCE-7RM2", URI("http://$host/dd.xml"), remote = URI("http://$host/sony/camera"), udn = udn)

    private fun item(
        id: String = "c1",
        name: String = "DSC00001",
        date: String = "2026-01-02T03:04:05Z",
        originals: List<AvContentOriginal> = listOf(AvContentOriginal("http://cam/DSC00001.JPG", AV_CONTENT_FORMAT_JPEG, "DSC00001.JPG", 1000)),
        preview: String? = "http://cam/pv.jpg",
        source: String = "storage"
    ) = AvContentItem(source, id, name, date, originals, preview)

    // --- policy --------------------------------------------------------------------------------

    @Test fun enterGalleryRequiresIdleShootingStateAndCapability() {
        fun enter(
            connected: Boolean = true, supported: Boolean = true, sub: RemoteSubMode = RemoteSubMode.Shooting,
            status: String = "IDLE", interval: Boolean = false, transferring: Boolean = false, busy: Boolean = false
        ) = RemoteGalleryPolicy.canEnter(connected, supported, sub, status, interval, transferring, busy)

        assertTrue(enter())
        assertTrue(enter(status = ""))
        assertFalse("disconnected", enter(connected = false))
        assertFalse("no capability", enter(supported = false))
        assertFalse("already switching", enter(sub = RemoteSubMode.SwitchingToGallery))
        assertFalse("already in gallery", enter(sub = RemoteSubMode.Gallery))
        assertFalse("recording", enter(status = "MovieRecording"))
        assertFalse("still capturing", enter(status = "StillCapturing"))
        assertFalse("unknown non-idle status", enter(status = "SomethingNew"))
        assertFalse("interval active", enter(interval = true))
        assertFalse("transferring", enter(transferring = true))
        assertFalse("busy", enter(busy = true))
    }

    @Test fun returnIsRefusedWhileDownloadingOrBusy() {
        assertTrue(RemoteGalleryPolicy.canReturn(true, RemoteSubMode.Gallery, transferring = false, busy = false))
        assertFalse(RemoteGalleryPolicy.canReturn(true, RemoteSubMode.Gallery, transferring = true, busy = false))
        assertFalse(RemoteGalleryPolicy.canReturn(true, RemoteSubMode.Gallery, transferring = false, busy = true))
        assertFalse(RemoteGalleryPolicy.canReturn(true, RemoteSubMode.Shooting, transferring = false, busy = false))
        assertFalse(RemoteGalleryPolicy.canReturn(false, RemoteSubMode.Gallery, transferring = false, busy = false))
    }

    // --- pagination ----------------------------------------------------------------------------

    @Test fun firstPagePublishesImmediatelyAndMoreFollowsBySource() {
        val pager = GalleryPager(20)
        pager.begin("storage", 100)
        val first = pager.accept(AvContentPage((1..20).map { item("c$it", "DSC$it") }, 20))
        assertEquals(20, first.size)
        assertEquals(20, pager.contentLoaded)
        assertEquals(20, pager.nextStart)
        assertTrue(pager.hasMore)
        val second = pager.accept(AvContentPage((21..40).map { item("c$it", "DSC$it") }, 20))
        assertEquals(20, second.size)
        assertEquals(40, pager.nextStart)
        assertTrue(pager.hasMore)
    }

    @Test fun shortOrEmptyPageEndsPagination() {
        val pager = GalleryPager(20)
        pager.begin("storage", 25)
        pager.accept(AvContentPage((1..20).map { item("c$it") }, 20))
        pager.accept(AvContentPage((21..25).map { item("c$it") }, 5))
        assertFalse("a short page is the last", pager.hasMore)
        val empty = GalleryPager(20)
        empty.begin("storage", 5)
        assertEquals(emptyList<AvContentItem>(), empty.accept(AvContentPage(emptyList(), 0)))
        assertFalse(empty.hasMore)
    }

    @Test fun declaredTotalStopsWithoutWaitingForShortPage() {
        val pager = GalleryPager(20)
        pager.begin("storage", 20)
        pager.accept(AvContentPage((1..20).map { item("c$it") }, 20))
        assertFalse("declared count reached", pager.hasMore)
    }

    @Test fun duplicatePageMakesNoProgressAndStops() {
        val pager = GalleryPager(20)
        pager.begin("storage", null)
        val page = AvContentPage((1..20).map { item("c$it") }, 20)
        assertEquals(20, pager.accept(page).size)
        assertTrue(pager.hasMore)
        assertEquals(emptyList<AvContentItem>(), pager.accept(page))
        assertFalse("a duplicate page must not spin", pager.hasMore)
    }

    @Test fun unknownKindPageStillAdvancesPagination() {
        val pager = GalleryPager(20)
        pager.begin("storage", 40)
        // 20 raw uris but every parsed item was dropped as an unknown kind: the page still advances.
        val dropped = AvContentPage(emptyList(), 20, (1..20).map { "uri-$it" })
        assertEquals(emptyList<AvContentItem>(), pager.accept(dropped))
        assertEquals(20, pager.nextStart)
        assertTrue("a page of dropped items is not a duplicate", pager.hasMore)
        assertEquals(emptyList<AvContentItem>(), pager.accept(dropped))
        assertFalse("a genuinely duplicate page stops", pager.hasMore)
    }

    @Test fun thumbnailUrlIsPreferredOverPreview() {
        val file = item().copy(thumbnailUrl = "http://cam/tn.jpg").toCameraFiles().single()
        assertEquals("http://cam/tn.jpg", file.thumbnail)
    }

    @Test fun mergingCameraStatePreservesAppOwnedGalleryFields() {
        val live = RemoteState(apis = setOf("actTakePicture"), status = "IDLE", canBrowseGallery = true,
            galleryActive = true, remoteSwitching = true, loadingMore = true, loadMoreFailed = true,
            hasMoreFiles = true, totalFiles = 45, loadedContentCount = 20,
            remoteFunction = RemoteFunction.CONTENTS_TRANSFER, switchTarget = RemoteFunction.REMOTE_SHOOTING, epoch = 7)
        // A camera-produced state only carries apis/settings/status/application/eventVersion.
        val fromCamera = RemoteState(apis = setOf("getEvent"), status = "StillCapturing")
        val merged = live.mergeCameraState(fromCamera)
        assertEquals(setOf("getEvent"), merged.apis)
        assertEquals("StillCapturing", merged.status)
        // Every app-owned field must survive the merge, or the gallery entry becomes unreachable.
        assertTrue(merged.canBrowseGallery)
        assertTrue(merged.galleryActive)
        assertTrue(merged.remoteSwitching)
        assertTrue(merged.loadingMore)
        assertTrue(merged.loadMoreFailed)
        assertTrue(merged.hasMoreFiles)
        assertEquals(45, merged.totalFiles)
        assertEquals(20, merged.loadedContentCount)
        assertEquals(RemoteFunction.CONTENTS_TRANSFER, merged.remoteFunction)
        assertEquals(RemoteFunction.REMOTE_SHOOTING, merged.switchTarget)
        assertEquals(7L, merged.epoch)
    }

    // --- identity mapping ----------------------------------------------------------------------

    @Test fun rawAndJpegOriginalsExpandToTwoExplicitFiles() {
        val files = item(originals = listOf(
            AvContentOriginal("http://cam/DSC00001.JPG", AV_CONTENT_FORMAT_JPEG, "DSC00001.JPG", 1000),
            AvContentOriginal("http://cam/DSC00001.ARW", AV_CONTENT_FORMAT_RAW, "DSC00001.ARW", 5000)
        )).toCameraFiles()
        assertEquals(2, files.size)
        val jpeg = files.single { it.format == AV_CONTENT_FORMAT_JPEG }
        val raw = files.single { it.format == AV_CONTENT_FORMAT_RAW }
        assertEquals("storage#c1#jpeg#DSC00001.JPG", jpeg.id)
        assertEquals("storage#c1#arw#DSC00001.ARW", raw.id)
        assertEquals(PROTOCOL_HTTP, raw.protocol)
        assertTrue(raw.raw)
        assertFalse(jpeg.raw)
        assertTrue(jpeg.hasOriginalFile)
        assertTrue(raw.hasOriginalFile)
        assertEquals("http://cam/DSC00001.ARW", raw.originalResource!!.url)
        assertEquals("image/x-sony-arw", raw.originalResource!!.mime)
        assertEquals("image/jpeg", jpeg.originalResource!!.mime)
        assertEquals("http://cam/pv.jpg", raw.thumbnail)
    }

    @Test fun hasOriginalFileIsTrueOnlyForPtpAndAvContentOriginals() {
        val ptp = CameraFile("1", "DSC.ARW", handle = 1, raw = true, size = 10)
        val avContent = item().toCameraFiles().single()
        val legacyJpeg = CameraFile("2", "DSC.JPG", listOf(Resource("http://cam/x.jpg", size = 10)))
        assertTrue(ptp.hasOriginalFile)
        assertTrue(avContent.hasOriginalFile)
        assertFalse("legacy JPEG mode still replaces by size", legacyJpeg.hasOriginalFile)
        assertNull(legacyJpeg.originalResource)
    }

    @Test fun sameFormatSiblingsNeverCollide() {
        val files = item(originals = listOf(
            AvContentOriginal("http://cam/a.JPG", AV_CONTENT_FORMAT_JPEG, "a.JPG", 1),
            AvContentOriginal("http://cam/b.JPG", AV_CONTENT_FORMAT_JPEG, "b.JPG", 1)
        )).toCameraFiles()
        assertEquals(2, files.size)
        assertEquals(2, files.map { it.id }.toSet().size)
    }

    @Test fun folderNoKeepsSameNamedItemsInDifferentFoldersApart() {
        val inA = item(id = "c1").copy(folderNo = "100MSDCF").toCameraFiles().single()
        val inB = item(id = "c2").copy(folderNo = "101MSDCF").toCameraFiles().single()
        assertEquals("storage/100MSDCF", inA.parent)
        assertEquals("storage/101MSDCF", inB.parent)
        assertNull("a different-folder item must not match", refreshQueuedFile(inA, listOf(inB)))
    }

    // --- transfer identity / recovery ----------------------------------------------------------

    @Test fun originalSourceKeyIsStableWhenListingSizeIsUnknown() {
        val key = "storage|camera"
        val file = item(originals = listOf(AvContentOriginal("http://cam/a.ARW", AV_CONTENT_FORMAT_RAW, "a.ARW", 0))).toCameraFiles().single()
        val listedUnknown = transferSource(key, file, file.originalResource)
        val listedKnown = transferSource(key, file, file.originalResource!!.copy(size = 36_700_160))
        assertEquals("a size change from unknown must not look like a new file", listedUnknown, listedKnown)
        val jpeg = item(originals = listOf(AvContentOriginal("http://cam/a.JPG", AV_CONTENT_FORMAT_JPEG, "a.JPG", 0))).toCameraFiles().single()
        assertNotEquals(listedUnknown, transferSource(key, jpeg, jpeg.originalResource))
    }

    @Test fun recoveryReplacesTheStoredUrlAndNeverTrustsIt() {
        val old = item(originals = listOf(AvContentOriginal("http://old/DSC00001.ARW", AV_CONTENT_FORMAT_RAW, "DSC00001.ARW", 5000))).toCameraFiles().single()
        val fresh = item(originals = listOf(AvContentOriginal("http://new/DSC00001.ARW", AV_CONTENT_FORMAT_RAW, "DSC00001.ARW", 5000))).toCameraFiles().single()
        val refreshed = refreshQueuedFile(old, listOf(fresh))!!
        assertEquals("http://new/DSC00001.ARW", refreshed.originalResource!!.url)
        assertNotEquals(old.originalResource!!.url, refreshed.originalResource!!.url)
        // A changed date, a changed name, or a same-format sibling must not match.
        assertNull(refreshQueuedFile(old, listOf(fresh.copy(date = "2026-01-03T00:00:00Z"))))
        assertNull(refreshQueuedFile(old, listOf(fresh.copy(name = "DSC00002.ARW"))))
        val sibling = item(originals = listOf(AvContentOriginal("http://new/other.ARW", AV_CONTENT_FORMAT_RAW, "other.ARW", 5000))).toCameraFiles().single()
        assertNull(refreshQueuedFile(old, listOf(sibling)))
    }

    @Test fun queueStoreRoundTripsOriginalIdentity() {
        val file = temporary.newFile(); file.delete()
        val store = TransferQueueStore(file)
        val key = avContentQueueScope(device())
        val original = item(originals = listOf(AvContentOriginal("http://cam/a.ARW", AV_CONTENT_FORMAT_RAW, "a.ARW", 42))).toCameraFiles().single()
        store.save(key, listOf(QueueItem(original, "传输中", received = 5)))
        val restored = TransferQueueStore(file).load(key).single()
        assertEquals("待恢复", restored.state)
        assertEquals(0L, restored.received)
        assertTrue(restored.file.original)
        assertEquals(AV_CONTENT_FORMAT_RAW, restored.file.format)
        assertEquals(PROTOCOL_HTTP, restored.file.protocol)
        assertEquals(original.id, restored.file.id)
        assertTrue(restored.file.hasOriginalFile)
    }

    @Test fun avContentQueueScopeIsUdnStableAndIndependent() {
        assertEquals(avContentQueueScope(device()), avContentQueueScope(device("other-host")))
        assertTrue(avContentQueueScope(device()).endsWith("|avContent"))
        assertNotEquals(avContentQueueScope(device()), queueScope(device(), CameraMode.JPEG))
        assertNotEquals(avContentQueueScope(device()), queueScope(device(), CameraMode.REMOTE))
        val unknown = Device("Sony", "ILCE", URI("http://c/dd.xml"))
        assertNotEquals(avContentQueueScope(unknown), avContentQueueScope(unknown))
    }

    @Test fun cameraXmlParsesTheAvContentEndpoint() {
        val xml = """<root><device><friendlyName>α7R II</friendlyName><modelName>ILCE-7RM2</modelName>
            <X_ScalarWebAPI_ServiceList>
              <X_ScalarWebAPI_Service><X_ScalarWebAPI_ServiceType>camera</X_ScalarWebAPI_ServiceType><X_ScalarWebAPI_ActionList_URL>/sony</X_ScalarWebAPI_ActionList_URL></X_ScalarWebAPI_Service>
              <X_ScalarWebAPI_Service><X_ScalarWebAPI_ServiceType>avContent</X_ScalarWebAPI_ServiceType><X_ScalarWebAPI_ActionList_URL>/sony</X_ScalarWebAPI_ActionList_URL></X_ScalarWebAPI_Service>
            </X_ScalarWebAPI_ServiceList></device></root>"""
        val parsed = CameraXml.description(xml, URI("http://192.168.122.1:64321/DmsDescPush.xml"))
        assertEquals("http://192.168.122.1:64321/sony/camera", parsed.remote.toString())
        assertEquals("http://192.168.122.1:64321/sony/avContent", parsed.avContent.toString())
        val without = CameraXml.description("<root><device><friendlyName>x</friendlyName></device></root>", URI("http://c/dd.xml"))
        assertNull(without.avContent)
    }
}
