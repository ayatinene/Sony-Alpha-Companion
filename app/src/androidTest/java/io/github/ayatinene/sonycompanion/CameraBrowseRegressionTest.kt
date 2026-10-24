package io.github.ayatinene.sonycompanion

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.ayatinene.sonycompanion.data.*
import io.github.ayatinene.sonycompanion.network.CameraHttp
import io.github.ayatinene.sonycompanion.protocol.CameraXml
import io.github.ayatinene.sonycompanion.protocol.SmartphoneTransfer
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Runs the camera parsing code on Android's runtime, not the desktop JVM. */
@RunWith(AndroidJUnit4::class)
class CameraBrowseRegressionTest {
    @Test fun jpegResourcesInitializeAndClassifyOnAndroid() {
        assertTrue(Resource("jpeg").isJpeg)
        for((profile,size) in listOf("JPEG_ORG" to JpegSize.ORIGINAL,"JPEG_LRG" to JpegSize.LARGE,
            "JPEG_SM" to JpegSize.SMALL,"JPEG_TN" to JpegSize.THUMBNAIL)) {
            val resource=Resource("jpeg",profile="http-get:*:image/jpeg:DLNA.ORG_OP=01;DLNA.ORG_PN=$profile;DLNA.ORG_CI=0")
            assertEquals(size,resource.jpegSize)
        }
        assertEquals(JpegSize.ORIGINAL,Resource("original",profile="http-get:*:image/jpeg:*").jpegSize)
        assertNull(Resource("unknown",profile="http-get:*:image/jpeg:DLNA.ORG_PN=JPEG_UNKNOWN").jpegSize)
    }
    @Test fun smartphoneConnectionReadsRootAndJpegDirectoryOnAndroid() = runBlocking {
        MockWebServer().use { server ->
            val root="""<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/"><container id="date-1"><dc:title>2026-10-24</dc:title></container></DIDL-Lite>"""
            val photos="""<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/"><item id="42"><dc:title>DSC00042.JPG</dc:title><dc:date>2026-10-24T13:00:00</dc:date><res protocolInfo="http-get:*:image/jpeg:*" size="123456">/original.jpg</res><res protocolInfo="http-get:*:image/jpeg:DLNA.ORG_PN=JPEG_TN" resolution="160x120" size="1000">/thumbnail.jpg</res></item></DIDL-Lite>"""
            server.enqueue(MockResponse().setResponseCode(500).setBody("<Envelope><Body><Fault><detail><UPnPError><errorCode>701</errorCode><errorDescription>No such object</errorDescription></UPnPError></detail></Fault></Body></Envelope>"))
            server.enqueue(MockResponse().setBody(page(root)))
            server.enqueue(MockResponse().setBody(page(photos)))
            val description="""<root><device><friendlyName>ILCE-7RM2</friendlyName><modelName>SonyImagingDevice</modelName><serviceList><service><serviceType>urn:schemas-upnp-org:service:ContentDirectory:1</serviceType><controlURL>/cds</controlURL></service></serviceList></device></root>"""
            val device=CameraXml.description(description,server.url("/dd.xml").toUri())
            val http=CameraHttp()
            try {
                val transfer=SmartphoneTransfer(device,http)
                val folders=transfer.initialize()
                assertEquals("PhotoRoot",transfer.rootId)
                assertTrue(folders.single().container)
                val photo=transfer.browse(folders.single().id).single()
                assertEquals("date-1",photo.parent)
                assertEquals(server.url("/original.jpg").toString(),photo.bestResource()!!.url)
                assertEquals(server.url("/thumbnail.jpg").toString(),photo.thumbnail)
                assertEquals(listOf(JpegSize.MAXIMUM,JpegSize.ORIGINAL,JpegSize.THUMBNAIL),photo.availableJpegSizes())
                assertEquals(3,server.requestCount)
                transfer.finish()
            } finally { http.close() }
        }
    }
    private fun page(didl: String) = "<Envelope><Body><BrowseResponse><Result>${CameraXml.escape(didl)}</Result><NumberReturned>1</NumberReturned><TotalMatches>1</TotalMatches></BrowseResponse></Body></Envelope>"
}
