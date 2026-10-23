package io.github.ayatinene.sonycompanion

import io.github.ayatinene.sonycompanion.network.CameraHttp
import io.github.ayatinene.sonycompanion.protocol.RemoteCamera
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test

class RemotePostviewTest {
    @Test fun versionOneBodyShutterEventCollectsTakePictureUrlArray()=runBlocking {
        MockWebServer().use { server -> CameraHttp().use { http ->
            server.enqueue(MockResponse().setBody("""{"result":[null,[{"type":"takePicture","takePictureUrl":["http://camera/postview1.jpg","http://camera/postview2.jpg"]}],{"type":"cameraStatus","cameraStatus":"IDLE"}]}"""))
            val (state,urls)=RemoteCamera(server.url("/sony/camera").toString(),http).events()
            assertEquals("IDLE",state.status)
            assertEquals(listOf("http://camera/postview1.jpg","http://camera/postview2.jpg"),urls)
            assertTrue(server.takeRequest().body.readUtf8().contains("\"version\":\"1.0\""))
        } }
    }
    @Test fun bulbAndContinuousShootingEventsCollectPostviewsWithoutThumbnailUrls()=runBlocking {
        MockWebServer().use { server -> CameraHttp().use { http ->
            server.enqueue(MockResponse().setBody("""{"result":[{"type":"bulbShooting","bulbShootingUrl":[{"postviewUrl":"http://camera/bulb.jpg","thumbnailUrl":"http://camera/tn.jpg"}]},{"type":"contShooting","contShootingUrl":[{"postviewUrl":"http://camera/continuous.jpg"}]}]}"""))
            val (_,urls)=RemoteCamera(server.url("/sony/camera").toString(),http).events()
            assertEquals(listOf("http://camera/bulb.jpg","http://camera/continuous.jpg"),urls)
        } }
    }
    @Test fun repeatedPostviewsAreReturnedOnceAndEmptyArraysAreAccepted()=runBlocking {
        MockWebServer().use { server -> CameraHttp().use { http ->
            server.enqueue(MockResponse().setBody("""{"result":[{"type":"takePicture","takePictureUrl":["http://camera/a.jpg","http://camera/a.jpg"]},{"postviewImageUrl":"http://camera/a.jpg"},{"type":"takePicture","takePictureUrl":[]}]}"""))
            assertEquals(listOf("http://camera/a.jpg"),RemoteCamera(server.url("/sony/camera").toString(),http).events().second)
        } }
    }
}
