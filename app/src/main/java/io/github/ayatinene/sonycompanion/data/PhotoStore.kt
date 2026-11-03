package io.github.ayatinene.sonycompanion.data

import android.content.*
import android.graphics.*
import android.net.Uri
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.*
import org.json.*
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import kotlin.coroutines.coroutineContext

/**
 * Pure JSON codec for the import records. Records written before GPS support have no `sourceBytes`
 * field; they stay readable because the original byte count defaults to the stored on-disk size, so
 * dedup keeps matching the camera's expected size after the upgrade.
 */
object PhotoRecords {
    fun decode(json: String): List<LocalPhoto> = runCatching {
        val array = JSONArray(json)
        (0 until array.length()).mapNotNull { i -> runCatching {
            val r = array.getJSONObject(i)
            val bytes = r.getLong("bytes")
            LocalPhoto(r.getString("uri"), r.getString("name"), bytes, r.getLong("added"),
                r.getString("origin"), r.getString("source"), sourceBytes = r.optLong("sourceBytes", bytes))
        }.getOrNull() }
    }.getOrDefault(emptyList())

    fun encode(records: List<LocalPhoto>): String {
        val array = JSONArray()
        records.forEach { p -> array.put(JSONObject().put("uri", p.uri).put("name", p.name).put("bytes", p.bytes)
            .put("added", p.added).put("origin", p.origin).put("source", p.sourceId)
            .put("pair", p.pairKey).put("sourceBytes", p.sourceBytes)) }
        return array.toString()
    }
}

