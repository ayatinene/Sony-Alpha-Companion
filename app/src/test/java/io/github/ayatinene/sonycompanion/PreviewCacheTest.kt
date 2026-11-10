package io.github.ayatinene.sonycompanion

import io.github.ayatinene.sonycompanion.data.PreviewCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PreviewCacheTest {
    @get:Rule val temporary=TemporaryFolder()
    @Test fun cacheAvoidsRepeatedDownloadsAndKeepsCameraSourcesSeparate() = runBlocking {
        val cache=PreviewCache(temporary.newFolder())
        var downloads=0
        val first=cache.obtain("camera-A|photo-1") { downloads++;it.write(byteArrayOf(1,2,3)) }
        assertEquals(first,cache.obtain("camera-A|photo-1") { error("Should use cached bytes") })
        val other=cache.obtain("camera-B|photo-1") { downloads++;it.write(byteArrayOf(4,5,6)) }
        assertNotEquals(first,other);assertEquals(2,downloads)
        assertArrayEquals(byteArrayOf(1,2,3),first.readBytes())
    }
    @Test fun cancellationAndOverSizeNeverLeaveCompletedOrPartialFiles() = runBlocking {
        val folder=temporary.newFolder(); val cache=PreviewCache(folder,perFileLimit=5)
        try { cache.obtain("interrupted") { it.write(byteArrayOf(1));throw CancellationException() };fail() } catch(_:CancellationException) { }
        assertTrue(folder.listFiles()!!.isEmpty())
        try { cache.obtain("oversized") { it.write(ByteArray(6)) };fail() } catch(_:IllegalStateException) { }
        assertTrue(folder.listFiles()!!.isEmpty())
    }
    @Test fun leastRecentlyUsedPreviewsAreEvictedWithinTheBudget() = runBlocking {
        val folder=temporary.newFolder(); val cache=PreviewCache(folder,limit=10,perFileLimit=10)
        val first=cache.obtain("a") { it.write(ByteArray(6)) };first.setLastModified(1)
        val second=cache.obtain("b") { it.write(ByteArray(6)) }
        assertFalse(first.exists()); assertTrue(second.exists()); assertTrue(folder.listFiles()!!.sumOf { it.length() }<=10)
    }
}
