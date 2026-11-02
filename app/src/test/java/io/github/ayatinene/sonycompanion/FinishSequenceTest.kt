package io.github.ayatinene.sonycompanion

import io.github.ayatinene.sonycompanion.data.SessionPriority
import io.github.ayatinene.sonycompanion.data.SessionScheduler
import io.github.ayatinene.sonycompanion.protocol.ComputerTransfer
import io.github.ayatinene.sonycompanion.protocol.FinishSequence
import io.github.ayatinene.sonycompanion.protocol.GracefulCloser
import io.github.ayatinene.sonycompanion.protocol.SessionGate
import io.github.ayatinene.sonycompanion.protocol.TransactionRunner
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Exercises the real production finish sequence, lifecycle gate and transaction runner. */
class FinishSequenceTest {
    /** Waits until the detached finish worker released the transaction lock, i.e. it terminated. */
    private suspend fun awaitWorkerRelease(scheduler: SessionScheduler) {
        withTimeout(2_000) {
            while (!scheduler.tryAcquireIdle()) delay(5)
            scheduler.releaseIdle()
        }
    }

    @Test fun defaultFinishRunsOnlyCloseAndAlwaysTearsDown() = runBlocking {
        val order = Collections.synchronizedList(mutableListOf<String>())
        FinishSequence(1_000).run(SessionScheduler(), sleep = null,
            close = { order += "close" }, abort = { order += "abort" })
        assertEquals(listOf("close", "abort"), order.toList())
    }

    @Test fun explicitSleepRunsThePairThenCloseInOrder() = runBlocking {
        val order = Collections.synchronizedList(mutableListOf<String>())
        FinishSequence(1_000).run(SessionScheduler(), sleep = { order += "sleep" },
            close = { order += "close" }, abort = { order += "abort" })
        assertEquals(listOf("sleep", "close", "abort"), order.toList())
    }

    @Test fun sleepFailureStopsTheSequenceBeforeClose() = runBlocking {
        val order = Collections.synchronizedList(mutableListOf<String>())
        FinishSequence(1_000).run(SessionScheduler(), sleep = { order += "sleep"; throw IOException("sleep failed") },
            close = { order += "close" }, abort = { order += "abort" })
        assertEquals(listOf("sleep", "abort"), order.toList())
    }

    @Test fun aRealSleepFailureIsReported() = runBlocking {
        val reported = CompletableDeferred<Throwable>()
        FinishSequence(1_000).run(SessionScheduler(),
            sleep = { throw IOException("sleep failed") }, close = {}, abort = {},
            onFailure = { reported.complete(it) })
        assertTrue("the real close failure must be surfaced, was ${reported.await()}", reported.await() is IOException)
    }

    @Test fun finishHoldsTheTransactionLockForTheWholeClose() = runBlocking {
        val scheduler = SessionScheduler()
        val closeStarted = CountDownLatch(1)
        val release = CountDownLatch(1)
        val ran = CountDownLatch(1)
        val closer = launch(Dispatchers.Default) {
            FinishSequence(5_000).run(scheduler, sleep = null,
                close = { closeStarted.countDown(); release.await() }, abort = {})
        }
        assertTrue(closeStarted.await(5, TimeUnit.SECONDS))
        val queued = launch(Dispatchers.Default) { scheduler.run(SessionPriority.SCAN) { ran.countDown() } }
        assertFalse("the transaction lock must stay held for the whole close", ran.await(200, TimeUnit.MILLISECONDS))
        release.countDown()
        closer.join(); queued.join()
        assertEquals("the queued transaction must run only after the close released the lock", 0, ran.count)
    }

    @Test fun aBusySessionAbortsWithoutWaitingOrSending() = runBlocking {
        val scheduler = SessionScheduler()
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val holder = launch(Dispatchers.Default) { scheduler.run(SessionPriority.SCAN) { started.complete(Unit); gate.await() } }
        started.await()
        val sleep = AtomicInteger(); val close = AtomicInteger(); val abort = AtomicInteger()
        FinishSequence(1_000).run(scheduler, sleep = { sleep.incrementAndGet() },
            close = { close.incrementAndGet() }, abort = { abort.incrementAndGet() })
        assertEquals("no sleep may be appended after a busy transaction", 0, sleep.get())
        assertEquals(0, close.get())
        assertEquals(1, abort.get())
        gate.complete(Unit); holder.join()
    }

    @Test fun blockingFinishIsBoundedAndAbortsWithoutFollowUp() = runBlocking {
        val scheduler = SessionScheduler()
        val aborted = AtomicBoolean(false)
        val timedOut = AtomicBoolean(false)
        val neverReleased = CountDownLatch(1)
        val sleep = AtomicInteger(); val close = AtomicInteger()
        val elapsed = withTimeout(3_000) {
            val start = System.nanoTime()
            FinishSequence(120).run(scheduler,
                sleep = { sleep.incrementAndGet() },
                close = { close.incrementAndGet(); neverReleased.await() },
                abort = { aborted.set(true); neverReleased.countDown() },
                onTimeout = { timedOut.set(true) })
            (System.nanoTime() - start) / 1_000_000
        }
        assertTrue("finish must be bounded, was ${elapsed}ms", elapsed < 2_000)
        assertTrue(timedOut.get())
        assertTrue(aborted.get())
        awaitWorkerRelease(scheduler)
        assertEquals(1, sleep.get())
        assertEquals(1, close.get()) // no follow-up command after the timeout abort
    }

