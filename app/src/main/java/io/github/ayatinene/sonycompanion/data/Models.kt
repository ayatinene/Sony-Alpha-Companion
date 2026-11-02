package io.github.ayatinene.sonycompanion.data

import java.net.URI
import java.util.Locale

enum class CameraMode(val title: String, val instruction: String) {
    REMOTE("遥控拍摄", "在相机上启动 Smart Remote Control，再连接相机 Wi-Fi。"),
    JPEG("JPEG 传输", "在相机选择“发送到智能手机”，再连接相机 Wi-Fi。"),
    COMPUTER("RAW / 原文件 · 实验性", "相机与手机连接同一 Wi-Fi，在相机选择“发送到电脑”。需先通过设置中的 USB OTG 工具或电脑完成接收端登记。")
}
data class Device(val name: String, val model: String, val description: URI,
    val remote: URI? = null, val content: URI? = null, val push: URI? = null,
    val digitalImaging: URI? = null, val identity: CameraIdentity = CameraIdentity(),
    val udn: String = "", val serial: String = "") {
    val displayName: String get() = identity.model.ifBlank { model.takeUnless { it.isBlank() || it=="SonyImagingDevice" } ?: name }
    val cameraId: String? get() = stableCameraId(udn, serial, model)
}
data class CameraApplication(val name: String, val version: String)
data class CameraIdentity(val model: String="", val firmware: String="", val lens: String="",
    val lensFirmware: String="", val applications: List<CameraApplication> = emptyList())
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
    val size: Long = 0, val raw: Boolean = false, val handle: Long? = null, val parent: String = "") {
    fun bestResource(): Resource? = jpegResource()
}
/** A queued COMPUTER file must uniquely match the current authoritative listing; a stale capture with
 * the same id but different metadata (size/date/parent/name) is never enqueued. */
fun matchCurrentFile(candidate: CameraFile, current: List<CameraFile>): CameraFile? = current.filter {
    it.id==candidate.id && it.parent==candidate.parent && it.name==candidate.name && it.date==candidate.date && it.size==candidate.size
}.singleOrNull()
data class LocalPhoto(val uri: String, val name: String, val bytes: Long, val added: Long,
    val origin: String, val sourceId: String, val pairKey: String = localPairKey(name,sourceId))
fun pairKey(name: String) = name.substringBeforeLast('.').uppercase(Locale.ROOT)
fun localPairKey(name: String, source: String): String {
    val parts=source.split('|')
    val directory=if(parts.size>=7) parts.take(3).joinToString("|") else source
    return "$directory|${pairKey(name)}"
}
data class QueueItem(val file: CameraFile, val state: String = "等待", val received: Long = 0,
    val total: Long = file.size, val error: String? = null)
enum class ScanPhase { Idle, Scanning, Done, Failed }
/** Progress of the COMPUTER (PTP) listing scan. `paused` is true while the scan is held back, either
 * by the user or by an in-flight download; the user's explicit pause is never lifted by a download
 * finishing. */
data class ScanState(val phase: ScanPhase = ScanPhase.Idle, val scanned: Int = 0, val total: Int? = null,
    val found: Int = 0, val paused: Boolean = false, val error: String? = null)
data class AppState(val mode: CameraMode? = null, val connected: Boolean = false,
    // `connecting` is owned by CompanionController.connect; `busy` is shared by browsing/USB/transfers and
    // is not a connection signal.
    val connecting: Boolean = false,
    val busy: Boolean = false, val message: String = "请连接相机", val error: String? = null,
    val device: Device? = null, val remote: RemoteState = RemoteState(),
    val files: List<CameraFile> = emptyList(), val folder: String = "", val queue: List<QueueItem> = emptyList(),
    val transferring: Boolean = false, val local: List<LocalPhoto> = emptyList(), val logs: List<String> = emptyList(),
    val intervalRemaining: Int = 0, val queueScope: String? = null, val queueNotice: String = "",
    val scan: ScanState = ScanState(), val sessionGeneration: Long = 0)
