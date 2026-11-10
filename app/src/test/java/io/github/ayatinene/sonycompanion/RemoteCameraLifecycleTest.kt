package io.github.ayatinene.sonycompanion

import io.github.ayatinene.sonycompanion.network.CameraHttp
import io.github.ayatinene.sonycompanion.protocol.RemoteCamera
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** The remote end sequence: `finish` stops live view and only leaves record mode this app entered. */
class RemoteCameraLifecycleTest {
    private val apis = """{"result":[["startRecMode","startLiveview","stopLiveview","stopRecMode","getVersions","getApplicationInfo"]]}"""
    private fun methods(server: MockWebServer, count: Int) =
        (1..count).map { JSONObject(server.takeRequest().body.readUtf8()).getString("method") }

    @Test fun startRecModeOwnedIsStoppedOnFinish() = runBlocking {
        MockWebServer().use { server -> CameraHttp().use { http ->
            server.enqueue(MockResponse().setBody(apis))
            server.enqueue(MockResponse().setBody("""{"result":[0]}"""))                 // startRecMode
            server.enqueue(MockResponse().setBody(apis))                                 // poll after start
            server.enqueue(MockResponse().setBody("""{"result":[["1.0"]]}"""))           // getVersions
            server.enqueue(MockResponse().setBody("""{"result":["Smart Remote","1.0"]}""")) // getApplicationInfo
            server.enqueue(MockResponse().setBody(apis))                                 // final refresh
            server.enqueue(MockResponse().setBody("""{"result":[0]}"""))                 // stopRecMode
            val camera=RemoteCamera(server.url("/sony/camera").toString(),http)
            camera.initialize()
            camera.finish()
            val methods=methods(server,7)
            assertTrue(methods.contains("startRecMode"))
            assertEquals("stopRecMode",methods.last())
        } }
    }

    @Test fun uncertainStartRecModeIsNotBlindlyStopped() = runBlocking {
        MockWebServer().use { server -> CameraHttp().use { http ->
            server.enqueue(MockResponse().setBody(apis))
            server.enqueue(MockResponse().setBody("not json"))                           // lost/invalid start response
            val camera=RemoteCamera(server.url("/sony/camera").toString(),http)
            try { camera.initialize(); fail("an invalid start response must propagate") } catch(_: Exception) {}
            camera.finish()
            val methods=methods(server,2)
            assertFalse("an uncertain start must not be undone",methods.contains("stopRecMode"))
        } }
    }

    @Test fun cameraWithoutStartRecModeNeverReceivesStopRecMode() = runBlocking {
        MockWebServer().use { server -> CameraHttp().use { http ->
            val liveOnly="""{"result":[["startLiveview","stopLiveview"]]}"""
            server.enqueue(MockResponse().setBody(liveOnly))
            server.enqueue(MockResponse().setBody("""{"result":[["1.0"]]}"""))  // getVersions
            server.enqueue(MockResponse().setBody(liveOnly))                    // final refresh
            val camera=RemoteCamera(server.url("/sony/camera").toString(),http)
            camera.initialize()
            camera.finish()
            val methods=methods(server,3)
            assertFalse(methods.contains("stopRecMode"))
        } }
    }

    @Test fun finishStopsLiveViewBeforeRecMode() = runBlocking {
        MockWebServer().use { server -> CameraHttp().use { http ->
            server.enqueue(MockResponse().setBody(apis))
            server.enqueue(MockResponse().setBody("""{"result":[0]}"""))                 // startRecMode
            server.enqueue(MockResponse().setBody(apis))
            server.enqueue(MockResponse().setBody("""{"result":[["1.0"]]}"""))           // getVersions
            server.enqueue(MockResponse().setBody("""{"result":["Smart Remote","1.0"]}""")) // getApplicationInfo
            server.enqueue(MockResponse().setBody(apis))                                 // final refresh
            server.enqueue(MockResponse().setBody("""{"result":["http://camera/liveview"]}""")) // startLiveview
            server.enqueue(MockResponse().setBody("""{"result":[0]}"""))                 // stopLiveview
            server.enqueue(MockResponse().setBody("""{"result":[0]}"""))                 // stopRecMode
            val camera=RemoteCamera(server.url("/sony/camera").toString(),http)
            camera.initialize()
            camera.liveView()
            camera.finish()
            val methods=methods(server,9)
            assertEquals(listOf("startLiveview","stopLiveview","stopRecMode"),methods.takeLast(3))
        } }
    }

    @Test fun aDisallowedRemoteSendsNoCleanupRpcFromItsFinally() = runBlocking {
        MockWebServer().use { server -> CameraHttp().use { http ->
            val liveOnly="""{"result":[["startLiveview","stopLiveview"]]}"""
            server.enqueue(MockResponse().setBody(liveOnly))                             // refresh
            server.enqueue(MockResponse().setBody("""{"result":["http://camera/liveview"]}""")) // startLiveview
            val camera=RemoteCamera(server.url("/sony/camera").toString(),http)
            camera.refresh(); camera.liveView()
            camera.disallowCleanup()
            // A forced teardown must not send a cleanup RPC even though live view was started.
            camera.stopLiveView(); camera.finish()
            assertEquals(2,server.requestCount)
        } }
    }
}
