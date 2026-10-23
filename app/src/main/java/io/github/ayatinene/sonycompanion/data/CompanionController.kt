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
import io.github.ayatinene.sonycompanion.BuildConfig
import java.io.IOException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.*
import java.text.SimpleDateFormat
import java.util.*

class CompanionController(private val context: Context) {
    val prefs = context.getSharedPreferences("settings",Context.MODE_PRIVATE)
    val photos = PhotoStore(context)
    val journal = DiagnosticLog(java.io.File(context.filesDir,"logs")).apply {
        minimum=runCatching { LogLevel.valueOf(prefs.getString("logLevel","DEBUG")!!) }.getOrDefault(LogLevel.DEBUG)
    }
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
    private val thumbnailGate = Semaphore(1)
    private var monitoringStop: Job? = null
    private var lastDevice: Device? = null
    private var lastRemote = RemoteState()
    private var lastMode: CameraMode? = null
    private val shutter = Mutex()
    private val thumbs = LruCache<String,Bitmap>(48)
    private val importedUrls = linkedSetOf<String>()
    private val protocolResponses = Collections.synchronizedMap(linkedMapOf<String,String>())
    private var selectedNetwork: Network? = null
    private var visible = true
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    init {
        log("应用启动 version=${BuildConfig.VERSION_NAME} sdk=${android.os.Build.VERSION.SDK_INT} device=${android.os.Build.MANUFACTURER}/${android.os.Build.MODEL}",tag="APP")
        cm.registerNetworkCallback(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),object: ConnectivityManager.NetworkCallback() {
            override fun onLost(network: Network) {
                if(network==selectedNetwork) scope.launch {
                    log("相机 Wi-Fi 丢失 network=$network",LogLevel.WARN,"CONNECT")
                    cancelTransfers(); disconnect(false)
                    update { copy(error="相机 Wi-Fi 已断开，请重新连接",message="连接已断开") }
                }
            }
        })
    }
    private fun update(block: AppState.()->AppState) { mutable.update { it.block() } }
    private fun log(message: String, level: LogLevel = LogLevel.INFO, tag: String = "APP", error: Throwable? = null) {
        val line=journal.record(level,tag,message,state.value.mode?.name ?: "OFFLINE",error) ?: return
        update { copy(logs=(logs+line.lineSequence().first().take(1500)).takeLast(250)) }
    }
    fun setLogLevel(level: LogLevel) {
        journal.minimum=level
        prefs.edit().putString("logLevel",level.name).apply()
        log("记录级别改为 $level",LogLevel.DEBUG,"LOGGER")
    }
    fun clearError() { update { copy(error=null) } }
    fun connect(mode: CameraMode) {
        if(state.value.transferring) { update { copy(error="请先完成或取消文件传输") }; return }
        if(connectionJob?.isActive==true) return
        connectionJob = scope.launch {
            disconnect()
            update { copy(mode=mode,busy=true,message="查找相机…",error=null,files=emptyList(),remote=RemoteState()) }
            log("开始连接 mode=$mode",tag="CONNECT")
            try {
                val networks = CameraNetwork(context)
                val network = networks.wifi(); selectedNetwork=network
                log("绑定 Wi-Fi network=$network",LogLevel.DEBUG,"CONNECT")
                val transport = CameraHttp(network,trace={ label,response ->
                    synchronized(protocolResponses) {
                        if(protocolResponses.size>=30 && label !in protocolResponses) protocolResponses.remove(protocolResponses.keys.first())
                        protocolResponses[label]=response.take(200_000)
                    }
                    log("$label\n${response.take(16000)}",LogLevel.TRACE,"PROTOCOL")
                },report={level,message,error->log(message,level,"HTTP",error)}); http=transport
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
                    val device=devices.single(); log("发现 ${device.model} ${device.description} remote=${device.remote} content=${device.content} push=${device.push}",tag="CONNECT")
                    update { copy(device=device) }
                    if(mode==CameraMode.REMOTE) {
                        val camera=RemoteCamera(requireNotNull(device.remote).toString(),transport) { message -> log(message,if(message.startsWith("RPC"))LogLevel.DEBUG else LogLevel.WARN,"RPC") }; remote=camera
                        val capabilities=camera.initialize()
                        update { copy(connected=true,remote=capabilities,message="遥控已连接 · ${device.model}") }
                        log("遥控连接成功 application=${capabilities.application} eventVersion=${capabilities.eventVersion} apis=${capabilities.apis.sorted()}",tag="CONNECT")
                        startMonitoring()
                    } else {
                        val transfer=SmartphoneTransfer(device,transport) { log(it,LogLevel.WARN,"BROWSE") }; smartphone=transfer
                        val files=transfer.initialize()
                        update { copy(connected=true,files=files,folder=transfer.rootId,message="JPEG 服务已连接 · ${transfer.rootId}") }
                        log("JPEG 连接成功 root=${transfer.rootId} entries=${files.size}",tag="CONNECT")
                    }
                }
            } catch(e: CancellationException) { throw e }
            catch(e: Exception) {
                log("连接失败：${e.message}",LogLevel.ERROR,"CONNECT",e)
                withContext(Dispatchers.IO) { computer?.abort(); http?.close() }; computer=null; smartphone=null; remote=null; http=null
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
                    failures++; log("状态查询失败 attempt=$failures：${e.message}",LogLevel.WARN,"EVENT",e)
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
                    requireNotNull(http).stream(url) {
                        val reader=LiveViewReader(requireNotNull(it.body).byteStream())
                        var frames=0L;var bytesRead=0L;var reported=System.nanoTime()
                        while(isActive) {
                            val bytes=reader.next()?:error("实时取景流结束")
                            val image=BitmapFactory.decodeByteArray(bytes,0,bytes.size)?:continue
                            frameState.value=image
                            frames++;bytesRead+=bytes.size
                            if(frames==1L) log("实时取景首帧 ${image.width}x${image.height}",tag="LIVEVIEW")
                            val now=System.nanoTime()
                            if(now-reported>=5_000_000_000L) {
                                log("实时取景 frames=$frames jpegBytes=$bytesRead",LogLevel.DEBUG,"LIVEVIEW");reported=now
                            }
                        }
                    }
                } catch(e: CancellationException) { throw e }
                catch(e: Exception) { attempts++; log("实时取景中断 ($attempts/3)：${e.message}",LogLevel.WARN,"LIVEVIEW",e); delay(1000L*attempts) }
            }
            if(attempts>=3) update { copy(error="实时取景已停止，请重连相机") }
        }
    }
    private fun stopMonitoring(): List<Job> {
        val jobs=listOfNotNull(liveJob,eventJob,intervalJob)
        liveJob=null; eventJob=null; intervalJob=null
        jobs.forEach(Job::cancel)
        return jobs
    }
    fun setVisible(value: Boolean) {
        visible=value
        log("可见状态 visible=$value connected=${state.value.connected} transferring=${state.value.transferring}",LogLevel.DEBUG,"LIFECYCLE")
        if(value) {
            scope.launch {
                monitoringStop?.join()
                if(visible && state.value.mode==CameraMode.REMOTE && state.value.connected) startMonitoring()
                refreshLocal()
            }
        } else {
            val jobs=stopMonitoring()
            monitoringStop=scope.launch { jobs.joinAll() }
        }
    }
    suspend fun disconnect(finish: Boolean = true) {
        val snapshot=state.value
        log("断开开始 finish=$finish connected=${snapshot.connected}",LogLevel.DEBUG,"CONNECT")
        if(snapshot.device!=null) { lastDevice=snapshot.device; lastRemote=snapshot.remote; lastMode=snapshot.mode }
        stopMonitoring().joinAll(); monitoringStop?.join(); frameState.value=null
        val oldHttp=http; val oldComputer=computer; val oldSmartphone=smartphone
        computer=null; remote=null; smartphone=null; http=null; selectedNetwork=null
        withContext(NonCancellable+Dispatchers.IO) {
            if(finish) {
                try { withTimeout(3000) { oldSmartphone?.finish(); oldComputer?.finish() } }
                catch(e: Exception) { log("结束会话：${e.message}",LogLevel.WARN,"CONNECT",e) }
            }
            runCatching { oldComputer?.abort() }.onFailure { log("关闭原文件会话：${it.message}",LogLevel.WARN,"CONNECT",it) }
            oldHttp?.close()
        }
        thumbs.evictAll(); importedUrls.clear()
        update { copy(connected=false,files=emptyList(),device=null,remote=RemoteState(),intervalRemaining=0,message="已断开") }
        log("断开完成",LogLevel.DEBUG,"CONNECT")
    }
    fun endSession() { if(!state.value.transferring) action { disconnect() } }
    private fun action(block: suspend ()->Unit) { scope.launch {
        try { block() } catch(e: CancellationException) { throw e } catch(e: Exception) { log(e.message.orEmpty(),LogLevel.ERROR,"ACTION",e); update { copy(error=e.message) } }
    } }
    fun setSetting(key:String,value:String) = action {
        log("设置 $key=$value",tag="CONTROL")
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
        log("请求拍照",tag="CAPTURE")
        val urls=camera.takePicture()
        log("拍照返回 previews=${urls.size}",tag="CAPTURE")
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
        try {
            log("浏览目录 id=$id",LogLevel.DEBUG,"BROWSE")
            val files=requireNotNull(smartphone).browse(id); update { copy(files=files,folder=id) }
            log("目录返回 id=$id entries=${files.size}",LogLevel.DEBUG,"BROWSE")
        }
        finally { update { copy(busy=false) } }
    }
    fun root() { smartphone?.let { browse(it.rootId) } }
    suspend fun thumbnail(file:CameraFile): Bitmap? = withContext(Dispatchers.IO) { thumbnailGate.withPermit {
        val key="${state.value.mode}:${file.id}"; thumbs.get(key)?.let { return@withContext it }
        try {
            val bytes = if(file.handle!=null) computer?.thumbnail(file.handle) else {
                val url=file.thumbnail ?: file.bestResource()?.url ?: return@withContext null
                http?.stream(url) { r ->
                    val source=requireNotNull(r.body).source()
                    require(!source.request(4L*1024*1024+1)) { "缩略图过大" }; source.readByteArray()
                }
            } ?: return@withContext null
            val options=BitmapFactory.Options().apply { inJustDecodeBounds=true }
            BitmapFactory.decodeByteArray(bytes,0,bytes.size,options)
            options.inSampleSize=(maxOf(options.outWidth,options.outHeight)/500).coerceAtLeast(1); options.inJustDecodeBounds=false
            BitmapFactory.decodeByteArray(bytes,0,bytes.size,options)?.also { thumbs.put(key,it) }
        } catch(e: CancellationException) { throw e } catch(_: Exception) { null }
    } }
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
                    var lastProgress=0L
                    fun progress(read:Long,total:Long) {
                        update { copy(queue=queue.map { if(it===item || (it.file.id==file.id && it.state=="传输中")) it.copy(state="传输中",received=read,total=total) else it }) }
                        val now=System.nanoTime()
                        if(read>0 && (read==total || now-lastProgress>=2_000_000_000L)) {
                            log("${file.name} bytes=$read/$total",LogLevel.DEBUG,"TRANSFER");lastProgress=now
                        }
                    }
                    progress(0,file.size)
                    try {
                        val resource = if(file.handle==null) requireNotNull(file.bestResource()) { "相机没有提供 JPEG 资源" } else null
                        val isRaw=file.handle!=null && file.raw
                        val name=if(!isRaw && file.name.endsWith(".arw",true)) file.name.substringBeforeLast('.')+".JPG" else file.name
                        val source="${state.value.device?.model}|${state.value.mode}|${resource?.url?:file.id}"
                        val expected=resource?.size?:file.size
                        val existing=photos.list().firstOrNull { it.sourceId==source && (expected<=0||it.bytes==expected) && photos.exists(it) }
                        log("开始 ${file.name} expected=$expected resource=${resource?.url ?: file.id}",tag="TRANSFER")
                        if(existing==null) {
                            // Only repeat safe file GETs. Every failed attempt removes its pending file.
                            val attempts=if(file.handle==null)3 else 1
                            for(attempt in 1..attempts) {
                                try {
                                    photos.import(name,isRaw,expected,source,state.value.mode?.title.orEmpty()) { out ->
                                        if(file.handle!=null) requireNotNull(computer).download(file,out,::progress)
                                        else requireNotNull(http).stream(requireNotNull(resource).url) { response ->
                                            val body=requireNotNull(response.body)
                                            val total=body.contentLength().takeIf { it>0 }?:expected
                                            log("下载 ${file.name}：HTTP ${response.code}，长度 $to…1690 tokens truncated…n, manual: String, computer: Boolean = false): List<Device> = withContext(Dispatchers.IO) {
        val wifi = context.getSystemService(WifiManager::class.java)
        val lock = wifi.createMulticastLock("alpha-discovery").apply { setReferenceCounted(false); acquire() }
        val locations = linkedSetOf<URI>()
        try {
            if (manual.startsWith("http://")) {
                val uri=URI(manual)
                val device=CameraXml.description(http.getText(uri.toString()),uri)
                require(if(remote)device.remote!=null else device.content!=null) { "描述文件未提供所选模式服务" }
                return@withContext listOf(device)
            }
            DatagramSocket(null).use { socket ->
                socket.reuseAddress = true
                socket.bind(InetSocketAddress(0)); network.bindSocket(socket)
                val targets = if (remote) listOf("urn:schemas-sony-com:service:ScalarWebAPI:1") else if(computer) listOf("urn:microsoft-com:service:MtpNullService:1") else listOf("ssdp:all")
                repeat(2) {
                    targets.forEach { st ->
                        val data = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 1\r\nST: $st\r\n\r\n".toByteArray()
                        socket.send(DatagramPacket(data, data.size, InetSocketAddress("239.255.255.250",1900)))
                    }
                    delay(120)
                }
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4)
                while (System.nanoTime() < deadline) {
                    currentCoroutineContext().ensureActive()
                    socket.soTimeout = 400
                    val packet = DatagramPacket(ByteArray(8192),8192)
                    try {
                        socket.receive(packet)
                        CameraXml.location(String(packet.data,0,packet.length))?.let(locations::add)
                    } catch (_: SocketTimeoutException) { }
                }
            }
            val host = manual.takeIf { !it.contains("://") && it.isNotBlank() } ?: gateway(network)
            val fallback = if (!remote && !computer && host != null) URI("http://$host:64321/DmsDescPush.xml") else null
            resolveCameraDescriptions(locations.toList(), fallback, remote, computer) { uri -> http.getText(uri.toString()) }
        } finally { if(lock.isHeld) lock.release() }
    }
}

/** SSDP describes the active service. A legacy descriptor can exist even when its service is inactive. */
suspend fun resolveCameraDescriptions(
    advertised: List<URI>, fallback: URI?, remote: Boolean, computer: Boolean = false,
    read: suspend (URI) -> String
): List<Device> {
    suspend fun resolve(locations: List<URI>): List<Device> = locations.distinct().mapNotNull { uri ->
        try {
            val xml = read(uri)
            if (computer && (!xml.contains("MtpNullService") || !xml.contains("Sony", true))) null
            else CameraXml.description(xml, uri).takeIf {
                if (computer) true else if (remote) it.remote != null else it.content != null
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { null }
    }.distinctBy { if (computer) it.description.host else if (remote) it.remote else it.content }

    val active = resolve(advertised)
    if (active.isNotEmpty()) return active
    return if (fallback != null && fallback !in advertised) resolve(listOf(fallback)) else emptyList()
}

class CameraHttpError(val code: Int) : java.io.IOException("相机 HTTP $code")

/** All camera HTTP uses the selected Wi-Fi; mobile data stays available to other apps. */
class CameraHttp(network: Network? = null, private val trace: (String,String)->Unit = {_,_->},
    private val report: (LogLevel,String,Throwable?)->Unit = {_,_,_->}) : Closeable {
    companion object { private val requestIds=AtomicLong() }
    private val calls = ConcurrentHashMap.newKeySet<Call>()
    val client = OkHttpClient.Builder().apply {
        if (network != null) { socketFactory(network.socketFactory); dns(object:Dns { override fun lookup(hostname:String) = network.getAllByName(hostname).toList() }) }
        connectTimeout(5,TimeUnit.SECONDS); readTimeout(65,TimeUnit.SECONDS)
        followRedirects(false); retryOnConnectionFailure(false)
    }.build()
    private suspend fun execute(call: Call, allowSoapFault: Boolean): Response = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object:Callback {
            override fun onFailure(call:Call,e:java.io.IOException) {
                if(continuation.isActive) continuation.resumeWithException(e)
            }
            override fun onResponse(call:Call,response:Response) {
                if(!response.isSuccessful && !(allowSoapFault&&response.code==500)) {
                    val code=response.code;response.close()
                    if(continuation.isActive) continuation.resumeWithException(CameraHttpError(code))
                } else continuation.resume(response) { _,value,_->value.close() }
            }
        })
    }
    // Cancellation must cancel the Call, not close a Response concurrently with its reader.
    // Keep cancellation armed until the complete body has been consumed, not just the headers.
    private suspend fun <T> exchange(request: Request, allowSoapFault: Boolean = false, operation: String = request.method, read: suspend (Response)->T): T =
        withContext(Dispatchers.IO) { coroutineScope {
            val call=client.newCall(request); calls += call
            val id=requestIds.incrementAndGet();val start=System.nanoTime()
            fun elapsed()=(System.nanoTime()-start)/1_000_000
            report(LogLevel.DEBUG,"HTTP #$id → $operation ${request.url}",null)
            val cancellation=launch(start=CoroutineStart.UNDISPATCHED) {
                suspendCancellableCoroutine<Unit> { it.invokeOnCancellation { call.cancel() } }
            }
            try { execute(call,allowSoapFault).use { response ->
                report(LogLevel.DEBUG,"HTTP #$id ← ${response.code} headers=${elapsed()}ms length=${response.body?.contentLength()} encoding=${response.header("Transfer-Encoding") ?: "fixed"}",null)
                read(response).also { report(LogLevel.DEBUG,"HTTP #$id complete total=${elapsed()}ms",null) }
            } }
            catch(e: Exception) {
                if(!currentCoroutineContext().isActive) {
                    report(LogLevel.DEBUG,"HTTP #$id cancelled total=${elapsed()}ms",null)
                    currentCoroutineContext().ensureActive()
                }
                report(LogLevel.WARN,"HTTP #$id failed total=${elapsed()}ms $operation ${request.url}",e)
                throw e
            }
            finally { cancellation.cancel(); calls -= call }
        } }
    suspend fun <T> stream(url: String, read: suspend (Response)->T): T = exchange(
        Request.Builder().url(url).header("Accept-Encoding","identity").header("Connection","close").get().build(),read=read)
    suspend fun getText(url: String): String = stream(url) { r ->
        val source = requireNotNull(r.body).source(); val out = okio.Buffer()
        var total = 0L
        while (true) { val n = source.read(out,8192); if(n < 0) break; total += n; require(total <= 8*1024*1024) { "响应过大" } }
        out.readUtf8().also { trace("GET $url",it) }
    }
    suspend fun post(url: String, body: String, mime: String = "application/json; charset=utf-8", headers: Map<String,String> = emptyMap(), allowSoapFault: Boolean = false): String {
        val request = Request.Builder().url(url).header("Connection","close").post(body.toRequestBody(mime.toMediaType())).apply { headers.forEach { (k,v) -> header(k,v) } }.build()
        val operation=headers["SOAPACTION"] ?: runCatching { org.json.JSONObject(body).let { "${it.optString("method")} rpcId=${it.optInt("id")}" } }.getOrDefault("POST")
        return exchange(request,allowSoapFault,operation) { response ->
            val source = requireNotNull(response.body).source()
            require(!source.request(8L*1024*1024+1)) { "响应过大" }
            source.readUtf8().also { trace("POST $url ${headers["SOAPACTION"] ?: runCatching { org.json.JSONObject(body).optString("method") }.getOrDefault("")}",it) }
        }
    }
    override fun close() { calls.forEach(Call::cancel); client.dispatcher.cancelAll(); client.connectionPool.evictAll() }
}
