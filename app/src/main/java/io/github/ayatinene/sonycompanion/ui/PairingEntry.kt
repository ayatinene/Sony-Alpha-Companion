package io.github.ayatinene.sonycompanion.ui

import android.Manifest
import android.content.Intent
import android.nfc.NfcAdapter
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import io.github.ayatinene.sonycompanion.pairing.*

internal object PairingPermissionPolicy {
    fun required(sdk: Int): List<String> = if (sdk >= 33) listOf(Manifest.permission.NEARBY_WIFI_DEVICES)
        else listOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
}

internal class PairingActions(val scan: () -> Unit, val nfc: () -> Unit)

@Composable
internal fun rememberPairingActions(model: CompanionViewModel, onConnecting: () -> Unit, onMessage: (String) -> Unit): PairingActions {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lifecycleState by lifecycle.currentStateFlow.collectAsState()
    var nfcHelp by rememberSaveable { mutableStateOf(false) }
    val permissionsPending = model.pairingPermissionPending
    var openSettings by remember { mutableStateOf(false) }
    val latestConnecting by rememberUpdatedState(onConnecting)
    val latestMessage by rememberUpdatedState(onMessage)
    val input = model.pairingInput
    val required = PairingPermissionPolicy.required(Build.VERSION.SDK_INT)
    val permissions = rememberPermissionLauncher { result ->
        model.pairingPermissionPending = false
        if (!required.all { result[it] == true || isPermissionGranted(context, it) }) {
            model.pairingInput = null
            openSettings = true
        }
    }
    fun eligible(): Boolean = !model.controller.state.value.let { it.connected || it.connecting || it.busy || it.transferring }
    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        if (result.contents == null) {
            latestMessage("已取消扫描；若未授予相机权限，请在应用设置中开启")
        } else if (!eligible()) {
            latestMessage("请先结束当前连接，再扫描相机二维码")
        } else {
            try {
                val credentials=SonyQrParser.parse(result.contents)
                model.pairingSource=PairingSource.QR
                model.pairingInput=credentials
            }
            catch (e: IllegalArgumentException) { latestMessage(e.message ?: "相机二维码无效") }
        }
    }
    LaunchedEffect(input, permissionsPending, lifecycleState) {
        val credentials = input ?: return@LaunchedEffect
        if (lifecycleState != Lifecycle.State.RESUMED || permissionsPending) return@LaunchedEffect
        nfcHelp = false
        if (!eligible()) { model.pairingInput = null; latestMessage("请先结束当前连接，再重新配对"); return@LaunchedEffect }
        val missing = required.filter { !isPermissionGranted(context, it) }
        if (missing.isNotEmpty()) {
            model.pairingPermissionPending = true
            permissions.launch(required) // API 31+ requires coarse and fine in the same request.
        } else {
            model.pairingInput = null
            if (model.controller.pairCamera(credentials,model.pairingSource)) latestConnecting()
        }
    }
    LaunchedEffect(model.pairingNotice) {
        model.pairingNotice?.let { latestMessage(it); model.pairingNotice = null }
    }
    if (nfcHelp) {
        val adapter = NfcAdapter.getDefaultAdapter(context)
        AlertDialog(onDismissRequest = { nfcHelp = false }, title = { Text("NFC 配对") }, text = {
            Text(when {
                adapter == null -> "此手机不支持 NFC，请使用二维码或手动连接。"
                !adapter.isEnabled -> "NFC 已关闭，请在系统设置中开启，再返回应用。"
                else -> "请保持本应用在前台，将手机靠近相机 N 标识约两秒，并在系统提示中确认连接 Wi-Fi。相机处于拍摄模式时进入遥控拍摄；处于回放或“发送到智能手机”时自动导入所选照片的 JPEG（最大可用尺寸），完成后自动断开。若未打开本应用，触碰可能启动索尼官方应用。"
            })
        }, confirmButton = { TextButton(onClick = { nfcHelp = false }) { Text("知道了") } },
            dismissButton = { if (adapter != null && !adapter.isEnabled) TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_NFC_SETTINGS)) }) { Text("打开 NFC 设置") } })
    }
    if (openSettings) AlertDialog(onDismissRequest = { openSettings = false }, title = { Text("需要 Wi-Fi 配对权限") },
        text = { Text(if (Build.VERSION.SDK_INT >= 33) "请允许附近设备权限后重新配对。" else "Android 10–12 需要精确位置权限和系统定位开关来请求相机 Wi-Fi；配对不采集手机位置。") },
        confirmButton = { TextButton(onClick = { openSettings = false; context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", context.packageName, null))) }) { Text("打开应用设置") } },
        dismissButton = { TextButton(onClick = { openSettings = false }) { Text("取消") } })
    return PairingActions(scan = {
        if (eligible()) scanner.launch(ScanOptions().setCaptureActivity(CameraQrActivity::class.java)
            .setDesiredBarcodeFormats(ScanOptions.QR_CODE).setBeepEnabled(false).setBarcodeImageEnabled(false)
            .setOrientationLocked(true).setPrompt("扫描相机智能遥控界面的二维码"))
        else latestMessage("请先结束当前连接，再扫描相机二维码")
    }, nfc = { nfcHelp = true })
}
