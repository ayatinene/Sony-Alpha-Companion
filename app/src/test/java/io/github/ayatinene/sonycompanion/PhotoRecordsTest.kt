package io.github.ayatinene.sonycompanion

import io.github.ayatinene.sonycompanion.data.LocalPhoto
import io.github.ayatinene.sonycompanion.data.PhotoRecords
import io.github.ayatinene.sonycompanion.data.matchesSource
import org.junit.Assert.*
import org.junit.Test

class PhotoRecordsTest {
    @Test fun legacyRecordWithoutSourceBytesDefaultsToTheOnDiskSize() {
        val legacy = """[{"uri":"content://media/1","name":"DSC00123.JPG","bytes":1234,"added":5,"origin":"遥控拍摄","source":"scope|parent|name|id|date|variant"}]"""
        val photo = PhotoRecords.decode(legacy).single()
        assertEquals("content://media/1", photo.uri)
        assertEquals(1234L, photo.bytes)
        assertEquals("an old record keeps dedup working by defaulting to its stored size", 1234L, photo.sourceBytes)
    }

    @Test fun sourceBytesSurviveARoundTripAfterAGpsEdit() {
        val original = LocalPhoto("content://media/2", "Remote_1.JPG", 1300, 5, "遥控拍摄", "source",
            sourceBytes = 1234)
        val decoded = PhotoRecords.decode(PhotoRecords.encode(listOf(original))).single()
        assertEquals("bytes is the on-disk size", 1300L, decoded.bytes)
        assertEquals("sourceBytes keeps the received size for dedup", 1234L, decoded.sourceBytes)
        assertEquals(original.pairKey, decoded.pairKey)
        assertEquals(original.sourceId, decoded.sourceId)
    }

    @Test fun corruptEntriesAreSkippedWithoutLosingTheRest() {
        val mixed = """[{"name":"missing uri"},{"uri":"content://media/3","name":"B.JPG","bytes":7,"added":8,"origin":"x","source":"s"}]"""
        val photos = PhotoRecords.decode(mixed)
        assertEquals(1, photos.size)
        assertEquals("content://media/3", photos.single().uri)
    }

    @Test fun unreadableJsonYieldsAnEmptyList() {
        assertTrue(PhotoRecords.decode("not json").isEmpty())
        assertTrue(PhotoRecords.decode("[]").isEmpty())
    }

    @Test fun dedupMatchesTheReceivedSizeNotThePostGpsOnDiskSize() {
        val geotagged = LocalPhoto("content://media/4", "Remote_2.JPG", 1300, 1, "遥控拍摄", "src", sourceBytes = 1234)
        assertTrue("the received size still matches after a GPS edit", matchesSource(geotagged, "src", 1234))
        assertFalse("the grown on-disk size must not be used for dedup", matchesSource(geotagged, "src", 1300))
        assertFalse(matchesSource(geotagged, "another-source", 1234))
        assertTrue("an unknown expected size accepts any entry for the source", matchesSource(geotagged, "src", 0))

        val legacy = LocalPhoto("content://media/5", "DSC1.JPG", 1234, 1, "遥控拍摄", "src")
        assertTrue("old records default sourceBytes to their on-disk size", matchesSource(legacy, "src", 1234))
    }
}
