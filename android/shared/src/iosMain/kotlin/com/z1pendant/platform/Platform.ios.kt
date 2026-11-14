package com.z1pendant.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.z1pendant.AppViewModel
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSLocale
import platform.Foundation.NSLog
import platform.Foundation.NSProcessInfo

/** Seconds since boot (not counting sleep), as ms: monotonic, like Android's elapsedRealtime for our use. */
actual fun nowMs(): Long = (NSProcessInfo.processInfo.systemUptime * 1000.0).toLong()

private val hms = NSDateFormatter().apply {
    dateFormat = "HH:mm:ss"
    locale = NSLocale(localeIdentifier = "en_US_POSIX")
}

actual fun wallClockHms(): String = hms.stringFromDate(NSDate())

actual fun platformLog(level: Char, tag: String, msg: String) {
    // NSLog takes a format string: pass the text as an argument so a '%' in it is harmless.
    NSLog("%@", "$level/$tag: $msg")
}

/** iPhone has no system back button; screens close with their close (X) button. */
@Composable
actual fun PlatformBackHandler(onBack: () -> Unit) {}

/**
 * TODO(iOS): hotkey export/import through the Files app (UIDocumentPickerViewController or a
 * share sheet). Until then the buttons say it isn't there yet.
 */
@Composable
actual fun rememberHotkeyFileActions(vm: AppViewModel): HotkeyFileActions = remember(vm) {
    object : HotkeyFileActions {
        override fun export() { vm.snack.tryEmit("Export is not available on iPhone yet") }
        override fun import() { vm.snack.tryEmit("Import is not available on iPhone yet") }
    }
}
