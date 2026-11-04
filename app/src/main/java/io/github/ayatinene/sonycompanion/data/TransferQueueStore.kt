package io.github.ayatinene.sonycompanion.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.UUID

fun stableCameraId(udn: String, serial: String, model: String): String? {
    val uuid=udn.trim().lowercase(Locale.ROOT).removePrefix("urn:").removePrefix("uuid:")
    val standard=Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    // Older Sony ScalarWebAPI descriptors sometimes omit the first UUID hyphen:
    // 12-4-4-12 instead of 8-4-4-4-12. Normalise only this exact 32-hex format;
    // the trailing 12 hex digits are device-specific and must not be a placeholder.
    val sonyLegacy=Regex("[0-9a-f]{12}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    val canonical=when {
        standard.matches(uuid) -> uuid
        sonyLegacy.matches(uuid) && uuid.takeLast(12).any { it != '0' } -> {
            val hex=uuid.replace("-", "")
            "${hex.substring(0,8)}-${hex.substring(8,12)}-${hex.substring(12,16)}-${hex.substring(16,20)}-${hex.substring(20)}"
        }
        else -> null
    }
    if(canonical!=null && canonical.any { it in '1'..'9' || it in 'a'..'f' }) return "udn:$canonical"
    val value=serial.trim()
    return value.takeIf { it.length>=3 && it.any { c -> c!='0' && c!='-' && !c.isWhitespace() } &&
        it.lowercase(Locale.ROOT) !in setOf("unknown","none","n/a") }?.let { "serial:$model:$it" }
}

fun queueScope(device: Device, mode: CameraMode): String =
    "${device.cameraId ?: "session:${UUID.randomUUID()}"}|${mode.name}"

/** Match current camera metadata before reusing an interrupted task; never reuse old URLs. */
fun refreshQueuedFile(old: CameraFile, current: List<CameraFile>): CameraFile? {
    val fresh=current.singleOrNull { it.id==old.id && it.parent==old.parent && it.name==old.name && !it.container } ?: return null
    if (old.date.isBlank() || fresh.date!=old.date) return null
    if (old.handle!=null) return fresh.takeIf { old.size>0 && it.size==old.size }
    // avContent originals: the fresh entry already carries the re-queried URL and the explicit
    // format/identity; the id pins source + content + format + file name, so a same-format sibling
    // never matches. The stored URL is treated as data only and is always replaced.
    if (old.original) return fresh
    val chosen=old.bestResource() ?: return null
    val resource=fresh.resources.singleOrNull {
        it.isJpeg && it.jpegSize==chosen.jpegSize && it.resolution==chosen.resolution &&
            chosen.size>0 && it.size==chosen.size
    } ?: return null
    return fresh.copy(resources=listOf(resource),size=resource.size)
}

fun transferSource(scope: String, file: CameraFile, resource: Resource?): String {
    // An avContent original's identity must not depend on the declared size: the listing may report 0
    // and the download response a real length, and a size-sensitive key would then look like a new
    // file and re-download an already-imported photo.
    val variant=when {
        file.original -> "original:${file.format}:${file.name}"
        resource!=null -> "${resource.jpegSize}:${resource.resolution}:${resource.size}"
        else -> "original:${file.size}"
    }
    return listOf(scope,file.parent,file.name,file.id,file.date,variant).joinToString("|")
}

/** Atomic small JSON store. Progress is intentionally not persisted: retries start from byte zero. */
class TransferQueueStore(private val file: File) {
    @Synchronized fun save(scope: String, queue: List<QueueItem>) {
        val root=read()
        if(queue.isEmpty()) root.remove(scope) else root.put(scope,JSONArray(queue.takeLast(2000).map { item ->
            val f=item.file
            JSONObject().put("state",item.state).put("error",item.error).put("file",JSONObject()
                .put("id",f.id).put("name",f.name).put("date",f.date).put("parent",f.parent)
                .put("size",f.size).put("raw",f.raw).put("handle",f.handle).put("thumbnail",f.thumbnail)
                .put("original",f.original).put("format",f.format).put("protocol",f.protocol)
                .put("resources",JSONArray(f.resources.map { r -> JSONObject().put("url",r.url).put("mime",r.mime)
                    .put("size",r.size).put("resolution",r.resolution).put("profile",r.profile) })))
        }))
        file.parentFile?.mkdirs()
        val temp=File(file.path+".tmp")
        temp.outputStream().use { out -> out.write(root.toString().toByteArray()); out.fd.sync() }
        Files.move(temp.toPath(),file.toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE)
    }
    @Synchronized fun load(scope: String): List<QueueItem> {
        val array=read().optJSONArray(scope) ?: return emptyList()
        return (0 until array.length()).mapNotNull { i -> runCatching {
            val item=array.getJSONObject(i); val f=item.getJSONObject("file"); val r=f.getJSONArray("resources")
            val resources=(0 until r.length()).map { n -> r.getJSONObject(n).let {
                Resource(it.getString("url"),it.getString("mime"),it.getLong("size"),it.getString("resolution"),it.getString("profile"))
            } }
            val cameraFile=CameraFile(f.getString("id"),f.getString("name"),resources,
                f.optString("thumbnail").takeIf(String::isNotBlank),f.getString("date"),size=f.getLong("size"),
                raw=f.getBoolean("raw"),handle=if(f.has("handle"))f.getLong("handle")else null,parent=f.optString("parent"),
                original=f.optBoolean("original",false),format=f.optString("format"),protocol=f.optString("protocol"))
            val oldState=item.getString("state")
            QueueItem(cameraFile,if(oldState in setOf("等待","传输中")) "待恢复" else oldState,
                error=item.optString("error").takeIf(String::isNotBlank))
        }.getOrNull() }
    }
    private fun read(): JSONObject = if(file.exists()) JSONObject(file.readText()) else JSONObject()
}
