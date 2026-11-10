package io.github.ayatinene.sonycompanion.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.util.Collections
import java.util.IdentityHashMap
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
 * Abort handles for the resources created by exactly one connection attempt. Cancelling a coroutine
 * cannot interrupt a blocking PTP/HTTP call, so the caller registers an explicit closer.
 *
 * Ownership is per attempt: [abortAll] releases this attempt's resources only, and a closer
 * registered *after* an abort is closed immediately so a resource created while the attempt is being
 * torn down cannot leak. On a successful promotion [release] drops the handles without aborting, so a
 * later external abort can never close the live session's transport. A stale attempt therefore can
 * never touch a newer attempt's resources.
 */
class PendingResources {
    private val lock = Any()
    private val closers = CopyOnWriteArrayList<() -> Unit>()
    private enum class State { Open, Released, Aborted }
    private var state = State.Open
    fun register(closer: () -> Unit) {
        val runNow = synchronized(lock) {
            when(state) {
                State.Open -> { closers += closer; false }
                State.Aborted -> true    // created while aborting: close now, never leak
                State.Released -> true   // after promotion a new resource must not be silently dropped
            }
        }
        if(runNow) runCatching { closer() }
    }
    /** Successful promotion: resources now belong to the live session; drop the abort handles. */
    fun release() = synchronized(lock) { if(state==State.Open) { state=State.Released; closers.clear() } }
    /** Abort this attempt's resources and reject/close anything registered later. Idempotent; a
     * released attempt is already owned by the live session and is also a no-op. */
    fun abortAll() {
        val pending: List<() -> Unit> = synchronized(lock) {
            if(state!=State.Open) return
            state=State.Aborted
            closers.toList().also { closers.clear() }
        }
        pending.forEach { runCatching { it() } }
    }
}

/**
 * Ordered, bounded end sequence for one camera session. Each step is bounded so a blocked call cannot
 * make disconnect wait for the full HTTP timeout, and a failing step never prevents the transport
 * close at the end. Kept separate so the required order is exercised by tests on production code.
 */
class SessionEndSequence(private val timeoutMillis: Long) {
    class Step(val name: String, val action: suspend () -> Unit)
    /** Each step's bound relies on its blocking call being interruptible (an OkHttp call is cancelled
     * when its coroutine is cancelled); a non-interruptible call must be aborted by the caller. */
    suspend fun run(steps: List<Step>, onFailure: (Step, Throwable) -> Unit = { _, _ -> }) {
        for (step in steps) {
            try { withTimeout(timeoutMillis) { step.action() } }
            catch (e: TimeoutCancellationException) { onFailure(step, e) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { onFailure(step, e) }
        }
    }
}

/**
 * Tracks the controller's session-bound control jobs. [detach] atomically takes and clears the tracked
 * set, so an old session's cleanup cancels/joins exactly the jobs it owned and can never touch a newer
 * session's actions.
 */
class SessionActions {
    private val lock = Any()
    private val jobs = Collections.newSetFromMap(IdentityHashMap<Job, Boolean>())
    fun track(job: Job) {
        synchronized(lock) { jobs += job }
        job.invokeOnCompletion { synchronized(lock) { jobs -= job } }
    }
    /** Atomically takes the currently tracked jobs and clears the registry. */
    fun detach(): List<Job> = synchronized(lock) { jobs.toList().also { jobs.clear() } }
    fun cancel(jobs: List<Job>, except: Job? = null) { jobs.forEach { if(it!==except) it.cancel() } }
    suspend fun join(jobs: List<Job>, except: Job? = null) { jobs.forEach { if(it!==except) runCatching { it.join() } } }
}
