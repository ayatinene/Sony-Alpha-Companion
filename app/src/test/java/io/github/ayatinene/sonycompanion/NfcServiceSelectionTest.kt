package io.github.ayatinene.sonycompanion

import io.github.ayatinene.sonycompanion.data.*
import io.github.ayatinene.sonycompanion.pairing.*
import io.github.ayatinene.sonycompanion.network.resolveNfcCameraDescriptions
import io.github.ayatinene.sonycompanion.ui.*
import java.net.URI
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class NfcServiceSelectionTest {
    private val uri=URI("http://192.0.2.1/dd.xml")
    private fun device(remote: Boolean=false,jpeg: Boolean=false)=Device("Camera","ILCE-7RM2",uri,
        remote=if(remote) uri.resolve("/sony/camera") else null,
        content=if(jpeg) uri.resolve("/cds") else null)
    @Test fun choosesTheAdvertisedRemoteOrJpegService() {
        assertEquals(CameraMode.REMOTE,selectNfcService(listOf(device(remote=true))).mode)
        assertEquals(CameraMode.JPEG,selectNfcService(listOf(device(jpeg=true))).mode)
    }
    @Test fun refusesToGuessWhenMissingAmbiguousOrMultipleCameraServicesAreAdvertised() {
        listOf(emptyList(),listOf(device()),listOf(device(remote=true,jpeg=true)),
            listOf(device(remote=true),device(jpeg=true))).forEach {
            assertThrows(IllegalArgumentException::class.java) { selectNfcService(it) }
        }
    }
    @Test fun duplicateAdvertisementsDoNotCreateAnAmbiguousChoice() {
        val camera=device(jpeg=true)
        assertEquals(CameraMode.JPEG,selectNfcService(listOf(camera,camera)).mode)
    }
    @Test fun automaticDiscoveryIgnoresInactiveFallbacksAndOnlyReadsEachDescriptorOnce()=runBlocking {
        var reads=0
        val xml="""<root><device><friendlyName>Camera</friendlyName><modelName>ILCE-7RM2</modelName><serviceList><service><serviceType>urn:schemas-upnp-org:service:ContentDirectory:1</serviceType><controlURL>/cds</controlURL></service></serviceList></device></root>"""
        val result=resolveNfcCameraDescriptions(listOf(uri,uri)) { reads++;xml }
        assertEquals(1,reads);assertEquals(CameraMode.JPEG,selectNfcService(result).mode)
        assertTrue(resolveNfcCameraDescriptions(emptyList()) { error("must not probe a legacy fallback") }.isEmpty())
    }
    @Test fun existingConnectionNavigationOpensTheDetectedJpegWorkspace() {
        val nav=ShellNav().requestConnection(CameraMode.REMOTE).observe(false,true,CameraMode.REMOTE)
            .observe(true,false,CameraMode.JPEG)
        assertEquals(Destination.Gallery,nav.destination)
    }
}
