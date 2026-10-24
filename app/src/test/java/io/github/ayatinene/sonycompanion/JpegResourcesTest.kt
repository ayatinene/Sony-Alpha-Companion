package io.github.ayatinene.sonycompanion

import io.github.ayatinene.sonycompanion.data.*
import io.github.ayatinene.sonycompanion.protocol.CameraXml
import org.junit.Assert.*
import org.junit.Test

class JpegResourcesTest {
    private fun jpeg(name: String, profile: String, size: Long=0, resolution: String="") =
        Resource("http://camera/$name", "image/jpeg", size, resolution, "http-get:*:image/jpeg:$profile")
    private val original=jpeg("original.jpg","*",11567104)
    private val large=jpeg("large.jpg","DLNA.ORG_PN=JPEG_LRG",300000,"1616x1080")
    private val small=jpeg("small.jpg","DLNA.ORG_PN=JPEG_SM",40000,"640x480")
    private val thumbnail=jpeg("tn.jpg","DLNA.ORG_PN=JPEG_TN",3000,"160x120")
    private val file=CameraFile("42","DSC02200.JPG",listOf(thumbnail,large,original,small))

    @Test fun originalWithoutResolutionWinsOverResizedJpeg() {
        assertEquals(original,file.bestResource())
        assertEquals(original,file.jpegResource(JpegSize.ORIGINAL))
        assertEquals(listOf(JpegSize.MAXIMUM,JpegSize.ORIGINAL,JpegSize.LARGE,JpegSize.SMALL,JpegSize.THUMBNAIL),file.availableJpegSizes())
    }
    @Test fun explicitOriginalProfileIsRecognized() {
        val explicit=jpeg("explicit.jpg","DLNA.ORG_PN=JPEG_ORG")
        assertEquals(JpegSize.ORIGINAL,explicit.jpegSize)
        assertEquals(explicit,file.copy(resources=listOf(large,explicit)).bestResource())
    }
    @Test fun profileAttributesAreDelimitedAndCaseInsensitive() {
        val lower=jpeg("large.jpg","DLNA.ORG_OP=01;dlna.org_pn=jpeg_lrg;DLNA.ORG_CI=0")
        assertEquals(JpegSize.LARGE,lower.jpegSize)
        assertEquals(JpegSize.THUMBNAIL,Resource("tn",profile="DLNA.ORG_PN=JPEG_TN").jpegSize)
        assertEquals(JpegSize.ORIGINAL,jpeg("original.jpg","DLNA.ORG_OP=01;DLNA.ORG_CI=0").jpegSize)
    }
    @Test fun similarlyNamedOrEmptyProfileAttributesDoNotBecomeKnownSizes() {
        assertNull(jpeg("empty.jpg","DLNA.ORG_PN=").jpegSize)
        assertNull(jpeg("unknown.jpg","DLNA.ORG_PN=JPEG_UNKNOWN").jpegSize)
        assertEquals(JpegSize.ORIGINAL,jpeg("other.jpg","X_DLNA.ORG_PN=JPEG_TN").jpegSize)
    }
    @Test fun eachSelectionUsesItsActualDlnaProfile() {
        assertEquals(large,file.jpegResource(JpegSize.LARGE))
        assertEquals(small,file.jpegResource(JpegSize.SMALL))
        assertEquals(thumbnail,file.jpegResource(JpegSize.THUMBNAIL))
        assertEquals(thumbnail,file.jpegThumbnail())
    }
    @Test fun missingOriginalUsesLargestAvailableWithoutInventingOriginalOption() {
        val reduced=file.copy(resources=listOf(thumbnail,small,large))
        assertEquals(large,reduced.bestResource()); assertNull(reduced.jpegResource(JpegSize.ORIGINAL))
        assertFalse(JpegSize.ORIGINAL in reduced.availableJpegSizes())
    }
    @Test fun rawObjectKeepsJpegPreviewResourceSeparateFromRawFile() {
        val raw=file.copy(name="DSC02200.ARW",raw=true,resources=listOf(large))
        assertTrue(raw.raw); assertEquals("image/jpeg",raw.bestResource()!!.mime)
        assertNull(raw.jpegResource(JpegSize.ORIGINAL))
    }
    @Test fun videoAndHeifResourcesDoNotBecomeJpegChoicesOrThumbnails() {
        val mixed=file.copy(resources=listOf(Resource("movie","video/mp4"),Resource("heif","image/heif"),large))
        assertEquals(large,mixed.bestResource()); assertEquals(large,mixed.jpegThumbnail())
        assertTrue(file.copy(resources=listOf(Resource("movie","video/mp4"))).availableJpegSizes().isEmpty())
    }
    @Test fun unknownProfilesStillUseActualResolutionAndSizeForMaximum() {
        val unknown=Resource("big","image/jpeg",600000,"3000X2000")
        assertEquals(unknown,file.copy(resources=listOf(large,unknown)).bestResource())
        assertNull(unknown.jpegSize)
    }
    @Test fun browsePreservesAnOriginalWithMissingMetadataAndChoosesTheThumbnail() {
        val didl="""<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/"><item id="42"><dc:title>DSC02200.JPG</dc:title><res protocolInfo="http-get:*:image/jpeg:*">/original.jpg</res><res protocolInfo="http-get:*:image/jpeg:DLNA.ORG_PN=JPEG_LRG" resolution="1616x1080" size="300000">/large.jpg</res><res protocolInfo="http-get:*:image/jpeg:DLNA.ORG_PN=JPEG_TN" resolution="160x120" size="3000">/tn.jpg</res></item></DIDL-Lite>"""
        val response="<Envelope><Body><Result>${CameraXml.escape(didl)}</Result><NumberReturned>1</NumberReturned><TotalMatches>1</TotalMatches></Body></Envelope>"
        val parsed=CameraXml.browse(response).items.single()
        assertEquals("/original.jpg",parsed.bestResource()!!.url); assertEquals("/tn.jpg",parsed.thumbnail)
        assertEquals(0L,parsed.bestResource()!!.size)
    }
}
