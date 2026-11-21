package com.z1pendant.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.z1pendant.AppViewModel
import com.z1pendant.files.FilesController
import com.z1pendant.files.FilesController.InfoState
import com.z1pendant.files.RemoteFiles
import com.z1pendant.link.RemoteEntry
import com.z1pendant.platform.PlatformBackHandler
import com.z1pendant.platform.epochSeconds
import com.z1pendant.platform.rememberFilePicker
import com.z1pendant.platform.utcOffsetSeconds

private val Danger = Color(0xFFF87171)
private val Playing = Color(0xFF86EFAC)
private val WarnBg = Color(0xFF3A2A0B)
private val WarnText = Color(0xFFFDE68A)

/**
 * Files on the Z1's SD card (/sd/gcodes and its folders): Job info, rename, delete, new folder, upload from
 * the phone. Mockup: pendant/mockups/files_mock.html.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(vm: AppViewModel, pickForRun: Boolean = false, onRun: (String) -> Unit = {}, onClose: () -> Unit) {
    val f = vm.files
    val path by f.path.collectAsState()
    val raw by f.entries.collectAsState()
    val loading by f.loading.collectAsState()
    val loadError by f.loadError.collectAsState()
    val sort by f.sort.collectAsState()
    val selected by f.selected.collectAsState()
    val upload by f.upload.collectAsState()
    val conflict by f.conflict.collectAsState()
    val working by f.working.collectAsState()
    val readOnly by f.readOnly.collectAsState()
    val playing by f.playing.collectAsState()
    val fresh by f.justUploaded.collectAsState()
    // Read so the Run gate below follows the machine.
    vm.link.status.collectAsState().value; vm.link.conn.collectAsState().value
    val runBlock = vm.runBlock()
    val entries = remember(raw, sort) { raw?.let { RemoteFiles.sorted(it, sort) } }
    val selecting = selected.isNotEmpty()

    var sheetFor by remember { mutableStateOf<RemoteEntry?>(null) }
    var renameFor by remember { mutableStateOf<RemoteEntry?>(null) }
    var deleteAsk by remember { mutableStateOf<List<String>?>(null) }
    var newFolder by remember { mutableStateOf(false) }
    val pick = rememberFilePicker { name, bytes -> f.upload(name, bytes) }

    LaunchedEffect(Unit) { f.open() }
    PlatformBackHandler { if (!f.back()) onClose() }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 12.dp, vertical = 8.dp)) {
            // ---- header: title bar, or "N selected" while selecting ----
            if (selecting) {
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color(0xFF0E2A3D)),
                    verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = f::clearSelection) { Icon(Icons.Filled.Close, "Stop selecting") }
                    Text("${selected.size} selected", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    IconButton(onClick = { deleteAsk = selected.toList() }, enabled = readOnly == null) {
                        Icon(Icons.Filled.Delete, "Delete", tint = if (readOnly == null) Danger else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Close") }
                    Text(if (pickForRun) "Select File" else "Files", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    IconButton(onClick = { newFolder = true }, enabled = readOnly == null) { Icon(Icons.Filled.CreateNewFolder, "New folder") }
                    IconButton(onClick = pick, enabled = readOnly == null && upload == null) { Icon(Icons.Filled.FileUpload, "Upload from the phone") }
                }
            }

            // ---- where we are ----
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 12.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("SD", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                val crumbs = RemoteFiles.crumbs(path)
                crumbs.forEachIndexed { i, (label, p) ->
                    Text("  ›  ", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                    val last = i == crumbs.lastIndex
                    Text(label, fontSize = 13.sp,
                        color = if (last) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary,
                        modifier = if (last) Modifier else Modifier.clickable { f.goTo(p) })
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)) {
                for (s in RemoteFiles.Sort.entries) FilterChip(selected = sort == s, onClick = { f.setSort(s) }, label = { Text(s.name) })
            }

            readOnly?.let { why ->
                Text(why, color = WarnText, fontSize = 13.sp,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(8.dp)).background(WarnBg).padding(10.dp))
            }

            upload?.let { u ->
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.secondaryContainer).padding(10.dp)) {
                    Text("Uploading ${u.name}", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    LinearProgressIndicator(progress = { if (u.total > 0) u.sent.toFloat() / u.total else 0f },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${RemoteFiles.sizeLabel(u.sent)} of ${RemoteFiles.sizeLabel(u.total)}", fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                        TextButton(onClick = f::cancelUpload) { Text("Cancel") }
                    }
                }
            }

            // ---- the list ----
            PullToRefreshBox(isRefreshing = loading && entries != null, onRefresh = f::refresh, modifier = Modifier.weight(1f).fillMaxWidth()) {
                when {
                    entries == null && loadError != null -> Message(loadError!!)
                    entries == null -> Message("Reading the SD card…")
                    entries.isEmpty() -> Message(if (path == RemoteFiles.ROOT) "No files yet." else "This folder is empty.")
                    else -> LazyColumn(Modifier.fillMaxSize()) {
                        items(entries, key = { it.name }) { e ->
                            val full = RemoteFiles.child(path, e.name)
                            EntryRow(e, isSelected = e.name in selected, selecting = selecting,
                                isPlaying = playing == full, isFresh = e.name in fresh,
                                onClick = {
                                    when {
                                        selecting -> f.toggleSelected(e.name)
                                        e.isDir -> f.enter(e.name)
                                        pickForRun -> if (runBlock == null) onRun(full)
                                        else -> sheetFor = e
                                    }
                                },
                                onLongClick = { f.toggleSelected(e.name) })
                        }
                    }
                }
            }

            // ---- footer ----
            val files = entries.orEmpty().filter { !it.isDir }
            Text(working ?: "${files.size} file${if (files.size == 1) "" else "s"} · ${RemoteFiles.sizeLabel(files.sumOf { it.size })}" +
                    if (selecting) "" else if (pickForRun) "   ·   " + (runBlock ?: "tap a file to set it up") else "   ·   tap a file, hold to select",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp, start = 4.dp))
        }
    }

    // ---- the file's sheet ----
    sheetFor?.let { e ->
        val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { sheetFor = null }, sheetState = state, containerColor = Color(0xFF111A2B)) {
            FileSheet(vm, f, e, path, readOnly, playing == RemoteFiles.child(path, e.name), runBlock,
                onRename = { sheetFor = null; renameFor = e },
                onDelete = { sheetFor = null; deleteAsk = listOf(e.name) },
                onRun = { sheetFor = null; onRun(RemoteFiles.child(path, e.name)) })
        }
    }

    deleteAsk?.let { names ->
        AlertDialog(onDismissRequest = { deleteAsk = null },
            title = { Text(if (names.size == 1) "Delete ${names.first()}?" else "Delete ${names.size} items?") },
            text = {
                Column {
                    Text((if (names.size == 1) "It's removed" else "They're removed") + " from the Z1's SD card and can't be brought back." +
                        if (names.any { n -> entries.orEmpty().any { it.name == n && it.isDir } }) " A folder is deleted with everything in it." else "")
                    if (names.size > 1) {
                        Spacer(Modifier.height(8.dp))
                        names.take(8).forEach { Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        if (names.size > 8) Text("and ${names.size - 8} more", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { f.delete(names); deleteAsk = null }) { Text("Delete", color = Danger) } },
            dismissButton = { TextButton(onClick = { deleteAsk = null }) { Text("Cancel") } })
    }

    renameFor?.let { e ->
        val dot = e.name.lastIndexOf('.').takeIf { !e.isDir && it > 0 }
        val ext = dot?.let { e.name.substring(it) } ?: ""
        var base by remember(e) { mutableStateOf(dot?.let { e.name.substring(0, it) } ?: e.name) }
        AlertDialog(onDismissRequest = { renameFor = null },
            title = { Text("Rename") },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(base, { base = it }, singleLine = true, modifier = Modifier.weight(1f))
                    if (ext.isNotEmpty()) Text(ext, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 6.dp))
                }
            },
            confirmButton = { TextButton(onClick = { f.rename(e, base + ext); renameFor = null }, enabled = base.isNotBlank()) { Text("Rename") } },
            dismissButton = { TextButton(onClick = { renameFor = null }) { Text("Cancel") } })
    }

    if (newFolder) {
        var name by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { newFolder = false },
            title = { Text("New folder in ${RemoteFiles.crumbs(path).joinToString(" › ") { it.first }}") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true, placeholder = { Text("Folder name") }) },
            confirmButton = { TextButton(onClick = { f.makeFolder(name); newFolder = false }, enabled = name.isNotBlank()) { Text("Create") } },
            dismissButton = { TextButton(onClick = { newFolder = false }) { Text("Cancel") } })
    }

    conflict?.let { c ->
        val existing = entries.orEmpty().firstOrNull { it.name == c.name }
        AlertDialog(onDismissRequest = { f.resolveConflict(FilesController.Clash.Cancel) },
            title = { Text("${c.name} is already on the Z1") },
            text = {
                existing?.let { Text("${RemoteFiles.sizeLabel(it.size)} · ${RemoteFiles.timeLabel(it.modified, epochSeconds(), ::utcOffsetSeconds)}") }
            },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = { f.resolveConflict(FilesController.Clash.Replace) }) { Text("Replace it") }
                    TextButton(onClick = { f.resolveConflict(FilesController.Clash.KeepBoth) }) {
                        Text("Keep both (${RemoteFiles.uniqueName(c.name, entries.orEmpty().map { it.name }.toSet())})")
                    }
                    TextButton(onClick = { f.resolveConflict(FilesController.Clash.Cancel) }) { Text("Cancel") }
                }
            })
    }
}

@Composable
private fun Message(text: String) {
    Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 48.dp), contentAlignment = Alignment.TopCenter) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EntryRow(e: RemoteEntry, isSelected: Boolean, selecting: Boolean, isPlaying: Boolean, isFresh: Boolean,
                     onClick: () -> Unit, onLongClick: () -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
        .background(if (isSelected || isPlaying) Color(0xFF0E2A3D) else Color.Transparent)
        .combinedClickable(onClick = onClick, onLongClick = onLongClick)
        .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        when {
            selecting -> Icon(if (isSelected) Icons.Filled.CheckBox else Icons.Filled.CheckBoxOutlineBlank, null,
                tint = if (isSelected) MaterialTheme.colorScheme.primary else muted)
            e.isDir -> Icon(Icons.Filled.Folder, null, tint = MaterialTheme.colorScheme.primary)
            isPlaying -> Icon(Icons.Filled.PlayArrow, null, tint = Playing)
            else -> Icon(Icons.Outlined.Description, null, tint = muted)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(e.name, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false))
                if (isPlaying) Tag("Playing", Playing, Color(0xFF123B23))
                else if (isFresh) Tag("just uploaded", MaterialTheme.colorScheme.primary, Color(0xFF0E2A3D))
            }
            Text(if (e.isDir) "folder" else "${RemoteFiles.sizeLabel(e.size)} · ${RemoteFiles.timeLabel(e.modified, epochSeconds(), ::utcOffsetSeconds)}",
                fontSize = 12.sp, color = muted)
        }
        if (e.isDir && !selecting) Icon(Icons.Filled.ChevronRight, null, tint = muted)
    }
}

@Composable
private fun Tag(text: String, fg: Color, bg: Color) {
    Text(text, color = fg, fontSize = 11.sp, modifier = Modifier.padding(start = 6.dp).clip(RoundedCornerShape(8.dp)).background(bg)
        .padding(horizontal = 6.dp, vertical = 1.dp))
}

@Composable
private fun FileSheet(vm: AppViewModel, f: FilesController, e: RemoteEntry, dir: String, readOnly: String?, isPlaying: Boolean, runBlock: String?,
                      onRename: () -> Unit, onDelete: () -> Unit, onRun: () -> Unit) {
    val full = RemoteFiles.child(dir, e.name)
    val infos by f.jobInfo.collectAsState()
    val info = infos[full]
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val canChange = readOnly == null && !isPlaying
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState())) {
        Text(e.name, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Text("${RemoteFiles.sizeLabel(e.size)} · ${RemoteFiles.timeLabel(e.modified, epochSeconds(), ::utcOffsetSeconds)} · ${dir.removePrefix("/sd")}",
            fontSize = 12.sp, color = muted, modifier = Modifier.padding(top = 2.dp, bottom = 12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SheetAction(Icons.Filled.PlayArrow, "Run", null, enabled = !isPlaying && runBlock == null, modifier = Modifier.weight(1f), onClick = onRun)
            SheetAction(Icons.Filled.Build, "Job info", null, enabled = info !is InfoState.Loading, modifier = Modifier.weight(1f)) { f.loadJobInfo(e) }
            SheetAction(Icons.Filled.DriveFileRenameOutline, "Rename", null, enabled = canChange, modifier = Modifier.weight(1f), onClick = onRename)
            SheetAction(Icons.Filled.Delete, "Delete", null, enabled = canChange, tint = Danger, modifier = Modifier.weight(1f), onClick = onDelete)
        }
        if (isPlaying) Text("This is the file the Z1 is playing.", color = Playing, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))
        else (readOnly ?: runBlock)?.let { Text(it, color = WarnText, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp)) }

        when (info) {
            null -> {}
            InfoState.Loading -> Text("Reading the file's header…", color = muted, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp))
            is InfoState.Failed -> Text(info.why, color = Danger, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp))
            is InfoState.Ready -> Column(Modifier.fillMaxWidth().padding(top = 12.dp).clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant).padding(12.dp)) {
                val ji = info.info
                Row { Text("Job info", color = muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                      Text("read in ${(info.seconds * 10).toInt() / 10.0} s", color = muted, fontSize = 12.sp) }
                ji.stockHeightMm?.let { InfoRow("Stock height", "$it mm") }
                ji.zero?.let { InfoRow("Zero", it) }
                Row(Modifier.padding(top = 8.dp)) {
                    Text("Tools", color = muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    Text("${ji.tools.size}", color = muted, fontSize = 12.sp)
                }
                if (ji.tools.isEmpty()) Text("No tools listed in the header.", fontSize = 13.sp, color = muted)
                ji.tools.forEach { t ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
                        Text("T${t.number}", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                            modifier = Modifier.width(40.dp))
                        Text(t.description, fontSize = 14.sp, modifier = Modifier.weight(1f))
                        t.diameterMm?.let { Text("$it mm", fontSize = 13.sp, color = muted, modifier = Modifier.padding(start = 8.dp)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.padding(top = 6.dp)) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp, modifier = Modifier.width(100.dp))
        Text(value, fontSize = 14.sp)
    }
}

@Composable
private fun SheetAction(icon: ImageVector, label: String, sub: String?, enabled: Boolean, modifier: Modifier = Modifier,
                        tint: Color = MaterialTheme.colorScheme.onSurface, onClick: () -> Unit) {
    val alpha = if (enabled) 1f else 0.38f
    Column(modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.secondaryContainer)
        .clickable(enabled = enabled, onClick = onClick).padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, label, tint = tint.copy(alpha = alpha), modifier = Modifier.size(22.dp))
        Text(label, fontSize = 13.sp, color = tint.copy(alpha = alpha), modifier = Modifier.padding(top = 4.dp))
        sub?.let { Text(it, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha)) }
    }
}
