@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package io.github.ayatinene.sonycompanion.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.ayatinene.sonycompanion.data.*
import io.github.ayatinene.sonycompanion.BuildConfig
import kotlinx.coroutines.*
import java.util.Locale

private val shape=RoundedCornerShape(10.dp)
private data class Destination(val title:String,val icon:ImageVector)
private val destinations=listOf(Destination("首页",Icons.Outlined.Home),Destination("相机",Icons.Outlined.CameraAlt),Destination("本地",Icons.Outlined.PhotoLibrary),Destination("设置",Icons.Outlined.Settings))

@Composable fun CompanionApp(model:CompanionViewModel,openWifi:()->Unit,requestPermissions:()->Unit) {
    val controller=model.controller
    val state by controller.state.collectAsStateWithLifecycle()
    val context=LocalContext.current
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var theme by remember { mutableStateOf(controller.prefs.getString("theme","system")!!) }
    var dynamic by remember { mutableStateOf(controller.prefs.getBoolean("dynamic",false)) }
    val dark=when(theme) { "dark"->true;"light"->false;else->isSystemInDarkTheme() }
    val colors=if(dynamic && android.os.Build.VERSION.SDK_INT>=31) { if(dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context) }
        else if(dark) darkColorScheme(primary=Color(0xFFF4AE78),secondary=Color(0xFF9CC9C5),surface=Color(0xFF111820),background=Color(0xFF111820))
        else lightColorScheme(primary=Color(0xFF914C1D),secondary=Color(0xFF356964),surface=Color(0xFFF8F9FC),background=Color(0xFFF8F9FC))
    val snackbar=remember { SnackbarHostState() }
    LaunchedEffect(state.error) { state.error?.let { snackbar.showSnackbar(it,duration=SnackbarDuration.Long); controller.clearError() } }
    MaterialTheme(colorScheme=colors,shapes=Shapes(small=shape,medium=shape,large=RoundedCornerShape(16.dp))) {
        Scaffold(snackbarHost={SnackbarHost(snackbar)},topBar={
            TopAppBar(title={ Column {
                Text("Alpha Companion",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.SemiBold)
                Text(state.device?.model?.ifBlank { state.device?.name } ?: "Sony α7R II · 4.01",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            } },actions={
                if(state.connected) IconButton(onClick=controller::endSession,enabled=!state.transferring) { Icon(Icons.Outlined.LinkOff,"结束连接") }
                IconButton(onClick=openWifi) { Icon(Icons.Outlined.Wifi,"Wi-Fi 设置") }
            })
        },bottomBar={NavigationBar {
            destinations.forEachIndexed { index,d -> NavigationBarItem(selected=tab==index,onClick={tab=index},icon={Icon(d.icon,d.title)},label={Text(d.title)},alwaysShowLabel=true) }
        }}) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                if(state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                when(tab) {
                    0->Home(state,controller,{mode->controller.connect(mode); tab=1},requestPermissions)
                    1->when {
                        state.mode==CameraMode.REMOTE && (state.connected || state.busy)->Remote(state,controller)
                        state.connected || state.busy->CameraGallery(state,controller)
                        else->Home(state,controller,{mode->controller.connect(mode)},requestPermissions)
                    }
                    2->LocalLibrary(state,controller)
                    else->SettingsPage(state,controller,theme,dynamic,{theme=it;controller.prefs.edit().putString("theme",it).apply()}, {dynamic=it;controller.prefs.edit().putBoolean("dynamic",it).apply()},requestPermissions)
                }
            }
        }
    }
}

