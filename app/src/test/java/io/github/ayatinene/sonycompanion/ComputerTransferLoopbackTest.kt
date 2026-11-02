package io.github.ayatinene.sonycompanion

import com.fimagena.libptp.PtpDataType
import com.fimagena.libptp.ptpip.DataBuffer
import com.fimagena.libptp.ptpip.PtpIpConnection
import com.fimagena.libptp.ptpip.PtpIpPacket
import io.github.ayatinene.sonycompanion.protocol.ComputerTransfer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

/**
 * Real [ComputerTransfer] against a loopback PTP/IP mock: the socket factory and close grace are
 * injected so the production connect/finish path runs without an Android Network. Verifies the exact
 * Sony wire sequence, timeout teardown and the busy-session gate.
 */
class ComputerTransferLoopbackTest {
    private companion object {
        val START_21 = byteArrayOf(2,0,0,0, 2,0,0,0, 0,0,0,0, 0,0,0,0, 1,0,2,0, 0)
        val SLEEP_19 = byteArrayOf(0,0,0,0, 5,0,0,0, 0,0,0,0, 0,0,0,0, 2,0, 0x30)
        const val GUID = "00:01:02:03:04:05:06:07:08:09:0a:0b:0c:0d:0e:0f"
    }

    /** Connects to the mock regardless of the address ComputerTransfer hard-codes. */
    private class RedirectSocket(private val target: InetSocketAddress) : Socket() {
        override fun connect(endpoint: SocketAddress, timeout: Int) {super.connect(target, timeout)}
    }
    private class RedirectFactory(private val target: InetSocketAddress) : SocketFactory() {
        override fun createSocket(): Socket = RedirectSocket(target)
        override fun createSocket(host: String, port: Int): Socket = createSocket()
        override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket = createSocket()
        override fun createSocket(host: InetAddress, port: Int): Socket = createSocket()
        override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket = createSocket()
    }

    private inner class MockSonyServer(
        private val blockSleepAck: Boolean = false,
        private val blockThumb: Boolean = false
    ) {
        private val listener = ServerSocket(0)
        val address: InetSocketAddress = InetSocketAddress(InetAddress.getLoopbackAddress(), listener.localPort)
        val ops = CopyOnWriteArrayList<Int>()
        val txs = CopyOnWriteArrayList<Long>()
        val params = CopyOnWriteArrayList<List<Long>>()
        val dataOut = CopyOnWriteArrayList<ByteArray>()
        val startDataLengths = CopyOnWriteArrayList<Long>()
        val sleepAckReached = CountDownLatch(1)
        val unblockSleepAck = CountDownLatch(1)
        val thumbReached = CountDownLatch(1)
        val unblockThumb = CountDownLatch(1)
        val finished = CountDownLatch(1)

        fun run() {
            try {
                listener.accept().use { command ->
                    command.soTimeout = 10000
                    readPacket(command.getInputStream())
                    send(command.getOutputStream(), PtpIpPacket.InitCommandAck(1L, PtpIpConnection.PtpIpHostId(ShortArray(16), "Mock")))
                    listener.accept().use { event ->
                        event.soTimeout = 10000
                        readPacket(event.getInputStream())
                        send(event.getOutputStream(), PtpIpPacket.InitEventAck())
                        serve(command)
                    }
                }
            } catch (e: Exception) {
                // The client tears the sockets down when the test ends.
            } finally {
                finished.countDown()
                runCatching { listener.close() }
            }
        }

        private fun serve(command: Socket) {
            var startRequests = 0
            var sleepPending = false
            while (true) {
                val request = readPacket(command.getInputStream()) ?: return
                val body = DataBuffer(request.copyOfRange(12, request.size))
                val op = body.readUInt16()
                val tx = body.readUInt32()
                val requestParams = List(body.available() / 4) {body.readUInt32()}
                ops.add(op); txs.add(tx); params.add(requestParams)
                when (op) {
                    0x1001 -> result(command.getOutputStream(), tx, info())
                    0x1002, 0x1003 -> result(command.getOutputStream(), tx, null)
                    0x9280 -> {
                        val (declared, payload) = readDataOut(command.getInputStream())
                        startDataLengths.add(declared); dataOut.add(payload)
                        startRequests++
                        sleepPending = startRequests == 2
                        result(command.getOutputStream(), tx, null)
                    }
                    0x9281 -> if (blockSleepAck && sleepPending) {
                        sleepAckReached.countDown()
                        unblockSleepAck.await(8, TimeUnit.SECONDS)
                        result(command.getOutputStream(), tx, byteArrayOf(0x11))
                    } else result(command.getOutputStream(), tx, byteArrayOf(0x11, 0x22, 0x33))
                    0x100a -> {
                        if (blockThumb) {thumbReached.countDown(); unblockThumb.await(8, TimeUnit.SECONDS)}
                        result(command.getOutputStream(), tx, byteArrayOf(0x77))
                    }
                    else -> throw AssertionError("unexpected operation 0x${op.toString(16)}")
                }
            }
        }
    }

