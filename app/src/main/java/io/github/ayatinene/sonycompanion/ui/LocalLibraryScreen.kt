package io.github.ayatinene.sonycompanion.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.ayatinene.sonycompanion.data.*
import kotlinx.coroutines.*
import java.util.Locale

@Composable fun LocalLibraryScreen(state: AppState,c: CompanionController) {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    var filter by rememberSaveable { mutableStateOf("全部") }
    var sort by rememberSaveable { mutableStateOf("最新导入") }
    var preview by remember { mutableStateOf<String?>(null) }
    var detail by remember { mutableStateOf<LocalPhoto?>(null) }
    var info by remember { mutableStateOf("") }
    var manage by remember { mutableStateOf<LocalPhoto?>(null) }
    var deleting by remember { mutableStateOf<LocalPhoto?>(null) }
    var forgetting by remember { mutableStateOf<LocalPhoto?>(null) }
    var permissionPhoto by remember { mutableStateOf<LocalPhoto?>(null) }
    var shareError by remember { mutableStateOf<String?>(null) }
    val deletePermission=rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if(result.resultCode==Activity.RESULT_OK) {
            if(Build.VERSION.SDK_INT==29) permissionPhoto?.let { c.deletePhoto(it) { } }
            c.refreshLocal()
        }
        permissionPhoto=null
    }
    fun showInfo(photo: LocalPhoto) {
        detail=photo;info="读取 EXIF…"
        scope.launch { val text=withContext(Dispatchers.IO) { runCatching { c.photos.metadata(photo) }.getOrElse { it.message.orEmpty() } }
            if(detail?.uri==photo.uri)info=text
        }
    }
    fun share(photo: LocalPhoto) { runCatching { shareLocalPhoto(context,photo) }.onFailure { shareError=it.message ?: "没有可接收照片的应用" } }
    LaunchedEffect(Unit) { c.refreshLocal() }
    val files=remember(state.local,filter,sort) {
        state.local.filter { filter=="全部" || it.name.endsWith(".arw",true)==(filter=="ARW") }.let { list ->
            when(sort) { "最早导入"->list.sortedBy { it.added };"文件名"->list.sortedBy { it.name };else->list.sortedByDescending { it.added } }
        }
    }
    val paired=remember(state.local) { state.local.groupBy { it.pairKey }.filterValues { group ->
        group.any { it.name.endsWith(".arw",true) } && group.any { it.name.endsWith(".jpg",true)||it.name.endsWith(".jpeg",true) }
    }.keys }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            listOf("全部","JPEG","ARW").forEach { name -> FilterChip(filter==name,{filter=name},label={Text(name)}) }
            Spacer(Modifier.weight(1f))
            var menu by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick={menu=true}) { Icon(Icons.Outlined.Sort,"排序：$sort") }
                DropdownMenu(menu,{menu=false}) { listOf("最新导入","最早导入","文件名").forEach { name ->
                    DropdownMenuItem(text={Text(name)},onClick={sort=name;menu=false})
                } }
            }
            IconButton(onClick=c::refreshLocal) { Icon(Icons.Outlined.Refresh,"刷新图库") }
        }
        Text("${files.size} 个文件 · DCIM/AlphaCompanion",Modifier.padding(horizontal=16.dp,vertical=4.dp),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        if(files.isEmpty()) Box(Modifier.weight(1f).fillMaxWidth(),contentAlignment=Alignment.Center) {
            Column(Modifier.padding(24.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                Icon(Icons.Outlined.PhotoLibrary,null,Modifier.size(40.dp))
                Text("暂无导入照片",Modifier.padding(top=12.dp),style=MaterialTheme.typography.titleMedium)
                Text("从相机图库下载后会显示在这里。",style=MaterialTheme.typography.bodySmall)
            }
        } else LazyVerticalGrid(GridCells.Adaptive(128.dp),Modifier.weight(1f),contentPadding=PaddingValues(12.dp),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            items(files,key={it.uri}) { photo ->
                val bitmap by produceState<android.graphics.Bitmap?>(null,photo.uri) { value=c.photos.preview(photo) }
                Column(Modifier.clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainer)
                    .combinedClickable(onClick={preview=photo.uri},onLongClick={manage=photo})) {
                    Box(Modifier.fillMaxWidth().aspectRatio(1f),contentAlignment=Alignment.Center) {
                        if(bitmap!=null) Image(bitmap!!.asImageBitmap(),photo.name,Modifier.fillMaxSize(),contentScale=ContentScale.Crop)
                        else Icon(if(photo.name.endsWith(".arw",true))Icons.Outlined.RawOn else Icons.Outlined.Image,null)
                        IconButton(onClick={manage=photo},modifier=Modifier.align(Alignment.TopEnd)) { Icon(Icons.Outlined.MoreVert,"文件操作") }
                    }
                    Text(photo.name,Modifier.padding(horizontal=8.dp,vertical=4.dp),style=MaterialTheme.typography.labelMedium,maxLines=1,overflow=TextOverflow.Ellipsis)
                    Text(String.format(Locale.ROOT,"%.1f MB",photo.bytes/(1024.0*1024))+(if(photo.pairKey in paired)" · RAW+JPEG" else ""),Modifier.padding(start=8.dp,end=8.dp,bottom=8.dp),style=MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
    preview?.let { key ->
        PhotoViewer(files.map { ViewerImage(it.uri,it.name,if(it.name.endsWith(".arw",true))"RAW · 同名 JPEG 或内嵌预览" else "本地 JPEG") },key,
            onDismiss={preview=null},load={ image -> c.photos.fullPreview(files.first { it.uri==image.key }) }) { image ->
            val photo=files.first { it.uri==image.key }
            TextButton(onClick={showInfo(photo)}) { Icon(Icons.Outlined.Info,null);Spacer(Modifier.width(4.dp));Text("信息") }
            TextButton(onClick={share(photo)}) { Icon(Icons.Outlined.Share,null);Spacer(Modifier.width(4.dp));Text("分享") }
            TextButton(onClick={manage=photo}) { Icon(Icons.Outlined.MoreHoriz,null);Spacer(Modifier.width(4.dp));Text("管理") }
        }
    }
    manage?.let { photo -> AlertDialog(onDismissRequest={manage=null},title={Text(photo.name)},text={
        Column {
            TextButton(onClick={manage=null;showInfo(photo)}) { Text("照片信息") }
            TextButton(onClick={manage=null;share(photo)}) { Text("分享文件") }
            TextButton(onClick={manage=null;forgetting=photo}) { Text("清除导入记录，保留文件") }
            TextButton(onClick={manage=null;deleting=photo}) { Text("删除照片文件",color=MaterialTheme.colorScheme.error) }
        }
    },confirmButton={TextButton(onClick={manage=null}) { Text("关闭") }}) }
    forgetting?.let { photo -> AlertDialog(onDismissRequest={forgetting=null},title={Text("清除导入记录？")},text={Text("手机中的 ${photo.name} 会保留，本应用不再显示。之后可以重新下载。")},
        confirmButton={TextButton(onClick={c.forgetPhoto(photo);forgetting=null;preview=null}) { Text("清除记录") }},dismissButton={TextButton(onClick={forgetting=null}) { Text("取消") }}) }
    deleting?.let { photo -> AlertDialog(onDismissRequest={deleting=null},title={Text("删除照片文件？")},text={Text("将从手机存储中永久删除 ${photo.name}，并清除导入记录。相机中的原文件保留。")},
        confirmButton={TextButton(onClick={permissionPhoto=photo;c.deletePhoto(photo) { sender -> deletePermission.launch(IntentSenderRequest.Builder(sender).build()) };deleting=null;preview=null}) { Text("删除文件",color=MaterialTheme.colorScheme.error) }},
        dismissButton={TextButton(onClick={deleting=null}) { Text("取消") }}) }
    detail?.let { photo -> AlertDialog(onDismissRequest={detail=null},title={Text(photo.name)},text={Text(info,Modifier.verticalScroll(rememberScrollState()))},
        confirmButton={TextButton(onClick={runCatching { context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(photo.uri),photo.mime()).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }.onFailure { shareError=it.message };detail=null}) { Text("其他应用打开") }},
        dismissButton={TextButton(onClick={detail=null}) { Text("关闭") }}) }
    shareError?.let { message -> AlertDialog(onDismissRequest={shareError=null},title={Text("无法打开")},text={Text(message)},confirmButton={TextButton(onClick={shareError=null}) { Text("关闭") }}) }
}

private fun LocalPhoto.mime()=if(name.endsWith(".arw",true))"image/x-sony-arw" else "image/jpeg"
private fun shareLocalPhoto(context: Context, photo: LocalPhoto) {
    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(photo.mime())
        .putExtra(Intent.EXTRA_STREAM,Uri.parse(photo.uri)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),"分享照片"))
}
