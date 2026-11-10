package io.github.ayatinene.sonycompanion.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream
import java.security.MessageDigest

/** Temporary previews never enter MediaStore or the import history. */
class PreviewCache(private val directory: File, private val limit: Long = 160L*1024*1024,
    private val perFileLimit: Long = 64L*1024*1024) {
    private val gate=Mutex()
    suspend fun obtain(key: String, download: suspend (OutputStream)->Unit): File = withContext(Dispatchers.IO) { gate.withLock {
        directory.mkdirs()
        val name=MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
        val target=File(directory,"$name.jpg")
        if(target.exists() && target.length()>0) { target.setLastModified(System.currentTimeMillis()); return@withLock target }
        val temp=File(directory,"$name.part")
        try {
            temp.outputStream().use { output ->
                var count=0L
                val bounded=object: OutputStream() {
                    override fun write(value: Int) { check(++count<=perFileLimit) { "预览超过缓存大小限制" }; output.write(value) }
                    override fun write(bytes: ByteArray, off: Int, len: Int) {
                        count+=len; check(count<=perFileLimit) { "预览超过缓存大小限制" }; output.write(bytes,off,len)
                    }
                }
                download(bounded)
            }
            check(temp.length()>0) { "相机返回空预览" }
            check(temp.renameTo(target)) { "无法保存临时预览" }
            var total=directory.listFiles()?.sumOf { it.length() } ?: 0
            directory.listFiles()?.filter { it!=target }?.sortedBy { it.lastModified() }?.forEach {
                if(total>limit || it.extension=="part") { val bytes=it.length(); if(it.delete())total-=bytes }
            }
            target
        } finally { temp.delete() }
    } }
}