@Composable private fun Home(state:AppState,c:CompanionController,connect:(CameraMode)->Unit,permissions:()->Unit) {
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
        item { Text("相机工作台",style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.SemiBold) }
        item { Surface(color=MaterialTheme.colorScheme.surfaceContainer,shape=shape) { Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(8.dp).background(if(state.connected)Color(0xFF5CA681) else MaterialTheme.colorScheme.outline,CircleShape))
                Text(if(state.connected) "已连接" else "等待连接",fontWeight=FontWeight.Medium)
            }
            Text(state.message,style=MaterialTheme.typography.bodyMedium)
            Text("先在相机选择工作模式，再连接对应的 Wi-Fi。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        } } }
        items(CameraMode.entries) { mode ->
            OutlinedCard(shape=shape,modifier=Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    Icon(when(mode){CameraMode.REMOTE->Icons.Outlined.CameraAlt;CameraMode.JPEG->Icons.Outlined.FileDownload;else->Icons.Outlined.RawOn},null,Modifier.size(26.dp))
                    Text(mode.title,style=MaterialTheme.typography.titleMedium)
                }
                Text(mode.instruction,style=MaterialTheme.typography.bodyMedium)
                Button(onClick={connect(mode)},enabled=!state.busy&&!state.transferring,shape=shape) { Text(if(state.mode==mode&&state.connected) "重新连接" else "连接") }
            } }
        }
        item { TextButton(onClick=permissions) { Icon(Icons.Outlined.Security,null); Spacer(Modifier.width(8.dp)); Text("附近设备与通知权限") } }
        item { Text("首个预览版 · 相机功能仍需 A7R2 实机验证",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant) }
        if(state.local.isNotEmpty()) item { Text("最近导入：${state.local.first().name}",style=MaterialTheme.typography.bodyMedium) }
    }
}

@Composable private fun Remote(state:AppState,c:CompanionController) {
    val image by c.frame.collectAsStateWithLifecycle()
    var grid by rememberSaveable { mutableStateOf(false) }
    var interval by remember { mutableStateOf(false) }
    var count by rememberSaveable { mutableStateOf("10") }
    var seconds by rememberSaveable { mutableStateOf("5") }
    val apis=state.remote.apis
    if(state.mode!=CameraMode.REMOTE||!state.connected) { EmptyState(Icons.Outlined.CameraAlt,"遥控拍摄","在相机启动 Smart Remote Control，然后从首页连接。");return }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom=16.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(3f/2).background(Color(0xFF090D12)),contentAlignment=Alignment.Center) {
            if(image!=null) Image(image!!.asImageBitmap(),"实时取景",Modifier.fillMaxSize(),contentScale=ContentScale.Fit)
            else Column(horizontalAlignment=Alignment.CenterHorizontally) { Icon(Icons.Outlined.Videocam, null,tint=Color.LightGray); Text(if("startLiveview" in apis) "等待实时画面…" else "相机未开放实时取景",color=Color.LightGray) }
            if(grid) Canvas(Modifier.fillMaxSize()) { for(i in 1..2) {
                drawLine(Color.White.copy(alpha=.4f),androidx.compose.ui.geometry.Offset(size.width*i/3,0f),androidx.compose.ui.geometry.Offset(size.width*i/3,size.height),1f)
                drawLine(Color.White.copy(alpha=.4f),androidx.compose.ui.geometry.Offset(0f,size.height*i/3),androidx.compose.ui.geometry.Offset(size.width,size.height*i/3),1f)
            } }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
            Text(state.remote.status.ifBlank{"相机已连接"},style=MaterialTheme.typography.labelLarge)
            Row { IconToggleButton(checked=grid,onCheckedChange={grid=it}){Icon(Icons.Outlined.Grid3x3,"构图网格")}; IconButton(onClick=c::reconnect){Icon(Icons.Outlined.Refresh,"重连")} }
        }
        Column(Modifier.padding(horizontal=16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            state.remote.settings.chunked(2).forEach { row -> Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                row.forEach { setting -> Box(Modifier.weight(1f)){ SettingControl(setting){c.setSetting(setting.key,it)} } }
                if(row.size==1) Spacer(Modifier.weight(1f))
            } }
            if(state.remote.settings.isEmpty()) Text("当前相机应用未开放曝光参数。可在机身调节；支持能力已记录到诊断信息。",style=MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth().padding(vertical=14.dp),horizontalArrangement=Arrangement.SpaceEvenly,verticalAlignment=Alignment.CenterVertically) {
                OutlinedButton(onClick=c::focus,enabled="actHalfPressShutter" in apis&&"cancelHalfPressShutter" in apis,shape=shape) { Text("AF") }
                FilledIconButton(onClick=c::capture,enabled="actTakePicture" in apis&&state.intervalRemaining==0,modifier=Modifier.size(72.dp),shape=CircleShape) { Icon(Icons.Outlined.CameraAlt,"拍照",Modifier.size(30.dp)) }
                FilledTonalIconButton(onClick=c::movie,enabled="startMovieRec" in apis||"stopMovieRec" in apis,modifier=Modifier.size(50.dp)) { Icon(if(state.remote.status.contains("MovieRecording",true)) Icons.Outlined.Stop else Icons.Outlined.Videocam,"录像") }
            }
            Text("${state.remote.application} · getEvent ${state.remote.eventVersion}",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            HorizontalDivider()
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                Text("间隔拍摄",style=MaterialTheme.typography.titleSmall)
                TextButton(onClick={interval=!interval}) { Text(if(interval) "收起" else "展开") }
            }
            if(interval) {
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(count,{count=it},Modifier.weight(1f),label={Text("张数")},singleLine=true)
                    OutlinedTextField(seconds,{seconds=it},Modifier.weight(1f),label={Text("拍完后间隔 / 秒")},singleLine=true)
                }
                if(state.intervalRemaining>0) OutlinedButton(onClick=c::stopInterval,shape=shape){Text("停止 · 剩余 ${state.intervalRemaining} 张")}
                else Button(onClick={c.interval(count.toIntOrNull()?:10,seconds.toIntOrNull()?:5)},enabled="actTakePicture" in apis,shape=shape){Text("开始间隔拍摄")}
                Text("保持应用在前台。间隔从上张快门响应后计算；不会补发丢失的快门请求。",style=MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable private fun SettingControl(setting:Setting,onSelect:(String)->Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick={open=true},enabled=setting.writable&&setting.choices.isNotEmpty(),shape=shape,modifier=Modifier.fillMaxWidth(),contentPadding=PaddingValues(horizontal=10.dp,vertical=8.dp)) {
            Column(Modifier.weight(1f),horizontalAlignment=Alignment.Start) {
                Text(setting.label,style=MaterialTheme.typography.labelSmall)
                Text(setting.display(setting.current),style=MaterialTheme.typography.titleSmall,maxLines=1,overflow=TextOverflow.Ellipsis)
            }; Icon(Icons.Outlined.ExpandMore,null,Modifier.size(18.dp))
        }
        DropdownMenu(expanded=open,onDismissRequest={open=false},modifier=Modifier.heightIn(max=350.dp)) {
            setting.choices.forEach { value->DropdownMenuItem(text={Text(setting.display(value))},onClick={open=false;onSelect(value)}) }
        }
    }
}

