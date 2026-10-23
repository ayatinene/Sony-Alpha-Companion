package io.github.ayatinene.sonycompanion.network

import android.app.PendingIntent
import android.content.*
import android.hardware.usb.*
import androidx.core.content.ContextCompat
import io.github.ayatinene.sonycompanion.protocol.ComputerTransfer
import io.github.ayatinene.sonycompanion.protocol.SonyGuidRegistration
import kotlinx.coroutines.*
import java.io.IOException
import kotlin.coroutines.resume

class UsbGuidRegistrar(private val context: Context, private val report: (String)->Unit = {}) {
    private val manager=context.getSystemService(UsbManager::class.java)
    private fun storage(device: UsbDevice): UsbInterface? = (0 until device.interfaceCount)
        .map(device::getInterface).firstOrNull { it.interfaceClass==UsbConstants.USB_CLASS_MASS_STORAGE && it.interfaceProtocol==0x50 }
    fun cameras(): List<UsbDevice> = manager.deviceList.values.filter { it.vendorId==0x054c && storage(it)!=null }
    fun description(): String = manager.deviceList.values.joinToString("\n") { device ->
        val interfaces=(0 until device.interfaceCount).joinToString { index -> device.getInterface(index).let {
            "if=${it.id} class=${it.interfaceClass}/${it.interfaceSubclass}/${it.interfaceProtocol} endpoints=${it.endpointCount}"
        } }
        "${device.productName ?: "USB 设备"} VID:PID=%04x:%04x $interfaces".format(device.vendorId,device.productId)
    }.ifBlank { "未检测到 USB 设备。请将相机设为海量存储，通过数据线和 USB OTG 连接手机。" }

    private suspend fun permission(device: UsbDevice): Boolean {
        if(manager.hasPermission(device)) return true
        return suspendCancellableCoroutine { continuation ->
            val action="${context.packageName}.USB_GUID_PERMISSION"
            val receiver=object: BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if(intent.action!=action) return
                    @Suppress("DEPRECATION")
                    val result: UsbDevice? = if(android.os.Build.VERSION.SDK_INT>=33) intent.getParcelableExtra(UsbManager.EXTRA_DEVICE,UsbDevice::class.java)
                        else intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    if(result?.deviceId!=device.deviceId) return
                    runCatching { context.unregisterReceiver(this) }
                    if(continuation.isActive) continuation.resume(intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED,false))
                }
            }
            ContextCompat.registerReceiver(context,receiver,IntentFilter(action),ContextCompat.RECEIVER_NOT_EXPORTED)
            continuation.invokeOnCancellation { runCatching { context.unregisterReceiver(receiver) } }
            try {
                val intent=PendingIntent.getBroadcast(context,device.deviceId,Intent(action).setPackage(context.packageName),PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                if(continuation.isActive) manager.requestPermission(device,intent)
            } catch(e: Exception) {
                runCatching { context.unregisterReceiver(receiver) }
                if(continuation.isActive) continuation.resumeWith(Result.failure(e))
            }
        }
    }
    suspend fun register(guid: String): String = withContext(Dispatchers.IO) {
        val bytes=ComputerTransfer.parseGuid(guid).map { it.toByte() }.toByteArray()
        val devices=cameras()
        require(devices.size==1) { if(devices.isEmpty()) "未检测到 Sony 海量存储相机，请检查 USB 模式、数据线和手机 OTG" else "检测到多个 Sony USB 设备，请仅连接目标相机" }
        val device=devices.single()
        report("USB 登记目标：${description()}")
        require(permission(device)) { "未获得相机 USB 访问权限" }
        ensureActive()
        val iface=requireNotNull(storage(device))
        val endpoints=(0 until iface.endpointCount).map(iface::getEndpoint).filter { it.type==UsbConstants.USB_ENDPOINT_XFER_BULK }
        val input=requireNotNull(endpoints.firstOrNull { it.direction==UsbConstants.USB_DIR_IN }) { "未发现 USB Bulk IN 端点" }
        val output=requireNotNull(endpoints.firstOrNull { it.direction==UsbConstants.USB_DIR_OUT }) { "未发现 USB Bulk OUT 端点" }
        val connection=requireNotNull(manager.openDevice(device)) { "无法打开相机 USB 设备" }
        var claimed=false
        try {
            check(connection.claimInterface(iface,true)) { "无法占用相机 USB 接口，请关闭其他正在访问相机的应用" }; claimed=true
            val maxLun=ByteArray(1)
            val received=connection.controlTransfer(0xa1,0xfe,0,iface.id,maxLun,1,1000)
            val lun=if(received==1) maxLun[0].toInt() and 255 else 0
            require(lun in 0..15) { "相机报告了无效的 USB LUN" }
            report("USB BOT 接口=${iface.id} IN=${input.address} OUT=${output.address} LUN=$lun")
            SonyGuidRegistration.register(bytes,lun,write={ packet ->
                ensureActive()
                connection.bulkTransfer(output,packet,packet.size,3000).also { report("USB OUT bytes=$it/${packet.size}") }
            },readStatus={
                ensureActive()
                val status=ByteArray(SonyGuidRegistration.STATUS_SIZE)
                var count=connection.bulkTransfer(input,status,status.size,3000)
                if(count<0) {
                    // BOT allows the status stage to STALL. Clear that endpoint once; never resend the write.
                    val cleared=connection.controlTransfer(0x02,1,0,input.address,null,0,1000)
                    if(cleared>=0) count=connection.bulkTransfer(input,status,status.size,3000)
                }
                if(count<0) throw IOException("读取 USB 登记状态失败，请重新连接相机")
                report("USB CSW bytes=$count")
                status.copyOf(count)
            })
            report("相机已接受 USB 接收端登记")
            device.productName ?: "Sony 相机"
        } finally {
            if(claimed) connection.releaseInterface(iface)
            connection.close()
        }
    }
}
