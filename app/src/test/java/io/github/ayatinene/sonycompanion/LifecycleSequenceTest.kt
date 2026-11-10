package io.github.ayatinene.sonycompanion

import io.github.ayatinene.sonycompanion.data.PendingResources
import io.github.ayatinene.sonycompanion.data.SessionActions
import io.github.ayatinene.sonycompanion.data.SessionEndSequence
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Connection-attempt ownership and the ordered, bounded end sequence used by disconnect/cancel. */
class LifecycleSequenceTest {
    @Test fun abortReleasesOwnResourcesAndClosesLateRegistrations() {
        val resources=PendingResources()
        val closed=mutableListOf<String>()
        resources.register { closed += "transport" }
        resources.abortAll()
        assertEquals(listOf("transport"),closed)
        // A resource created while the attempt is aborting must not leak.
        resources.register { closed += "late" }
        assertEquals(listOf("transport","late"),closed)
        resources.abortAll()
        assertEquals("abort is idempotent",listOf("transport","late"),closed)
    }

    @Test fun releaseDropsHandlesSoALaterAbortCannotCloseTheLiveSession() {
        val resources=PendingResources()
        var closed=false
        resources.register { closed=true }
        resources.release()
        resources.abortAll()
        assertFalse("a promoted session's transport must survive an external abort",closed)
    }

    @Test fun aStaleAttemptCannotReleaseANewerAttemptsResources() {
        val old=PendingResources()
        val current=PendingResources()
        var oldClosed=false; var currentClosed=false
        old.register { oldClosed=true }
        current.register { currentClosed=true }
        old.abortAll()
        assertTrue(oldClosed)
        assertFalse("aborting the old attempt must not touch the new one",currentClosed)
        current.release()
        assertFalse(currentClosed)
    }

    @Test fun sessionEndSequenceRunsInOrderAndAlwaysReleasesTheTransport() = runBlocking {
        val order=mutableListOf<String>()
        val failures=mutableListOf<String>()
        SessionEndSequence(500).run(listOf(
            SessionEndSequence.Step("remote"){ order+="remote"; throw IllegalStateException("camera gone") },
            SessionEndSequence.Step("smartphone"){ order+="smartphone" },
            SessionEndSequence.Step("close"){ order+="close" },
        )) { step,_ -> failures += step.name }
        assertEquals(listOf("remote","smartphone","close"),order)
        assertEquals(listOf("remote"),failures)
    }

    @Test fun sessionEndSequenceBoundsABlockingStep() = runBlocking {
        val order=mutableListOf<String>()
        val start=System.nanoTime()
        SessionEndSequence(80).run(listOf(
            SessionEndSequence.Step("stuck"){ delay(10_000) },
            SessionEndSequence.Step("close"){ order+="close" },
        ))
        val elapsed=(System.nanoTime()-start)/1_000_000
        assertEquals("the bounded step must not skip the transport close",listOf("close"),order)
        assertTrue("blocking step must be bounded, was ${elapsed}ms",elapsed<2_000)
    }

    @Test fun sessionActionsCancelsAndJoinsTrackedJobs() = runBlocking {
        val actions=SessionActions()
        var ran=false
        val gate=CompletableDeferred<Unit>()
        val job=launch { gate.await(); ran=true }
        actions.track(job)
        val detached=actions.detach()
        actions.cancel(detached)
        actions.join(detached)
        assertTrue(job.isCancelled)
        assertFalse("a cancelled action must not run to completion",ran)
    }

    @Test fun sessionActionsDetachNeverTouchesANewerAction() = runBlocking {
        val actions=SessionActions()
        val old=launch { delay(10_000) }
        actions.track(old)
        val detached=actions.detach()
        val fresh=launch { delay(50) }
        actions.track(fresh)
        actions.cancel(detached)
        assertTrue(old.isCancelled)
        assertFalse("an old detach must not cancel a newer action",fresh.isCancelled)
        fresh.join()
    }

    @Test fun sessionActionsExcludesTheDisconnectJobItself() = runBlocking {
        val actions=SessionActions()
        val keep=launch { delay(50) }
        val drop=launch { delay(10_000) }
        actions.track(keep); actions.track(drop)
        val detached=actions.detach()
        actions.cancel(detached,keep)
        assertFalse(keep.isCancelled)
        assertTrue(drop.isCancelled)
        keep.join()
    }
}
