package io.github.ayatinene.sonycompanion.network

import android.content.Context
import android.net.*
import android.net.wifi.WifiManager
import io.github.ayatinene.sonycompanion.data.Device
import io.github.ayatinene.sonycompanion.protocol.CameraXml
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.Closeable
import java.net.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

class CameraNetwork(private val context: Context) {
    private val manager = context.getSystemService(ConnectivityManager::class.java)
    fun wifi(): Network = manager.allNetworks.firstOrNull {
        manager.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    } ?: error("尚未连接 Wi-Fi，请先连接相机或同一局域网")
    fun gateway(network: Network): String? = manager.getLinkProperties(network)?.routes
        ?.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }?.gateway?.hostAddress

    suspend fun discover(network: Network, http: CameraHttp, remote: Boolean, manual: String, computer: Boolean = false): List<Device> = withContext(Dispatchers.IO) {
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
            // Documented legacy endpoint; acceptance still requires a real descriptor response.
            if (!remote && !computer) {
                val host = manual.takeIf { !it.contains("://") && it.isNotBlank() } ?: gateway(network)
                if (host != null) locations += URI("http://$host:64321/DmsDescPush.xml")
            }
            val devices = locations.mapNotNull { uri ->
                try {
                    val xml=http.getText(uri.toString())
                    if(computer && (!xml.contains("MtpNullService") || !xml.contains("Sony",true))) null
                    else CameraXml.description(xml,uri)
                }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { null }
            }.filter { if(computer)true else if (remote) it.remote != null else it.content != null }.distinctBy { if(computer)it.description.host else if(remote) it.remote else it.content }
            devices
        } finally { if(lock.isHeld) lock.release() }
    }
}

/** All camera HTTP uses the selected Wi-Fi; mobile data stays available to other apps. */
class CameraHttp(network: Network? = null, private val trace: (String,String)->Unit = {_,_->}) : Closeable {
    private val calls = ConcurrentHashMap.newKeySet<Call>()
    val client = OkHttpClient.Builder().apply {
        if (network != null) { socketFactory(network.socketFactory); dns(object:Dns { override fun lookup(hostname:String) = network.getAllByName(hostname).toList() }) }
        connectTimeout(5,TimeUnit.SECONDS); readTimeout(65,TimeUnit.SECONDS)
        followRedirects(false); retryOnConnectionFailure(false)
    }.build()
    private suspend fun execute(request: Request, allowSoapFault: Boolean = false): Response = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request); calls += call
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object:Callback {
            override fun onFailure(call:Call,e:java.io.IOException) {
                calls-=call
                if(continuation.isActive) continuation.resumeWithException(e)
            }
            override fun onResponse(call:Call,response:Response) {
                calls-=call
                if(!response.isSuccessful && !(allowSoapFault&&response.code==500)) {
                    val code=response.code;response.close()
                    if(continuation.isActive) continuation.resumeWithException(java.io.IOException("相机 HTTP $code"))
                } else continuation.resume(response) { _,value,_->value.close() }
            }
        })
    }
    suspend fun getText(url: String): String = withContext(Dispatchers.IO) { get(url).use { r ->
        val source = requireNotNull(r.body).source(); val out = okio.Buffer()
        var total = 0L
        while (true) { val n = source.read(out,8192); if(n < 0) break; total += n; require(total <= 8*1024*1024) { "响应过大" } }
        out.readUtf8().also { trace("GET $url",it) }
    } }
    suspend fun get(url: String): Response = execute(Request.Builder().url(url).header("Accept-Encoding","identity").get().build())
    suspend fun post(url: String, body: String, mime: String = "application/json; charset=utf-8", headers: Map<String,String> = emptyMap(), allowSoapFault: Boolean = false): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).post(body.toRequestBody(mime.toMediaType())).apply { headers.forEach { (k,v) -> header(k,v) } }.build()
        execute(request,allowSoapFault).use { response ->
            val source = requireNotNull(response.body).source()
            require(!source.request(8L*1024*1024+1)) { "响应过大" }
            source.readUtf8().also { trace("POST $url ${headers["SOAPACTION"] ?: runCatching { org.json.JSONObject(body).optString("method") }.getOrDefault("")}",it) }
        }
    }
    override fun close() { calls.forEach(Call::cancel); client.dispatcher.cancelAll(); client.connectionPool.evictAll() }
}
