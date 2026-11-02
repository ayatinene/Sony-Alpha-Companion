package io.github.ayatinene.sonycompanion.protocol

import io.github.ayatinene.sonycompanion.data.SessionPriority
import io.github.ayatinene.sonycompanion.data.SessionScheduler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

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
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    suspend fun close(blockingClose: () -> Unit, abort: () -> Unit, onTimeout: () -> Unit = {}) {
        val closer = scope.launch { runCatching { blockingClose() } }
        try {
            if (withTimeoutOrNull(graceMillis) { closer.join() } == null) runCatching { onTimeout() }
        } catch (e: CancellationException) {
            runCatching { abort() }
            throw e
        } finally {
            runCatching { abort() }
        }
    }
}
