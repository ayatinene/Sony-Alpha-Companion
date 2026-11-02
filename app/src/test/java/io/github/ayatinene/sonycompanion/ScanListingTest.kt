package io.github.ayatinene.sonycompanion

import io.github.ayatinene.sonycompanion.data.*
import org.junit.Assert.*
import org.junit.Test

class ScanListingTest {
    @Test fun computerListingsAreOnlyAuthoritativeWhenTheScanIsDone() {
        assertFalse(scanListingComplete(CameraMode.COMPUTER, ScanPhase.Scanning))
        assertFalse(scanListingComplete(CameraMode.COMPUTER, ScanPhase.Idle))
        assertFalse(scanListingComplete(CameraMode.COMPUTER, ScanPhase.Failed))
        assertTrue(scanListingComplete(CameraMode.COMPUTER, ScanPhase.Done))
    }

    @Test fun jpegAndRemoteListingsAreAlwaysAuthoritative() {
        assertTrue(scanListingComplete(CameraMode.JPEG, ScanPhase.Idle))
        assertTrue(scanListingComplete(CameraMode.JPEG, ScanPhase.Scanning))
        assertTrue(scanListingComplete(CameraMode.REMOTE, ScanPhase.Idle))
    }

    @Test fun aStaleFileWithChangedMetadataIsNotMatchedToTheCurrentListing() {
        val current = CameraFile("1", "DSC00001.ARW", size = 100, date = "2026-01-01", parent = "0/1", raw = true, handle = 1)
        // Same id but a different size/date: a stale capture from before a reconnect must not match.
        val stale = current.copy(size = 999, date = "2020-01-01")
        assertNull(matchCurrentFile(stale, listOf(current)))
        assertEquals(current, matchCurrentFile(current, listOf(current)))
    }

    @Test fun anAmbiguousDuplicateMetadataMatchIsNotEnqueued() {
        val current = CameraFile("1", "DSC00001.JPG", size = 100, date = "2026-01-01", parent = "0/1")
        // Two indistinguishable entries: refuse rather than guess.
        assertNull(matchCurrentFile(current, listOf(current, current)))
    }

    @Test fun aFileAbsentFromTheCurrentListingIsRejected() {
        val current = listOf(CameraFile("1", "DSC00001.JPG", size = 100, date = "2026-01-01", parent = "0/1"))
        val removed = CameraFile("2", "DSC00002.JPG", size = 200, date = "2026-01-02", parent = "0/1")
        assertNull("a file no longer in the scan result must not be enqueued", matchCurrentFile(removed, current))
        assertNull(matchCurrentFile(removed, emptyList()))
    }
}
