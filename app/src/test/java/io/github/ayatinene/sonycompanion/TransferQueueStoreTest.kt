package io.github.ayatinene.sonycompanion

import io.github.ayatinene.sonycompanion.data.*
import io.github.ayatinene.sonycompanion.protocol.CameraXml
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.URI

class TransferQueueStoreTest {
    @get:Rule val temporary=TemporaryFolder()
    private val a="uuid:11111111-2222-3333-4444-555555555555"
    private val b="uuid:11111111-2222-3333-4444-666666666666"
    private fun device(udn: String, host: String="camera")=Device("Sony","ILCE-7RM2",URI("http://$host/dd.xml"),udn=udn)
    private fun photo(date: String="2026-10-24")=CameraFile("123","DSC00123.JPG",listOf(Resource("http://old/image",size=100,resolution="6000x4000")),date=date,parent="DCIM/100MSDCF")
    @Test fun distinguishesSameModelAndRetainsIdentityAcrossAddressChanges() {
        assertEquals(device(a).cameraId,device(a,"new-address").cameraId)
        assertNotEquals(device(a).cameraId,device(b).cameraId)
        assertNotEquals(queueScope(device(a),CameraMode.JPEG),queueScope(device(a),CameraMode.COMPUTER))
    }
    @Test fun rejectsPlaceholderIdentityAndUsesPtpSerialWhenAvailable() {
        assertNull(stableCameraId("uuid:00000000-0000-0000-0000-000000000000","000000","Sony"))
        assertNull(stableCameraId("","unknown","Sony"))
        assertEquals("serial:ILCE-7RM2:123456",stableCameraId("","123456","ILCE-7RM2"))
        assertNotEquals(queueScope(device(""),CameraMode.JPEG),queueScope(device(""),CameraMode.JPEG))
    }
    @Test fun parsesDescriptorUdnAndSerial() {
        val d=CameraXml.description("<root><device><UDN>$a</UDN><serialNumber>123456</serialNumber></device></root>",URI("http://camera/dd.xml"))
        assertEquals(device(a).cameraId,d.cameraId); assertEquals("123456",d.serial)
    }
    @Test fun persistsSeparateCamerasAndRestartsInterruptedFileFromZero() {
        val file=temporary.newFile(); file.delete()
        val store=TransferQueueStore(file)
        val keyA=queueScope(device(a),CameraMode.JPEG); val keyB=queueScope(device(b),CameraMode.JPEG)
        store.save(keyA,listOf(QueueItem(photo(),"传输中",received=50)))
        store.save(keyB,listOf(QueueItem(photo("2026-10-25"),"完成")))
        val restored=TransferQueueStore(file)
        assertEquals("待恢复",restored.load(keyA).single().state)
        assertEquals(0L,restored.load(keyA).single().received)
        assertEquals(photo(),restored.load(keyA).single().file)
        assertEquals("完成",restored.load(keyB).single().state)
        store.save(keyA,emptyList()); assertTrue(restored.load(keyA).isEmpty()); assertEquals(1,restored.load(keyB).size)
    }
    @Test fun resumesOnlyMatchingMetadataAndRefreshesTheUrl() {
        val old=photo()
        val fresh=old.copy(resources=old.resources.map { it.copy(url="http://new-address/image") })
        assertEquals("http://new-address/image",refreshQueuedFile(old,listOf(fresh))!!.bestResource()!!.url)
        assertNull(refreshQueuedFile(old,listOf(fresh.copy(date="2026-10-25"))))
        assertNull(refreshQueuedFile(old.copy(date=""),listOf(fresh.copy(date=""))))
        assertNull(refreshQueuedFile(old,listOf(fresh.copy(resources=listOf(Resource("http://new",size=99,resolution="6000x4000"))))))
        assertNull(refreshQueuedFile(old,listOf(fresh.copy(parent="another-card-directory"))))
    }
    @Test fun sourceIdsAreIndependentOfTemporaryHostAndSeparateResourceSizes() {
        val f=photo(); val key=queueScope(device(a),CameraMode.JPEG)
        assertEquals(transferSource(key,f,f.bestResource()),transferSource(key,f,f.bestResource()!!.copy(url="http://changed")))
        assertNotEquals(transferSource(key,f,f.bestResource()),transferSource(key,f,f.bestResource()!!.copy(size=50)))
    }
}
