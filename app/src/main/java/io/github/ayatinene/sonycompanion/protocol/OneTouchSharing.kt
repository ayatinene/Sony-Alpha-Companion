package io.github.ayatinene.sonycompanion.protocol

import io.github.ayatinene.sonycompanion.data.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Locale

/** Result codes observed in Imaging Edge's XPushList end notification. */
enum class OneTouchResult(val code: Int) { Success(0), PartialFailure(0), Failure(1), Cancelled(2) }

/** DID current-content path from Imaging Edge 7.8.6, independently implemented for JPEG imports.
 * A missing current item permits PushRoot discovery; a declared unsupported item must not turn into
 * an unrelated full-library download. No URI from an NFC payload is used here. */
fun currentSharedJpeg(xml: String, descriptor: URI, camera: URI): CameraFile? = with(CameraXml) {
    val current=document(xml).all("X_CurrentContent_URL").singleOrNull() ?: return@with null
    val entries=current.all("X_CurrentContent_URL_URL")
    if(entries.isEmpty()) return@with null
    val types=entries.map { it.getAttribute("type").uppercase(Locale.ROOT) }.toSet()
    require(types.size==1 && types.single() in setOf("JPG","ARW","MPO")) {
        "相机当前共享的格式不支持自动导入 JPEG，请使用其他传输方式"
    }
    val raw=types.single()=="ARW"
    val byProfile=linkedMapOf<String,Resource>()
    for(e in entries) {
        val profile=e.getAttribute("profileID").uppercase(Locale.ROOT)
        if(profile !in setOf("JPEG_TN","JPEG_SM","JPEG_LRG","UNKNOWN")) continue
        // UNKNOWN is the original. For RAW/MPO it is not a JPEG: the official Copy path also uses
        // a JPEG derivative rather than feeding an ARW into an image/jpeg MediaStore entry.
        if(profile=="UNKNOWN" && types.single()!="JPG") continue
        val value=e.textContent.trim()
        if(value.isBlank()) continue
        val uri=sharedResourceUri(descriptor.resolve(value).toString(),camera)
        val resource=Resource(uri.toString(),"image/jpeg",e.getAttribute("size").toLongOrNull()?.coerceAtLeast(0) ?: 0,
            e.getAttribute("resolution"),if(profile=="UNKNOWN") "http-get:*:image/jpeg:*" else "http-get:*:image/jpeg:DLNA.ORG_PN=$profile")
        val old=byProfile.put(profile,resource)
        require(old==null || old==resource) { "相机当前共享内容的尺寸记录冲突" }
    }
    if(byProfile.keys.none { it!="JPEG_TN" }) return@with null
    val resources=byProfile.values.toList()
    val best=CameraFile("","",resources).bestResource() ?: return@with null
    var name=URLDecoder.decode(URI(best.url).rawPath.substringAfterLast('/').replace("+","%2B"),"UTF-8")
    name=name.replace(Regex("^(ORG_|LRG_|SM_|TN_)",RegexOption.IGNORE_CASE),"")
    require(name.isNotBlank() && '/' !in name && '\\' !in name && name.none { it.isISOControl() }) { "相机共享文件名无效" }
    require(name.substringAfterLast('.').uppercase(Locale.ROOT) in setOf("JPG","JPEG","ARW","MPO")) { "相机共享文件不是可导入的 JPEG" }
    if(!name.endsWith(".jpg",true) && !name.endsWith(".jpeg",true)) name=name.substringBeforeLast('.')+".JPG"
    // Ignore the transient HTTP port, but retain the camera's resource path/query identity. The
    // existing camera-scoped PhotoStore key adds name, size and chosen variant for deduplication.
    val stable=resources.map { URI(it.url).let { u -> "${u.rawPath}?${u.rawQuery.orEmpty()}" } }.sorted().joinToString("|")
    val id=MessageDigest.getInstance("SHA-256").digest(stable.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
    CameraFile("did:$id",name,resources,resources.firstOrNull { it.jpegSize==JpegSize.THUMBNAIL }?.url,
        size=best.size,raw=raw,parent="NfcCurrent")
}

/** Ports may differ between the descriptor and photo server. Hosts must still identify this camera. */
internal fun sharedResourceUri(value: String, camera: URI): URI {
    val uri=URI(value)
    require(uri.scheme?.lowercase(Locale.ROOT) in setOf("http","https") &&
        uri.host?.equals(camera.host,true)==true && uri.userInfo==null && uri.fragment==null) { "共享照片地址不属于当前相机" }
    return uri
}

/** Enumerate only the camera's selected PushRoot descendants, with cycle/size limits and cancellation.
 * Validate the entire set before enqueueing, so unsupported content cannot silently become success. */
suspend fun selectedSharedJpegs(initial: List<CameraFile>, camera: URI,
    browse: suspend (String)->List<CameraFile>): List<CameraFile> {
    val pending=ArrayDeque<Pair<List<CameraFile>,Int>>().apply { add(initial to 0) }
    val visited=mutableSetOf("PushRoot","NfcCurrent")
    val files=linkedMapOf<String,CameraFile>()
    var entries=0
    while(pending.isNotEmpty()) {
        currentCoroutineContext().ensureActive()
        val (items,depth)=pending.removeFirst()
        require(depth<=16) { "共享目录层数超出限制" }
        for(file in items) {
            currentCoroutineContext().ensureActive()
            require(++entries<=4000) { "共享内容过多，请在相机上分批选择" }
            require(file.id.isNotBlank()) { "相机共享条目缺少标识" }
            if(file.container) {
                require(file.id !in setOf("0","PhotoRoot") && visited.add(file.id) && visited.size<=256) { "共享目录无效或存在循环" }
                pending.add(browse(file.id) to depth+1)
            } else {
                require(files.size<2000) { "共享照片过多，请在相机上分批选择" }
                val resource=requireNotNull(file.bestResource()) { "共享集合包含未提供 JPEG 的文件，本次未自动导入" }
                require(resource.jpegSize!=JpegSize.THUMBNAIL) { "共享条目仅提供缩略图，不能自动导入照片" }
                sharedResourceUri(resource.url,camera)
                val old=files.put(file.id,file)
                require(old==null || old==file) { "相机共享条目标识冲突" }
            }
        }
    }
    require(files.isNotEmpty()) { "相机没有提供可导入的共享照片，请在相机上重新选择" }
    return files.values.toList()
}

/** Only this batch's items contribute to progress/outcome; recovered historical tasks stay untouched. */
fun oneTouchOutcome(queue: List<QueueItem>, selected: Set<String>): OneTouchResult {
    val batch=queue.filter { it.file.id in selected }
    if(batch.size!=selected.size || batch.any { it.state in setOf("等待","传输中","已取消","待恢复") }) return OneTouchResult.Cancelled
    val successes=batch.count { it.state in setOf("完成","已存在") }
    return when {
        successes==batch.size -> OneTouchResult.Success
        successes>0 -> OneTouchResult.PartialFailure
        else -> OneTouchResult.Failure
    }
}
