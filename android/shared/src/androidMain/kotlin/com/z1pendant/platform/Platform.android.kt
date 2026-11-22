package com.z1pendant.platform

import android.os.SystemClock
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.z1pendant.AppViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

actual fun nowMs(): Long = SystemClock.elapsedRealtime()

actual fun wallClockHms(): String = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())

actual fun epochSeconds(): Long = System.currentTimeMillis() / 1000

actual fun utcOffsetSeconds(epochSec: Long): Int = java.util.TimeZone.getDefault().getOffset(epochSec * 1000) / 1000

@Composable
actual fun rememberFilePicker(onPicked: (name: String, bytes: ByteArray) -> Unit): () -> Unit {
    val ctx = LocalContext.current
    val callback = androidx.compose.runtime.rememberUpdatedState(onPicked)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val name = ctx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file.nc"
        val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@rememberLauncherForActivityResult
        callback.value(name, bytes)
    }
    return remember(launcher) { { launcher.launch(arrayOf("*/*")) } }
}

actual val systemPickerHasCancel: Boolean = false

actual fun platformLog(level: Char, tag: String, msg: String) {
    when (level) {
        'D' -> Log.d(tag, msg)
        'W' -> Log.w(tag, msg)
        else -> Log.i(tag, msg)
    }
}

@Composable
actual fun PlatformBackHandler(onBack: () -> Unit) = BackHandler(onBack = onBack)

/** System file picker: "save as" for export, "open" for import. */
@Composable
actual fun rememberHotkeyFileActions(vm: AppViewModel): HotkeyFileActions {
    val ctx = LocalContext.current
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        ctx.contentResolver.openOutputStream(uri)?.use { it.write(vm.exportHotkeys().toByteArray()) }
        vm.snack.tryEmit("Hotkeys exported")
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        ctx.contentResolver.openInputStream(uri)?.use { vm.importHotkeys(it.readBytes().decodeToString()) }
    }
    return remember(exporter, importer) {
        object : HotkeyFileActions {
            override fun export() = exporter.launch("z1-hotkeys.json")
            override fun import() = importer.launch(arrayOf("application/json", "text/plain", "*/*"))
        }
    }
}
