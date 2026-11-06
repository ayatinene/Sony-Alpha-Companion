@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package io.github.ayatinene.sonycompanion.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.ayatinene.sonycompanion.data.*
import io.github.ayatinene.sonycompanion.BuildConfig
import kotlinx.coroutines.*

private val shape=RoundedCornerShape(10.dp)
private val ShellNavSaver=listSaver<ShellNav,Any>(
    save={ listOf(it.destination.name,it.phase.name,it.entryTarget?.name.orEmpty()) },
    restore={ ShellNav(Destination.valueOf(it[0] as String),ConnectionPhase.valueOf(it[1] as String),(it[2] as String).takeIf(String::isNotEmpty)?.let { name -> Destination.valueOf(name) }) })
private fun Destination.icon():ImageVector=when(this) {
    Destination.Home->Icons.Outlined.Home
    Destination.Capture->Icons.Outlined.CameraAlt
    Destination.Gallery->Icons.Outlined.PhotoAlbum
    Destination.Local->Icons.Outlined.PhotoLibrary
    Destination.Settings->Icons.Outlined.Settings
}
private fun connectionSubtitle(state:AppState):String=when {
    state.connecting->"正在连接…"
    state.connected->state.device?.displayName ?: "已连接"
    else->"未连接"
}

/** Permanently denied permissions can only be fixed in system settings; offer that path instead of
 *  re-prompting in a loop. */
