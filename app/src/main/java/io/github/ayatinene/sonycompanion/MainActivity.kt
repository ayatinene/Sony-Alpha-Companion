package io.github.ayatinene.sonycompanion

import android.Manifest
import android.content.Intent
import android.os.*
import android.provider.Settings
import android.nfc.NfcAdapter
import io.github.ayatinene.sonycompanion.pairing.NfcPairing
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ViewModelProvider
import io.github.ayatinene.sonycompanion.ui.*

class MainActivity: ComponentActivity() {
    private val permission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    private val model by lazy { ViewModelProvider(this)[CompanionViewModel::class.java] }
    private val nfc by lazy { NfcPairing(this) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if(savedInstanceState==null) receiveNfc(intent)
        setContent { CompanionApp(model,openWifi={ startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) },requestPermissions={
            // Manual remedy for the notification permission only; the app needs no nearby-devices
            // runtime permission for manual connection; pairing and geotagging have their own gates.
            if(Build.VERSION.SDK_INT>=33) permission.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
        }) }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        receiveNfc(intent)
    }
    private fun receiveNfc(source: Intent) {
        if(source.action!=NfcAdapter.ACTION_NDEF_DISCOVERED) return
        if(model.controller.state.value.let { it.connected || it.connecting || it.busy || it.transferring } || model.pairingInput!=null) {
            model.pairingNotice="请先结束当前连接，再触碰相机 NFC 标识"
        } else {
            try { model.pairingInput=NfcPairing.read(source) }
            catch (e: IllegalArgumentException) { model.pairingNotice=e.message ?: "NFC 配对数据无效" }
            catch (_: Exception) { model.pairingNotice="无法读取此 NFC 配对数据，请重新触碰或扫描相机二维码" }
        }
        source.removeExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)
        source.removeExtra(NfcAdapter.EXTRA_TAG)
    }
    override fun onResume() {
        super.onResume()
        runCatching { nfc.resume() }.onFailure { model.pairingNotice="无法启用 NFC 读取，请检查系统 NFC 设置" }
    }
    override fun onPause() { nfc.pause(); super.onPause() }
    override fun onStart() { super.onStart(); model.controller.setVisible(true) }
    override fun onStop() { model.controller.setVisible(false); super.onStop() }
}