    private fun readPacket(inp: InputStream): ByteArray? {
        val header = inp.readNBytes(8)
        if (header.size < 8) return null
        val length = DataBuffer(header).readUInt32().toInt()
        val rest = inp.readNBytes(length - 8)
        if (rest.size < length - 8) return null
        return header + rest
    }

    private fun readDataOut(inp: InputStream): Pair<Long, ByteArray> {
        val start = readPacket(inp) ?: return 0L to ByteArray(0)
        val declared = DataBuffer(start.copyOfRange(12, 20)).readUInt64()
        val out = ByteArrayOutputStream()
        while (true) {
            val packet = readPacket(inp) ?: break
            val type = DataBuffer(packet.copyOfRange(4, 8)).readUInt32()
            when (type) {
                0x0cL -> {out.write(packet.copyOfRange(12, packet.size)); break} // EndData
                0x0aL -> out.write(packet.copyOfRange(12, packet.size))         // Data
            }
        }
        return declared to out.toByteArray()
    }

    private fun send(out: OutputStream, packet: PtpIpPacket) {
        out.write(packet.serializePacket()); out.flush()
    }

    private fun result(out: OutputStream, tx: Long, data: ByteArray?) {
        if (data != null) {
            send(out, PtpIpPacket.StartData(tx, data.size.toLong()))
            send(out, PtpIpPacket.EndData(tx, DataBuffer(data)))
        }
        send(out, PtpIpPacket.OperationResponse(0x2001, tx, LongArray(0)))
    }

    private fun info(): ByteArray {
        val b = DataBuffer()
        b.writeUInt16(100).writeUInt32(6L).writeUInt16(100).writeUInt8(0.toShort()).writeUInt16(0)
        b.writeUInt32(5L)
        for (op in intArrayOf(0x1001, 0x1002, 0x1009, 0x9280, 0x9281)) b.writeUInt16(op)
        repeat(4) { b.writeUInt32(0L) }
        for (s in listOf("Sony Corporation", "ILCE-7RM2 synthetic", "4.01", "test")) PtpDataType.PtpString(s).writeToBuffer(b)
        return b.getData()
    }

    private fun transfer(server: MockSonyServer, graceMillis: Long = 300L): ComputerTransfer =
        ComputerTransfer(null, RedirectFactory(server.address), closeGraceMillis = graceMillis) { _, _, _ -> }

    private fun MockSonyServer.start(): Thread = Thread { this.run() }.apply { isDaemon = true; start() }

    @Test fun explicitSleepSendsTheSonyPairThenCloseSession() = runBlocking {
        val server = MockSonyServer()
        server.start()
        val transfer = transfer(server)
        try {
            transfer.connect("127.0.0.1", GUID)
            transfer.finish(sleepCamera = true)
        } finally {
            transfer.abort()
        }
        assertTrue("the mock server must finish", server.finished.await(5, TimeUnit.SECONDS))
        assertEquals(listOf(0x1001, 0x1002, 0x9280, 0x9281, 0x9280, 0x9281, 0x1003), server.ops.toList())
        assertEquals(2, server.dataOut.size)
        assertArrayEquals(START_21, server.dataOut[0])
        assertArrayEquals(SLEEP_19, server.dataOut[1])
        // Parameter 4 on every Sony pair request, and StartData declares the real payload size.
        server.ops.forEachIndexed { i, op ->
            if (op == 0x9280 || op == 0x9281) assertEquals("op 0x${op.toString(16)} must use param 4", listOf(4L), server.params[i])
        }
        assertEquals(21L, server.startDataLengths[0])
        assertEquals(19L, server.startDataLengths[1])
        // Transaction ids strictly increase across the non-null transactions.
        val nonZero = server.txs.filter { it != 0L }
        for (i in 1 until nonZero.size) assertTrue("transaction ids must increase: $nonZero", nonZero[i] > nonZero[i - 1])
    }

