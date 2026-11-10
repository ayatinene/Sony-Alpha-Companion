package io.github.ayatinene.sonycompanion

import io.github.ayatinene.sonycompanion.data.PendingResources
import io.github.ayatinene.sonycompanion.data.ReadinessLatch
import io.github.ayatinene.sonycompanion.data.SessionIdentity
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

class BackgroundWorkTest {
    @Test fun rejectedReadinessNeverRunsTheWork() = runBlocking {
        val latch = ReadinessLatch()
        var workStarted = false
        latch.begin()
        latch.fail(IllegalStateException("startForeground rejected"))
        val ready = latch.await(2_000)
        assertFalse(ready)
        if (ready) workStarted = true
        assertFalse("no scan/download may start after a rejected foreground start", workStarted)
    }

    @Test fun readyReadinessRunsTheWork() = runBlocking {
        val latch = ReadinessLatch()
        latch.begin()
        latch.succeed()
        assertTrue(latch.await(2_000))
    }

    @Test fun readinessWaitIsBoundedWhenTheServiceNeverAnswers() = runBlocking {
        val latch = ReadinessLatch()
        latch.begin()
        val elapsed = withTimeout(3_000) {
            val start = System.nanoTime()
            assertFalse(latch.await(150))
            (System.nanoTime() - start) / 1_000_000
        }
        assertTrue("bounded wait, was ${elapsed}ms", elapsed < 2_000)
    }

    @Test fun staleTokenCannotPublishIntoANewerSession() {
        val identity = SessionIdentity()
        val token = identity.nextScanRun()
        assertTrue(identity.isCurrent(token))
        // A reconnect bumps the generation: the old scan/queue token is now stale.
        identity.invalidate()
        assertFalse(identity.isCurrent(token))
    }

    @Test fun staleQueueFinallyDoesNotOverwriteTheNewQueue() {
        val identity = SessionIdentity()
        val token = identity.nextQueueRun()
        val queue = mutableListOf<String>()
        identity.whenCurrent(token) { queue += "old" }
        assertEquals(listOf("old"), queue)
        identity.invalidate()
        val result = identity.whenCurrent(token) { queue += "stale" }
        assertNull(result)
        assertEquals("a superseded download finally must not touch the new queue", listOf("old"), queue)
    }

    @Test fun newScanRunInvalidatesThePreviousScan() {
        val identity = SessionIdentity()
        val first = identity.nextScanRun()
        assertTrue(identity.isCurrent(first))
        val second = identity.nextScanRun()
        assertFalse(identity.isCurrent(first))
        assertTrue(identity.isCurrent(second))
    }

    @Test fun startingAScanDoesNotInvalidateAnInFlightDownload() {
        val identity = SessionIdentity()
        val queue = identity.nextQueueRun()
        identity.nextScanRun() // a rescan must not supersede the running download
        assertTrue(identity.isCurrent(queue))
        identity.invalidate() // only a session change invalidates both
        assertFalse(identity.isCurrent(queue))
    }

    @Test fun abortAllWakesABlockingHandleImmediately() = runBlocking {
        val resources = PendingResources()
        val gate = CompletableDeferred<Unit>()
        resources.register { gate.complete(Unit) }
        val released = AtomicBoolean(false)
        val job = launch(Dispatchers.Default) {
            try { gate.await() } finally { released.set(true) }
        }
        delay(60)
        assertFalse(released.get())
        val start = System.nanoTime()
        resources.abortAll()
        job.join()
        val elapsed = (System.nanoTime() - start) / 1_000_000
        assertTrue("abort must unblock the pending connection promptly, was ${elapsed}ms", elapsed < 1_000)
        assertTrue(released.get())
    }
}
