package io.github.ayatinene.sonycompanion.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

data class ViewerImage(val key: String, val title: String, val subtitle: String = "")
private data class PreviewLoad(val bitmap: Bitmap? = null, val loading: Boolean = false, val error: String? = null)

@Composable fun PhotoViewer(images: List<ViewerImage>, initialKey: String, refreshKey: Any? = null,
    onDismiss: ()->Unit, load: suspend (ViewerImage)->Bitmap?,
    actions: @Composable RowScope.(ViewerImage)->Unit) {
    if(images.isEmpty()) { LaunchedEffect(Unit) { onDismiss() }; return }
    val pager=rememberPagerState(initialPage=images.indexOfFirst { it.key==initialKey }.coerceAtLeast(0),pageCount={images.size})
    val scope=rememberCoroutineScope()
    var zoomed by remember { mutableStateOf(false) }
    var reset by remember { mutableIntStateOf(0) }
    LaunchedEffect(pager.currentPage) { zoomed=false;reset++ }
    Dialog(onDismissRequest=onDismiss,properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)) {
        MaterialTheme(colorScheme=darkColorScheme(primary=Color(0xFFF4AE78),surface=Color(0xFF101215))) {
            Surface(Modifier.fillMaxSize(),color=Color(0xFF080A0D)) {
                Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                    val current=images[pager.currentPage.coerceIn(images.indices)]
                    Row(Modifier.fillMaxWidth().padding(end=12.dp),verticalAlignment=Alignment.CenterVertically) {
                        IconButton(onClick=onDismiss) { Icon(Icons.Outlined.Close,"关闭预览") }
                        Column(Modifier.weight(1f)) {
                            Text(current.title,style=MaterialTheme.typography.titleSmall,maxLines=1,overflow=TextOverflow.Ellipsis)
                            if(current.subtitle.isNotBlank()) Text(current.subtitle,style=MaterialTheme.typography.labelSmall,maxLines=1,overflow=TextOverflow.Ellipsis,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text("${pager.currentPage+1} / ${images.size}",style=MaterialTheme.typography.labelMedium)
                    }
                    HorizontalPager(state=pager,userScrollEnabled=!zoomed,key={images[it].key},modifier=Modifier.weight(1f)) { page ->
                        val image=images[page]
                        var retry by remember(image.key) { mutableIntStateOf(0) }
                        val result by produceState(PreviewLoad(loading=true),image.key,retry,refreshKey) {
                            value=PreviewLoad(loading=true)
                            value=try { load(image)?.let { PreviewLoad(bitmap=it) } ?: PreviewLoad(error="没有可显示的预览") }
                            catch(e: CancellationException) { throw e }
                            catch(e: Exception) { PreviewLoad(error=e.message ?: "读取预览失败") }
                        }
                        Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center) {
                            result.bitmap?.let { bitmap ->
                                ZoomablePhoto(bitmap,image.title,reset+pager.currentPage) { if(page==pager.currentPage)zoomed=it }
                            }
                            if(result.loading) CircularProgressIndicator(Modifier.size(32.dp))
                            result.error?.let { error -> Column(Modifier.padding(24.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                                Icon(Icons.Outlined.BrokenImage,null)
                                Text(error,Modifier.padding(vertical=12.dp))
                                OutlinedButton(onClick={retry++}) { Text("重新读取") }
                            } }
                        }
                    }
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly,verticalAlignment=Alignment.CenterVertically) {
                        IconButton(onClick={scope.launch { pager.animateScrollToPage(pager.currentPage-1) }},enabled=pager.currentPage>0) { Icon(Icons.Outlined.ChevronLeft,"上一张") }
                        TextButton(onClick={reset++;zoomed=false}) { Text(if(zoomed)"还原缩放" else "双指缩放 · 双击放大") }
                        IconButton(onClick={scope.launch { pager.animateScrollToPage(pager.currentPage+1) }},enabled=pager.currentPage<images.lastIndex) { Icon(Icons.Outlined.ChevronRight,"下一张") }
                    }
                    Row(Modifier.fillMaxWidth().padding(horizontal=12.dp),horizontalArrangement=Arrangement.SpaceEvenly,verticalAlignment=Alignment.CenterVertically) {
                        actions(current)
                    }
                }
            }
        }
    }
}

@Composable private fun ZoomablePhoto(bitmap: Bitmap, description: String, reset: Int, onZoom: (Boolean)->Unit) {
    var scale by remember(bitmap,reset) { mutableFloatStateOf(1f) }
    var offset by remember(bitmap,reset) { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    fun constrain(value: Offset, zoom: Float): Offset {
        if(viewport.width==0 || viewport.height==0) return Offset.Zero
        val fit=minOf(viewport.width.toFloat()/bitmap.width,viewport.height.toFloat()/bitmap.height)
        val x=((bitmap.width*fit*zoom-viewport.width)/2).coerceAtLeast(0f)
        val y=((bitmap.height*fit*zoom-viewport.height)/2).coerceAtLeast(0f)
        return Offset(value.x.coerceIn(-x,x),value.y.coerceIn(-y,y))
    }
    val transform=rememberTransformableState { zoom, pan, _ ->
        scale=(scale*zoom).coerceIn(1f,6f)
        offset=constrain(offset+pan*scale,scale)
        onZoom(scale>1.01f)
    }
    Box(Modifier.fillMaxSize().clipToBounds().onSizeChanged { viewport=it }
        .pointerInput(bitmap,reset) { detectTapGestures(onDoubleTap={
            scale=if(scale>1.01f)1f else 2.5f;offset=Offset.Zero;onZoom(scale>1.01f)
        }) }.transformable(state=transform,canPan={scale>1.01f}),contentAlignment=Alignment.Center) {
        Image(bitmap.asImageBitmap(),description,Modifier.fillMaxSize().graphicsLayer {
            scaleX=scale;scaleY=scale;translationX=offset.x;translationY=offset.y
        },contentScale=ContentScale.Fit)
    }
}
