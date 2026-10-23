package io.github.ayatinene.sonycompanion

import io.github.ayatinene.sonycompanion.data.*
import io.github.ayatinene.sonycompanion.protocol.CameraXml
import org.junit.Assert.*
import org.junit.Test
import java.net.URI

class CameraIdentityTest {
    @Test fun descriptionUsesTheAdvertisedDigitalImagingUrl() {
        val xml="""<root><URLBase>http://camera:64321/</URLBase><device><friendlyName>ILCE-7RM2</friendlyName><modelName>SonyImagingDevice</modelName><serviceList><service><serviceType>urn:schemas-sony-com:service:DigitalImaging:1</serviceType><SCPDURL>/DigitalImagingDesc.xml</SCPDURL></service></serviceList></device></root>"""
        val device=CameraXml.description(xml,URI("http://camera/dd.xml"))
        assertEquals("http://camera:64321/DigitalImagingDesc.xml",device.digitalImaging.toString())
        assertEquals("ILCE-7RM2",device.displayName)
    }
    @Test fun namespacedIdentityIncludesLensFirmwareAndInstalledApplications() {
        val xml="""<root xmlns:av="urn:schemas-sony-com:av"><av:X_DeviceInfo><av:X_ModelName>ILCE-7RM2</av:X_ModelName><av:X_FirmwareVersion>4.01</av:X_FirmwareVersion><av:X_LensModelName>28-200mm F2.8-5.6</av:X_LensModelName><av:X_LensFirmwareVersion>01</av:X_LensFirmwareVersion><av:X_InstalledPlayMemoriesCameraApps><av:X_PlayMemoriesCameraApps_App num="1"><av:X_PlayMemoriesCameraApps_AppName>Smart Remote Control</av:X_PlayMemoriesCameraApps_AppName><av:X_PlayMemoriesCameraApps_AppVersion>4.00</av:X_PlayMemoriesCameraApps_AppVersion></av:X_PlayMemoriesCameraApps_App></av:X_InstalledPlayMemoriesCameraApps></av:X_DeviceInfo></root>"""
        val info=CameraXml.identity(xml)
        assertEquals("4.01",info.firmware); assertEquals("28-200mm F2.8-5.6",info.lens)
        assertEquals("01",info.lensFirmware)
        assertEquals(listOf(CameraApplication("Smart Remote Control","4.00")),info.applications)
    }
    @Test fun absentOptionalIdentityFieldsStayEmpty() {
        assertEquals(CameraIdentity(),CameraXml.identity("<root/>"))
        val info=CameraXml.identity("<root><X_LensModelNumber>A071</X_LensModelNumber></root>")
        assertEquals("A071",info.lens); assertTrue(info.applications.isEmpty())
    }
}
