package io.github.ayatinene.sonycompanion.data

import io.github.ayatinene.sonycompanion.protocol.AV_CONTENT_FORMAT_JPEG
import io.github.ayatinene.sonycompanion.protocol.AV_CONTENT_FORMAT_RAW
import io.github.ayatinene.sonycompanion.protocol.AvContentItem
import io.github.ayatinene.sonycompanion.protocol.AvContentPage
import java.util.Locale
import java.util.UUID

/** Download transport tokens carried by [CameraFile.protocol]. */
const val PROTOCOL_HTTP = "http"
const val PROTOCOL_PTP = "ptp"

/** Camera function values passed to setCameraFunction. */
object RemoteFunction {
    const val REMOTE_SHOOTING = "Remote Shooting"
    const val CONTENTS_TRANSFER = "Contents Transfer"
}

/**
 * The same-connection sub-mode. Shooting and gallery are mutually exclusive: the camera body can only
 * be in one function at a time, so every transition is serialized by the controller and gated by
 * [RemoteGalleryPolicy] before any RPC is sent.
 */
enum class RemoteSubMode { Shooting, SwitchingToGallery, Gallery, SwitchingToShooting }

/**
 * Pure decision rules for entering and leaving the same-connection gallery. No Android, no I/O, so the
 * safety boundaries (recording / interval / transfer / busy / unknown status) are unit tested directly.
 */
object RemoteGalleryPolicy {
    /** Camera statuses treated as idle. Anything else non-blank is an activity we do not understand. */
    private val idleStatuses = setOf("", "idle", "standby")

    fun isRecording(status: String): Boolean = status.contains("MovieRecording", ignoreCase = true)

    /** True only for a known-idle (or not-yet-reported blank) camera status. */
    fun isIdleStatus(status: String): Boolean = status.trim().lowercase(Locale.ROOT) in idleStatuses

    /**
     * Entering the gallery is refused while the camera is capturing (including long exposure), an
     * interval run is active, a transfer is in flight, the controller is busy, a switch is already
     * running, or the reported status is not a known-idle one.
     */
    fun canEnter(
        connected: Boolean,
        gallerySupported: Boolean,
        subMode: RemoteSubMode,
        status: String,
        intervalActive: Boolean,
        transferring: Boolean,
        busy: Boolean
    ): Boolean {
        if (!connected || !gallerySupported) return false
        if (subMode != RemoteSubMode.Shooting) return false
        if (intervalActive || transferring || busy) return false
        if (isRecording(status)) return false
        if (!isIdleStatus(status)) return false
        return true
    }

    /** Leaving is refused while a download is in progress; a completed queue is fine. */
    fun canReturn(connected: Boolean, subMode: RemoteSubMode, transferring: Boolean, busy: Boolean): Boolean =
        connected && subMode == RemoteSubMode.Gallery && !transferring && !busy

    /** Human-readable reason the gallery entry was refused, for the UI message. */
    fun enterRefusal(status: String, intervalActive: Boolean, transferring: Boolean, busy: Boolean): String = when {
        transferring -> "正在下载文件，完成后可进入相机图库"
        busy -> "正在处理，请稍候再进入相机图库"
        intervalActive -> "间隔拍摄进行中，结束后可进入相机图库"
        isRecording(status) -> "录像进行中，结束后可进入相机图库"
        !isIdleStatus(status) -> "相机忙碌，请待机后再进入相机图库"
        else -> "当前无法进入相机图库"
    }
}

/**
 * Stable, UDN-keyed queue scope for the avContent gallery. It is independent from the JPEG / COMPUTER
 * scopes so a camera's gallery downloads never collide with another mode. A camera whose identity
 * cannot be confirmed gets a per-session scope so queues never leak across sessions.
 */
fun avContentQueueScope(device: Device): String =
    "${device.cameraId ?: "session:${UUID.randomUUID()}"}|avContent"

