package io.github.ayatinene.sonycompanion

import io.github.ayatinene.sonycompanion.data.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class GeotagTest {
    private class FakeRegistration : LocationRegistration {
        var closed = false
        override fun close() { closed = true }
    }

    private class FakeProvider(var permission: Boolean, var last: GeoSnapshot?, var register: Boolean = true) : LocationFixProvider {
        var callback: ((GeoSnapshot) -> Unit)? = null
        var registration: FakeRegistration? = null
        var starts = 0
        var duringStart: (() -> Unit)? = null
        override fun hasPermission() = permission
        override fun lastKnown() = last
        override fun start(onFix: (GeoSnapshot) -> Unit): LocationRegistration? {
            starts++; callback = onFix
            duringStart?.invoke()
            if (!register) return null
            return FakeRegistration().also { registration = it }
        }
    }

    /** First registration hangs until [release]; later registrations return immediately. */
    private class BlockingProvider : LocationFixProvider {
        @Volatile var permission = true
        @Volatile var last: GeoSnapshot? = null
        val handles = ArrayList<FakeRegistration>()
        val callbacks = ArrayList<(GeoSnapshot) -> Unit>()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        private val monitor = Any()
        override fun hasPermission() = permission
        override fun lastKnown() = last
        override fun start(onFix: (GeoSnapshot) -> Unit): LocationRegistration? {
            val handle = FakeRegistration()
            val index = synchronized(monitor) { val i = handles.size; handles += handle; callbacks += onFix; i }
            if (index == 0) { entered.countDown(); release.await() }
            return handle
        }
    }

    private fun fix(accuracy: Float, elapsed: Long, lat: Double = 35.681236, lon: Double = 139.767125,
        time: Long = 1_700_000_000_000L) = GeoSnapshot(lat, lon, accuracy, time, elapsed)

    @Test fun freshPreciseFixIsAccepted() {
        val source = GeotagSource(FakeProvider(true, fix(10f, 1_000_000_000L)),
            nowElapsedNanos = { 1_000_000_000L + 30_000_000_000L })
        assertNotNull(source.freshSnapshot())
    }

    @Test fun staleFixBeyondSixtySecondsIsRejected() {
        val source = GeotagSource(FakeProvider(true, fix(10f, 1_000_000_000L)),
            nowElapsedNanos = { 1_000_000_000L + 60_000_000_001L })
        assertNull(source.freshSnapshot())
    }

    @Test fun exactlySixtySecondsAndOneHundredMetersAreStillFresh() {
        val source = GeotagSource(FakeProvider(true, fix(100f, 1_000_000_000L)),
            nowElapsedNanos = { 1_000_000_000L + 60_000_000_000L })
        assertNotNull(source.freshSnapshot())
    }

    @Test fun lowPrecisionFixIsRejected() {
        val source = GeotagSource(FakeProvider(true, fix(150f, 1_000_000_000L)),
            nowElapsedNanos = { 1_000_000_000L })
        assertNull(source.freshSnapshot())
    }

    @Test fun aFixWithoutElapsedOrWithAFutureClockIsRejected() {
        assertNull(GeotagSource(FakeProvider(true, fix(10f, 0L)), nowElapsedNanos = { 1_000L }).freshSnapshot())
        assertNull(GeotagSource(FakeProvider(true, fix(10f, 2_000L)), nowElapsedNanos = { 1_000L }).freshSnapshot())
    }

    @Test fun consecutiveCallbacksReplaceTheCachedFix() {
        val provider = FakeProvider(true, null)
        val source = GeotagSource(provider, nowElapsedNanos = { 3_000L })
        source.start()
        provider.callback!!.invoke(fix(10f, 1_000L, lat = 35.0))
        assertEquals(35.0, source.freshSnapshot()!!.latitude, 1e-9)
        provider.callback!!.invoke(fix(20f, 2_000L, lat = 36.0))
        assertEquals(36.0, source.freshSnapshot()!!.latitude, 1e-9)
    }

    @Test fun lateCallbackAfterStopIsIgnored() {
        val provider = FakeProvider(true, null)
        val source = GeotagSource(provider, nowElapsedNanos = { 3_000L })
        source.start()
        val late = provider.callback!!
        source.stop()
        late(fix(10f, 2_500L))
        assertNull("a callback from a superseded registration must not populate the cache", source.freshSnapshot())
    }

    @Test fun stopReleasesTheRegistrationSoNoListenerRemains() {
        val provider = FakeProvider(true, null)
        val source = GeotagSource(provider, nowElapsedNanos = { 1L })
        source.start()
        val registration = provider.registration!!
        source.stop()
        assertTrue("stop must close exactly this registration", registration.closed)
    }

    @Test fun stopDuringRegistrationClosesTheSupersededRegistration() {
        val provider = FakeProvider(true, null)
        val source = GeotagSource(provider, nowElapsedNanos = { 1L })
        provider.duringStart = { source.stop() }
        source.start()
        assertTrue("a registration that completes after stop must be closed", provider.registration!!.closed)
        assertNull(source.freshSnapshot())
    }

    @Test fun stopDuringRegistrationReleasesTheGenerationSoANewStartSucceeds() {
        val provider = BlockingProvider()
        val source = GeotagSource(provider, nowElapsedNanos = { 10_000L })

        val first = thread { source.start() }
        assertTrue("the first registration must be in flight", provider.entered.await(5, TimeUnit.SECONDS))

        // stop must clear this generation's starting flag so the next start is not blocked forever.
        source.stop()
        source.start()
        assertEquals("a stop must release the generation so a new start can register", 2, provider.handles.size)
        val liveHandle = provider.handles[1]
        val liveCallback = provider.callbacks[1]
        assertFalse(liveHandle.closed)

        provider.release.countDown()
        first.join(5_000)
        assertTrue("the superseded registration must be closed", provider.handles[0].closed)
        assertFalse("the live registration must stay open", liveHandle.closed)

        provider.callbacks[0].invoke(fix(10f, 5_000L))
        liveCallback.invoke(fix(20f, 6_000L))
        assertEquals("only the live callback may take effect", 20f, source.freshSnapshot()!!.accuracyMeters, 1e-6f)
    }

    @Test fun permissionRevokeClearsTheCacheAndStopsSampling() {
        val provider = FakeProvider(true, fix(10f, 1_000L))
        val source = GeotagSource(provider, nowElapsedNanos = { 2_000L })
        assertNotNull(source.freshSnapshot())
        provider.permission = false
        assertNull(source.freshSnapshot())
        val startsBefore = provider.starts
        source.start()
        assertEquals("no permission must not register a listener", startsBefore, provider.starts)
    }

    @Test fun aFailedRegistrationLeavesTheSourceStoppedSoRefreshCanRetry() {
        val provider = FakeProvider(true, null, register = false)
        val source = GeotagSource(provider, nowElapsedNanos = { 1L })
        source.start()
        assertNull(provider.registration)
        provider.register = true
        source.start()
        assertNotNull("a later refresh must retry the registration", provider.registration)
    }

    @Test fun geotagOnlyAppliesToRemoteOptInWithPermission() {
        assertTrue(geotagAllowed(CameraMode.REMOTE, true, true))
        assertFalse(geotagAllowed(CameraMode.JPEG, true, true))
        assertFalse(geotagAllowed(CameraMode.COMPUTER, true, true))
        assertFalse(geotagAllowed(CameraMode.REMOTE, false, true))
        assertFalse(geotagAllowed(CameraMode.REMOTE, true, false))
        assertFalse(geotagAllowed(null, true, true))
    }

    @Test fun snapshotValidityRange() {
        assertTrue(GeoSnapshot(0.0, 0.0, 1f, 1L, 1L).isValid())
        assertFalse(GeoSnapshot(91.0, 0.0, 1f, 1L, 1L).isValid())
        assertFalse(GeoSnapshot(0.0, 181.0, 1f, 1L, 1L).isValid())
        assertFalse(GeoSnapshot(0.0, 0.0, 0f, 1L, 1L).isValid())
    }

    @Test fun gpsTimestampUsesTheFixInstantInUtc() {
        assertEquals("2023:11:14", GeoExif.gpsDate(1_700_000_000_000L))
        assertEquals("22:13:20", GeoExif.gpsTime(1_700_000_000_000L))
        assertEquals("1970:01:01", GeoExif.gpsDate(0L))
        assertEquals("00:00:00", GeoExif.gpsTime(0L))
    }

    @Test fun bindingFreezesNullAndBindsOnlyTheRpcCaptureUrls() {
        val binding = CaptureGeoBinding()
        val token = binding.begin(1L, null)
        assertTrue("a body postview is deferred while the RPC capture is unresolved", binding.defer(1L, "event"))
        assertEquals(listOf("event"), binding.complete(token, listOf("rpc")))
        assertTrue(binding.has(1L, "rpc"))
        assertNull("the frozen null must not fall back to a fresh fix", binding.geoFor(1L, "rpc"))
        assertFalse("after completion an independent event is not deferred", binding.defer(1L, "body"))
    }

    @Test fun bindingBindsTheFrozenFixToTheCaptureUrlsOnly() {
        val binding = CaptureGeoBinding()
        val frozen = fix(5f, 1L)
        val token = binding.begin(2L, frozen)
        assertTrue(binding.complete(token, listOf("rpc")).isEmpty())
        assertEquals(frozen, binding.geoFor(2L, "rpc"))
        assertFalse("an event url is never bound", binding.has(2L, "event"))
        assertNull(binding.geoFor(2L, "event"))
        assertNull("another generation never sees this url", binding.geoFor(3L, "rpc"))
    }

    @Test fun bindingDefersOnlyForTheInflightGeneration() {
        val binding = CaptureGeoBinding()
        binding.begin(3L, fix(5f, 1L))
        assertFalse(binding.defer(4L, "other-session"))
        assertTrue(binding.defer(3L, "same-session"))
        binding.clear()
        assertFalse(binding.defer(3L, "after-clear"))
    }

    @Test fun aSupersededCaptureNeverClearsOrBindsTheNewerOne() {
        val binding = CaptureGeoBinding()
        val old = fix(5f, 1L)
        val newer = fix(6f, 2L)
        val t1 = binding.begin(1L, old)
        binding.clear()
        val t2 = binding.begin(2L, newer)
        assertTrue("a stale complete is ignored", binding.complete(t1, listOf("old")).isEmpty())
        binding.abort(t1)
        assertTrue("the newer window is still inflight", binding.defer(2L, "event"))
        assertEquals(listOf("event"), binding.complete(t2, listOf("rpc")))
        assertEquals(newer, binding.geoFor(2L, "rpc"))
        assertFalse("the stale token never bound a url", binding.has(1L, "rpc"))
    }
}
