package io.github.ayatinene.sonycompanion.ui

import io.github.ayatinene.sonycompanion.data.CameraFile
import io.github.ayatinene.sonycompanion.data.CameraMode
import io.github.ayatinene.sonycompanion.data.ScanPhase
import io.github.ayatinene.sonycompanion.data.ScanState
import io.github.ayatinene.sonycompanion.data.captureTimeMillis
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

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

enum class GallerySort(val title: String) {
    Newest("拍摄时间：最新优先"),
    Oldest("拍摄时间：最早优先"),
    NameAscending("文件名：升序"),
    NameDescending("文件名：降序"),
    SizeAsc("文件大小：从小到大"),
    SizeDesc("文件大小：从大到小"),
}

enum class GalleryLayout { Grid, List }

/** Display and sort size: the file's own size when known, else its best JPEG resource's, else 0. */
fun effectiveFileSize(file: CameraFile): Long =
    file.size.takeIf { it > 0 } ?: file.bestResource()?.size?.takeIf { it > 0 } ?: 0L

/** Digit runs compare by value without overflow (leading zeros dropped, length then lexically); text is case-insensitive. */
internal fun naturalCompare(a: String, b: String): Int {
    var i = 0
    var j = 0
    while (i < a.length && j < b.length) {
        if (a[i].isDigit() && b[j].isDigit()) {
            var iEnd = i
            while (iEnd < a.length && a[iEnd].isDigit()) iEnd++
            var jEnd = j
            while (jEnd < b.length && b[jEnd].isDigit()) jEnd++
            val digitsA = a.substring(i, iEnd).trimStart('0')
            val digitsB = b.substring(j, jEnd).trimStart('0')
            if (digitsA.length != digitsB.length) return digitsA.length - digitsB.length
            val byDigits = digitsA.compareTo(digitsB)
            if (byDigits != 0) return byDigits
            i = iEnd
            j = jEnd
        } else {
            val charA = a[i].lowercaseChar()
            val charB = b[j].lowercaseChar()
            if (charA != charB) return charA - charB
            i++
            j++
        }
    }
    return (a.length - i) - (b.length - j)
}

/** Natural name order, falling back to the exact name and finally the id so ties stay stable. */
private fun naturalNameThenId(a: CameraFile, b: CameraFile): Int {
    val byNatural = naturalCompare(a.name, b.name)
    if (byNatural != 0) return byNatural
    val byExact = a.name.compareTo(b.name)
    if (byExact != 0) return byExact
    return a.id.compareTo(b.id)
}

/** Reverses only the natural name comparison; equal names keep the stable ascending tie-break. */
private fun naturalNameDescendingThenId(a: CameraFile, b: CameraFile): Int {
    val byNatural = naturalCompare(a.name, b.name)
    if (byNatural != 0) return -byNatural
    return naturalNameThenId(a, b)
}

/** JPEG folders use yyyy-MM-dd; Sony avContent date folders use yyyyMMdd. */
private val folderDateFormatter = DateTimeFormatter.ISO_LOCAL_DATE
private val sonyFolderDateFormatter = DateTimeFormatter.BASIC_ISO_DATE

/**
 * Strict parse of both observed camera "Date" title forms: yyyy-MM-dd and yyyyMMdd.
 * Other numeric strings, directory names, and impossible dates stay unknown.
 */
internal fun folderDateMillis(name: String, zone: ZoneId = ZoneId.systemDefault()): Long? {
    val text = name.trim()
    val formatter=when {
        text.length==10 && text[4]=='-' && text[7]=='-' -> folderDateFormatter
        text.length==8 && text.all { it in '0'..'9' } -> sonyFolderDateFormatter
        else -> return null
    }
    return try {
        LocalDate.parse(text, formatter).atStartOfDay(zone).toInstant().toEpochMilli()
    } catch (_: DateTimeException) { null }
}

/**
 * Sort key for a time sort: the real capture time when the date parses, else the confirmed date title
 * of a folder whose own date is blank. A non-blank but unreadable date is never replaced by a name.
 */
private fun sortTime(file: CameraFile, zone: ZoneId): Long? =
    captureTimeMillis(file.date, zone)
        ?: if (file.container && file.date.isBlank()) folderDateMillis(file.name, zone) else null

/**
 * Time sort shared by folders and files. Entries with an unknown time go last in both directions.
 * Two unknown folders fall back to the natural name in the chosen direction, so the default
 * newest-first keeps the old descending behaviour; unknown files keep the stable ascending fallback
 * they had before, and simultaneous known times keep that same ascending tie-break.
 */
private fun compareTime(a: CameraFile, b: CameraFile, times: Map<CameraFile, Long?>, newestFirst: Boolean): Int {
    val timeA = times[a]
    val timeB = times[b]
    if (timeA == null || timeB == null) {
        if (timeA == null && timeB == null) {
            return if (a.container && newestFirst) naturalNameDescendingThenId(a, b) else naturalNameThenId(a, b)
        }
        return if (timeA == null) 1 else -1
    }
    val byTime = timeA.compareTo(timeB)
    if (byTime != 0) return if (newestFirst) -byTime else byTime
    return naturalNameThenId(a, b)
}

/** Unknown sizes (<= 0) sort last in both directions. */
private fun compareFileSize(a: Long, b: Long, ascending: Boolean): Int {
    val aKnown = a > 0
    val bKnown = b > 0
    if (aKnown != bKnown) return if (aKnown) -1 else 1
    if (!aKnown) return 0
    val bySize = a.compareTo(b)
    return if (ascending) bySize else -bySize
}

/**
 * Folders first, then by the selected key; the same key applies inside the folder group too, so
 * sorting the camera's "Date" folders no longer ignores the setting. Folders have no real size, so
 * both size directions keep a stable natural-name ascending order instead of a fake size 0.
 */
fun gallerySorted(files: List<CameraFile>, sort: GallerySort): List<CameraFile> {
    val zone = ZoneId.systemDefault()
    val times: Map<CameraFile, Long?> =
        if (sort == GallerySort.Newest || sort == GallerySort.Oldest) files.associateWith { sortTime(it, zone) } else emptyMap()
    val sizes: Map<CameraFile, Long> =
        if (sort == GallerySort.SizeAsc || sort == GallerySort.SizeDesc) files.associateWith { effectiveFileSize(it) } else emptyMap()
    return files.sortedWith(Comparator<CameraFile> { a, b ->
        if (a.container != b.container) return@Comparator if (a.container) -1 else 1
        when (sort) {
            GallerySort.Newest, GallerySort.Oldest ->
                compareTime(a, b, times, sort == GallerySort.Newest)
            GallerySort.NameAscending -> naturalNameThenId(a, b)
            GallerySort.NameDescending -> naturalNameDescendingThenId(a, b)
            GallerySort.SizeAsc, GallerySort.SizeDesc ->
                if (a.container) naturalNameThenId(a, b)
                else compareFileSize(sizes[a] ?: 0L, sizes[b] ?: 0L, sort == GallerySort.SizeAsc)
                    .takeIf { it != 0 } ?: naturalNameThenId(a, b)
        }
    })
}

private val galleryTimeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT)

/** Local wall-clock text for a capture time, or null when the date is missing or unreadable. */
fun galleryTimeText(date: String, zone: ZoneId = ZoneId.systemDefault()): String? =
    captureTimeMillis(date, zone)?.let { Instant.ofEpochMilli(it).atZone(zone).format(galleryTimeFormat) }

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