/**
 * One avContent content item maps to one downloadable [CameraFile] per original resource, because the
 * gallery filters and downloads by explicit format. A RAW+JPEG shot therefore expands to two entries
 * (the declared total is a content count, not a file count). [id] embeds source + content id + format
 * + original file name so a restored queue entry is matched back to the exact original without
 * trusting its stored URL, and two same-format originals of one item never collide.
 */
fun AvContentItem.toCameraFiles(): List<CameraFile> =
    if (isDateFolder) listOf(CameraFile(
        id = contentId, name = name, container = true, parent = source, protocol = PROTOCOL_HTTP
    )) else original.map { res ->
    val raw = res.format == AV_CONTENT_FORMAT_RAW
    val originalName = res.name.ifBlank { res.url.substringAfterLast('/').substringBefore('?') }
    // [parent] keeps same-named items in different parent folders apart so RAW/JPEG pairing and
    // recovery never match across directories.
    val folder = if (folderNo.isBlank()) source else "$source/$folderNo"
    CameraFile(
        id = "$source#$contentId#${res.format}#$originalName",
        name = originalName.ifBlank { contentId },
        resources = listOf(Resource(res.url, if (raw) "image/x-sony-arw" else "image/jpeg", res.size)),
        thumbnail = thumbnailUrl ?: previewUrl,
        date = date,
        size = res.size,
        raw = raw,
        handle = null,
        parent = folder,
        original = true,
        format = res.format,
        protocol = PROTOCOL_HTTP
    )
}

/** True when [file] is an HTTP original whose format is [format] (jpeg / arw). */
fun CameraFile.isHttpOriginalOf(format: String): Boolean =
    original && protocol == PROTOCOL_HTTP && this.format == format

/**
 * Bounded flat pagination state for one avContent source. The first page is published immediately;
 * [accept] dedups by content id, advances the camera index by the raw returned count, and decides
 * whether another page exists. A short, empty or zero-progress page terminates the walk instead of
 * spinning.
 */
class GalleryPager(val pageSize: Int = 20) {
    var source: String? = null; private set
    var total: Int? = null; private set
    /** Number of content items loaded so far (not the expanded file count). */
    var contentLoaded: Int = 0; private set
    var hasMore: Boolean = false; private set
    private var offset: Int = 0
    private val seen = LinkedHashSet<String>()
    private val seenRaw = LinkedHashSet<String>()

    /** Next `stIdx` to request from the camera. */
    val nextStart: Int get() = offset

    fun reset() {
        source = null; total = null; contentLoaded = 0; hasMore = false; offset = 0; seen.clear(); seenRaw.clear()
    }

    fun begin(source: String, total: Int?) {
        reset(); this.source = source; this.total = total
    }

    /**
     * Accepts one page and returns the fresh (not previously seen) items in camera order. Progress is
     * measured on the raw page identities ([AvContentPage.rawUris]) so a page whose entries are all
     * filtered out as unknown kinds still advances and never terminates the walk early; only a truly
     * duplicate or empty page stops it.
     */
    fun accept(page: AvContentPage): List<AvContentItem> {
        if (page.returned <= 0) { hasMore = false; return emptyList() }
        val rawKeys = page.rawUris.filter { it.isNotEmpty() }
        val progressKeys = rawKeys.ifEmpty { page.items.map { it.contentId } }
        // Record every key (no short-circuit): `any` would stop at the first new key and leave the rest
        // unrecorded, so the next identical page would look like progress.
        val progressed = progressKeys.map { seenRaw.add(it) }.any { it }
        offset += page.returned
        val fresh = page.items.filter { seen.add(it.contentId) }
        contentLoaded += fresh.size
        if (!progressed) { hasMore = false; return emptyList() }
        val reachedTotal = total?.let { it in 0..offset } ?: false
        hasMore = !reachedTotal && page.returned >= pageSize
        return fresh
    }
}
