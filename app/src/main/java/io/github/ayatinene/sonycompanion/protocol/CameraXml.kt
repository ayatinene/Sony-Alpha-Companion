package io.github.ayatinene.sonycompanion.protocol

import io.github.ayatinene.sonycompanion.data.*
import java.io.StringReader
import java.net.URI
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.xml.sax.InputSource

object CameraXml {
    fun document(xml: String): Element {
        require(xml.length <= 8 * 1024 * 1024) { "XML 超过大小限制" }
        require(!xml.contains("<!DOCTYPE", true) && !xml.contains("<!ENTITY", true)) { "拒绝 XML 外部实体" }
        val f = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        }
        return f.newDocumentBuilder().parse(InputSource(StringReader(xml))).documentElement
    }
    fun Element.all(name: String): List<Element> {
        val nodes = getElementsByTagNameNS("*", name)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }
    fun Element.text(name: String) = all(name).firstOrNull()?.textContent?.trim().orEmpty()
    fun description(xml: String, location: URI): Device {
        val root = document(xml)
        val urlBase = root.text("URLBase").takeIf { it.isNotBlank() }?.let(location::resolve) ?: location
        var remote: URI? = null
        root.all("X_ScalarWebAPI_Service").forEach { e ->
            if (e.text("X_ScalarWebAPI_ServiceType") == "camera") {
                val advertised = e.text("X_ScalarWebAPI_ActionList_URL")
                if (advertised.isNotBlank()) {
                    val base = urlBase.resolve(advertised).toString().trimEnd('/')
                    remote = URI(if (base.endsWith("/camera")) base else "$base/camera")
                }
            }
        }
        fun control(type: String): URI? = root.all("service").firstOrNull {
            it.text("serviceType").contains(":$type:")
        }?.text("controlURL")?.takeIf(String::isNotBlank)?.let(urlBase::resolve)
        return Device(root.text("friendlyName").ifBlank { "Sony Camera" }, root.text("modelName"),
            location, remote, control("ContentDirectory"), control("XPushList"),
            root.all("service").firstOrNull { it.text("serviceType").contains(":DigitalImaging:") }
                ?.text("SCPDURL")?.takeIf(String::isNotBlank)?.let(urlBase::resolve))
    }
    fun identity(xml: String): CameraIdentity {
        val root=document(xml)
        return CameraIdentity(root.text("X_ModelName"),root.text("X_FirmwareVersion"),
            root.text("X_LensModelName").ifBlank { root.text("X_LensModelNumber") },root.text("X_LensFirmwareVersion"),
            root.all("X_PlayMemoriesCameraApps_App").mapNotNull { app ->
                app.text("X_PlayMemoriesCameraApps_AppName").takeIf(String::isNotBlank)?.let {
                    CameraApplication(it,app.text("X_PlayMemoriesCameraApps_AppVersion"))
                }
            })
    }
    fun location(ssdp: String): URI? = ssdp.lineSequence().firstOrNull {
        it.substringBefore(':').trim().equals("LOCATION", true)
    }?.substringAfter(':')?.trim()?.let { runCatching { URI(it) }.getOrNull() }

    data class BrowsePage(val items: List<CameraFile>, val returned: Int, val total: Int)
    fun browse(xml: String): BrowsePage {
        val root = document(xml)
        val fault = root.text("faultstring")
        if (root.all("Fault").isNotEmpty()) throw SoapFault(root.text("errorCode"), fault.ifBlank { root.text("errorDescription") })
        val result = root.text("Result")
        val inner = if (result.isBlank()) null else document(result)
        val items = inner?.let { d ->
            (d.all("container") + d.all("item")).map { e ->
                val container = e.localName == "container"
                val resources = e.all("res").mapNotNull { r ->
                    val url = r.textContent.trim(); if (url.isBlank()) return@mapNotNull null
                    val protocol = r.getAttribute("protocolInfo")
                    Resource(url, protocol.split(':').getOrNull(2).orEmpty(), r.getAttribute("size").toLongOrNull() ?: 0,
                        r.getAttribute("resolution"), protocol)
                }
                val name = e.text("title").ifBlank { e.getAttribute("id") }
                CameraFile(e.getAttribute("id"), name, resources,
                    e.text("albumArtURI").takeIf(String::isNotBlank)
                        ?: CameraFile(e.getAttribute("id"), name, resources).jpegThumbnail()?.url,
                    e.text("date"), container, resources.maxOfOrNull { it.size } ?: 0,
                    raw = name.endsWith(".arw", true))
            }
        }.orEmpty()
        return BrowsePage(items, root.text("NumberReturned").toIntOrNull() ?: items.size,
            root.text("TotalMatches").toIntOrNull() ?: items.size)
    }
    fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
}
class SoapFault(val code: String, detail: String) : Exception("SOAP $code: $detail")
