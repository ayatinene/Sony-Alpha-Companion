package io.github.ayatinene.sonycompanion.protocol

import android.net.Network
import com.fimagena.libptp.*
import com.fimagena.libptp.ptpip.PtpIpConnection
import io.github.ayatinene.sonycompanion.data.CameraFile
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.OutputStream
import java.net.InetAddress

/** Standard PTP/IP + Sony's documented-by-reference Send to Computer start pair.
 * Requires an already registered receiver GUID. No claim of A7R2 hardware validation.
 */
class ComputerTransfer(private val network: Network) {
    private var connection: PtpConnection? = null
    private var transport: PtpIpConnection? = null
    private var session: PtpSession? = null
    private val mutex = Mutex()
    suspend fun connect(host: String, guid: String): String = withContext(Dispatchers.IO) {
        val bytes = parseGuid(guid)
        val transport = PtpIpConnection().apply { setSocketFactory(network.socketFactory) }; this@ComputerTransfer.transport=transport
        val c = PtpConnection(transport); connection = c
        try {
            c.connect(PtpIpConnection.PtpIpAddress(InetAddress.getByName(host),15740),
                PtpIpConnection.PtpIpHostId(bytes,"Alpha Companion"))
            val s = c.openSession(); session = s
            val info = c.deviceInfo
            val codes = info.mOperationsSupported.values.map { it.mValue }
            if(0x9280 in codes && 0x9281 in codes) s.sonyStartTransfer()
            info.mModel.mString
        } catch(e: Exception) { abort(); throw e }
    }
    suspend fun files(onFile: (Int) -> Unit = {}): List<CameraFile> = withContext(Dispatchers.IO) { mutex.withLock {
        val s = requireNotNull(session)
        buildList {
            for(storage in s.storageIDs) {
                for(handle in s.getObjectHandles(storage)) {
                    ensureActive()
                    val i = s.getObjectInfo(handle)
                    if(i.mObjectFormatCode.mValue == 0x3001) continue
                    val name = i.mFilename.mString
                    // First release focuses on JPEG/ARW; no thumbnail probing for movies.
                    if(!name.endsWith(".arw",true) && !name.endsWith(".jpg",true) && !name.endsWith(".jpeg",true)) continue
                    add(CameraFile(handle.mValue.toString(),name,size=i.mObjectCompressedSize.mValue,
                        raw=name.endsWith(".arw",true),handle=handle.mValue,date=i.mCaptureDate.mDate?.toString().orEmpty()))
                    onFile(size)
                }
            }
        }
    } }
    suspend fun thumbnail(handle: Long): ByteArray = withContext(Dispatchers.IO) { mutex.withLock { requireNotNull(session).getThumb(PtpDataType.ObjectHandle(handle)) } }
    suspend fun download(file: CameraFile, output: OutputStream, progress: (Long,Long)->Unit) = withContext(Dispatchers.IO) { mutex.withLock {
        requireNotNull(session).getObjectToStream(PtpDataType.ObjectHandle(requireNotNull(file.handle)),output,
            object: PtpSession.DataLoadListener { override fun onDataLoaded(loaded: Long, expected: Long) { progress(loaded,expected) } })
    } }
    suspend fun finish() = withContext(Dispatchers.IO) {
        try { session?.close() } finally { abort() }
    }
    fun abort() { transport?.close(); transport=null; runCatching { connection?.close() }; session=null; connection=null }
    companion object {
        fun parseGuid(value: String): ShortArray {
            val hex = value.replace(":","").replace("-","").trim()
            require(hex.length == 32 && hex.all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' }) { "请输入已登记的 16 字节 GUID（32 位十六进制），请勿填入随机值" }
            return hex.chunked(2).map { it.toInt(16).toShort() }.toShortArray()
        }
    }
}
