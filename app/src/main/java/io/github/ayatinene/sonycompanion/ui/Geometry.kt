package io.github.ayatinene.sonycompanion.ui

/** A rectangle in the same unit as its inputs (dp for layout, px for canvas drawing). */
data class FitRect(val left: Float, val top: Float, val width: Float, val height: Float) {
    val right: Float get() = left + width
    val bottom: Float get() = top + height
}

/**
 * Largest rectangle with [contentAspect] (width / height) that fits inside the container, centered, so the
 * live view is never cropped or stretched and the leftover space stays black.
 */
fun containRect(containerWidth: Float, containerHeight: Float, contentAspect: Float): FitRect {
    require(contentAspect > 0f) { "contentAspect must be positive" }
    if (containerWidth <= 0f || containerHeight <= 0f) return FitRect(0f, 0f, 0f, 0f)
    val containerAspect = containerWidth / containerHeight
    return if (containerAspect > contentAspect) {
        val height = containerHeight
        val width = height * contentAspect
        FitRect((containerWidth - width) / 2f, 0f, width, height)
    } else {
        val width = containerWidth
        val height = width / contentAspect
        FitRect(0f, (containerHeight - height) / 2f, width, height)
    }
}

/** Thirds lines strictly inside [rect] so the grid follows the image, not the viewport. */
fun gridLines(rect: FitRect): Pair<List<Float>, List<Float>> =
    listOf(rect.left + rect.width / 3f, rect.left + rect.width * 2f / 3f) to
        listOf(rect.top + rect.height / 3f, rect.top + rect.height * 2f / 3f)
