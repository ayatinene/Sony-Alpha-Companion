package io.github.ayatinene.sonycompanion.pairing

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Handler
import android.os.Looper
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

class AndroidPairingBackend(private val context: Context) : PairingBackend<Network> {
    @SuppressLint("MissingPermission") // Pairing entry requests the platform-specific runtime permissions.
    override fun request(credentials: PairingCredentials, events: PairingBackend.Events<Network>): Closeable {
        check(context.getSystemService(WifiManager::class.java).isWifiEnabled) { "请先在系统设置中开启 Wi-Fi" }
        if (android.os.Build.VERSION.SDK_INT <= 32) {
            check(context.getSystemService(android.location.LocationManager::class.java).isLocationEnabled) {
                "Android 10–12 请求相机 Wi-Fi 需要开启系统定位，请在系统设置中开启后重试"
            }
        }
        val manager = context.getSystemService(ConnectivityManager::class.java)
        try {
        val specifier = WifiNetworkSpecifier.Builder().setSsid(credentials.ssid)
            .setWpa2Passphrase(credentials.password).build()
        val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).setNetworkSpecifier(specifier).build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = events.available(network)
            override fun onUnavailable() = events.unavailable()
            override fun onLost(network: Network) = events.lost(network)
        }
        // No process binding: CameraHttp/discovery use exactly the returned Network.
        manager.requestNetwork(request, callback, Handler(Looper.getMainLooper()), 60_000)
        val closed = AtomicBoolean()
        return Closeable { if (closed.compareAndSet(false, true)) runCatching { manager.unregisterNetworkCallback(callback) } }
        } catch (_: SecurityException) {
            throw java.io.IOException("Wi-Fi 配对权限不足，请在应用设置中允许附近设备（Android 10–12 需精确位置）后重试")
        } catch (_: IllegalArgumentException) {
            throw java.io.IOException("系统拒绝此 Wi-Fi 配置，请重新扫描相机二维码")
        } catch (_: RuntimeException) {
            throw java.io.IOException("无法发起系统 Wi-Fi 连接，请检查 Wi-Fi 与定位设置后重试")
        }
    }
}
