package io.github.ayatinene.sonycompanion

import io.github.ayatinene.sonycompanion.data.RemoteThumbnailScheduler
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Deterministic concurrency and cancellation tests for REMOTE thumbnail scheduling. */
class RemoteThumbnailSchedulerTest {
    @Test fun twoDifferentUrlsCanRunTogetherWithoutStartingAThird() = runBlocking {
        val scheduler=RemoteThumbnailScheduler(parallelism=2)
        val started=AtomicInteger()
        val active=AtomicInteger()
        val maximum=AtomicInteger()
        val release=CompletableDeferred<Unit>()
        val tasks=(0 until 3).map { n ->
            async(Dispatchers.Default) {
                scheduler.load("thumb-$n",{null as String?}) {
                    val count=active.incrementAndGet()
                    maximum.accumulateAndGet(count,::maxOf)
                    started.incrementAndGet()
                    try { release.await(); "image-$n" }
                    finally { active.decrementAndGet() }
                }
            }
        }
        try {
            withTimeout(4_000) { while(started.get()<2) delay(10) }
            // The third request cannot enter its HTTP section until a slot is released.
            assertEquals(2,started.get())
            assertEquals(2,maximum.get())
        } finally { release.complete(Unit) }
        assertEquals(listOf("image-0","image-1","image-2"),tasks.awaitAll())
        assertEquals(2,maximum.get())
    }

    @Test fun onePermitGivesARepeatableSerialBaseline() = runBlocking {
        val scheduler=RemoteThumbnailScheduler(parallelism=1)
        val active=AtomicInteger()
        val maximum=AtomicInteger()
        val jobs=(0 until 4).map { n -> async(Dispatchers.Default) {
            scheduler.load("thumb-$n",{null as Int?}) {
                val now=active.incrementAndGet()
                maximum.accumulateAndGet(now,::maxOf)
                try { delay(30); n } finally { active.decrementAndGet() }
            }
        } }
        assertEquals(listOf(0,1,2,3),jobs.awaitAll())
        assertEquals(1,maximum.get())
    }

    @Test fun jpegAndArwWithTheSameUrlShareOneInFlightFetch() = runBlocking {
        val cache=ConcurrentHashMap<String,String>()
        val calls=AtomicInteger()
        val scheduler=RemoteThumbnailScheduler(2)
        val key="camera-generation:card:thumbnail:http://camera/thumb/DSC02200.jpg"
        val results=(0 until 12).map { async(Dispatchers.Default) {
            scheduler.load(key,{cache[key]}) {
                calls.incrementAndGet()
                delay(45)
                "decoded-thumbnail".also { cache[key]=it }
            }
        } }.awaitAll()
        assertTrue(results.all { it=="decoded-thumbnail" })
        assertEquals("even concurrent misses must not duplicate the camera GET",1,calls.get())
    }

    @Test fun laterDateNavigationUsesTheAlreadySuccessfulSessionCache() = runBlocking {
        val cache=ConcurrentHashMap<String,String>()
        val scheduler=RemoteThumbnailScheduler()
        val key="generation1:cameraScope:thumbnail:http://camera/thumb/image.jpg"
        var calls=0
        fun cached()=cache[key]
        val first=scheduler.load(key,::cached) { calls++; "image".also { cache[key]=it } }
        // A new Date page uses a different file ID, but the same session and thumbnail URL.
        val second=scheduler.load(key,::cached) { error("returning to Date must not issue another GET") }
        assertEquals("image",first)
        assertEquals(first,second)
        assertEquals(1,calls)
        assertNull("different session never reuses the old camera's key",
            scheduler.load("generation2:cameraScope:thumbnail:http://camera/thumb/image.jpg",{null as String?}) { null })
    }

    @Test fun cancelledOwnerDoesNotBlockOrPoisonANewReader() = runBlocking {
        val scheduler=RemoteThumbnailScheduler()
        val entered=CompletableDeferred<Unit>()
        val first=async(Dispatchers.Default) {
            scheduler.load("same-url",{null as String?}) {
                entered.complete(Unit)
                awaitCancellation()
            }
        }
        withTimeout(3_000) { entered.await() }
        first.cancelAndJoin()
        assertEquals("retry",withTimeout(3_000) { scheduler.load("same-url",{null as String?}) { "retry" } })
    }
}
