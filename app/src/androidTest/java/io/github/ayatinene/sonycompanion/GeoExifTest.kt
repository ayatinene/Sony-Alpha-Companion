package io.github.ayatinene.sonycompanion

import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ayatinene.sonycompanion.data.GeoSnapshot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CancellationException

@RunWith(AndroidJUnit4::class)
class GeoExifTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val controller get() = (context.applicationContext as CompanionApplication).controller

    private data class Fixture(val bytes: ByteArray, val dateTimeOriginal: String?, val exposureTime: String?)

    private fun jpeg(width: Int = 80, height: Int = 60): ByteArray =
        ByteArrayOutputStream().also { out ->
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(0xff336699.toInt()) }
                .compress(Bitmap.CompressFormat.JPEG, 90, out)
        }.toByteArray()

    /** A camera-like JPEG that already carries DateTimeOriginal and an exposure time. */
    private fun fixtureJpeg(): Fixture {
        val file = File.createTempFile("fixture-", ".jpg", context.cacheDir)
        file.writeBytes(jpeg())
        ExifInterface(file.absolutePath).apply {
            setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2020:05:06 07:08:09")
            setAttribute(ExifInterface.TAG_EXPOSURE_TIME, "1/250")
            saveAttributes()
        }
        val read = ExifInterface(file.absolutePath)
        val fixture = Fixture(file.readBytes(), read.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL),
            read.getAttribute(ExifInterface.TAG_EXPOSURE_TIME))
        file.delete()
        return fixture
    }

    private fun exif(uri: Uri): ExifInterface =
        context.contentResolver.openInputStream(uri)!!.use { ExifInterface(it) }

    /** The JPEG compressed scan data from the SOS marker to the end; an EXIF edit must not touch it. */
    private fun scanBytes(jpeg: ByteArray): ByteArray {
        var i = 2
        while (i + 3 < jpeg.size) {
            if ((jpeg[i].toInt() and 0xFF) != 0xFF) { i++; continue }
            val marker = jpeg[i + 1].toInt() and 0xFF
            if (marker == 0xDA) return jpeg.copyOfRange(i, jpeg.size)
            if (marker == 0xD8 || marker in 0xD0..0xD7 || marker == 0x01) { i += 2; continue }
            val length = ((jpeg[i + 2].toInt() and 0xFF) shl 8) or (jpeg[i + 3].toInt() and 0xFF)
            if (length < 2) break
            i += 2 + length
        }
        return ByteArray(0)
    }

    private fun snapshot() = GeoSnapshot(35.681236, 139.767125, 8.5f, 1_700_000_000_000L, SystemClock.elapsedRealtimeNanos())

    private fun storedBytes(uri: Uri): ByteArray =
        context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }

    @Test fun gpsIsAddedWithoutReencodingOrChangingOriginalCaptureMetadata() {
        val fixture = fixtureJpeg()
        val originalScan = scanBytes(fixture.bytes)
        val photo = runBlocking {
            controller.photos.import("GEO_${System.nanoTime()}.JPG", false, fixture.bytes.size.toLong(), "geo-test", "遥控拍摄",
                geotag = snapshot(), log = {}) { it.write(fixture.bytes) }
        }
        try {
            val read = exif(Uri.parse(photo.uri))
            val latLong = FloatArray(2)
            assertTrue("GPS latitude/longitude must be present", read.getLatLong(latLong))
            assertEquals(35.681236, latLong[0].toDouble(), 0.001)
            assertEquals(139.767125, latLong[1].toDouble(), 0.001)
            assertEquals("2023:11:14", read.getAttribute(ExifInterface.TAG_GPS_DATESTAMP))
            assertEquals("22:13:20", read.getAttribute(ExifInterface.TAG_GPS_TIMESTAMP))
            assertEquals(8.5, read.getAttributeDouble(ExifInterface.TAG_GPS_H_POSITIONING_ERROR, -1.0), 0.5)
            // The camera's own capture metadata survives untouched.
            assertEquals(fixture.dateTimeOriginal, read.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))
            assertEquals(fixture.exposureTime, read.getAttribute(ExifInterface.TAG_EXPOSURE_TIME))
            // The compressed scan data is byte-identical: no re-encode.
            assertTrue("scan data must be untouched", scanBytes(storedBytes(Uri.parse(photo.uri))).contentEquals(originalScan))
            assertEquals("the received byte count stays available for dedup", fixture.bytes.size.toLong(), photo.sourceBytes)
        } finally { runCatching { controller.photos.deleteFile(photo) } }
    }

    @Test fun writeTimeGateSkipsGpsAndKeepsTheOriginal() {
        val fixture = fixtureJpeg()
        val photo = runBlocking {
            controller.photos.import("NOGATE_${System.nanoTime()}.JPG", false, fixture.bytes.size.toLong(), "geo-test", "遥控拍摄",
                geotag = snapshot(), canWriteGeotag = { false }, log = {}) { it.write(fixture.bytes) }
        }
        try {
            val latLong = FloatArray(2)
            assertFalse(exif(Uri.parse(photo.uri)).getLatLong(latLong))
            assertEquals(fixture.bytes.size.toLong(), photo.sourceBytes)
        } finally { runCatching { controller.photos.deleteFile(photo) } }
    }

    @Test fun gpsGateRecheckedBeforeWriteBackKeepsTheOriginal() {
        val fixture = fixtureJpeg()
        var checks = 0
        val photo = runBlocking {
            controller.photos.import("RECHECK_${System.nanoTime()}.JPG", false, fixture.bytes.size.toLong(), "geo-test", "遥控拍摄",
                geotag = snapshot(), canWriteGeotag = { checks++ == 0 }, log = {}) { it.write(fixture.bytes) }
        }
        try {
            val latLong = FloatArray(2)
            assertFalse("a mid-run opt-out must not write GPS", exif(Uri.parse(photo.uri)).getLatLong(latLong))
            assertTrue("the gate is checked before staging and before the write-back", checks >= 2)
        } finally { runCatching { controller.photos.deleteFile(photo) } }
    }

    @Test fun cancellingDuringGpsStagingPublishesNothing() {
        val fixture = fixtureJpeg()
        val before = controller.photos.list().map { it.uri }.toSet()
        var calls = 0
        val thrown = runCatching {
            runBlocking {
                controller.photos.import("CANCEL_${System.nanoTime()}.JPG", false, fixture.bytes.size.toLong(), "geo-test", "遥控拍摄",
                    geotag = snapshot(), checkActive = { if (++calls >= 4) throw CancellationException("cancel") }, log = {}) { it.write(fixture.bytes) }
            }
        }.exceptionOrNull()
        assertTrue("cancellation must propagate, not be swallowed", thrown is CancellationException)
        assertEquals("no record may be published on cancel", before, controller.photos.list().map { it.uri }.toSet())
    }

    @Test fun importWithoutAGpsFixLeavesTheFileUntouched() {
        val bytes = jpeg(40, 30)
        val photo = runBlocking {
            controller.photos.import("NOGEO_${System.nanoTime()}.JPG", false, bytes.size.toLong(), "geo-test", "遥控拍摄") { it.write(bytes) }
        }
        try {
            val latLong = FloatArray(2)
            assertFalse("no opt-in fix means no GPS block", exif(Uri.parse(photo.uri)).getLatLong(latLong))
            assertEquals(bytes.size.toLong(), photo.bytes)
        } finally { runCatching { controller.photos.deleteFile(photo) } }
    }
}
