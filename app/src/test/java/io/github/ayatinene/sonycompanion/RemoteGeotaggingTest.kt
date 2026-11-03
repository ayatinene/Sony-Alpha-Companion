package io.github.ayatinene.sonycompanion

import android.Manifest
import io.github.ayatinene.sonycompanion.ui.RemoteGeotaggingPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure coverage for the geotagging permission decision, so denial handling never needs the system dialog. */
class RemoteGeotaggingTest {
    @Test fun anyGrantedLocationPermissionEnablesTheOptIn() {
        assertTrue(RemoteGeotaggingPolicy.anyGranted(mapOf(Manifest.permission.ACCESS_FINE_LOCATION to true)))
        assertTrue(RemoteGeotaggingPolicy.anyGranted(mapOf(Manifest.permission.ACCESS_COARSE_LOCATION to true)))
        assertTrue(RemoteGeotaggingPolicy.anyGranted(mapOf(
            Manifest.permission.ACCESS_FINE_LOCATION to true,
            Manifest.permission.ACCESS_COARSE_LOCATION to false)))
    }

    @Test fun deniedLocationPermissionStaysDisabled() {
        assertFalse(RemoteGeotaggingPolicy.anyGranted(emptyMap()))
        assertFalse(RemoteGeotaggingPolicy.anyGranted(mapOf(
            Manifest.permission.ACCESS_FINE_LOCATION to false,
            Manifest.permission.ACCESS_COARSE_LOCATION to false)))
        assertFalse(RemoteGeotaggingPolicy.anyGranted(mapOf(Manifest.permission.ACCESS_FINE_LOCATION to false)))
    }

    @Test fun geotaggingDefaultsOffAndHasTheDenialCopy() {
        assertFalse(RemoteGeotaggingPolicy.DEFAULT)
        assertEquals("remoteGeotagging", RemoteGeotaggingPolicy.KEY)
        assertEquals("未授予位置权限，已关闭照片位置写入。", RemoteGeotaggingPolicy.DENIED_MESSAGE)
        assertEquals("位置权限已失效，已关闭照片位置写入。", RemoteGeotaggingPolicy.REVOKED_MESSAGE)
    }
}
