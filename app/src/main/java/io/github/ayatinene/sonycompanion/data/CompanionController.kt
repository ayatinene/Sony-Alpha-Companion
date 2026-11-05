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
import kotlin.coroutines.coroutineContext
import io.github.ayatinene.sonycompanion.BuildConfig
import java.io.IOException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.*
import java.text.SimpleDateFormat
import java.util.*

class CompanionController(private val context: Context) {
    companion object {
        private const val SERVICE_READY_TIMEOUT_MILLIS = 5_000L
        private const val INIT_FINISH_TIMEOUT_MILLIS = 4_000L
        private const val SESSION_END_TIMEOUT_MILLIS = 4_000L
        private const val SONY_SLEEP_PREF = "sonySleepOnDisconnect"
        private const val REMOTE_GEOTAGGING_PREF = "remoteGeotagging"
        private const val GALLERY_SWITCH_TIMEOUT_MILLIS = 12_000L
        private const val GALLERY_POLL_INTERVAL_MILLIS = 400L
        private const val GALLERY_MONITORING_JOIN_TIMEOUT_MILLIS = 3_000L
        /** Sony reports 7 (NotAvailableNow) while the camera is not ready; a bounded retry waits it out. */
        private const val AV_CONTENT_ERROR_NOT_AVAILABLE_NOW = 7
    }
    val prefs = context.getSharedPreferences("settings",Context.MODE_PRIVATE)
    val photos = PhotoStore(context)
    /** Samples the platform provider only while foregrounded in a REMOTE session. */
    var locationSource: LocationSource = GeotagSource(PlatformLocationFixProvider(context))
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
    private var scanJob: Job? = null
    private var galleryJob: Job? = null
    /** The same-connection avContent client and its current source/pagination, live only in gallery. */
    private var avContent: AvContentTransfer? = null
    private var avContentEndpoint: String? = null
    private var avContentSource: String? = null
    private var galleryPager: GalleryPager? = null
    /** Bumped on every monitoring start / mode switch so an old same-connection loop cannot publish. */
    @Volatile private var monitorEpoch = 0L
    private val identity = SessionIdentity()
    @Volatile private var userScanPaused = false
    private val readiness = ReadinessLatch()
    /** Abort handles for the connection attempt currently in flight. Replaced per attempt so an old
     * attempt's cleanup can never release a newer attempt's resources. */
    private var pendingConnection = PendingResources()
    /** Session-bound control jobs, cancelled before a disconnect/cancel joins anything. */
    private val sessionActions = SessionActions()
    /** Test seam: when set, connect() uses this transport and device list instead of Wi-Fi discovery.
     * Null in production. */
    class SessionOverride(val transport: CameraHttp, val devices: List<Device>)
    var sessionOverride: SessionOverride? = null
    /** Immutable snapshot of one session's generation, resources and jobs, taken atomically under the
     * identity lock before any suspension, so cleanup only ever touches the session it detached. */
    private class DetachedSession(
        val gen: Long, val snapshot: AppState,
        val http: CameraHttp?, val computer: ComputerTransfer?, val smartphone: SmartphoneTransfer?, val remote: RemoteCamera?,
        val queue: Job?, val identity: Job?, val scan: Job?, val gallery: Job?, val monitoring: List<Job>, val monitoringStop: Job?,
        val ownConnection: Job?, val attempt: PendingResources, val actions: List<Job>, val effectiveGraceful: Boolean)
    private val backgroundActive = MutableStateFlow(false)
    /** True while any scan or download holds a background lease. The service observes this instead of
     * inferring from scan/transferring, so a pending start is never torn down before it becomes ready. */
    val backgroundActiveFlow: StateFlow<Boolean> = backgroundActive.asStateFlow()
    private val background = BackgroundOwnership { active, reason ->
        log("后台所有权 ${if(active) "获取" else "释放"} reason=$reason scan=${state.value.scan.phase} transferring=${state.value.transferring} generation=${identity.currentGeneration}",LogLevel.DEBUG,"FGS")
        backgroundActive.value = active
        if(active) requestBackgroundStart()
    }
    private val thumbnailGate = Semaphore(1)
    private var monitoringStop: Job? = null
    private var lastDevice: Device? = null
    private var lastRemote = RemoteState()
    private var lastMode: CameraMode? = null
    private val shutter = Mutex()
    /** True while a capture (RPC or interval) holds the shutter, including a long exposure. The gallery
     * entry is refused on this signal because an in-flight exposure must never be interrupted. */
    @Volatile private var captureInFlight = false
    private val thumbs = LruCache<String,Bitmap>(48)
    private val importedUrls = linkedSetOf<String>()
    /** Freezes the per-capture GPS decision (including null) and defers camera-body postviews while an
     *  RPC capture is unresolved, so an event URL can never steal or inherit a capture's fix. */
    private val captureBinding = CaptureGeoBinding()
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
        // Foreground sampling follows the live session: it stops on disconnect, on hiding and on disable.
        scope.launch {
            state.map { it.mode to it.connected }.distinctUntilChanged().collect { applyLocationSampling() }
        }
        cm.registerNetworkCallback(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),object: ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                log("Wi-Fi 可用 network=$network",LogLevel.DEBUG,"CONNECT")
            }
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
    /** Monotonic session identity. Bumped on connect/disconnect/reconnect so stale callbacks and
     * previews can never write into a newer session. Never derived from queueScope (same camera
     * reuses the same scope). */
    private fun nextGeneration(): Long = identity.bumpGeneration { value ->
        update { copy(sessionGeneration=value) }
        log("会话代次 generation=$value",LogLevel.DEBUG,"CONNECT")
        value
    }
    /** Runs [block] only while [gen] is still the live generation, atomically with the identity lock. */
    private fun <T> whenGeneration(gen: Long, block: () -> T): T? =
        identity.locked { if(identity.currentGeneration==gen) block() else null }
    private fun requestBackgroundStart() {
        readiness.begin()
        try { ContextCompat.startForegroundService(context,Intent(context,TransferService::class.java).setAction("start")) }
        catch(e: Exception) {
            // A rejected foreground start must never hang the UI or pretend background work is safe.
            log("无法启动后台服务：${e.javaClass.simpleName}: ${e.message}",LogLevel.ERROR,"FGS",e)
            readiness.fail(e)
        }
    }
    /** Bounded wait for the service's real `startForeground`. False means the caller must cancel the
     * work and invalidate the session rather than continue without foreground ownership. */
    private suspend fun awaitBackgroundReady(): Boolean {
        val ready=readiness.await(SERVICE_READY_TIMEOUT_MILLIS)
        if(!ready) log("后台服务未就绪，取消本次扫描/下载",LogLevel.ERROR,"FGS")
        return ready
    }
    fun onServiceReady() { readiness.succeed() }
    fun onBackgroundServiceRejected(reason: String?) {
        log("后台服务被系统拒绝：${reason ?: "未知原因"}；扫描/下载仅在前台可靠运行",LogLevel.WARN,"FGS")
        update { copy(message="后台受限：请保持应用在前台完成扫描或下载") }
        readiness.fail(IllegalStateException("后台服务被系统拒绝：${reason ?: "未知原因"}"))
        // Drop all leases so no awaiting/queued work keeps claiming background ownership.
        background.reset("rejected")
    }
    fun setScanPaused(paused: Boolean) {
        if(state.value.mode!=CameraMode.COMPUTER) return
        userScanPaused=paused
        log(if(paused) "暂停扫描" else "继续扫描",LogLevel.INFO,"SCAN")
        update { copy(scan=scan.copy(paused=userScanPaused||transferring)) }
    }
    private suspend fun awaitScanResume(isCurrent: () -> Boolean) {
        while(isCurrent() && !scanShouldRun(userScanPaused,state.value.transferring)) delay(50)
    }
    fun rescan() {
        if(state.value.mode!=CameraMode.COMPUTER) return
        if(state.value.transferring || queueJob?.isActive==true) { update { copy(message="正在下载文件，完成后可重新扫描") }; return }
        val transfer=computer
        if(!state.value.connected || transfer==null || !transfer.alive) { reconnect(); return }
        log("重新扫描相机文件",LogLevel.INFO,"SCAN")
        startScan()
    }
    /** The COMPUTER session is established before the scan: gallery entry never waits for the full
     * listing. The scan runs independently and publishes immutable snapshots. */
    private fun startScan() {
        val transfer=computer ?: return
        val snapshot=state.value
        if(snapshot.mode!=CameraMode.COMPUTER || !snapshot.connected) return
        val token=identity.nextScanRun()
        scanJob?.cancel()
        scanJob=scope.launch(Dispatchers.IO) {
            val lease=background.acquire("scan")
            try {
                // The service must actually reach startForeground before any PTP listing begins.
                if(!awaitBackgroundReady()) {
                    identity.whenCurrent(token) {
                        if(computer===transfer) {
                            runCatching { transfer.abort() }
                            computer=null
                            update { copy(connected=false,scan=ScanState(ScanPhase.Failed,error="后台服务不可用，已停止扫描"),
                                error="后台服务不可用，请保持应用在前台后重试",message="后台服务不可用，已停止扫描") }
                        }
                    }
                    return@launch
                }
                if(!identity.isCurrent(token) || computer!==transfer) return@launch
                identity.whenCurrent(token) { update { copy(scan=ScanState(ScanPhase.Scanning,paused=userScanPaused),files=emptyList(),message="已连接 · 正在读取相机文件…") } }
                val started=System.currentTimeMillis()
                fun current()=identity.isCurrent(token) && computer===transfer
                var lastLog=0L
                try {
                    val outcome=ScanEngine(transfer).scan(
                        isCurrent={current()},
                        awaitResume={awaitScanResume { current() }},
                        onBatch={scanned,total,found,files ->
                            identity.whenCurrent(token) {
                                if(computer===transfer) {
                                    update { copy(scan=scan.copy(phase=ScanPhase.Scanning,scanned=scanned,total=total,found=found),files=files) }
                                    val now=System.currentTimeMillis()
                                    if(scanned==0 || now-lastLog>=1000L) {
                                        log("扫描进度 $scanned/$total found=$found",LogLevel.DEBUG,"SCAN");lastLog=now
                                    }
                                }
                            }
                        })
                    if(!current()) return@launch
                    identity.whenCurrent(token) { update { copy(scan=ScanState(ScanPhase.Done,scanned=outcome.scanned,total=outcome.total,found=outcome.found),
                        files=outcome.files,message="已连接 · 原文件传输 · ${outcome.found} 个文件") } }
                    log("扫描完成 objects=${outcome.total} files=${outcome.found} 耗时=${System.currentTimeMillis()-started}ms",LogLevel.INFO,"SCAN")
                } catch(e: CancellationException) { throw e }
                catch(e: Exception) {
                    if(!current()) return@launch
                    val known=state.value.files
                    log("扫描中断：${e.javaClass.simpleName}: ${e.message}",LogLevel.ERROR,"SCAN",e)
                    if(!transfer.alive) {
                        // A partial PTP transaction aborted the transport: the session is unusable.
                        identity.whenCurrent(token) {
                            if(computer===transfer) computer=null
                            update { copy(connected=false,scan=ScanState(ScanPhase.Failed,scanned=state.value.scan.scanned,
                                total=state.value.scan.total,found=known.size,error=e.message),
                                files=known,error="读取中断，已发现 ${known.size} 个文件，请重新连接",
                                message="读取中断，已发现 ${known.size} 个文件，请重新连接") }
                        }
                    } else {
                        // A complete protocol failure (e.g. one unreadable object) keeps the session
                        // usable: stop the scan but leave the connection for downloads/retry.
                        identity.whenCurrent(token) { update { copy(scan=ScanState(ScanPhase.Failed,scanned=state.value.scan.scanned,
                            total=state.value.scan.total,found=known.size,error=e.message),
                            files=known,message="读取中断：${e.message}") } }
                    }
                }
            } finally { lease.release() }
        }
    }
    fun cancelBackgroundWork() {
        val snapshot=state.value
        log("取消后台任务 scan=${snapshot.scan.phase} transferring=${snapshot.transferring}",LogLevel.INFO,"FGS")
        // Forced: detachSession closes the transports before cancelling monitoring, so no cleanup RPC
        // is sent, then we join only the detached jobs.
        val d=detachSession(snapshot,null,gracefulRequested=false)
        runCatching { d.attempt.abortAll() }
        val cancelled=snapshot.scan.copy(phase=ScanPhase.Failed,error="已取消",paused=false)
        val queue=snapshot.queue.map { if(it.state in listOf("等待","传输中")) it.copy(state="已取消") else it }
        // Clear busy/connecting too: the cancelled connect job's own finally is generation-guarded and
        // would otherwise skip, leaving the UI stuck busy forever.
        whenGeneration(d.gen) { update { copy(connected=false,connecting=false,busy=false,transferring=false,scan=cancelled,queue=queue,
            remote=RemoteState(),intervalRemaining=0,
            message=if(snapshot.mode==CameraMode.COMPUTER) "已取消，原文件会话已断开，请重新连接" else "已取消后台任务") } }
        background.reset("cancel")
        scope.launch(Dispatchers.IO) {
            withContext(NonCancellable) {
                runCatching { sessionActions.join(d.actions) }
                runCatching { d.scan?.join() }
                runCatching { d.queue?.join() }
                runCatching { d.identity?.join() }
                runCatching { d.gallery?.join() }
                runCatching { d.monitoring.joinAll() }
                runCatching { d.monitoringStop?.join() }
                whenGeneration(d.gen) { frameState.value=null }
            }
        }
    }
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
    fun connect(mode: CameraMode): Boolean {
        if(state.value.busy) return false
        if(state.value.transferring) { update { copy(error="请先完成或取消文件传输") }; return false }
        if(connectionJob?.isActive==true) return false
        connectionJob = scope.launch {
            var gen = identity.currentGeneration
            try {
                disconnect()
                gen = identity.currentGeneration
                val attempt = PendingResources()
                pendingConnection = attempt
                var remoteAttempt: RemoteCamera? = null
                var smartphoneAttempt: SmartphoneTransfer? = null
                update { copy(connecting=true,mode=mode,busy=true,message="查找相机…",error=null,files=emptyList(),folder="",galleryPath=emptyList(),remote=RemoteState(),scan=ScanState()) }
                log("开始连接 mode=$mode",tag="CONNECT")
                try {
                    // Test seam: a local transport + device list lets lifecycle tests drive a real
                    // controller against a local server without a camera or Wi-Fi.
                    val testOverride=sessionOverride
                    val networks = CameraNetwork(context)
                    val network = if(testOverride==null) networks.wifi() else null; selectedNetwork=network
                    log("绑定 Wi-Fi network=$network",LogLevel.DEBUG,"CONNECT")
                    val transport = testOverride?.transport ?: CameraHttp(network,trace={ label,response ->
                        synchronized(protocolResponses) {
                            if(protocolResponses.size>=30 && label !in protocolResponses) protocolResponses.remove(protocolResponses.keys.first())
                            protocolResponses[label]=response.take(200_000)
                        }
                        log("$label\n${response.take(16000)}",LogLevel.TRACE,"PROTOCOL")
                    },report={level,message,error->log(message,level,"HTTP",error)})
                    // Register the real closer so an external disconnect can unblock the blocking
                    // PTP/HTTP calls even though cancelling a coroutine cannot interrupt them.
                    attempt.register { transport.close() }
                    if(identity.currentGeneration!=gen) { attempt.abortAll(); return@launch }
                    if(mode==CameraMode.COMPUTER) {
                        require(testOverride==null) { "测试覆盖不支持原文件模式" }
                        val computerNetwork=requireNotNull(network) { "尚未连接 Wi-Fi" }
                        val guid=prefs.getString("guid","").orEmpty()
                        ComputerTransfer.parseGuid(guid)
                        val host=prefs.getString("computerHost","").orEmpty().trim().ifBlank {
                            val devices=networks.discover(computerNetwork,transport,false,"",computer=true)
                            require(devices.size==1) {
                                if(devices.isEmpty()) "未找到相机，请检查与相机是否在同一局域网，或在设置填写相机 IP。"
                                else "发现多个相机，请在设置中填写目标相机 IP。"
                            }
                            devices.single().description.host
                        }
                        val transfer=ComputerTransfer(computerNetwork) { level,message,error -> log(message,level,"PTP",error) }
                        attempt.register { transfer.abort() }
                        val model=transfer.connect(host,guid)
                        if(identity.currentGeneration!=gen) { attempt.abortAll(); return@launch }
                        val device=Device(model,model,java.net.URI("http://$host"),serial=transfer.serial,
                            identity=CameraIdentity(model=model,firmware=transfer.firmware))
                        if(whenGeneration(gen) { http=transport; computer=transfer; true } != true) { attempt.abortAll(); return@launch }
                        attachQueue(device,mode,gen)
                        if(identity.currentGeneration!=gen) {
                            attempt.abortAll()
                            if(computer===transfer) computer=null
                            if(http===transport) http=null
                            return@launch
                        }
                        val published=whenGeneration(gen) {
                            update { copy(device=device,connected=true,files=emptyList(),scan=ScanState(ScanPhase.Scanning),message="已连接 · 正在读取相机文件…") }
                            attempt.release()
                            true
                        }
                        if(published!=true) {
                            attempt.abortAll()
                            if(computer===transfer) computer=null
                            if(http===transport) http=null
                            return@launch
                        }
                        log("原文件会话已建立，开始后台扫描 generation=$gen",tag="CONNECT")
                        startScan()
                    } else {
                        val devices = testOverride?.devices
                            ?: networks.discover(network!!,transport,mode==CameraMode.REMOTE,prefs.getString("description","").orEmpty().trim())
                        if(identity.currentGeneration!=gen) { attempt.abortAll(); return@launch }
                        require(devices.size==1) {
                            if(devices.isEmpty()) "没有发现相机。请确认相机工作模式、Wi-Fi 和附近设备权限，或在设置填写描述文件 URL。"
                            else "发现多个相机，请在设置填写目标相机的描述文件 URL"
                        }
                        val device=devices.single(); log("发现 ${device.model} ${device.description} remote=${device.remote} content=${device.content} push=${device.push}",tag="CONNECT")
                        val scope=if(mode==CameraMode.REMOTE) avContentQueueScope(device) else queueScope(device,mode)
                        attachQueue(device,mode,gen,scope)
                        if(identity.currentGeneration!=gen) { attempt.abortAll(); return@launch }
                        if(whenGeneration(gen) { http=transport; true } != true) { attempt.abortAll(); return@launch }
                        whenGeneration(gen) { update { copy(device=device) } }
                        if(mode==CameraMode.REMOTE) {
                            var cameraRef: RemoteCamera? = null
                            val camera=RemoteCamera(requireNotNull(device.remote).toString(),transport,
                                stillOwned={ remote===cameraRef }) { message -> log(message,if(message.startsWith("RPC"))LogLevel.DEBUG else LogLevel.WARN,"RPC") }
                            cameraRef=camera; remoteAttempt=camera
                            if(whenGeneration(gen) { remote=camera; true } != true) { attempt.abortAll(); return@launch }
                            val capabilities=camera.initialize()
                            // Dynamic capability gate: the descriptor advertises avContent AND the camera
                            // confirms setCameraFunction with getSupportedCameraFunction + method version.
                            avContentEndpoint=device.avContent?.toString()?.takeIf { it.isNotBlank() }
                            val canBrowse=if(avContentEndpoint!=null) try { camera.gallerySupported() }
                                catch(e: CancellationException) { throw e }
                                catch(e: Exception) { log("图库能力探测失败：${e.message}",LogLevel.DEBUG,"GALLERY"); false } else false
                            val published=whenGeneration(gen) {
                                update { copy(connected=true,remote=capabilities.copy(canBrowseGallery=canBrowse),message="遥控已连接 · ${device.displayName}") }
                                attempt.release()
                                true
                            }
                            if(published!=true) {
                                attempt.abortAll()
                                if(remote===camera) remote=null
                                if(http===transport) http=null
                                return@launch
                            }
                            log("遥控连接成功 application=${capabilities.application} eventVersion=${capabilities.eventVersion} apis=${capabilities.apis.sorted()}",tag="CONNECT")
                            startMonitoring()
                        } else {
                            val transfer=SmartphoneTransfer(device,transport) { log(it,LogLevel.WARN,"BROWSE") }
                            smartphoneAttempt=transfer
                            if(whenGeneration(gen) { smartphone=transfer; true } != true) { attempt.abortAll(); return@launch }
                            val files=transfer.initialize()
                            val published=whenGeneration(gen) {
                                update { copy(connected=true,files=files,folder=transfer.rootId,galleryPath=emptyList(),message="已连接 · JPEG 传输") }
                                attempt.release()
                                true
                            }
                            if(published!=true) {
                                attempt.abortAll()
                                if(smartphone===transfer) smartphone=null
                                if(http===transport) http=null
                                return@launch
                            }
                            log("JPEG 连接成功 root=${transfer.rootId} entries=${files.size}",tag="CONNECT")
                        }
                        loadIdentity(device,transport)
                    }
                } catch(e: CancellationException) {
                    // Cancelled mid-initialization: abort this attempt's own transport to unblock the
                    // blocking call before the caller joins.
                    withContext(NonCancellable+Dispatchers.IO) { attempt.abortAll() }
                    whenGeneration(gen) { computer=null; http=null; smartphone=null; remote=null }
                    throw e
                }
                catch(e: Exception) {
                    log("连接失败：${e.message}",LogLevel.ERROR,"CONNECT",e)
                    // Normal initialization error: end only the local resources this attempt created,
                    // never a global reference read across dispatchers, and only while this attempt is
                    // still the live generation.
                    val owns=identity.currentGeneration==gen
                    withContext(NonCancellable+Dispatchers.IO) {
                        if(owns) {
                            runCatching { withTimeout(INIT_FINISH_TIMEOUT_MILLIS) { remoteAttempt?.finish(); smartphoneAttempt?.finish() } }
                                .onFailure { log("初始化失败后结束会话：${it.message}",LogLevel.WARN,"CONNECT",it) }
                        }
                        attempt.abortAll()
                    }
                    // Never let a superseded connect overwrite a newer session's state.
                    whenGeneration(gen) {
                        computer=null; http=null; smartphone=null; remote=null
                        update { copy(connected=false,error=e.message,message="连接失败",scan=ScanState()) }
                    }
                }
            } finally {
                whenGeneration(gen) { update { copy(connecting=false,busy=false) } }
            }
        }
        return true
    }
    /**
     * Reads and publishes the saved queue for [gen] atomically: a superseded attempt must not publish
     * a queue into a newer session. The identity-unknown / recovery notices only apply to the gallery
     * transfer modes (JPEG / COMPUTER); remote capture has no gallery to resume.
     */
    private suspend fun attachQueue(device: Device, mode: CameraMode, gen: Long, scope: String = queueScope(device, mode)) {
        val key=scope
        val saved=withContext(Dispatchers.IO) { runCatching { queues.load(key) }.onFailure {
            log("读取下载队列：${it.message}",LogLevel.WARN,"QUEUE",it)
        }.getOrDefault(emptyList()) }
        val notice=if(mode==CameraMode.REMOTE) "" else
            if(device.cameraId==null) "无法确认相机身份，重连后需重新选择下载文件。"
            else if(saved.any { it.state !in setOf("完成","已存在") }) "已恢复未完成的下载，继续前会重新确认文件。" else ""
        whenGeneration(gen) { update { copy(queueScope=key,queue=saved,queueNotice=notice) } }
        log("相机队列 camera=${device.displayName} mode=$mode scope=$key identified=${device.cameraId!=null} entries=${saved.size}",tag="QUEUE")
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
    fun reconnect() { val mode=state.value.mode ?: return; connect(mode) }
    private fun startMonitoring() {
        val camera=remote?:return
        // Never start a second monitoring pair: an orphaned live-view loop would leak its stream and
        // later send a stray stopLiveview.
        if(!visible || eventJob?.isActive==true || liveJob?.isActive==true) return
        val gen=state.value.sessionGeneration
        val epoch=++monitorEpoch
        // Publish the epoch so the UI can drop gallery/shooting selection and previews on a transition.
        whenGeneration(gen) { if(remote===camera) update { copy(remote=remote.copy(epoch=epoch)) } }
        val transport=http
        eventJob=scope.launch {
            var failures=0
            while(isActive) {
                try {
                    val (status,urls)=camera.events(); failures=0
                    // Publish only while this session AND this monitoring epoch are still live, so an
                    // old loop finishing after a same-connection mode switch cannot write.
                    whenMonitoring(gen,epoch,camera) { update { copy(remote=remote.mergeCameraState(status)) } }
                    // While an RPC capture is in flight a body postview may actually belong to it, so it is
                    // deferred for the RPC to claim; otherwise it is an independent capture and stays noGPS.
                    urls.forEach { url -> if(!captureBinding.defer(gen,url)) importPostview(url,gen) }
                } catch(e: CancellationException) { throw e }
                catch(e: Exception) {
                    failures++; log("状态查询失败 attempt=$failures：${e.message}",LogLevel.WARN,"EVENT",e)
                    if(failures>=3) { whenMonitoring(gen,epoch,camera) { update { copy(error="状态查询失败，请检查连接并重连") } }; break }
                }
                delay(900)
            }
        }
        if("startLiveview" in state.value.remote.apis && transport!=null) liveJob=scope.launch(Dispatchers.IO) {
            var attempts=0
            while(isActive && attempts<3) {
                try {
                    val url=camera.liveView()
                    transport.stream(url) {
                        val reader=LiveViewReader(requireNotNull(it.body).byteStream())
                        var frames=0L;var bytesRead=0L;var reported=System.nanoTime()
                        while(isActive) {
                            val bytes=reader.next()?:error("实时取景流结束")
                            val image=BitmapFactory.decodeByteArray(bytes,0,bytes.size)?:continue
                            // Check and publish the frame under the same identity lock so a newer
                            // session's frame is never overwritten by an old one.
                            val owned=whenMonitoring(gen,epoch,camera) { frameState.value=image; true }
                            if(owned!=true) break
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
            if(attempts>=3) whenMonitoring(gen,epoch,camera) { update { copy(error="实时取景已停止，请重连相机") } }
        }
    }
    /** Runs [block] only while this session, monitoring epoch and camera instance are all still live. */
    private fun <T> whenMonitoring(gen: Long, epoch: Long, camera: RemoteCamera, block: ()->T): T? =
        identity.locked { if(identity.currentGeneration==gen && monitorEpoch==epoch && remote===camera) block() else null }
    /**
     * Stops the live-view and event loops for a same-connection mode switch and joins them before the
     * caller sends setCameraFunction. Joining is what isolates the old live-view `finally` (which sends
     * stopLiveview): it completes before the camera leaves Remote Shooting, and the epoch bump stops any
     * late publish. It never touches record-mode ownership or the shared HTTP transport.
     */
    private suspend fun stopMonitoringForSwitch() {
        val jobs=identity.locked {
            val epoch=++monitorEpoch
            // Publish the epoch so the UI clears the old sub-mode's selection/preview immediately.
            if(remote!=null) update { copy(remote=remote.copy(epoch=epoch)) }
            val captured=listOfNotNull(liveJob,eventJob)
            liveJob=null; eventJob=null
            captured
        }
        jobs.forEach(Job::cancel)
        withContext(NonCancellable) {
            withTimeoutOrNull(GALLERY_MONITORING_JOIN_TIMEOUT_MILLIS) { jobs.joinAll() }
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
                // A gallery session must never start live view: the camera is in Contents Transfer.
                if(visible && state.value.mode==CameraMode.REMOTE && state.value.connected && !state.value.remote.galleryActive) startMonitoring()
                refreshLocal()
            }
        } else {
            val jobs=stopMonitoring()
            monitoringStop=scope.launch { jobs.joinAll() }
        }
        applyLocationSampling()
    }
    /** Opt-in switch persisted in settings. */
    val remoteGeotaggingEnabled: Boolean get() = prefs.getBoolean(REMOTE_GEOTAGGING_PREF,false)
    fun locationPermissionGranted(): Boolean = locationSource.hasPermission()
    fun setRemoteGeotagging(enabled: Boolean) {
        prefs.edit().putBoolean(REMOTE_GEOTAGGING_PREF,enabled).apply()
        log("遥控地理标记 ${if(enabled) "开启" else "关闭"}",LogLevel.INFO,"GEOTAG")
        applyLocationSampling()
    }
    /** Re-reads the pref and permission after the runtime permission dialog returns. */
    fun refreshLocationSettings() = applyLocationSampling()
    /** Foreground sampling runs only for a visible, connected REMOTE session with the opt-in on. */
    private fun applyLocationSampling() {
        val snapshot=state.value
        val active=geotagAllowed(snapshot.mode,remoteGeotaggingEnabled,locationSource.hasPermission()) && visible && snapshot.connected
        if(active) locationSource.start() else locationSource.stop()
    }
    /** A fix for the current REMOTE session, or null when the opt-in/permission gate fails. */
    private fun currentGeoSnapshot(): GeoSnapshot? =
        if(!geotagAllowed(state.value.mode,remoteGeotaggingEnabled,locationSource.hasPermission())) null
        else locationSource.freshSnapshot()
    /** Detaches the live session. Only pure field capture, generation bump and state clearing happen
     * under the identity lock; every actual abort/close/cancel runs outside it, so no synchronous
     * callback can run while the lock is held. A forced detach closes the transports before cancelling
     * the monitoring jobs, and marks the remote so a live-view finally cannot send a cleanup RPC.
     * `effectiveGraceful` is false whenever a control action is still in flight (a cancelled capture
     * does not mean the camera stopped, so no record-mode cleanup is sent). */
    private fun detachSession(snapshot: AppState, self: Job?, gracefulRequested: Boolean): DetachedSession {
        val detached = identity.locked {
            val gen=nextGeneration()
            val actions=sessionActions.detach()
            val oldHttp=http; val oldComputer=computer; val oldSmartphone=smartphone; val oldRemote=remote
            val oldQueue=queueJob; val oldIdentity=identityJob; val oldScan=scanJob; val oldGallery=galleryJob
            val monitoring=listOfNotNull(liveJob,eventJob,intervalJob)
            liveJob=null; eventJob=null; intervalJob=null
            galleryJob=null
            val monitoringStopJob=monitoringStop
            val ownConnection=connectionJob?.takeIf { it.isActive && it!==self }
            val attempt=pendingConnection; pendingConnection=PendingResources()
            val graceful=gracefulRequested && actions.isEmpty()
            if(!graceful) oldRemote?.disallowCleanup()
            userScanPaused=false
            computer=null; remote=null; smartphone=null; http=null; selectedNetwork=null
            avContent=null; avContentSource=null; galleryPager=null; avContentEndpoint=null
            captureBinding.clear()
            update { copy(connected=false,connecting=false,busy=false,transferring=false,folder="",galleryPath=emptyList(),scan=ScanState()) }
            DetachedSession(gen,snapshot,oldHttp,oldComputer,oldSmartphone,oldRemote,oldQueue,oldIdentity,oldScan,oldGallery,monitoring,monitoringStopJob,ownConnection,attempt,actions,graceful)
        }
        if(!detached.effectiveGraceful) {
            runCatching { detached.computer?.abort() }
            runCatching { detached.http?.close() }
        }
        detached.actions.forEach { it.cancel() }
        detached.scan?.cancel()
        detached.queue?.cancel()
        detached.identity?.cancel()
        detached.gallery?.cancel()
        detached.ownConnection?.cancel()
        detached.monitoring.forEach { it.cancel() }
        return detached
    }
    suspend fun disconnect(finish: Boolean = true, allowSonySleep: Boolean = false) {
        val snapshot=state.value
        log("断开开始 finish=$finish allowSonySleep=$allowSonySleep connected=${snapshot.connected}",LogLevel.DEBUG,"CONNECT")
        val self=coroutineContext[Job]
        val d=detachSession(snapshot,self,gracefulRequested=finish)
        runCatching { d.attempt.abortAll() }
        background.reset("disconnect")
        if(snapshot.device!=null) { lastDevice=snapshot.device; lastRemote=snapshot.remote; lastMode=snapshot.mode }
        if(d.ownConnection!=null) {
            runCatching { d.http?.close() }
            d.ownConnection.join()
        }
        // Persist the interrupted queue from the snapshot, never from a newer session's state.
        snapshot.queueScope?.let { key -> withContext(Dispatchers.IO) {
            val resumable=snapshot.queue.map { if(it.state=="传输中") it.copy(state="等待",received=0) else it }
            runCatching { queues.save(key,resumable) }.onFailure { log("保存断开队列：${it.message}",LogLevel.WARN,"QUEUE",it) }
        } }
        // Join only the jobs this session detached, never a newer session's actions. HTTP jobs are
        // released by cancellation, so these joins are bounded and no live-view finally can race the
        // end sequence below.
        sessionActions.join(d.actions,self)
        d.identity?.join()
        // A gallery switch can be blocked in an HTTP RPC; bound the join so a graceful disconnect can
        // never hang on it (the transport is closed later in the end sequence).
        withTimeoutOrNull(GALLERY_MONITORING_JOIN_TIMEOUT_MILLIS) { d.gallery?.join() }
        d.monitoring.joinAll()
        d.monitoringStop?.join()
        whenGeneration(d.gen) { frameState.value=null }
        // Run the end sequence before joining scan/queue: the PTP finish's idle gate aborts a blocking
        // scan, which unblocks the joins without relying on coroutine cancellation. A non-graceful
        // detach sends no RPC.
        val sleepCamera = d.effectiveGraceful && allowSonySleep && prefs.getBoolean(SONY_SLEEP_PREF,false)
        withContext(NonCancellable+Dispatchers.IO) {
            SessionEndSequence(SESSION_END_TIMEOUT_MILLIS).run(listOf(
                // Leaving the camera in Contents Transfer would strand the next connection; return it to
                // shooting first (best effort, never on a forced detach).
                SessionEndSequence.Step("gallery") { if(d.effectiveGraceful && d.snapshot.remote.galleryActive) d.remote?.setCameraFunction(RemoteFunction.REMOTE_SHOOTING) },
                SessionEndSequence.Step("remote") { if(d.effectiveGraceful) d.remote?.finish() },
                SessionEndSequence.Step("smartphone") { if(d.effectiveGraceful) d.smartphone?.finish() },
                SessionEndSequence.Step("computer") { if(d.effectiveGraceful) d.computer?.finish(sleepCamera=sleepCamera) },
            )) { step, e -> log("结束会话 ${step.name}：${e.message}",LogLevel.WARN,"CONNECT",e) }
            runCatching { d.computer?.abort() }
        }
        d.scan?.join()
        d.queue?.join()
        d.http?.close()
        whenGeneration(d.gen) { thumbs.evictAll(); importedUrls.clear() }
        whenGeneration(d.gen) { update { copy(connected=false,files=emptyList(),device=null,remote=RemoteState(),intervalRemaining=0,message="已断开") } }
        log("断开完成",LogLevel.DEBUG,"CONNECT")
    }
    fun endSession() {
        if(state.value.transferring) return
        // A plain (untracked) job: disconnect must never cancel/join itself.
        scope.launch {
            try { disconnect(allowSonySleep=true) } catch(e: CancellationException) { throw e }
            catch(e: Exception) { log(e.message.orEmpty(),LogLevel.ERROR,"ACTION",e); update { copy(error=e.message) } }
        }
    }
    private fun action(block: suspend ()->Unit) {
        sessionAction(state.value.sessionGeneration,block)
    }
    /** Control helper: the job is tracked (LAZY) before it starts, so a disconnect between creation
     * and start still cancels it, and the caller must have captured the camera/transfer and [gen]. */
    private fun sessionAction(gen: Long, block: suspend ()->Unit) {
        val job=scope.launch(start=CoroutineStart.LAZY) {
            try { block() } catch(e: CancellationException) { throw e }
            catch(e: Exception) { log(e.message.orEmpty(),LogLevel.ERROR,"ACTION",e); whenGeneration(gen) { update { copy(error=e.message) } } }
        }
        sessionActions.track(job)
        job.start()
    }
    fun setSetting(key:String,value:String) {
        val gen=state.value.sessionGeneration
        val camera=remote ?: return
        if(!remoteControlAllowed()) return
        log("设置 $key=$value",tag="CONTROL")
        sessionAction(gen) {
            if(!ownsCamera(camera,gen)) return@sessionAction
            camera.set(key,value)
            if(!ownsCamera(camera,gen)) return@sessionAction
            val refreshed=camera.refresh()
            whenGeneration(gen) { if(remote===camera) update { copy(remote=remote.mergeCameraState(refreshed)) } }
        }
    }
    fun focus() {
        val gen=state.value.sessionGeneration
        val camera=remote ?: return
        if(!remoteControlAllowed()) return
        sessionAction(gen) { if(ownsCamera(camera,gen)) camera.focus() }
    }
    fun movie() {
        val gen=state.value.sessionGeneration
        val camera=remote ?: return
        if(!remoteControlAllowed()) return
        val recording=state.value.remote.status.contains("MovieRecording",true)
        sessionAction(gen) { if(ownsCamera(camera,gen)) camera.movie(!recording) }
    }
    fun capture() {
        val gen=state.value.sessionGeneration
        val camera=remote ?: return
        if(!remoteControlAllowed()) return
        sessionAction(gen) { shutter.withLock { captureInFlight=true; try { captureInternal(camera,gen) } finally { captureInFlight=false } } }
    }
    /** True only while [camera] is still the live remote for [gen]; checked before every command so an
     * old capture/interval can never send to a newer session. */
    private fun ownsCamera(camera: RemoteCamera, gen: Long): Boolean =
        whenGeneration(gen) { remote===camera } == true
    private suspend fun captureInternal(camera: RemoteCamera, gen: Long) {
        if(!ownsCamera(camera,gen)) return
        log("请求拍照",tag="CAPTURE")
        if(!ownsCamera(camera,gen)) return
        // Freeze the fix for this capture (null included); never block the shutter on location.
        val geo=currentGeoSnapshot()
        if(remoteGeotaggingEnabled && locationSource.hasPermission()) locationSource.requestRefresh()
        val token=captureBinding.begin(gen,geo)
        val urls=try {
            camera.takePicture()
        } catch(e: CancellationException) {
            captureBinding.abort(token); throw e
        } catch(e: Exception) {
            captureBinding.complete(token,emptyList()).forEach { importPostview(it,gen) }
            throw e
        }
        log("拍照返回 previews=${urls.size}",tag="CAPTURE")
        // complete() binds the frozen fix to the RPC URLs first; import those before flushing any
        // deferred body postviews so a shared URL is claimed by the RPC capture, not as noGPS.
        val deferred=captureBinding.complete(token,urls)
        urls.forEach { importPostview(it,gen) }
        deferred.forEach { importPostview(it,gen) }
        if(urls.isEmpty()) whenGeneration(gen) { update { copy(message="快门请求已接受，未返回 JPEG 预览；请检查相机") } }
    }
    /** Dedup and enqueue under the identity lock so a stale postview cannot mutate a newer session. The
     * fix is only the one frozen for this capture; an event postview is never bound to a current fix. */
    private fun importPostview(url: String, gen: Long) {
        val name=runCatching { java.net.URI(url).path.substringAfterLast('/') }.getOrDefault("").takeIf { it.endsWith(".jpg",true) }
            ?: "Remote_${System.currentTimeMillis()}.JPG"
        identity.locked {
            if(gen!=identity.currentGeneration) return@locked
            if(url in importedUrls) return@locked
            importedUrls += url
            while(importedUrls.size>500) importedUrls.remove(importedUrls.first())
            val bound=captureBinding.geoFor(gen,url)
            val file=CameraFile(url,name,listOf(Resource(url)),thumbnail=url)
            enqueue(listOf(file),gen,geo=if(bound!=null) mapOf(file.id to bound) else emptyMap())
            if(bound!=null) log("遥控照片附带定位 ${name} accuracy=${bound.accuracyMeters}m",LogLevel.DEBUG,"GEOTAG")
        }
    }
    fun interval(count: Int, seconds: Int) {
        if(intervalJob?.isActive==true) return
        if(!remoteControlAllowed()) return
        val gen=state.value.sessionGeneration
        val camera=remote ?: return
        intervalJob=scope.launch {
            try {
                repeat(count.coerceIn(1,999)) { index ->
                    if(!ownsCamera(camera,gen)) return@launch
                    whenGeneration(gen) { update { copy(intervalRemaining=count-index) } }
                    shutter.withLock { captureInFlight=true; try { captureInternal(camera,gen) } finally { captureInFlight=false } }
                    if(index<count-1) delay(seconds.coerceIn(2,3600)*1000L)
                }
            } catch(e: CancellationException) { throw e } catch(e: Exception) { whenGeneration(gen) { update { copy(error=e.message) } } }
            finally { whenGeneration(gen) { update { copy(intervalRemaining=0) } } }
        }
    }
    fun stopInterval() { intervalJob?.cancel() }
    // --- same-connection camera gallery (Contents Transfer) --------------------------------------
    /** Synchronous sub-mode derived from the remote flags, so a double tap cannot start two switches. */
    private fun subMode(remote: RemoteState): RemoteSubMode = when {
        remote.galleryActive && remote.remoteSwitching -> RemoteSubMode.SwitchingToShooting
        remote.galleryActive -> RemoteSubMode.Gallery
        remote.remoteSwitching -> RemoteSubMode.SwitchingToGallery
        else -> RemoteSubMode.Shooting
    }
    /** Capture / AF / record / settings are refused in the gallery or during a mode switch, so the UI
     * disable is not the only guard. */
    private fun remoteControlAllowed(): Boolean {
        val snapshot=state.value
        return snapshot.mode==CameraMode.REMOTE && snapshot.connected && !snapshot.remote.galleryActive && !snapshot.remote.remoteSwitching
    }
    private suspend fun <T> bestEffortValue(block: suspend ()->T): T? =
        try { block() } catch(e: CancellationException) { throw e } catch(_: Exception) { null }
    /** Retries only Sony error 7 (NotAvailableNow) a bounded number of times; anything else propagates.
     * This is the camera saying "not ready yet", never "empty list". */
    private suspend fun <T> retryNotAvailableNow(attempts: Int = 3, block: suspend ()->T): T {
        var last: CameraApiError?=null
        for(attempt in 1..attempts) {
            try { return block() }
            catch(e: CameraApiError) {
                if(e.code!=AV_CONTENT_ERROR_NOT_AVAILABLE_NOW) throw e
                last=e
                if(attempt<attempts) delay(GALLERY_POLL_INTERVAL_MILLIS)
            }
        }
        throw last ?: IllegalStateException("不可达")
    }
    /**
     * Enters the same-connection gallery. The switch latch and epoch are set synchronously before the
     * async work starts, so a second tap during the switch is rejected instead of launching a second
     * setCameraFunction. On failure galleryActive stays true so [returnToRemoteCapture] can recover.
     */
    fun openRemoteGallery() {
        val snapshot=state.value
        val camera=remote ?: return
        if(snapshot.mode!=CameraMode.REMOTE || !snapshot.connected) return
        if(snapshot.remote.remoteSwitching) return
        val browsing=snapshot.remote.galleryActive && avContent!=null
        if(browsing) return
        // A failed earlier entry leaves galleryActive=true with no avContent: that is a retry, so the
        // capability/idle gate is skipped (the camera may already be mid-switch).
        val retry=snapshot.remote.galleryActive
        val endpoint=avContentEndpoint
        if(endpoint==null) { update { copy(error="相机未提供内容传输接口，无法进入相机图库") }; return }
        val intervalActive=intervalJob?.isActive==true || snapshot.intervalRemaining>0
        // An in-flight exposure (including a long exposure) must never be interrupted by a switch.
        val busy=snapshot.busy || captureInFlight
        if(!retry) {
            if(!RemoteGalleryPolicy.canEnter(snapshot.connected,snapshot.remote.canBrowseGallery,subMode(snapshot.remote),
                    snapshot.remote.status,intervalActive,snapshot.transferring,busy)) {
                update { copy(error=RemoteGalleryPolicy.enterRefusal(snapshot.remote.status,intervalActive,snapshot.transferring,busy)) }
                return
            }
        } else if(intervalActive || snapshot.transferring || busy) {
            // The retry path skips the capability check (the camera may already be mid-switch) but must
            // still never interrupt an exposure, interval run or download.
            update { copy(error=RemoteGalleryPolicy.enterRefusal(snapshot.remote.status,intervalActive,snapshot.transferring,busy)) }
            return
        }
        val gen=snapshot.sessionGeneration
        val latched=identity.locked {
            if(identity.currentGeneration==gen && remote===camera) {
                update { copy(files=if(retry) files else emptyList(), error=null, message="正在切换到相机图库…",
                    remote=remote.copy(galleryActive=true, remoteSwitching=true, loadingMore=true, loadMoreFailed=false, hasMoreFiles=false, totalFiles=null, loadedContentCount=0,
                        switchTarget=RemoteFunction.CONTENTS_TRANSFER)) }
                true
            } else false
        }
        if(!latched) return
        galleryJob?.cancel()
        galleryJob=scope.launch {
            try { switchToGallery(camera,endpoint,gen,retry) }
            catch(e: CancellationException) { throw e }
            catch(e: Exception) {
                log("进入相机图库失败：${e.message}",LogLevel.ERROR,"GALLERY",e)
                whenGeneration(gen) { if(remote===camera) update { copy(remote=remote.copy(remoteSwitching=false, loadingMore=false, switchTarget=""),
                    error="无法进入相机图库，请重试或重新连接相机。", message="切换失败，可重试或返回拍摄") } }
            }
        }
    }
    private suspend fun switchToGallery(camera: RemoteCamera, endpoint: String, gen: Long, retry: Boolean) {
        // Stop live view/events and JOIN them, so the old live-view finally (stopLiveview) completes
        // before the camera leaves Remote Shooting, and no late publish can write.
        stopMonitoringForSwitch()
        if(!ownsCamera(camera,gen)) return
        // If the camera already reports Contents Transfer (a retry after a browse failure, or a restart
        // with the camera left there) the set and the idle gate are skipped.
        val current=readCameraFunction(camera)
        val alreadyContents=current!=null && current.equals(RemoteFunction.CONTENTS_TRANSFER,true)
        if(!alreadyContents) {
            confirmIdleBeforeSwitch(camera,gen,retry)
            if(!ownsCamera(camera,gen)) return
            switchCameraFunction(camera,RemoteFunction.CONTENTS_TRANSFER,gen)
        }
        if(!ownsCamera(camera,gen)) return
        // The camera is in Contents Transfer now; record it so a later browse failure still lets the UI
        // treat the retry/return as the correct direction.
        whenGeneration(gen) { if(remote===camera) update { copy(remote=remote.copy(remoteFunction=RemoteFunction.CONTENTS_TRANSFER)) } }
        val av=AvContentTransfer(endpoint,requireNotNull(http)) { message -> log(message,LogLevel.DEBUG,"AVCONTENT") }
        val source=openGallerySource(av)
        if(!ownsCamera(camera,gen)) return
        val total=bestEffortValue { retryNotAvailableNow { av.count(source,AvContentTransfer.VIEW_DATE) } }?.takeIf { it>=0 }
        val pager=GalleryPager(AvContentTransfer.DEFAULT_PAGE_SIZE)
        pager.begin(source,total)
        val page=retryNotAvailableNow { av.getContentPage(source,0,pager.pageSize,view=AvContentTransfer.VIEW_DATE) }
        val fresh=pager.accept(page)
        val files=fresh.flatMap { it.toCameraFiles() }
        whenGeneration(gen) {
            if(remote!==camera) return@whenGeneration
            avContent=av; avContentSource=source; galleryPager=pager
            update { copy(files=files,folder=source,galleryPath=emptyList(), message="相机图库 · ${pager.contentLoaded} 项",
                remote=remote.copy(galleryActive=true, remoteSwitching=false, loadingMore=false, loadMoreFailed=false,
                    hasMoreFiles=pager.hasMore, totalFiles=pager.total, loadedContentCount=pager.contentLoaded,
                    remoteFunction=RemoteFunction.CONTENTS_TRANSFER, switchTarget="")) }
        }
        log("相机图库已就绪 source=$source total=$total loaded=${pager.contentLoaded} more=${pager.hasMore}",tag="GALLERY")
        refreshGalleryQueue(gen,camera)
        loadRemainingRemotePages(camera,av,pager,gen)
    }
    private suspend fun openGallerySource(av: AvContentTransfer): String {
        val schemes=bestEffortValue { retryNotAvailableNow { av.schemes() } }.orEmpty()
        val candidates=if(schemes.isEmpty()) listOf("storage") else schemes
        for(scheme in candidates) {
            val sources=bestEffortValue { retryNotAvailableNow { av.sources(scheme) } }.orEmpty()
            if(sources.isNotEmpty()) return sources.first()
        }
        error("相机未提供可浏览的内容源")
    }
    /** One last non-long-poll getEvent before switching, in case a body-shutter capture status had not
     * arrived yet. A transient failure is tolerated; an explicit non-idle status refuses the switch. */
    private suspend fun confirmIdleBeforeSwitch(camera: RemoteCamera, gen: Long, retry: Boolean = false) {
        val result=try { camera.events() } catch(e: CancellationException) { throw e }
            catch(e: Exception) { log("切换前状态确认失败：${e.message}",LogLevel.DEBUG,"GALLERY"); return }
        val status=result.first.status
        // A retry may find the camera already in the target function; that is not "busy".
        val tolerated=retry && status.contains("ContentsTransfer",ignoreCase=true)
        if(ownsCamera(camera,gen) && !RemoteGalleryPolicy.isIdleStatus(status) && !tolerated) {
            log("切换前相机忙碌 status=$status",LogLevel.DEBUG,"GALLERY")
            throw IllegalStateException("相机忙碌，请待机后再进入相机图库")
        }
    }
    /**
     * Switches the camera function with a bounded wait. If getCameraFunction already reports [function]
     * the set is skipped (recovery after a restart or a camera left in the target state). Error 7
     * (NotAvailableNow) is retried; a transient getCameraFunction error is tolerated and readiness may
     * also be confirmed via getEvent. Record-mode ownership is never touched.
     */
    private suspend fun switchCameraFunction(camera: RemoteCamera, function: String, gen: Long) {
        if(!ownsCamera(camera,gen)) return
        val initial=readCameraFunction(camera)
        if(initial!=null && initial.equals(function,true)) { log("相机模式已是 $function",LogLevel.DEBUG,"GALLERY"); return }
        val deadline=System.currentTimeMillis()+GALLERY_SWITCH_TIMEOUT_MILLIS
        var sent=false
        var lastError: Exception?=null
        while(!sent && System.currentTimeMillis()<deadline) {
            if(!ownsCamera(camera,gen)) return
            try { camera.setCameraFunction(function); sent=true }
            catch(e: CameraApiError) {
                if(e.code==AV_CONTENT_ERROR_NOT_AVAILABLE_NOW) { lastError=e; delay(GALLERY_POLL_INTERVAL_MILLIS); continue }
                throw e
            }
        }
        if(!sent) throw lastError ?: IllegalStateException("切换相机模式超时：$function")
        if(!awaitFunctionReady(camera,function,gen)) throw IllegalStateException("切换相机模式后未就绪：$function")
    }
    private suspend fun readCameraFunction(camera: RemoteCamera): String? =
        try { camera.cameraFunction() } catch(e: CancellationException) { throw e }
        catch(e: Exception) { log("getCameraFunction 暂不可用：${e.message}",LogLevel.DEBUG,"GALLERY"); null }
    private suspend fun awaitFunctionReady(camera: RemoteCamera, function: String, gen: Long): Boolean {
        val canQuery="getCameraFunction" in camera.state.apis
        val deadline=System.currentTimeMillis()+GALLERY_SWITCH_TIMEOUT_MILLIS
        var iterations=0
        while(System.currentTimeMillis()<deadline) {
            if(!ownsCamera(camera,gen)) return false
            iterations++
            val current=readCameraFunction(camera)
            if(current!=null && current.equals(function,true)) return true
            val events=try { camera.events() } catch(e: CancellationException) { throw e } catch(_: Exception) { null }
            if(events!=null && ownsCamera(camera,gen)) {
                val (status,_)=events
                whenGeneration(gen) { if(remote===camera) update { copy(remote=remote.copy(status=status.status,apis=status.apis)) } }
                if(function.equals(RemoteFunction.REMOTE_SHOOTING,true) && RemoteGalleryPolicy.isIdleStatus(status.status) && "startLiveview" in status.apis) return true
                // After a switch to Contents Transfer getCameraFunction can stop answering; the reported
                // camera status is then the only confirmation available.
                if(function.equals(RemoteFunction.CONTENTS_TRANSFER,true) && status.status.contains("ContentsTransfer",ignoreCase=true)) return true
            }
            // No way to query the function: a single bounded settle is the best confirmation available.
            if(!canQuery && iterations>=2) return true
            delay(GALLERY_POLL_INTERVAL_MILLIS)
        }
        return false
    }
    /**
     * Automatically load the current root/date directory in bounded 20-item requests.
     * The first page has already been published. A new directory or capture return cancels
     * galleryJob; old workers must never overwrite the new view or its loading state.
     */
    private suspend fun loadRemainingRemotePages(
        camera: RemoteCamera, av: AvContentTransfer, pager: GalleryPager, gen: Long
    ) {
        val source=pager.source ?: return
        val worker=coroutineContext[Job]
        if(!pager.hasMore) return
        whenGeneration(gen) {
            if(remote===camera && avContent===av && galleryPager===pager && galleryJob===worker)
                update { copy(remote=remote.copy(loadingMore=true,loadMoreFailed=false)) }
        }
        try {
            while(pager.hasMore && ownsCamera(camera,gen) && galleryJob===worker &&
                avContent===av && galleryPager===pager) {
                val page=retryNotAvailableNow {
                    av.getContentPage(source,pager.nextStart,pager.pageSize,view=AvContentTransfer.VIEW_DATE)
                }
                val fresh=pager.accept(page)
                val files=fresh.flatMap { it.toCameraFiles() }
                whenGeneration(gen) {
                    if(remote===camera && avContent===av && galleryPager===pager && galleryJob===worker)
                        update { copy(files=this.files+files,
                            remote=remote.copy(loadingMore=pager.hasMore,loadMoreFailed=false,
                                hasMoreFiles=pager.hasMore,totalFiles=pager.total,
                                loadedContentCount=pager.contentLoaded)) }
                }
                refreshGalleryQueue(gen,camera)
            }
        } catch(e: CancellationException) { throw e }
        catch(e: Exception) {
            log("自动加载相机目录失败：${e.message}",LogLevel.ERROR,"GALLERY",e)
            whenGeneration(gen) {
                if(remote===camera && avContent===av && galleryPager===pager && galleryJob===worker)
                    update { copy(remote=remote.copy(loadingMore=false,loadMoreFailed=true),
                        error="加载更多失败，请重试。") }
            }
        } finally {
            withContext(NonCancellable) {
                whenGeneration(gen) {
                    if(remote===camera && avContent===av && galleryPager===pager && galleryJob===worker)
                        update { copy(remote=remote.copy(loadingMore=false)) }
                }
            }
        }
    }

    /** Only a failed automatic page can be retried manually. */
    fun loadMoreRemoteFiles() {
        val snapshot=state.value
        val camera=remote ?: return
        val av=avContent ?: return
        val pager=galleryPager ?: return
        if(snapshot.mode!=CameraMode.REMOTE || !snapshot.connected || !snapshot.remote.galleryActive ||
            snapshot.remote.remoteSwitching || snapshot.remote.loadingMore || !snapshot.remote.loadMoreFailed ||
            !pager.hasMore || snapshot.transferring) return
        val gen=snapshot.sessionGeneration
        val latched=identity.locked {
            if(identity.currentGeneration==gen && remote===camera && avContent===av && galleryPager===pager) {
                update { copy(remote=remote.copy(loadingMore=true,loadMoreFailed=false),error=null) }
                true
            } else false
        }
        if(!latched) return
        galleryJob?.cancel()
        galleryJob=scope.launch { loadRemainingRemotePages(camera,av,pager,gen) }
    }
    /** Unified refresh entry: reloads the remote gallery page, a JPEG folder, or the RAW scan. A gallery
     * whose entry failed (galleryActive but no client yet) is retried through the same open path. */
    fun refreshCameraFiles() {
        val snapshot=state.value
        when {
            snapshot.mode==CameraMode.REMOTE && snapshot.remote.galleryActive ->
                if(avContent!=null) reloadRemoteGallery() else openRemoteGallery()
            snapshot.mode==CameraMode.JPEG -> root()
            snapshot.mode==CameraMode.COMPUTER -> rescan()
            else -> Unit
        }
    }
    private fun reloadRemoteGallery() {
        val snapshot=state.value
        val camera=remote ?: return
        val av=avContent ?: return
        val pager=galleryPager ?: return
        val source=pager.source ?: return
        if(snapshot.remote.loadingMore || snapshot.remote.remoteSwitching || snapshot.transferring) return
        val gen=snapshot.sessionGeneration
        val latched=identity.locked {
            if(identity.currentGeneration==gen && remote===camera && avContent===av) { update { copy(remote=remote.copy(loadingMore=true)) }; true } else false
        }
        if(!latched) return
        galleryJob=scope.launch {
            try {
                val total=bestEffortValue { retryNotAvailableNow { av.count(source,AvContentTransfer.VIEW_DATE) } }?.takeIf { it>=0 }
                pager.begin(source,total)
                val page=retryNotAvailableNow { av.getContentPage(source,0,pager.pageSize,view=AvContentTransfer.VIEW_DATE) }
                val fresh=pager.accept(page)
                val files=fresh.flatMap { it.toCameraFiles() }
                whenGeneration(gen) {
                    if(remote!==camera) return@whenGeneration
                    update { copy(files=files, message="相机图库 · ${pager.contentLoaded} 项",
                        remote=remote.copy(loadingMore=false, loadMoreFailed=false, hasMoreFiles=pager.hasMore, totalFiles=pager.total, loadedContentCount=pager.contentLoaded)) }
                }
                refreshGalleryQueue(gen,camera)
                loadRemainingRemotePages(camera,av,pager,gen)
            } catch(e: CancellationException) { throw e }
            catch(e: Exception) {
                log("刷新相机图库失败：${e.message}",LogLevel.ERROR,"GALLERY",e)
                whenGeneration(gen) { if(remote===camera) update { copy(remote=remote.copy(loadingMore=false), error="无法读取相机文件，请重试或返回拍摄。") } }
            }
        }
    }
    /**
     * Returns to Remote Shooting. A download in progress refuses the return; a cancelled download is
     * joined first so the camera never switches function while its stream is still draining. Only a
     * confirmed return clears galleryActive; a failure stays in the gallery so it can be retried.
     */
    fun returnToRemoteCapture() {
        val snapshot=state.value
        val camera=remote ?: return
        if(snapshot.mode!=CameraMode.REMOTE || !snapshot.connected) return
        if(!snapshot.remote.galleryActive || snapshot.remote.remoteSwitching) return
        if(snapshot.transferring || queueJob?.isActive==true) { update { copy(error="下载尚未结束，请先取消或等待完成再返回拍摄") }; return }
        if(snapshot.busy) { update { copy(error="正在处理，请稍候再返回拍摄") }; return }
        val gen=snapshot.sessionGeneration
        val latched=identity.locked {
            if(identity.currentGeneration==gen && remote===camera) {
                update { copy(remote=remote.copy(remoteSwitching=true, switchTarget=RemoteFunction.REMOTE_SHOOTING), error=null, message="正在返回拍摄模式…") }
                true
            } else false
        }
        if(!latched) return
        galleryJob?.cancel()
        galleryJob=scope.launch {
            try {
                val job=queueJob
                if(job!=null && job.isActive) withTimeoutOrNull(GALLERY_SWITCH_TIMEOUT_MILLIS) { job.join() }
                if(state.value.transferring) throw IllegalStateException("下载仍在进行，已取消返回拍摄")
                avContent=null; avContentSource=null; galleryPager=null
                switchCameraFunction(camera,RemoteFunction.REMOTE_SHOOTING,gen)
                if(!ownsCamera(camera,gen)) return@launch
                whenGeneration(gen) { if(remote===camera) update { copy(remote=remote.copy(remoteFunction=RemoteFunction.REMOTE_SHOOTING)) } }
                // A function switch can drop record mode; re-enter it so live view is available again.
                bestEffortValue { camera.ensureShootingMode() }
                if(!ownsCamera(camera,gen)) return@launch
                confirmShootingReady(camera,gen)
                if(!ownsCamera(camera,gen)) return@launch
                whenGeneration(gen) { if(remote===camera) update { copy(files=emptyList(),folder="",galleryPath=emptyList(), message="已返回拍摄模式",
                    remote=remote.copy(galleryActive=false, remoteSwitching=false, hasMoreFiles=false, loadingMore=false,
                        totalFiles=null, loadedContentCount=0, remoteFunction=RemoteFunction.REMOTE_SHOOTING, switchTarget="")) } }
                startMonitoring()
            } catch(e: CancellationException) { throw e }
            catch(e: Exception) {
                log("返回拍摄失败：${e.message}",LogLevel.ERROR,"GALLERY",e)
                whenGeneration(gen) { if(remote===camera) update { copy(remote=remote.copy(remoteSwitching=false, switchTarget=""),
                    error="无法返回拍摄模式，请重试或重新连接相机。", message="返回失败，可重试") } }
            }
        }
    }
    /** Confirms the camera is back in Remote Shooting and advertises live view before clearing the
     * gallery flag; otherwise the return fails and stays recoverable from the gallery. */
    private suspend fun confirmShootingReady(camera: RemoteCamera, gen: Long) {
        val deadline=System.currentTimeMillis()+GALLERY_SWITCH_TIMEOUT_MILLIS
        while(System.currentTimeMillis()<deadline) {
            if(!ownsCamera(camera,gen)) return
            val events=try { camera.events() } catch(e: CancellationException) { throw e }
                catch(e: Exception) { log("返回拍摄就绪确认：${e.message}",LogLevel.DEBUG,"GALLERY"); null }
            if(events!=null && ownsCamera(camera,gen)) {
                val (status,_)=events
                whenGeneration(gen) { if(remote===camera) update { copy(remote=remote.copy(status=status.status,apis=status.apis)) } }
                // Shooting is confirmed by an idle status plus a shooting API; live view may lag behind
                // and is started by the monitoring restart, so it is not required here.
                if(RemoteGalleryPolicy.isIdleStatus(status.status) &&
                    ("startLiveview" in status.apis || "actTakePicture" in status.apis)) return
            }
            delay(GALLERY_POLL_INTERVAL_MILLIS)
        }
        throw IllegalStateException("相机未恢复到拍摄模式")
    }
    /** Re-queries the loaded listing to refresh restored avContent queue entries; a stored URL is never
     * trusted and an entry that cannot be matched is marked for reselection. */
    private fun refreshGalleryQueue(gen: Long, camera: RemoteCamera) {
        val snapshot=state.value
        val files=snapshot.files
        if(snapshot.queue.isEmpty()) return
        // Only an entry that cannot be matched in a COMPLETE listing is genuinely missing. While more
        // pages are still available an unmatched entry may simply live on a page not loaded yet, so it
        // stays resumable instead of being wrongly downgraded to "需重新选择".
        val listingComplete=!snapshot.remote.hasMoreFiles
        val currentDate=snapshot.folder.takeIf { it.isNotBlank() && it!=avContentSource }
        val updated=snapshot.queue.map { item ->
            if(item.file.original && item.state in setOf("待恢复","失败","已取消","需重新选择")) {
                val fresh=refreshQueuedFile(item.file,files)
                when {
                    fresh!=null -> item.copy(file=fresh,state="待恢复",error=null)
                    // A date-root listing contains no originals and cannot prove a queue entry missing.
                    listingComplete && currentDate!=null && item.file.id.startsWith("$currentDate#") ->
                        item.copy(state="需重新选择",error="无法确认该文件，请重新选择后再下载。")
                    else -> item
                }
            } else item
        }
        if(updated!=snapshot.queue) whenGeneration(gen) { if(remote===camera) update { copy(queue=updated) } }
    }
    private enum class JpegNavigation { Enter, Up, Root }

    private fun browseJpeg(id: String, navigation: JpegNavigation) {
        val snapshot=state.value
        if(!snapshot.connected || snapshot.mode!=CameraMode.JPEG || snapshot.busy || snapshot.transferring) return
        val camera=smartphone ?: return
        val gen=snapshot.sessionGeneration
        sessionAction(gen) {
            if(whenGeneration(gen) { smartphone===camera } != true) return@sessionAction
            whenGeneration(gen) { update { copy(busy=true) } }
            try {
                log("浏览目录 id=$id",LogLevel.DEBUG,"BROWSE")
                val files=camera.browse(id)
                whenGeneration(gen) {
                    if(smartphone===camera) update {
                        val history=when(navigation) {
                            JpegNavigation.Root -> emptyList()
                            JpegNavigation.Up -> galleryPath.dropLast(1)
                            JpegNavigation.Enter -> if(folder==id) galleryPath else galleryPath+folder
                        }
                        copy(files=files,folder=id,galleryPath=history)
                    }
                }
                log("目录返回 id=$id entries=${files.size}",LogLevel.DEBUG,"BROWSE")
            } finally { whenGeneration(gen) { update { copy(busy=false) } } }
        }
    }

    /** Browse only a camera-supplied date URI, never one constructed from a filename. */
    private fun browseRemoteDate(id: String) {
        val snapshot=state.value
        val camera=remote ?: return
        val av=avContent ?: return
        val root=avContentSource ?: return
        if(!snapshot.connected || snapshot.mode!=CameraMode.REMOTE || !snapshot.remote.galleryActive ||
            snapshot.remote.remoteSwitching || snapshot.busy || snapshot.transferring) return
        if(id!=root && !(snapshot.folder==root && snapshot.files.any { it.container && it.id==id })) return
        val gen=snapshot.sessionGeneration
        val latched=identity.locked {
            if(identity.currentGeneration==gen && remote===camera && avContent===av) {
                update { copy(remote=remote.copy(loadingMore=true,loadMoreFailed=false),error=null) }
                true
            } else false
        }
        if(!latched) return
        galleryJob?.cancel()
        galleryJob=scope.launch {
            try {
                val total=bestEffortValue { retryNotAvailableNow { av.count(id,AvContentTransfer.VIEW_DATE) } }?.takeIf { it>=0 }
                val pager=GalleryPager(AvContentTransfer.DEFAULT_PAGE_SIZE)
                pager.begin(id,total)
                val page=retryNotAvailableNow { av.getContentPage(id,0,pager.pageSize,view=AvContentTransfer.VIEW_DATE) }
                val files=pager.accept(page).flatMap { it.toCameraFiles() }
                whenGeneration(gen) {
                    if(remote!==camera || avContent!==av) return@whenGeneration
                    galleryPager=pager
                    update { copy(files=files,folder=id,galleryPath=if(id==root) emptyList() else listOf(root),
                        remote=remote.copy(loadingMore=false,loadMoreFailed=false,hasMoreFiles=pager.hasMore,
                            totalFiles=pager.total,loadedContentCount=pager.contentLoaded)) }
                }
                refreshGalleryQueue(gen,camera)
                loadRemainingRemotePages(camera,av,pager,gen)
            } catch(e: CancellationException) { throw e }
            catch(e: Exception) {
                log("读取日期目录失败：${e.message}",LogLevel.ERROR,"GALLERY",e)
                whenGeneration(gen) { if(remote===camera && avContent===av) update {
                    copy(remote=remote.copy(loadingMore=false,loadMoreFailed=true),error="无法读取相机文件，请重试或返回拍摄。")
                } }
            }
        }
    }

    fun browse(id: String) {
        val snapshot=state.value
        if(snapshot.mode==CameraMode.REMOTE && snapshot.remote.galleryActive) browseRemoteDate(id)
        else if(snapshot.mode==CameraMode.JPEG) browseJpeg(id,JpegNavigation.Enter)
    }
    fun up() {
        val snapshot=state.value
        val parent=snapshot.galleryPath.lastOrNull() ?: return
        if(snapshot.mode==CameraMode.REMOTE && snapshot.remote.galleryActive) browseRemoteDate(parent)
        else if(snapshot.mode==CameraMode.JPEG) browseJpeg(parent,JpegNavigation.Up)
    }
    fun root() {
        val snapshot=state.value
        if(snapshot.mode==CameraMode.REMOTE && snapshot.remote.galleryActive)
            avContentSource?.let(::browseRemoteDate)
        else if(snapshot.mode==CameraMode.JPEG)
            smartphone?.let { browseJpeg(it.rootId,JpegNavigation.Root) }
    }
    suspend fun cameraPreview(file: CameraFile, expectedScope: String?, expectedGeneration: Long = state.value.sessionGeneration): Bitmap? {
        val snapshot=state.value;val transport=http;val ptp=computer
        return withContext(Dispatchers.IO) { thumbnailGate.withPermit {
        check(snapshot.sessionGeneration==expectedGeneration && state.value.sessionGeneration==expectedGeneration) { "相机连接已变化，请重新打开预览" }
        check(snapshot.queueScope==expectedScope && state.value.queueScope==expectedScope) { "相机连接已变化，请重新打开预览" }
        check(snapshot.connected && state.value.connected) { "相机已断开" }
        check(!snapshot.transferring) { "文件正在传输，完成后可读取预览" }
        if(file.handle!=null && file.raw) {
            val data=requireNotNull(ptp).thumbnail(file.handle)
            val bitmap=PreviewBitmap.decode { data.inputStream() }
            // The blocking read may have outlived the session: never hand back a stale bitmap.
            return@withContext if(sessionCurrent(expectedGeneration,snapshot.queueScope,ptp,transport)) bitmap else null
        }
        // An avContent original JPEG is its own preview; an ARW uses the camera's preview URL, never the
        // full RAW. Legacy modes keep choosing the largest JPEG resource.
        val resource=if(file.original && file.protocol==PROTOCOL_HTTP)
            file.originalResource?.takeIf { !file.raw } ?: file.thumbnail?.let { Resource(it) }
        else file.jpegResource(JpegSize.LARGE) ?: file.bestResource()
        val key="${expectedGeneration}|${snapshot.queueScope}|${file.parent}|${file.id}|${file.date}|${file.size}|${resource?.url}"
        val cached=previews.obtain(key) { output ->
            if(file.handle!=null) requireNotNull(ptp).download(file,output) { _,_-> }
            else requireNotNull(transport).stream(requireNotNull(resource) { "相机没有提供 JPEG 预览" }.url) { response ->
                val body=requireNotNull(response.body)
                val expected=body.contentLength().takeIf { it>0 } ?: resource.size
                body.byteStream().use { input -> StreamCopy.copy(input,output,expected,{ensureActive()}) { _,_-> } }
            }
        }
        check(sessionCurrent(expectedGeneration,snapshot.queueScope,ptp,transport)) { "相机连接已变化，请重新打开预览" }
        val decoded=PreviewBitmap.decode { cached.inputStream() }
        if(decoded==null) { cached.delete();error("无法解码相机预览，请重新读取") }
        if(!sessionCurrent(expectedGeneration,snapshot.queueScope,ptp,transport)) return@withContext null
        decoded
    } } }
    /** True when the session captured by a preview/thumbnail request is still the live one. */
    private fun sessionCurrent(gen: Long, scope: String?, ptp: ComputerTransfer?, transport: CameraHttp?): Boolean {
        val current=state.value
        return current.sessionGeneration==gen && current.queueScope==scope && current.connected && computer===ptp && http===transport
    }
    suspend fun thumbnail(file:CameraFile, expectedScope: String?, expectedGeneration: Long = state.value.sessionGeneration): Bitmap? {
        val snapshot=state.value;val transport=http;val ptp=computer
        return withContext(Dispatchers.IO) { thumbnailGate.withPermit {
        if(!snapshot.connected || !state.value.connected || snapshot.queueScope!=expectedScope || state.value.queueScope!=expectedScope
            || snapshot.sessionGeneration!=expectedGeneration || state.value.sessionGeneration!=expectedGeneration) return@withContext null
        val key=if(file.original && file.protocol==PROTOCOL_HTTP && file.thumbnail!=null)
            "${expectedGeneration}:${snapshot.queueScope}:thumbnail:${file.thumbnail}"
        else "${expectedGeneration}:${snapshot.queueScope}:${file.id}:${file.thumbnail}"
        thumbs.get(key)?.let { return@withContext it }
        try {
            val bytes = if(file.handle!=null) ptp?.thumbnail(file.handle) else {
                val url=file.thumbnail ?: file.bestResource()?.url ?: return@withContext null
                transport?.stream(url) { r ->
                    val source=requireNotNull(r.body).source()
                    require(!source.request(4L*1024*1024+1)) { "缩略图过大" }; source.readByteArray()
                }
            } ?: return@withContext null
            val options=BitmapFactory.Options().apply { inJustDecodeBounds=true }
            BitmapFactory.decodeByteArray(bytes,0,bytes.size,options)
            options.inSampleSize=(maxOf(options.outWidth,options.outHeight)/500).coerceAtLeast(1); options.inJustDecodeBounds=false
            // Never backfill a newer session's cache from an old-generation request, and never return
            // a bitmap whose session changed while it decoded.
            val decoded=BitmapFactory.decodeByteArray(bytes,0,bytes.size,options)
            if(decoded!=null && sessionCurrent(expectedGeneration,snapshot.queueScope,ptp,transport)) {
                thumbs.put(key,decoded)
                return@withContext decoded
            }
            return@withContext null
        } catch(e: CancellationException) { throw e } catch(_: Exception) { null }
    } } }
    fun enqueue(incoming:List<CameraFile>, expectedGeneration: Long = state.value.sessionGeneration,
        geo: Map<String,GeoSnapshot> = emptyMap()) {
        val snapshot=state.value
        if(!snapshot.connected || snapshot.sessionGeneration!=expectedGeneration) return
        identity.locked {
            update {
                // Re-check atomically against the latest state so a stale caller cannot overwrite it.
                if(sessionGeneration!=expectedGeneration || queueScope!=snapshot.queueScope || !connected) this
                else {
                    // For COMPUTER the current scan listing (this.files) is the source of truth: only a
                    // file that uniquely matches (id+parent+name+date+size) is enqueued, never a stale
                    // capture with the same id but changed metadata.
                    val verified=incoming.mapNotNull { file ->
                        if(file.container) null
                        else if(mode==CameraMode.COMPUTER) matchCurrentFile(file,this.files)
                        else if(mode==CameraMode.REMOTE && file.original) {
                            if(remote.galleryActive && !remote.remoteSwitching && file.protocol==PROTOCOL_HTTP)
                                matchCurrentFile(file,this.files)
                            else null
                        } else file
                    }
                    if(verified.isEmpty()) this
                    else {
                        val queue=queue.toMutableList()
                        verified.forEach { file ->
                            if(queue.none { it.file.id==file.id && it.state in listOf("等待","传输中") }) {
                                queue.removeAll { it.file.id==file.id && it.state !in listOf("等待","传输中") }
                                queue+=QueueItem(file,geo=geo[file.id])
                            }
                        }
                        copy(queue=queue)
                    }
                }
            }
        }
        if(state.value.queue.any { it.state=="等待" } && !state.value.transferring) launchTransfers()
    }
    private fun launchTransfers() {
        if(queueJob?.isActive==true) return
        val snapshot=state.value
        if(!snapshot.connected || snapshot.queueScope==null) return
        if(snapshot.queue.none { it.state=="等待" }) return
        val sessionScope=snapshot.queueScope
        val sessionGeneration=snapshot.sessionGeneration
        val sessionHttp=http
        val sessionComputer=computer
        val token=identity.nextQueueRun()
        queueJob=scope.launch(Dispatchers.IO) {
            val lease=background.acquire("transfer")
            var started=false
            var sessionDied=false
            try {
                if(!awaitBackgroundReady()) {
                    // Never leave items silently "waiting": fail them for this session and, for a RAW
                    // session, invalidate the transport so the user can retry cleanly.
                    identity.whenCurrent(token) {
                        if(sessionComputer!=null) { runCatching { sessionComputer.abort() }; if(computer===sessionComputer) computer=null }
                        update { copy(transferring=false,connected=sessionComputer==null&&connected,
                            queue=queue.map { if(it.state in listOf("等待","传输中")) it.copy(state="已取消") else it },
                            error="后台服务不可用，无法开始下载",message="后台服务不可用，请保持应用在前台后重试") }
                    }
                    return@launch
                }
                if(!identity.isCurrent(token) || state.value.queueScope!=sessionScope) return@launch
                started=true
                identity.whenCurrent(token) { update { copy(transferring=true,scan=scan.copy(paused=scan.phase==ScanPhase.Scanning)) } }
                while(isActive) {
                    if(!identity.isCurrent(token) || !state.value.connected || state.value.queueScope!=sessionScope) {
                        log("相机会话已结束，暂停队列",tag="QUEUE");break
                    }
                    // A previous transaction aborted the session: no further PTP transaction is safe.
                    if(sessionComputer!=null && !sessionComputer.alive) { sessionDied=true; break }
                    val item=state.value.queue.firstOrNull { it.state=="等待" }?:break
                    val file=item.file
                    var lastProgress=0L
                    var lastUiProgress=0L
                    fun progress(read:Long,total:Long) {
                        identity.whenCurrent(token) {
                            val now=System.nanoTime()
                            if(read==0L || read==total || now-lastUiProgress>=100_000_000L) {
                                update { copy(queue=queue.map { if(it===item || (it.file.id==file.id && it.state=="传输中")) it.copy(state="传输中",received=read,total=total) else it }) }
                                lastUiProgress=now
                            }
                            if(read>0 && (read==total || now-lastProgress>=2_000_000_000L)) {
                                log("${file.name} bytes=$read/$total",LogLevel.DEBUG,"TRANSFER");lastProgress=now
                            }
                        }
                    }
                    progress(0,file.size)
                    try {
                        val isPtp=file.handle!=null
                        val isHttpOriginal=!isPtp && file.original && file.protocol==PROTOCOL_HTTP
                        // HTTP originals (avContent) never depend on a PTP handle: the explicit format
                        // selects the JPEG or ARW resource. Legacy modes keep the JPEG resource choice.
                        val resource=when {
                            isPtp -> null
                            isHttpOriginal -> file.originalResource
                            else -> file.bestResource()
                        }
                        if(!isPtp && resource==null) error("相机没有提供可下载的文件")
                        val isRaw=(isPtp && file.raw) || (isHttpOriginal && file.format==AV_CONTENT_FORMAT_RAW)
                        val name=if(!isRaw && file.name.endsWith(".arw",true)) file.name.substringBeforeLast('.')+".JPG" else file.name
                        val source=transferSource(sessionScope,file,resource)
                        val expected=resource?.size?.takeIf { it>0 } ?: file.size
                        // An HTTP original's authoritative length is the response Content-Length (the
                        // listing size can be absent or differ). Passing 0 lets StreamCopy's own
                        // Content-Length check stand and keeps import from rejecting a valid file whose
                        // listing size disagreed; the transfer source key already pins the identity.
                        val importExpected=if(isHttpOriginal) 0L else expected
                        val dedupExpected=if(isHttpOriginal) 0L else expected
                        // Dedup on the received size, not the on-disk size a GPS edit may change.
                        val existing=photos.list().firstOrNull { matchesSource(it,source,dedupExpected) && photos.exists(it) }
                        log("开始 ${file.name} expected=$expected resource=${resource?.url ?: file.id} raw=$isRaw",tag="TRANSFER")
                        if(existing==null) {
                            // Only repeat safe file GETs. Every failed attempt removes its pending file.
                            val attempts=if(file.handle==null)3 else 1
                            for(attempt in 1..attempts) {
                                try {
                                    photos.import(name,isRaw,importExpected,source,state.value.mode?.title.orEmpty(),geotag=item.geo,
                                        canWriteGeotag={ geotagAllowed(state.value.mode,remoteGeotaggingEnabled,locationSource.hasPermission()) && state.value.sessionGeneration==sessionGeneration },
                                        log={ message -> log(message,LogLevel.WARN,"GEOTAG") }) { out ->
                                        if(file.handle!=null) requireNotNull(sessionComputer).download(file,out,::progress)
                                        else requireNotNull(sessionHttp).stream(requireNotNull(resource).url) { response ->
                                            if(isHttpOriginal && (response.code!=200 || response.header("Content-Range")!=null))
                                                throw IOException("相机未返回完整原文件（HTTP ${response.code}）")
                                            val body=requireNotNull(response.body)
                                            // Prefer the HTTP Content-Length; when the listing size is unknown
                                            // it is the only validation available. Both absent means a chunked
                                            // body, validated as "must be non-empty" by StreamCopy.
                                            val headerLength=body.contentLength()
                                            val total=headerLength.takeIf { it>0 } ?: expected.takeIf { it>0 } ?: 0L
                                            if(expected>0 && headerLength>0 && headerLength!=expected)
                                                log("下载 ${file.name}：列表长度 $expected 与 HTTP 长度 $headerLength 不一致，以 HTTP 为准",LogLevel.WARN,"TRANSFER")
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
                        identity.whenCurrent(token) { update { copy(queue=queue.map { if(it.file.id==file.id && it.state=="传输中") it.copy(state=if(existing!=null) "已存在" else "完成") else it },local=photos.list()) } }
                        log("${file.name} ${if(existing!=null) "跳过有效本地文件" else "保存完成"}",tag="TRANSFER")
                    } catch(e: CancellationException) { throw e }
                    catch(e: Exception) {
                        log("下载 ${file.name} 失败：${e.javaClass.simpleName}: ${e.message}",LogLevel.ERROR,"TRANSFER",e)
                        val dead=sessionComputer!=null && !sessionComputer.alive
                        if(dead) sessionDied=true
                        identity.whenCurrent(token) { update { copy(queue=queue.map { if(it.file.id==file.id && it.state=="传输中") it.copy(state="失败",error=e.message) else it }) } }
                        if(dead) break
                    }
                }
            } finally {
                // Never let a superseded queue run overwrite a newer session's state.
                identity.whenCurrent(token) {
                    if(started) {
                        if(sessionDied) {
                            // A dead session can leave a paused scan holding its lease; cancel it so the
                            // lease is released and the scan does not stay stuck.
                            if(sessionComputer!=null && computer===sessionComputer) computer=null
                            scanJob?.cancel(); scanJob=null
                            update { copy(transferring=false,connected=false,
                                scan=if(scan.phase==ScanPhase.Scanning) ScanState(ScanPhase.Failed,scanned=scan.scanned,total=scan.total,found=scan.found,error="连接中断") else scan,
                                queue=queue.map { if(it.state=="传输中") it.copy(state="等待",received=0) else it },
                                error="连接中断，请重新连接",message="连接中断，已保留未完成的下载") }
                        } else {
                            update { copy(transferring=false,scan=scan.copy(paused=userScanPaused),queue=queue.map { if(it.state in listOf("等待","传输中")) it.copy(state="已取消") else it }) }
                        }
                        val current=state.value
                        runCatching { queues.save(sessionScope,current.queue) }.onFailure { log("保存传输结果：${it.message}",LogLevel.WARN,"QUEUE",it) }
                    }
                }
                lease.release()
            }
        }
    }
    fun cancelTransfers() {
        log("请求取消下载",tag="TRANSFER")
        queueJob?.cancel()
        if(state.value.mode==CameraMode.COMPUTER) {
            // PTP has no partial cancel: aborting the transport is the only way to release a blocked
            // getObjectToStream. The session is therefore invalidated and must be reconnected.
            nextGeneration()
            userScanPaused=false
            val transfer=computer; computer=null
            scanJob?.cancel(); scanJob=null
            scope.launch(Dispatchers.IO) { runCatching { transfer?.abort() } }
            update { copy(connected=false,transferring=false,queue=queue.map { if(it.state in listOf("等待","传输中")) it.copy(state="已取消") else it },
                scan=ScanState(ScanPhase.Failed,scanned=scan.scanned,total=scan.total,found=scan.found,error="已取消"),
                message="已取消下载，原文件会话已断开，请重新连接") }
        }
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
    fun deletePhoto(photo: LocalPhoto, permission: (IntentSender)->Unit) = action {
        try {
            withContext(Dispatchers.IO) { photos.deleteFile(photo) }
            importedUrls.clear()
            update { copy(local=photos.list(),message="已删除 ${photo.name}") }
        } catch(e: SecurityException) {
            if(android.os.Build.VERSION.SDK_INT>=30) permission(android.provider.MediaStore.createDeleteRequest(
                context.contentResolver,listOf(Uri.parse(photo.uri))).intentSender)
            else if(e is RecoverableSecurityException) permission(e.userAction.actionIntent.intentSender)
            else throw e
        }
    }
    fun exportDiagnostics(uri: Uri) = action {
        log("开始导出日志与诊断",tag="EXPORT")
        withContext(Dispatchers.IO) {
            val logs=journal.snapshot()
            val json=JSONObject(diagnostics()).put("logging",JSONObject().put("level",journal.minimum.name).put("session",journal.session)
                .put("dropped",logs.dropped).put("writeFailures",logs.writeFailures)).toString(2)
            requireNotNull(context.contentResolver.openOutputStream(uri,"wt")) { "无法写入诊断文件" }.use {
                DiagnosticBundle.write(it,json,logs,CrashLog.read(context.filesDir))
            }
        }
        log("日志与诊断导出完成",tag="EXPORT")
        update { copy(message="日志与诊断已导出") }
    }
    fun retryFailed() = action {
        if(!state.value.connected || state.value.transferring || state.value.busy) return@action
        val snapshot=state.value
        val mode=snapshot.mode ?: return@action
        // A partial COMPUTER listing must never judge queued files missing; wait for the scan.
        if(!scanListingComplete(mode,snapshot.scan.phase)) {
            log("扫描未完成，暂不重试队列",LogLevel.DEBUG,"QUEUE")
            update { copy(message="正在读取相机文件列表，请稍候再试") }
            return@action
        }
        val retry=snapshot.queue.filter { it.state in listOf("失败","已取消","待恢复","需重新选择") }
        if(retry.isEmpty()) return@action
        update { copy(busy=true) }
        try {
            val folders=mutableMapOf<String,List<CameraFile>>()
            val refreshed=retry.associate { item ->
                val list=if(mode==CameraMode.JPEG) folders.getOrPut(item.file.parent) {
                    requireNotNull(smartphone).browse(item.file.parent)
                } else snapshot.files
                item.file.id to refreshQueuedFile(item.file,list)
            }
            if(state.value.queueScope!=snapshot.queueScope || !state.value.connected || state.value.sessionGeneration!=snapshot.sessionGeneration) return@action
            val ready=retry.mapNotNull { refreshed[it.file.id] }
            update { copy(queue=queue.map { item -> if(item in retry && refreshed[item.file.id]==null)
                item.copy(state="需重新选择",error="无法确认该文件，请从相机图库重新选择。") else item }) }
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
            put("sessionGeneration",snapshot.sessionGeneration)
            put("scan",JSONObject().apply {
                put("phase",snapshot.scan.phase.name); put("scanned",snapshot.scan.scanned); put("found",snapshot.scan.found)
                put("paused",snapshot.scan.paused); put("error",snapshot.scan.error)
                snapshot.scan.total?.let { put("total",it) }
            })
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
