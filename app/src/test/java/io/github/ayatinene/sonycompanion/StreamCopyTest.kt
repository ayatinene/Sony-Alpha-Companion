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
    @Test fun unknownLengthOriginalPreservesEveryByte() {
        val original=ByteArray(3*65536+19){(it%253).toByte()};val output=ByteArrayOutputStream()
        var received=0L
        assertEquals(original.size.toLong(),StreamCopy.copy(ByteArrayInputStream(original),output,-1,progress={read,total->
            received=read;assertEquals(-1L,total)
        }))
        assertEquals(original.size.toLong(),received)
        assertArrayEquals(original,output.toByteArray())
    }
    @Test(expected=EOFException::class) fun rejectsLongerThanDeclaredTransfer() {
        StreamCopy.copy(ByteArrayInputStream(byteArrayOf(1,2,3)),ByteArrayOutputStream(),2)
    }
    @Test fun interruptedReadDoesNotReportCompletion() {
        var reads=0;var received=0L;val output=ByteArrayOutputStream()
        val input=object:InputStream() {
            override fun read():Int=throw IOException("connection lost")
            override fun read(bytes:ByteArray,offset:Int,length:Int):Int {
                if(reads++>0) throw IOException("connection lost")
                bytes.fill(7,offset,offset+length);return length
            }
        }
        try {
            StreamCopy.copy(input,output,200000,progress={read,_->received=read});fail()
        } catch(e:IOException) {
            assertEquals("connection lost",e.message)
            assertEquals(65536L,received);assertEquals(65536,output.size())
        }
    }
    @Test(expected=EOFException::class) fun rejectsShortTransfer() { StreamCopy.copy(ByteArrayInputStream(byteArrayOf(1,2)),ByteArrayOutputStream(),100) }
    @Test(expected=EOFException::class) fun rejectsEmptyUnknownLengthTransfer() { StreamCopy.copy(ByteArrayInputStream(byteArrayOf()),ByteArrayOutputStream(),-1) }
    @Test fun cancellationStopsBeforeNextWrite() {
        var checks=0;val output=ByteArrayOutputStream()
        try { StreamCopy.copy(ByteArrayInputStream(ByteArray(200000)),output,200000,checkActive={if(++checks==2)throw CancellationException()});fail() }
        catch(_:CancellationException){assertEquals(65536,output.size())}
    }
}
