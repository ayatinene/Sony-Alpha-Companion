package io.github.ayatinene.sonycompanion

import io.github.ayatinene.sonycompanion.data.StreamCopy
import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.util.concurrent.CancellationException

class StreamCopyTest {
    @Test fun preservesBytesAndReportsFullLength() {
        val input=ByteArray(1024*1024+17){(it%251).toByte()};val output=ByteArrayOutputStream()
        var last=0L
        assertEquals(input.size.toLong(),StreamCopy.copy(ByteArrayInputStream(input),output,input.size.toLong(),progress={read,total->assertTrue(read>last);assertEquals(input.size.toLong(),total);last=read}))
        assertArrayEquals(input,output.toByteArray());assertEquals(input.size.toLong(),last)
    }
    @Test(expected=EOFException::class) fun rejectsShortTransfer() { StreamCopy.copy(ByteArrayInputStream(byteArrayOf(1,2)),ByteArrayOutputStream(),100) }
    @Test(expected=EOFException::class) fun rejectsEmptyUnknownLengthTransfer() { StreamCopy.copy(ByteArrayInputStream(byteArrayOf()),ByteArrayOutputStream(),-1) }
    @Test fun cancellationStopsBeforeNextWrite() {
        var checks=0;val output=ByteArrayOutputStream()
        try { StreamCopy.copy(ByteArrayInputStream(ByteArray(200000)),output,200000,checkActive={if(++checks==2)throw CancellationException()});fail() }
        catch(_:CancellationException){assertEquals(65536,output.size())}
    }
}
