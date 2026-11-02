package io.github.ayatinene.sonycompanion.data

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicInteger

/** Raw PTP object metadata needed to build a [CameraFile]. Never carries object payload bytes. */
data class CameraObject(
    val handle: Long,
    val name: String,
    val size: Long,
    val date: String,
    val parent: String,
    val association: Boolean
) {
    val raw: Boolean get() = name.endsWith(".arw", true)
    val supported: Boolean get() = !association &&
        (name.endsWith(".arw", true) || name.endsWith(".jpg", true) || name.endsWith(".jpeg", true))
}

/**
 * Serialized transaction surface over one live PTP/IP session. Implementations must make every
 * call a complete single transaction. Scan, thumbnail and download callers may run concurrently
 * and rely on the implementation (or [SessionScheduler]) to keep at most one transaction in flight.
 */
interface ComputerSession {
    suspend fun storages(): List<Long>
    suspend fun handles(storage: Long): List<Long>
    suspend fun objectInfo(storage: Long, handle: Long): CameraObject
    suspend fun thumbnail(handle: Long): ByteArray
    suspend fun download(file: CameraFile, output: OutputStream, progress: (Long, Long) -> Unit)
    val alive: Boolean
    fun abort()
}

enum class SessionPriority { DOWNLOAD, PREVIEW, SCAN }

/**
 * Lightweight single-in-flight arbiter over one camera session.
 *
 * - At most one transaction executes at a time (a single fair FIFO [Mutex]).
 * - A scan never begins while a download is pending: it waits at a full transaction boundary until
 *   [downloadsPending] drops to zero, so a later scan cannot steal the slot from a queued download.
 * - A transaction already running is never interrupted, so no command is injected into a stream.
 * - Thumbnails/previews interleave between transactions in arrival order; strict preview priority is
 *   intentionally not required.
 */
class SessionScheduler(private val pollMillis: Long = 1L) {
    private val mutex = Mutex()
    private val downloads = AtomicInteger(0)

    val downloadsPending: Int get() = downloads.get()

    suspend fun <T> run(priority: SessionPriority, block: suspend () -> T): T {
        if (priority == SessionPriority.DOWNLOAD) {
            downloads.incrementAndGet()
            try { return mutex.withLock { block() } }
            finally { downloads.decrementAndGet() }
        }
        if (priority == SessionPriority.SCAN) return runScan(block)
        return mutex.withLock { block() }
    }

    private suspend fun <T> runScan(block: suspend () -> T): T {
        while (true) {
            while (downloads.get() > 0) delay(pollMillis)
            mutex.withLock {
                // Re-check inside the fair lock: a download that arrived while we queued wins the slot.
                if (downloads.get() == 0) return block()
            }
        }
    }
}

data class ScanOutcome(val total: Int, val scanned: Int, val found: Int, val files: List<CameraFile>)

/**
 * Production scan loop. All handles are fetched first so the object total is real (not an image
 * count); object info is then read serially. Directories and videos count as scanned; ARW/JPEG
 * count as found. The first snapshot is emitted as soon as the total is known and later ones are
 * throttled by [batchIntervalMillis] (0 emits after every object, used by tests).
 */
class ScanEngine(private val session: ComputerSession, private val batchIntervalMillis: Long = 250L) {
    suspend fun scan(
        isCurrent: () -> Boolean,
        awaitResume: suspend () -> Unit,
        onBatch: suspend (scanned: Int, total: Int, found: Int, files: List<CameraFile>) -> Unit
    ): ScanOutcome {
        val storages = session.storages()
        val grouped = LinkedHashMap<Long, List<Long>>()
        var total = 0
        for (storage in storages) {
            if (!isCurrent()) return ScanOutcome(total, 0, 0, emptyList())
            val handles = session.handles(storage)
            grouped[storage] = handles
            total += handles.size
        }
        var scanned = 0
        val found = ArrayList<CameraFile>()
        var lastEmitNanos = System.nanoTime()
        var publishedFile = false
        onBatch(0, total, 0, emptyList())
        for ((storage, handles) in grouped) {
            for (handle in handles) {
                if (!isCurrent()) return ScanOutcome(total, scanned, found.size, found.toList())
                awaitResume()
                if (!isCurrent()) return ScanOutcome(total, scanned, found.size, found.toList())
                // A dead session is a failure, not a superseded run: surface it so the caller can
                // invalidate the connection instead of leaving a stuck "scanning" state.
                if (!session.alive) throw IOException("相机连接已断开")
                val obj = session.objectInfo(storage, handle)
                scanned++
                if (obj.supported) {
                    found += CameraFile(obj.handle.toString(), obj.name, size = obj.size, raw = obj.raw,
                        handle = obj.handle, date = obj.date, parent = obj.parent)
                }
                val now = System.nanoTime()
                // Publish the first supported file immediately instead of waiting a full interval;
                // later batches stay throttled so progress updates remain reasonable.
                val firstFile = obj.supported && !publishedFile
                if (batchIntervalMillis <= 0L || firstFile || now - lastEmitNanos >= batchIntervalMillis * 1_000_000L) {
                    if (obj.supported) publishedFile = true
                    onBatch(scanned, total, found.size, found.toList())
                    lastEmitNanos = now
                }
            }
        }
        return ScanOutcome(total, scanned, found.size, found.toList())
    }
}

/**
 * A COMPUTER listing is only authoritative once its scan reached Done. A partial listing must never
 * be used to judge queued files missing.
 */
fun scanListingComplete(mode: CameraMode, phase: ScanPhase): Boolean =
    mode != CameraMode.COMPUTER || phase == ScanPhase.Done

/**
 * The scan may only advance between transactions when the user has not paused it and no download is
 * in progress. A user pause is independent of the download pause: finishing a download never lifts
 * the user's explicit pause.
 */
fun scanShouldRun(userPaused: Boolean, transferring: Boolean): Boolean = !userPaused && !transferring
