package io.github.ayatinene.sonycompanion.data

/** These names describe resources actually returned by ContentDirectory, not camera model promises. */
enum class JpegSize(val title: String) {
    MAXIMUM("最大可用"), ORIGINAL("原尺寸 JPEG"), LARGE("大图"), SMALL("小图"), THUMBNAIL("缩略图")
}

private val dlnaProfile = Regex("(?:^|[:;])DLNA\\.ORG_PN=([^;:]*)", RegexOption.IGNORE_CASE)
val Resource.isJpeg: Boolean get() = mime.substringBefore(';').trim().equals("image/jpeg", true)
    || mime.substringBefore(';').trim().equals("image/jpg", true)
val Resource.jpegSize: JpegSize? get() {
    if (!isJpeg) return null
    return when (dlnaProfile.find(profile)?.groupValues?.get(1)?.uppercase()) {
        "JPEG_ORG", "JPEG_ORIGINAL" -> JpegSize.ORIGINAL
        "JPEG_TN" -> JpegSize.THUMBNAIL
        "JPEG_SM" -> JpegSize.SMALL
        "JPEG_LRG" -> JpegSize.LARGE
        null -> if (profile.isNotBlank()) JpegSize.ORIGINAL else null
        else -> null
    }
}
val Resource.pixelCount: Long get() = resolution.lowercase().split('x').mapNotNull(String::toLongOrNull)
    .let { if (it.size == 2 && it.all { n -> n > 0 && n <= 100000 }) it[0] * it[1] else 0 }

fun CameraFile.jpegResource(size: JpegSize = JpegSize.MAXIMUM): Resource? {
    val jpeg = resources.filter { it.isJpeg }
    val candidates = if (size == JpegSize.MAXIMUM) jpeg else jpeg.filter { it.jpegSize == size }
    // Sony's unprofiled JPEG is the original. Its resolution/size attributes can be absent.
    return candidates.maxWithOrNull(compareBy<Resource> { if (it.jpegSize == JpegSize.ORIGINAL) 1 else 0 }
        .thenBy { it.pixelCount }.thenBy { it.size })
}
fun CameraFile.availableJpegSizes(): List<JpegSize> = JpegSize.entries.filter { jpegResource(it) != null }
fun CameraFile.jpegThumbnail(): Resource? = jpegResource(JpegSize.THUMBNAIL)
    ?: resources.filter { it.isJpeg }.minWithOrNull(compareBy<Resource> { if (it.pixelCount > 0) it.pixelCount else Long.MAX_VALUE }
        .thenBy { if (it.size > 0) it.size else Long.MAX_VALUE })