@Composable private fun CameraGallery(state:AppState,c:CompanionController) {
    var selected by remember(state.device,state.folder) { mutableStateOf(setOf<String>()) }
    var detail by remember(state.device,state.folder) { mutableStateOf<CameraFile?>(null) }
    var filter by rememberSaveable { mutableStateOf("全部") }
    var descending by rememberSaveable { mutableStateOf(true) }
    var sizeChoice by rememberSaveable { mutableStateOf(JpegSize.MAXIMUM.name) }
    val offeredSizes=JpegSize.entries.filter { choice -> state.files.any { !it.container && it.jpegResource(choice)!=null } }
    val selectedSize=offeredSizes.firstOrNull { it.name==sizeChoice } ?: JpegSize.MAXIMUM
    val selectedFiles=state.files.filter { it.id in selected && !it.container }
    val unsupported=if(state.mode==CameraMode.JPEG) selectedFiles.count { it.jpegResource(selectedSize)==null } else 0
    if(!state.connected||state.mode==CameraMode.REMOTE) { EmptyState(Icons.Outlined.SdCard,"相机图库","从首页连接 JPEG 传输，或已完成登记的 RAW / 原文件模式。");return }
    val files=state.files.filter { it.container||filter=="全部"||(filter=="ARW"&&it.raw)||(filter=="JPEG"&&!it.raw) }
        .let { list->if(descending) list.sortedWith(compareByDescending<CameraFile>{it.container}.thenByDescending{it.date}.thenByDescending{it.name}) else list.sortedWith(compareByDescending<CameraFile>{it.container}.thenBy{it.date}.thenBy{it.name}) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically) {
            if(state.mode==CameraMode.JPEG) IconButton(onClick=c::root,enabled=!state.busy&&!state.transferring){Icon(Icons.Outlined.FolderOpen,"返回根目录")}
            Text(state.folder.ifBlank { "存储卡" },Modifier.weight(1f),style=MaterialTheme.typography.titleSmall,maxLines=1,overflow=TextOverflow.Ellipsis)
            IconButton(onClick={descending=!descending}) { Icon(Icons.Outlined.Sort,"切换排序") }
            TextButton(onClick={selected=if(selected.size==files.count{!it.container})emptySet() else files.filter{!it.container}.map{it.id}.toSet()}){Text("全选")}
        }
        Row(Modifier.padding(horizontal=16.dp),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            listOf("全部","JPEG","ARW").filter { state.mode==CameraMode.COMPUTER || it!="ARW" }.forEach { name-> FilterChip(selected=filter==name,onClick={filter=name},label={Text(name)}) }
            if(state.mode==CameraMode.JPEG) {
                var menu by remember { mutableStateOf(false) }
                Box { TextButton(onClick={menu=true},enabled=offeredSizes.isNotEmpty()){Text(selectedSize.title)}; DropdownMenu(menu,{menu=false}) {
                    offeredSizes.forEach { size->DropdownMenuItem(text={Text(size.title)},onClick={sizeChoice=size.name;menu=false}) }
                } }
            }
        }
        if(state.mode==CameraMode.JPEG) Text("仅下载相机实际提供的 JPEG 尺寸。ARW 条目的 JPEG 是预览。",Modifier.padding(horizontal=16.dp,vertical=4.dp),style=MaterialTheme.typography.bodySmall)
        if(files.isEmpty()) Box(Modifier.weight(1f)){EmptyState(Icons.Outlined.Image,"没有可显示的文件","可返回根目录，或在相机选择需要发送的照片。")}
        else LazyVerticalGrid(GridCells.Adaptive(120.dp),Modifier.weight(1f),contentPadding=PaddingValues(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            items(files,key={it.id}) { file->
                val bitmap by produceState<android.graphics.Bitmap?>(null,file.id,state.device,state.transferring) { if(!file.container&&!state.transferring) value=c.thumbnail(file) }
                Column(Modifier.clip(shape).background(if(file.id in selected)MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer)
                    .clickable(enabled=!state.transferring||!file.container){if(file.container)c.browse(file.id) else selected=if(file.id in selected)selected-file.id else selected+file.id}) {
                    Box(Modifier.fillMaxWidth().aspectRatio(1f),contentAlignment=Alignment.Center) {
                        if(bitmap!=null) Image(bitmap!!.asImageBitmap(),file.name,Modifier.fillMaxSize(),contentScale=ContentScale.Crop)
                        else Icon(if(file.container)Icons.Outlined.Folder else if(file.raw)Icons.Outlined.RawOn else Icons.Outlined.Image,null,Modifier.size(32.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant)
                        if(!file.container) IconButton(onClick={detail=file},modifier=Modifier.align(Alignment.TopStart)) { Icon(Icons.Outlined.Info,"照片信息",tint=MaterialTheme.colorScheme.onSurface) }
                        if(file.id in selected) Icon(Icons.Outlined.CheckCircle,"已选择",Modifier.align(Alignment.TopEnd).padding(6.dp),tint=MaterialTheme.colorScheme.primary)
                    }
                    Text(file.name,Modifier.padding(horizontal=8.dp,vertical=4.dp),style=MaterialTheme.typography.labelMedium,maxLines=1,overflow=TextOverflow.Ellipsis)
                    Text(if(file.container) "文件夹" else (if(file.raw)"ARW · " else "JPEG · ")+bytes(file.size),Modifier.padding(start=8.dp,end=8.dp,bottom=8.dp),style=MaterialTheme.typography.labelSmall)
                }
            }
        }
        if(unsupported>0) Text("所选的 $unsupported 个文件未提供${selectedSize.title}，请更改尺寸或取消选择。",Modifier.padding(horizontal=16.dp),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)
        if(selected.isNotEmpty()) Button(onClick={
            val choices=selectedFiles.map { f->
                if(f.handle!=null)f else {
                    val resource=f.jpegResource(selectedSize)
                    f.copy(resources=listOfNotNull(resource),size=resource?.size?:0)
                }
            }
            c.enqueue(choices);selected=emptySet()
        },Modifier.fillMaxWidth().padding(horizontal=16.dp),enabled=unsupported==0 && selectedFiles.isNotEmpty(),shape=shape){Text("下载 ${selectedFiles.size} 个文件")}
        QueuePanel(state,c)
    }
    detail?.let { file -> AlertDialog(onDismissRequest={detail=null},title={Text(file.name)},text={
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            if(file.date.isNotBlank()) Text(file.date)
            if(file.handle!=null) Text("${if(file.raw) "ARW 原文件" else "JPEG 原文件"} · ${bytes(file.size)}")
            else {
                if(file.raw) Text("此 ARW 条目提供的可下载资源为 JPEG 预览。")
                file.resources.filter { it.isJpeg }.distinctBy { it.url }.forEach { resource ->
                    Text(listOfNotNull(resource.jpegSize?.title ?: "JPEG",resource.resolution.takeIf { it.isNotBlank() },resource.size.takeIf { it>0 }?.let(::bytes)).joinToString(" · "))
                }
            }
        }
    },confirmButton={TextButton(onClick={detail=null}){Text("关闭")}}) }
}