private fun openAppSettings(context:Context) {
    runCatching {
        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.fromParts("package",context.packageName,null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

@Composable fun CompanionApp(model:CompanionViewModel,openWifi:()->Unit,requestPermissions:()->Unit) {
    val controller=model.controller
    val state by controller.state.collectAsStateWithLifecycle()
    val context=LocalContext.current
    var nav by rememberSaveable(stateSaver=ShellNavSaver) { mutableStateOf(ShellNav()) }
    var theme by remember { mutableStateOf(controller.prefs.getString("theme","system")!!) }
    var dynamic by remember { mutableStateOf(controller.prefs.getBoolean("dynamic",false)) }
    val captureReady=state.connected && state.mode==CameraMode.REMOTE && !state.remote.galleryActive
    val immersive=nav.destination==Destination.Capture && captureReady
    val shown=when {
        nav.destination==Destination.Capture && !captureReady->Destination.Home
        nav.destination==Destination.Gallery && !state.connected->Destination.Home
        else->nav.destination
    }
    val dark=when(theme) { "dark"->true;"light"->false;else->isSystemInDarkTheme() }
    val colors=if(immersive) darkColorScheme(primary=Color(0xFFF4AE78),surface=Color(0xFF14171B),background=Color(0xFF080B0E))
        else if(dynamic && android.os.Build.VERSION.SDK_INT>=31) { if(dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context) }
        else if(dark) darkColorScheme(primary=Color(0xFFF4AE78),secondary=Color(0xFF9CC9C5),surface=Color(0xFF111820),background=Color(0xFF111820))
        else lightColorScheme(primary=Color(0xFF914C1D),secondary=Color(0xFF356964),surface=Color(0xFFF8F9FC),background=Color(0xFFF8F9FC))
    val snackbar=remember { SnackbarHostState() }
    val scope=rememberCoroutineScope()
    val permissionAskStore=remember(controller) { PrefsPermissionAskStore(controller.prefs) }
    // One guard for every mode card and the reconnect entry: connect when nothing is needed, otherwise
    // ask the missing permissions once and auto-continue; a notification denial still connects.
    val connectWithPermissions=rememberConnectionGuard(
        onConnect={ mode->if(controller.connect(mode)) nav=nav.requestConnection(mode) },
        onDenied={ message->scope.launch { snackbar.showSnackbar(message) } },
        onPermanentDenied={ message->scope.launch {
            if(snackbar.showSnackbar(message,actionLabel="打开设置")==SnackbarResult.ActionPerformed) openAppSettings(context)
        } },
        onNotificationDenied={ message->scope.launch { snackbar.showSnackbar(message) } },
        askStore=permissionAskStore)
    val pairing=rememberPairingActions(model,
        onConnecting={ nav=nav.requestConnection(CameraMode.REMOTE) },
        onMessage={ message->scope.launch { snackbar.showSnackbar(message) } })
    // Geotagging lives at app scope, so a permission revoked outside Settings is corrected on resume
    // instead of leaving the preference on while the switch reads off.
    var geotagging by rememberSaveable { mutableStateOf(controller.remoteGeotaggingEnabled && controller.locationPermissionGranted()) }
    var pendingLocation by rememberSaveable { mutableStateOf(false) }
    var askedLocation by rememberSaveable(stateSaver=permissionSetSaver) { mutableStateOf(permissionAskStore.asked()) }
    fun applyGeotagging(granted:Boolean) {
        pendingLocation=false
        geotagging=granted
        controller.setRemoteGeotagging(granted)
        controller.refreshLocationSettings()
        if(!granted) scope.launch { snackbar.showSnackbar(RemoteGeotaggingPolicy.DENIED_MESSAGE) }
    }
    val locationLauncher=rememberPermissionLauncher { results ->
        if(pendingLocation) applyGeotagging(RemoteGeotaggingPolicy.anyGranted(results) || controller.locationPermissionGranted())
    }
    fun canAskLocationAgain(permission:String):Boolean =
        context.findActivityOrNull()?.shouldShowRequestPermissionRationale(permission)==true
    fun enableGeotagging() {
        val plan=planPermissionRequest(RemoteGeotaggingPolicy.PERMISSIONS,emptyList(),{ controller.locationPermissionGranted() },askedLocation,::canAskLocationAgain)
        when {
            controller.locationPermissionGranted() -> applyGeotagging(true)
            plan.blockedPermanently -> scope.launch {
                if(snackbar.showSnackbar(RemoteGeotaggingPolicy.PERMANENT_MESSAGE,actionLabel="打开设置")==SnackbarResult.ActionPerformed) openAppSettings(context)
            }
            else -> {
                askedLocation=askedLocation+plan.request
                permissionAskStore.markAsked(plan.request)
                pendingLocation=true
                locationLauncher.launch(plan.request)
            }
        }
    }
    fun disableGeotagging() { geotagging=false;pendingLocation=false;controller.setRemoteGeotagging(false) }
    fun reconcileRevokedGeotagging() {
        if(controller.remoteGeotaggingEnabled && !controller.locationPermissionGranted()) {
            geotagging=false
            controller.setRemoteGeotagging(false)
            scope.launch { snackbar.showSnackbar(RemoteGeotaggingPolicy.REVOKED_MESSAGE) }
        }
    }
    LaunchedEffect(Unit) { reconcileRevokedGeotagging() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { reconcileRevokedGeotagging() }
    LaunchedEffect(state.error) { state.error?.let { snackbar.showSnackbar(it,duration=SnackbarDuration.Long); controller.clearError() } }
    LaunchedEffect(state.connected,state.connecting,state.mode,state.remote.galleryActive) { nav=nav.observe(state.connected,state.connecting,state.mode,state.remote.galleryActive) }
    MaterialTheme(colorScheme=colors,shapes=Shapes(small=shape,medium=shape,large=RoundedCornerShape(16.dp))) {
        if(immersive) {
            RemoteScreen(state,controller,snackbar,onBack={ nav=nav.copy(destination=Destination.Home,entryTarget=null) },openWifi=openWifi)
        } else {
            val items=destinationsFor(state.connected,state.mode,state.remote.galleryActive)
            Scaffold(contentWindowInsets=WindowInsets.safeDrawing,snackbarHost={SnackbarHost(snackbar)},topBar={
                TopAppBar(title={ Column {
                    Text("Alpha Companion",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.SemiBold)
                    Text(connectionSubtitle(state),style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                } },actions={
                    if(state.connected) IconButton(onClick=controller::endSession,enabled=!state.transferring) { Icon(Icons.Outlined.LinkOff,"结束连接") }
                    IconButton(onClick=openWifi) { Icon(Icons.Outlined.Wifi,"Wi-Fi 设置") }
                })
            },bottomBar={NavigationBar {
                items.forEach { d -> NavigationBarItem(selected=shown==d,onClick={ if(d!=nav.destination) nav=nav.copy(destination=d,entryTarget=null) },
                    icon={Icon(d.icon(),d.title())},label={Text(d.title())},alwaysShowLabel=true) }
            }}) { padding ->
                Column(Modifier.fillMaxSize().padding(padding)) {
                    if(state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if(state.oneTouchImport) OneTouchImportStatus(state,controller::cancelOneTouchImport)
                    when(shown) {
                        Destination.Home,Destination.Capture->Home(state,controller,
                            onOpenWorkspace={ nav=nav.copy(destination=workspaceFor(state.mode,state.remote.galleryActive),entryTarget=null) },
                            onConnect=connectWithPermissions,permissions=requestPermissions,pairing=pairing)
                        Destination.Gallery->if(state.oneTouchImport) Column(Modifier.fillMaxSize().padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                            Text("正在导入相机共享照片",style=MaterialTheme.typography.titleMedium)
                            QueuePanel(state,controller.galleryActions())
                            Text("照片保存到 DCIM/AlphaCompanion；完成后自动结束连接。",style=MaterialTheme.typography.bodyMedium)
                        } else CameraGallery(state,controller.galleryActions())
                        Destination.Local->LocalLibraryScreen(state,controller)
                        Destination.Settings->SettingsPage(state,controller,theme,dynamic,{theme=it;controller.prefs.edit().putString("theme",it).apply()}, {dynamic=it;controller.prefs.edit().putBoolean("dynamic",it).apply()},requestPermissions,geotagging,::enableGeotagging,::disableGeotagging)
                    }
                }
            }
        }
    }
}

@Composable internal fun OneTouchImportStatus(state: AppState, onCancel: ()->Unit) {
    Surface(color=MaterialTheme.colorScheme.surfaceContainer,modifier=Modifier.fillMaxWidth().testTag("one-touch-import")) {
        Row(Modifier.padding(horizontal=16.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically) {
            Text(state.message,Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium)
            TextButton(onClick=onCancel,enabled=state.connected||state.connecting) { Text("取消导入") }
        }
    }
}

@Composable private fun Home(state:AppState,c:CompanionController,onOpenWorkspace:()->Unit,onConnect:(CameraMode)->Unit,permissions:()->Unit,pairing:PairingActions) {
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
        item { Text("相机工作台",style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.SemiBold) }
        item { HomeStatusCard(state) }
        if(state.connecting) item { OutlinedButton(onClick=c::cancelPairing,shape=shape,modifier=Modifier.fillMaxWidth()) { Text("取消连接") } }
        if(!state.connected) item {
            Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick=pairing.scan,enabled=!state.busy&&!state.connecting,shape=shape,modifier=Modifier.fillMaxWidth().testTag("pairing-scan")) {
                    Icon(Icons.Outlined.QrCodeScanner,null); Spacer(Modifier.width(8.dp)); Text("扫描相机二维码")
                }
                OutlinedButton(onClick=pairing.nfc,enabled=!state.busy&&!state.connecting,shape=shape,modifier=Modifier.fillMaxWidth().testTag("pairing-nfc")) {
                    Icon(Icons.Outlined.Nfc,null); Spacer(Modifier.width(8.dp)); Text("NFC 配对")
                }
            }
        }
        if(state.connected) {
            item { Button(onClick=onOpenWorkspace,shape=shape,modifier=Modifier.fillMaxWidth()) {
                val remoteCapture=state.mode==CameraMode.REMOTE&&!state.remote.galleryActive
                Icon(if(remoteCapture)Icons.Outlined.CameraAlt else Icons.Outlined.PhotoAlbum,null)
                Spacer(Modifier.width(8.dp))
                Text(if(state.oneTouchImport) "查看导入进度" else if(remoteCapture) "进入拍摄" else "打开相机图库")
            } }
            item { OutlinedButton(onClick={ state.mode?.let(onConnect) },enabled=!state.connecting&&!state.busy&&!state.transferring,shape=shape,modifier=Modifier.fillMaxWidth()) { Text("重新连接相机") } }
            item { OutlinedButton(onClick=c::endSession,enabled=!state.busy&&!state.transferring,shape=shape,modifier=Modifier.fillMaxWidth()) { Icon(Icons.Outlined.LinkOff,null);Spacer(Modifier.width(8.dp));Text("断开相机") } }
        } else {
            items(CameraMode.entries) { mode ->
                OutlinedCard(shape=shape,modifier=Modifier.fillMaxWidth().testTag("home-mode-card")) { Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        Icon(when(mode){CameraMode.REMOTE->Icons.Outlined.CameraAlt;CameraMode.JPEG->Icons.Outlined.FileDownload;else->Icons.Outlined.RawOn},null,Modifier.size(26.dp))
                        Text(mode.title,style=MaterialTheme.typography.titleMedium)
                    }
                    Text(mode.instruction,style=MaterialTheme.typography.bodyMedium)
                    Button(onClick={onConnect(mode)},enabled=!state.connecting&&!state.busy&&!state.transferring,shape=shape) { Text(if(state.connecting) "连接中…" else "连接") }
                } }
            }
        }
        item { TextButton(onClick=permissions) { Icon(Icons.Outlined.Security,null); Spacer(Modifier.width(8.dp)); Text("权限设置") } }
        if(state.local.isNotEmpty()) item { Text("最近导入：${state.local.first().name}",style=MaterialTheme.typography.bodyMedium) }
    }
}

@Composable internal fun HomeStatusCard(state:AppState,modifier:Modifier=Modifier) {
    Surface(modifier=modifier.fillMaxWidth().testTag("home-status-card"),color=MaterialTheme.colorScheme.surfaceContainer,shape=shape) {
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(8.dp).background(if(state.connected)Color(0xFF5CA681) else MaterialTheme.colorScheme.outline,CircleShape))
                Text(when { state.connected->"已连接"; state.connecting->"连接中"; else->"等待连接" },fontWeight=FontWeight.Medium)
            }
            Text(state.message,style=MaterialTheme.typography.bodyMedium)
            (state.error ?: state.scan.error?.takeIf { it.isNotBlank() })?.let {
                Text(it,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)
            }
            if(state.scan.phase==ScanPhase.Scanning) Text("已发现 ${state.scan.found} 个文件",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(state.queueNotice.isNotBlank()) Text(state.queueNotice,style=MaterialTheme.typography.bodySmall)
            state.device?.let { device ->
                Text(device.displayName+device.identity.firmware.takeIf { it.isNotBlank() }?.let { " · 固件 $it" }.orEmpty(),style=MaterialTheme.typography.labelLarge)
                if(device.identity.lens.isNotBlank()) Text(device.identity.lens,style=MaterialTheme.typography.bodySmall)
            }
            if(!state.connected) Text("可扫描智能遥控二维码或触碰 NFC；也可在系统连接 Wi-Fi 后选择下方模式。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable private fun SettingsPage(state:AppState,c:CompanionController,theme:String,dynamic:Boolean,setTheme:(String)->Unit,setDynamic:(Boolean)->Unit,permissions:()->Unit,geotagging:Boolean,onEnableGeotagging:()->Unit,onDisableGeotagging:()->Unit) {
    val context=LocalContext.current
    val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")){uri->if(uri!=null)c.exportDiagnostics(uri)}
    var description by remember { mutableStateOf(c.prefs.getString("description","").orEmpty()) }
    var host by remember { mutableStateOf(c.prefs.getString("computerHost","").orEmpty()) }
    var guid by remember { mutableStateOf(c.prefs.getString("guid","").orEmpty()) }
    var sonySleep by remember { mutableStateOf(c.prefs.getBoolean(SONY_SLEEP_ON_DISCONNECT,false)) }
    var sensorRotation by remember { mutableStateOf(c.prefs.getBoolean(REMOTE_SENSOR_ROTATION,REMOTE_SENSOR_ROTATION_DEFAULT)) }
    var saved by remember { mutableStateOf(false) }
    var logLevel by remember { mutableStateOf(c.journal.minimum) }
    var logMenu by remember { mutableStateOf(false) }
    var usbInfo by remember { mutableStateOf("") }
    var registerUsb by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item { Text("外观",style=MaterialTheme.typography.titleMedium) }
        item { Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) { listOf("system" to "跟随系统","light" to "浅色","dark" to "深色").forEach{(value,label)->FilterChip(theme==value,{setTheme(value)},label={Text(label)})} } }
        item { Row(verticalAlignment=Alignment.CenterVertically){Text("Material You 动态配色",Modifier.weight(1f));Switch(dynamic,setDynamic)} }
        item { HorizontalDivider() }
        item { Text("连接",style=MaterialTheme.typography.titleMedium) }
        state.device?.let { device ->
            item { Text(device.displayName+device.identity.firmware.takeIf { it.isNotBlank() }?.let { " · 固件 $it" }.orEmpty(),style=MaterialTheme.typography.bodySmall) }
            if(device.identity.lens.isNotBlank()) item { Text("镜头：${device.identity.lens}"+device.identity.lensFirmware.takeIf { it.isNotBlank() }?.let { " · 固件 $it" }.orEmpty(),style=MaterialTheme.typography.bodySmall) }
        }
        item { Text("遥控拍摄可在首页扫描二维码或使用 NFC。JPEG / 原文件传输及手动遥控仍可先连接系统 Wi-Fi。",style=MaterialTheme.typography.bodySmall) }
        item { OutlinedTextField(description,{description=it;saved=false},Modifier.fillMaxWidth(),label={Text("描述文件 URL / JPEG 模式相机 IP")},placeholder={Text("留空自动查找")},singleLine=true) }
        item { Text("RAW / 原文件传输（实验性）",style=MaterialTheme.typography.titleSmall) }
        item { Text("使用已登记的接收端 GUID；也可通过下方 USB 工具登记。",style=MaterialTheme.typography.bodySmall) }
        item { OutlinedTextField(host,{host=it;saved=false},Modifier.fillMaxWidth(),label={Text("相机局域网 IP（留空自动发现）")},singleLine=true) }
        item { OutlinedTextField(guid,{guid=it;saved=false},Modifier.fillMaxWidth(),label={Text("接收端 16 字节 GUID")},supportingText={Text("32 位十六进制，可含冒号")},singleLine=true) }
        item { Button(onClick={c.prefs.edit().putString("description",description.trim()).putString("computerHost",host.trim()).putString("guid",guid.trim()).apply();saved=true},shape=shape){Text(if(saved) "已保存" else "保存连接设置")} }
        item { OutlinedButton(onClick={usbInfo=c.usbStatus();registerUsb=true},enabled=!state.connected&&!state.busy&&!state.transferring,shape=shape) { Icon(Icons.Outlined.Usb,null);Spacer(Modifier.width(8.dp));Text("USB OTG 登记接收端 · 实验性") } }
        item { Text("相机 USB 连接选择“海量存储”，通过数据线和 OTG 连接手机后登记。成功后拔下 USB，再连接 Wi-Fi 使用原文件传输。",style=MaterialTheme.typography.bodySmall) }
        item { SonySleepSetting(sonySleep){ enabled->sonySleep=enabled;c.prefs.edit().putBoolean(SONY_SLEEP_ON_DISCONNECT,enabled).apply() } }
        item { TextButton(onClick=permissions){Text("权限设置")} }
        item { HorizontalDivider() }
        item { Text("遥控拍摄",style=MaterialTheme.typography.titleMedium) }
        item { RemoteSensorRotationSetting(sensorRotation){ enabled->sensorRotation=enabled;c.prefs.edit().putBoolean(REMOTE_SENSOR_ROTATION,enabled).apply() } }
        item { RemoteGeotaggingSetting(geotagging,onEnableRequest=onEnableGeotagging,onDisable=onDisableGeotagging) }
        item { HorizontalDivider() }
        item { Text("存储与诊断",style=MaterialTheme.typography.titleMedium) }
        item { Text("照片保存在 DCIM/AlphaCompanion。\nNFC 一触分享会自动导入相机共享的 JPEG（最大可用尺寸），结束后断开连接。\n其他预览不会自动保存，点击下载才保存到系统图库。\n相机图库中未完成的下载可在重连后继续，重试会从头下载。\n本地图库可删除文件，或仅清除导入记录。",style=MaterialTheme.typography.bodySmall) }
        item { Box {
            OutlinedButton(onClick={logMenu=true},shape=shape){Text("记录级别：${logLevel.name}")}
            DropdownMenu(logMenu,{logMenu=false}) { LogLevel.entries.forEach { level ->
                DropdownMenuItem(text={Text(level.name)},onClick={logLevel=level;c.setLogLevel(level);logMenu=false})
            } }
        } }
        item { Text("遇到问题可导出日志。日志保存在本机，可能包含相机地址和文件名，分享前请留意。TRACE 记录更详细。",style=MaterialTheme.typography.bodySmall) }
        item { OutlinedButton(onClick={export.launch(DiagnosticFiles.name(state.mode))},shape=shape){Icon(Icons.Outlined.BugReport,null);Spacer(Modifier.width(8.dp));Text("导出日志与诊断")} }
        item { HorizontalDivider() }
        item { Text("关于与许可",style=MaterialTheme.typography.titleMedium) }
        item { Text("版本 ${BuildConfig.VERSION_NAME}\n基于 SonyRX10M3Remote、ImagingEdge4Linux、SonyPhotoTransfer / libptp。GPL-3.0；libptp 保留 LGPL-2.1。\n二维码扫描：ZXing Android Embedded / ZXing · Apache-2.0。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    if(registerUsb) AlertDialog(onDismissRequest={registerUsb=false},title={Text("登记相机的接收端？")},text={
        Text("$usbInfo\n\n这会替换相机“发送到电脑”的接收端设置。"+(if(guid.isBlank()) "将生成并保存本应用专用 GUID。" else "将登记输入框中的 GUID。")+"如需保留电脑接收端，请先保存其 GUID。")
    },confirmButton={TextButton(onClick={registerUsb=false;c.registerUsbGuid(guid) { guid=it }}){Text("登记")}},dismissButton={TextButton(onClick={registerUsb=false}){Text("取消")}})
}

