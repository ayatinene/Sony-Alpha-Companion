@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package io.github.ayatinene.sonycompanion

import io.github.ayatinene.sonycompanion.pairing.*
import java.io.Closeable
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

class PairingConnectionTest {
    private val credentials=PairingCredentials.create("DIRECT-ab12:Camera","testPass")
    private class Backend : PairingBackend<String> {
        lateinit var events: PairingBackend.Events<String>
        var releases=0
        var synchronous: ((PairingBackend.Events<String>) -> Unit)?=null
        override fun request(credentials: PairingCredentials, events: PairingBackend.Events<String>): Closeable {
            this.events=events; synchronous?.invoke(events)
            return Closeable { releases++ }
        }
    }
    @Test fun approvedNetworkIsPassedToDiscoveryAndRetainedUntilSessionEnd()=runTest {
        val backend=Backend(); val lease=PairingConnection(backend) { fail("unexpected loss") }
        var discoveredOn=""
        val work=async { lease.await(credentials).also { discoveredOn=it } }
        runCurrent(); assertEquals("",discoveredOn)
        backend.events.available("camera-network"); runCurrent()
        assertEquals("camera-network",work.await()); assertEquals("camera-network",discoveredOn)
        assertEquals(0,backend.releases)
        lease.close();lease.close();assertEquals(1,backend.releases)
    }
    @Test fun rejectionAndAuthenticationFailureAreReportedAndReleaseTheCallback()=runTest {
        val backend=Backend();val lease=PairingConnection(backend) {}
        val result=async { runCatching { lease.await(credentials) } };runCurrent()
        backend.events.unavailable();runCurrent()
        assertTrue(result.await().exceptionOrNull() is IOException)
        assertEquals(1,backend.releases)
    }
    @Test fun explicitCancelAndTimeoutBothReleaseAndLateCallbacksCannotConnect()=runTest {
        val first=Backend();val lease=PairingConnection(first) { fail("late loss") }
        val work=async { lease.await(credentials) };runCurrent();work.cancelAndJoin()
        first.events.available("late");first.events.lost("late");assertEquals(1,first.releases)
        val second=Backend();val next=PairingConnection(second) {}
        val timeout=async { runCatching { next.await(credentials,100) } };runCurrent();advanceTimeBy(101);runCurrent()
        assertTrue(timeout.await().exceptionOrNull() is IOException);assertEquals(1,second.releases)
    }
    @Test fun lostAndRepeatedCallbacksDoNotInvalidateANewerCamera()=runTest {
        val first=Backend();var lost=0;val old=PairingConnection(first) { lost++ }
        val a=async { old.await(credentials) };runCurrent();first.events.available("A");assertEquals("A",a.await())
        first.events.lost("unrelated");assertEquals(0,lost)
        first.events.lost("A");first.events.lost("A");assertEquals(1,lost);assertEquals(1,first.releases)
        val second=Backend();val next=PairingConnection(second) { fail("old callback touched B") }
        val b=async { next.await(credentials) };runCurrent();second.events.available("B");assertEquals("B",b.await())
        first.events.available("late A");first.events.unavailable();first.events.lost("A")
        assertFalse(next.isClosed);assertEquals(0,second.releases);next.close()
    }
    @Test fun synchronousFailureOrCancellationBeforeRegistrationReturnsDoesNotLeak()=runTest {
        val backend=Backend().apply { synchronous={ it.unavailable() } }
        val lease=PairingConnection(backend) {}
        assertTrue(runCatching { lease.await(credentials) }.exceptionOrNull() is IOException)
        assertEquals(1,backend.releases)
        val synchronous=Backend().apply { synchronous={ it.available("A") } }
        val connected=PairingConnection(synchronous) {};assertEquals("A",connected.await(credentials));connected.close()
        assertEquals(1,synchronous.releases)
    }
    @Test fun discoveryRetriesOnlyEmptyResultsAndHonorsCancellation()=runTest {
        var attempts=0
        assertEquals(listOf("camera"),discoverPairedCamera { if(++attempts<3) emptyList() else listOf("camera") })
        assertEquals(3,attempts)
        attempts=0; assertTrue(discoverPairedCamera<String> { attempts++;emptyList() }.isEmpty());assertEquals(3,attempts)
        attempts=0; val job=launch { discoverPairedCamera<String> { attempts++;emptyList() } };runCurrent();job.cancelAndJoin()
        advanceUntilIdle();assertEquals(1,attempts)
    }
}
