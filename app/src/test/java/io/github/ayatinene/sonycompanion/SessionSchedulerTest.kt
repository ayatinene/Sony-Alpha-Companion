package io.github.ayatinene.sonycompanion

import io.github.ayatinene.sonycompanion.data.SessionPriority
import io.github.ayatinene.sonycompanion.data.SessionScheduler
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

class SessionSchedulerTest {
    @Test fun keepsAtMostOneTransactionInFlight() = runBlocking {
        val scheduler = SessionScheduler()
        val inFlight = AtomicInteger(0)
        val max = AtomicInteger(0)
        val jobs = (1..8).map { i ->
            launch(Dispatchers.Default) {
                scheduler.run(if (i % 2 == 0) SessionPriority.PREVIEW else SessionPriority.SCAN) {
                    val now = inFlight.incrementAndGet()
                    max.updateAndGet { m -> maxOf(m, now) }
                    delay(5)
                    inFlight.decrementAndGet()
                }
            }
        }
        jobs.joinAll()
        assertEquals(1, max.get())
    }

    @Test fun downloadWaitsForTheRunningTransactionAndThenScanYieldsToIt() = runBlocking {
        val scheduler = SessionScheduler()
        val order = Collections.synchronizedList(mutableListOf<String>())
        val scanGate = CompletableDeferred<Unit>()
        val downloadGate = CompletableDeferred<Unit>()

        val scan1 = launch(Dispatchers.Default) {
            scheduler.run(SessionPriority.SCAN) { order += "scan1"; scanGate.await() }
        }
        while (order.isEmpty()) delay(5)
        val download = launch(Dispatchers.Default) {
            scheduler.run(SessionPriority.DOWNLOAD) { order += "download"; downloadGate.await() }
        }
        // A download must not preempt a transaction already running.
        delay(80)
        assertEquals(listOf("scan1"), order.toList())

        scanGate.complete(Unit); scan1.join()
        while (order.size < 2) delay(5)
        assertEquals(listOf("scan1", "download"), order.toList())

        // A scan must not start while a download is pending.
        val scan2 = launch(Dispatchers.Default) { scheduler.run(SessionPriority.SCAN) { order += "scan2" } }
        delay(80)
        assertEquals(listOf("scan1", "download"), order.toList())

        downloadGate.complete(Unit); download.join(); scan2.join()
        assertEquals(listOf("scan1", "download", "scan2"), order.toList())
    }

    @Test fun aQueuedDownloadIsNotStolenByLaterScans() = runBlocking {
        val scheduler = SessionScheduler()
        val order = Collections.synchronizedList(mutableListOf<String>())
        val downloadGate = CompletableDeferred<Unit>()
        val download = launch(Dispatchers.Default) {
            scheduler.run(SessionPriority.DOWNLOAD) { order += "download"; downloadGate.await() }
        }
        while (order.isEmpty()) delay(5)
        val scans = (1..5).map { i ->
            launch(Dispatchers.Default) { scheduler.run(SessionPriority.SCAN) { order += "scan$i" } }
        }
        delay(100)
        assertEquals("a pending download must keep the slot", listOf("download"), order.toList())
        downloadGate.complete(Unit)
        download.join(); scans.joinAll()
        assertEquals(6, order.size)
        assertEquals("download", order.first())
    }
}
