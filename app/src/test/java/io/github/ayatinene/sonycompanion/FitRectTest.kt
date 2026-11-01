package io.github.ayatinene.sonycompanion

import io.github.ayatinene.sonycompanion.ui.containRect
import io.github.ayatinene.sonycompanion.ui.gridLines
import org.junit.Assert.assertEquals
import org.junit.Test

class FitRectTest {
    @Test fun threeToTwoImageIsPillarboxedInsideAWiderViewport() {
        val rect = containRect(1920f, 1080f, 3f / 2f)
        assertEquals(1620f, rect.width, 0.001f)
        assertEquals(1080f, rect.height, 0.001f)
        assertEquals(150f, rect.left, 0.001f)
        assertEquals(0f, rect.top, 0.001f)
    }

    @Test fun fourToThreeImageFillsWidthAndLeavesVerticalBars() {
        val rect = containRect(800f, 800f, 4f / 3f)
        assertEquals(800f, rect.width, 0.001f)
        assertEquals(600f, rect.height, 0.001f)
        assertEquals(0f, rect.left, 0.001f)
        assertEquals(100f, rect.top, 0.001f)
    }

    @Test fun sixteenToNineImageKeepsCompositionWithoutStretching() {
        val rect = containRect(1000f, 1000f, 16f / 9f)
        assertEquals(1000f, rect.width, 0.001f)
        assertEquals(562.5f, rect.height, 0.001f)
        assertEquals(218.75f, rect.top, 0.001f)
    }

    @Test fun aWiderViewportThanTheImageLimitsByHeight() {
        val rect = containRect(740f, 340f, 3f / 2f)
        assertEquals(510f, rect.width, 0.001f)
        assertEquals(340f, rect.height, 0.001f)
        assertEquals(115f, rect.left, 0.001f)
    }

    @Test fun gridLinesStayStrictlyInsideTheImageRect() {
        val rect = containRect(740f, 340f, 3f / 2f)
        val (xs, ys) = gridLines(rect)
        assertEquals(listOf(rect.left + rect.width / 3f, rect.left + rect.width * 2f / 3f), xs)
        assertEquals(listOf(rect.top + rect.height / 3f, rect.top + rect.height * 2f / 3f), ys)
    }
}
