package io.github.ayatinene.sonycompanion.data

import java.net.URI
import java.util.Locale

enum class CameraMode(val title: String, val instruction: String) {
    REMOTE("遥控拍摄", "在相机上启动 Smart Remote Control，再连接相机 Wi-Fi。"),
    JPEG("JPEG 传输", "在相机选择“发送到智能手机”，再连接相机 Wi-Fi。"),
    COMPUTER("RAW / 原文件 · 实验性", "相机与手机连接同一 Wi-Fi，在相机选择“发送到电脑”。需先完成 USB 接收端登记。")
}
data class Device(val name: String, val model: String, val description: URI,
    val remote: URI? = null, val content: URI? = null, val push: URI? = null)
data class Setting(val key: String, val label: String, val current: String,
    val choices: List<String>, val writable: Boolean, val evStep: Int = 0) {
    fun display(value: String): String = if (key == "ExposureCompensation" && evStep > 0)
        String.format(Locale.US, "%+.1f EV", (value.toIntOrNull() ?: 0).toDouble() / evStep) else value
}
data class RemoteState(val apis: Set<String> = emptySet(), val settings: List<Setting> = emptyList(),
    val status: String = "", val application: String = "", val eventVersion: String = "1.0")
data class Resource(val url: String, val mime: String = "image/jpeg", val size: Long = 0,
    val resolution: String = "", val profile: String = "")
data class CameraFile(val id: String, val name: String, val resources: List<Resource> = emptyList(),
    val thumbnail: String? = null, val date: String = "", val container: Boolean = false,
    val size: Long = 0, val raw: Boolean = false, val handle: Long? = null) {
    fun bestResource(): Resource? = jpegResource()
}
data class LocalPhoto(val uri: String, val name: String, val bytes: Long, val added: Long,
    val origin: String, val sourceId: String, val pairKey: String = pairKey(name))
fun pairKey(name: String) = name.substringBeforeLast('.').uppercase(Locale.ROOT)
data class QueueItem(val file: CameraFile, val state: String = "等待", val received: Long = 0,
    val total: Long = file.size, val error: String? = null)
data class AppState(val mode: CameraMode? = null, val connected: Boolean = false,
    val busy: Boolean = false, val message: String = "请连接相机", val error: String? = null,
    val device: Device? = null, val remote: RemoteState = RemoteState(),
    val files: List<CameraFile> = emptyList(), val folder: String = "", val queue: List<QueueItem> = emptyList(),
    val transferring: Boolean = false, val local: List<LocalPhoto> = emptyList(), val logs: List<String> = emptyList(),
    val intervalRemaining: Int = 0)
