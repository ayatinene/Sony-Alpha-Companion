package io.github.ayatinene.sonycompanion.data

import android.os.SystemClock
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * A phone location fix reduced to what EXIF needs, captured at shutter/postview time and kept with the
 * queue entry for that one photo. It is only written into the REMOTE phone copy's EXIF on opt-in and is
 * never surfaced to the UI.
 */
data class GeoSnapshot(val latitude: Double, val longitude: Double, val accuracyMeters: Float,
    val fixTimeMillis: Long, val fixElapsedNanos: Long) {
    fun isValid(): Boolean = latitude in -90.0..90.0 && longitude in -180.0..180.0 && accuracyMeters > 0f
}

/** Freshness/precision policy; defaults 60s / 100m, both tunable. */
class GeoPolicy(val maxAgeMillis: Long = 60_000L, val maxAccuracyMeters: Float = 100f) {
    fun acceptable(fix: GeoSnapshot, nowElapsedNanos: Long): Boolean =
        fix.isValid() && fix.accuracyMeters <= maxAccuracyMeters && fix.fixElapsedNanos > 0L &&
            nowElapsedNanos >= fix.fixElapsedNanos &&
            nowElapsedNanos - fix.fixElapsedNanos <= maxAgeMillis * 1_000_000L
}

/** A fix is only ever attached to the REMOTE phone copy, never to camera-original JPEG/RAW imports. */
fun geotagAllowed(mode: CameraMode?, enabled: Boolean, permissionGranted: Boolean): Boolean =
    mode == CameraMode.REMOTE && enabled && permissionGranted

/** One live provider registration; [close] unregisters exactly this registration, not a newer one. */
interface LocationRegistration { fun close() }

/** Platform location seam. [start] returns a registration handle, or null when nothing was registered. */
interface LocationFixProvider {
    fun hasPermission(): Boolean
    fun lastKnown(): GeoSnapshot?
    fun start(onFix: (GeoSnapshot) -> Unit): LocationRegistration?
}

/**
 * Foreground-only location source. [freshSnapshot] reads a cached fix and never blocks the shutter;
 * [start]/[stop] bracket the visible REMOTE session. Lifecycle, the cached fix and the generation guard
 * share one lock; the platform is only ever called outside it.
 */
interface LocationSource {
    fun hasPermission(): Boolean
    fun freshSnapshot(): GeoSnapshot?
    fun requestRefresh()
    fun start()
    fun stop()
}

class GeotagSource(private val provider: LocationFixProvider,
    private val nowElapsedNanos: () -> Long = { SystemClock.elapsedRealtimeNanos() },
    private val policy: GeoPolicy = GeoPolicy()) : LocationSource {
    private val lock = Any()
    private var latest: GeoSnapshot? = null
    private var generation = 0L
    private var starting = false
    private var registration: LocationRegistration? = null

    override fun hasPermission(): Boolean = provider.hasPermission()

    override fun freshSnapshot(): GeoSnapshot? {
        if (!provider.hasPermission()) { synchronized(lock) { latest = null }; return null }
        val cached = synchronized(lock) { latest }
        val fix = cached ?: provider.lastKnown()?.also { snapshot -> synchronized(lock) { latest = snapshot } } ?: return null
        return fix.takeIf { policy.acceptable(it, nowElapsedNanos()) }
    }

    override fun requestRefresh() = start()

    override fun start() {
        if (!provider.hasPermission()) return
        val gen = synchronized(lock) {
            if (registration != null || starting) return
            starting = true
            ++generation
        }
        // Register outside the lock; the platform call may block or deliver a late callback.
        val reg = try { provider.start { fix -> synchronized(lock) { if (generation == gen) latest = fix } } }
        catch (_: Exception) { null }
        var closeIt = false
        synchronized(lock) {
            if (generation == gen) {
                // Still the owning generation: publish the registration, or clear starting so a retry works.
                starting = false
                if (registration == null) registration = reg else closeIt = true
            } else {
                // Superseded by a stop/start: never touch the newer generation's starting/registration.
                closeIt = true
            }
        }
        if (closeIt) runCatching { reg?.close() }
    }

    override fun stop() {
        val reg = synchronized(lock) {
            generation++
            starting = false
            val current = registration
            registration = null
            latest = null
            current
        }
        runCatching { reg?.close() }
    }
}

/**
 * Freezes the GPS decision (including null) for an in-flight phone capture and defers camera-body
 * postviews while that capture is unresolved. An event URL that arrives first therefore cannot steal
 * the RPC capture's fix, and an independent body capture can never inherit the previous capture's fix:
 * event postviews are only bound by the in-flight RPC capture, otherwise they are noGPS. Every in-flight
 * window carries a token, so a superseded capture can never clear or bind a newer one.
 */
class CaptureGeoBinding {
    private data class Bound(val gen: Long, val geo: GeoSnapshot?)
    private var nextToken = 0L
    private var inflightToken = 0L
    private var inflightGen: Long? = null
    private var frozenGeo: GeoSnapshot? = null
    private val frozenByUrl = LinkedHashMap<String, Bound>()
    private val deferred = ArrayList<String>()

    /** Starts a capture for [gen]; the returned token owns this in-flight window. */
    @Synchronized fun begin(gen: Long, geo: GeoSnapshot?): Long {
        nextToken++
        inflightToken = nextToken
        inflightGen = gen
        frozenGeo = geo
        deferred.clear()
        return nextToken
    }

    /** Binds the frozen fix to the capture URLs and returns deferred event URLs. Only the owning token acts. */
    @Synchronized fun complete(token: Long, urls: List<String>): List<String> {
        if (token != inflightToken) return emptyList()
        val gen = inflightGen
        if (gen != null) {
            urls.forEach { frozenByUrl[it] = Bound(gen, frozenGeo) }
            while (frozenByUrl.size > 500) frozenByUrl.remove(frozenByUrl.keys.first())
        }
        inflightToken = 0L; inflightGen = null; frozenGeo = null
        val flushed = deferred.toList(); deferred.clear()
        return flushed
    }

    /** Abandons the window owned by [token]; a superseded capture is ignored. */
    @Synchronized fun abort(token: Long) {
        if (token != inflightToken) return
        inflightToken = 0L; inflightGen = null; frozenGeo = null; deferred.clear()
    }

    /** Defers a camera-body postview while a capture for [gen] is unresolved; true when deferred. */
    @Synchronized fun defer(gen: Long, url: String): Boolean {
        if (inflightGen != gen) return false
        deferred += url
        return true
    }

    @Synchronized fun has(gen: Long, url: String): Boolean = frozenByUrl[url]?.gen == gen
    @Synchronized fun geoFor(gen: Long, url: String): GeoSnapshot? =
        frozenByUrl[url]?.takeIf { it.gen == gen }?.geo

    @Synchronized fun clear() {
        inflightToken = 0L; inflightGen = null; frozenGeo = null; frozenByUrl.clear(); deferred.clear()
    }
}

/** Pure UTC GPS EXIF formatting. */
object GeoExif {
    private val DATE = DateTimeFormatter.ofPattern("yyyy:MM:dd", Locale.US)
    private val TIME = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.US)
    fun gpsDate(fixTimeMillis: Long): String =
        Instant.ofEpochMilli(fixTimeMillis).atZone(ZoneOffset.UTC).format(DATE)
    fun gpsTime(fixTimeMillis: Long): String =
        Instant.ofEpochMilli(fixTimeMillis).atZone(ZoneOffset.UTC).format(TIME)
}
