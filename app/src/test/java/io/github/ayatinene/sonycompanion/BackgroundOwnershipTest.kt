package io.github.ayatinene.sonycompanion

import io.github.ayatinene.sonycompanion.data.BackgroundOwnership
import org.junit.Assert.*
import org.junit.Test

class BackgroundOwnershipTest {
    @Test fun staysActiveWhileEitherLeaseIsHeld() {
        val transitions = mutableListOf<Pair<Boolean, String>>()
        val ownership = BackgroundOwnership { active, reason -> transitions += active to reason }
        assertFalse(ownership.active)

        val scan = ownership.acquire("scan")
        assertTrue(ownership.active)
        val transfer = ownership.acquire("transfer")
        scan.release()
        assertTrue("a running download must keep the service alive", ownership.active)
        transfer.release()
        assertFalse(ownership.active)

        // Releasing an already-released lease must not resurrect or underflow ownership.
        scan.release(); transfer.release()
        assertFalse(ownership.active)

        assertEquals("scan-started", transitions.first().second)
        assertTrue(transitions.first().first)
        assertTrue(transitions.any { it.second == "transfer-finished" && !it.first })
    }

    @Test fun resetReleasesEverything() {
        val ownership = BackgroundOwnership()
        ownership.acquire("scan"); ownership.acquire("transfer")
        assertTrue(ownership.active)
        ownership.reset("cancel")
        assertFalse(ownership.active)
    }

    @Test fun staleLeaseReleaseCannotReleaseANewOwner() {
        val ownership = BackgroundOwnership()
        val old = ownership.acquire("scan")
        ownership.reset("disconnect")
        assertFalse(ownership.active)

        val fresh = ownership.acquire("scan")
        assertTrue(ownership.active)
        // A stale coroutine's finally releasing the old lease must not drop the new owner.
        old.release()
        assertTrue("old release must not release a newer owner", ownership.active)
        fresh.release()
        assertFalse(ownership.active)
    }

    @Test fun nestedAcquisitionsOnlyReportTheOuterTransition() {
        val transitions = mutableListOf<Pair<Boolean, String>>()
        val ownership = BackgroundOwnership { active, reason -> transitions += active to reason }
        val a = ownership.acquire("scan")
        val b = ownership.acquire("scan")
        a.release()
        b.release()
        assertEquals(listOf(true to "scan-started", false to "scan-finished"), transitions)
    }
}