    @Test fun defaultFinishSendsOnlyCloseSession() = runBlocking {
        val server = MockSonyServer()
        server.start()
        val transfer = transfer(server)
        try {
            transfer.connect("127.0.0.1", GUID)
            transfer.finish()
        } finally {
            transfer.abort()
        }
        assertTrue("the mock server must finish", server.finished.await(5, TimeUnit.SECONDS))
        assertEquals(listOf(0x1001, 0x1002, 0x9280, 0x9281, 0x1003), server.ops.toList())
        assertEquals(1, server.dataOut.size)
        assertArrayEquals(START_21, server.dataOut[0])
    }

    @Test fun finishIsIdempotentAndSendsASingleCloseSession() = runBlocking {
        val server = MockSonyServer()
        server.start()
        val transfer = transfer(server)
        try {
            transfer.connect("127.0.0.1", GUID)
            transfer.finish()
            transfer.finish()
        } finally {
            transfer.abort()
        }
        assertTrue("the mock server must finish", server.finished.await(5, TimeUnit.SECONDS))
        assertEquals(1, server.ops.count { it == 0x1003 })
    }

    @Test fun aBlockedSleepAckTimesOutWithoutSendingCloseSession() = runBlocking {
        val server = MockSonyServer(blockSleepAck = true)
        server.start()
        val transfer = transfer(server)
        try {
            transfer.connect("127.0.0.1", GUID)
            val elapsed = withTimeout(3_000) {
                val start = System.nanoTime()
                transfer.finish(sleepCamera = true)
                (System.nanoTime() - start) / 1_000_000
            }
            assertTrue("finish must be bounded, was ${elapsed}ms", elapsed < 2_500)
            assertTrue("the sleep ack must have been reached", server.sleepAckReached.await(1, TimeUnit.SECONDS))
            assertEquals("the sleep pair must be attempted", 2, server.dataOut.size)
            assertArrayEquals(SLEEP_19, server.dataOut[1])
            assertFalse("a timed-out sleep must not send CloseSession", server.ops.contains(0x1003))
        } finally {
            server.unblockSleepAck.countDown()
            transfer.abort()
        }
        assertTrue("the mock server must finish", server.finished.await(5, TimeUnit.SECONDS))
    }

    @Test fun aBusyThumbnailMakesFinishAbortWithoutSleeping() = runBlocking {
        val server = MockSonyServer(blockThumb = true)
        server.start()
        val transfer = transfer(server)
        try {
            transfer.connect("127.0.0.1", GUID)
            val thumb = launch(Dispatchers.IO) { runCatching { transfer.thumbnail(42L) } }
            assertTrue("the thumbnail must reach the camera", server.thumbReached.await(5, TimeUnit.SECONDS))
            val elapsed = withTimeout(3_000) {
                val start = System.nanoTime()
                transfer.finish(sleepCamera = true)
                (System.nanoTime() - start) / 1_000_000
            }
            assertTrue("a busy session must abort promptly, was ${elapsed}ms", elapsed < 2_000)
            assertEquals("a busy session must not send the sleep pair", 1, server.dataOut.size)
            assertFalse("a busy session must not send CloseSession", server.ops.contains(0x1003))
            server.unblockThumb.countDown()
            thumb.join()
        } finally {
            server.unblockThumb.countDown()
            transfer.abort()
        }
        assertTrue("the mock server must finish", server.finished.await(5, TimeUnit.SECONDS))
    }
}