class PhotoStore(private val context: Context) {
    private val preferences = context.getSharedPreferences("photos",Context.MODE_PRIVATE)
    private val resolver = context.contentResolver
    init {
        // A killed transfer never appears as a complete photo on the next launch.
        preferences.getStringSet("pending",emptySet())?.forEach { uri -> runCatching { resolver.delete(Uri.parse(uri),null,null) } }
        preferences.edit().remove("pending").apply()
    }
    @Synchronized fun list(): List<LocalPhoto> =
        PhotoRecords.decode(preferences.getString("records","[]") ?: "[]").sortedByDescending { it.added }
    private fun writeRecords(records: List<LocalPhoto>) {
        check(preferences.edit().putString("records",PhotoRecords.encode(records)).commit()) { "无法保存导入记录" }
    }
    @Synchronized private fun save(photo: LocalPhoto) = writeRecords((listOf(photo)+list()).take(10000))
    @Synchronized private fun pending(uri: Uri, add: Boolean) {
        val items = preferences.getStringSet("pending",emptySet()).orEmpty().toMutableSet()
        if(add) items += uri.toString() else items -= uri.toString()
        preferences.edit().putStringSet("pending",items).commit()
    }
    /**
     * Streams one camera file into MediaStore. The received byte count is validated against [expected]
     * first, then the phone copy's GPS block is written to a verified copy and copied back before the
     * row is published. [canWriteGeotag] is re-checked before staging and before the write-back so a
     * mid-run opt-out or permission revoke keeps the original. [sourceBytes] keeps the received size so
     * dedup stays stable across a GPS edit.
     */
    suspend fun import(name: String, raw: Boolean, expected: Long, source: String, origin: String,
        geotag: GeoSnapshot? = null, canWriteGeotag: () -> Boolean = { true },
        checkActive: (() -> Unit)? = null, log: (String) -> Unit = {},
        transfer: suspend (OutputStream) -> Unit): LocalPhoto = withContext(Dispatchers.IO) {
        val coroutineCtx = coroutineContext
        val check: () -> Unit = checkActive ?: { coroutineCtx.ensureActive() }
        val safeName = name.substringAfterLast('/').substringAfterLast('\\').replace(Regex("[\\x00-\\x1f]"),"_")
            .take(180).ifBlank { "IMG_${UUID.randomUUID()}.${if(raw) "ARW" else "JPG"}" }
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME,safeName)
            put(MediaStore.Images.Media.MIME_TYPE,if(raw) "image/x-sony-arw" else "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH,"DCIM/AlphaCompanion")
            put(MediaStore.Images.Media.IS_PENDING,1)
        }
        val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values)) { "无法创建照片文件" }
        pending(uri,true)
        try {
            var total = 0L
            resolver.openOutputStream(uri,"w")!!.use { target ->
                val counted = object: OutputStream() {
                    override fun write(value: Int) { ensureActive(); target.write(value); total++ }
                    override fun write(bytes: ByteArray, offset: Int, length: Int) { ensureActive(); target.write(bytes,offset,length); total += length }
                    override fun flush() = target.flush()
                }
                transfer(counted); counted.flush()
            }
            // Length is validated on the received data, never on the post-GPS size.
            if(total<=0 || (expected>0 && total!=expected)) throw java.io.EOFException("文件长度不符：$total / $expected；已移除未完成文件")
            if(geotag!=null && !raw) {
                when(writeGeotag(uri,geotag,canWriteGeotag,check) { log(it) }) {
                    GeotagResult.Applied -> {}
                    GeotagResult.KeepOriginal -> {}
                    // A failed write-back leaves the pending row untrustworthy; never publish it.
                    GeotagResult.Corrupt -> throw IOException("写入 GPS 后文件可能损坏，已回滚")
                }
            }
            check()
            val actual = fileSize(uri)?.takeIf { it>0 } ?: total
            val photo = LocalPhoto(uri.toString(),safeName,actual,System.currentTimeMillis(),origin,source,sourceBytes=total)
            resolver.update(uri,ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING,0) },null,null)
            save(photo); pending(uri,false)
            photo
        } catch(e: Exception) {
            resolver.delete(uri,null,null); pending(uri,false); throw e
        }
    }
    private enum class GeotagResult { Applied, KeepOriginal, Corrupt }
    /**
     * Backs up the received original, writes GPS EXIF onto a cache copy, verifies it, then copies it
     * back. A failure before or during the write-back keeps the original bytes; only a failed restore
     * rolls the pending row back. GPS attributes are the only change (DateTimeOriginal is preserved)
     * and the image is streamed, never held whole in memory. Cancellation is never swallowed.
     */
    private fun writeGeotag(uri: Uri, snapshot: GeoSnapshot, canWrite: () -> Boolean,
        check: () -> Unit, log: (String)->Unit): GeotagResult {
        if(!canWrite()) return keepOriginal(log,"已关闭或无权限")
        val backup = createTemp() ?: return keepOriginal(log,"无法创建临时文件")
        val work = createTemp() ?: run { runCatching { backup.delete() }; return keepOriginal(log,"无法创建临时文件") }
        try {
            check()
            // Stage 1 only reads the pending file, so any failure leaves the received original intact.
            val staged = try {
                copyUriToFile(uri,backup,check)
                copyFile(backup,work,check)
                val exif = ExifInterface(work.absolutePath)
                exif.setLatLong(snapshot.latitude,snapshot.longitude)
                exif.setAttribute(ExifInterface.TAG_GPS_DATESTAMP,GeoExif.gpsDate(snapshot.fixTimeMillis))
                exif.setAttribute(ExifInterface.TAG_GPS_TIMESTAMP,GeoExif.gpsTime(snapshot.fixTimeMillis))
                exif.setAttribute(ExifInterface.TAG_GPS_H_POSITIONING_ERROR,snapshot.accuracyMeters.toString())
                exif.saveAttributes()
                geotagVerified(work,snapshot)
            } catch(e: CancellationException) { throw e }
            catch(e: Exception) { log("GPS EXIF：${e.javaClass.simpleName}"); false }
            if(!staged) return keepOriginal(log,"校验失败")
            check()
            // Re-check right before the write-back: disabling or revoking mid-run keeps the original.
            if(!canWrite()) return keepOriginal(log,"已关闭或无权限")
            try {
                copyFileToUri(work,uri,check)
                return GeotagResult.Applied
            } catch(e: CancellationException) {
                // Partial write-back cancelled: the caller deletes the pending row and publishes nothing.
                throw e
            } catch(e: Exception) {
                log("GPS EXIF 回写：${e.javaClass.simpleName}")
                return if(runCatching { copyFileToUri(backup,uri) {} }.isSuccess) GeotagResult.KeepOriginal
                    else GeotagResult.Corrupt
            }
        } finally {
            runCatching { backup.delete() }
            runCatching { work.delete() }
        }
    }
    private fun keepOriginal(log: (String)->Unit, reason: String): GeotagResult {
        log("GPS 写入失败，已保留原图：$reason")
        return GeotagResult.KeepOriginal
    }
    private fun createTemp(): File? = runCatching { File.createTempFile("geotag-",".jpg",context.cacheDir) }.getOrNull()
    private fun copyUriToFile(uri: Uri, file: File, check: () -> Unit) {
        resolver.openInputStream(uri)!!.use { input -> file.outputStream().use { out -> copyStream(input,out,check) } }
    }
    private fun copyFile(from: File, to: File, check: () -> Unit) {
        from.inputStream().use { input -> to.outputStream().use { out -> copyStream(input,out,check) } }
    }
    private fun copyFileToUri(file: File, uri: Uri, check: () -> Unit) {
        resolver.openOutputStream(uri,"w")!!.use { out -> file.inputStream().use { input -> copyStream(input,out,check) } }
    }
    private fun copyStream(input: InputStream, output: OutputStream, check: () -> Unit) {
        val buffer = ByteArray(64*1024)
        while(true) {
            check()
            val n = input.read(buffer)
            if(n<0) break
            if(n==0) continue
            output.write(buffer,0,n)
        }
    }
    private fun geotagVerified(file: File, snapshot: GeoSnapshot): Boolean = runCatching {
        val exif = ExifInterface(file.absolutePath)
        val latLong = FloatArray(2)
        if(!exif.getLatLong(latLong)) return@runCatching false
        if(kotlin.math.abs(latLong[0]-snapshot.latitude)>0.01 || kotlin.math.abs(latLong[1]-snapshot.longitude)>0.01) return@runCatching false
        val hpe = exif.getAttributeDouble(ExifInterface.TAG_GPS_H_POSITIONING_ERROR,-1.0)
        if(hpe<0.0 || kotlin.math.abs(hpe-snapshot.accuracyMeters)>0.5) return@runCatching false
        val options = BitmapFactory.Options().apply { inJustDecodeBounds=true }
        BitmapFactory.decodeFile(file.absolutePath,options)
        options.outWidth>0 && options.outHeight>0
    }.getOrDefault(false)
    private fun fileSize(uri: Uri): Long? = runCatching { resolver.openFileDescriptor(uri,"r")?.use { it.statSize } }.getOrNull()
    fun metadata(photo: LocalPhoto): String = resolver.openInputStream(Uri.parse(photo.uri))!!.use { stream ->
        val exif = ExifInterface(stream)
        listOf("文件" to photo.name, "大小" to "${photo.bytes} bytes", "来源" to photo.origin,
            "型号" to exif.getAttribute(ExifInterface.TAG_MODEL),
            "镜头" to exif.getAttribute(ExifInterface.TAG_LENS_MODEL),
            "尺寸" to "${exif.getAttribute(ExifInterface.TAG_IMAGE_WIDTH) ?: "?"} × ${exif.getAttribute(ExifInterface.TAG_IMAGE_LENGTH) ?: "?"}",
            "拍摄时间" to exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL),
            "ISO" to exif.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY),
            "快门" to exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME),
            "光圈" to exif.getAttribute(ExifInterface.TAG_F_NUMBER),
            "焦距" to exif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH))
            .joinToString("\n") { (key,value)-> "$key：${value ?: "未提供"}" }
    }
    suspend fun preview(photo: LocalPhoto): Bitmap? = withContext(Dispatchers.IO) {
        runCatching { resolver.loadThumbnail(Uri.parse(photo.uri),android.util.Size(400,400),null) }.getOrNull()
            ?: runCatching { resolver.openInputStream(Uri.parse(photo.uri))!!.use { ExifInterface(it).thumbnailBitmap } }.getOrNull()
    }
    suspend fun fullPreview(photo: LocalPhoto): Bitmap? = withContext(Dispatchers.IO) {
        val display=if(photo.name.endsWith(".arw",true)) list().firstOrNull {
            it.pairKey==photo.pairKey && (it.name.endsWith(".jpg",true) || it.name.endsWith(".jpeg",true)) && exists(it)
        } ?: photo else photo
        if(display.name.endsWith(".arw",true)) {
            runCatching { resolver.openInputStream(Uri.parse(display.uri))?.use { ExifInterface(it).thumbnailBitmap } }.getOrNull()
                ?: preview(display)
        } else PreviewBitmap.decode { requireNotNull(resolver.openInputStream(Uri.parse(display.uri))) { "文件已移除" } }
    }
    @Synchronized fun deleteFile(photo: LocalPhoto) {
        resolver.delete(Uri.parse(photo.uri),null,null)
        removeRecord(photo)
    }
    @Synchronized fun removeRecord(photo: LocalPhoto) = writeRecords(list().filter { it.uri!=photo.uri })
    @Synchronized fun pruneMissing(): List<LocalPhoto> {
        val records=list()
        val valid=records.filter(::exists)
        if(valid.size!=records.size) writeRecords(valid)
        return valid
    }
    fun exists(photo: LocalPhoto) = runCatching {
        val uri=Uri.parse(photo.uri)
        val columns=if(android.os.Build.VERSION.SDK_INT>=30) arrayOf(MediaStore.Images.Media.IS_TRASHED) else arrayOf(MediaStore.Images.Media._ID)
        val present=resolver.query(uri,columns,null,null,null)?.use { cursor ->
            cursor.moveToFirst() && (android.os.Build.VERSION.SDK_INT<30 || cursor.getInt(0)==0)
        } ?: false
        present && (resolver.openFileDescriptor(uri,"r")?.use { it.statSize<0 || it.statSize==photo.bytes } ?: false)
    }.getOrDefault(false)
}
