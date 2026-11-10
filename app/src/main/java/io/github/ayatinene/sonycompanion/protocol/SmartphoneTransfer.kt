/* Kotlin port of ImagingEdge4Linux imaging-edge.py, Georg Sieber (2024–2025), GPL-3.0.
 * Adaptations: descriptor endpoints, bounded pagination, explicit end, resource choice, faults.
 * Source and pinned revision are recorded in docs/REUSE.md.
 */
package io.github.ayatinene.sonycompanion.protocol

import io.github.ayatinene.sonycompanion.data.*
import io.github.ayatinene.sonycompanion.network.CameraHttp
import io.github.ayatinene.sonycompanion.network.CameraHttpError
import java.net.URI

class SmartphoneTransfer(private val device: Device, private val http: CameraHttp,
    private val report: (String) -> Unit = {}) {
    private val content = requireNotNull(device.content) { "未发现 ContentDirectory 服务" }
    var rootId = "PushRoot"; private set
    private var started = false
    private suspend fun soap(url: URI, service: String, action: String, args: String): String {
        val body = """<?xml version="1.0" encoding="UTF-8"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body><u:$action xmlns:u="$service">$args</u:$action></s:Body></s:Envelope>"""
        // Some UPnP servers return a SOAP fault in an HTTP 500 response.
        val response = http.post(url.toString(), body, "text/xml; charset=utf-8", mapOf("SOAPACTION" to "\"$service#$action\""), allowSoapFault = true)
        val xml = CameraXml.document(response)
        with(CameraXml) { if (xml.all("Fault").isNotEmpty()) throw SoapFault(xml.text("errorCode"), xml.text("errorDescription").ifBlank { xml.text("faultstring") }) }
        return response
    }
    suspend fun initialize(): List<CameraFile> {
        device.push?.let {
            try {
                soap(it, PUSH, "X_TransferStart", "")
                started = true
            } catch (e: CameraHttpError) {
                if (e.code !in setOf(404, 405, 501)) throw e
                report("X_TransferStart 不受支持（HTTP ${e.code}），继续 ContentDirectory 浏览")
            } catch (e: SoapFault) {
                if (e.code != "401") throw e // UPnP Invalid Action; HTTP 401 must still fail.
                report("X_TransferStart 不受支持（UPnP 401），继续 ContentDirectory 浏览")
            }
        }
        return try { browse("PushRoot") } catch (e: SoapFault) {
            // A fault means the root is not available in this camera's selection mode.
            if (e.code !in setOf("701", "710", "501", "402")) throw e
            rootId = "PhotoRoot"
            browse(rootId)
        }
    }
    suspend fun browse(id: String): List<CameraFile> {
        val out = mutableListOf<CameraFile>(); var offset = 0
        repeat(1000) {
            val args = "<ObjectID>${CameraXml.escape(id)}</ObjectID><BrowseFlag>BrowseDirectChildren</BrowseFlag><Filter>*</Filter><StartingIndex>$offset</StartingIndex><RequestedCount>100</RequestedCount><SortCriteria></SortCriteria>"
            val page = CameraXml.browse(soap(content, CDS, "Browse", args))
            out.addAll(page.items.map { file -> file.copy(
                parent=id,
                resources=file.resources.map { it.copy(url=device.description.resolve(it.url).toString()) },
                thumbnail=file.thumbnail?.let { device.description.resolve(it).toString() }
            ) })
            if (page.returned == 0) {
                check(offset >= page.total) { "相机分页没有前进：$offset / ${page.total}" }
                return out.distinctBy { it.id }
            }
            offset += page.returned
            if (offset >= page.total) return out.distinctBy { it.id }
        }
        error("目录页数超出限制，请导出诊断信息")
    }
    suspend fun finish(error: Boolean = false) {
        if (started) device.push?.let { soap(it, PUSH, "X_TransferEnd", "<ErrCode>${if(error) 1 else 0}</ErrCode>"); started = false }
    }
    companion object {
        const val CDS = "urn:schemas-upnp-org:service:ContentDirectory:1"
        const val PUSH = "urn:schemas-sony-com:service:XPushList:1"
    }
}
