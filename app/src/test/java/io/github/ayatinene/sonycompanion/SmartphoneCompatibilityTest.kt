package io.github.ayatinene.sonycompanion

import io.github.ayatinene.sonycompanion.data.Device
import io.github.ayatinene.sonycompanion.network.*
import io.github.ayatinene.sonycompanion.protocol.*
import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.net.URI

class SmartphoneCompatibilityTest {
    // Sanitized shape of the A7R II's active dd.xml; the fallback also exists but its push URL returns 404.
    private val activeXml = """<root><device><friendlyName>ILCE-7RM2</friendlyName><modelName>SonyImagingDevice</modelName><serviceList><service><serviceType>urn:schemas-upnp-org:service:ContentDirectory:1</serviceType><controlURL>/upnp/control/ContentDirectory</controlURL></service></serviceList></device></root>"""
    private val fallbackXml = activeXml.replace("</serviceList>", """<service><serviceType>urn:schemas-sony-com:service:XPushList:1</serviceType><controlURL>/upnp/control/XPushList</controlURL></service></serviceList>""")
    private val emptyPage = """<Envelope><Body><BrowseResponse><Result></Result><NumberReturned>0</NumberReturned><TotalMatches>0</TotalMatches></BrowseResponse></Body></Envelope>"""
    private fun fault(code: String) = """<Envelope><Body><Fault><detail><UPnPError><errorCode>$code</errorCode><errorDescription>Unsupported</errorDescription></UPnPError></detail></Fault></Body></Envelope>"""
    private fun transfer(server: MockWebServer, http: CameraHttp, report: (String) -> Unit = {}) =
        SmartphoneTransfer(Device("ILCE-7RM2", "SonyImagingDevice", server.url("/dd.xml").toUri(),
            content=server.url("/upnp/control/ContentDirectory").toUri(), push=server.url("/upnp/control/XPushList").toUri()), http, report)

    @Test fun advertisedContentDirectoryIsUsedWithoutProbingInactiveFallback() = runBlocking {
        MockWebServer().use { server -> CameraHttp().use { http ->
            server.enqueue(MockResponse().setBody(activeXml))
            server.enqueue(MockResponse().setBody(emptyPage))
            val device = resolveCameraDescriptions(listOf(server.url("/dd.xml").toUri()),
                server.url("/DmsDescPush.xml").toUri(), false) { http.getText(it.toString()) }.single()
            assertNull(device.push)
            assertTrue(SmartphoneTransfer(device, http).initialize().isEmpty())
            assertEquals(2, server.requestCount)
            assertEquals("/dd.xml", server.takeRequest().path)
            assertEquals("/upnp/control/ContentDirectory", server.takeRequest().path)
        } }
    }
    @Test fun legacyDescriptorIsProbedWhenAdvertisedDescriptorHasNoMatchingService() = runBlocking {
        val dd=URI("http://camera/dd.xml"); val fallback=URI("http://camera/DmsDescPush.xml")
        val reads=mutableListOf<URI>()
        val device=resolveCameraDescriptions(listOf(dd), fallback, false) {
            reads+=it; if(it==dd) "<root><device/></root>" else fallbackXml
        }.single()
        assertEquals(listOf(dd,fallback),reads)
        assertEquals(fallback,device.description); assertNotNull(device.push)
    }
    @Test fun failedAdvertisementCanUseLegacyDescriptor() = runBlocking {
        val fallback=URI("http://camera/DmsDescPush.xml")
        val device=resolveCameraDescriptions(listOf(URI("http://camera/missing.xml")),fallback,false) {
            if(it!=fallback) throw java.io.IOException("unavailable")
            fallbackXml
        }.single()
        assertEquals(fallback,device.description)
    }
    @Test fun cancelledDiscoveryDoesNotProbeFallback() = runBlocking {
        var reads=0
        try {
            resolveCameraDescriptions(listOf(URI("http://camera/dd.xml")),URI("http://camera/DmsDescPush.xml"),false) {
                reads++; throw CancellationException("cancelled")
            }
            fail("cancellation must propagate")
        } catch (_: CancellationException) { assertEquals(1,reads) }
    }
    @Test fun unsupportedPushHttpStatusesAllowBrowseAndNeverSendTransferEnd() = runBlocking {
        for(code in listOf(404,405,501)) MockWebServer().use { server -> CameraHttp().use { http ->
            server.enqueue(MockResponse().setResponseCode(code))
            server.enqueue(MockResponse().setBody(emptyPage))
            val messages=mutableListOf<String>(); val transfer=transfer(server,http) { messages+=it }
            assertTrue(transfer.initialize().isEmpty()); transfer.finish()
            assertEquals(2,server.requestCount); assertEquals(1,messages.size)
            assertTrue(messages.single().contains("HTTP $code"))
            assertTrue(server.takeRequest().getHeader("SOAPACTION")!!.contains("X_TransferStart"))
            assertTrue(server.takeRequest().getHeader("SOAPACTION")!!.contains("#Browse"))
        } }
    }
    @Test fun missingPushAndPushRootCanStillBrowsePhotoRoot() = runBlocking {
        MockWebServer().use { server -> CameraHttp().use { http ->
            server.enqueue(MockResponse().setResponseCode(404))
            server.enqueue(MockResponse().setResponseCode(500).setBody(fault("701")))
            server.enqueue(MockResponse().setBody(emptyPage))
            val transfer=transfer(server,http)
            assertTrue(transfer.initialize().isEmpty()); assertEquals("PhotoRoot",transfer.rootId)
            transfer.finish(); assertEquals(3,server.requestCount)
            server.takeRequest()
            assertTrue(server.takeRequest().body.readUtf8().contains("<ObjectID>PushRoot</ObjectID>"))
            assertTrue(server.takeRequest().body.readUtf8().contains("<ObjectID>PhotoRoot</ObjectID>"))
        } }
    }
    @Test fun upnpInvalidActionAllowsBrowse() = runBlocking {
        MockWebServer().use { server -> CameraHttp().use { http ->
            server.enqueue(MockResponse().setResponseCode(500).setBody(fault("401")))
            server.enqueue(MockResponse().setBody(emptyPage))
            val transfer=transfer(server,http)
            transfer.initialize(); transfer.finish(); assertEquals(2,server.requestCount)
        } }
    }
    @Test fun authenticationAndServerErrorsRemainFailures() = runBlocking {
        for(code in listOf(401,403,503)) MockWebServer().use { server -> CameraHttp().use { http ->
            server.enqueue(MockResponse().setResponseCode(code))
            try { transfer(server,http).initialize(); fail("HTTP $code must not be ignored") }
            catch(e: CameraHttpError) { assertEquals(code,e.code) }
            assertEquals(1,server.requestCount)
        } }
    }
    @Test fun otherSoapFaultsRemainFailures() = runBlocking {
        MockWebServer().use { server -> CameraHttp().use { http ->
            server.enqueue(MockResponse().setResponseCode(500).setBody(fault("501")))
            try { transfer(server,http).initialize(); fail("Action Failed must not be ignored") }
            catch(e: SoapFault) { assertEquals("501",e.code) }
            assertEquals(1,server.requestCount)
        } }
    }
}
