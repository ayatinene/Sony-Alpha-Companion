package io.github.ayatinene.sonycompanion

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ayatinene.sonycompanion.data.*
import io.github.ayatinene.sonycompanion.network.CameraHttp
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * Drives the real [CompanionController] against local servers through the [CompanionController.SessionOverride]
 * seam, covering the lifecycle the unit tests cannot: a failed initialize ends the session and releases
 * the transport, forced cancel/disconnect close the transport immediately and stop all requests, a
 * normal remote disconnect ends the record mode this app entered, a blocking request is cancelled
 * instead of waited on, and a new session is never touched by the old teardown.
 */
@RunWith(AndroidJUnit4::class)
class ControllerLifecycleIntegrationTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val controller get()=(context.applicationContext as CompanionApplication).controller

    @Before fun setUp() { controller.sessionOverride=null; runBlocking { controller.disconnect(false) } }
    @After fun tearDown() { runBlocking { controller.disconnect(false) }; controller.sessionOverride=null; controller.setVisible(true) }

    private fun waitUntil(timeoutMillis: Long = 8_000, condition: () -> Boolean) {
        val end=System.currentTimeMillis()+timeoutMillis
        while(System.currentTimeMillis()<end) { if(condition()) return; Thread.sleep(20) }
        fail("condition not met within ${timeoutMillis}ms")
    }
    private fun emptyPage()="""<Envelope><Body><BrowseResponse><Result></Result><NumberReturned>0</NumberReturned><TotalMatches>0</TotalMatches></BrowseResponse></Body></Envelope>"""
    private fun fault(code: String)="""<Envelope><Body><Fault><detail><UPnPError><errorCode>$code</errorCode><errorDescription>Unsupported</errorDescription></UPnPError></detail></Fault></Body></Envelope>"""
    private fun jpegDevice(server: MockWebServer, push: Boolean)=
        Device("ILCE-7RM2","ILCE-7RM2",server.url("/dd.xml").toUri(),content=server.url("/cds").toUri(),
            push=if(push) server.url("/push").toUri() else null)
    private fun remoteDevice(server: MockWebServer)=
        Device("ILCE-7RM2","ILCE-7RM2",server.url("/dd.xml").toUri(),remote=server.url("/sony/camera").toUri())
    private fun rpcMethods(server: MockWebServer): List<String> {
        val out=mutableListOf<String>()
        repeat(server.requestCount) {
            val request=server.takeRequest()
            runCatching { JSONObject(request.body.readUtf8()).optString("method") }
                .getOrNull()?.takeIf(String::isNotBlank)?.let(out::add)
        }
        return out
    }
    private fun soapActions(server: MockWebServer): List<String> {
        val out=mutableListOf<String>()
        repeat(server.requestCount) { out += server.takeRequest().getHeader("SOAPACTION").orEmpty() }
        return out
    }

    /** Initialize responses for a remote that advertises record mode and a capture API, so the poll
     * loop breaks after one refresh. */
    private fun enqueueRemoteInitialize(server: MockWebServer) {
        val apis="""{"result":[["startRecMode","actTakePicture","stopRecMode","getVersions","getApplicationInfo"]]}"""
        server.enqueue(MockResponse().setBody(apis))                                 // refresh
        server.enqueue(MockResponse().setBody("""{"result":[0]}"""))                 // startRecMode
        server.enqueue(MockResponse().setBody(apis))                                 // poll (breaks)
        server.enqueue(MockResponse().setBody("""{"result":[["1.0"]]}"""))           // getVersions
        server.enqueue(MockResponse().setBody("""{"result":["Smart Remote","1.0"]}""")) // getApplicationInfo
        server.enqueue(MockResponse().setBody(apis))                                 // final refresh
    }

    @Test fun startSuccessBrowseFailureEndsSessionAndClosesTransport() {
        MockWebServer().use { server ->
            val http=CameraHttp()
            server.enqueue(MockResponse().setBody("<Envelope><Body/></Envelope>"))     // X_TransferStart ok
            server.enqueue(MockResponse().setResponseCode(500).setBody(fault("501")))  // PushRoot fails
            server.enqueue(MockResponse().setResponseCode(500).setBody(fault("501")))  // PhotoRoot fails
            server.enqueue(MockResponse().setBody("<Envelope><Body/></Envelope>"))     // X_TransferEnd
            controller.sessionOverride=CompanionController.SessionOverride(http,listOf(jpegDevice(server,true)))
            assertTrue(controller.connect(CameraMode.JPEG))
            waitUntil { http.isClosed && !controller.state.value.connecting && !controller.state.value.busy }
            assertFalse(controller.state.value.connected)
            val actions=soapActions(server)
            assertTrue(actions.any { it.contains("X_TransferStart") })
            assertTrue("a failed initialize must still end the started session",actions.any { it.contains("X_TransferEnd") })
        }
    }

    @Test fun cancelBackgroundWorkClosesTransportImmediatelyAndStopsRequests() {
        MockWebServer().use { server ->
            val http=CameraHttp()
            server.enqueue(MockResponse().setBody(emptyPage()))  // browse PushRoot
            controller.sessionOverride=CompanionController.SessionOverride(http,listOf(jpegDevice(server,false)))
            assertTrue(controller.connect(CameraMode.JPEG))
            waitUntil { controller.state.value.connected }
            val before=server.requestCount
            controller.cancelBackgroundWork()
            assertTrue("a forced cancel must close the transport immediately",http.isClosed)
            assertFalse(controller.state.value.connected)
            assertFalse("busy must be cleared by a forced cancel",controller.state.value.busy)
            Thread.sleep(400)
            assertEquals("no request may reach the camera after cancel",before,server.requestCount)
        }
    }

    @Test fun forcedDisconnectSendsNoCleanupRpc() {
        MockWebServer().use { server ->
            val http=CameraHttp()
            server.enqueue(MockResponse().setBody("<Envelope><Body/></Envelope>"))  // X_TransferStart
            server.enqueue(MockResponse().setBody(emptyPage()))                     // browse
            controller.sessionOverride=CompanionController.SessionOverride(http,listOf(jpegDevice(server,true)))
            assertTrue(controller.connect(CameraMode.JPEG))
            waitUntil { controller.state.value.connected }
            runBlocking { controller.disconnect(false) }
            assertTrue(http.isClosed)
            assertFalse("a forced disconnect must send no cleanup RPC",soapActions(server).any { it.contains("X_TransferEnd") })
        }
    }

    @Test fun normalRemoteDisconnectStopsRecordModeOwnedByTheApp() {
        MockWebServer().use { server ->
            controller.setVisible(false)   // suppress event/live polling so responses are deterministic
            val http=CameraHttp()
            enqueueRemoteInitialize(server)
            server.enqueue(MockResponse().setBody("""{"result":[0]}"""))  // stopRecMode
            controller.sessionOverride=CompanionController.SessionOverride(http,listOf(remoteDevice(server)))
            assertTrue(controller.connect(CameraMode.REMOTE))
            waitUntil { controller.state.value.connected }
            runBlocking { controller.disconnect(true) }
            assertTrue(http.isClosed)
            assertEquals("a normal remote disconnect ends the record mode this app entered","stopRecMode",rpcMethods(server).last())
        }
    }

    @Test fun forcedRemoteDisconnectSendsNoCleanupRpc() {
        MockWebServer().use { server ->
            controller.setVisible(false)
            val http=CameraHttp()
            enqueueRemoteInitialize(server)
            controller.sessionOverride=CompanionController.SessionOverride(http,listOf(remoteDevice(server)))
            assertTrue(controller.connect(CameraMode.REMOTE))
            waitUntil { controller.state.value.connected }
            val before=server.requestCount
            runBlocking { controller.disconnect(false) }
            assertTrue(http.isClosed)
            Thread.sleep(300)
            assertEquals("a forced remote disconnect sends no cleanup RPC",before,server.requestCount)
        }
    }

    @Test fun queuedCapturesAreSerializedToTheSameCamera() {
        MockWebServer().use { server ->
            controller.setVisible(false)
            val http=CameraHttp()
            enqueueRemoteInitialize(server)
            server.enqueue(MockResponse().setBody("""{"result":[[]]}"""))  // takePicture #1
            server.enqueue(MockResponse().setBody("""{"result":[[]]}"""))  // takePicture #2
            controller.sessionOverride=CompanionController.SessionOverride(http,listOf(remoteDevice(server)))
            assertTrue(controller.connect(CameraMode.REMOTE))
            waitUntil { controller.state.value.connected }
            controller.capture(); controller.capture()
            waitUntil { server.requestCount>=8 }
            assertEquals(listOf("actTakePicture","actTakePicture"),rpcMethods(server).takeLast(2))
        }
    }

    @Test fun normalRemoteDisconnectDoesNotStopRecordModeWhileACaptureIsInFlight() {
        MockWebServer().use { server ->
            controller.setVisible(false)
            val http=CameraHttp()
            enqueueRemoteInitialize(server)
            server.enqueue(MockResponse().setBody("""{"result":[[]]}""").setBodyDelay(30,TimeUnit.SECONDS)) // capture hangs
            controller.sessionOverride=CompanionController.SessionOverride(http,listOf(remoteDevice(server)))
            assertTrue(controller.connect(CameraMode.REMOTE))
            waitUntil { controller.state.value.connected }
            controller.capture()
            Thread.sleep(250)  // the capture request has been sent and is blocked on its body
            runBlocking { controller.disconnect(true) }
            assertTrue(http.isClosed)
            assertFalse("a cancelled in-flight capture must not trigger stopRecMode",rpcMethods(server).contains("stopRecMode"))
        }
    }

    @Test fun forcedDisconnectCancelsABlockingHttpRequestInsteadOfWaiting() {
        MockWebServer().use { server ->
            val http=CameraHttp()
            server.enqueue(MockResponse().setBody(emptyPage()))  // initialize browse
            server.enqueue(MockResponse().setBody(emptyPage()).setBodyDelay(30,TimeUnit.SECONDS)) // hanging browse
            controller.sessionOverride=CompanionController.SessionOverride(http,listOf(jpegDevice(server,false)))
            assertTrue(controller.connect(CameraMode.JPEG))
            waitUntil { controller.state.value.connected }
            controller.browse("folder")
            Thread.sleep(250)  // let the hanging request start
            val start=System.nanoTime()
            runBlocking { controller.disconnect(false) }
            val elapsed=(System.nanoTime()-start)/1_000_000
            assertTrue("disconnect must cancel the body, not wait for its timeout, was ${elapsed}ms",elapsed<5_000)
            assertTrue(http.isClosed)
        }
    }

    @Test fun aNewSessionIsNotAffectedByTheOldTeardown() {
        MockWebServer().use { first ->
            MockWebServer().use { second ->
                val firstHttp=CameraHttp()
                first.enqueue(MockResponse().setBody(emptyPage()))
                controller.sessionOverride=CompanionController.SessionOverride(firstHttp,listOf(jpegDevice(first,false)))
                assertTrue(controller.connect(CameraMode.JPEG))
                waitUntil { controller.state.value.connected }
                val firstBefore=first.requestCount
                val generationBefore=controller.state.value.sessionGeneration
                val secondDevice=jpegDevice(second,false)
                val secondHttp=CameraHttp()
                second.enqueue(MockResponse().setBody(emptyPage()))
                controller.sessionOverride=CompanionController.SessionOverride(secondHttp,listOf(secondDevice))
                assertTrue(controller.connect(CameraMode.JPEG))
                waitUntil { controller.state.value.connected }
                assertTrue("the old transport must be released by the new connect",firstHttp.isClosed)
                assertFalse("the new transport must stay open",secondHttp.isClosed)
                assertTrue("the new session must have its own generation",controller.state.value.sessionGeneration>generationBefore)
                assertEquals("the published device must be the new session's",secondDevice.description,controller.state.value.device?.description)
                Thread.sleep(300)
                assertEquals("the old teardown must not talk to the new session's server",firstBefore,first.requestCount)
            }
        }
    }
}
