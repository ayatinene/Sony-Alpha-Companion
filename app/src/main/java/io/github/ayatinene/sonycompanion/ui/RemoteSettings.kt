package io.github.ayatinene.sonycompanion.ui

import android.Manifest
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

internal const val REMOTE_SENSOR_ROTATION_TITLE = "拍摄页跟随手机旋转"
internal const val REMOTE_SENSOR_ROTATION_DESCRIPTION =
    "开启后，即使系统锁定方向，拍摄页仍随手机切换横竖屏；退出拍摄页后恢复系统方向设置。"

internal const val REMOTE_GEOTAGGING_TITLE = "为遥控照片添加手机位置"
internal const val REMOTE_GEOTAGGING_DESCRIPTION =
    "仅用于遥控拍摄保存到手机的照片，不修改相机原片。定位不可用或不准确时不添加，不影响拍照。分享照片时也会携带位置信息。"

internal object RemoteGeotaggingPolicy {
    const val KEY = "remoteGeotagging"
    const val DEFAULT = false
    const val DENIED_MESSAGE = "未授予位置权限，已关闭照片位置写入。"
    const val REVOKED_MESSAGE = "位置权限已失效，已关闭照片位置写入。"
    const val PERMANENT_MESSAGE = "位置权限已被拒绝，请在系统设置中开启后重试。"
    val PERMISSIONS = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

    /** Any granted location permission is enough to enable; the controller still applies the accuracy gate. */
    fun anyGranted(results: Map<String, Boolean>): Boolean = PERMISSIONS.any { results[it] == true }
}

/** Remote-shooting rotation toggle; the preference is written immediately on change. */
@Composable
internal fun RemoteSensorRotationSetting(
    checked: Boolean,
    modifier: Modifier = Modifier,
    onCheckedChange: (Boolean) -> Unit,
) {
    SettingRow(
        title = REMOTE_SENSOR_ROTATION_TITLE,
        description = REMOTE_SENSOR_ROTATION_DESCRIPTION,
        checked = checked,
        onCheckedChange = onCheckedChange,
        tag = "remote-sensor-rotation",
        modifier = modifier,
    )
}

/** Geotagging toggle: enabling asks for the location permission, disabling is immediate. */
@Composable
internal fun RemoteGeotaggingSetting(
    checked: Boolean,
    onEnableRequest: () -> Unit,
    onDisable: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingRow(
        title = REMOTE_GEOTAGGING_TITLE,
        description = REMOTE_GEOTAGGING_DESCRIPTION,
        checked = checked,
        onCheckedChange = { want -> if (want) onEnableRequest() else onDisable() },
        tag = "remote-geotagging",
        modifier = modifier,
    )
}
