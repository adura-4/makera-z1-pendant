package com.z1pendant

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.window.ComposeUIViewController
import com.z1pendant.platform.IosPlatform
import com.z1pendant.ui.App
import com.z1pendant.ui.PendantTheme
import platform.UIKit.UIApplication
import platform.UIKit.UIViewController

/** One view model for the app's lifetime (the iOS app has a single window). */
private val viewModel by lazy { AppViewModel(IosPlatform()) }

/**
 * Entry point for the iOS app (Swift: `MainViewControllerKt.MainViewController()`).
 *
 * The app's Info.plist needs `CADisableMinimumFrameDurationOnPhone` = YES (Compose checks it) and
 * `NSLocalNetworkUsageDescription` (TCP 2222 and UDP 3333 on the LAN). Receiving the UDP 3333
 * discovery broadcasts works without Apple's multicast entitlement (confirmed on an iPhone 16,
 * iOS 27, free-provisioning build), once the user allows local network access.
 * Hardware keys (bound buttons, volume jog) aren't wired up on iOS yet.
 */
fun MainViewController(): UIViewController = ComposeUIViewController {
    val vm = viewModel
    // Test hook (launch arguments, e.g. `xcrun simctl launch booted <bundle> -testpad X,1,2,1500,4`):
    // axis, direction, taps, gap ms, pad step index. Runs 4 s after launch, once connected.
    LaunchedEffect(Unit) {
        val args = platform.Foundation.NSProcessInfo.processInfo.arguments.map { it.toString() }
        val i = args.indexOf("-testpad")
        if (i >= 0 && i + 1 < args.size) {
            val p = args[i + 1].split(',')
            kotlinx.coroutines.delay(4000)
            vm.testPadTaps(p[0].first(), p[1].toInt(), p[2].toInt(), p[3].toLong(), p[4].toInt())
        }
    }
    val keepOn by vm.keepScreenOn.collectAsState()
    LaunchedEffect(keepOn) { UIApplication.sharedApplication.idleTimerDisabled = keepOn }
    PendantTheme { App(vm) }
}