@Composable private fun QueuePanel(state:AppState,c:CompanionController) {
    if(state.queue.isEmpty())return
    var expanded by rememberSaveable { mutableStateOf(false) }
    val active=state.queue.firstOrNull{it.state=="传输中"}
    Surface(tonalElevation=2.dp) { Column(Modifier.fillMaxWidth().padding(12.dp)) {
        Row(verticalAlignment=Alignment.CenterVertically) {
            Text(active?.file?.name ?: "队列 · ${state.queue.count { it.state=="完成"||it.state=="已存在" }} / ${state.queue.size}",Modifier.weight(1f),maxLines=1,style=MaterialTheme.typography.labelLarge)
            if(state.transferring)TextButton(onClick=c::cancelTransfers){Text("取消")}
            else TextButton(onClick=c::retryFailed){Text("重试失败")}
            IconButton(onClick={expanded=!expanded}){Icon(if(expanded)Icons.Outlined.ExpandMore else Icons.Outlined.ExpandLess,"队列详情")}
        }
        if(active!=null) {
            if(active.total>0)LinearProgressIndicator(progress={(active.received.toFloat()/active.total).coerceIn(0f,1f)},modifier=Modifier.fillMaxWidth()) else LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("${bytes(active.received)} / ${bytes(active.total)}",style=MaterialTheme.typography.labelSmall)
        }
        if(expanded) {
            Column(Modifier.heightIn(max=180.dp).verticalScroll(rememberScrollState())) { state.queue.takeLast(50).forEach { item->Text("${item.file.name} · ${item.state}${item.error?.let{"\n$it"}?:""}",style=MaterialTheme.typography.bodySmall,modifier=Modifier.padding(vertical=4.dp)) } }
            if(!state.transferring)TextButton(onClick=c::clearCompleted){Text("清空队列记录")}
        }
    } }
}

