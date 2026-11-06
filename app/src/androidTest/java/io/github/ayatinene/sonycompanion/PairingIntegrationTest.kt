package io.github.ayatinene.sonycompanion

import android.net.ConnectivityManager
import android.net.Network
import android.nfc.NdefRecord
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ayatinene.sonycompanion.data.*
import io.github.ayatinene.sonycompanion.network.CameraHttp
import io.github.ayatinene.sonycompanion.pairing.*
import io.github.ayatinene.sonycompanion.ui.*
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.Closeable
import java.util.concurrent.atomic.AtomicInteger

/** Emulator coverage of wiring/lifecycle only; no assertion of physical Sony Wi-Fi/NFC compatibility. */
@RunWith(AndroidJUnit4::class)
class PairingIntegrationTest {
    @get:Rule val compose=createComposeRule()
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val controller get()=(context.applicationContext as CompanionApplication).controller
    private val credentials=PairingCredentials.create("DIRECT-ab12:ILCE-7RM2","testPass")
    private lateinit var original: PairingBackend<Network>
    private class Backend : PairingBackend<Network> {
        @Volatile var events: PairingBackend.Events<Network>?=null
        val releases=AtomicInteger()
        override fun request(credentials: PairingCredentials, events: PairingBackend.Events<Network>): Closeable {
            this.events=events
            return Closeable { releases.incrementAndGet() }
        }
    }
    @Before fun setup() {
        runBlocking { controller.disconnect(false) }
        original=controller.pairingBackend
        controller.sessionOverride=null;controller.setVisible(false)
    }
    @After fun cleanup() {
        runBlocking { controller.disconnect(false) }
        controller.pairingBackend=original;controller.sessionOverride=null;controller.setVisible(true)
    }
    private fun waitFor(condition: () -> Boolean) {
        val end=System.currentTimeMillis()+8000
        while(System.currentTimeMillis()<end) { if(condition()) return; Thread.sleep(20) }
        fail("Pairing condition not reached")
    }
    private fun initialize(server: MockWebServer) {
        val apis="""{"result":[["startRecMode","actTakePicture","stopRecMode","getVersions","getApplicationInfo"]]}"""
        listOf(apis,"""{"result":[0]}""",apis,"""{"result":[["1.0"]]}""","""{"result":["Smart Remote","4.30"]}""",apis)
            .forEach { server.enqueue(MockResponse().setBody(it)) }
    }
    private fun override(server: MockWebServer): CameraHttp {
        val http=CameraHttp()
        controller.sessionOverride=CompanionController.SessionOverride(http,listOf(Device("ILCE-7RM2","ILCE-7RM2",server.url("/dd.xml").toUri(),remote=server.url("/sony/camera").toUri())))
        return http
    }
    @Test fun wifiApprovalThenExistingRemoteInitializationAndDisconnectReleaseTheLease() {
        MockWebServer().use { server ->
            initialize(server); val http=override(server)
            val backend=Backend();controller.pairingBackend=backend
            assertTrue(controller.pairCamera(credentials));waitFor { backend.events!=null }
            assertFalse(controller.state.value.connected);assertEquals(0,server.requestCount)
            // Simulate approval UI stop/start: it must not cancel the in-flight system request.
            controller.setVisible(false);controller.setVisible(true);controller.setVisible(false)
            assertEquals(0,backend.releases.get())
            val network=requireNotNull(context.getSystemService(ConnectivityManager::class.java).activeNetwork)
            backend.events!!.available(network)
            waitFor { controller.state.value.connected && !controller.state.value.busy }
            assertEquals(CameraMode.REMOTE,controller.state.value.mode)
            assertEquals(0,backend.releases.get());assertFalse(http.isClosed)
            runBlocking { controller.disconnect(false) }
            assertEquals(1,backend.releases.get());assertTrue(http.isClosed)
            backend.events!!.available(network);backend.events!!.lost(network)
            assertFalse(controller.state.value.connected)
        }
    }
    @Test fun activityStopAndRecreationPreservePendingWifiUntilExplicitCancel() {
        val backend=Backend();controller.pairingBackend=backend
        androidx.test.core.app.ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { assertTrue(controller.pairCamera(credentials)) }
            waitFor { backend.events!=null }
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            assertEquals(0,backend.releases.get())
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            scenario.recreate()
            assertEquals(0,backend.releases.get())
            scenario.onActivity { controller.cancelPairing() }
            waitFor { backend.releases.get()==1 }
            assertFalse(controller.state.value.connected)
        }
    }
    @Test fun losingApprovedWifiTerminatesOnlyItsRemoteSession() {
        MockWebServer().use { server ->
            initialize(server);val http=override(server)
            val backend=Backend();controller.pairingBackend=backend
            controller.pairCamera(credentials);waitFor { backend.events!=null }
            val network=requireNotNull(context.getSystemService(ConnectivityManager::class.java).activeNetwork)
            backend.events!!.available(network)
            waitFor { controller.state.value.connected && !controller.state.value.busy }
            backend.events!!.lost(network)
            waitFor { !controller.state.value.connected && http.isClosed && controller.state.value.error?.contains("Wi-Fi") == true }
            assertEquals(1,backend.releases.get())
            assertTrue(controller.state.value.error?.contains("Wi-Fi") == true)
        }
    }
    @Test fun rejectAndCancelDoNotLeaveCallbacksOrClaimRemoteSuccess() {
        val first=Backend();controller.pairingBackend=first
        assertTrue(controller.pairCamera(credentials));waitFor { first.events!=null };first.events!!.unavailable()
        waitFor { !controller.state.value.busy && controller.state.value.error!=null }
        assertFalse(controller.state.value.connected);assertEquals(1,first.releases.get())
        val next=Backend();controller.pairingBackend=next
        assertTrue(controller.pairCamera(credentials));waitFor { next.events!=null }
        controller.cancelPairing();waitFor { next.releases.get()==1 }
        assertFalse(controller.state.value.connected)
    }
    @Test fun apiFailureAfterWifiApprovalReleasesTheNetworkAndReportsFailure() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(500));val http=override(server)
            val backend=Backend();controller.pairingBackend=backend
            controller.pairCamera(credentials);waitFor { backend.events!=null }
            backend.events!!.available(requireNotNull(context.getSystemService(ConnectivityManager::class.java).activeNetwork))
            waitFor { !controller.state.value.busy && controller.state.value.error!=null }
            assertFalse(controller.state.value.connected);assertEquals(1,backend.releases.get());assertTrue(http.isClosed)
        }
    }
    @Test fun homeKeepsManualModesAndAddsPairingEntries() {
        val model=CompanionViewModel(context.applicationContext as android.app.Application)
        compose.setContent { MaterialTheme { CompanionApp(model,{}, {}) } }
        compose.onNodeWithTag("pairing-scan").assertExists()
        compose.onNodeWithTag("pairing-nfc").assertExists()
        CameraMode.entries.forEach { mode ->
            compose.onNode(hasScrollAction()).performScrollToNode(hasText(mode.title))
            compose.onNodeWithText(mode.title).assertExists()
        }
        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("pairing-nfc"))
        compose.onNodeWithTag("pairing-nfc").performClick()
        compose.onNodeWithText("知道了").assertExists().performClick()
        compose.onNodeWithTag("pairing-scan").assertExists()
    }
    @Test fun ndefRecordAdapterRejectsIncompleteAndAmbiguousTags() {
        assertThrows(Exception::class.java) { NfcPairing.readRecords(emptyList()) }
        assertThrows(Exception::class.java) { NfcPairing.readRecords(listOf(NdefRecord.createMime("application/vnd.wfa.wsc",byteArrayOf(1)))) }
        assertThrows(Exception::class.java) { NfcPairing.readRecords(listOf(NdefRecord.createMime(SonyNfcParser.MIME,byteArrayOf(0)))) }
        assertEquals(listOf(android.Manifest.permission.NEARBY_WIFI_DEVICES),PairingPermissionPolicy.required(36))
        assertTrue(PairingPermissionPolicy.required(31).contains(android.Manifest.permission.ACCESS_FINE_LOCATION))
    }
}
