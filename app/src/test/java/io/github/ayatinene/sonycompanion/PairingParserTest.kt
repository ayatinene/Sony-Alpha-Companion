package io.github.ayatinene.sonycompanion

import io.github.ayatinene.sonycompanion.pairing.*
import org.junit.Assert.*
import org.junit.Test

class PairingParserTest {
    // Same observed S/C/M layout, deliberately synthetic password and device identifier.
    private val qr = "W01:S:ZmE0;P:testPass;C:ILCE-7RM2;M:001122334455;"
    private fun reject(raw: String) { assertThrows(IllegalArgumentException::class.java) { SonyQrParser.parse(raw) } }
    private fun tlv(tag: Int, value: ByteArray): ByteArray = byteArrayOf((tag shr 8).toByte(), tag.toByte(), (value.size / 10).toByte(), (value.size % 10).toByte()) + value
    private fun nfc(ssid: String = "DIRECT-ZmE0:ILCE-7RM2", password: String = "testPass") =
        tlv(1, byteArrayOf(0x10)) + tlv(0x1000, ssid.toByteArray()) + tlv(0x1001, password.toByteArray())
    @Test fun observedW01LayoutBuildsTheMeasuredSsid() {
        val result = SonyQrParser.parse(qr)
        assertEquals("DIRECT-ZmE0:ILCE-7RM2", result.ssid)
        assertEquals("testPass", result.password)
    }
    @Test fun macIsOptionalAndNeverRequiredToBuildTheSsid() {
        assertEquals("DIRECT-ab12:DSC-RX100M5", SonyQrParser.parse("W01:C:DSC-RX100M5;P:12345678;S:ab12;").ssid)
    }
    @Test fun escapedDelimitersAndBackslashAreDecoded() {
        val result=SonyQrParser.parse("W01:S:ab12;P:ab\\;cd\\:e\\,f\\\\;C:ILCE-7RM2;")
        assertEquals("ab;cd:e,f\\",result.password)
    }
    @Test fun missingEmptyMalformedDuplicateAndUnknownFieldsAreRejected() {
        listOf("", "W02:S:ab12;P:12345678;C:Camera;", qr.removeSuffix(";"), qr+"\\", qr+";",
            qr.replace("S:ZmE0;", ""), qr.replace("C:ILCE-7RM2;", ""), qr.replace("P:testPass;", ""),
            qr.replace("P:testPass;", "P:;"), qr+"S:xxxx;", qr+"X:foo;", qr.replace("S:","S="),
            qr.replace("001122334455", "invalid"), qr.replace("testPass","bad\\xPass"),
            qr.replace("ZmE0","\u0000")).forEach(::reject)
    }
    @Test fun ssidLimitCountsUtf8BytesAndPassphrasesFollowWpa2Bounds() {
        assertEquals(32, PairingCredentials.create("x".repeat(32),"x".repeat(63)).ssid.length)
        listOf("", " ", "x".repeat(33), "相".repeat(11), "bad\nssid").forEach { ssid ->
            assertThrows(IllegalArgumentException::class.java) { PairingCredentials.create(ssid,"testPass") }
        }
        listOf("", "x".repeat(7), "x".repeat(64), "bad\nPass", "密码密码密码密码", " ".repeat(8)).forEach { password ->
            assertThrows(IllegalArgumentException::class.java) { PairingCredentials.create("camera",password) }
        }
    }
    @Test fun credentialsAndValidationErrorsNeverExposeSecrets() {
        val secret="aSecretPassword"
        assertFalse(PairingCredentials.create("camera",secret).toString().contains(secret))
        val invalid=secret+"\n"
        val error=runCatching { SonyQrParser.parse("W01:S:ab12;C:Cam;P:$invalid;") }.exceptionOrNull()!!
        assertFalse(error.toString().contains(secret))
        assertFalse(error.stackTraceToString().contains(secret))
    }
    @Test fun sonyNfcUsesBinaryDecimalLengthAndCarriesFullCredentials() {
        val parsed=SonyNfcParser.parse(SonyNfcParser.MIME,nfc()+tlv(0x1004,byteArrayOf(1)))
        assertEquals("DIRECT-ZmE0:ILCE-7RM2",parsed.ssid)
        assertEquals("testPass",parsed.password)
        assertEquals(21,parsed.ssid.toByteArray().size)
    }
    @Test fun unknownNfcExtensionsAreSkippedButIncompleteOrUnsupportedRecordsFail() {
        assertEquals("DIRECT-ZmE0:ILCE-7RM2",SonyNfcParser.parse(SonyNfcParser.MIME,nfc()+tlv(0x2000,byteArrayOf(1,2))).ssid)
        listOf(byteArrayOf(), byteArrayOf(0), nfc().dropLast(1).toByteArray(),
            tlv(1,byteArrayOf(0x11))+tlv(0x1000,"camera".toByteArray()),
            nfc()+tlv(0x1000,"duplicate".toByteArray()), nfc()+tlv(0x1004,byteArrayOf(0)),
            tlv(1,byteArrayOf(0x10)), nfc(password=""), nfc(ssid="bad\u0000ssid")).forEach {
                assertThrows(IllegalArgumentException::class.java) { SonyNfcParser.parse(SonyNfcParser.MIME,it) }
            }
        assertThrows(IllegalArgumentException::class.java) { SonyNfcParser.parse("application/vnd.wfa.wsc",nfc()) }
    }
}
