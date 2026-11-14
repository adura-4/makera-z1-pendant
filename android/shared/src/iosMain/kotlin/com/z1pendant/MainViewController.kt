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
 * `NSLocalNetworkUsageDescription` (TCP 2222 and UDP 3333 on the LAN); discovery also needs the
 * `com.apple.developer.networking.multicast` entitlement to receive broadcasts.
 * Hardware keys (bound buttons, volume jog) aren't wired up on iOS yet.
 */
fun MainViewController(): UIViewController = ComposeUIViewController {
    val vm = viewModel
    val keepOn by vm.keepScreenOn.collectAsState()
    LaunchedEffect(keepOn) { UIApplication.sharedApplication.idleTimerDisabled = keepOn }
    PendantTheme { App(vm) }
}
