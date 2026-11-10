package io.github.ayatinene.sonycompanion

import io.github.ayatinene.sonycompanion.data.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Exercises the real [ScanEngine] loop and [SessionScheduler] through an injectable blocking session. */
class CameraScanTest {
    private fun obj(handle: Long, name: String, size: Long = 100, association: Boolean = false) =
        CameraObject(handle, name, size, "2026-10-24", "0/0", association)

    private class FakeSession(private val storages: Map<Long, List<CameraObject>>) : ComputerSession {
        val infoCalls = AtomicInteger()
        @Volatile var gate: CompletableDeferred<Unit>? = null
        @Volatile var allowFirst: Int = Int.MAX_VALUE
        @Volatile var aborted = false
        override val alive: Boolean get() = !aborted
        override suspend fun storages(): List<Long> = storages.keys.toList()
        override suspend fun handles(storage: Long): List<Long> = storages.getValue(storage).map { it.handle }
        override suspend fun objectInfo(storage: Long, handle: Long): CameraObject {
            val n = infoCalls.incrementAndGet()
            if (n > allowFirst) gate?.await()
            return storages.getValue(storage).first { it.handle == handle }
        }
        override suspend fun thumbnail(handle: Long): ByteArray = ByteArray(0)
        override suspend fun download(file: CameraFile, output: OutputStream, progress: (Long, Long) -> Unit) {}
        override fun abort() { aborted = true }
    }

    @Test fun reportsTheRealObjectTotalAndCountsDirectoriesAndVideosAsScanned() = runBlocking {
        val objects = listOf(
            obj(1, "DSC00001.ARW"), obj(2, "DSC00002.JPG"), obj(3, "CLIP.MP4"),
            obj(4, "DCIM", association = true), obj(5, "DSC00003.ARW"))
        val session = FakeSession(mapOf(0L to objects))
        val batches = mutableListOf<Triple<Int, Int, Int>>()
        val outcome = ScanEngine(session, batchIntervalMillis = 0L).scan(
            isCurrent = { true }, awaitResume = {},
            onBatch = { s, t, f, _ -> batches += Triple(s, t, f) })
        assertEquals(5, outcome.total)
        assertEquals(5, outcome.scanned)
        assertEquals(3, outcome.found)
        assertEquals(listOf("DSC00003.ARW", "DSC00002.JPG", "DSC00001.ARW"), outcome.files.map { it.name })
        assertEquals(0, batches.first().first)
        assertEquals(5, batches.first().second)
        assertEquals(5, batches.last().first)
        assertEquals(3, batches.last().third)
    }

    @Test fun walksEachStorageFromTheEndOfTheCameraOrderWithoutSortingByHandle() = runBlocking {
        // Handles are deliberately not ascending: a sort by handle value would yield 10,20,30 or
        // 30,20,10. Only the literal array reversal produces this order.
        val objects = listOf(obj(30, "DSC00003.JPG"), obj(10, "DSC00001.JPG"), obj(20, "DSC00002.JPG"))
        val session = FakeSession(mapOf(0L to objects))
        val outcome = ScanEngine(session, batchIntervalMillis = 0L).scan(
            isCurrent = { true }, awaitResume = {}, onBatch = { _, _, _, _ -> })
        assertEquals(3, outcome.total)
        assertEquals(3, outcome.scanned)
        assertEquals(listOf("DSC00002.JPG", "DSC00001.JPG", "DSC00003.JPG"), outcome.files.map { it.name })
    }

    @Test fun directoriesAndVideosAreCountedAndNeverDroppedInReverseOrder() = runBlocking {
        val objects = listOf(
            obj(1, "DSC00001.ARW"), obj(2, "DCIM", association = true),
            obj(3, "CLIP.MP4"), obj(4, "DSC00002.JPG"))
        val session = FakeSession(mapOf(0L to objects))
        val outcome = ScanEngine(session, batchIntervalMillis = 0L).scan(
            isCurrent = { true }, awaitResume = {}, onBatch = { _, _, _, _ -> })
        assertEquals(4, outcome.total)
        assertEquals(4, outcome.scanned)
        assertEquals(listOf("DSC00002.JPG", "DSC00001.ARW"), outcome.files.map { it.name })
    }

