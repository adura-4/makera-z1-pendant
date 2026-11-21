package com.z1pendant.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.z1pendant.AppViewModel
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSLocale
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSData
import platform.Foundation.NSTimeZone
import platform.Foundation.dataWithContentsOfURL
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.localTimeZone
import platform.Foundation.timeIntervalSince1970
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned

/** Seconds since boot (not counting sleep), as ms: monotonic, like Android's elapsedRealtime for our use. */
actual fun nowMs(): Long = (NSProcessInfo.processInfo.systemUptime * 1000.0).toLong()

private val hms = NSDateFormatter().apply {
    dateFormat = "HH:mm:ss"
    locale = NSLocale(localeIdentifier = "en_US_POSIX")
}

actual fun wallClockHms(): String = hms.stringFromDate(NSDate())

actual fun epochSeconds(): Long = NSDate().timeIntervalSince1970.toLong()

actual fun utcOffsetSeconds(epochSec: Long): Int =
    NSTimeZone.localTimeZone.secondsFromGMTForDate(
        NSDate.dateWithTimeIntervalSince1970(epochSec.toDouble())).toInt()

/** The Files app picker; the chosen file is copied into the app's sandbox, then read. */
@Composable
actual fun rememberFilePicker(onPicked: (name: String, bytes: ByteArray) -> Unit): () -> Unit {
    val callback = androidx.compose.runtime.rememberUpdatedState(onPicked)
    val delegate = remember {
        FilePickerDelegate { url ->
            val data = NSData.dataWithContentsOfURL(url) ?: return@FilePickerDelegate
            callback.value(url.lastPathComponent ?: "file.nc", data.toByteArray())
        }
    }
    return remember(delegate) {
        {
            val picker = platform.UIKit.UIDocumentPickerViewController(
                forOpeningContentTypes = listOf(platform.UniformTypeIdentifiers.UTTypeData), asCopy = true)
            picker.delegate = delegate
            picker.allowsMultipleSelection = false
            topViewController()?.presentViewController(picker, animated = true, completion = null)
        }
    }
}

private class FilePickerDelegate(private val onUrl: (platform.Foundation.NSURL) -> Unit) :
    platform.darwin.NSObject(), platform.UIKit.UIDocumentPickerDelegateProtocol {
    override fun documentPicker(controller: platform.UIKit.UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
        (didPickDocumentsAtURLs.firstOrNull() as? platform.Foundation.NSURL)?.let(onUrl)
    }
}

@Suppress("DEPRECATION")
private fun topViewController(): platform.UIKit.UIViewController? {
    var vc = platform.UIKit.UIApplication.sharedApplication.keyWindow?.rootViewController
    while (vc?.presentedViewController != null) vc = vc.presentedViewController
    return vc
}

@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
private fun platform.Foundation.NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    val out = ByteArray(size)
    if (size > 0) out.usePinned { pinned -> platform.posix.memcpy(pinned.addressOf(0), bytes, length) }
    return out
}

actual fun platformLog(level: Char, tag: String, msg: String) {
    // Not NSLog: a Kotlin String passed through its C varargs isn't an Objective-C object, and the
    // %@ formatting crashed the app (SIGTRAP in objc_opt_respondsToSelector). stdout reaches the
    // Xcode / devicectl console all the same.
    println("$level/$tag: $msg")
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
