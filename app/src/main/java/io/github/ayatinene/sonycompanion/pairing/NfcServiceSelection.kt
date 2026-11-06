package io.github.ayatinene.sonycompanion.pairing

import io.github.ayatinene.sonycompanion.data.CameraMode
import io.github.ayatinene.sonycompanion.data.Device

enum class PairingSource { QR, NFC }

data class NfcServiceSelection(val device: Device, val mode: CameraMode)

/** NFC credentials do not establish the camera's current application. Only advertised services do. */
fun selectNfcService(devices: List<Device>): NfcServiceSelection {
    val candidates = devices.flatMap { device ->
        buildList {
            if (device.remote != null) add(NfcServiceSelection(device, CameraMode.REMOTE))
            if (device.content != null) add(NfcServiceSelection(device, CameraMode.JPEG))
        }
    }.distinctBy { it.mode to (if (it.mode == CameraMode.REMOTE) it.device.remote else it.device.content) }
    require(candidates.size == 1) {
        if (candidates.isEmpty()) "Wi-Fi 已连接，未发现遥控或发送到智能手机服务，请检查相机当前模式"
        else "相机广播了多个可用服务，无法自动选择，请使用首页对应模式手动连接"
    }
    return candidates.single()
}
