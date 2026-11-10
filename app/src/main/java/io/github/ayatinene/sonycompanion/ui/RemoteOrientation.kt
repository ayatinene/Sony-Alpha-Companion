package io.github.ayatinene.sonycompanion.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

/** Preference for following the phone's rotation while the capture page is open (default on). */
internal const val REMOTE_SENSOR_ROTATION = "remoteSensorRotation"
internal const val REMOTE_SENSOR_ROTATION_DEFAULT = true

/** Sentinel for "no pre-workspace orientation captured yet". */
private const val NO_ORIGINAL = Int.MIN_VALUE

/** Adapter over the host Activity's orientation. */
internal interface OrientationHost {
    var requestedOrientation: Int

    /** True while the host is being recreated for a configuration change; the effect must then leave the
     *  orientation alone so the recreated Activity re-applies the sensor setting itself. */
    val isChangingConfigurations: Boolean
}

internal class ActivityOrientationHost(private val activity: Activity) : OrientationHost {
    override var requestedOrientation: Int
        get() = activity.requestedOrientation
        set(value) { activity.requestedOrientation = value }
    override val isChangingConfigurations: Boolean get() = activity.isChangingConfigurations
}

/**
 * Applies the sensor orientation while composed and restores the original on true exit. The original is
 * [rememberSaveable], so a configuration change keeps it instead of fighting the recreated Activity.
 */
@Composable
internal fun RemoteSensorOrientation(enabled: Boolean, host: OrientationHost) {
    var original by rememberSaveable { mutableStateOf(NO_ORIGINAL) }
    DisposableEffect(enabled, host) {
        if (!enabled) return@DisposableEffect onDispose { }
        if (original == NO_ORIGINAL) original = host.requestedOrientation
        host.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
        onDispose {
            if (!host.isChangingConfigurations && original != NO_ORIGINAL) {
                host.requestedOrientation = original
                original = NO_ORIGINAL
            }
        }
    }
}
