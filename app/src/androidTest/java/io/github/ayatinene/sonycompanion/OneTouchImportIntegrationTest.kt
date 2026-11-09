package io.github.ayatinene.sonycompanion

import android.content.Intent
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.provider.MediaStore
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ayatinene.sonycompanion.data.*
import io.github.ayatinene.sonycompanion.network.CameraHttp
import io.github.ayatinene.sonycompanion.pairing.*
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.*
import okio.Buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Real queue, foreground service and MediaStore against simulated camera HTTP/Wi-Fi callbacks.
 * Passing this suite does not assert physical NFC, system Wi-Fi approval or Sony firmware behavior. */
@RunWith(AndroidJUnit4::class)
class OneTouchImportIntegrationTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val controller get()=(context.applicationContext as CompanionApplication).controller
    private lateinit var originalBackend: PairingBackend<Network>
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var backend: Backend
    private val createdNames=mutableSetOf<String>()
    private class Backend: PairingBackend<Network> {
        @Volatile var events: PairingBackend.Events<Network>?=null
        val releases=AtomicInteger()
        val requests=AtomicInteger()
        override fun request(credentials: PairingCredentials, events: PairingBackend.Events<Network>): Closeable {
            this.events=events;requests.incrementAndGet();return Closeable { releases.incrementAndGet() }
        }
    }
    private fun waitFor(timeout: Long=12_000, condition: ()->Boolean) {
        val end=System.currentTimeMillis()+timeout
        while(System.currentTimeMillis()<end) { if(condition()) return;Thread.sleep(20) }
        fail("one-touch condition was not reached: ${controller.state.value.message}")
    }
    @Before fun setup() {
        runBlocking { controller.disconnect(false) }
        originalBackend=controller.pairingBackend;backend=Backend();controller.pairingBackend=backend
        controller.sessionOverride=null
        scenario=ActivityScenario.launch(MainActivity::class.java)
        controller.setVisible(true)
    }
    @After fun cleanup() {
        runBlocking { controller.disconnect(false) }
        controller.sessionOverride=null;controller.pairingBackend=originalBackend
        controller.photos.list().filter { it.name in createdNames }.forEach { controller.photos.deleteFile(it) }
        scenario.close();controller.setVisible(true)
    }
    private fun jpeg(): ByteArray=ByteArrayOutputStream().use { out ->
        val image=Bitmap.createBitmap(2,2,Bitmap.Config.ARGB_8888)
        image.eraseColor(0xff34789a.toInt());image.compress(Bitmap.CompressFormat.JPEG,95,out);image.recycle();out.toByteArray()
    }
    private inner class Camera(private val did: Boolean=true): Closeable {
        val server=MockWebServer()
        val bytes=jpeg()
        val name="SHARE_${UUID.randomUUID().toString().take(8)}.JPG".also { createdNames+=it }
        val second="SHARE_${UUID.randomUUID().toString().take(8)}.JPG".also { createdNames+=it }
        val downloads=CopyOnWriteArrayList<String>()
        val ends=CopyOnWriteArrayList<String>()
        val browses=CopyOnWriteArrayList<String>()
        val progress=CopyOnWriteArrayList<String>()
        val starts=AtomicInteger()
        val folderReads=CountDownLatch(1)
        val preparationReads=CountDownLatch(1)
        val endStarted=CountDownLatch(1)
        val allowEnd=CountDownLatch(1)
        @Volatile var blockEnd=false
        @Volatile var empty=false
        @Volatile var failFirst=false
        @Volatile var failAll=false
        @Volatile var slow=false
        @Volatile var blockFolder=false
        @Volatile var multi=false
        @Volatile var blockDid=false
        @Volatile var blockRoot=false
        @Volatile var blockStart=false
        @Volatile var mpo=false
        @Volatile var jpegSizeKnown=true
        val device: Device
        init {
            server.start()
            device=Device("Sony","ILCE-7RM2",server.url("/dd.xml").toUri(),content=server.url("/cds").toUri(),
                push=server.url("/push").toUri(),digitalImaging=if(did)server.url("/did.xml").toUri()else null,
                udn="uuid:${UUID.randomUUID()}")
            server.dispatcher=object: Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val action=request.getHeader("SOAPACTION").orEmpty()
                    val body=request.body.readUtf8()
                    return when {
                        action.contains("#X_TransferStart") -> {
                            starts.incrementAndGet()
                            if(blockStart) { preparationReads.countDown();MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE) }
                            else MockResponse().setBody("<Envelope/>")
                        }
                        action.contains("#X_TransferEnd") -> {
                            ends+=body
                            if(blockEnd) { endStarted.countDown();allowEnd.await(5,TimeUnit.SECONDS) }
                            MockResponse().setBody("<Envelope/>")
                        }
                        action.contains("#X_TransferProgress") -> { progress+=body;MockResponse().setBody("<Envelope/>") }
                        action.contains("#X_GetPushRoot") -> MockResponse().setBody("<Envelope/>")
                        request.path=="/did.xml" && blockDid -> { preparationReads.countDown();MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE) }
                        request.path=="/did.xml" -> MockResponse().setBody("<root><X_CurrentContent_URL>"+
                            "<X_CurrentContent_URL_URL profileID=\"JPEG_TN\" type=\"JPG\">${server.url("/thumb/$name")}</X_CurrentContent_URL_URL>"+
                            "<X_CurrentContent_URL_URL profileID=\"UNKNOWN\" type=\"JPG\" size=\"${bytes.size}\" resolution=\"2x2\">${server.url("/ORG_$name")}?item=selected</X_CurrentContent_URL_URL>"+"</X_CurrentContent_URL></root>")
                        action.contains("#Browse") -> {
                            val id=body.substringAfter("<ObjectID>").substringBefore("</ObjectID>");browses+=id
                            when {
                                id=="PushRoot" && blockRoot -> { preparationReads.countDown();MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE) }
                                id=="folder" && blockFolder -> { folderReads.countDown();MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE) }
                                empty -> page("")
                                id=="PushRoot" && multi -> page(item("A",name)+"<container id=\"folder\"><title>selected</title></container>")
                                id=="folder" -> page(item("B",second))
                                else -> page(item("A",name))
                            }
                        }
                        request.path.orEmpty().startsWith("/thumb/") -> MockResponse().setBody(Buffer().write(bytes))
                        request.path.orEmpty().startsWith("/mpo/") -> { downloads+=request.path.orEmpty();MockResponse().setResponseCode(400) }
                        request.path.orEmpty().startsWith("/ORG_") || request.path.orEmpty().startsWith("/file/") -> {
                            val path=request.path.orEmpty();downloads+=path
                            if(failAll || (failFirst && path.contains(name))) MockResponse().setResponseCode(400)
                            else MockResponse().setBody(Buffer().write(bytes)).apply { if(slow) throttleBody(1,1,TimeUnit.SECONDS) }
                        }
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
        }
        private fun item(id: String, name: String): String {
            val title=if(mpo) name.substringBeforeLast('.')+".MPO" else name
            val original=if(mpo) "<res protocolInfo=\"http-get:*:image/mpo:*\" size=\"999999\" resolution=\"6000x4000\">${server.url("/mpo/$title")}</res>" else ""
            val length=if(jpegSizeKnown) " size=\"${bytes.size}\"" else ""
            val profile=if(mpo) "DLNA.ORG_PN=JPEG_LRG" else "*"
            return "<item id=\"$id\"><title>$title</title><albumArtURI>${server.url("/thumb/$name")}</albumArtURI>$original<res protocolInfo=\"http-get:*:image/jpeg:$profile\"$length resolution=\"2x2\">${server.url("/file/$name")}</res></item>"
        }
        private fun page(contents: String): MockResponse {
            val count=Regex("<(item|container) ").findAll(contents).count()
            val xml=("<DIDL-Lite>"+contents+"</DIDL-Lite>").replace("&","&amp;").replace("<","&lt;").replace(">","&gt;")
            return MockResponse().setBody("<Envelope><Body><BrowseResponse><Result>$xml</Result><NumberReturned>$count</NumberReturned><TotalMatches>$count</TotalMatches></BrowseResponse></Body></Envelope>")
        }
        fun connect(): CameraHttp {
            val http=CameraHttp();controller.sessionOverride=CompanionController.SessionOverride(http,listOf(device))
            val previous=backend.events
            assertTrue(controller.pairCamera(PairingCredentials.create("DIRECT-test:ILCE-7RM2","testPass"),PairingSource.NFC))
            waitFor { backend.events!=null && backend.events!==previous }
            backend.events!!.available(requireNotNull(context.getSystemService(ConnectivityManager::class.java).activeNetwork))
            return http
        }
        override fun close() { server.close() }
    }
    private fun completed(http: CameraHttp) {
        waitFor { http.isClosed && backend.releases.get()==backend.requests.get() && !controller.state.value.connected && !controller.state.value.busy && !controller.state.value.oneTouchImport }
        waitFor { !controller.backgroundActiveFlow.value }
    }

    @Test fun manualJpegConnectionWithPushServiceDoesNotAutomaticallyImport() {
        Camera(false).use { camera ->
            val http=CameraHttp()
            controller.sessionOverride=CompanionController.SessionOverride(http,listOf(camera.device))
            assertTrue(controller.connect(CameraMode.JPEG))
            waitFor { controller.state.value.connected && !controller.state.value.busy }
            assertFalse(controller.state.value.oneTouchImport);assertTrue(camera.downloads.isEmpty())
            assertEquals(0,backend.requests.get());assertTrue(camera.progress.isEmpty())
            runBlocking { controller.disconnect(false) }
        }
    }

    @Test fun didCurrentPhotoAutomaticallySavesAndRepeatedTouchUsesExistingPhoto() {
        Camera().use { camera ->
            var http=camera.connect();completed(http)
            waitFor { controller.state.value.message.contains("一触分享完成") }
            assertTrue(camera.browses.isEmpty());assertEquals(1,camera.downloads.size)
            val photo=controller.photos.list().single { it.name==camera.name }
            assertEquals(camera.bytes.size.toLong(),photo.sourceBytes);assertTrue(controller.photos.exists(photo))
            assertEquals(1,backend.releases.get());assertTrue(camera.ends.single().contains("<ErrCode>0</ErrCode>"))
            val oldEvents=backend.events!!
            http=camera.connect();oldEvents.lost(requireNotNull(context.getSystemService(ConnectivityManager::class.java).activeNetwork));completed(http)
            waitFor { controller.state.value.message.contains("已存在 1 张") }
            assertEquals(1,camera.downloads.size);assertEquals(2,camera.ends.size);assertEquals(2,backend.releases.get())
        }
    }
    @Test fun multiSelectionImportsAllSelectedFilesWithoutResumingHistoricalWork() {
        Camera(false).use { camera ->
            camera.multi=true
            val old=CameraFile("unrelated","old.JPG",listOf(Resource(camera.server.url("/unrelated").toString())),date="2020",parent="PhotoRoot")
            TransferQueueStore(File(context.filesDir,"transfer-queues.json")).save(queueScope(camera.device,CameraMode.JPEG),listOf(QueueItem(old)))
            val http=camera.connect();completed(http)
            waitFor { controller.state.value.message.contains("已导入 2 张") }
            assertEquals(setOf("PushRoot","folder"),camera.browses.toSet())
            assertEquals(2,camera.downloads.size);assertFalse(camera.downloads.any { it.contains("unrelated") })
            assertEquals("待恢复",controller.state.value.queue.single { it.file.id=="unrelated" }.state)
            assertEquals(2,controller.photos.list().count { it.name in setOf(camera.name,camera.second) })
            assertTrue(camera.ends.single().contains("<ErrCode>0</ErrCode>"))
        }
    }
    @Test fun newPairingWaitsUntilThePreviousShareHasFinishedEnding() {
        Camera().use { camera ->
            camera.blockEnd=true
            val http=camera.connect()
            try {
                assertTrue(camera.endStarted.await(8,TimeUnit.SECONDS))
                assertTrue(controller.state.value.busy);assertTrue(controller.state.value.oneTouchImport)
                assertFalse(controller.state.value.connected)
                assertFalse(controller.pairCamera(PairingCredentials.create("DIRECT-test:ILCE-7RM2","testPass"),PairingSource.NFC))
                assertEquals(1,backend.requests.get())
            } finally { camera.allowEnd.countDown() }
            completed(http)
        }
    }

    @Test fun capacityFailureRetainsHistoricalTasksAndDownloadsNothing() {
        Camera(false).use { camera ->
            val saved=(1..2000).map { QueueItem(CameraFile("old-$it","old-$it.JPG",date="2020",parent="PhotoRoot")) }
            val store=TransferQueueStore(File(context.filesDir,"transfer-queues.json"))
            val key=queueScope(camera.device,CameraMode.JPEG)
            store.save(key,saved)
            val http=camera.connect();completed(http)
            assertTrue(camera.downloads.isEmpty());assertTrue(camera.ends.single().contains("<ErrCode>1</ErrCode>"))
            assertEquals(2000,store.load(key).size)
            assertTrue(store.load(key).all { it.file.id.startsWith("old-") && it.state=="待恢复" })
        }
    }
    @Test fun cancellingSelectedFolderEnumerationEndsWithTwoWithoutStartingDownloads() {
        Camera(false).use { camera ->
            camera.multi=true;camera.blockFolder=true
            val http=camera.connect();assertTrue(camera.folderReads.await(8,TimeUnit.SECONDS))
            controller.cancelOneTouchImport();completed(http)
            assertTrue(camera.downloads.isEmpty());assertTrue(camera.ends.single().contains("<ErrCode>2</ErrCode>"))
        }
    }
    @Test fun cancellingDuringDidOrInitialPushRootEndsBeforeReleasingWifi() {
        for(did in listOf(true,false)) Camera(did).use { camera ->
            camera.blockDid=did;camera.blockRoot=!did;camera.blockEnd=true
            val releases=backend.releases.get()
            val http=camera.connect()
            try {
                assertTrue(camera.preparationReads.await(8,TimeUnit.SECONDS))
                assertTrue(controller.state.value.connecting);assertFalse(controller.state.value.connected)
                controller.cancelOneTouchImport()
                assertTrue(camera.endStarted.await(3,TimeUnit.SECONDS))
                controller.cancelOneTouchImport() // Repeated taps cannot send a second End.
                assertFalse(http.isClosed);assertEquals(releases,backend.releases.get())
                assertTrue(controller.state.value.busy);assertTrue(controller.backgroundActiveFlow.value)
                assertFalse(controller.pairCamera(PairingCredentials.create("DIRECT-test:ILCE-7RM2","testPass"),PairingSource.NFC))
            } finally { camera.allowEnd.countDown() }
            completed(http)
            assertTrue(controller.state.value.message.contains("已取消"))
            assertFalse(controller.state.value.connecting)
            assertTrue(camera.downloads.isEmpty());assertTrue(camera.progress.isEmpty())
            assertTrue(camera.ends.single().contains("<ErrCode>2</ErrCode>"))
        }
    }
    @Test fun preparationNotificationCancelUsesEndWithTwo() {
        Camera(false).use { camera ->
            camera.blockRoot=true
            val http=camera.connect();assertTrue(camera.preparationReads.await(8,TimeUnit.SECONDS))
            scenario.moveToState(Lifecycle.State.CREATED)
            context.startService(Intent(context,TransferService::class.java).setAction("cancel"))
            completed(http)
            assertTrue(camera.ends.single().contains("<ErrCode>2</ErrCode>"))
            assertTrue(camera.downloads.isEmpty())
        }
    }
    @Test fun cancellationBeforeStartAcknowledgementDoesNotSendAnUnownedEnd() {
        Camera(false).use { camera ->
            camera.blockStart=true
            val http=camera.connect();assertTrue(camera.preparationReads.await(8,TimeUnit.SECONDS))
            controller.cancelOneTouchImport();completed(http)
            assertTrue(camera.ends.isEmpty());assertTrue(camera.browses.isEmpty());assertTrue(camera.downloads.isEmpty())
        }
    }
    @Test fun forcedDisconnectDuringPreparationCannotSendEndOrAffectTheNextShare() {
        Camera(false).use { old -> Camera().use { next ->
            old.blockRoot=true
            val first=old.connect();assertTrue(old.preparationReads.await(8,TimeUnit.SECONDS))
            runBlocking { controller.disconnect(false) }
            assertTrue(first.isClosed);assertTrue(old.ends.isEmpty())
            val fresh=next.connect();completed(fresh)
            assertTrue(next.ends.single().contains("<ErrCode>0</ErrCode>"))
            assertEquals(1,next.downloads.size)
        } }
    }
    @Test fun pushRootMpoDerivativeHasJpegNameMimeAndBytesAndDeduplicates() {
        for(known in listOf(true,false)) Camera(false).use { camera ->
            camera.mpo=true;camera.jpegSizeKnown=known
            val http=camera.connect();completed(http)
            waitFor { controller.state.value.message.contains("一触分享完成") }
            val photo=controller.photos.list().single { it.name==camera.name }
            val uri=Uri.parse(photo.uri)
            assertEquals("image/jpeg",context.contentResolver.getType(uri))
            context.contentResolver.query(uri,arrayOf(MediaStore.Images.Media.DISPLAY_NAME,MediaStore.Images.Media.IS_PENDING),null,null,null)!!.use { row ->
                assertTrue(row.moveToFirst());assertEquals(camera.name,row.getString(0));assertEquals(0,row.getInt(1))
            }
            context.contentResolver.openInputStream(uri)!!.use { assertArrayEquals(camera.bytes,it.readBytes()) }
            assertEquals(camera.bytes.size.toLong(),photo.sourceBytes)
            assertEquals(1,camera.downloads.size);assertFalse(camera.downloads.any { it.startsWith("/mpo/") })
            assertTrue(controller.state.value.queue.single().file.name.endsWith(".MPO"))
            val repeated=camera.connect();completed(repeated)
            waitFor { controller.state.value.message.contains("已存在 1 张") }
            assertEquals(1,camera.downloads.size)
            assertEquals(2,camera.ends.size);assertTrue(camera.ends.all { it.contains("<ErrCode>0</ErrCode>") })
        }
    }

    @Test fun cancelDuringHttpDownloadRemovesPartialPhotoEndsWithTwoAndReleasesWifi() {
        Camera().use { camera ->
            camera.slow=true
            val http=camera.connect()
            waitFor { controller.state.value.transferring && camera.downloads.isNotEmpty() }
            controller.cancelOneTouchImport();completed(http)
            waitFor { controller.state.value.message.contains("已取消") }
            assertTrue(camera.ends.single().contains("<ErrCode>2</ErrCode>"))
            assertFalse(controller.photos.list().any { it.name==camera.name });assertEquals(1,backend.releases.get())
        }
    }
    @Test fun backgroundNotificationCancelUsesTheSameGracefulShareCancellation() {
        Camera().use { camera ->
            camera.slow=true
            val http=camera.connect()
            waitFor { controller.state.value.transferring && camera.downloads.isNotEmpty() }
            scenario.moveToState(Lifecycle.State.CREATED)
            context.startService(Intent(context,TransferService::class.java).setAction("cancel"))
            completed(http)
            assertTrue(camera.ends.single().contains("<ErrCode>2</ErrCode>"))
            assertFalse(controller.photos.list().any { it.name==camera.name })
        }
    }
    @Test fun wifiLossCancelsTheImportWithoutClaimingCompletionOrLeavingPendingPhoto() {
        Camera().use { camera ->
            camera.slow=true
            val http=camera.connect();waitFor { controller.state.value.transferring && camera.downloads.isNotEmpty() }
            backend.events!!.lost(requireNotNull(context.getSystemService(ConnectivityManager::class.java).activeNetwork))
            completed(http)
            assertFalse(controller.state.value.message.contains("一触分享完成"));assertTrue(camera.ends.isEmpty())
            assertFalse(controller.photos.list().any { it.name==camera.name });assertEquals(1,backend.releases.get())
        }
    }
    @Test fun emptySharedSetEndsWithFailureAndDoesNotFetchAnEntireLibrary() {
        Camera(false).use { camera ->
            camera.empty=true
            val http=camera.connect();completed(http)
            assertEquals(listOf("PushRoot"),camera.browses.toList());assertTrue(camera.downloads.isEmpty())
            assertTrue(camera.ends.single().contains("<ErrCode>1</ErrCode>"))
            assertFalse(controller.state.value.message.contains("一触分享完成"))
        }
    }
    @Test fun partialFailureRetainsSuccessfulPhotoAndUsesTheObservedZeroEndCode() {
        Camera(false).use { camera ->
            camera.multi=true;camera.failFirst=true
            val http=camera.connect();completed(http)
            waitFor { controller.state.value.message.contains("部分完成") }
            assertTrue(camera.ends.single().contains("<ErrCode>0</ErrCode>"))
            assertFalse(controller.photos.list().any { it.name==camera.name })
            assertTrue(controller.photos.list().any { it.name==camera.second })
            assertEquals(1,controller.state.value.queue.count { it.state=="失败" })
        }
    }
    @Test fun allFailedFilesEndWithOneAndNeverPublishASavedPhoto() {
        Camera().use { camera ->
            camera.failAll=true
            val http=camera.connect();completed(http)
            waitFor { controller.state.value.message.contains("一触分享失败") }
            assertTrue(camera.ends.single().contains("<ErrCode>1</ErrCode>"))
            assertFalse(controller.photos.list().any { it.name==camera.name })
        }
    }
    @Test fun disconnectWhileListingCannotCloseOrPublishIntoANewerShare() {
        Camera(false).use { old -> Camera().use { next ->
            old.multi=true;old.blockFolder=true
            val first=old.connect();assertTrue(old.folderReads.await(8,TimeUnit.SECONDS))
            runBlocking { controller.disconnect(false) }
            assertTrue(first.isClosed);assertTrue(old.ends.isEmpty())
            next.slow=true
            val fresh=next.connect();waitFor { controller.state.value.transferring && next.downloads.isNotEmpty() }
            Thread.sleep(200)
            assertTrue(controller.state.value.connected);assertTrue(controller.state.value.oneTouchImport);assertFalse(fresh.isClosed)
            controller.cancelOneTouchImport();completed(fresh)
            assertEquals(2,backend.releases.get());assertTrue(next.ends.single().contains("<ErrCode>2</ErrCode>"))
        } }
    }
}
