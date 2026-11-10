package io.github.ayatinene.sonycompanion.protocol

import android.net.Network
import com.fimagena.libptp.*
import com.fimagena.libptp.ptpip.PtpIpConnection
import com.fimagena.libptp.ptpip.PtpIpExceptions
import io.github.ayatinene.sonycompanion.data.*
import kotlinx.coroutines.*
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress

/** Standard PTP/IP + Sony's documented-by-reference Send to Computer start pair.
 * Requires an already registered receiver GUID. No claim of A7R2 hardware validation.
 *
 * Every public operation is a complete single transaction routed through [SessionScheduler], so a
 * long scan never holds the session lock and a user download always preempts the next scan step.
 * The GUID is never written to [report]; only connection progress and failures are reported.
 */
class ComputerTransfer(
    private val network: Network?,
    private val socketFactory: javax.net.SocketFactory?,
    private val closeGraceMillis: Long = CLOSE_GRACE_MILLIS,
    private val reportLog: (LogLevel, String, Throwable?) -> Unit
) : ComputerSession {
    /** Production constructor: the transport uses [network]'s socket factory. */
    constructor(network: Network, reportLog: (LogLevel, String, Throwable?) -> Unit = { _, _, _ -> })
        : this(network, null, CLOSE_GRACE_MILLIS, reportLog)

    var serial: String = ""; private set
    var firmware: String = ""; private set
    @Volatile private var connection: PtpConnection? = null
    @Volatile private var transport: PtpIpConnection? = null
    @Volatile private var session: PtpSession? = null
    private val scheduler = SessionScheduler()
    private val transactions = TransactionRunner(scheduler, ::abort) { isCompleteResponse(it) }
    private val finishSequence = FinishSequence(closeGraceMillis)
    private val gate = SessionGate()
    @Volatile private var sonyPairSupported = false
    @Volatile private var sonyHandshakeDone = false

    override val alive: Boolean get() = gate.isOpen && session != null && connection?.isConnected == true

    suspend fun connect(host: String, guid: String): String = withContext(Dispatchers.IO) {
        val bytes = parseGuid(guid)
        reportLog(LogLevel.DEBUG, "打开原文件会话 host=$host", null)
        val transport = PtpIpConnection()
        (socketFactory ?: network?.socketFactory)?.let { transport.setSocketFactory(it) }
        // Route structured PTP/PTP-IP diagnostics into the app log. The observer contract guarantees
        // the message never carries the GUID or payload bytes; the original cause chain is preserved.
        transport.setLogObserver(object : PtpLogObserver {
            override fun report(level: PtpLogRecord.Level, message: String?, error: Throwable?) {
                reportLog(ptpLevel(level, message), message ?: "PTP 日志", error)
            }
        })
        val c = PtpConnection(transport)
        // Publish under the same lock as abort(): a connect racing a close is rejected and its fresh
        // transport is torn down, so a closed session can never be revived by a late connect.
        if (!gate.publish { this@ComputerTransfer.transport = transport; connection = c }) {
            runCatching { transport.close() }
            throw IOException("原文件会话已结束")
        }
        try {
            c.connect(PtpIpConnection.PtpIpAddress(InetAddress.getByName(host), 15740),
                PtpIpConnection.PtpIpHostId(bytes, "Alpha Companion"))
            val s = c.openSession()
            if (!gate.publish { session = s }) { runCatching { s.close() }; throw IOException("原文件会话已结束") }
            val info = c.deviceInfo
            serial = info.mSerialNumber.mString
            firmware = info.mDeviceVersion.mString
            val codes = info.mOperationsSupported.values.map { it.mValue }
            sonyPairSupported = 0x9280 in codes && 0x9281 in codes
            if (sonyPairSupported) { s.sonyStartTransfer(); sonyHandshakeDone = true }
            reportLog(LogLevel.INFO, "原文件 PTP/IP 会话已打开", null)
            info.mModel.mString
        } catch (e: PtpIpExceptions.OperationFailed) {
            abort()
            reportLog(LogLevel.WARN, sessionFailureLog(e), e)
            when (e.responseCode) {
                PtpOperation.RSPCODE_SessionAlreadyOpen.toLong() ->
                    throw IOException("相机仍保持上次会话：请在相机上退出“发送到电脑”，再重新连接。", e)
                PtpOperation.RSPCODE_DeviceBusy.toLong() ->
                    throw IOException("相机忙：请在相机上退出“发送到电脑”，稍后再连接。", e)
                else -> throw e
            }
        } catch (e: Exception) { abort(); throw e }
    }

    private fun ptpLevel(level: PtpLogRecord.Level, message: String?): LogLevel = when {
        // A successful GetObjectInfo per scanned object would otherwise flood DEBUG/INFO with a
        // request/response pair each. Keep only WARN/ERROR at their level; downgrade this op to TRACE.
        (level == PtpLogRecord.Level.DEBUG || level == PtpLogRecord.Level.INFO) &&
            message?.contains("op=0x1008") == true -> LogLevel.TRACE
        level == PtpLogRecord.Level.DEBUG -> LogLevel.DEBUG
        level == PtpLogRecord.Level.INFO -> LogLevel.INFO
        level == PtpLogRecord.Level.WARN -> LogLevel.WARN
        level == PtpLogRecord.Level.ERROR -> LogLevel.ERROR
        else -> LogLevel.DEBUG
    }

    private fun sessionFailureLog(e: PtpIpExceptions.OperationFailed): String {
        val parameters = e.responseParameters
        val extra = if (parameters.isNotEmpty()) " sessionId=0x${"%08x".format(parameters[0])}" else ""
        return "OpenSession 失败 response=0x${"%04x".format(e.responseCode)}$extra"
    }

    override suspend fun storages(): List<Long> = transaction(SessionPriority.SCAN) { s ->
        s.storageIDs.map { it.mValue }
    }

    override suspend fun handles(storage: Long): List<Long> = transaction(SessionPriority.SCAN) { s ->
        s.getObjectHandles(PtpDataType.StorageID(storage)).map { it.mValue }
    }

    override suspend fun objectInfo(storage: Long, handle: Long): CameraObject = transaction(SessionPriority.SCAN) { s ->
        val i = s.getObjectInfo(PtpDataType.ObjectHandle(handle))
        CameraObject(handle, i.mFilename.mString, i.mObjectCompressedSize.mValue,
            i.mCaptureDate.mDate?.toString().orEmpty(), "$storage/${i.mParentObject.mValue}",
            i.mObjectFormatCode.mValue == ASSOCIATION_FORMAT)
    }

    override suspend fun thumbnail(handle: Long): ByteArray = transaction(SessionPriority.PREVIEW) { s ->
        s.getThumb(PtpDataType.ObjectHandle(handle))
    }

    override suspend fun download(file: CameraFile, output: OutputStream, progress: (Long, Long) -> Unit) =
        transaction(SessionPriority.DOWNLOAD) { s ->
            s.getObjectToStream(PtpDataType.ObjectHandle(requireNotNull(file.handle) { "文件没有可下载的对象句柄" }), output,
                object: PtpSession.DataLoadListener { override fun onDataLoaded(loaded: Long, expected: Long) { progress(loaded, expected) } })
        }

    private suspend fun <T> transaction(priority: SessionPriority, block: suspend (PtpSession) -> T): T =
        transactions.run(priority, { !gate.isOpen }, { session }, block)

    /**
     * Bounded, idempotent finish. [sleepCamera] requests the Sony sleep pair only when both opcodes
     * are advertised and the start handshake completed; otherwise a standard CloseSession is sent and
     * the skip is recorded. The pair + close hold the transaction lock and share one grace budget;
     * a busy lock aborts instead of waiting, and the raw transport is always torn down.
     */
    suspend fun finish(sleepCamera: Boolean = false) {
        if (!gate.beginFinish()) return
        val s = session
        if (s == null) { abort(); return }
        val sleep = shouldSleep(sleepCamera, sonyPairSupported, sonyHandshakeDone)
        if (sleepCamera && !sleep)
            reportLog(LogLevel.INFO, "跳过相机休眠：未同时支持 0x9280/0x9281 或未完成开始握手", null)
        val sleepAction: (() -> Unit)? = if (sleep) ({ s.sonySleep() }) else null
        finishSequence.run(
            scheduler = scheduler,
            sleep = sleepAction,
            close = { s.close() },
            abort = { abort() },
            onTimeout = { reportLog(LogLevel.WARN, "关闭原文件会话超时，强制中止", null) },
            onFailure = { e -> reportLog(LogLevel.WARN, "结束原文件会话失败：${e.message}", e) },
            onWorkerStuck = { reportLog(LogLevel.WARN, "结束原文件会话的工作线程未在预算内退出", null) }
        )
    }

    override fun abort() {
        var t: PtpIpConnection? = null
        var c: PtpConnection? = null
        gate.close {
            t = transport; c = connection
            transport = null; connection = null; session = null
        }
        // Lower the raw transport first: it interrupts a blocked PTP read before the wrapper, whose
        // close() would send a CloseSession, runs. Both calls are idempotent.
        runCatching { t?.close() }
        runCatching { c?.close() }
    }

    companion object {
        private const val ASSOCIATION_FORMAT = 0x3001
        private const val CLOSE_GRACE_MILLIS = 3000L
        /**
         * A [PtpExceptions.OperationFailed] carries a complete response (e.g. "thumbnail unsupported"),
         * so the transaction boundary was reached and the session stays usable. A
         * [PtpIpExceptions.OperationFailed] is a device-cancelled / half transaction and must abort.
         */
        fun isCompleteResponse(error: Throwable): Boolean = error is PtpExceptions.OperationFailed
        /** Sleep is only attempted when both Sony opcodes are advertised, the start handshake completed and the caller asked. */
        fun shouldSleep(sleepCamera: Boolean, pairSupported: Boolean, handshakeDone: Boolean): Boolean =
            sleepCamera && pairSupported && handshakeDone
        fun parseGuid(value: String): ShortArray {
            val hex = value.replace(":","").replace("-","").trim()
            require(hex.length == 32 && hex.all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' }) { "请输入已登记的 16 字节 GUID（32 位十六进制），请勿填入随机值" }
            return hex.chunked(2).map { it.toInt(16).toShort() }.toShortArray()
        }
    }
}
