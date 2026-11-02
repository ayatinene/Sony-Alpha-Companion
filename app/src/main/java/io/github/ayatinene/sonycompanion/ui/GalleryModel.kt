package io.github.ayatinene.sonycompanion.ui

import io.github.ayatinene.sonycompanion.data.CameraFile
import io.github.ayatinene.sonycompanion.data.CameraMode
import io.github.ayatinene.sonycompanion.data.ScanPhase
import io.github.ayatinene.sonycompanion.data.ScanState

/**
 * Copy and selection rules for the camera gallery. Kept free of Android types so they can be unit
 * tested on the desktop JVM and shared by the JPEG and RAW / original-file workspaces.
 */

data class GalleryEmpty(val title: String, val text: String)

/** Headline for the scan progress bar. Null when there is nothing to say. */
fun scanHeadline(scan: ScanState, transferring: Boolean): String? = when (scan.phase) {
    ScanPhase.Scanning -> when {
        scan.paused && transferring -> "下载进行中，查找已暂停"
        scan.paused -> "查找已暂停"
        else -> "正在扫描相机文件"
    }
    ScanPhase.Done -> "查找完成"
    ScanPhase.Failed -> "查找失败"
    ScanPhase.Idle -> null
}

/**
 * Counts for the scan bar. Uses the camera-reported total when present and never invents one, so a
 * RAW scan that does not know its size is still readable.
 */
fun scanCountText(scan: ScanState): String? {
    if (scan.phase != ScanPhase.Scanning && scan.phase != ScanPhase.Done) return null
    val checked = if (scan.total != null) "已检查 ${scan.scanned}/${scan.total} 项" else "已检查 ${scan.scanned} 项"
    return "$checked，发现 ${scan.found} 个文件"
}

/** Empty-gallery copy that follows the scan phase instead of claiming "no photos" while still reading. */
fun galleryEmptyState(mode: CameraMode?, scan: ScanState): GalleryEmpty = when (scan.phase) {
    ScanPhase.Scanning -> GalleryEmpty("正在查找照片", "正在读取相机文件，请稍候…")
    ScanPhase.Failed -> GalleryEmpty("查找失败", scan.error?.takeIf { it.isNotBlank() } ?: "无法读取相机文件，请重试。")
    ScanPhase.Done -> GalleryEmpty("没有照片", "相机存储卡里没有可显示的文件。")
    ScanPhase.Idle -> if (mode == CameraMode.JPEG)
        GalleryEmpty("没有文件", "可返回根目录，或在相机选择需要发送的照片。")
    else GalleryEmpty("没有照片", "相机存储卡里没有可显示的文件。")
}

fun galleryFiltered(files: List<CameraFile>, filter: String): List<CameraFile> =
    files.filter { it.container || filter == "全部" || (filter == "ARW" && it.raw) || (filter == "JPEG" && !it.raw) }

fun gallerySorted(files: List<CameraFile>, descending: Boolean): List<CameraFile> =
    if (descending) files.sortedWith(compareByDescending<CameraFile> { it.container }.thenByDescending { it.date }.thenByDescending { it.name })
    else files.sortedWith(compareByDescending<CameraFile> { it.container }.thenBy { it.date }.thenBy { it.name })

/** Only files that are on screen may be acted on; a hidden selection is kept but never downloaded. */
fun visibleSelected(selected: Set<String>, visibleIds: Set<String>): Set<String> = selected intersect visibleIds

fun hiddenSelectedCount(selected: Set<String>, visibleIds: Set<String>): Int = (selected - visibleIds).size

/** Select-all covers only files currently discovered and passing the filter, never placeholders. */
fun selectAllIds(files: List<CameraFile>): Set<String> = files.filter { !it.container }.map { it.id }.toSet()

/** Network reads (thumbnail / preview) need a live, non-failed session with no transfer in flight. */
fun galleryReadAllowed(connected: Boolean, scan: ScanState, transferring: Boolean): Boolean =
    connected && scan.phase != ScanPhase.Failed && !transferring

/**
 * Resuming an unfinished queue needs a live session. For RAW / original-file mode the scan must have
 * finished, because the queue is reconciled against the file list.
 */
fun continueHistoryEnabled(connected: Boolean, busy: Boolean, transferring: Boolean, mode: CameraMode?, scan: ScanState): Boolean =
    connected && !busy && !transferring && (mode != CameraMode.COMPUTER || scan.phase == ScanPhase.Done)

/** Newly selected files can be queued even while the RAW scan is still running. */
fun downloadNewFilesEnabled(connected: Boolean, scan: ScanState): Boolean =
    connected && scan.phase != ScanPhase.Failed
