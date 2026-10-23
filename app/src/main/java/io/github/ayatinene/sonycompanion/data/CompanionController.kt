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
    private val queues=TransferQueueStore(java.io.File(context.filesDir,"transfer-queues.json"))
    private val previews=PreviewCache(java.io.File(context.cacheDir,"previews"))
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
    private var identityJob: Job? = null
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
    private var lastUsbDescription = ""
    private var visible = true
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    init {
        scope.launch(Dispatchers.IO) {
            state.map { it.queueScope to it.queue.map { item -> item.copy(received=0) } }
                .distinctUntilChanged().collect { (key,queue) ->
                    if(key!=null) runCatching { queues.save(key,queue) }.onFailure {
                        log("保存下载队列：${it.message}",LogLevel.ERROR,"QUEUE",it)
                    }
                }
        }
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
    fun usbStatus(): String = UsbGuidRegistrar(context).description().also {
        lastUsbDescription=it; log(it,LogLevel.DEBUG,"USB")
    }
    fun registerUsbGuid(value: String, onRegistered: (String)->Unit) = action {
        require(!state.value.connected && !state.value.busy && !state.value.transferring) { "请先断开相机会话并结束传输" }
        val guid=value.trim().ifBlank { UUID.randomUUID().toString().replace("-","") }
        ComputerTransfer.parseGuid(guid)
        update { copy(busy=true,error=null,message="等待 USB 相机授权与登记…") }
        try {
            lastUsbDescription=UsbGuidRegistrar(context).description()
            val model=UsbGuidRegistrar(context) { log(it,tag="USB") }.register(guid)
            prefs.edit().putString("guid",guid).apply()
            onRegistered(guid)
            update { copy(message="已登记 $model。拔下 USB 后，将手机与相机接入同一 Wi-Fi，在相机选择“发送到电脑”。") }
            log("USB GUID 登记完成，接收端设置已保存",tag="USB")
        } finally { update { copy(busy=false) } }
    }
    fun connect(mode: CameraMode) {
        if(state.value.busy) return
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
                    val device=Device(model,model,java.net.URI("http://$host"),serial=transfer.serial,
                        identity=CameraIdentity(model=model,firmware=transfer.firmware))
                    attachQueue(device,mode)
                    update { copy(device=device,message="读取文件对象…") }
                    val files=transfer.files { count-> update { copy(message="已读取 $count 个文件…") } }
                    update { copy(connected=true,files=files,message="PTP/IP 已连接 · 尚未实机验证") }
                } else {
                    val devices = networks.discover(network,transport,mode==CameraMode.REMOTE,prefs.getString("description","").orEmpty().trim())
                    require(devices.size==1) {
                        if(devices.isEmpty()) "没有发现匹配服务。请确认相机工作模式、Wi-Fi 和附近设备权限；可在设置填写描述文件 URL。"
                        else "发现多个相机，请在设置填写目标相机的描述文件 URL"
                    }
                    val device=devices.single(); log("发现 ${device.model} ${device.description} remote=${device.remote} content=${device.content} push=${device.push}",tag="CONNECT")
                    attachQueue(device,mode)
                    update { copy(device=device) }
                    if(mode==CameraMode.REMOTE) {
                        val camera=RemoteCamera(requireNotNull(device.remote).toString(),transport) { message -> log(message,if(message.startsWith("RPC"))LogLevel.DEBUG else LogLevel.WARN,"RPC") }; remote=camera
                        val capabilities=camera.initialize()
                        update { copy(connected=true,remote=capabilities,message="遥控已连接 · ${device.displayName}") }
                        log("遥控连接成功 application=${capabilities.application} eventVersion=${capabilities.eventVersion} apis=${capabilities.apis.sorted()}",tag="CONNECT")
                        startMonitoring()
                    } else {
                        val transfer=SmartphoneTransfer(device,transport) { log(it,LogLevel.WARN,"BROWSE") }; smartphone=transfer
                        val files=transfer.initialize()
                        update { copy(connected=true,files=files,folder=transfer.rootId,message="JPEG 服务已连接 · ${transfer.rootId}") }
                        log("JPEG 连接成功 root=${transfer.rootId} entries=${files.size}",tag="CONNECT")
                    }
                    loadIdentity(device,transport)
                }
            } catch(e: CancellationException) { throw e }
            catch(e: Exception) {
                log("连接失败：${e.message}",LogLevel.ERROR,"CONNECT",e)
                withContext(Dispatchers.IO) { computer?.abort(); http?.close() }; computer=null; smartphone=null; remote=null; http=null
                update { copy(connected=false,error=e.message,message="连接失败") }
            } finally { update { copy(busy=false) } }
        }
    }
    private suspend fun attachQueue(device: Device, mode: CameraMode) {
        val key=queueScope(device,mode)
        val saved=withContext(Dispatchers.IO) { runCatching { queues.load(key) }.onFailure {
            log("读取下载队列：${it.message}",LogLevel.WARN,"QUEUE",it)
        }.getOrDefault(emptyList()) }
        update { copy(queueScope=key,queue=saved,queueNotice=if(device.cameraId==null)
            "相机未提供唯一身份；本次任务单独保存，不自动关联旧队列。" else
            if(saved.any { it.state !in setOf("完成","已存在") }) "已恢复此相机的队列，继续前会重新核对文件。" else "") }
        log("相机队列 camera=${device.displayName} identified=${device.cameraId!=null} entries=${saved.size}",tag="QUEUE")
    }
    private fun loadIdentity(device: Device, transport: CameraHttp) {
        val uri=device.digitalImaging ?: return
        identityJob?.cancel()
        identityJob=scope.launch {
            try {
                val identity=withTimeout(2500) { CameraXml.identity(transport.getText(uri.toString())) }
                val enriched=device.copy(identity=identity)
                update { if(connected && http===transport) copy(device=enriched) else this }
                log("相机信息 model=${identity.model} firmware=${identity.firmware} lens=${identity.lens} applications=${identity.applications}",LogLevel.DEBUG,"DEVICE")
            } catch(_: TimeoutCancellationException) { log("附加相机信息读取超时",LogLevel.DEBUG,"DEVICE") }
            catch(e: CancellationException) { throw e }
            catch(e: Exception) { log("附加相机信息：${e.message}",LogLevel.DEBUG,"DEVICE") }
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
                finally {
                    withContext(NonCancellable) {
                        try { withTimeout(2000) { camera.stopLiveView() } }
                        catch(e: Exception) { log("停止机内实时取景：${e.message}",LogLevel.WARN,"LIVEVIEW",e) }
                    }
                }
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
        queueJob?.cancelAndJoin(); queueJob=null
        state.value.let { current -> current.queueScope?.let { key -> withContext(Dispatchers.IO) {
            runCatching { queues.save(key,current.queue) }.onFailure { log("保存断开队列：${it.message}",LogLevel.WARN,"QUEUE",it) }
        } } }
        identityJob?.cancelAndJoin(); identityJob=null
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
    suspend fun cameraPreview(file: CameraFile): Bitmap? = withContext(Dispatchers.IO) { thumbnailGate.withPermit {
        val snapshot=state.value
        check(snapshot.connected) { "相机已断开" }
        check(!snapshot.transferring) { "文件正在传输，完成后可读取预览" }
        if(file.handle!=null && file.raw) {
            val data=requireNotNull(computer).thumbnail(file.handle)
            return@withContext PreviewBitmap.decode { data.inputStream() }
        }
        val transport=http
        val ptp=computer
        val resource=file.jpegResource(JpegSize.LARGE) ?: file.bestResource()
        val key="${snapshot.queueScope}|${file.parent}|${file.id}|${file.date}|${file.size}|${resource?.url}"
        val cached=previews.obtain(key) { output ->
            if(file.handle!=null) requireNotNull(ptp).download(file,output) { _,_-> }
            else requireNotNull(transport).stream(requireNotNull(resource) { "相机没有提供 JPEG 预览" }.url) { response ->
                val body=requireNotNull(response.body)
                val expected=body.contentLength().takeIf { it>0 } ?: resource.size
                body.byteStream().use { input -> StreamCopy.copy(input,output,expected,{ensureActive()}) { _,_-> } }
            }
        }
        check(state.value.queueScope==snapshot.queueScope && state.value.connected) { "相机会话已更换" }
        PreviewBitmap.decode { cached.inputStream() } ?: error("无法解码相机预览")
    } }
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
            if(queue.none { it.file.id==file.id && it.state in listOf("等待","传输中") }) {
                queue.removeAll { it.file.id==file.id && it.state !in listOf("等待","传输中") }
                queue+=QueueItem(file)
            }
        }
        update { copy(queue=queue) }
        if(queue.any { it.state=="等待" } && !state.value.transferring) {
            try { ContextCompat.startForegroundService(context,Intent(context,TransferService::class.java)) }
            catch(e:Exception) { update { copy(error="无法启动传输服务：${e.message}",queue=queue.map { if(it.state=="等待")it.copy(state="失败",error="传输服务未启动")else it }) } }
        }
    }
    suspend fun beginQueuedTransfers() {
        if(queueJob?.isActive==true) { queueJob?.join(); return }
        val snapshot=state.value
        if(!snapshot.connected || snapshot.queueScope==null) return
        val sessionScope=snapshot.queueScope
        val sessionHttp=http
        val sessionComputer=computer
        val job=scope.launch(Dispatchers.IO) {
            update { copy(transferring=true) }
            try {
                while(isActive) {
                    check(state.value.connected && state.value.queueScope==sessionScope) { "相机会话已更换，队列暂停" }
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
                        val source=transferSource(sessionScope,file,resource)
                        val expected=resource?.size?:file.size
                        val existing=photos.list().firstOrNull { it.sourceId==source && (expected<=0||it.bytes==expected) && photos.exists(it) }
                        log("开始 ${file.name} expected=$expected resource=${resource?.url ?: file.id}",tag="TRANSFER")
                        if(existing==null) {
                            // Only repeat safe file GETs. Every failed attempt removes its pending file.
                            val attempts=if(file.handle==null)3 else 1
                            for(attempt in 1..attempts) {
                                try {
                                    photos.import(name,isRaw,expected,source,state.value.mode?.title.orEmpty()) { out ->
                                        if(file.handle!=null) requireNotNull(sessionComputer).download(file,out,::progress)
                                        else requireNotNull(sessionHttp).stream(requireNotNull(resource).url) { response ->
                                            val body=requireNotNull(response.body)
                                            val total=body.contentLength().takeIf { it>0 }?:expected
                                            log("下载 ${file.name}：HTTP ${response.code}，长度 $total，编码 ${response.header("Transfer-Encoding") ?: "fixed"}，第 $attempt 次")
                                            body.byteStream().use { input -> StreamCopy.copy(input,out,total,{ensureActive()},::progress) }
                                        }
                                    }
                                    break
                                } catch(e: IOException) {
                                    if(attempt==attempts) throw e
                                    log("下载 ${file.name} 中断，重试 $attempt/${attempts-1}：${e.message}",LogLevel.WARN,"TRANSFER",e)
                                    progress(0,expected); delay(400L*attempt)
                                }
                            }
                        }
                        update { copy(queue=queue.map { if(it.file.id==file.id && it.state=="传输中") it.copy(state=if(existing!=null) "已存在" else "完成") else it },local=photos.list()) }
                        log("${file.name} ${if(existing!=null) "跳过有效本地文件" else "保存完成"}",tag="TRANSFER")
                    } catch(e: CancellationException) { throw e }
                    catch(e: Exception) {
                        log("下载 ${file.name} 失败：${e.javaClass.simpleName}: ${e.message}",LogLevel.ERROR,"TRANSFER",e)
                        update { copy(queue=queue.map { if(it.file.id==file.id && it.state=="传输中") it.copy(state="失败",error=e.message) else it }) }
                    }
                }
            } finally {
                update { copy(transferring=false,queue=queue.map { if(it.state in listOf("等待","传输中")) it.copy(state="已取消") else it }) }
                val current=state.value
                runCatching { queues.save(sessionScope,current.queue) }.onFailure { log("保存传输结果：${it.message}",LogLevel.WARN,"QUEUE",it) }
            }
        }
        queueJob=job; job.join()
    }
    fun cancelTransfers() {
        log("请求取消下载",tag="TRANSFER")
        queueJob?.cancel()
        if(state.value.mode==CameraMode.COMPUTER) { val transfer=computer; scope.launch(Dispatchers.IO) { transfer?.abort() } }
    }
    fun refreshLocal() = action {
        val local=withContext(Dispatchers.IO) { photos.pruneMissing() }
        update { copy(local=local) }
    }
    fun forgetPhoto(photo: LocalPhoto) = action {
        withContext(Dispatchers.IO) { photos.removeRecord(photo) }
        importedUrls.clear()
        update { copy(local=photos.list(),message="已移除导入记录，手机中的文件保留") }
    }
    fun exportDiagnostics(uri: Uri) = action {
        log("开始导出日志与诊断",tag="EXPORT")
        withContext(Dispatchers.IO) {
            val logs=journal.snapshot()
            val json=JSONObject(diagnostics()).put("logging",JSONObject().put("level",journal.minimum.name).put("session",journal.session)
                .put("dropped",logs.dropped).put("writeFailures",logs.writeFailures)).toString(2)
            requireNotNull(context.contentResolver.openOutputStream(uri,"wt")) { "无法打开诊断文件" }.use {
                DiagnosticBundle.write(it,json,logs,CrashLog.read(context.filesDir))
            }
        }
        log("日志与诊断导出完成",tag="EXPORT")
        update { copy(message="日志与诊断已导出") }
    }
    fun retryFailed() = action {
        if(!state.value.connected || state.value.transferring || state.value.busy) return@action
        val snapshot=state.value
        val retry=snapshot.queue.filter { it.state in listOf("失败","已取消","待恢复","需重新选择") }
        if(retry.isEmpty()) return@action
        update { copy(busy=true) }
        try {
            val folders=mutableMapOf<String,List<CameraFile>>()
            val refreshed=retry.associate { item ->
                val list=if(snapshot.mode==CameraMode.JPEG) folders.getOrPut(item.file.parent) {
                    requireNotNull(smartphone).browse(item.file.parent)
                } else snapshot.files
                item.file.id to refreshQueuedFile(item.file,list)
            }
            if(state.value.queueScope!=snapshot.queueScope || !state.value.connected) return@action
            val ready=retry.mapNotNull { refreshed[it.file.id] }
            update { copy(queue=queue.map { item -> if(item in retry && refreshed[item.file.id]==null)
                item.copy(state="需重新选择",error="当前文件信息不匹配或信息不足，请从相机图库重新选择。") else item }) }
            enqueue(ready)
        } finally { update { copy(busy=false) } }
    }
    fun clearCompleted() { update { copy(queue=queue.filter { it.state in listOf("等待","传输中") }) } }
    fun diagnostics(): String {
        val snapshot=state.value
        val previous=lastMode==snapshot.mode
        val device=snapshot.device ?: lastDevice.takeIf { previous }
        val remote=if(snapshot.remote.application.isNotBlank()) snapshot.remote else if(previous) lastRemote else snapshot.remote
        return JSONObject().apply {
            put("app",BuildConfig.VERSION_NAME); put("versionCode",BuildConfig.VERSION_CODE)
            put("exportedAt",java.time.OffsetDateTime.now().toString()); put("android",android.os.Build.VERSION.SDK_INT)
            put("device","${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            put("cameraFirmwareUserProvided","4.01"); put("mode",snapshot.mode?.name); put("connected",snapshot.connected)
            put("camera",device?.displayName); put("description",device?.description?.toString())
            put("cameraId",device?.cameraId); put("queueScope",snapshot.queueScope)
            put("cameraFirmwareReported",device?.identity?.firmware)
            put("lens",device?.identity?.lens); put("lensFirmware",device?.identity?.lensFirmware)
            put("installedCameraApplications",JSONArray(device?.identity?.applications.orEmpty().map { app -> JSONObject().put("name",app.name).put("version",app.version) }))
            put("application",remote.application); put("apis",JSONArray(remote.apis.toList()))
            put("eventVersion",remote.eventVersion); put("logs",JSONArray(snapshot.logs))
            put("queue",JSONArray(snapshot.queue.map { q -> JSONObject().apply {
                put("name",q.file.name); put("state",q.state); put("received",q.received); put("total",q.total); put("error",q.error)
                put("resource",q.file.bestResource()?.url)
            } }))
            synchronized(protocolResponses) { put("protocolResponses",JSONObject(protocolResponses.toMap())) }
            put("lastCrash",CrashLog.read(context.filesDir))
            put("usb",lastUsbDescription)
        }.toString(2)
    }
}
