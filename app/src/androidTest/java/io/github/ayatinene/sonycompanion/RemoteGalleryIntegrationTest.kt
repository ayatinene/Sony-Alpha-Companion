package io.github.ayatinene.sonycompanion

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ayatinene.sonycompanion.data.*
import io.github.ayatinene.sonycompanion.network.CameraHttp
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections

/**
 * Drives the real [CompanionController] through the same-connection gallery against a method-dispatching
 * MockWebServer (not a fragile FIFO queue): entering stops live view before setCameraFunction and never
 * stops record mode, interval/recording refuse the switch, pagination appends, and returning tolerates a
 * transient getCameraFunction error while getEvent reports idle + live view.
 */
@RunWith(AndroidJUnit4::class)
class RemoteGalleryIntegrationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val controller get() = (context.applicationContext as CompanionApplication).controller

    @Before fun setUp() { controller.sessionOverride=null; runBlocking { controller.disconnect(false) } }
    @After fun tearDown() { runBlocking { controller.disconnect(false) }; controller.sessionOverride=null; controller.setVisible(true) }

    private fun waitUntil(timeoutMillis: Long = 10_000, condition: () -> Boolean) {
        val end=System.currentTimeMillis()+timeoutMillis
        while(System.currentTimeMillis()<end) { if(condition()) return; Thread.sleep(20) }
        fail("condition not met within ${timeoutMillis}ms")
    }

    private fun device(server: MockWebServer) = Device("ILCE-7RM2","ILCE-7RM2",server.url("/dd.xml").toUri(),
        remote=server.url("/sony/camera").toUri(), avContent=server.url("/sony/avContent").toUri(),
        udn="uuid:11111111-2222-3333-4444-555555555555")

    /** One valid live-view frame (8-byte common + 128-byte payload header + a 1x1 PNG payload). */
    private fun liveFrame(png: ByteArray): ByteArray {
        val header=ByteArray(128)
        header[0]=0x24; header[1]=0x35; header[2]=0x68; header[3]=0x79
        header[4]=((png.size shr 16) and 0xFF).toByte(); header[5]=((png.size shr 8) and 0xFF).toByte(); header[6]=(png.size and 0xFF).toByte()
        val common=ByteArray(7); common[0]=1
        return byteArrayOf(0xFF.toByte()) + common + header + png
    }

    private val png = android.util.Base64.decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==",
        android.util.Base64.DEFAULT)

    private inner class GalleryCamera(private val server: MockWebServer) : Dispatcher() {
        @Volatile var function = "Remote Shooting"
        @Volatile var status = "IDLE"
        @Volatile var cameraFunctionFails = false
        val calls = Collections.synchronizedList(mutableListOf<String>())
        val contentCount = 45
        val dateUri = "storage:memorycard1?path=2026-01-02"
        private val apis = JSONArray(listOf("startRecMode","actTakePicture","stopRecMode","startLiveview","stopLiveview",
            "getVersions","getApplicationInfo","getSupportedCameraFunction","getCameraFunction","getEvent"))

        private fun ok(result: JSONArray) = MockResponse().setBody(JSONObject().put("result",result).put("id",1).toString())
        private fun fail(code: Int) = MockResponse().setBody(JSONObject().put("error",JSONArray().put(code).put("err")).put("id",1).toString())
        private fun row(name: String, version: String) = JSONArray().put(name).put("object").put(JSONArray()).put(version)

        override fun dispatch(request: RecordedRequest): MockResponse {
            val path=request.path.orEmpty()
            if(path.startsWith("/liveview")) { calls += "GET /liveview"; return MockResponse().setBody(Buffer().write(liveFrame(png))) }
            val json=runCatching { JSONObject(request.body.readUtf8()) }.getOrNull() ?: return ok(JSONArray().put(0))
            val method=json.optString("method"); calls += method
            return if(path.contains("avContent")) avContent(method,json) else camera(method,json)
        }

        private fun camera(method: String, json: JSONObject): MockResponse = when(method) {
            "getAvailableApiList" -> ok(JSONArray().put(apis))
            "startRecMode","stopRecMode","stopLiveview" -> ok(JSONArray().put(0))
            "setCameraFunction" -> { function=json.optJSONArray("params")?.optString(0).orEmpty(); ok(JSONArray().put(0)) }
            "getVersions" -> ok(JSONArray().put(JSONArray().put("1.0")))
            "getApplicationInfo" -> ok(JSONArray().put("Smart Remote").put("1.0"))
            "getSupportedCameraFunction" -> ok(JSONArray().put(JSONArray().put("Remote Shooting").put("Contents Transfer")))
            "getMethodTypes" -> ok(JSONArray().put(row("setCameraFunction","1.0")).put(row("getEvent","1.0")))
            "getCameraFunction" -> if(cameraFunctionFails) fail(12) else ok(JSONArray().put(function))
            "getEvent" -> ok(JSONArray().put(JSONObject().put("type","cameraStatus").put("cameraStatus",status))
                .put(JSONObject().put("type","availableApiList").put("names",apis)))
            "startLiveview" -> ok(JSONArray().put(server.url("/liveview").toString()))
            "actTakePicture" -> ok(JSONArray().put(JSONArray()))
            else -> ok(JSONArray().put(0))
        }

        private fun avContent(method: String, json: JSONObject): MockResponse = when(method) {
            "getVersions" -> ok(JSONArray().put(JSONArray().put("1.0").put("1.1").put("1.2").put("1.3")))
            "getMethodTypes" -> {
                val version=json.optJSONArray("params")?.optString(0)
                val rows=when(version) { "1.2" -> JSONArray().put(row("getContentCount","1.2")); "1.3" -> JSONArray().put(row("getContentList","1.3")); else -> JSONArray() }
                ok(JSONArray().put(rows))
            }
            "getSchemeList" -> ok(JSONArray().put(JSONArray().put(JSONObject().put("scheme","storage"))))
            "getSourceList" -> ok(JSONArray().put(JSONArray().put("storage:memorycard1")))
            "getContentCount" -> {
                val uri=json.optJSONArray("params")?.optJSONObject(0)?.optString("uri")
                ok(JSONArray().put(JSONObject().put("count",if(uri==dateUri) contentCount else 1)))
            }
            "getContentList" -> {
                val params=json.optJSONArray("params")
                val spec=params?.optJSONObject(0) ?: params?.optJSONObject(1)
                val start=spec?.optInt("stIdx") ?: 0
                val count=spec?.optInt("cnt") ?: 20
                val items=JSONArray()
                val uri=spec?.optString("uri").orEmpty()
                if(uri=="storage:memorycard1") {
                    if(start==0) items.put(JSONObject().put("uri",dateUri)
                        .put("title","2026-01-02").put("contentKind","date").put("isBrowsable","true"))
                } else if(uri==dateUri) {
                    for(i in start until minOf(start+count,contentCount)) {
                        val name="DSC%05d.JPG".format(i)
                        items.put(JSONObject().put("uri","content://$i").put("contentKind","still").put("createdTime","2026-01-02T03:04:05Z")
                            .put("content",JSONObject()
                                .put("original",JSONArray().put(JSONObject().put("url","http://cam/$name").put("fileName",name).put("fileSize",1000).put("stillObject","jpeg")))
                                .put("largeUrl","http://cam/pv_$i.jpg")))
                    }
                }
                ok(JSONArray().put(items))
            }
            else -> ok(JSONArray().put(0))
        }
    }

    private fun connectGallery(server: MockWebServer, camera: GalleryCamera, visible: Boolean = false): CameraHttp {
        controller.setVisible(visible)
        val http=CameraHttp()
        controller.sessionOverride=CompanionController.SessionOverride(http,listOf(device(server)))
        server.dispatcher=camera
        assertTrue(controller.connect(CameraMode.REMOTE))
        waitUntil { controller.state.value.connected && controller.state.value.remote.canBrowseGallery }
        return http
    }

    @Test fun intervalAndRecordingRefuseTheSwitch() {
        MockWebServer().use { server ->
            val camera=GalleryCamera(server)
            connectGallery(server,camera)
            controller.interval(3,2)
            waitUntil { controller.state.value.intervalRemaining>0 }
            controller.openRemoteGallery()
            Thread.sleep(300)
            assertFalse("an interval run must refuse the switch",controller.state.value.remote.galleryActive)
            assertFalse(camera.calls.contains("setCameraFunction"))
            controller.stopInterval()
        }
    }

    @Test fun recordingStatusRefusesTheSwitch() {
        MockWebServer().use { server ->
            val camera=GalleryCamera(server)
            camera.status="MovieRecording"
            connectGallery(server,camera,visible=true)
            waitUntil { controller.state.value.remote.status.contains("MovieRecording") }
            controller.openRemoteGallery()
            Thread.sleep(300)
            assertFalse("recording must refuse the switch",controller.state.value.remote.galleryActive)
            assertFalse(camera.calls.contains("setCameraFunction"))
        }
    }

    @Test fun enterStopsLiveViewBeforeSetCameraFunctionAndNeverStopsRecMode() {
        MockWebServer().use { server ->
            val camera=GalleryCamera(server)
            connectGallery(server,camera,visible=true)
            // Wait until the stream is actually consumed, so liveViewStarted is set and the live-view
            // finally really sends stopLiveview when the switch cancels it.
            waitUntil { camera.calls.contains("GET /liveview") }
            controller.openRemoteGallery()
            waitUntil { controller.state.value.remote.galleryActive && !controller.state.value.remote.remoteSwitching }
            val calls=camera.calls.toList()
            val stop=calls.indexOf("stopLiveview")
            val set=calls.indexOf("setCameraFunction")
            assertTrue("live view must have been stopped",stop>=0)
            assertTrue("setCameraFunction must follow stopLiveview",set>stop)
            assertFalse("a mode switch must never stop record mode",calls.contains("stopRecMode"))
            assertTrue("first page published immediately",controller.state.value.files.isNotEmpty())
            assertEquals("1", controller.state.value.remote.totalFiles?.toString() ?: "null")
            assertEquals("2026-01-02",controller.state.value.files.single().name)
            assertTrue(controller.state.value.files.single().container)
        }
    }

    @Test fun loadMoreAppendsTheNextPage() {
        MockWebServer().use { server ->
            val camera=GalleryCamera(server)
            connectGallery(server,camera)
            controller.openRemoteGallery()
            waitUntil { controller.state.value.remote.galleryActive && !controller.state.value.remote.remoteSwitching }
            assertEquals(1,controller.state.value.files.size)
            val folder=controller.state.value.files.single()
            assertTrue(folder.container)
            controller.browse(folder.id)
            waitUntil { controller.state.value.folder==folder.id && !controller.state.value.remote.loadingMore }
            assertEquals(20,controller.state.value.files.size)
            assertTrue(controller.state.value.remote.hasMoreFiles)
            controller.loadMoreRemoteFiles()
            waitUntil { controller.state.value.files.size==40 }
            assertTrue(controller.state.value.remote.hasMoreFiles)
            controller.loadMoreRemoteFiles()
            waitUntil { controller.state.value.files.size==45 }
            assertFalse("the declared count is reached",controller.state.value.remote.hasMoreFiles)
            assertEquals(45,controller.state.value.remote.loadedContentCount)
            controller.up()
            waitUntil { controller.state.value.folder=="storage:memorycard1" && !controller.state.value.remote.loadingMore }
            assertEquals(listOf("2026-01-02"),controller.state.value.files.map { it.name })
            assertTrue(controller.state.value.galleryPath.isEmpty())
            assertEquals(1, camera.calls.count { it=="setCameraFunction" })
        }
    }

    @Test fun returnToleratesATransientGetCameraFunctionError() {
        MockWebServer().use { server ->
            val camera=GalleryCamera(server)
            connectGallery(server,camera,visible=true)
            controller.openRemoteGallery()
            waitUntil { controller.state.value.remote.galleryActive && !controller.state.value.remote.remoteSwitching }
            camera.cameraFunctionFails=true
            val liveViewsBefore=camera.calls.count { it=="startLiveview" }
            controller.returnToRemoteCapture()
            waitUntil { !controller.state.value.remote.galleryActive }
            assertFalse(controller.state.value.remote.remoteSwitching)
            waitUntil { camera.calls.count { it=="startLiveview" }>liveViewsBefore }
        }
    }
}
