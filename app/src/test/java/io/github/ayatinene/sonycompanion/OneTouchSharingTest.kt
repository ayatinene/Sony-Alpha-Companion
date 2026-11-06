package io.github.ayatinene.sonycompanion

import io.github.ayatinene.sonycompanion.data.*
import io.github.ayatinene.sonycompanion.network.CameraHttp
import io.github.ayatinene.sonycompanion.protocol.*
import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.net.URI

class OneTouchSharingTest {
    private val camera=URI("http://camera:64321/dd.xml")
    private val did=URI("http://camera:64321/DigitalImagingDesc.xml")
    private fun current(type: String="JPG", port: Int=60151)="""<root xmlns:s="urn:sony"><s:X_CurrentContent_URL>
        <s:X_CurrentContent_URL_URL profileID="JPEG_TN" type="$type">http://camera:$port/TN_DSC12345.JPG?%21id%21tn</s:X_CurrentContent_URL_URL>
        <s:X_CurrentContent_URL_URL profileID="JPEG_LRG" type="$type" size="4">http://camera:$port/LRG_DSC12345.${if(type=="JPG") "JPG" else type}?%21id%21lrg</s:X_CurrentContent_URL_URL>
        <s:X_CurrentContent_URL_URL profileID="UNKNOWN" type="$type" size="8" resolution="7952x5304">http://camera:$port/ORG_DSC12345.$type?%21id%21original</s:X_CurrentContent_URL_URL>
        </s:X_CurrentContent_URL></root>"""
    private fun photo(id: String)=CameraFile(id,"$id.JPG",listOf(Resource("http://camera:60151/$id.JPG",profile="http-get:*:image/jpeg:*")),parent="PushRoot")
    private val emptyPage="<Envelope><Body><BrowseResponse><Result/><NumberReturned>0</NumberReturned><TotalMatches>0</TotalMatches></BrowseResponse></Body></Envelope>"
    private fun fault(code: String)="<Envelope><Body><Fault><detail><UPnPError><errorCode>$code</errorCode></UPnPError></detail></Fault></Body></Envelope>"
    private fun device(server: MockWebServer, withDid: Boolean=true)=Device("Sony","ILCE-7RM2",server.url("/dd.xml").toUri(),
        content=server.url("/cds").toUri(),push=server.url("/push").toUri(),digitalImaging=if(withDid)server.url("/did.xml").toUri()else null)

