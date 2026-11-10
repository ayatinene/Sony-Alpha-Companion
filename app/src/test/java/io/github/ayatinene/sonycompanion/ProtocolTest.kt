package io.github.ayatinene.sonycompanion

import io.github.ayatinene.sonycompanion.protocol.*
import io.github.ayatinene.sonycompanion.data.*
import io.github.ayatinene.sonycompanion.network.CameraHttp
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.net.URI

class ProtocolTest {
    private val descriptor="""<root xmlns="urn:schemas-upnp-org:device-1-0" xmlns:av="urn:schemas-sony-com:av"><device><friendlyName>α7R II</friendlyName><modelName>ILCE-7RM2</modelName><av:X_ScalarWebAPI_ServiceList><av:X_ScalarWebAPI_Service><av:X_ScalarWebAPI_ServiceType>camera</av:X_ScalarWebAPI_ServiceType><av:X_ScalarWebAPI_ActionList_URL>/sony</av:X_ScalarWebAPI_ActionList_URL></av:X_ScalarWebAPI_Service></av:X_ScalarWebAPI_ServiceList><serviceList><service><serviceType>urn:schemas-upnp-org:service:ContentDirectory:1</serviceType><controlURL>/upnp/control/ContentDirectory</controlURL></service><service><serviceType>urn:schemas-sony-com:service:XPushList:1</serviceType><controlURL>/upnp/control/XPushList</controlURL></service></serviceList></device></root>"""
    @Test fun descriptionResolvesNamespacedServices() {
        val device=CameraXml.description(descriptor,URI("http://192.168.122.1:64321/DmsDescPush.xml"))
        assertEquals("ILCE-7RM2",device.model)
        assertEquals("http://192.168.122.1:64321/sony/camera",device.remote.toString())
        assertEquals("/upnp/control/ContentDirectory",device.content!!.path)
    }
    @Test fun endpointAlreadyHasCameraSuffix() {
        assertTrue(CameraXml.description(descriptor.replace(">/sony<",">/sony/camera<"),URI("http://192.168.0.1/dd.xml")).remote.toString().endsWith("/sony/camera"))
    }
    @Test fun ssdpHeaderIsCaseInsensitiveAndKeepsPort() {
        assertEquals(URI("http://192.168.122.1:61000/dd.xml"),CameraXml.location("HTTP/1.1 200 OK\r\nlocation: http://192.168.122.1:61000/dd.xml\r\nST: something\r\n"))
        assertNull(CameraXml.location("HTTP/1.1 200 OK\r\nST: other"))
    }
    @Test(expected=IllegalArgumentException::class) fun rejectsDoctype() { CameraXml.document("<!DOCTYPE x [<!ENTITY y SYSTEM 'file:///etc/passwd'>]><x>&y;</x>") }
    private fun page(didl:String,returned:Int=1,total:Int=1)="""<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><u:BrowseResponse xmlns:u="urn:schemas-upnp-org:service:ContentDirectory:1"><Result>${CameraXml.escape(didl)}</Result><NumberReturned>$returned</NumberReturned><TotalMatches>$total</TotalMatches></u:BrowseResponse></s:Body></s:Envelope>"""
    private val didl="""<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/"><item id="42"><dc:title>DSC02200.ARW</dc:title><res protocolInfo="http-get:*:image/jpeg:DLNA.ORG_PN=JPEG_TN" size="100" resolution="160x120">http://192.168.0.1/tn.jpg</res><res protocolInfo="http-get:*:image/jpeg:DLNA.ORG_PN=JPEG_LRG" size="3000" resolution="1616x1080">http://192.168.0.1/large.jpg</res></item></DIDL-Lite>"""
    @Test fun nestedDidlPreservesResourcesAndRawPreviewDistinction() {
        val p=CameraXml.browse(page(didl));val f=p.items.single()
        assertTrue(f.raw);assertEquals(2,f.resources.size);assertEquals("http://192.168.0.1/large.jpg",f.bestResource()!!.url)
        assertEquals("http://192.168.0.1/tn.jpg",f.thumbnail)
        assertEquals(1,p.returned)
    }
    @Test fun missingSizeStillSelectsLargestResolution() {
        val f=CameraXml.browse(page(didl.replace(" size=\"100\"","").replace(" size=\"3000\"",""))).items.single()
        assertEquals("1616x1080",f.bestResource()!!.resolution)
    }
    @Test fun emptyBrowsePageIsRepresentable() {
        val p=CameraXml.browse(page("",0,0));assertEquals(0,p.total);assertTrue(p.items.isEmpty())
    }
    @Test fun soapFaultPreservesCode() {
        try { CameraXml.browse("""<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><s:Fault><detail><UPnPError><errorCode>701</errorCode><errorDescription>No such object</errorDescription></UPnPError></detail></s:Fault></s:Body></s:Envelope>""");fail() }
        catch(e:SoapFault) {assertEquals("701",e.code)}
    }
    @Test fun liveViewHandlesSplitReadsPaddingAndMetadata() {
        fun packet(type:Int,data:ByteArray):ByteArray {
            val out=ByteArrayOutputStream();out.write(byteArrayOf(-1,type.toByte(),0,1,0,0,0,1))
            val h=ByteArray(128);h[0]=0x24;h[1]=0x35;h[2]=0x68;h[3]=0x79;h[6]=data.size.toByte();h[7]=2
            out.write(h);out.write(data);out.write(byteArrayOf(0,0));return out.toByteArray()
        }
        val jpeg=byteArrayOf(-1,-40,3,5,-1,-39)
        val stream=object:ByteArrayInputStream(packet(2,byteArrayOf(1,2))+packet(1,jpeg)) {
            override fun read(b:ByteArray,off:Int,len:Int)=super.read(b,off,minOf(1,len))
        }
        val reader=LiveViewReader(stream);assertArrayEquals(jpeg,reader.next());assertNull(reader.next())
    }
    @Test(expected=EOFException::class) fun liveViewRejectsTruncation() { LiveViewReader(ByteArrayInputStream(byteArrayOf(-1,1,0))).next() }
    @Test fun pairingIsCaseInsensitive() { assertEquals(pairKey("DSC02200.ARW"),pairKey("dsc02200.jpg")) }
    @Test fun guidRoundTripsExplicitBytes() { assertArrayEquals(shortArrayOf(0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15),ComputerTransfer.parseGuid("00:01:02:03:04:05:06:07:08:09:0a:0b:0c:0d:0e:0f")) }
    @Test(expected=IllegalArgumentException::class) fun guidNeverSubstitutesRandomValue() { ComputerTransfer.parseGuid("") }
    @Test fun jsonRpcFailureIsNotReportedAsSuccess()=runBlocking {
        MockWebServer().use { server->
            server.enqueue(MockResponse().setBody("""{"error":[12,"No such method"],"id":1}"""))
            val camera=RemoteCamera(server.url("/sony/camera").toString(),CameraHttp())
            try{camera.call("actTakePicture");fail()}catch(e:CameraApiError){assertEquals(12,e.code)}
            val request=JSONObject(server.takeRequest().body.readUtf8());assertEquals("actTakePicture",request.getString("method"));assertEquals("1.0",request.getString("version"))
        }
    }
    @Test fun capabilityControlsUseAdvertisedValuesAndEvStep()=runBlocking {
        MockWebServer().use { server->
            server.enqueue(MockResponse().setBody("""{"result":[["getAvailableIsoSpeedRate","setIsoSpeedRate","getAvailableExposureCompensation"]]}"""))
            server.enqueue(MockResponse().setBody("""{"result":["100",["100","200"]]}"""))
            server.enqueue(MockResponse().setBody("""{"result":[0,3,-3,1]}"""))
            val camera=RemoteCamera(server.url("/sony/camera").toString(),CameraHttp())
            val state=camera.refresh();assertEquals(2,state.settings.size)
            assertTrue(state.settings.first().writable);assertFalse(state.settings.last().writable)
            assertEquals("+1.0 EV",state.settings.last().display("3"))
            try{camera.set("IsoSpeedRate","999999");fail()}catch(_:IllegalArgumentException){}
            assertEquals(3,server.requestCount)
        }
    }
    @Test fun soapPaginationAndTransferEndAreExplicit()=runBlocking {
        MockWebServer().use { server->
            server.enqueue(MockResponse().setBody("<Envelope><Body/></Envelope>"))
            server.enqueue(MockResponse().setBody(page(didl,1,2)))
            server.enqueue(MockResponse().setBody(page(didl.replace("42","43"),1,2)))
            server.enqueue(MockResponse().setBody("<Envelope><Body/></Envelope>"))
            val transfer=SmartphoneTransfer(Device("camera","A7R2",server.url("/desc").toUri(),content=server.url("/cds").toUri(),push=server.url("/push").toUri()),CameraHttp())
            assertEquals(2,transfer.initialize().size)
            assertEquals(3,server.requestCount)
            transfer.finish();assertEquals(4,server.requestCount)
            assertTrue(server.takeRequest().getHeader("SOAPACTION")!!.contains("X_TransferStart"))
            server.takeRequest()
            assertTrue(server.takeRequest().body.readUtf8().contains("<StartingIndex>1</StartingIndex>"))
            assertTrue(server.takeRequest().getHeader("SOAPACTION")!!.contains("X_TransferEnd"))
        }
    }
    @Test fun zeroProgressPaginationFailsRatherThanLoops()=runBlocking {
        MockWebServer().use { server->
            server.enqueue(MockResponse().setBody(page("",0,10)))
            val transfer=SmartphoneTransfer(Device("c","A7",server.url("/desc").toUri(),content=server.url("/cds").toUri()),CameraHttp())
            try{transfer.initialize();fail()}catch(_:IllegalStateException){}
            assertEquals(1,server.requestCount)
        }
    }
}
