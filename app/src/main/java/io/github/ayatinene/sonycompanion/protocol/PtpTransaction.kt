package io.github.ayatinene.sonycompanion.protocol

import io.github.ayatinene.sonycompanion.data.SessionPriority
import io.github.ayatinene.sonycompanion.data.SessionScheduler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs PTP transactions through [SessionScheduler] with consistent failure handling.
 *
 * A failure that happens *after* a transaction started (cancel, transport IO, protocol or
 * output-write error) can leave unread Data/Response packets in the transport, so [abort] is invoked
 * before the error is rethrown. The controller treats the aborted session as a broken connection.
 * A complete response such as an [isCompleteResponse] failure (e.g. "thumbnail unsupported") reached
 * the transaction boundary and keeps the session usable, so it is never treated as a broken link.
 */
class TransactionRunner(
    private val scheduler: SessionScheduler,
    private val abort: () -> Unit,
    private val isCompleteResponse: (Throwable) -> Boolean = { false }
) {
    suspend fun <S, T> run(
        priority: SessionPriority,
        isClosed: () -> Boolean,
        session: () -> S?,
        block: suspend (S) -> T
    ): T = withContext(Dispatchers.IO) {
        if (isClosed()) throw IOException("原文件会话已结束")
        scheduler.run(priority) {
            // Re-check inside the lock: finish() flips the gate to FINISHING before it takes this
            // lock, so a transaction queued behind it rejects instead of running on a dying transport.
            if (isClosed()) throw IOException("原文件会话已结束")
            val s = session() ?: throw IOException("原文件会话已结束")
            try {
                block(s)
            } catch (e: Throwable) {
                // Abort while still holding the scheduler lock: a queued transaction must not start
                // (and read stale Data/Response packets) before the transport is torn down.
                if (!isCompleteResponse(e)) runCatching { abort() }
                throw e
            }
        }
    }
}

/**
 * Bounded graceful close with a detached worker. The blocking close runs on its own scope so
 * cancelling the caller while it waits can never strand a blocking `CloseSession`: [abort] is always
 * invoked (on success, timeout or cancellation) to unblock the worker, keeping the close bounded.
 */
class GracefulCloser(
    private val graceMillis: Long,
    private val workerReclaimMillis: Long = WORKER_RECLAIM_MILLIS,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    companion object {const val WORKER_RECLAIM_MILLIS = 500L}

    suspend fun close(
        blockingClose: () -> Unit,
        abort: () -> Unit,
        onTimeout: () -> Unit = {},
        onCancel: () -> Unit = {},
        onFailure: (Throwable) -> Unit = {},
        onWorkerStuck: () -> Unit = {}
    ) {
        // The worker reports a genuine close failure (never a silent swallow) while its own
        // cancellation/timeout path is signalled separately via [onCancel]/[onTimeout].
        val closer = scope.launch { runCatching { blockingClose() }.onFailure { onFailure(it) } }
        try {
            if (withTimeoutOrNull(graceMillis) { closer.join() } == null) runCatching { onTimeout() }
        } catch (e: CancellationException) {
            runCatching { onCancel() }
            runCatching { abort() }
            throw e
        } finally {
            runCatching { abort() }
            // Independent, bounded, non-cancellable reclamation: finish must not return while a detached
            // worker could still send a command, but it must never wait unbounded either. A worker that
            // did not exit within the budget is reported so production can record it explicitly.
            val reclaimed = withContext(NonCancellable) { withTimeoutOrNull(workerReclaimMillis) { closer.join() } }
            if (reclaimed == null) runCatching { onWorkerStuck() }
        }
    }
}

/**
 * One-session lifecycle gate: OPEN, then FINISHING (no new transaction may start), then CLOSED.
 *
 * Every transition shares one lock, so a resource published concurrently with a close is either
 * rejected or torn down and can never be revived; repeated finish/abort transitions are no-ops.
 */
class SessionGate {
    enum class State { OPEN, FINISHING, CLOSED }
    private val lock = Any()
    private var state = State.OPEN

    val isOpen: Boolean get() = synchronized(lock) { state == State.OPEN }

    /** True exactly once: only the caller that moves OPEN -> FINISHING runs the finish sequence. */
    fun beginFinish(): Boolean = synchronized(lock) {
        if (state != State.OPEN) return false
        state = State.FINISHING
        true
    }

    /** Runs [publish] under the lock only while still OPEN; false means the session already closed. */
    fun publish(publish: () -> Unit): Boolean = synchronized(lock) {
        if (state != State.OPEN) return false
        publish()
        true
    }

    /** Atomically marks the session closed and captures the resources to tear down; a repeated
     * close does not run the capture again. */
    fun close(capture: () -> Unit) = synchronized(lock) {
        if (state == State.CLOSED) return
        state = State.CLOSED
        capture()
    }
}

/**
 * Bounded finish sequence shared by [io.github.ayatinene.sonycompanion.protocol.ComputerTransfer].
 *
 * [sleep] (the optional Sony sleep pair) and [close] run together under [SessionScheduler]'s
 * transaction lock, acquired non-blocking: if a transaction holds or awaits the lock the transport is
 * aborted instead of waiting for it and appending a sleep afterwards. The whole sequence shares one
 * grace budget through a detached [GracefulCloser] worker; on timeout or caller cancellation [abort]
 * closes the transport so the worker unblocks before it can send another command. A failure in [sleep]
 * stops the sequence before [close].
 */
class FinishSequence(
    private val graceMillis: Long,
    private val workerReclaimMillis: Long = GracefulCloser.WORKER_RECLAIM_MILLIS,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    suspend fun run(
        scheduler: SessionScheduler,
        sleep: (() -> Unit)?,
        close: () -> Unit,
        abort: () -> Unit,
        onTimeout: () -> Unit = {},
        onFailure: (Throwable) -> Unit = {},
        onWorkerStuck: () -> Unit = {}
    ) {
        // Once cancelled or timed out, the worker must not proceed to CloseSession after a blocking
        // sleep returns. The transaction lock stays with the worker until it terminates, so a queued
        // transaction can only acquire it after the sequence is fully torn down.
        val cancelled = AtomicBoolean(false)
        GracefulCloser(graceMillis, workerReclaimMillis, scope).close(
            blockingClose = {
                if (scheduler.tryAcquireIdle()) {
                    try {
                        sleep?.invoke()
                        if (!cancelled.get()) close()
                    } finally { scheduler.releaseIdle() }
                }
            },
            abort = abort,
            onTimeout = { cancelled.set(true); onTimeout() },
            onCancel = { cancelled.set(true) },
            // A failure caused by our own timeout/cancel teardown is expected; only a real failure is
            // reported so the caller can distinguish local release from an unconfirmed close.
            onFailure = { e -> if (!cancelled.get()) onFailure(e) },
            onWorkerStuck = onWorkerStuck
        )
    }
}
