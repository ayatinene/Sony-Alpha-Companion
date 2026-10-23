package io.github.ayatinene.sonycompanion.data

import android.app.*
import android.content.*
import android.graphics.*
import android.net.*
import android.util.LruCache
import androidx.core.content.ContextCompat
import io.github.ayatinene.sonycompanion.protocol.*
import io.github.ayatinene.sonycompanion.network.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Response
import org.json.*
import java.text.SimpleDateFormat
import java.util.*

class CompanionController(private val context: Context) {
    val prefs = context.getSharedPreferences("settings",Context.MODE_PRIVATE)
    val photos = PhotoStore(context)
    private val scope = CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val mutable = MutableStateFlow(AppState(local=photos.list()))
    val state = mutable.asStateFlow()
    private val frameState = MutableStateFlow<Bitmap?>(null)
    val frame = frameState.asStateFlow()
    private var http: CameraHttp? = null
    private var remote: RemoteCamera? = null
    private var smartphone: SmartphoneTransfer? = null
    private var computer: ComputerTransfer? = null
    private var liveJob: Job? = null
    private var eventJob: Job? = null
    private var connectionJob: Job? = null
    private var queueJob: Job? = null
    private var intervalJob: Job? = null
    private var liveResponse: Response? = null
    private var downloadResponse: Response? = null
    private val shutter = Mutex()
    private val thumbs = LruCache<String,Bitmap>(48)
    private val importedUrls = linkedSetOf<String>()
    private val protocolResponses = Collections.synchronizedMap(linkedMapOf<String,String>())
    private var selectedNetwork: Network? = null
    private var visible = true
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    init {
        cm.registerNetworkCallback(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),object: ConnectivityManager.NetworkCallback() {
            override fun onLost(network: Network) {
                if(network==selectedNetwork) scope.launch {
                    cancelTransfers(); disconnect(false)
                    update { copy(error="相机 Wi-Fi 已断开，请重新连接",message="连接已断开") }
                }
            }
        })
    }
    private fun update(block: AppState.()->AppState) { mutable.update { it.block() } }
    private fun log(message: String) {
        val line = SimpleDateFormat("HH:mm:ss",Locale.ROOT).format(Date())+" "+message
        update { copy(logs=(logs+line).takeLast(250)) }
    }
    fun clearError() { update { copy(error=null) } }
    fun connect(mode: CameraMode) {
        if(state.value.transferring) { update { copy(error="请先完成或取消文件传输") }; return }
        if(connectionJob?.isActive==true) return
        connectionJob = scope.launch {
            disconnect(false)
            update { copy(mode=mode,busy=true,message="查找相机…",error=null,files=emptyList(),remote=RemoteState()) }
            try {
                val networks = CameraNetwork(context)
                val network = networks.wifi(); selectedNetwork=network
                val transport = CameraHttp(network) { label,response ->
                    synchronized(protocolResponses) {
                        if(protocolResponses.size>=30 && label !in protocolResponses) protocolResponses.remove(protocolResponses.keys.first())
                        protocolResponses[label]=response.take(200_000)
                    }
                }; http=transport
                if(mode==CameraMode.COMPUTER) {
                    val guid=prefs.getString("guid","").orEmpty()
                    ComputerTransfer.parseGuid(guid)
                    val host=prefs.getString("computerHost","").orEmpty().trim().ifBlank {
                        val devices=networks.discover(network,transport,false,"",computer=true)
                        require(devices.size==1) { "没有唯一的 MtpNullService 相机，请在设置填写相机的局域网 IP" }
                        devices.single().description.host
                    }
                    val transfer=ComputerTransfer(network); computer=transfer
                    val model=transfer.connect(host,guid)
                    update { copy(device=Device(model,model,java.net.URI("http://$host")),message="读取文件对象…") }
                    val files=transfer.files { count-> update { copy(message="已读取 $count 个文件…") } }
                    update { copy(connected=true,files=files,message="PTP/IP 已连接 · 尚未实机验证") }
                } else {
                    val devices = networks.discover(network,transport,mode==CameraMode.REMOTE,prefs.getString("description","").orEmpty().trim())
                    require(devices.size==1) {
                        if(devices.isEmpty()) "没有发现匹配服务。请确认相机工作模式、Wi-Fi 和附近设备权限；可在设置填写描述文件 URL。"
                        else "发现多个相机，请在设置填写目标相机的描述文件 URL"
                    }
                    val device=devices.single(); log("发现 ${device.model} ${device.description}")
                    update { copy(device=device) }
                    if(mode==CameraMode.REMOTE) {
                        val camera=RemoteCamera(requireNotNull(device.remote).toString(),transport,::log); remote=camera
                        val capabilities=camera.initialize()
                        update { copy(connected=true,remote=capabilities,message="遥控已连接 · ${device.model}") }
                        startMonitoring()
                    } else {
                        val transfer=SmartphoneTransfer(device,transport); smartphone=transfer
                        val files=transfer.initialize()
                        update { copy(connected=true,files=files,folder=transfer.rootId,message="JPEG 服务已连接 · ${transfer.rootId}") }
                    }
                }
            } catch(e: CancellationException) { throw e }
            catch(e: Exception) {
                log("连接失败：${e.message}")
                computer?.abort(); computer=null; smartphone=null; remote=null; http?.close(); http=null
                update { copy(connected=false,error=e.message,message="连接失败") }
            } finally { update { copy(busy=false) } }
        }
    }
    fun reconnect() { state.value.mode?.let(::connect) }
    private fun startMonitoring() {
        val camera=remote?:return
        if(!visible || eventJob?.isActive==true) return
        eventJob=scope.launch {
            var failures=0
            while(isActive) {
                try {
                    val (status,urls)=camera.events(); failures=0
                    update { copy(remote=status) }
                    urls.forEach(::importPostview)
                } catch(e: CancellationException) { throw e }
                catch(e: Exception) {
                    failures++; log("状态查询失败：${e.message}")
                    if(failures>=3) { update { copy(error="状态查询失败，请检查连接并重连") }; break }
                }
                delay(900)
            }
        }
        if("startLiveview" in state.value.remote.apis) liveJob=scope.launch(Dispatchers.IO) {
            var attempts=0
            while(isActive && attempts<3) {
                try {
                    val url=camera.liveView()
                    val response=requireNotNull(http).get(url); liveResponse=response
                    response.use {
                        val reader=LiveViewReader(requireNotNull(it.body).byteStream())
                        while(isActive) {
                            val bytes=reader.next()?:error("实时取景流结束")
                            val image=BitmapFactory.decodeByteArray(bytes,0,bytes.size)?:continue
                            frameState.value=image
                        }
                    }
                } catch(e: CancellationException) { throw e }
                catch(e: Exception) { attempts++; log("实时取景中断 ($attempts/3)：${e.message}"); delay(1000L*attempts) }
            }
            if(attempts>=3) update { copy(error="实时取景已停止，请重连相机") }
        }
    }
    fun setVisible(value: Boolean) {
        visible=value
        if(value) { if(state.value.mode==CameraMode.REMOTE && state.value.connected) startMonitoring() }
        else { intervalJob?.cancel(); liveResponse?.close(); liveJob?.cancel(); liveJob=null; eventJob?.cancel(); eventJob=null }
    }
    suspend fun disconnect(finish: Boolean = true) {
        liveResponse?.close(); liveResponse=null; liveJob?.cancel(); liveJob=null; eventJob?.cancel(); eventJob=null
        intervalJob?.cancel(); intervalJob=null; frameState.value=null
        if(finish) {
            try { smartphone?.finish(); computer?.finish() }
            catch(e: Exception) { log("结束会话：${e.message}") }
        }
        computer?.abort(); computer=null; remote=null; smartphone=null; http?.close(); http=null
        selectedNetwork=null; thumbs.evictAll(); importedUrls.clear()
        update { copy(connected=false,files=emptyList(),device=null,remote=RemoteState(),intervalRemaining=0,message="已断开") }
    }
    fun endSession() { if(!state.value.transferring) scope.launch { disconnect() } }
    private fun action(block: suspend ()->Unit) { scope.launch {
        try { block() } catch(e: CancellationException) { throw e } catch(e: Exception) { log(e.message.orEmpty()); update { copy(error=e.message) } }
    } }
    fun setSetting(key:String,value:String) = action {
        val camera=requireNotNull(remote); camera.set(key,value)
        val refreshed=camera.refresh()
        update { copy(remote=refreshed) }
    }
    fun focus() = action { requireNotNull(remote).focus() }
    fun movie() = action {
        val camera=requireNotNull(remote)
        camera.movie(!state.value.remote.status.contains("MovieRecording",true))
    }
    fun capture() = action { shutter.withLock { captureInternal() } }
    private suspend fun captureInternal() {
        val camera=requireNotNull(remote)
        val urls=camera.takePicture()
        urls.forEach(::importPostview)
        if(urls.isEmpty()) update { copy(message="快门请求已接受，未返回 JPEG 预览；请检查相机") }
    }
    private fun importPostview(url: String) {
        if(url in importedUrls) return
        importedUrls += url
        while(importedUrls.size>500) importedUrls.remove(importedUrls.first())
        val name=runCatching { java.net.URI(url).path.substringAfterLast('/') }.getOrDefault("").takeIf { it.endsWith(".jpg",true) }
            ?: "Remote_${System.currentTimeMillis()}.JPG"
        enqueue(listOf(CameraFile(url,name,listOf(Resource(url)),thumbnail=url)))
    }
    fun interval(count: Int, seconds: Int) {
        if(intervalJob?.isActive==true) return
        intervalJob=scope.launch {
            try {
                repeat(count.coerceIn(1,999)) { index ->
                    update { copy(intervalRemaining=count-index) }
                    shutter.withLock { captureInternal() }
                    if(index<count-1) delay(seconds.coerceIn(2,3600)*1000L)
                }
            } catch(e: CancellationException) { throw e } catch(e: Exception) { update { copy(error=e.message) } }
            finally { update { copy(intervalRemaining=0) } }
        }
    }
    fun stopInterval() { intervalJob?.cancel() }
    fun browse(id: String) = action {
        update { copy(busy=true) }
        try { val files=requireNotNull(smartphone).browse(id); update { copy(files=files,folder=id) } }
        finally { update { copy(busy=false) } }
    }
    fun root() { smartphone?.let { browse(it.rootId) } }
    suspend fun thumbnail(file:CameraFile): Bitmap? = withContext(Dispatchers.IO) {
        val key="${state.value.mode}:${file.id}"; thumbs.get(key)?.let { return@withContext it }
        try {
            val bytes = if(file.handle!=null) computer?.thumbnail(file.handle) else {
                val url=file.thumbnail ?: file.bestResource()?.url ?: return@withContext null
                http?.get(url)?.use { r ->
                    val source=requireNotNull(r.body).source()
                    require(!source.request(4L*1024*1024+1)) { "缩略图过大" }; source.readByteArray()
                }
            } ?: return@withContext null
            val options=BitmapFactory.Options().apply { inJustDecodeBounds=true }
            BitmapFactory.decodeByteArray(bytes,0,bytes.size,options)
            options.inSampleSize=(maxOf(options.outWidth,options.outHeight)/500).coerceAtLeast(1); options.inJustDecodeBounds=false
            BitmapFactory.decodeByteArray(bytes,0,bytes.size,options)?.also { thumbs.put(key,it) }
        } catch(e: CancellationException) { throw e } catch(_: Exception) { null }
    }
    fun enqueue(files:List<CameraFile>) {
        if(!state.value.connected) return
        val queue=state.value.queue.toMutableList()
        files.filter { !it.container }.forEach { file ->
            if(queue.none { it.file.id==file.id && it.state in listOf("等待","传输中") }) queue+=QueueItem(file)
        }
        update { copy(queue=queue) }
        if(queue.any { it.state=="等待" } && !state.value.transferring) {
            try { ContextCompat.startForegroundService(context,Intent(context,TransferService::class.java)) }
            catch(e:Exception) { update { copy(error="无法启动传输服务：${e.message}",queue=queue.map { if(it.state=="等待")it.copy(state="失败",error="传输服务未启动")else it }) } }
        }
    }
    suspend fun beginQueuedTransfers() {
        if(queueJob?.isActive==true) { queueJob?.join(); return }
        val job=scope.launch(Dispatchers.IO) {
            update { copy(transferring=true) }
            try {
                while(isActive) {
                    val item=state.value.queue.firstOrNull { it.state=="等待" }?:break
                    val file=item.file
                    fun progress(read:Long,total:Long) = update { copy(queue=queue.map { if(it===item || (it.file.id==file.id && it.state=="传输中")) it.copy(state="传输中",received=read,total=total) else it }) }
                    progress(0,file.size)
                    try {
                        val resource = if(file.handle==null) requireNotNull(file.bestResource()) { "相机没有提供 JPEG 资源" } else null
                        val isRaw=file.handle!=null && file.raw
                        val name=if(!isRaw && file.name.endsWith(".arw",true)) file.name.substringBeforeLast('.')+".JPG" else file.name
                        val source="${state.value.device?.model}|${state.value.mode}|${resource?.url?:file.id}"
                        val expected=resource?.size?:file.size
                        val existing=photos.list().firstOrNull { it.sourceId==source && (expected<=0||it.bytes==expected) && photos.exists(it) }
                        if(existing==null) photos.import(name,isRaw,expected,source,state.value.mode?.title.orEmpty()) { out ->
                            if(file.handle!=null) requireNotNull(computer).download(file,out,::progress)
                            else requireNotNull(http).get(requireNotNull(resource).url).use { response ->
                                downloadResponse=response
                                val body=requireNotNull(response.body)
                                val total=body.contentLength().takeIf { it>0 }?:expected
                                body.byteStream().use { input ->
                                    StreamCopy.copy(input,out,total,{ensureActive()},::progress)
                                }
                                downloadResponse=null
                            }
                        }
                        update { copy(queue=queue.map { if(it.file.id==file.id && it.state=="传输中") it.copy(state=if(existing!=null) "已存在" else "完成") else it },local=photos.list()) }
                    } catch(e: CancellationException) { throw e }
                    catch(e: Exception) {
                        log("下载 ${file.name} 失败：${e.message}")
                        update { copy(queue=queue.map { if(it.file.id==file.id && it.state=="传输中") it.copy(state="失败",error=e.message) else it }) }
                    }
                }
            } finally {
                update { copy(transferring=false,queue=queue.map { if(it.state in listOf("等待","传输中")) it.copy(state="已取消") else it }) }
            }
        }
        queueJob=job; job.join()
    }
    fun cancelTransfers() { queueJob?.cancel(); downloadResponse?.close(); downloadResponse=null; if(state.value.mode==CameraMode.COMPUTER) computer?.abort() }
    fun retryFailed() {
        val retry=state.value.queue.filter { it.state in listOf("失败","已取消") }.map { it.file }
        update { copy(queue=queue.filter { it.state !in listOf("失败","已取消") }) }; enqueue(retry)
    }
    fun clearCompleted() { update { copy(queue=queue.filter { it.state in listOf("等待","传输中") }) } }
    fun diagnostics(): String = JSONObject().apply {
        put("app","0.1.0-preview"); put("android",android.os.Build.VERSION.SDK_INT)
        put("device","${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
        put("cameraFirmwareUserProvided","4.01"); put("mode",state.value.mode?.name)
        put("camera",state.value.device?.model); put("description",state.value.device?.description?.toString())
        put("application",state.value.remote.application); put("apis",JSONArray(state.value.remote.apis.toList()))
        put("eventVersion",state.value.remote.eventVersion); put("logs",JSONArray(state.value.logs))
        synchronized(protocolResponses) { put("protocolResponses",JSONObject(protocolResponses.toMap())) }
        put("hardwareVerified",false)
    }.toString(2)
}
