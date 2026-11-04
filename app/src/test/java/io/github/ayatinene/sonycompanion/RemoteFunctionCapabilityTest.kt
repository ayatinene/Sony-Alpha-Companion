package io.github.ayatinene.sonycompanion

import io.github.ayatinene.sonycompanion.data.RemoteFunction
import io.github.ayatinene.sonycompanion.network.CameraHttp
import io.github.ayatinene.sonycompanion.protocol.RemoteCamera
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class RemoteFunctionCapabilityTest {
    @Test fun declaredSwitchWorksWhenMissingFromAvailableList()=runBlocking {
        MockWebServer().use { server -> CameraHttp().use { http ->
            val requests=CopyOnWriteArrayList<JSONObject>()
            server.dispatcher=object:Dispatcher() {
                override fun dispatch(request:RecordedRequest):MockResponse {
                    val json=JSONObject(request.body.readUtf8());requests+=json
                    val result=when(json.getString("method")) {
                        "getAvailableApiList" -> "[[\"getCameraFunction\"]]"
                        "getVersions" -> "[[\"1.0\",\"1.3\"]]"
                        "getMethodTypes" -> "[[\"setCameraFunction\",[\"string\"],[\"int\"],\"1.0\"]]"
                        "getSupportedCameraFunction" -> "[[\"Contents Transfer\",\"Remote Shooting\"]]"
                        "getCameraFunction" -> "[\"Remote Shooting\"]"
                        "setCameraFunction" -> "[0]"
                        else -> error("Unexpected method: $json")
                    }
                    val key = if (json.getString("method") == "getMethodTypes") "results" else "result"
                    return MockResponse().setBody("{\"$key\":$result}")
                }
            }
            val camera=RemoteCamera(server.url("/sony/camera").toString(),http)
            camera.refresh()
            assertTrue(camera.gallerySupported())
            assertEquals(RemoteFunction.REMOTE_SHOOTING,camera.cameraFunction())
            camera.setCameraFunction(RemoteFunction.CONTENTS_TRANSFER)
            val switch=requests.single { it.getString("method")=="setCameraFunction" }
            assertEquals("1.0",switch.getString("version"))
            assertEquals(RemoteFunction.CONTENTS_TRANSFER,switch.getJSONArray("params").getString(0))
        } }
    }

    @Test fun unsupportedCameraNeverReceivesTheSwitch()=runBlocking {
        MockWebServer().use { server -> CameraHttp().use { http ->
            server.enqueue(MockResponse().setBody("{\"result\":[[\"1.0\"]]}"))
            server.enqueue(MockResponse().setBody("{\"result\":[[\"getEvent\",\"object\",[],\"1.0\"]]}"))
            val camera=RemoteCamera(server.url("/sony/camera").toString(),http)
            assertFalse(camera.gallerySupported())
            try { camera.setCameraFunction(RemoteFunction.CONTENTS_TRANSFER);fail() }
            catch(_:IllegalStateException) { }
            assertEquals(2,server.requestCount)
        } }
    }
}
