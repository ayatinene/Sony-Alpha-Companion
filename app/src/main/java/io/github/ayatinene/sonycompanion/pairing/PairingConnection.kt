package io.github.ayatinene.sonycompanion.pairing

import java.io.Closeable
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

interface PairingBackend<N> {
    interface Events<N> {
        fun available(network: N)
        fun unavailable()
        fun lost(network: N)
    }
    fun request(credentials: PairingCredentials, events: Events<N>): Closeable
}

/** One request owns one callback until session end. Handles synchronous callbacks and late delivery.
 * It deliberately survives Activity pause/stop (including Android's approval UI) and recreation. */
class PairingConnection<N>(private val backend: PairingBackend<N>, private val onLost: (N) -> Unit) : Closeable {
    private val lock = Any()
    private val ready = CompletableDeferred<N>()
    private var registration: Closeable? = null
    private var closed = false
    private var started = false
    private var selected: N? = null
    val isClosed: Boolean get() = synchronized(lock) { closed }
    suspend fun await(credentials: PairingCredentials, timeoutMillis: Long = 60_000): N {
        synchronized(lock) { check(!started && !closed); started = true }
        try {
            val handle = backend.request(credentials, object : PairingBackend.Events<N> {
                override fun available(network: N) {
                    synchronized(lock) {
                        if (closed || selected != null) return
                        selected = network
                        ready.complete(network)
                    }
                }
                override fun unavailable() {
                    synchronized(lock) {
                        if (closed) return
                        ready.completeExceptionally(IOException("Wi-Fi 连接未完成：可能已拒绝、密码错误或相机不可用，请重试"))
                    }
                    close()
                }
                override fun lost(network: N) {
                    val notify = synchronized(lock) {
                        if (closed || selected != network) false else {
                            ready.completeExceptionally(IOException("相机 Wi-Fi 已断开")); true
                        }
                    }
                    if (notify) { close(); onLost(network) }
                }
            })
            val discard = synchronized(lock) { if (closed) true else { registration = handle; false } }
            if (discard) handle.close()
            return withTimeout(timeoutMillis) { ready.await() }.also {
                if (isClosed) throw IOException("相机 Wi-Fi 已断开")
            }
        } catch (_: TimeoutCancellationException) { close(); throw IOException("等待 Wi-Fi 连接超时，请确认相机热点已开启后重试") }
        catch (e: Exception) { close(); throw e }
    }
    override fun close() {
        val old = synchronized(lock) {
            if (closed) return
            closed = true
            ready.cancel()
            registration.also { registration = null; selected = null }
        }
        old?.close()
    }
}

/** A newly joined hotspot may advertise SSDP late. Never retry indefinitely or on another network. */
suspend fun <T> discoverPairedCamera(discover: suspend () -> List<T>): List<T> {
    repeat(3) { attempt ->
        currentCoroutineContext().ensureActive()
        val devices = discover()
        if (devices.isNotEmpty()) return devices
        if (attempt < 2) delay(750)
    }
    return emptyList()
}