    @Test fun aTimedOutSleepDoesNotProceedToClose() = runBlocking {
        val scheduler = SessionScheduler()
        val sleepStarted = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closeCalls = AtomicInteger()
        val abortCalls = AtomicInteger()
        val timedOut = AtomicBoolean(false)
        withTimeout(3_000) {
            FinishSequence(150).run(scheduler,
                sleep = { sleepStarted.countDown(); release.await() },
                close = { closeCalls.incrementAndGet() },
                abort = { abortCalls.incrementAndGet(); release.countDown() },
                onTimeout = { timedOut.set(true) })
        }
        assertTrue("the blocking sleep must have started", sleepStarted.await(1, TimeUnit.SECONDS))
        assertTrue(timedOut.get())
        assertEquals(1, abortCalls.get())
        awaitWorkerRelease(scheduler)
        assertEquals("close must not run after a timed-out sleep returns", 0, closeCalls.get())
    }

    @Test fun aFailureCausedByOurOwnTimeoutIsNotReported() = runBlocking {
        val scheduler = SessionScheduler()
        val reported = AtomicInteger()
        val release = CountDownLatch(1)
        withTimeout(3_000) {
            FinishSequence(150).run(scheduler,
                sleep = { release.await(); throw IOException("unblocked by our own abort") },
                close = {}, abort = { release.countDown() },
                onTimeout = {}, onFailure = { reported.incrementAndGet() })
        }
        awaitWorkerRelease(scheduler)
        assertEquals("our own timeout teardown must not be reported as a real close error", 0, reported.get())
    }

    @Test fun aWorkerThatDoesNotExitIsReportedAsStuckAndCloseStaysBounded() = runBlocking {
        val stuck = CountDownLatch(1)
        val reported = AtomicBoolean(false)
        val elapsed = withTimeout(3_000) {
            val start = System.nanoTime()
            GracefulCloser(100, workerReclaimMillis = 200).close(
                blockingClose = { stuck.await() },
                abort = {},
                onWorkerStuck = { reported.set(true) })
            (System.nanoTime() - start) / 1_000_000
        }
        assertTrue("a worker that never exits must be reported as stuck", reported.get())
        assertTrue("close must stay bounded, was ${elapsed}ms", elapsed < 2_000)
        stuck.countDown()
    }

    @Test fun cancellingFinishStillReachesAbort() = runBlocking {
        val aborted = AtomicBoolean(false)
        val closeStarted = CountDownLatch(1)
        val released = CountDownLatch(1)
        val job = launch(Dispatchers.Default) {
            FinishSequence(5_000).run(SessionScheduler(), sleep = null,
                close = { closeStarted.countDown(); released.await() },
                abort = { aborted.set(true); released.countDown() })
        }
        assertTrue(closeStarted.await(5, TimeUnit.SECONDS))
        job.cancel()
        assertTrue(withTimeout(3_000) { job.join(); true })
        assertTrue(aborted.get())
    }

    @Test fun gateBeginFinishIsIdempotentAndBlocksNewTransactions() {
        val gate = SessionGate()
        assertTrue(gate.isOpen)
        assertTrue(gate.beginFinish())
        assertFalse("only the first finish may run", gate.beginFinish())
        assertFalse(gate.isOpen)
    }

    @Test fun gateRejectsPublishAfterFinishAndCloseCapturesOnce() {
        val gate = SessionGate()
        var resource: String? = null
        assertTrue(gate.publish { resource = "transport" })
        assertTrue(gate.beginFinish())
        var republished = false
        assertFalse("a closed session must reject a late publish", gate.publish { republished = true })
        assertFalse(republished)
        val captures = AtomicInteger()
        gate.close { captures.incrementAndGet(); resource = null }
        gate.close { captures.incrementAndGet() }
        assertEquals("a repeated close must not run its capture again", 1, captures.get())
    }

    @Test fun gateCloseThenPublishIsRejected() {
        val gate = SessionGate()
        gate.close {}
        var published = false
        assertFalse(gate.publish { published = true })
        assertFalse(published)
    }

    @Test fun sleepOnlyWhenBothCodesHandshakeAndRequestAgree() {
        assertTrue(ComputerTransfer.shouldSleep(true, true, true))
        assertFalse(ComputerTransfer.shouldSleep(false, true, true))
        assertFalse(ComputerTransfer.shouldSleep(true, false, true))
        assertFalse(ComputerTransfer.shouldSleep(true, true, false))
    }

    @Test fun queuedTransactionRejectsWhenFinishClosesTheGateInsideTheLock() = runBlocking {
        val scheduler = SessionScheduler()
        val closed = AtomicBoolean(false)
        val ran = AtomicBoolean(false)
        val runner = TransactionRunner(scheduler, {})
        val outerChecked = CountDownLatch(1)
        val checks = AtomicInteger()
        val isClosed = {
            val value = closed.get()
            if (checks.getAndIncrement() == 0) outerChecked.countDown()
            value
        }
        // Hold the transaction lock so the runner blocks right after its outer check.
        assertTrue(scheduler.tryAcquireIdle())
        val outcome = CompletableDeferred<Throwable?>()
        val job = launch(Dispatchers.Default) {
            outcome.complete(runCatching {
                runner.run<String, Unit>(SessionPriority.SCAN, isClosed, { "session" }) { ran.set(true) }
            }.exceptionOrNull())
        }
        assertTrue("the runner must pass its outer check before blocking", outerChecked.await(5, TimeUnit.SECONDS))
        closed.set(true) // finish flips the gate while the transaction is queued behind the lock
        scheduler.releaseIdle()
        job.join()
        assertTrue("a queued transaction must reject once the gate closed: ${outcome.await()}", outcome.await() is IOException)
        assertFalse(ran.get())
    }
}
