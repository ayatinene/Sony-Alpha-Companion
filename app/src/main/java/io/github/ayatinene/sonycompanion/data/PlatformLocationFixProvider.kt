package io.github.ayatinene.sonycompanion.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat

/**
 * LocationManager-backed provider. Foreground only: callers start it around a visible REMOTE session and
 * stop it otherwise. Each start uses its own listener, so closing a registration never removes a newer
 * one. It never registers background location and never requests ACCESS_MEDIA_LOCATION.
 */
class PlatformLocationFixProvider(private val context: Context) : LocationFixProvider {
    private val manager: LocationManager? = context.getSystemService(LocationManager::class.java)
    override fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    override fun lastKnown(): GeoSnapshot? {
        if (!hasPermission()) return null
        val locationManager = manager ?: return null
        return try {
            locationManager.getProviders(true).mapNotNull { provider ->
                try { locationManager.getLastKnownLocation(provider) }
                catch (_: SecurityException) { null }
                catch (_: IllegalArgumentException) { null }
            }.mapNotNull(::snapshot).maxByOrNull { it.fixElapsedNanos }
        } catch (_: SecurityException) { null }
    }
    override fun start(onFix: (GeoSnapshot) -> Unit): LocationRegistration? {
        val locationManager = manager ?: return null
        if (!hasPermission()) return null
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) { snapshot(location)?.let(onFix) }
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        }
        var registered = 0
        runCatching {
            locationManager.getProviders(true).forEach { provider ->
                val ok = try {
                    locationManager.requestLocationUpdates(provider, 1_000L, 0f, listener, Looper.getMainLooper())
                    true
                } catch (_: SecurityException) { false }
                catch (_: IllegalArgumentException) { false }
                if (ok) registered++
            }
        }
        if (registered == 0) return null
        return object : LocationRegistration {
            override fun close() { runCatching { locationManager.removeUpdates(listener) } }
        }
    }
    private fun snapshot(location: Location): GeoSnapshot? {
        if (!location.hasAccuracy() || location.accuracy <= 0f) return null
        // A fix without elapsedRealtimeNanos cannot establish freshness, and a missing/future wall clock
        // is not a valid fix instant: reject instead of synthesizing one.
        val elapsed = location.elapsedRealtimeNanos
        if (elapsed <= 0L) return null
        val time = location.time
        if (time <= 0L || time > System.currentTimeMillis() + 5_000L) return null
        return GeoSnapshot(location.latitude, location.longitude, location.accuracy, time, elapsed)
    }
}
