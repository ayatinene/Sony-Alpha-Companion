/* Adapted from CameraController.kt, SonyRX10M3Remote, MIT, RealMadScientist (2025).
 * Preserves JSON-RPC/exposure/capture/AF/movie command implementation; removes View dependency.
 * Adds capability gates, explicit errors, polling versions, long-exposure await and Wi-Fi transport.
 */
package io.github.ayatinene.sonycompanion.protocol

import io.github.ayatinene.sonycompanion.data.*
import io.github.ayatinene.sonycompanion.network.CameraHttp
import kotlinx.coroutines.*
import org.json.*
import java.util.concurrent.atomic.AtomicInteger

class CameraApiError(val code: Int, detail: String) : Exception("相机 API $code: $detail")
class RemoteCamera(private val endpoint: String, private val http: CameraHttp,
    private val trace: (String) -> Unit = {}) {
    private val nextId = AtomicInteger(1)
    var state = RemoteState(); private set
    private var liveViewStarted = false
    suspend fun call(method: String, params: List<Any> = emptyList(), version: String = "1.0"): JSONArray {
        val request = JSONObject().put("method",method).put("params",JSONArray(params))
            .put("id",nextId.getAndIncrement()).put("version",version)
        trace("RPC → $method $version")
        val json = JSONObject(http.post(endpoint,request.toString()))
        val error = json.optJSONArray("error")
        if (error != null) throw CameraApiError(error.optInt(0,-1),error.optString(1))
        return json.optJSONArray("result") ?: error("相机返回缺少 result：$method")
    }
    suspend fun initialize(): RemoteState {
        refresh()
        if ("startRecMode" in state.apis) {
            call("startRecMode")
            for(attempt in 0 until 10) {
                delay(400); refresh()
                if ("startLiveview" in state.apis || "actTakePicture" in state.apis) break
            }
        }
        var eventVersion = "1.0"
        try {
            val versions = call("getVersions").optJSONArray(0)?.strings().orEmpty()
            // Only use a version when getMethodTypes advertises getEvent for that version.
            for (version in listOf("1.3","1.2","1.1")) {
                if (version !in versions) continue
                val types = call("getMethodTypes",listOf(version))
                if ((0 until types.length()).any { types.optJSONArray(it)?.let { method->method.optString(0)=="getEvent" && method.optString(3)==version }==true }) { eventVersion = version; break }
            }
        } catch (e: CancellationException) { throw e } catch (_: Exception) { }
        val info = if ("getApplicationInfo" in state.apis) call("getApplicationInfo").let { "${it.optString(0)} ${it.optString(1)}" } else "版本未提供"
        state = state.copy(application=info,eventVersion=eventVersion)
        return refresh()
    }
    suspend fun refresh(): RemoteState {
        val apis = call("getAvailableApiList").optJSONArray(0)?.strings()?.toSet().orEmpty()
        val settings = mutableListOf<Setting>()
        for ((key,label) in listOf("IsoSpeedRate" to "ISO", "ShutterSpeed" to "快门", "FNumber" to "光圈",
            "ExposureCompensation" to "曝光补偿", "ShootMode" to "拍摄类型", "FocusMode" to "对焦模式", "ExposureMode" to "曝光模式")) {
            if ("getAvailable$key" !in apis) continue
            try {
                val r = call("getAvailable$key")
                if (key == "ExposureCompensation") {
                    val step = when(r.optInt(3,1)) { 1 -> 3; 2 -> 2; else -> 0 }
                    settings += Setting(key,label,r.getInt(0).toString(),(r.getInt(2)..r.getInt(1)).map(Int::toString),"set$key" in apis,step)
                } else settings += Setting(key,label,r.optString(0),r.optJSONArray(1)?.strings().orEmpty(),"set$key" in apis)
            } catch (e: CancellationException) { throw e } catch (e: Exception) { trace("$key: ${e.message}") }
        }
        state = state.copy(apis=apis,settings=settings)
        return state
    }
    suspend fun set(key: String, value: String) {
        val setting = state.settings.firstOrNull { it.key == key } ?: error("相机未开放此参数")
        require(setting.writable && value in setting.choices) { "当前模式无法修改此参数" }
        call("set$key",listOf(if(key=="ExposureCompensation") value.toInt() else value))
    }
    suspend fun events(): Pair<RemoteState,List<String>> {
        val result = try { call("getEvent",listOf(false),state.eventVersion) }
        catch(e: CameraApiError) {
            if(e.code !in listOf(14,15) || state.eventVersion=="1.0") throw e
            state = state.copy(eventVersion="1.0"); call("getEvent",listOf(false))
        }
        var status = state.status
        var apis = state.apis
        val values = mutableMapOf<String,String>()
        val urls = mutableListOf<String>()
        fun visit(x: Any?) {
            when(x) {
                is JSONObject -> {
                    when(x.optString("type")) {
                        "cameraStatus" -> status = x.optString("cameraStatus")
                        "availableApiList" -> x.optJSONArray("names")?.let { apis = it.strings().toSet() }
                    }
                    for (key in listOf("isoSpeedRate","shutterSpeed","fNumber","exposureCompensation","shootMode","focusMode","exposureMode")) {
                        val current="current"+key.replaceFirstChar(Char::uppercaseChar)
                        val field=if(x.has(current)) current else key
                        if(x.has(field)) values[key.replaceFirstChar(Char::uppercaseChar)] = x.optString(field)
                    }
                    // Camera-body shutter events report a postview URL after capture.
                    for (key in listOf("postviewImageUrl","postviewUrl","imageUrl")) x.optString(key).takeIf { it.startsWith("http://") }?.let(urls::add)
                    x.optJSONArray("takePictureUrl")?.strings()?.filter { it.startsWith("http://") }?.let(urls::addAll)
                    x.keys().forEach { visit(x.opt(it)) }
                }
                is JSONArray -> (0 until x.length()).forEach { visit(x.opt(it)) }
            }
        }
        visit(result)
        val settings=state.settings.map { s -> s.copy(current=values[s.key]?:s.current,writable="set${s.key}" in apis) }.toMutableList()
        for((key,label) in listOf("IsoSpeedRate" to "ISO","ShutterSpeed" to "快门","FNumber" to "光圈","FocusMode" to "对焦模式","ExposureMode" to "曝光模式")) {
            if(settings.none { it.key==key }) values[key]?.let { value -> settings += Setting(key,label,value,emptyList(),false) }
        }
        state = state.copy(status=status,apis=apis,settings=settings)
        return state to urls.distinct()
    }
    suspend fun takePicture(): List<String> {
        require("actTakePicture" in state.apis) { "当前模式无法拍照" }
        val result = try { call("actTakePicture") } catch(e: CameraApiError) {
            if(e.code!=40403 || "awaitTakePicture" !in state.apis) throw e
            awaitPicture()
        } catch(e: java.net.SocketTimeoutException) {
            // Never resend actTakePicture: a lost response does not mean a failed shutter.
            if("awaitTakePicture" !in state.apis) throw e
            awaitPicture()
        }
        val first = result.opt(0)
        return (if(first is JSONArray) first.strings() else result.strings()).filter { it.startsWith("http://") }
    }
    private suspend fun awaitPicture(): JSONArray {
        val end = System.nanoTime() + 180_000_000_000L
        while(System.nanoTime()<end) {
            try { return call("awaitTakePicture") }
            catch(e: CameraApiError) { if(e.code!=40403) throw e; delay(500) }
        }
        error("等待长曝光完成超时，请检查相机；未重发快门")
    }
    suspend fun focus() {
        require("actHalfPressShutter" in state.apis && "cancelHalfPressShutter" in state.apis) { "相机未开放半按快门" }
        call("actHalfPressShutter")
        try { delay(700) } finally { withContext(NonCancellable) { call("cancelHalfPressShutter") } }
    }
    suspend fun liveView(): String {
        val url=call("startLiveview").getString(0)
        liveViewStarted=true
        return url
    }
    suspend fun stopLiveView() {
        if(!liveViewStarted) return
        liveViewStarted=false
        if("stopLiveview" in state.apis) call("stopLiveview")
    }
    suspend fun movie(start: Boolean) {
        val method = if(start) "startMovieRec" else "stopMovieRec"
        require(method in state.apis) { "当前相机模式未开放录像操作" }
        call(method)
    }
}
fun JSONArray.strings(): List<String> = (0 until length()).mapNotNull { opt(it)?.takeIf { x -> x is String }?.toString() }
