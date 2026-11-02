package io.github.ayatinene.sonycompanion.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.withLock
import kotlin.coroutines.cancellation.CancellationException

/**
 * Bounded, non-blocking readiness handshake for the foreground service. The service calls [succeed]
 * once `startForeground` returned, or [fail] when the start was rejected. [await] never blocks a
 * thread and returns false on rejection or timeout, so the caller can cancel the work and invalidate
 * the session instead of silently continuing without foreground ownership.
 */
class ReadinessLatch {
    @Volatile private var deferred: CompletableDeferred<Unit>? = null

    /** Reuses an unresolved pending latch so an older service confirmation cannot resolve a newer
     * ticket; a fresh latch is only created once the previous one is resolved. */
    @Synchronized fun begin() {
        val current = deferred
        if (current == null || current.isCompleted) deferred = CompletableDeferred()
    }
    @Synchronized fun succeed() { deferred?.complete(Unit) }
    @Synchronized fun fail(cause: Throwable) { deferred?.completeExceptionally(cause) }

    suspend fun await(timeoutMillis: Long): Boolean {
        val pending = deferred ?: return true
        return try {
            withTimeout(timeoutMillis) { pending.await() }
            true
        } catch (_: TimeoutCancellationException) {
            false
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }
}

/**
 * Monotonic identity for one camera session and the work runs inside it. Generation and per-kind run
 * counters are guarded by one lock, and [whenCurrent] performs the check and the state mutation under
 * that same lock, so a stale scan/queue callback can never publish into a newer session. Scan and
 * queue runs use independent counters, so starting a scan never invalidates an in-flight download.
 */
class SessionIdentity {
    private val lock = java.util.concurrent.locks.ReentrantLock()
    private var generation = 0L
    private var scanRun = 0L
    private var queueRun = 0L

    val currentGeneration: Long get() = lock.withLock { generation }

    fun invalidate(): Long = bumpGeneration { it }
    /** Bumps the generation and runs [block] under the identity lock, so a state publish cannot race
     * with a concurrent generation change. */
    fun <T> bumpGeneration(block: (Long) -> T): T = lock.withLock { generation += 1; block(generation) }
    /** Runs [block] under the identity lock (use with [currentGeneration] for atomic check+publish). */
    fun <T> locked(block: () -> T): T = lock.withLock(block)
    fun nextScanRun(): Token = lock.withLock { scanRun += 1; Token(generation, scanRun, Kind.SCAN) }
    fun nextQueueRun(): Token = lock.withLock { queueRun += 1; Token(generation, queueRun, Kind.QUEUE) }
    fun scanToken(): Token = lock.withLock { Token(generation, scanRun, Kind.SCAN) }
    fun queueToken(): Token = lock.withLock { Token(generation, queueRun, Kind.QUEUE) }

    fun isCurrent(token: Token): Boolean = lock.withLock { current(token) }
    private fun current(token: Token): Boolean = generation == token.generation && when (token.kind) {
        Kind.SCAN -> scanRun == token.run
        Kind.QUEUE -> queueRun == token.run
    }
    /** Runs [block] atomically with the identity check, so no generation/run bump can slip in between. */
    fun <T> whenCurrent(token: Token, block: () -> T): T? = lock.withLock { if (current(token)) block() else null }

    enum class Kind { SCAN, QUEUE }
    class Token internal constructor(val generation: Long, val run: Long, internal val kind: Kind)
}

/**
 * Identity-tracked abort handles for resources created by an in-flight connection. Cancelling a
 * coroutine cannot interrupt a blocking PTP/HTTP call, so the caller registers an explicit closer
 * and aborts them before joining the old job.
 */
class PendingResources {
    private val closers = CopyOnWriteArrayList<() -> Unit>()
    fun register(closer: () -> Unit) { closers += closer }
    fun clear() { closers.clear() }
    fun abortAll() { closers.forEach { runCatching { it() } } }
}
