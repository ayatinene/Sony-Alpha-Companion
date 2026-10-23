package io.github.ayatinene.sonycompanion.data

import android.content.*
import android.graphics.*
import android.net.Uri
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.*
import org.json.*
import java.io.OutputStream
import java.util.UUID

class PhotoStore(private val context: Context) {
    private val preferences = context.getSharedPreferences("photos",Context.MODE_PRIVATE)
    private val resolver = context.contentResolver
    init {
        // A killed transfer never appears as a complete photo on the next launch.
        preferences.getStringSet("pending",emptySet())?.forEach { uri -> runCatching { resolver.delete(Uri.parse(uri),null,null) } }
        preferences.edit().remove("pending").apply()
    }
    @Synchronized fun list(): List<LocalPhoto> {
        val records = runCatching { JSONArray(preferences.getString("records","[]")) }.getOrDefault(JSONArray())
        return (0 until records.length()).mapNotNull { i -> runCatching {
            val r=records.getJSONObject(i)
            LocalPhoto(r.getString("uri"),r.getString("name"),r.getLong("bytes"),r.getLong("added"),r.getString("origin"),r.getString("source"),r.optString("pair",pairKey(r.getString("name"))))
        }.getOrNull() }.sortedByDescending { it.added }
    }
    private fun writeRecords(records: List<LocalPhoto>) {
        val array=JSONArray()
        records.forEach { p -> array.put(JSONObject().put("uri",p.uri).put("name",p.name).put("bytes",p.bytes).put("added",p.added).put("origin",p.origin).put("source",p.sourceId).put("pair",p.pairKey)) }
        check(preferences.edit().putString("records",array.toString()).commit()) { "无法保存导入记录" }
    }
    @Synchronized private fun save(photo: LocalPhoto) = writeRecords((listOf(photo)+list()).take(10000))
    @Synchronized private fun pending(uri: Uri, add: Boolean) {
        val items = preferences.getStringSet("pending",emptySet()).orEmpty().toMutableSet()
        if(add) items += uri.toString() else items -= uri.toString()
        preferences.edit().putStringSet("pending",items).commit()
    }
    suspend fun import(name: String, raw: Boolean, expected: Long, source: String, origin: String,
        transfer: suspend (OutputStream) -> Unit): LocalPhoto = withContext(Dispatchers.IO) {
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
            if(total<=0 || (expected>0 && total!=expected)) throw java.io.EOFException("文件长度不符：$total / $expected；已移除未完成文件")
            val photo = LocalPhoto(uri.toString(),safeName,total,System.currentTimeMillis(),origin,source)
            resolver.update(uri,ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING,0) },null,null)
            save(photo); pending(uri,false)
            photo
        } catch(e: Exception) {
            resolver.delete(uri,null,null); pending(uri,false); throw e
        }
    }
    fun metadata(photo: LocalPhoto): String = resolver.openInputStream(Uri.parse(photo.uri))!!.use { stream ->
        val exif = ExifInterface(stream)
        listOf("文件" to photo.name, "大小" to "${photo.bytes} bytes", "来源" to photo.origin,
            "型号" to exif.getAttribute(ExifInterface.TAG_MODEL),
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