    @Test fun eachStorageIsReversedWhileTheStorageOrderIsPreserved() = runBlocking {
        val session = FakeSession(mapOf(
            0x100L to listOf(obj(1, "A.JPG"), obj(2, "B.JPG"), obj(3, "C.JPG")),
            0x200L to listOf(obj(10, "X.JPG"), obj(11, "Y.JPG"))))
        val outcome = ScanEngine(session, batchIntervalMillis = 0L).scan(
            isCurrent = { true }, awaitResume = {}, onBatch = { _, _, _, _ -> })
        assertEquals(5, outcome.total)
        assertEquals(5, outcome.scanned)
        assertEquals(listOf("C.JPG", "B.JPG", "A.JPG", "Y.JPG", "X.JPG"), outcome.files.map { it.name })
    }

    @Test fun firstSupportedFileAfterReversalIsPublishedBeforeSlowerObjects() = runBlocking {
        val objects = listOf(obj(1, "DSC00001.JPG"), obj(2, "DSC00002.JPG"), obj(3, "DCIM", association = true))
        val session = FakeSession(mapOf(0L to objects))
        session.allowFirst = 2
        session.gate = CompletableDeferred()
        val firstFile = CompletableDeferred<Triple<Int, Int, Int>>()
        val inspected = CompletableDeferred<Unit>()
        val scan = launch(Dispatchers.Default) {
            // A long interval must not delay the first supported file even after a leading directory.
            ScanEngine(session, batchIntervalMillis = 60_000L).scan(
                isCurrent = { true }, awaitResume = {},
                onBatch = { s, t, f, _ ->
                    if (f > 0 && !firstFile.isCompleted) {
                        firstFile.complete(Triple(s, t, f))
                        inspected.await()
                    }
                })
        }
        try {
            val batch = withTimeout(3000) { firstFile.await() }
            assertEquals(2, batch.first)
            assertEquals(1, batch.third)
            assertEquals("the third object is still slow/blocked", 2, session.infoCalls.get())
        } finally {
            inspected.complete(Unit)
            session.gate!!.complete(Unit)
        }
        scan.join()
        assertEquals(3, session.infoCalls.get())
    }

    @Test fun aSupersededRunStopsBeforeReadingTheRemainingReversedObjects() = runBlocking {
        val objects = (1..10).map { obj(it.toLong(), "DSC%05d.JPG".format(it)) }
        val session = FakeSession(mapOf(0L to objects))
        val batches = AtomicInteger()
        val outcome = ScanEngine(session, batchIntervalMillis = 0L).scan(
            isCurrent = { batches.get() < 3 }, awaitResume = {},
            onBatch = { _, _, _, _ -> batches.incrementAndGet() })
        assertEquals(2, outcome.scanned)
        assertEquals(listOf("DSC00010.JPG", "DSC00009.JPG"), outcome.files.map { it.name })
    }

    @Test fun firstBatchArrivesBeforeTheScanCompletes() = runBlocking {
        val objects = (1..50).map { obj(it.toLong(), "DSC%05d.JPG".format(it)) }
        val session = FakeSession(mapOf(0L to objects))
        session.gate = CompletableDeferred()
        session.allowFirst = 0
        val first = CompletableDeferred<Triple<Int, Int, Int>>()
        // Lets the scan pause inside the first batch until the test has inspected the state at that
        // exact point; without it the scan races ahead into objectInfo before the assertion runs.
        val inspected = CompletableDeferred<Unit>()
        val scan = launch(Dispatchers.Default) {
            ScanEngine(session, batchIntervalMillis = 0L).scan(
                isCurrent = { true }, awaitResume = {},
                onBatch = { s, t, f, _ ->
                    if (!first.isCompleted) {
                        first.complete(Triple(s, t, f))
                        inspected.await()
                    }
                })
        }
        try {
            val batch = withTimeout(3000) { first.await() }
            assertEquals(50, batch.second)
            assertEquals(0, session.infoCalls.get())
        } finally {
            inspected.complete(Unit)
            session.gate!!.complete(Unit)
        }
        scan.join()
        assertEquals(50, session.infoCalls.get())
    }

