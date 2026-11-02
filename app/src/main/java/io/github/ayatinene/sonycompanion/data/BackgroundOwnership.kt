package io.github.ayatinene.sonycompanion.data

import java.util.Collections
import java.util.IdentityHashMap

/**
 * Shared ownership of background work through identity leases. The foreground service and its wake
 * lock are held while at least one lease is active, so one unit finishing while another still runs
 * never tears the service down. [reset] invalidates every outstanding lease, so a stale coroutine's
 * `finally` releasing an old lease can never release a newer owner's work.
 *
 * A transition is only reported when the active state actually changes, so a nested acquisition does
 * not restart the service again.
 */
class BackgroundOwnership(private val onTransition: (active: Boolean, reason: String) -> Unit = { _, _ -> }) {
    private val leases = Collections.newSetFromMap(IdentityHashMap<Lease, Boolean>())
    @Volatile private var activeFlag = false

    val active: Boolean get() = activeFlag

    class Lease internal constructor(private val parent: BackgroundOwnership, private val kind: String) {
        private var released = false
        @Synchronized fun release() {
            if (released) return
            released = true
            parent.release(this, kind)
        }
    }

    @Synchronized fun acquire(kind: String): Lease {
        val lease = Lease(this, kind)
        leases += lease
        refresh("$kind-started")
        return lease
    }

    @Synchronized private fun release(lease: Lease, kind: String) {
        if (leases.remove(lease)) refresh("$kind-finished")
    }

    @Synchronized fun reset(reason: String) {
        if (leases.isEmpty() && !activeFlag) return
        leases.clear()
        refresh(reason)
    }

    @Synchronized private fun refresh(reason: String) {
        val next = leases.isNotEmpty()
        if (next != activeFlag) { activeFlag = next; onTransition(next, reason) }
    }
}
