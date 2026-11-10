package io.github.ayatinene.sonycompanion

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ayatinene.sonycompanion.data.PhotoStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.EOFException
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CancellationException

/**
 * Verifies the real Android MediaStore pending row and PhotoStore rollback/launch recovery.
 * Uses unique test filenames and always cleans up any row it inserted.
 */
@RunWith(AndroidJUnit4::class)
class PhotoStorePendingCleanupTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val resolver get()=context.contentResolver
    private val prefs get()=context.getSharedPreferences("photos",Context.MODE_PRIVATE)

    private fun pendingUris(): Set<String> =
        prefs.getStringSet("pending",emptySet()).orEmpty().toSet()

    private fun pendingRow(uri: Uri): Int? =
        resolver.query(MediaStore.setIncludePending(uri),
            arrayOf(MediaStore.Images.Media.IS_PENDING),null,null,null)?.use {
                if(it.moveToFirst()) it.getInt(0) else null
            }

    private fun uniqueName()="ROLLBACK_${UUID.randomUUID()}.JPG"

    private fun assertClean(uri: Uri, before: Set<String>, beforeRecords: Set<String>, store: PhotoStore) {
        assertNull("even hidden IS_PENDING=1 rows must have been deleted",pendingRow(uri))
        assertEquals("pending URI preferences must be restored",before,pendingUris())
        assertEquals("no LocalPhoto record may be published",beforeRecords,store.list().map { it.uri }.toSet())
    }

    @Test fun cancellingAfterWritingBytesDeletesItsPendingRow() {
        val store=PhotoStore(context)
        val before=pendingUris()
        val beforeRecords=store.list().map { it.uri }.toSet()
        var uri: Uri?=null
        try {
            val failure=runCatching {
                runBlocking {
                    store.import(uniqueName(),false,0,"pending-cleanup-test","test") { output ->
                        uri=Uri.parse((pendingUris()-before).single())
                        assertEquals("the row must still be private while being written",1,pendingRow(requireNotNull(uri)))
                        output.write(ByteArray(4096) { 42 })
                        throw CancellationException("cancel before publish")
                    }
                }
            }.exceptionOrNull()
            assertTrue("cancel must propagate to the transfer",failure is CancellationException)
            assertClean(requireNotNull(uri),before,beforeRecords,store)
        } finally { uri?.let { runCatching { resolver.delete(it,null,null) } } }
    }

    @Test fun truncatedFileIsNeverPublishedAndPendingRowIsDeleted() {
        val store=PhotoStore(context)
        val before=pendingUris()
        val beforeRecords=store.list().map { it.uri }.toSet()
        var uri: Uri?=null
        try {
            val failure=runCatching {
                runBlocking {
                    store.import(uniqueName(),false,2048,"pending-cleanup-test","test") { output ->
                        uri=Uri.parse((pendingUris()-before).single())
                        output.write(ByteArray(512))
                    }
                }
            }.exceptionOrNull()
            assertTrue("a declared-length mismatch must fail",failure is EOFException)
            assertClean(requireNotNull(uri),before,beforeRecords,store)
        } finally { uri?.let { runCatching { resolver.delete(it,null,null) } } }
    }

    @Test fun thrownNetworkErrorAlsoRollsBackThePendingRow() {
        val store=PhotoStore(context)
        val before=pendingUris()
        val beforeRecords=store.list().map { it.uri }.toSet()
        var uri: Uri?=null
        try {
            val failure=runCatching {
                runBlocking {
                    store.import(uniqueName(),false,0,"pending-cleanup-test","test") { output ->
                        uri=Uri.parse((pendingUris()-before).single())
                        output.write(ByteArray(256))
                        throw IOException("camera disconnected")
                    }
                }
            }.exceptionOrNull()
            assertTrue(failure is IOException)
            assertClean(requireNotNull(uri),before,beforeRecords,store)
        } finally { uri?.let { runCatching { resolver.delete(it,null,null) } } }
    }

    @Test fun aNewStoreDeletesRowsLeftPendingByAnInterruptedPriorProcess() {
        val before=pendingUris()
        val name=uniqueName()
        val uri=requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME,name)
            put(MediaStore.Images.Media.MIME_TYPE,"image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH,"DCIM/AlphaCompanion")
            put(MediaStore.Images.Media.IS_PENDING,1)
        }))
        try {
            assertEquals(1,pendingRow(uri))
            assertTrue(prefs.edit().putStringSet("pending",before+uri.toString()).commit())
            // PhotoStore constructor runs the recovery path exactly as on app startup.
            PhotoStore(context)
            assertNull("an orphaned pending row must not survive app restart",pendingRow(uri))
            assertEquals(before,pendingUris())
        } finally {
            runCatching { resolver.delete(uri,null,null) }
            prefs.edit().putStringSet("pending",before).commit()
        }
    }
}
