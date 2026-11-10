package io.github.ayatinene.sonycompanion.ui

import android.graphics.Bitmap
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.ayatinene.sonycompanion.data.*
import java.util.Locale

private val galleryShape = RoundedCornerShape(10.dp)

/**
 * Presentation callbacks for the camera gallery. Tests can inject real fixtures here, while the app
 * builds it from [CompanionController] so the controller remains the single source of behaviour.
 */
data class GalleryActions(
    val browse: (String) -> Unit = {},
    val root: () -> Unit = {},
    val enqueue: (List<CameraFile>, Long) -> Unit = { _, _ -> },
    val cancelTransfers: () -> Unit = {},
    val retryFailed: () -> Unit = {},
    val clearCompleted: () -> Unit = {},
    val rescan: () -> Unit = {},
    val setScanPaused: (Boolean) -> Unit = {},
    val thumbnail: suspend (CameraFile, String?, Long) -> Bitmap? = { _, _, _ -> null },
    val preview: suspend (CameraFile, String?, Long) -> Bitmap? = { _, _, _ -> null },
)

/** Forwards to the controller with the scope and generation captured by the caller. */
fun CompanionController.galleryActions(): GalleryActions = GalleryActions(
    browse = ::browse,
    root = ::root,
    enqueue = { files, generation -> enqueue(files, generation) },
    cancelTransfers = ::cancelTransfers,
    retryFailed = ::retryFailed,
    clearCompleted = ::clearCompleted,
    rescan = ::rescan,
    setScanPaused = ::setScanPaused,
    thumbnail = { file, scope, generation -> thumbnail(file, scope, generation) },
    preview = { file, scope, generation -> cameraPreview(file, scope, generation) },
)

/** The file under preview plus the session it was opened against; never re-read from the controller. */
private data class GalleryPreview(val id: String, val scope: String?, val generation: Long)

