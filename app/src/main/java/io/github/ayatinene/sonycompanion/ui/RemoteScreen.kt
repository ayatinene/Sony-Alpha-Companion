package io.github.ayatinene.sonycompanion.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.ayatinene.sonycompanion.data.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val workspaceShape=RoundedCornerShape(10.dp)
private val scrim=Color.Black.copy(alpha=0.45f)
private val headerReserve=56.dp
internal const val REMOTE_HEADER_TIMEOUT_MILLIS=4_000L

/** Safe-area insets the controls must avoid; the image may extend underneath them. */
data class WorkspaceInsets(val top:Dp=0.dp,val bottom:Dp=0.dp,val left:Dp=0.dp,val right:Dp=0.dp)

data class RemoteActions(
    val onBack:()->Unit,
    val onDisconnect:()->Unit,
    val onToggleGrid:()->Unit,
    val onTools:()->Unit,
    val onFocus:()->Unit,
    val onCapture:()->Unit,
    val onMovie:()->Unit,
    val onSetSetting:(String,String)->Unit,
    val onOpenGallery:()->Unit = {}
)

@Composable fun RemoteScreen(state:AppState,c:CompanionController,snackbar:SnackbarHostState,onBack:()->Unit,openWifi:()->Unit) {
    val image by c.frame.collectAsStateWithLifecycle()
    var grid by rememberSaveable { mutableStateOf(false) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    var galleryPrompt by rememberSaveable { mutableStateOf(false) }
    val scope=rememberCoroutineScope()
    // Only the production capture page drives hardware rotation; RemoteWorkspace stays injectable so
    // its tests can keep exercising explicit portrait/landscape sizes. The host is keyed on the
    // Activity so recomposition never rebuilds it (which would restart the effect every frame).
    val view=LocalView.current
    val activity=remember(view) { view.context.findActivityOrNull() }
    val orientationHost=remember(activity) { activity?.let(::ActivityOrientationHost) }
    val sensorRotation=remember { c.prefs.getBoolean(REMOTE_SENSOR_ROTATION,REMOTE_SENSOR_ROTATION_DEFAULT) }
    if(orientationHost!=null) RemoteSensorOrientation(sensorRotation,orientationHost)
    val insets=rememberWorkspaceInsets()
    RemoteWorkspace(state,image,grid,insets,RemoteActions(
        onBack=onBack,onDisconnect=c::endSession,onToggleGrid={grid=!grid},onTools={advanced=true},
        onFocus=c::focus,onCapture=c::capture,onMovie=c::movie,onSetSetting=c::setSetting,
        onOpenGallery={
            if(state.remote.canBrowseGallery) galleryPrompt=true
            else scope.launch { snackbar.showSnackbar(REMOTE_GALLERY_UNSUPPORTED) }
        }),snackbar)
    if(advanced) CaptureTools(state,c,onDismiss={advanced=false},openWifi=openWifi)
    if(galleryPrompt) AlertDialog(
        onDismissRequest={galleryPrompt=false},
        title={Text("进入相机图库？")},
        text={Text("进入相机图库时将暂时停止实时取景和遥控拍摄，返回拍摄后可继续使用。")},
        confirmButton={TextButton(onClick={galleryPrompt=false;c.openRemoteGallery()}){Text("进入")}},
        dismissButton={TextButton(onClick={galleryPrompt=false}){Text("取消")}})
}

internal const val REMOTE_GALLERY_UNSUPPORTED="当前机内应用不支持相机图库，可使用 JPEG 传输或原文件连接。"

/**
 * Portrait stacks header / frame / panel so controls never cover the picture; landscape keeps the frame
 * full-bleed and floats bounded, scrollable columns at the edges. The image is fitted with [containRect].
 */
@Composable fun RemoteWorkspace(state:AppState,image:Bitmap?,grid:Boolean,insets:WorkspaceInsets,actions:RemoteActions,snackbar:SnackbarHostState) {
    BackHandler { actions.onBack() }
    ImmersiveSystemBars()
    Surface(Modifier.fillMaxSize(),color=Color.Black,contentColor=Color.White) {
        BoxWithConstraints(Modifier.fillMaxSize().testTag("remote-screen")) {
            if(maxWidth<maxHeight) PortraitWorkspace(state,image,grid,insets,actions)
            else LandscapeWorkspace(state,image,grid,insets,actions)
            SnackbarHost(snackbar,Modifier.align(Alignment.TopCenter).padding(top=insets.top+headerReserve,start=insets.left+8.dp,end=insets.right+8.dp))
        }
    }
}

@Composable private fun PortraitWorkspace(state:AppState,image:Bitmap?,grid:Boolean,insets:WorkspaceInsets,actions:RemoteActions) {
    Column(Modifier.fillMaxSize()) {
        WorkspaceHeader(state,grid,actions,Modifier.fillMaxWidth().testTag("remote-header").padding(top=insets.top+4.dp,start=insets.left+8.dp,end=insets.right+8.dp))
        Spacer(Modifier.height(4.dp))
        Box(Modifier.fillMaxWidth().weight(1f).background(Color.Black).testTag("remote-region")) {
            ViewFinder(state,image,grid,Modifier.fillMaxSize())
        }
        BottomPanel(state,actions,Modifier.fillMaxWidth().testTag("remote-panel").padding(top=4.dp,bottom=insets.bottom+4.dp,start=insets.left+8.dp,end=insets.right+8.dp))
    }
}

/** A bounded top slot prevents the header from inheriting the full overlay height. The parameter
 * and shutter columns are laid out below that slot instead of relying on an estimated offset. */
@Composable private fun BoxWithConstraintsScope.LandscapeWorkspace(state:AppState,image:Bitmap?,grid:Boolean,insets:WorkspaceInsets,actions:RemoteActions) {
    var headerVisible by remember { mutableStateOf(true) }
    var interaction by remember { mutableLongStateOf(0L) }
    var touching by remember { mutableStateOf(false) }
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val accessibility=LocalAccessibilityManager.current
    val timeout=accessibility?.calculateRecommendedTimeoutMillis(REMOTE_HEADER_TIMEOUT_MILLIS,
        containsIcons=true,containsText=true,containsControls=true) ?: REMOTE_HEADER_TIMEOUT_MILLIS
    LaunchedEffect(lifecycle) {
        if(lifecycle==Lifecycle.State.RESUMED) { headerVisible=true;interaction++ }
    }
    LaunchedEffect(interaction,touching,lifecycle,timeout) {
        if(!touching && lifecycle==Lifecycle.State.RESUMED) {
            delay(timeout)
            headerVisible=false
        }
    }
    // Observe in the initial pass without consuming input: AF/shutter, scrolling and menus keep
    // their own gestures. Holding a finger down never hides a control beneath that finger.
    Box(Modifier.fillMaxSize().pointerInput(Unit) {
        awaitPointerEventScope {
            while(true) {
                val event=awaitPointerEvent(PointerEventPass.Initial)
                touching=event.changes.any { it.pressed }
                headerVisible=true
                interaction++
            }
        }
    }) {
        ViewFinder(state,image,grid,Modifier.fillMaxSize().clickable(
            onClickLabel="显示顶部工具栏",onClick={headerVisible=true;interaction++}))
        Column(Modifier.align(Alignment.TopStart).fillMaxSize()
            .padding(top=insets.top+4.dp,bottom=insets.bottom+4.dp,start=insets.left+8.dp,end=insets.right+8.dp)) {
            // Keep this slot when hidden so the side controls and viewfinder do not jump.
            Box(Modifier.fillMaxWidth().height(headerReserve)) {
                if(headerVisible) WorkspaceHeader(state,grid,actions,
                    Modifier.align(Alignment.TopCenter).fillMaxWidth().testTag("remote-header"))
            }
            val sideWidth=minOf(150.dp,this@LandscapeWorkspace.maxWidth*0.28f)
            Row(Modifier.fillMaxWidth().weight(1f),horizontalArrangement=Arrangement.SpaceBetween) {
                Column(Modifier.widthIn(max=sideWidth).heightIn(max=(this@LandscapeWorkspace.maxHeight-insets.top-insets.bottom-headerReserve-8.dp).coerceAtLeast(0.dp))
                    .testTag("remote-params").clip(workspaceShape).background(scrim)
                    .verticalScroll(rememberScrollState()).padding(6.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    if(state.remote.settings.isEmpty()) Text("曝光参数请在机身调节",style=MaterialTheme.typography.bodySmall,color=Color.White.copy(alpha=.7f))
                    state.remote.settings.forEach { setting-> ParameterReadout(setting) { value->actions.onSetSetting(setting.key,value) } }
                }
                Column(Modifier.widthIn(max=sideWidth).heightIn(max=(this@LandscapeWorkspace.maxHeight-insets.top-insets.bottom-headerReserve-8.dp).coerceAtLeast(0.dp))
                    .testTag("remote-controls").clip(workspaceShape).background(scrim)
                    .verticalScroll(rememberScrollState()).padding(6.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    FocusControl(state,actions); ShutterControl(state,actions); MovieControl(state,actions)
                }
            }
        }
    }
}

@Composable private fun ViewFinder(state:AppState,image:Bitmap?,grid:Boolean,modifier:Modifier) {
    BoxWithConstraints(modifier) {
        val aspect=image?.let { it.width.toFloat()/it.height } ?: (3f/2)
        val rect=containRect(maxWidth.value,maxHeight.value,aspect)
        Box(Modifier.offset(rect.left.dp,rect.top.dp).size(rect.width.dp,rect.height.dp).testTag("remote-viewfinder")) {
            if(image!=null) Image(image.asImageBitmap(),"实时取景",Modifier.fillMaxSize(),contentScale=ContentScale.Fit)
            else ViewfinderPlaceholder(state)
            if(grid) Canvas(Modifier.matchParentSize().testTag("remote-grid")) {
                val (xs,ys)=gridLines(FitRect(0f,0f,size.width,size.height))
                xs.forEach { x->drawLine(Color.White.copy(alpha=.45f),Offset(x,0f),Offset(x,size.height),1f) }
                ys.forEach { y->drawLine(Color.White.copy(alpha=.45f),Offset(0f,y),Offset(size.width,y),1f) }
            }
        }
    }
}

@Composable private fun WorkspaceHeader(state:AppState,grid:Boolean,actions:RemoteActions,modifier:Modifier) {
    Row(modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
        Row(Modifier.weight(1f).clip(workspaceShape).background(scrim).padding(2.dp),verticalAlignment=Alignment.CenterVertically) {
            IconButton(onClick=actions.onBack) { Icon(Icons.Outlined.ArrowBack,"返回工作台",tint=Color.White) }
            Column(Modifier.weight(1f).padding(end=8.dp)) {
                Text(state.device?.displayName ?: if(state.connecting)"连接相机…" else "未连接",
                    style=MaterialTheme.typography.labelLarge,color=Color.White,maxLines=1,overflow=TextOverflow.Ellipsis)
                Text(statusText(state),style=MaterialTheme.typography.labelSmall,color=Color.White.copy(alpha=.7f),maxLines=1,overflow=TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.width(6.dp))
        Row(Modifier.clip(workspaceShape).background(scrim).padding(2.dp),verticalAlignment=Alignment.CenterVertically) {
            IconToggleButton(checked=grid,onCheckedChange={actions.onToggleGrid()}) { Icon(Icons.Outlined.Grid3x3,"构图网格",tint=Color.White) }
            // Blocked while shooting, recording, interval shooting or transferring; the backend re-checks
            // the exact status, so the UI never gates on a strict idle requirement.
            val recording=RemoteGalleryPolicy.isRecording(state.remote.status)
            val canGallery=state.connected&&!state.connecting&&!state.busy&&!state.transferring&&state.intervalRemaining==0&&!recording
            IconButton(onClick=actions.onOpenGallery,enabled=canGallery,modifier=Modifier.testTag("remote-gallery-entry")) {
                Icon(Icons.Outlined.PhotoAlbum,"相机图库",tint=if(canGallery)Color.White else Color.DarkGray)
            }
            IconButton(onClick=actions.onTools) { Icon(Icons.Outlined.Tune,"拍摄工具",tint=Color.White) }
            val canDisconnect=!state.transferring&&!state.connecting
            IconButton(onClick=actions.onDisconnect,enabled=canDisconnect) { Icon(Icons.Outlined.LinkOff,"断开相机",tint=if(canDisconnect)Color.White else Color.DarkGray) }
        }
    }
}

private fun statusText(state:AppState):String=when {
    state.connected->remoteStatusLabel(state.remote.status)
    state.connecting->"连接中…"
    else->state.message
}

@Composable private fun BottomPanel(state:AppState,actions:RemoteActions,modifier:Modifier) {
    Column(modifier.clip(workspaceShape).background(scrim).padding(8.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            if(state.remote.settings.isEmpty()) Text("曝光参数请在机身调节",Modifier.padding(6.dp),style=MaterialTheme.typography.bodySmall,color=Color.White.copy(alpha=.7f))
            state.remote.settings.forEach { setting-> ParameterReadout(setting) { value->actions.onSetSetting(setting.key,value) } }
        }
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceEvenly) {
            FocusControl(state,actions); ShutterControl(state,actions); MovieControl(state,actions)
        }
    }
}

@Composable private fun ViewfinderPlaceholder(state:AppState) {
    Column(Modifier.fillMaxSize().testTag("remote-placeholder"),verticalArrangement=Arrangement.Center,horizontalAlignment=Alignment.CenterHorizontally) {
        Icon(Icons.Outlined.Videocam,null,tint=Color(0xFF9AA0A6))
        Text(when {
            state.connecting->"连接中…"
            "startLiveview" in state.remote.apis->"等待实时画面…"
            else->"相机未开放实时取景"
        },color=Color(0xFF9AA0A6),style=MaterialTheme.typography.bodySmall)
    }
}

@Composable private fun ParameterReadout(setting:Setting,onSelect:(String)->Unit) {
    var open by remember { mutableStateOf(false) }
    val writable=setting.writable&&setting.choices.isNotEmpty()
    Box {
        Column(Modifier.clip(workspaceShape).background(Color.White.copy(alpha=.08f))
            .then(if(writable) Modifier.clickable { open=true } else Modifier).padding(horizontal=10.dp,vertical=6.dp)) {
            Text(setting.label,style=MaterialTheme.typography.labelSmall,color=Color.White.copy(alpha=.6f),maxLines=1,overflow=TextOverflow.Ellipsis)
            Row(verticalAlignment=Alignment.CenterVertically) {
                Text(setting.display(setting.current),style=MaterialTheme.typography.titleSmall,fontWeight=FontWeight.Medium,color=Color.White,maxLines=1,overflow=TextOverflow.Ellipsis)
                if(writable) Icon(Icons.Outlined.ExpandMore,null,Modifier.size(14.dp),tint=Color.White.copy(alpha=.7f))
            }
        }
        DropdownMenu(expanded=open,onDismissRequest={open=false},modifier=Modifier.heightIn(max=350.dp)) {
            setting.choices.forEach { value->DropdownMenuItem(text={Text(setting.display(value))},onClick={open=false;onSelect(value)}) }
        }
    }
}

@Composable private fun FocusControl(state:AppState,actions:RemoteActions) {
    OutlinedButton(onClick=actions.onFocus,enabled=state.connected && "actHalfPressShutter" in state.remote.apis && "cancelHalfPressShutter" in state.remote.apis,
        shape=workspaceShape,contentPadding=PaddingValues(horizontal=16.dp,vertical=10.dp)) { Text("AF") }
}
@Composable private fun ShutterControl(state:AppState,actions:RemoteActions) {
    val enabled=state.connected && "actTakePicture" in state.remote.apis && state.intervalRemaining==0
    Box(Modifier.size(72.dp).border(2.dp,if(enabled)Color.White else Color.DarkGray,CircleShape).padding(6.dp),contentAlignment=Alignment.Center) {
        FilledIconButton(onClick=actions.onCapture,enabled=enabled,modifier=Modifier.fillMaxSize(),shape=CircleShape,
            colors=IconButtonDefaults.filledIconButtonColors(containerColor=Color.White,contentColor=Color(0xFF202020))) {
            Icon(Icons.Outlined.CameraAlt,"拍照")
        }
    }
}
@Composable private fun MovieControl(state:AppState,actions:RemoteActions) {
    val recording=state.remote.status.contains("MovieRecording",true)
    FilledTonalIconButton(onClick=actions.onMovie,enabled=state.connected && (if(recording)"stopMovieRec" else "startMovieRec") in state.remote.apis,
        modifier=Modifier.size(48.dp),shape=workspaceShape) {
        Icon(if(recording)Icons.Outlined.Stop else Icons.Outlined.Videocam,if(recording)"停止录像" else "开始录像",tint=if(recording)Color(0xFFFF6B63) else LocalContentColor.current)
    }
}

@Composable private fun CaptureTools(state:AppState,c:CompanionController,onDismiss:()->Unit,openWifi:()->Unit) {
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

/** Hides the system bars; an edge swipe reveals them transiently, exit restores the previous behavior. */
@Composable private fun ImmersiveSystemBars() {
    val view=LocalView.current
    DisposableEffect(view) {
        val window=view.context.findActivity()?.window
        if(window==null) return@DisposableEffect onDispose { }
        val controller=WindowCompat.getInsetsController(window,view)
        val previous=controller.systemBarsBehavior
        controller.systemBarsBehavior=WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            controller.show(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior=previous
        }
    }
}

/** Controls avoid the cutout and gesture areas, which stay meaningful while the bars are hidden. */
@Composable fun rememberWorkspaceInsets():WorkspaceInsets {
    val density=LocalDensity.current
    val layoutDirection=LocalLayoutDirection.current
    val cutout=WindowInsets.displayCutout
    val gestures=WindowInsets.systemGestures
    return with(density) {
        WorkspaceInsets(
            top=maxOf(cutout.getTop(this),gestures.getTop(this)).toDp(),
            bottom=maxOf(cutout.getBottom(this),gestures.getBottom(this)).toDp(),
            left=maxOf(cutout.getLeft(this,layoutDirection),gestures.getLeft(this,layoutDirection)).toDp(),
            right=maxOf(cutout.getRight(this,layoutDirection),gestures.getRight(this,layoutDirection)).toDp())
    }
}

private tailrec fun Context.findActivity():Activity?=when(this) {
    is Activity->this
    is ContextWrapper->baseContext.findActivity()
    else->null
}

