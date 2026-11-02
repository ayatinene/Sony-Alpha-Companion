package io.github.ayatinene.sonycompanion

import com.fimagena.libptp.PtpExceptions
import com.fimagena.libptp.ptpip.PtpIpExceptions
import io.github.ayatinene.sonycompanion.data.SessionPriority
import io.github.ayatinene.sonycompanion.data.SessionScheduler
import io.github.ayatinene.sonycompanion.protocol.ComputerTransfer
import io.github.ayatinene.sonycompanion.protocol.GracefulCloser
import io.github.ayatinene.sonycompanion.protocol.TransactionRunner
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.io.OutputStream
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Exercises the real transaction/close wrappers [ComputerTransfer] delegates to. */
class PtpTransactionTest {
    @Test fun outputFailureAfterAStartedTransactionAbortsTheSession() = runBlocking {
        val aborted = AtomicBoolean(false)
        val runner = TransactionRunner(SessionScheduler(), { aborted.set(true) }, { ComputerTransfer.isCompleteResponse(it) })
        val failure = runCatching {
            runner.run<String, Unit>(SessionPriority.DOWNLOAD, { false }, { "session" }) { _ ->
                val output = object : OutputStream() {
                    override fun write(b: Int) { throw IOException("磁盘写入失败") }
                }
                output.write(1)
            }
        }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertTrue("a partial transaction must abort the transport", aborted.get())
    }

    @Test fun aCompleteOperationFailedKeepsTheSessionUsable() = runBlocking {
        val aborted = AtomicBoolean(false)
        val runner = TransactionRunner(SessionScheduler(), { aborted.set(true) }, { ComputerTransfer.isCompleteResponse(it) })
        // PtpSession throws PtpExceptions.OperationFailed for a complete response (e.g. no thumbnail).
        val failure = runCatching {
            runner.run<String, Unit>(SessionPriority.PREVIEW, { false }, { "session" }) {
                throw PtpExceptions.OperationFailed("GetThumb", 0x2009)
            }
        }.exceptionOrNull()
        assertTrue(failure is PtpExceptions.OperationFailed)
        assertFalse("a complete response reached the transaction boundary; the session stays valid", aborted.get())
    }

    @Test fun aDeviceCancelledTransactionAbortsTheSession() = runBlocking {
        val aborted = AtomicBoolean(false)
        val runner = TransactionRunner(SessionScheduler(), { aborted.set(true) }, { ComputerTransfer.isCompleteResponse(it) })
        // A cancel event with response code 0 is a half transaction: the transport must be aborted.
        val failure = runCatching {
            runner.run<String, Unit>(SessionPriority.DOWNLOAD, { false }, { "session" }) {
                throw PtpIpExceptions.OperationFailed("Device cancelled transaction!", 0L)
            }
        }.exceptionOrNull()
        assertTrue(failure is PtpIpExceptions.OperationFailed)
        assertTrue("a device-cancelled half transaction must abort", aborted.get())
    }

    @Test fun cancellingAStartedTransactionAbortsImmediately() = runBlocking {
        val aborted = AtomicBoolean(false)
        val runner = TransactionRunner(SessionScheduler(), { aborted.set(true) })
        val gate = CompletableDeferred<Unit>()
        val job = launch(Dispatchers.Default) {
            try { runner.run<String, Unit>(SessionPriority.SCAN, { false }, { "session" }) { gate.await() } }
            catch (_: CancellationException) { }
        }
        delay(80)
        assertFalse(aborted.get())
        job.cancelAndJoin()
        assertTrue("cancelling a started transaction must abort to unblock the transport", aborted.get())
    }

    @Test fun closedSessionNeverAbortsOrRunsTheBlock() = runBlocking {
        val aborted = AtomicBoolean(false)
        val ran = AtomicBoolean(false)
        val runner = TransactionRunner(SessionScheduler(), { aborted.set(true) })
        val failure = runCatching {
            runner.run<String, Unit>(SessionPriority.SCAN, { true }, { "session" }) { ran.set(true) }
        }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertFalse(ran.get())
        assertFalse(aborted.get())
    }

    @Test fun aQueuedTransactionWaitsForTheAbortOfAFailedOne() = runBlocking {
        val order = Collections.synchronizedList(mutableListOf<String>())
        val aborted = AtomicBoolean(false)
        val gate = CompletableDeferred<Unit>()
        val runner = TransactionRunner(SessionScheduler(), { aborted.set(true); order += "abort" })
        val first = launch(Dispatchers.Default) {
            runCatching {
                runner.run<String, Unit>(SessionPriority.SCAN, { false }, { "session" }) {
                    order += "tx1"; gate.await(); throw IOException("partial")
                }
            }
        }
        while (order.isEmpty()) delay(5)
        val second = launch(Dispatchers.Default) {
            runner.run<String, Unit>(SessionPriority.SCAN, { false }, { "session" }) { order += "tx2" }
        }
        delay(80)
        assertEquals("the queued transaction must not start while the first holds the lock", listOf("tx1"), order.toList())
        gate.complete(Unit)
        first.join(); second.join()
        assertEquals(listOf("tx1", "abort", "tx2"), order.toList())
        assertTrue(aborted.get())
    }

    @Test fun gracefulCloseReturnsAndAlwaysAborts() = runBlocking {
        val aborted = AtomicInteger(0)
        val closer = GracefulCloser(1_000)
        closer.close(blockingClose = {}, abort = { aborted.incrementAndGet() })
        assertTrue(aborted.get() > 0)
    }

    @Test fun cancellingDuringABlockingCloseStillReachesAbort() = runBlocking {
        val aborted = AtomicBoolean(false)
        val released = CountDownLatch(1)
        val closer = GracefulCloser(5_000)
        val job = launch(Dispatchers.Default) {
            closer.close(blockingClose = { released.await() }, abort = { aborted.set(true); released.countDown() })
        }
        delay(80)
        assertFalse(aborted.get())
        job.cancel()
        assertTrue("the caller cancellation must still unblock the detached close", withTimeout(3_000) { job.join(); true })
        assertTrue(aborted.get())
    }

    @Test fun blockingCloseIsBoundedByTheGraceAndAborted() = runBlocking {
        val aborted = AtomicBoolean(false)
        val timedOut = AtomicBoolean(false)
        val neverReleased = CountDownLatch(1)
        val closer = GracefulCloser(120)
        val elapsed = withTimeout(3_000) {
            val start = System.nanoTime()
            closer.close(
                blockingClose = { neverReleased.await() },
                abort = { aborted.set(true) },
                onTimeout = { timedOut.set(true) })
            (System.nanoTime() - start) / 1_000_000
        }
        assertTrue("close must be bounded, was ${elapsed}ms", elapsed < 2_000)
        assertTrue(timedOut.get())
        assertTrue(aborted.get())
        neverReleased.countDown()
    }
}