@Composable private fun LocalLibrary(state:AppState,c:CompanionController) {
    val context=LocalContext.current
    var filter by rememberSaveable { mutableStateOf("全部") }
    var detail by remember { mutableStateOf<LocalPhoto?>(null) }
    var info by remember { mutableStateOf("") }
    var forget by remember { mutableStateOf<LocalPhoto?>(null) }
    val scope=rememberCoroutineScope()
    LaunchedEffect(Unit) { c.refreshLocal() }
    val files=state.local.filter { filter=="全部"||(it.name.endsWith(".arw",true))==(filter=="ARW") }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal=16.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            listOf("全部","JPEG","ARW").forEach { name->FilterChip(filter==name,{filter=name},label={Text(name)}) }
            Spacer(Modifier.weight(1f))
            IconButton(onClick=c::refreshLocal){Icon(Icons.Outlined.Refresh,"刷新并清理失效记录")}
        }
        if(files.isEmpty()) EmptyState(Icons.Outlined.PhotoLibrary,"尚未导入照片","下载完成的照片保存在 DCIM/AlphaCompanion，无需授予整库访问权限。")
        else LazyColumn(contentPadding=PaddingValues(horizontal=16.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            items(files,key={it.uri}) { file ->
                val preview by produceState<android.graphics.Bitmap?>(null,file.uri){value=c.photos.preview(file)}
                ListItem(modifier=Modifier.clip(shape).clickable{detail=file;info="读取 EXIF…";scope.launch { info=withContext(Dispatchers.IO){runCatching {c.photos.metadata(file)}.getOrElse{it.message.orEmpty()}} }},
                    leadingContent={Box(Modifier.size(64.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceContainer),contentAlignment=Alignment.Center){
                        if(preview!=null)Image(preview!!.asImageBitmap(),file.name,Modifier.fillMaxSize(),contentScale=ContentScale.Crop)else Icon(Icons.Outlined.Image,null)
                    }},headlineContent={Text(file.name,maxLines=1)},supportingContent={
                        val group=state.local.filter{it.pairKey==file.pairKey}
                        val paired=group.any{it.name.endsWith(".arw",true)}&&group.any{it.name.endsWith(".jpg",true)||it.name.endsWith(".jpeg",true)}
                        Text(bytes(file.bytes)+(if(paired)" · RAW/JPEG 配对" else ""),style=MaterialTheme.typography.bodySmall)
                    },trailingContent={Row {
                        IconButton(onClick={share(context,file)}){Icon(Icons.Outlined.Share,"分享")}
                        IconButton(onClick={forget=file}){Icon(Icons.Outlined.RemoveCircleOutline,"移除导入记录")}
                    }})
            }
        }
    }
    if(forget!=null)AlertDialog(onDismissRequest={forget=null},title={Text("移除导入记录？")},
        text={Text("仅从本应用图库移除 ${forget!!.name} 的记录，手机中的文件保留。之后可以重新导入。")},
        confirmButton={TextButton(onClick={c.forgetPhoto(forget!!);forget=null}){Text("移除记录")}},
        dismissButton={TextButton(onClick={forget=null}){Text("取消")}})
    if(detail!=null)AlertDialog(onDismissRequest={detail=null},title={Text("照片信息")},text={Text(info,Modifier.verticalScroll(rememberScrollState()))},confirmButton={TextButton(onClick={
        val photo=detail!!
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(photo.uri),if(photo.name.endsWith(".arw",true))"image/x-sony-arw" else "image/jpeg").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
        detail=null
    }){Text("打开")}},dismissButton={TextButton(onClick={detail=null}){Text("关闭")}})
}
private fun share(context:android.content.Context,p:LocalPhoto) {
    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(if(p.name.endsWith(".arw",true))"image/x-sony-arw" else "image/jpeg")
        .putExtra(Intent.EXTRA_STREAM,Uri.parse(p.uri)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),"分享照片"))
}

