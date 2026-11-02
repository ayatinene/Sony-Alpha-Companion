package io.github.ayatinene.sonycompanion

import io.github.ayatinene.sonycompanion.data.CameraFile
import io.github.ayatinene.sonycompanion.data.CameraMode
import io.github.ayatinene.sonycompanion.data.ScanPhase
import io.github.ayatinene.sonycompanion.data.ScanState
import io.github.ayatinene.sonycompanion.ui.continueHistoryEnabled
import io.github.ayatinene.sonycompanion.ui.downloadNewFilesEnabled
import io.github.ayatinene.sonycompanion.ui.galleryEmptyState
import io.github.ayatinene.sonycompanion.ui.galleryFiltered
import io.github.ayatinene.sonycompanion.ui.galleryReadAllowed
import io.github.ayatinene.sonycompanion.ui.gallerySorted
import io.github.ayatinene.sonycompanion.ui.hiddenSelectedCount
import io.github.ayatinene.sonycompanion.ui.scanCountText
import io.github.ayatinene.sonycompanion.ui.scanHeadline
import io.github.ayatinene.sonycompanion.ui.selectAllIds
import io.github.ayatinene.sonycompanion.ui.visibleSelected
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GalleryModelTest {
    private fun file(id: String, raw: Boolean = false, date: String = "", container: Boolean = false) =
        CameraFile(id, id, date = date, raw = raw, container = container)

    @Test fun scanningWithoutATotalReportsCheckedItemsWithoutInventingOne() {
        assertEquals("已检查 7 项，发现 3 个文件", scanCountText(ScanState(phase = ScanPhase.Scanning, scanned = 7, total = null, found = 3)))
    }

    @Test fun scanningWithATotalReportsTheRatio() {
        assertEquals("已检查 7/20 项，发现 3 个文件", scanCountText(ScanState(phase = ScanPhase.Scanning, scanned = 7, total = 20, found = 3)))
    }

    @Test fun doneKeepsTheFinalCounts() {
        assertEquals("已检查 12 项，发现 5 个文件", scanCountText(ScanState(phase = ScanPhase.Done, scanned = 12, total = null, found = 5)))
    }

    @Test fun idleHasNoScanCopy() {
        assertNull(scanCountText(ScanState(phase = ScanPhase.Idle)))
        assertNull(scanHeadline(ScanState(phase = ScanPhase.Idle), transferring = false))
    }

    @Test fun pausedCopyDistinguishesATransferYieldFromAUserPause() {
        val paused = ScanState(phase = ScanPhase.Scanning, paused = true)
        assertEquals("下载进行中，查找已暂停", scanHeadline(paused, transferring = true))
        assertEquals("查找已暂停", scanHeadline(paused, transferring = false))
        assertEquals("正在扫描相机文件", scanHeadline(ScanState(phase = ScanPhase.Scanning), transferring = false))
    }

    @Test fun emptyCopyFollowsTheScanPhaseInsteadOfClaimingThereAreNoPhotos() {
        assertEquals("正在查找照片", galleryEmptyState(CameraMode.COMPUTER, ScanState(phase = ScanPhase.Scanning)).title)
        assertEquals("没有照片", galleryEmptyState(CameraMode.COMPUTER, ScanState(phase = ScanPhase.Done)).title)
        assertEquals("没有文件", galleryEmptyState(CameraMode.JPEG, ScanState(phase = ScanPhase.Idle)).title)
        assertEquals("查找失败", galleryEmptyState(CameraMode.COMPUTER, ScanState(phase = ScanPhase.Failed)).title)
    }

    @Test fun filterKeepsContainersAndMatchesTheSelectedKind() {
        val files = listOf(file("dir", container = true), file("a", raw = true), file("b"))
        assertEquals(listOf("dir", "a"), galleryFiltered(files, "ARW").map { it.id })
        assertEquals(listOf("dir", "b"), galleryFiltered(files, "JPEG").map { it.id })
        assertEquals(3, galleryFiltered(files, "全部").size)
    }

    @Test fun sortKeepsFoldersFirstAndHonoursDirection() {
        val files = listOf(file("dir", container = true), file("a", date = "2026-01-01"), file("b", date = "2026-02-01"))
        assertEquals(listOf("dir", "b", "a"), gallerySorted(files, descending = true).map { it.id })
        assertEquals(listOf("dir", "a", "b"), gallerySorted(files, descending = false).map { it.id })
    }

    @Test fun hiddenSelectionIsCountedButNeverActedOn() {
        val selected = setOf("a", "b", "c")
        val visible = setOf("a", "c")
        assertEquals(setOf("a", "c"), visibleSelected(selected, visible))
        assertEquals(1, hiddenSelectedCount(selected, visible))
    }

    @Test fun selectAllOnlyCoversDiscoveredNonContainerFiles() {
        assertEquals(setOf("a", "b"), selectAllIds(listOf(file("a"), file("b"), file("dir", container = true))))
    }

    @Test fun networkReadsNeedALiveNonFailedSessionWithoutATransfer() {
        assertFalse(galleryReadAllowed(connected = false, scan = ScanState(phase = ScanPhase.Done), transferring = false))
        assertFalse(galleryReadAllowed(connected = true, scan = ScanState(phase = ScanPhase.Failed), transferring = false))
        assertFalse(galleryReadAllowed(connected = true, scan = ScanState(phase = ScanPhase.Scanning), transferring = true))
        assertTrue(galleryReadAllowed(connected = true, scan = ScanState(phase = ScanPhase.Scanning), transferring = false))
    }

    @Test fun continuingHistoryNeedsDoneForComputerButNotForJpeg() {
        val scanning = ScanState(phase = ScanPhase.Scanning)
        assertFalse(continueHistoryEnabled(connected = true, busy = false, transferring = false, mode = CameraMode.COMPUTER, scan = scanning))
        assertTrue(continueHistoryEnabled(connected = true, busy = false, transferring = false, mode = CameraMode.JPEG, scan = scanning))
        assertTrue(continueHistoryEnabled(connected = true, busy = false, transferring = false, mode = CameraMode.COMPUTER, scan = ScanState(phase = ScanPhase.Done)))
        assertFalse(continueHistoryEnabled(connected = true, busy = true, transferring = false, mode = CameraMode.JPEG, scan = scanning))
    }

    @Test fun newFilesCanBeQueuedWhileScanningButNotAfterFailure() {
        assertTrue(downloadNewFilesEnabled(connected = true, scan = ScanState(phase = ScanPhase.Scanning)))
        assertFalse(downloadNewFilesEnabled(connected = true, scan = ScanState(phase = ScanPhase.Failed)))
        assertFalse(downloadNewFilesEnabled(connected = false, scan = ScanState(phase = ScanPhase.Done)))
    }
}
