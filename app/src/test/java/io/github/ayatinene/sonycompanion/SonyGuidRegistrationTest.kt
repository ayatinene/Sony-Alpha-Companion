package io.github.ayatinene.sonycompanion

import io.github.ayatinene.sonycompanion.protocol.SonyGuidRegistration
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

class SonyGuidRegistrationTest {
    private val guid=ByteArray(16) { it.toByte() }
    private fun status(tag: Int=1, result: Int=0)=ByteBuffer.allocate(13).order(ByteOrder.LITTLE_ENDIAN)
        .putInt(0x53425355).putInt(tag).putInt(0).put(result.toByte()).array()

    @Test fun payloadMatchesTheDocumentedSonyRegistrationLayout() {
        val bytes=SonyGuidRegistration.payload(guid)
        assertEquals(8192,bytes.size)
        assertArrayEquals(byteArrayOf(0x25,0x01,0,0,0x08),bytes.copyOfRange(0,5))
        assertEquals("Alpha",String(bytes.copyOfRange(17,27),Charsets.UTF_16LE))
        assertArrayEquals(byteArrayOf(0x10,0,0,0),bytes.copyOfRange(177,181))
        assertArrayEquals(guid,bytes.copyOfRange(181,197))
        assertTrue(bytes.copyOfRange(197,8192).all { it==0.toByte() })
    }
    @Test fun commandContainsTheVendorCdbAndExactDataLength() {
        val bytes=SonyGuidRegistration.command(0x12345678,0)
        assertEquals(31,bytes.size)
        assertEquals("USBC",String(bytes.copyOfRange(0,4),Charsets.US_ASCII))
        val fields=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0x12345678,fields.getInt(4)); assertEquals(8192,fields.getInt(8))
        assertEquals(0,bytes[12].toInt()); assertEquals(9,bytes[14].toInt())
        assertArrayEquals(byteArrayOf(0x7a,0x04,0,0,0,0,0,0,0),bytes.copyOfRange(15,24))
    }
    @Test fun registrationSendsCommandThenPayloadAndChecksTheReply() {
        val sent=mutableListOf<ByteArray>()
        SonyGuidRegistration.register(guid,write={ sent+=it;it.size },readStatus={ assertEquals(2,sent.size);status() })
        assertEquals(listOf(31,8192),sent.map { it.size })
        assertArrayEquals(guid,sent[1].copyOfRange(181,197))
    }
    @Test fun shortCommandWriteStopsBeforeSendingTheGuid() {
        var writes=0
        try {
            SonyGuidRegistration.register(guid,write={ writes++;30 },readStatus={ fail("must not read after short command");status() })
            fail()
        } catch(_: IOException) { assertEquals(1,writes) }
    }
    @Test fun cameraFailureAndMismatchedTransactionAreReported() {
        for(bytes in listOf(status(result=1),status(tag=2),status().copyOf(12))) {
            try { SonyGuidRegistration.checkStatus(bytes,1); fail() } catch(_: IOException) { }
        }
    }
}