    @Test fun firstSupportedFileIsPublishedBeforeRemainingSlowObjects() = runBlocking {
        val objects = listOf(obj(1, "DSC00001.JPG"), obj(2, "DSC00002.JPG"), obj(3, "DSC00003.JPG"))
        val session = FakeSession(mapOf(0L to objects))
        session.allowFirst = 1
        session.gate = CompletableDeferred()
        val firstFile = CompletableDeferred<Triple<Int, Int, Int>>()
        // Holds the scan inside the first-file batch until the test has sampled infoCalls; otherwise
        // the loop can already be blocked on the second objectInfo when the assertion runs.
        val inspected = CompletableDeferred<Unit>()
        val scan = launch(Dispatchers.Default) {
            // A long interval must not delay the first supported file.
            ScanEngine(session, batchIntervalMillis = 60_000L).scan(
                isCurrent = { true }, awaitResume = {},
                onBatch = { s, t, f, _ ->
                    if (f > 0 && !firstFile.isCompleted) {
                        firstFile.complete(Triple(s, t, f))
                        inspected.await()
                    }
                })
        }
        try {
            val batch = withTimeout(3000) { firstFile.await() }
            assertEquals(1, batch.first)
            assertEquals(1, batch.third)
            assertEquals("the second object is still slow/blocked", 1, session.infoCalls.get())
        } finally {
            inspected.complete(Unit)
            session.gate!!.complete(Unit)
        }
        scan.join()
        assertEquals(3, session.infoCalls.get())
    }

    @Test fun stopsPublishingAndReturnsEarlyWhenTheRunIsSuperseded() = runBlocking {
        val objects = (1..20).map { obj(it.toLong(), "DSC%05d.JPG".format(it)) }
        val session = FakeSession(mapOf(0L to objects))
        val batches = AtomicInteger()
        val outcome = ScanEngine(session, batchIntervalMillis = 0L).scan(
            isCurrent = { batches.get() < 3 }, awaitResume = {},
            onBatch = { _, _, _, _ -> batches.incrementAndGet() })
        assertTrue(outcome.scanned < 20)
        assertTrue(outcome.scanned <= 2)
    }

    @Test fun scanWaitsWhileTheUserPauses() = runBlocking {
        val objects = (1..5).map { obj(it.toLong(), "DSC%05d.JPG".format(it)) }
        val session = FakeSession(mapOf(0L to objects))
        val paused = AtomicBoolean(true)
        val scan = launch(Dispatchers.Default) {
            ScanEngine(session, batchIntervalMillis = 0L).scan(
                isCurrent = { true }, awaitResume = { while (paused.get()) delay(5) }, onBatch = { _, _, _, _ -> })
        }
        delay(100)
        assertEquals(0, session.infoCalls.get())
        paused.set(false)
        scan.join()
        assertEquals(5, session.infoCalls.get())
    }

    @Test fun userPauseIsNotLiftedByADownloadFinishing() {
        assertFalse(scanShouldRun(userPaused = true, transferring = true))
        assertFalse(scanShouldRun(userPaused = true, transferring = false))
        assertFalse(scanShouldRun(userPaused = false, transferring = true))
        assertTrue(scanShouldRun(userPaused = false, transferring = false))
    }

    @Test fun abortReleasesABlockingTransactionImmediately() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val session = object : ComputerSession {
            override val alive: Boolean get() = true
            override suspend fun storages(): List<Long> = emptyList()
            override suspend fun handles(storage: Long): List<Long> = emptyList()
            override suspend fun objectInfo(storage: Long, handle: Long): CameraObject = error("unused")
            override suspend fun thumbnail(handle: Long): ByteArray = ByteArray(0)
            override suspend fun download(file: CameraFile, output: OutputStream, progress: (Long, Long) -> Unit) { gate.await() }
            override fun abort() { gate.cancel() }
        }
        val file = CameraFile("1", "DSC00001.ARW", size = 100, raw = true, handle = 1)
        val started = CompletableFuture<Unit>()
        val job = launch(Dispatchers.Default) {
            try { session.download(file, ByteArrayOutputStream()) { _, _ -> } } finally { started.complete(Unit) }
        }
        delay(80)
        assertFalse(started.isDone)
        val startNanos = System.nanoTime()
        session.abort()
        job.join()
        val elapsed = (System.nanoTime() - startNanos) / 1_000_000
        assertTrue("abort must release the blocked transaction promptly, was ${elapsed}ms", elapsed < 2000)
        assertTrue(started.isDone)
    }
}
