/* Kotlin port of ImagingEdge4Linux imaging-edge.py, Georg Sieber (2024–2025), GPL-3.0.
 * Adaptations: descriptor endpoints, bounded pagination, explicit end, resource choice, faults.
 * Source and pinned revision are recorded in docs/REUSE.md.
 */
package io.github.ayatinene.sonycompanion.protocol

import io.github.ayatinene.sonycompanion.data.*
import io.github.ayatinene.sonycompanion.network.CameraHttp
import io.github.ayatinene.sonycompanion.network.CameraHttpError
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

class SmartphoneTransfer(private val device: Device, private val http: CameraHttp,
    private val report: (String) -> Unit = {}) {
    private val content = requireNotNull(device.content) { "未发现相机传图服务" }
    var rootId = "PushRoot"; private set
    private val startedLock = Any()
    private var started = false
    private var oneTouchResult: OneTouchResult? = null
    fun setOneTouchResult(result: OneTouchResult) { synchronized(startedLock) { if(oneTouchResult!=null) oneTouchResult=result } }
    private suspend fun soap(url: URI, service: String, action: String, args: String): String {
        val body = """<?xml version="1.0" encoding="UTF-8"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body><u:$action xmlns:u="$service">$args</u:$action></s:Body></s:Envelope>"""
        // Some UPnP servers return a SOAP fault in an HTTP 500 response.
        val response = http.post(url.toString(), body, "text/xml; charset=utf-8", mapOf("SOAPACTION" to "\"$service#$action\""), allowSoapFault = true)
        val xml = CameraXml.document(response)
        with(CameraXml) { if (xml.all("Fault").isNotEmpty()) throw SoapFault(xml.text("errorCode"), xml.text("errorDescription").ifBlank { xml.text("faultstring") }) }
        return response
    }
    suspend fun initialize(oneTouch: Boolean = false): List<CameraFile> {
        if(oneTouch) {
            requireNotNull(device.push) { "相机未提供一触分享服务，无法自动导入" }
            synchronized(startedLock) { oneTouchResult=OneTouchResult.Cancelled }
        }
        device.push?.let {
            try {
                soap(it, PUSH, "X_TransferStart", "")
                synchronized(startedLock) { started = true }
            } catch (e: CameraHttpError) {
                if (e.code !in setOf(404, 405, 501)) throw e
                report("X_TransferStart 不受支持（HTTP ${e.code}），继续 ContentDirectory 浏览")
            } catch (e: SoapFault) {
                if (e.code != "401") throw e // UPnP Invalid Action; HTTP 401 must still fail.
                report("X_TransferStart 不受支持（UPnP 401），继续 ContentDirectory 浏览")
            }
        }
        if(oneTouch) {
            check(synchronized(startedLock) { started }) { "相机不支持分享传输初始化，未自动导入" }
            val current=device.digitalImaging?.let { descriptor ->
                try {
                    withTimeoutOrNull(5_000) { currentSharedJpeg(http.getText(descriptor.toString()),descriptor,device.description) }
                } catch(e: CameraHttpError) {
                    if(e.code !in setOf(404,405,501)) throw e
                    report("相机未提供当前照片描述，读取共享列表")
                    null
                }
            }
            if(current!=null) { rootId="NfcCurrent";return listOf(current) }
            // Auto import must never fall back from a missing selection to the entire PhotoRoot.
            return browse("PushRoot",4000)
        }
        return try { browse("PushRoot") } catch (e: SoapFault) {
            // A fault means the root is not available in this camera's selection mode.
            if (e.code !in setOf("701", "710", "501", "402")) throw e
            rootId = "PhotoRoot"
            browse(rootId)
        }
    }
    suspend fun browse(id: String, maxItems: Int = 100_000): List<CameraFile> {
        val out = mutableListOf<CameraFile>(); var offset = 0
        val seen=if(synchronized(startedLock) { oneTouchResult!=null }) mutableMapOf<String,CameraFile>() else null
        repeat(1000) {
            val args = "<ObjectID>${CameraXml.escape(id)}</ObjectID><BrowseFlag>BrowseDirectChildren</BrowseFlag><Filter>*</Filter><StartingIndex>$offset</StartingIndex><RequestedCount>100</RequestedCount><SortCriteria></SortCriteria>"
            val page = CameraXml.browse(soap(content, CDS, "Browse", args))
            require(out.size+page.items.size<=maxItems) { "相机目录条目超出限制，请分批选择共享照片" }
            val normalized=page.items.map { file -> file.copy(
                parent=id,
                resources=file.resources.map { it.copy(url=device.description.resolve(it.url).toString()) },
                thumbnail=file.thumbnail?.let { device.description.resolve(it).toString() }
            ) }
            if(seen!=null) normalized.forEach { file ->
                val previous=seen.put(file.id,file)
                require(previous==null || previous==file) { "相机共享条目标识冲突" }
            }
            out.addAll(normalized)
            if (page.returned == 0) {
                check(offset >= page.total) { "相机分页没有前进：$offset / ${page.total}" }
                return out.distinctBy { it.id }
            }
            offset += page.returned
            if (offset >= page.total) return out.distinctBy { it.id }
        }
        error("目录页数超出限制，请导出诊断信息")
    }
    /** Advisory notifications: the official app ignores their response failures too. Bounds avoid
     * delaying a photo batch indefinitely; cancellation still propagates to the owning session. */
    private suspend fun notifyOneTouch(action: String, args: String = "") {
        val push=synchronized(startedLock) { device.push.takeIf { started && oneTouchResult!=null } } ?: return
        try {
            if(withTimeoutOrNull(2_000) { soap(push,PUSH,action,args) }==null) report("$action 通知超时，继续处理共享照片")
        } catch(e: CancellationException) { throw e }
        catch(e: Exception) { report("$action 通知未完成（${e.javaClass.simpleName}），继续处理共享照片") }
    }
    suspend fun beginOneTouchCopy() { notifyOneTouch("X_GetPushRoot") }
    suspend fun oneTouchProgress(total: Int, processed: Int) {
        require(total>=0 && processed in 0..total)
        notifyOneTouch("X_TransferProgress","<NumTotal>$total</NumTotal><NumTransferd>$processed</NumTransferd>")
    }
    /** Claims the started transfer exactly once, before the request is sent, so a failed or repeated
     * finish never resends X_TransferEnd and two concurrent finishes still send only one. */
    suspend fun finish(error: Boolean = false) {
        val end = synchronized(startedLock) {
            if(!started) return
            started = false
            device.push?.let { it to if(error) 1 else (oneTouchResult?.code ?: 0) }
        } ?: return
        soap(end.first, PUSH, "X_TransferEnd", "<ErrCode>${end.second}</ErrCode>")
    }
    companion object {
        const val CDS = "urn:schemas-upnp-org:service:ContentDirectory:1"
        const val PUSH = "urn:schemas-sony-com:service:XPushList:1"
    }
}