@Composable internal fun CameraGallery(state: AppState, actions: GalleryActions) {
    if (!state.connected || state.mode == CameraMode.REMOTE) {
        EmptyState(Icons.Outlined.SdCard, "相机图库", "从首页连接 JPEG 传输，或已完成登记的 RAW / 原文件模式。")
        return
    }
    // Selection and preview are tied to the session generation and the browsed folder, so a
    // reconnect or a JPEG directory change starts clean.
    var selected by remember(state.sessionGeneration, state.folder) { mutableStateOf(setOf<String>()) }
    var detail by remember(state.sessionGeneration, state.folder) { mutableStateOf<CameraFile?>(null) }
    var preview by remember(state.sessionGeneration, state.folder) { mutableStateOf<GalleryPreview?>(null) }
    var filter by rememberSaveable { mutableStateOf("全部") }
    var sortName by rememberSaveable { mutableStateOf(GallerySort.Newest.name) }
    var layoutName by rememberSaveable { mutableStateOf(GalleryLayout.Grid.name) }
    var sizeChoice by rememberSaveable { mutableStateOf(JpegSize.MAXIMUM.name) }
    val sort = GallerySort.entries.firstOrNull { it.name == sortName } ?: GallerySort.Newest
    val layout = GalleryLayout.entries.firstOrNull { it.name == layoutName } ?: GalleryLayout.Grid
    // The grid and the list keep separate scroll positions; a switch re-anchors on the item first
    // visible at the click.
    val gridState = rememberLazyGridState()
    val listState = rememberLazyListState()
    var pendingAnchor by remember { mutableStateOf<String?>(null) }
    var pendingScroll by remember { mutableStateOf(false) }
    LaunchedEffect(state.mode) {
        if (state.mode == CameraMode.JPEG && filter == "ARW") { filter = "全部"; pendingAnchor = null; pendingScroll = true }
    }

    val offeredSizes = JpegSize.entries.filter { choice -> state.files.any { !it.container && it.jpegResource(choice) != null } }
    val selectedSize = offeredSizes.firstOrNull { it.name == sizeChoice } ?: JpegSize.MAXIMUM
    // Derive off the inputs so an unrelated recomposition (selection, queue progress) never re-parses dates.
    val filtered = remember(state.files, filter) { galleryFiltered(state.files, filter) }
    val files = remember(filtered, sort) { gallerySorted(filtered, sort) }
    val selectableIds = selectAllIds(files)
    val selectedVisible = visibleSelected(selected, selectableIds)
    val hiddenSelected = hiddenSelectedCount(selected, selectableIds)
    val selectedFiles = files.filter { it.id in selectedVisible }
    val unsupported = if (state.mode == CameraMode.JPEG) selectedFiles.count { it.jpegResource(selectedSize) == null } else 0
    val allVisibleSelected = selectableIds.isNotEmpty() && selectedVisible.size == selectableIds.size
    val canRead = galleryReadAllowed(state.connected, state.scan, state.transferring)
    val canDownload = downloadNewFilesEnabled(state.connected, state.scan)
    val scope = state.queueScope
    val generation = state.sessionGeneration
    // Filter/sort changes return to the top; one effect applies the pending anchor so a restore is
    // never overwritten.
    val requestTop: () -> Unit = { pendingAnchor = null; pendingScroll = true }
    val switchLayout: () -> Unit = {
        pendingAnchor = if (layout == GalleryLayout.Grid) {
            gridState.layoutInfo.visibleItemsInfo.firstOrNull()?.key as? String
        } else {
            listState.layoutInfo.visibleItemsInfo.firstOrNull()?.key as? String
        }
        pendingScroll = true
        layoutName = (if (layout == GalleryLayout.Grid) GalleryLayout.List else GalleryLayout.Grid).name
    }
    LaunchedEffect(layoutName, pendingScroll) {
        if (!pendingScroll) return@LaunchedEffect
        // No list is mounted while empty; drop the pending scroll instead of suspending on it.
        if (files.isEmpty()) { pendingScroll = false; return@LaunchedEffect }
        val index = pendingAnchor?.let { id -> files.indexOfFirst { it.id == id }.takeIf { it >= 0 } } ?: 0
        if (layout == GalleryLayout.Grid) gridState.scrollToItem(index) else listState.scrollToItem(index)
        pendingScroll = false
    }
    val onItemClick: (CameraFile) -> Unit = { file ->
        if (file.container) actions.browse(file.id)
        else if (selectedVisible.isNotEmpty()) selected = if (file.id in selected) selected - file.id else selected + file.id
        else preview = GalleryPreview(file.id, scope, generation)
    }
    val onItemLongClick: (CameraFile) -> Unit = { file -> if (!file.container) selected = selected + file.id }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal=12.dp), verticalAlignment=Alignment.CenterVertically) {
            if (state.mode == CameraMode.JPEG) IconButton(onClick=actions.root, enabled=!state.busy && !state.transferring) { Icon(Icons.Outlined.FolderOpen, "返回根目录") }
            Text("相机图库", Modifier.weight(1f), style=MaterialTheme.typography.titleSmall, maxLines=1, overflow=TextOverflow.Ellipsis)
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal=12.dp),
            horizontalArrangement=Arrangement.spacedBy(4.dp), verticalAlignment=Alignment.CenterVertically) {
            var sortMenu by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick={ sortMenu = true }, modifier=Modifier.size(48.dp).testTag("gallery-sort-menu")) {
                    Icon(Icons.Outlined.Sort, "排序：${sort.title}")
                }
                DropdownMenu(sortMenu, { sortMenu = false }) {
                    GallerySort.entries.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.title) },
                            onClick = { sortMenu = false; if (option.name != sortName) { sortName = option.name; requestTop() } },
                            modifier = Modifier.testTag("gallery-sort-${option.name}")
                                .semantics { this.selected = option.name == sortName },
                            leadingIcon = {
                                if (option.name == sortName) Icon(Icons.Outlined.Check, null)
                                else Spacer(Modifier.size(24.dp))
                            }
                        )
                    }
                }
            }
            IconButton(onClick = switchLayout, modifier=Modifier.size(48.dp).testTag("gallery-layout-toggle")) {
                Icon(if (layout == GalleryLayout.Grid) Icons.Outlined.ViewList else Icons.Outlined.GridView,
                    if (layout == GalleryLayout.Grid) "切换为详情列表" else "切换为网格")
            }
            if (selected.isNotEmpty()) TextButton(onClick={ selected = emptySet() }) { Text("取消选择") }
            TextButton(onClick={ selected = if (allVisibleSelected) selected - selectableIds else selected + selectableIds }) {
                Text(if (allVisibleSelected) "取消全选" else "全选 ${selectableIds.size}")
            }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal=16.dp),
            horizontalArrangement=Arrangement.spacedBy(6.dp), verticalAlignment=Alignment.CenterVertically) {
            listOf("全部", "JPEG", "ARW").filter { state.mode == CameraMode.COMPUTER || it != "ARW" }.forEach { name ->
                FilterChip(selected = filter == name, onClick = { if (filter != name) { filter = name; requestTop() } }, label = { Text(name) })
            }
            if (state.mode == CameraMode.JPEG) {
                var menu by remember { mutableStateOf(false) }
                Box { TextButton(onClick={ menu = true }, enabled=offeredSizes.isNotEmpty()) { Text(selectedSize.title) }
                    DropdownMenu(menu, { menu = false }) { offeredSizes.forEach { size -> DropdownMenuItem(text={ Text(size.title) }, onClick={ sizeChoice = size.name; menu = false }) } }
                }
            }
        }
        if (state.mode == CameraMode.JPEG) Text("仅下载相机实际提供的 JPEG 尺寸。ARW 条目的 JPEG 是预览。", Modifier.padding(horizontal=16.dp, vertical=4.dp), style=MaterialTheme.typography.bodySmall)
        if (state.mode == CameraMode.COMPUTER) ScanBar(state, actions)
        if (hiddenSelected > 0) Text("另有 $hiddenSelected 项被筛选隐藏，不会下载。", Modifier.padding(horizontal=16.dp, vertical=4.dp), style=MaterialTheme.typography.bodySmall, color=MaterialTheme.colorScheme.onSurfaceVariant)
        if (state.scan.phase == ScanPhase.Scanning) Text("查找仍在进行，列表会继续增加。", Modifier.padding(horizontal=16.dp, vertical=4.dp), style=MaterialTheme.typography.labelSmall, color=MaterialTheme.colorScheme.onSurfaceVariant)

        if (files.isEmpty()) {
            val discovered = state.files.count { !it.container }
            val empty = if (discovered == 0) galleryEmptyState(state.mode, state.scan)
                else GalleryEmpty("没有符合筛选的文件", "试试切换上方的筛选条件。")
            Box(Modifier.weight(1f)) { EmptyState(emptyIcon(state), empty.title, empty.text) }
        } else if (layout == GalleryLayout.Grid) {
            LazyVerticalGrid(state=gridState, columns=GridCells.Adaptive(120.dp), modifier=Modifier.weight(1f).testTag("gallery-grid"),
                contentPadding=PaddingValues(12.dp), verticalArrangement=Arrangement.spacedBy(8.dp), horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                items(files, key={ it.id }) { file ->
                    val bitmap = galleryThumbnail(file, scope, generation, canRead, actions)
                    Column(Modifier.clip(galleryShape).background(if (file.id in selectedVisible) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer)
                        .testTag("gallery-item-${file.id}")
                        .semantics { this.selected = file.id in selectedVisible }
                        .combinedClickable(enabled = !state.transferring || !file.container, onClick={ onItemClick(file) }, onLongClick={ onItemLongClick(file) })) {
                        Box(Modifier.fillMaxWidth().aspectRatio(1f), contentAlignment=Alignment.Center) {
                            if (bitmap != null) Image(bitmap.asImageBitmap(), file.name, Modifier.fillMaxSize(), contentScale=ContentScale.Crop)
                            else Icon(if (file.container) Icons.Outlined.Folder else if (file.raw) Icons.Outlined.RawOn else Icons.Outlined.Image, null, Modifier.size(32.dp), tint=MaterialTheme.colorScheme.onSurfaceVariant)
                            if (!file.container) IconButton(onClick={ detail = file }, modifier=Modifier.align(Alignment.TopStart)) { Icon(Icons.Outlined.Info, "照片信息 ${file.name}", tint=MaterialTheme.colorScheme.onSurface) }
                            if (file.id in selectedVisible) Icon(Icons.Outlined.CheckCircle, "已选择", Modifier.align(Alignment.TopEnd).padding(6.dp), tint=MaterialTheme.colorScheme.primary)
                        }
                        Text(file.name, Modifier.padding(horizontal=8.dp, vertical=4.dp), style=MaterialTheme.typography.labelMedium, maxLines=1, overflow=TextOverflow.Ellipsis)
                        Text(if (file.container) "文件夹" else (if (file.raw) "ARW · " else "JPEG · ")+bytes(effectiveFileSize(file)), Modifier.padding(start=8.dp, end=8.dp, bottom=8.dp), style=MaterialTheme.typography.labelSmall)
                    }
                }
            }
        } else {
            LazyColumn(state=listState, modifier=Modifier.weight(1f).testTag("gallery-list"),
                contentPadding=PaddingValues(vertical=4.dp), verticalArrangement=Arrangement.spacedBy(4.dp)) {
                items(files, key={ it.id }) { file ->
                    val bitmap = galleryThumbnail(file, scope, generation, canRead, actions)
                    Row(Modifier.fillMaxWidth().heightIn(min=56.dp).clip(galleryShape)
                        .background(if (file.id in selectedVisible) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer)
                        .testTag("gallery-item-${file.id}")
                        .semantics { this.selected = file.id in selectedVisible }
                        .combinedClickable(enabled = !state.transferring || !file.container, onClick={ onItemClick(file) }, onLongClick={ onItemLongClick(file) })
                        .padding(horizontal=8.dp, vertical=4.dp), verticalAlignment=Alignment.CenterVertically) {
                        Box(Modifier.size(56.dp), contentAlignment=Alignment.Center) {
                            if (bitmap != null) Image(bitmap.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale=ContentScale.Crop)
                            else Icon(if (file.container) Icons.Outlined.Folder else if (file.raw) Icons.Outlined.RawOn else Icons.Outlined.Image, null, Modifier.size(28.dp), tint=MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(file.name, style=MaterialTheme.typography.labelLarge, maxLines=1, overflow=TextOverflow.Ellipsis)
                            Text(gallerySecondaryText(file), style=MaterialTheme.typography.labelSmall, color=MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (!file.container) IconButton(onClick={ detail = file }) { Icon(Icons.Outlined.Info, "照片信息 ${file.name}") }
                        if (file.id in selectedVisible) Icon(Icons.Outlined.CheckCircle, "已选择", tint=MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
        if (unsupported > 0) Text("所选的 $unsupported 个文件未提供${selectedSize.title}，请更改尺寸或取消选择。", Modifier.padding(horizontal=16.dp), style=MaterialTheme.typography.bodySmall, color=MaterialTheme.colorScheme.error)
        if (selectedVisible.isNotEmpty()) Button(onClick={
            val choices = selectedFiles.map { f ->
                if (f.handle != null) f else {
                    val resource = f.jpegResource(selectedSize)
                    f.copy(resources=listOfNotNull(resource), size=resource?.size ?: 0)
                }
            }
            actions.enqueue(choices, generation); selected = emptySet()
        }, Modifier.fillMaxWidth().padding(horizontal=16.dp), enabled=canDownload && unsupported == 0 && selectedFiles.isNotEmpty(), shape=galleryShape) { Text("下载 ${selectedFiles.size} 个文件") }
        QueuePanel(state, actions)
    }
    preview?.let { request ->
        val pictures = files.filter { !it.container }
        if (pictures.none { it.id == request.id }) {
            // The file left the listing (rescan or directory change): close instead of crashing.
            LaunchedEffect(request) { preview = null }
        } else {
            val previewActionsEnabled = canDownload && !state.transferring
            PhotoViewer(pictures.map { file -> ViewerImage(file.id, file.name,
                if (file.raw && file.handle != null) "相机缩略预览 · ARW 原文件可单独下载" else "相机预览 · 下载后保存") }, request.id,
                refreshKey = request.generation to state.transferring, onDismiss={ preview = null },
                load={ image -> pictures.firstOrNull { it.id == image.key }?.let { file -> actions.preview(file, request.scope, request.generation) } }) { image ->
                pictures.firstOrNull { it.id == image.key }?.let { file ->
                    TextButton(onClick={ detail = file }) { Icon(Icons.Outlined.Info, null); Spacer(Modifier.width(4.dp)); Text("信息") }
                    TextButton(onClick={
                        val resource = file.jpegResource(selectedSize)
                        actions.enqueue(listOf(if (file.handle != null) file else file.copy(resources=listOfNotNull(resource), size=resource?.size ?: 0)), request.generation)
                    }, enabled=previewActionsEnabled && (file.handle != null || file.jpegResource(selectedSize) != null)) { Icon(Icons.Outlined.FileDownload, null); Spacer(Modifier.width(4.dp)); Text("下载") }
                    TextButton(onClick={ selected = selected + file.id; preview = null }, enabled=previewActionsEnabled) { Icon(Icons.Outlined.CheckCircleOutline, null); Spacer(Modifier.width(4.dp)); Text("选择") }
                }
            }
        }
    }
    detail?.let { file -> AlertDialog(onDismissRequest={ detail = null }, title={ Text(file.name) }, text={
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement=Arrangement.spacedBy(8.dp)) {
            galleryTimeText(file.date)?.let { Text(it) }
            if (file.handle != null) Text("${if (file.raw) "ARW 原文件" else "JPEG 原文件"} · ${bytes(file.size)}")
            else {
                if (file.raw) Text("此 ARW 条目提供的可下载资源为 JPEG 预览。")
                file.resources.filter { it.isJpeg }.distinctBy { it.url }.forEach { resource ->
                    Text(listOfNotNull(resource.jpegSize?.title ?: "JPEG", resource.resolution.takeIf { it.isNotBlank() }, resource.size.takeIf { it > 0 }?.let(::bytes)).joinToString(" · "))
                }
            }
        }
    }, confirmButton={ TextButton(onClick={ detail = null }) { Text("关闭") } }) }
}

@Composable private fun galleryThumbnail(file: CameraFile, scope: String?, generation: Long, canRead: Boolean, actions: GalleryActions): Bitmap? {
    val bitmap by produceState<Bitmap?>(null, file.id, scope, generation, canRead) {
        if (!file.container && canRead) value = actions.thumbnail(file, scope, generation)
    }
    return bitmap
}

/** Format · size · local capture time for the list row; the time is omitted when it cannot be read. */
private fun gallerySecondaryText(file: CameraFile): String = if (file.container) "文件夹"
    else listOfNotNull(if (file.raw) "ARW" else "JPEG", bytes(effectiveFileSize(file)), galleryTimeText(file.date)).joinToString(" · ")

@Composable private fun ScanBar(state: AppState, actions: GalleryActions) {
    val scan = state.scan
    if (scan.phase == ScanPhase.Idle) return
    Surface(tonalElevation=1.dp, modifier=Modifier.fillMaxWidth().testTag("gallery-scan-bar")) { Column(Modifier.padding(horizontal=16.dp, vertical=8.dp), verticalArrangement=Arrangement.spacedBy(4.dp)) {
        scanHeadline(scan, state.transferring)?.let { Text(it, style=MaterialTheme.typography.labelLarge) }
        scanCountText(scan)?.let { Text(it, style=MaterialTheme.typography.bodySmall, color=MaterialTheme.colorScheme.onSurfaceVariant) }
        if (scan.phase == ScanPhase.Scanning) {
            if (scan.total != null && scan.total > 0) LinearProgressIndicator(progress={ (scan.scanned.toFloat()/scan.total).coerceIn(0f, 1f) }, modifier=Modifier.fillMaxWidth())
            else LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        when (scan.phase) {
            ScanPhase.Scanning -> if (scan.paused) {
                // A transfer-driven pause must not look like a user pause the user can simply unlock.
                TextButton(onClick={ actions.setScanPaused(false) }, enabled=!state.transferring) { Text("继续查找") }
            } else TextButton(onClick={ actions.setScanPaused(true) }) { Text("暂停查找") }
            ScanPhase.Done -> TextButton(onClick=actions.rescan) { Text("重新查找") }
            ScanPhase.Failed -> if (state.connected) TextButton(onClick=actions.rescan) { Text("重试查找") }
            ScanPhase.Idle -> Unit
        }
    } }
}

@Composable internal fun QueuePanel(state: AppState, actions: GalleryActions) {
    if (state.queue.isEmpty()) return
    var expanded by rememberSaveable { mutableStateOf(false) }
    val active = state.queue.firstOrNull { it.state == "传输中" }
    val canContinue = continueHistoryEnabled(state.connected, state.busy, state.transferring, state.mode, state.scan)
    Surface(tonalElevation=2.dp) { Column(Modifier.fillMaxWidth().padding(12.dp)) {
        if (state.queueNotice.isNotBlank()) Text(state.queueNotice, style=MaterialTheme.typography.labelSmall, color=MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment=Alignment.CenterVertically) {
            Text(active?.file?.name ?: "队列 · ${state.queue.count { it.state == "完成" || it.state == "已存在" }} / ${state.queue.size}", Modifier.weight(1f), maxLines=1, style=MaterialTheme.typography.labelLarge)
            if (state.transferring) TextButton(onClick=actions.cancelTransfers) { Text("取消") }
            else TextButton(onClick=actions.retryFailed, enabled=canContinue) { Text("继续未完成") }
            IconButton(onClick={ expanded = !expanded }) { Icon(if (expanded) Icons.Outlined.ExpandMore else Icons.Outlined.ExpandLess, "队列详情") }
        }
        if (active != null) {
            if (active.total > 0) LinearProgressIndicator(progress={ (active.received.toFloat()/active.total).coerceIn(0f, 1f) }, modifier=Modifier.fillMaxWidth())
            else LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("${bytes(active.received)} / ${bytes(active.total)}", style=MaterialTheme.typography.labelSmall)
        }
        if (expanded) {
            Column(Modifier.heightIn(max=180.dp).verticalScroll(rememberScrollState())) { state.queue.takeLast(50).forEach { item -> Text("${item.file.name} · ${item.state}${item.error?.let { "\n$it" } ?: ""}", style=MaterialTheme.typography.bodySmall, modifier=Modifier.padding(vertical=4.dp)) } }
            if (!state.transferring) TextButton(onClick=actions.clearCompleted) { Text("清空队列记录") }
        }
    } }
}

private fun emptyIcon(state: AppState): ImageVector = when {
    state.scan.phase == ScanPhase.Scanning -> Icons.Outlined.Search
    else -> Icons.Outlined.Image
}

@Composable internal fun EmptyState(icon: ImageVector, title: String, text: String) {
    Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement=Arrangement.Center, horizontalAlignment=Alignment.CenterHorizontally) {
        Icon(icon, null, Modifier.size(46.dp), tint=MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp)); Text(title, style=MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp)); Text(text, style=MaterialTheme.typography.bodyMedium, color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

internal fun bytes(value: Long): String = when {
    value <= 0 -> "未知大小"; value < 1024 -> "$value B"; value < 1024*1024 -> String.format(Locale.ROOT, "%.1f KB", value/1024.0)
    else -> String.format(Locale.ROOT, "%.1f MB", value/(1024.0*1024))
}