@Composable private fun SettingsPage(state:AppState,c:CompanionController,theme:String,dynamic:Boolean,setTheme:(String)->Unit,setDynamic:(Boolean)->Unit,permissions:()->Unit) {
    val context=LocalContext.current
    val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")){uri->if(uri!=null)c.exportDiagnostics(uri)}
    var description by remember { mutableStateOf(c.prefs.getString("description","").orEmpty()) }
    var host by remember { mutableStateOf(c.prefs.getString("computerHost","").orEmpty()) }
    var guid by remember { mutableStateOf(c.prefs.getString("guid","").orEmpty()) }
    var saved by remember { mutableStateOf(false) }
    var logLevel by remember { mutableStateOf(c.journal.minimum) }
    var logMenu by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item { Text("外观",style=MaterialTheme.typography.titleMedium) }
        item { Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) { listOf("system" to "跟随系统","light" to "浅色","dark" to "深色").forEach{(value,label)->FilterChip(theme==value,{setTheme(value)},label={Text(label)})} } }
        item { Row(verticalAlignment=Alignment.CenterVertically){Text("Material You 动态配色",Modifier.weight(1f));Switch(dynamic,setDynamic)} }
        item { HorizontalDivider() }
        item { Text("连接",style=MaterialTheme.typography.titleMedium) }
        if(state.remote.application.isNotBlank()) item { Text("机内应用：${state.remote.application}",style=MaterialTheme.typography.bodySmall) }
        item { Text("手动连接 Wi-Fi 后发现相机。保留移动数据；相机请求固定走 Wi-Fi。",style=MaterialTheme.typography.bodySmall) }
        item { OutlinedTextField(description,{description=it;saved=false},Modifier.fillMaxWidth(),label={Text("描述文件 URL / JPEG 模式相机 IP")},placeholder={Text("留空使用 SSDP 自动发现")},singleLine=true) }
        item { Text("RAW / Send to Computer · 实验性",style=MaterialTheme.typography.titleSmall) }
        item { Text("需先用电脑通过 USB 登记接收端，填入相同 GUID。当前版本未实现 Android USB OTG 登记；不会用随机 GUID 试连。",style=MaterialTheme.typography.bodySmall) }
        item { OutlinedTextField(host,{host=it;saved=false},Modifier.fillMaxWidth(),label={Text("相机局域网 IP（留空自动发现）")},singleLine=true) }
        item { OutlinedTextField(guid,{guid=it;saved=false},Modifier.fillMaxWidth(),label={Text("已登记的 16 字节 GUID")},supportingText={Text("32 位十六进制，可含冒号")},singleLine=true) }
        item { Button(onClick={c.prefs.edit().putString("description",description.trim()).putString("computerHost",host.trim()).putString("guid",guid.trim()).apply();saved=true},shape=shape){Text(if(saved) "已保存" else "保存连接设置")} }
        item { TextButton(onClick=permissions){Text("申请附近设备与通知权限")} }
        item { HorizontalDivider() }
        item { Text("存储与诊断",style=MaterialTheme.typography.titleMedium) }
        item { Text("照片：DCIM/AlphaCompanion\n失败和中断的文件不会显示为完成；重试从头下载。\n导入记录用于本地图库；可移除记录，失效记录会自动清理。",style=MaterialTheme.typography.bodySmall) }
        item { Box {
            OutlinedButton(onClick={logMenu=true},shape=shape){Text("记录级别：${logLevel.name}")}
            DropdownMenu(logMenu,{logMenu=false}) { LogLevel.entries.forEach { level ->
                DropdownMenuItem(text={Text(level.name)},onClick={logLevel=level;c.setLogLevel(level);logMenu=false})
            } }
        } }
        item { Text("DEBUG 用于排查连接和传输；TRACE 额外记录协议文本。日志循环保留约 1.5 MiB，重启后仍可导出。",style=MaterialTheme.typography.bodySmall) }
        item { OutlinedButton(onClick={export.launch(DiagnosticFiles.name(state.mode))},shape=shape){Icon(Icons.Outlined.BugReport,null);Spacer(Modifier.width(8.dp));Text("导出日志与诊断")} }
        item { Text("版本 ${BuildConfig.VERSION_NAME} · Android 10 及以上\n基于 SonyRX10M3Remote、ImagingEdge4Linux、SonyPhotoTransfer / libptp。GPL-3.0；libptp 保留 LGPL-2.1。\n适配目标：Sony α7R II · 固件 4.01。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant) }
        item { Text(state.logs.takeLast(12).joinToString("\n"),style=MaterialTheme.typography.labelSmall) }
    }
}

@Composable private fun EmptyState(icon:ImageVector,title:String,text:String) {
    Column(Modifier.fillMaxSize().padding(28.dp),verticalArrangement=Arrangement.Center,horizontalAlignment=Alignment.CenterHorizontally) {
        Icon(icon,null,Modifier.size(46.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp));Text(title,style=MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp));Text(text,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
private fun bytes(value:Long):String = when {
    value<=0->"未知大小";value<1024->"$value B";value<1024*1024->String.format(Locale.ROOT,"%.1f KB",value/1024.0)
    else->String.format(Locale.ROOT,"%.1f MB",value/(1024.0*1024))
}
