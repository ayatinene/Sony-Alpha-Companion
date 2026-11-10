package io.github.ayatinene.sonycompanion

import io.github.ayatinene.sonycompanion.network.CameraHttp
import io.github.ayatinene.sonycompanion.protocol.RemoteCamera
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test

class RemoteLiveViewSessionTest {
    @Test fun startedLiveViewIsStoppedOnceBeforeTheNextStart()=runBlocking {
        MockWebServer().use { server -> CameraHttp().use { http ->
            server.enqueue(MockResponse().setBody("""{"result":[["startLiveview","stopLiveview"]]}"""))
            server.enqueue(MockResponse().setBody("""{"result":["http://camera/liveview"]}"""))
            server.enqueue(MockResponse().setBody("""{"result":[0]}"""))
            server.enqueue(MockResponse().setBody("""{"result":["http://camera/liveview2"]}"""))
            server.enqueue(MockResponse().setBody("""{"result":[0]}"""))
            val camera=RemoteCamera(server.url("/sony/camera").toString(),http)
            camera.refresh(); camera.stopLiveView(); camera.liveView(); camera.stopLiveView(); camera.stopLiveView()
            camera.liveView(); camera.stopLiveView()
            assertEquals(5,server.requestCount)
            assertEquals(listOf("getAvailableApiList","startLiveview","stopLiveview","startLiveview","stopLiveview"),
                (1..5).map { org.json.JSONObject(server.takeRequest().body.readUtf8()).getString("method") })
        } }
    }
    @Test fun cameraWithoutStopLiveviewDoesNotReceiveAnUnsupportedCommand()=runBlocking {
        MockWebServer().use { server -> CameraHttp().use { http ->
            server.enqueue(MockResponse().setBody("""{"result":[["startLiveview"]]}"""))
            server.enqueue(MockResponse().setBody("""{"result":["http://camera/liveview"]}"""))
            val camera=RemoteCamera(server.url("/sony/camera").toString(),http)
            camera.refresh(); camera.liveView(); camera.stopLiveView()
            assertEquals(2,server.requestCount)
        } }
    }
}
