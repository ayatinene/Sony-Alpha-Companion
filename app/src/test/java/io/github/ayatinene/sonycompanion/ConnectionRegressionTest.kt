package io.github.ayatinene.sonycompanion

import io.github.ayatinene.sonycompanion.data.*
import io.github.ayatinene.sonycompanion.network.*
import io.github.ayatinene.sonycompanion.protocol.*
import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ConnectionRegressionTest {
    @Test fun pushDescriptorWinsRegardlessOfDiscoveryOrder() {
        val base=Device("ILCE-7RM2","SonyImagingDevice",java.net.URI("http://127.0.0.1/dd.xml"),content=java.net.URI("http://127.0.0.1/cds"))
        val push=base.copy(description=java.net.URI("http://127.0.0.1/DmsDescPush.xml"),push=java.net.URI("http://127.0.0.1/push"))
        for(order in listOf(listOf(base,push),listOf(push,base))) {
            val selected=preferCompleteDescriptions(order,false).single()
            assertNotNull(selected.push)
        }
    }
    @Test fun cameraRequestsDoNotReuseLegacyConnections()=runBlocking {
        MockWebServer().use { server ->
            repeat(3) { server.enqueue(MockResponse().setBody("OK")) }
            CameraHttp().use { http -> repeat(3) {
                assertEquals("OK",http.getText(server.url("/file").toString()))
                val request=server.takeRequest()
                assertEquals("close",request.getHeader("Connection"))
                assertEquals(0,request.sequenceNumber)
            } }
        }
    }
    @Test fun cancellingAfterHeadersUnblocksAStalledBody()=runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("x").setHeader("Content-Length",1000000))
            CameraHttp().use { http ->
                val reading=CountDownLatch(1)
                var closed=false
                val job=launch(Dispatchers.IO) {
                    try { http.stream(server.url("/liveview").toString()) { response ->
                        val input=response.body!!.byteStream()
                        assertEquals('x'.code,input.read());reading.countDown()
                        input.read()
                    } } finally { closed=true }
                }
                assertTrue(reading.await(3,TimeUnit.SECONDS))
                withTimeout(3000) { job.cancelAndJoin() }
                assertTrue(closed)
            }
        }
    }
    @Test fun closingClientCancelsBodiesWhoseHeadersAlreadyArrived()=runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("x").setHeader("Content-Length",1000000))
            val http=CameraHttp();val reading=CountDownLatch(1)
            val result=async(Dispatchers.IO) { runCatching {
                http.stream(server.url("/file").toString()) { response ->
                    val input=response.body!!.byteStream();input.read();reading.countDown();input.read()
                }
            } }
            assertTrue(reading.await(3,TimeUnit.SECONDS));http.close()
            assertTrue(withTimeout(3000) { result.await() }.exceptionOrNull() is IOException)
        }
    }
    @Test fun versionOneCurrentFieldsRemainVisibleWithoutWritableApis()=runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"result":[{"type":"cameraStatus","cameraStatus":"IDLE"},{"type":"fNumber","currentFNumber":"8.0"},{"type":"isoSpeedRate","currentIsoSpeedRate":"AUTO"},{"type":"shutterSpeed","currentShutterSpeed":"1/30"},{"type":"focusMode","currentFocusMode":"AF-C"}]}"""))
            CameraHttp().use { http ->
                val (state,_)=RemoteCamera(server.url("/sony/camera").toString(),http).events()
                assertEquals("8.0",state.settings.single { it.key=="FNumber" }.current)
                assertEquals("1/30",state.settings.single { it.key=="ShutterSpeed" }.current)
                assertTrue(state.settings.all { !it.writable })
            }
        }
    }
    @Test fun diagnosticNamesIncludeModeTimestampAndMilliseconds() {
        val name=DiagnosticFiles.name(CameraMode.JPEG,1700000000123)
        assertTrue(name.matches(Regex("AlphaCompanion-jpeg-[0-9]{8}-[0-9]{6}-123\\.json")))
        assertNotEquals(name,DiagnosticFiles.name(CameraMode.JPEG,1700000000124))
    }
    @Test fun crashExportRetainsTheExceptionAndItsCause() {
        val directory=Files.createTempDirectory("alpha-crash").toFile()
        try {
            assertNull(CrashLog.read(directory))
            CrashLog.record(directory,"main",IllegalStateException("close failed",IOException("socket")))
            val text=CrashLog.read(directory)!!
            assertTrue(text.contains("thread=main"));assertTrue(text.contains("close failed"));assertTrue(text.contains("Caused by: java.io.IOException: socket"))
        } finally { directory.deleteRecursively() }
    }
}
