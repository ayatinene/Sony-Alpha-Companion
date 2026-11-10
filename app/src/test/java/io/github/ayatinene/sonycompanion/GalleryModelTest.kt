package io.github.ayatinene.sonycompanion

import io.github.ayatinene.sonycompanion.data.CameraFile
import io.github.ayatinene.sonycompanion.data.CameraMode
import io.github.ayatinene.sonycompanion.data.Resource
import io.github.ayatinene.sonycompanion.data.ScanPhase
import io.github.ayatinene.sonycompanion.data.ScanState
import io.github.ayatinene.sonycompanion.data.captureTimeMillis
import io.github.ayatinene.sonycompanion.ui.GallerySort
import io.github.ayatinene.sonycompanion.ui.continueHistoryEnabled
import io.github.ayatinene.sonycompanion.ui.downloadNewFilesEnabled
import io.github.ayatinene.sonycompanion.ui.effectiveFileSize
import io.github.ayatinene.sonycompanion.ui.folderDateMillis
import io.github.ayatinene.sonycompanion.ui.galleryEmptyState
import io.github.ayatinene.sonycompanion.ui.galleryFiltered
import io.github.ayatinene.sonycompanion.ui.galleryReadAllowed
import io.github.ayatinene.sonycompanion.ui.gallerySorted
import io.github.ayatinene.sonycompanion.ui.galleryTimeText
import io.github.ayatinene.sonycompanion.ui.hiddenSelectedCount
import io.github.ayatinene.sonycompanion.ui.scanCountText
import io.github.ayatinene.sonycompanion.ui.scanHeadline
import io.github.ayatinene.sonycompanion.ui.selectAllIds
import io.github.ayatinene.sonycompanion.ui.visibleSelected
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GalleryModelTest {
    private fun file(id: String, raw: Boolean = false, date: String = "", container: Boolean = false) =
        CameraFile(id, id, date = date, raw = raw, container = container)

    private fun named(name: String, date: String = "") = CameraFile(name, name, date = date)

    /** The exact `Date.toString` shape a RAW PTP capture reports, built from a real instant. */
    private fun rawDate(iso: String) = java.util.Date(Instant.parse(iso).toEpochMilli()).toString()

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

    @Test fun timeSortsUseRealCaptureMillisForRawDatesAcrossMonthsAndYears() {
        // Date.toString starts with the weekday, so a lexicographic sort would order these wrongly.
        val older = file("older", raw = true, date = rawDate("2025-12-31T10:00:00Z"))
        val newer = file("newer", raw = true, date = rawDate("2026-01-02T10:00:00Z"))
        val newest = file("newest", raw = true, date = rawDate("2026-02-01T10:00:00Z"))
        val files = listOf(older, newest, newer)
        assertEquals(listOf("newest", "newer", "older"), gallerySorted(files, GallerySort.Newest).map { it.id })
        assertEquals(listOf("older", "newer", "newest"), gallerySorted(files, GallerySort.Oldest).map { it.id })
    }

    @Test fun isoAndRawDatesAgreeOnTheSameInstant() {
        val iso = file("iso", date = "2026-01-02T10:30:00Z")
        val raw = file("raw", raw = true, date = rawDate("2026-01-02T10:30:00Z"))
        val earlier = file("earlier", date = "2026-01-02T09:00:00Z")
        // iso and raw share a time, so the natural-name fallback keeps them before the earlier file.
        assertEquals(listOf("iso", "raw", "earlier"), gallerySorted(listOf(iso, raw, earlier), GallerySort.Newest).map { it.id })
    }

    @Test fun missingOrUnreadableDatesGoLastInBothTimeDirections() {
        val dated = file("dated", date = "2026-01-01T00:00:00Z")
        val blank = file("blank", date = "")
        val broken = file("broken", date = "not a date")
        val files = listOf(blank, dated, broken)
        // Known dates lead; unknown files keep the stable ascending name fallback in both directions
        // (only folders follow the selected direction).
        assertEquals(listOf("dated", "blank", "broken"), gallerySorted(files, GallerySort.Newest).map { it.id })
        assertEquals(listOf("dated", "blank", "broken"), gallerySorted(files, GallerySort.Oldest).map { it.id })
    }

    @Test fun onlyFoldersFollowTheDirectionForAnUnknownTime() {
        val b = file("b", container = true)
        val a = file("a", container = true)
        assertEquals(listOf("b", "a"), gallerySorted(listOf(a, b), GallerySort.Newest).map { it.id })
        assertEquals(listOf("a", "b"), gallerySorted(listOf(a, b), GallerySort.Oldest).map { it.id })
        val fileB = file("b")
        val fileA = file("a")
        assertEquals(listOf("a", "b"), gallerySorted(listOf(fileB, fileA), GallerySort.Newest).map { it.id })
        assertEquals(listOf("a", "b"), gallerySorted(listOf(fileB, fileA), GallerySort.Oldest).map { it.id })
    }

    @Test fun sameCaptureTimeFallsBackToNaturalNameAscendingInBothDirections() {
        val later = file("later", date = "2026-01-01T00:00:00Z")
        val earlier = file("earlier", date = "2026-01-01T00:00:00Z")
        val files = listOf(later, earlier)
        assertEquals(listOf("earlier", "later"), gallerySorted(files, GallerySort.Newest).map { it.id })
        assertEquals(listOf("earlier", "later"), gallerySorted(files, GallerySort.Oldest).map { it.id })
    }

    @Test fun identicalNamesAtTheSameTimeFallBackToTheStableId() {
        val two = CameraFile("2", "same", date = "2026-01-01T00:00:00Z")
        val one = CameraFile("1", "same", date = "2026-01-01T00:00:00Z")
        assertEquals(listOf("1", "2"), gallerySorted(listOf(two, one), GallerySort.Newest).map { it.id })
    }

    @Test fun nameSortOrdersByNameAscendingAndDescending() {
        val files = listOf(named("b.jpg"), named("a.jpg"), named("c.jpg"))
        assertEquals(listOf("a.jpg", "b.jpg", "c.jpg"), gallerySorted(files, GallerySort.NameAscending).map { it.name })
        assertEquals(listOf("c.jpg", "b.jpg", "a.jpg"), gallerySorted(files, GallerySort.NameDescending).map { it.name })
    }

    @Test fun naturalSortComparesNumbersByValueWithoutOverflow() {
        val two = named("IMG2")
        val ten = named("IMG10")
        val paddedTen = named("IMG" + "0".repeat(28) + "10")
        val huge = named("IMG" + "1" + "0".repeat(29))
        val huger = named("IMG" + "9".repeat(30))
        val sorted = gallerySorted(listOf(ten, two, huge, huger, paddedTen), GallerySort.NameAscending).map { it.name }
        assertEquals(
            listOf("IMG2", "IMG" + "0".repeat(28) + "10", "IMG10", "IMG" + "1" + "0".repeat(29), "IMG" + "9".repeat(30)),
            sorted
        )
    }

    @Test fun zeroPaddedNumbersCompareEqualAndFallBackToTheOriginalName() {
        val plain = named("IMG7")
        val padded = named("IMG0007")
        assertEquals(listOf("IMG0007", "IMG7"), gallerySorted(listOf(plain, padded), GallerySort.NameAscending).map { it.name })
    }

    @Test fun textSortIgnoresCaseAndFallsBackDeterministically() {
        val upper = named("IMG_1")
        val lower = named("img_2")
        assertEquals(listOf("IMG_1", "img_2"), gallerySorted(listOf(lower, upper), GallerySort.NameAscending).map { it.name })
    }

    @Test fun foldersLeadFilesUnderEverySort() {
        val folders = listOf(file("zdir", container = true), file("adir", container = true))
        val files = listOf(named("a.jpg"), named("z.jpg"))
        GallerySort.entries.forEach { sort ->
            val ids = gallerySorted(folders + files, sort).map { it.id }
            assertEquals("folders first for $sort", setOf("adir", "zdir"), ids.take(2).toSet())
        }
    }

    @Test fun undatedFoldersFollowTheChosenSortKeyInsteadOfAlwaysAscendingByName() {
        val alpha = file("alpha", container = true)
        val bravo = file("bravo", container = true)
        val charlie = file("charlie", container = true)
        val files = listOf(bravo, charlie, alpha)
        // Undated folders have no time, so the time fallback follows the chosen direction (oldest-first
        // is ascending); the default newest-first is descending, matching the old default.
        assertEquals(listOf("charlie", "bravo", "alpha"), gallerySorted(files, GallerySort.Newest).map { it.id })
        assertEquals(listOf("alpha", "bravo", "charlie"), gallerySorted(files, GallerySort.Oldest).map { it.id })
        assertEquals(listOf("alpha", "bravo", "charlie"), gallerySorted(files, GallerySort.NameAscending).map { it.id })
        // Name descending must actually reverse the folders, not leave them ascending.
        assertEquals(listOf("charlie", "bravo", "alpha"), gallerySorted(files, GallerySort.NameDescending).map { it.id })
        // Folders have no real size; both size directions keep a stable natural-name ascending order.
        assertEquals(listOf("alpha", "bravo", "charlie"), gallerySorted(files, GallerySort.SizeAsc).map { it.id })
        assertEquals(listOf("alpha", "bravo", "charlie"), gallerySorted(files, GallerySort.SizeDesc).map { it.id })
    }

    @Test fun dateFolderTitlesDriveTimeSortsWhenTheirDateFieldIsBlank() {
        val jan = CameraFile("jan", "2026-01-15", container = true)
        val feb = CameraFile("feb", "2026-02-10", container = true)
        val mar = CameraFile("mar", "2026-03-20", container = true)
        val files = listOf(jan, mar, feb)
        assertEquals(listOf("mar", "feb", "jan"), gallerySorted(files, GallerySort.Newest).map { it.id })
        assertEquals(listOf("jan", "feb", "mar"), gallerySorted(files, GallerySort.Oldest).map { it.id })
    }

    @Test fun aDateFolderWithItsOwnDateIgnoresTheTitleForTimeSorts() {
        // The titles would sort the other way; the real capture dates must win when present.
        val namedOld = CameraFile("namedOld", "2020-01-01", container = true, date = "2026-12-31T00:00:00Z")
        val namedNew = CameraFile("namedNew", "2026-12-31", container = true, date = "2020-01-01T00:00:00Z")
        assertEquals(listOf("namedOld", "namedNew"), gallerySorted(listOf(namedNew, namedOld), GallerySort.Newest).map { it.id })
        assertEquals(listOf("namedNew", "namedOld"), gallerySorted(listOf(namedNew, namedOld), GallerySort.Oldest).map { it.id })
    }

    @Test fun folderDateFallbackOnlyAcceptsTheConfirmedDateTitleForm() {
        val zone = ZoneOffset.UTC
        assertEquals(captureTimeMillis("2026-10-24", zone), folderDateMillis("2026-10-24", zone))
        // A bare digit run, a camera directory and impossible dates never become a time.
        assertEquals(captureTimeMillis("2026-10-24", zone), folderDateMillis("20261024", zone))
        assertNull(folderDateMillis("100MSDCF", zone))
        assertNull(folderDateMillis("DSC00042.JPG", zone))
        assertNull(folderDateMillis("2026-2-4", zone))
        assertNull(folderDateMillis("2026-13-01", zone))
        assertNull(folderDateMillis("2026-02-30", zone))
        assertNull(folderDateMillis("20260230", zone))
        assertNull(folderDateMillis("20261301", zone))
        assertNull(folderDateMillis("00000000", zone))
    }

    @Test fun sonyCompactDateFoldersSortByActualDate() {
        val early=CameraFile("early","20250102",container=true)
        val late=CameraFile("late","20251105",container=true)
        val mixed=CameraFile("mixed","2025-10-01",container=true)
        assertEquals(listOf("late","mixed","early"),
            gallerySorted(listOf(early,late,mixed),GallerySort.Newest).map { it.id })
        assertEquals(listOf("early","mixed","late"),
            gallerySorted(listOf(early,late,mixed),GallerySort.Oldest).map { it.id })
    }

    @Test fun undatedFoldersThatDoNotLookLikeDatesStayUnknownAndSortLast() {
        val dated = CameraFile("dated", "2026-01-15", container = true)
        val plain = file("plain", container = true)
        val files = listOf(plain, dated)
        assertEquals(listOf("dated", "plain"), gallerySorted(files, GallerySort.Newest).map { it.id })
        assertEquals(listOf("dated", "plain"), gallerySorted(files, GallerySort.Oldest).map { it.id })
    }

    @Test fun sizeSortsPutUnknownLastInBothDirectionsAndTieBreakNaturally() {
        val small = CameraFile("small", "small", size = 100L)
        val big = CameraFile("big", "big", size = 5000L)
        val unknown = CameraFile("unknown", "unknown", size = 0L)
        val files = listOf(unknown, big, small)
        assertEquals(listOf("small", "big", "unknown"), gallerySorted(files, GallerySort.SizeAsc).map { it.id })
        assertEquals(listOf("big", "small", "unknown"), gallerySorted(files, GallerySort.SizeDesc).map { it.id })
    }

    @Test fun sizeSortFallsBackToNaturalNameThenIdForEqualSizes() {
        val b = CameraFile("b", "b", size = 10L)
        val a = CameraFile("a", "a", size = 10L)
        assertEquals(listOf("a", "b"), gallerySorted(listOf(b, a), GallerySort.SizeAsc).map { it.id })
    }

    @Test fun effectiveSizePrefersTheOwnSizeThenTheBestJpegResourceAndNeverGuesses() {
        val own = CameraFile("own", "own", resources = listOf(Resource("u", "image/jpeg", 999L)), size = 50L)
        assertEquals(50L, effectiveFileSize(own))
        val viaResource = CameraFile("r", "r", resources = listOf(Resource("u", "image/jpeg", 777L)))
        assertEquals(777L, effectiveFileSize(viaResource))
        // A non-JPEG resource is never used to guess a size the sort would not see.
        val unknown = CameraFile("u", "u", resources = listOf(Resource("u", "video/mp4", 123L)))
        assertEquals(0L, effectiveFileSize(unknown))
    }

    @Test fun sizeSortUsesTheSameEffectiveSizeAsTheDisplay() {
        val byResource = CameraFile("byResource", "byResource", resources = listOf(Resource("u", "image/jpeg", 900L)))
        val explicit = CameraFile("explicit", "explicit", size = 400L)
        assertEquals(listOf("explicit", "byResource"), gallerySorted(listOf(byResource, explicit), GallerySort.SizeAsc).map { it.id })
    }

    @Test fun galleryTimeTextFormatsKnownCaptureTimesAndOmitsMissingOnes() {
        assertEquals("2026-01-02 10:30", galleryTimeText("2026-01-02T10:30:00Z", ZoneOffset.UTC))
        // A raw Date.toString is written in the device zone, so read it back in that same zone: it must
        // describe the same instant as the explicit ISO form regardless of the host timezone.
        assertEquals(galleryTimeText("2026-01-02T10:30:00Z"), galleryTimeText(rawDate("2026-01-02T10:30:00Z")))
        assertNull(galleryTimeText("", ZoneOffset.UTC))
        assertNull(galleryTimeText("not a date", ZoneOffset.UTC))
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