    @Test fun didSelectsTheOriginalJpegAndKeepsOtherSizes() {
        val file=currentSharedJpeg(current(),did,camera)!!
        assertEquals("DSC12345.JPG",file.name);assertEquals(8L,file.size)
        assertEquals(3,file.resources.size);assertEquals(JpegSize.ORIGINAL,file.bestResource()!!.jpegSize)
        assertEquals("7952x5304",file.bestResource()!!.resolution);assertEquals("NfcCurrent",file.parent)
        assertFalse(file.original);assertFalse(file.raw)
    }
    @Test fun transientPhotoServerPortDoesNotChangeTheCurrentItemIdentity() {
        assertEquals(currentSharedJpeg(current(port=60151),did,camera)!!.id,
            currentSharedJpeg(current(port=60199),did,camera)!!.id)
    }
    @Test fun rawAndMpoOriginalsAreExcludedAndTheirJpegDerivativeIsNamedJpg() {
        for(type in listOf("ARW","MPO")) {
            val file=currentSharedJpeg(current(type),did,camera)!!
            assertEquals("DSC12345.JPG",file.name);assertEquals(4L,file.size)
            assertEquals(JpegSize.LARGE,file.bestResource()!!.jpegSize)
            assertFalse(file.resources.any { it.jpegSize==JpegSize.ORIGINAL })
            assertEquals(type=="ARW",file.raw)
        }
    }
    @Test fun missingCurrentContentAndThumbnailOnlyPermitSelectedListFallback() {
        assertNull(currentSharedJpeg("<root/>",did,camera))
        assertNull(currentSharedJpeg("<root><X_CurrentContent_URL/></root>",did,camera))
        val thumb="<root><X_CurrentContent_URL><X_CurrentContent_URL_URL type=\"JPG\" profileID=\"JPEG_TN\">/TN_A.JPG?q</X_CurrentContent_URL_URL></X_CurrentContent_URL></root>"
        assertNull(currentSharedJpeg(thumb,did,camera))
    }
    @Test fun unsupportedMixedExternalAndUnsafeCurrentItemsAreRejected() {
        for(xml in listOf(current("MP4"),current("HIF"),current().replaceFirst("type=\"JPG\"","type=\"ARW\""),
            current().replace("http://camera:","http://other:"),current().replace("ORG_DSC12345.JPG","ORG_%2Fsecret.JPG"))) {
            assertThrows(IllegalArgumentException::class.java) { currentSharedJpeg(xml,did,camera) }
        }
        assertThrows(IllegalArgumentException::class.java) { currentSharedJpeg("<!DOCTYPE root><root/>",did,camera) }
    }
    @Test fun selectionIncludesAllItemsAndSelectedSubfoldersOnly() = runBlocking {
        val reads=mutableListOf<String>()
        val initial=listOf(photo("A"),CameraFile("selected-folder","folder",container=true))
        val files=selectedSharedJpegs(initial,camera) { reads+=it;listOf(photo("B"),photo("C")) }
        assertEquals(listOf("A","B","C"),files.map { it.id });assertEquals(listOf("selected-folder"),reads)
    }
    @Test fun emptyIncompleteAndRecursiveSelectionsNeverTurnIntoPhotoRootImports() = runBlocking {
        val invalid=listOf(emptyList(),listOf(CameraFile("unsupported","movie.mp4")),
            listOf(CameraFile("PhotoRoot","all photos",container=true)),
            listOf(CameraFile("cycle","folder",container=true)),
            listOf(photo("T").copy(resources=listOf(Resource("http://camera/T.JPG",profile="http-get:*:image/jpeg:DLNA.ORG_PN=JPEG_TN")))))
        for(items in invalid) {
            try { selectedSharedJpegs(items,camera) { listOf(CameraFile("cycle","folder",container=true)) };fail("must reject invalid selection") }
            catch(_: IllegalArgumentException) { }
        }
    }
    @Test fun cancelledSelectionDoesNotContinueEnumerating() = runBlocking {
        var reads=0
        try { selectedSharedJpegs(listOf(CameraFile("folder","folder",container=true)),camera) { reads++;throw CancellationException() };fail("must cancel") }
        catch(_: CancellationException) { assertEquals(1,reads) }
    }
    @Test fun outcomesIgnoreHistoricalTasksAndDistinguishPartialFailureFromCancellation() {
        val ids=setOf("A","B")
        val history=QueueItem(photo("history"),"待恢复")
        fun outcome(a: String,b: String)=oneTouchOutcome(listOf(QueueItem(photo("A"),a),QueueItem(photo("B"),b),history),ids)
        assertEquals(OneTouchResult.Success,outcome("完成","已存在"))
        assertEquals(OneTouchResult.PartialFailure,outcome("完成","失败"));assertEquals(0,OneTouchResult.PartialFailure.code)
        assertEquals(OneTouchResult.Failure,outcome("失败","失败"));assertEquals(1,OneTouchResult.Failure.code)
        assertEquals(OneTouchResult.Cancelled,outcome("完成","已取消"));assertEquals(2,OneTouchResult.Cancelled.code)
    }
    @Test fun didShortcutUsesStartCurrentDescriptorAndNotificationsWithoutBrowsing() = runBlocking {
        MockWebServer().use { server -> CameraHttp().use { http ->
            server.dispatcher=object: Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = MockResponse().setBody(
                    if(request.path=="/did.xml") current().replace("http://camera:60151",server.url("/").toString().trimEnd('/')) else "<Envelope/>")
            }
            val transfer=SmartphoneTransfer(device(server),http)
            val files=transfer.initialize(oneTouch=true)
            assertEquals(1,files.size);assertEquals("NfcCurrent",transfer.rootId)
            transfer.beginOneTouchCopy();transfer.oneTouchProgress(1,1)
            transfer.setOneTouchResult(OneTouchResult.Success);transfer.finish();transfer.finish()
            val requests=List(server.requestCount) { server.takeRequest() }
            assertEquals(5,requests.size)
            assertFalse(requests.any { it.getHeader("SOAPACTION").orEmpty().contains("#Browse") })
            assertTrue(requests[3].body.readUtf8().contains("<NumTransferd>1</NumTransferd>"))
            assertTrue(requests.last().body.readUtf8().contains("<ErrCode>0</ErrCode>"))
        } }
    }
    @Test fun missingPushRootInAutomaticModeNeverBrowsesPhotoRootAndEndsWithFailure() = runBlocking {
        MockWebServer().use { server -> CameraHttp().use { http ->
            server.enqueue(MockResponse().setBody("<Envelope/>"))
            server.enqueue(MockResponse().setResponseCode(500).setBody(fault("701")))
            server.enqueue(MockResponse().setBody("<Envelope/>"))
            val transfer=SmartphoneTransfer(device(server,false),http)
            try { transfer.initialize(oneTouch=true);fail("PushRoot missing must fail") }catch(_: SoapFault) { }
            transfer.finish(error=true)
            val requests=List(server.requestCount) { server.takeRequest() }
            assertEquals(3,requests.size)
            assertTrue(requests[1].body.readUtf8().contains("<ObjectID>PushRoot</ObjectID>"))
            assertTrue(requests.last().body.readUtf8().contains("<ErrCode>1</ErrCode>"))
        } }
    }
    @Test fun optionalNotificationFailureDoesNotPreventEndAndDefaultCancellationIsTwo() = runBlocking {
        MockWebServer().use { server -> CameraHttp().use { http ->
            listOf(MockResponse().setBody("<Envelope/>"),MockResponse().setBody(emptyPage),
                MockResponse().setResponseCode(501),MockResponse().setBody("<Envelope/>")) .forEach(server::enqueue)
            val transfer=SmartphoneTransfer(device(server,false),http)
            transfer.initialize(oneTouch=true);transfer.beginOneTouchCopy();transfer.finish();transfer.finish()
            val requests=List(server.requestCount) { server.takeRequest() }
            assertEquals(4,requests.size);assertTrue(requests.last().body.readUtf8().contains("<ErrCode>2</ErrCode>"))
        } }
    }
    @Test fun missingOptionalDidUsesPushRootButAuthenticationFailureDoesNot() = runBlocking {
        for(code in listOf(404,401)) MockWebServer().use { server -> CameraHttp().use { http ->
            server.enqueue(MockResponse().setBody("<Envelope/>"))
            server.enqueue(MockResponse().setResponseCode(code))
            if(code==404) server.enqueue(MockResponse().setBody(emptyPage))
            server.enqueue(MockResponse().setBody("<Envelope/>"))
            val transfer=SmartphoneTransfer(device(server),http)
            if(code==404) { assertTrue(transfer.initialize(oneTouch=true).isEmpty());assertEquals("PushRoot",transfer.rootId) }
            else try { transfer.initialize(oneTouch=true);fail("authentication failure must propagate") }
                catch(e: io.github.ayatinene.sonycompanion.network.CameraHttpError) { assertEquals(401,e.code) }
            transfer.finish(error=true)
            assertEquals(if(code==404)4 else 3,server.requestCount)
        } }
    }

    @Test fun unsupportedStartCannotBecomeAnAutomaticImport() = runBlocking {
        MockWebServer().use { server -> CameraHttp().use { http ->
            server.enqueue(MockResponse().setResponseCode(404))
            val transfer=SmartphoneTransfer(device(server,false),http)
            try { transfer.initialize(oneTouch=true);fail("must reject unsupported start") }catch(_: IllegalStateException) { }
            transfer.finish();assertEquals(1,server.requestCount)
        } }
    }
}
