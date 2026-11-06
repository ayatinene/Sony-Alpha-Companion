package io.github.ayatinene.sonycompanion.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Limits only REMOTE avContent thumbnail transfers; JPEG and PTP keep their own gate.
 *
 * Per-key striped locks stop simultaneous JPEG/ARW tiles from fetching the same thumbnail
 * URL, including when both callers miss the cache initially. Locks are bounded (not one
 * permanent Mutex per photo) and released on cancellation. A second cache check after
 * acquiring the permit catches thumbnails completed by another key while we were queued.
 *
 * The caller owns the session-keyed LRU cache and stores successful results. There is no
 * background prefetch: leaving a Date folder cancels unfinished requests as before.
 */
internal class RemoteThumbnailScheduler(
    parallelism: Int = 2,
    stripes: Int = 64
) {
    init {
        require(parallelism >= 1)
        require(stripes >= 1)
    }
    private val gate=Semaphore(parallelism)
    private val locks=Array(stripes) { Mutex() }

    suspend fun <T : Any> load(key: String, cached: () -> T?, fetch: suspend () -> T?): T? {
        cached()?.let { return it }
        val mutex=locks[(key.hashCode() and Int.MAX_VALUE) % locks.size]
        mutex.lock()
        try {
            cached()?.let { return it }
            gate.acquire()
            try {
                return cached() ?: fetch()
            } finally {
                gate.release()
            }
        } finally {
            mutex.unlock()
        }
    }
}
