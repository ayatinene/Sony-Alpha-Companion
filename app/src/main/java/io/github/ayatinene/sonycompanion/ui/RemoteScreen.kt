package io.github.ayatinene.sonycompanion.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.ayatinene.sonycompanion.data.*

@Composable fun RemoteScreen(state: AppState, c: CompanionController, onBack: ()->Unit, openWifi: ()->Unit) {
    val image by c.frame.collectAsStateWithLifecycle()
    var grid by rememberSaveable { mutableStateOf(false) }
    var advanced by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(Color(0xFF080B0E)).testTag("remote-screen")) {
        Row(Modifier.fillMaxWidth().heightIn(min=48.dp),verticalAlignment=Alignment.CenterVertically) {
            IconButton(onClick=onBack) { Icon(Icons.Outlined.ArrowBack,"返回工作台") }
            Column(Modifier.weight(1f)) {
                Text(state.device?.displayName ?: "连接相机…",style=MaterialTheme.typography.titleSmall,maxLines=1,overflow=TextOverflow.Ellipsis)
                Text(if(state.connected)state.remote.status.ifBlank { "已连接" }else state.message,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1)
            }
            IconToggleButton(grid,{grid=it}) { Icon(Icons.Outlined.Grid3x3,"构图网格") }
            IconButton(onClick={advanced=true}) { Icon(Icons.Outlined.Tune,"拍摄工具") }
            IconButton(onClick=c::endSession,enabled=!state.transferring&&!state.busy) { Icon(Icons.Outlined.LinkOff,"断开相机") }
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val landscape=maxWidth>maxHeight
            val viewfinder: @Composable (Modifier)->Unit = { modifier ->
                BoxWithConstraints(modifier.background(Color.Black).testTag("remote-viewfinder"),contentAlignment=Alignment.Center) {
                    val ratio=image?.let { it.width.toFloat()/it.height } ?: 3f/2
                    val fitted=if(maxWidth/maxHeight>ratio) Modifier.fillMaxHeight().aspectRatio(ratio)
                        else Modifier.fillMaxWidth().aspectRatio(ratio)
                    Box(fitted,contentAlignment=Alignment.Center) {
                        if(image!=null) Image(image!!.asImageBitmap(),"实时取景",Modifier.fillMaxSize(),contentScale=ContentScale.Fit)
                        else Column(horizontalAlignment=Alignment.CenterHorizontally) {
                            Icon(Icons.Outlined.Videocam,null,tint=Color.LightGray)
                            Text(if(state.busy)"连接中…" else if("startLiveview" in state.remote.apis)"等待实时画面…" else "相机未开放实时取景",color=Color.LightGray,style=MaterialTheme.typography.bodySmall)
                        }
                        if(grid) Canvas(Modifier.matchParentSize()) { for(i in 1..2) {
                            drawLine(Color.White.copy(alpha=.4f),Offset(size.width*i/3,0f),Offset(size.width*i/3,size.height),1f)
                            drawLine(Color.White.copy(alpha=.4f),Offset(0f,size.height*i/3),Offset(size.width,size.height*i/3),1f)
                        } }
                    }
                }
            }
            if(landscape) Row(Modifier.fillMaxSize(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Column(Modifier.width(132.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(start=8.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    ExposureControls(state,c)
                }
                viewfinder(Modifier.weight(1f).fillMaxHeight())
                Column(Modifier.width(84.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(vertical=8.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(18.dp,Alignment.CenterVertically)) {
                    FocusControl(state,c); ShutterControl(state,c); MovieControl(state,c)
                }
            } else Column(Modifier.fillMaxSize()) {
                viewfinder(Modifier.weight(1f).fillMaxWidth())
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(8.dp),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    if(state.remote.settings.isEmpty()) Text("曝光参数请在机身调节",Modifier.padding(8.dp),style=MaterialTheme.typography.bodySmall)
                    state.remote.settings.forEach { setting -> Box(Modifier.width(112.dp)) { SettingControl(setting) { c.setSetting(setting.key,it) } } }
                }
                Row(Modifier.fillMaxWidth().padding(bottom=12.dp,top=4.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceEvenly) {
                    FocusControl(state,c); ShutterControl(state,c); MovieControl(state,c)
                }
            }
        }
    }
    if(advanced) CaptureTools(state,c,onDismiss={advanced=false},openWifi=openWifi)
}

@Composable private fun ExposureControls(state: AppState, c: CompanionController) {
    state.remote.settings.forEach { setting -> SettingControl(setting) { c.setSetting(setting.key,it) } }
    if(state.remote.settings.isEmpty()) Text("曝光参数请在机身调节",Modifier.padding(8.dp),style=MaterialTheme.typography.bodySmall)
}
@Composable private fun FocusControl(state: AppState,c: CompanionController) {
    OutlinedButton(onClick=c::focus,enabled=state.connected && "actHalfPressShutter" in state.remote.apis && "cancelHalfPressShutter" in state.remote.apis,
        shape=RoundedCornerShape(8.dp),contentPadding=PaddingValues(horizontal=16.dp,vertical=10.dp)) { Text("AF") }
}
@Composable private fun ShutterControl(state: AppState,c: CompanionController) {
    val enabled=state.connected && "actTakePicture" in state.remote.apis && state.intervalRemaining==0
    Box(Modifier.size(72.dp).border(2.dp,if(enabled)Color.White else Color.DarkGray,CircleShape).padding(6.dp),contentAlignment=Alignment.Center) {
        FilledIconButton(onClick=c::capture,enabled=enabled,modifier=Modifier.fillMaxSize(),shape=CircleShape,
            colors=IconButtonDefaults.filledIconButtonColors(containerColor=Color.White,contentColor=Color(0xFF202020))) {
            Icon(Icons.Outlined.CameraAlt,"拍照")
        }
    }
}
@Composable private fun MovieControl(state: AppState,c: CompanionController) {
    val recording=state.remote.status.contains("MovieRecording",true)
    FilledTonalIconButton(onClick=c::movie,enabled=state.connected && (if(recording)"stopMovieRec" else "startMovieRec") in state.remote.apis,
        modifier=Modifier.size(48.dp),shape=RoundedCornerShape(10.dp)) {
        Icon(if(recording)Icons.Outlined.Stop else Icons.Outlined.Videocam,if(recording)"停止录像" else "开始录像",tint=if(recording)Color(0xFFFF6B63)else LocalContentColor.current)
    }
}

@Composable private fun CaptureTools(state: AppState,c: CompanionController,onDismiss: ()->Unit,openWifi: ()->Unit) {
    var count by rememberSaveable { mutableStateOf("10") }
    var seconds by rememberSaveable { mutableStateOf("5") }
    AlertDialog(onDismissRequest=onDismiss,title={Text("拍摄工具")},text={
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("间隔拍摄",style=MaterialTheme.typography.titleSmall)
            OutlinedTextField(count,{count=it},label={Text("张数")},singleLine=true)
            OutlinedTextField(seconds,{seconds=it},label={Text("拍完后间隔 / 秒")},singleLine=true)
            if(state.intervalRemaining>0) OutlinedButton(onClick=c::stopInterval) { Text("停止 · 剩余 ${state.intervalRemaining} 张") }
            else Button(onClick={c.interval(count.toIntOrNull()?:10,seconds.toIntOrNull()?:5);onDismiss()},enabled="actTakePicture" in state.remote.apis) { Text("开始间隔拍摄") }
            Text("间隔拍摄期间保持应用在前台。",style=MaterialTheme.typography.bodySmall)
            HorizontalDivider()
            TextButton(onClick={c.reconnect();onDismiss()}) { Text("重新连接相机") }
            TextButton(onClick=openWifi) { Text("Wi-Fi 设置") }
        }
    },confirmButton={TextButton(onClick=onDismiss) { Text("关闭") }})
}
