package com.z1pendant

import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.z1pendant.hotkeys.Hotkey
import com.z1pendant.ui.ConsoleScreen
import com.z1pendant.ui.HotkeyEditor
import com.z1pendant.ui.MainScreen
import com.z1pendant.ui.SettingsScreen

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        setContent {
            val keepOn by vm.keepScreenOn.collectAsState()
            LaunchedEffect(keepOn) {
                if (keepOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            MaterialTheme(colorScheme = darkColorScheme(
                primary = Color(0xFF38BDF8), onPrimary = Color(0xFF00222F),
                secondaryContainer = Color(0xFF1E293B), onSecondaryContainer = Color(0xFFE2E8F0),
                surfaceVariant = Color(0xFF172033), onSurfaceVariant = Color(0xFF94A3B8),
                background = Color(0xFF0B1120), surface = Color(0xFF0F172A),
            )) {
                App(vm)
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** `am start -n com.z1pendant/.MainActivity --es host 192.168.1.50 [--ei port 2222] [--es name Z1]` */
    private fun handleIntent(i: android.content.Intent?) {
        i ?: return
        // Test hook: `--ei testclicks 100 --ei testms 1000 [--ei teststep 3]` = burst of handwheel clicks.
        if (i.hasExtra("testclicks")) {
            if (i.hasExtra("teststep")) vm.selectStep(i.getIntExtra("teststep", 2))
            val n = i.getIntExtra("testclicks", 0); val ms = i.getIntExtra("testms", 1000).toLong()
            vm.viewModelScopeLaunchBurst(n, ms)
        }
        val host = i.getStringExtra("host") ?: return
        vm.connect(com.z1pendant.link.MachineTarget(i.getStringExtra("name") ?: host, host, i.getIntExtra("port", 2222)))
    }

    /**
     * Wi-Fi power save made status round-trips swing 18–300 ms on the Pixel 10 (the radio dozes
     * between polls). The low-latency lock turns power save off while the app is visible; Android
     * only honours it for a foreground app with the screen on, so holding it here costs nothing
     * in the background.
     */
    private val wifiLock by lazy {
        (applicationContext.getSystemService(WIFI_SERVICE) as android.net.wifi.WifiManager)
            .createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "z1pendant-jog")
            .apply { setReferenceCounted(false) }
    }

    override fun onResume() { super.onResume(); wifiLock.acquire() }
    override fun onPause() { wifiLock.release(); super.onPause() }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (vm.onKey(event)) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onStop() {
        super.onStop()
        vm.jog.cancelPending()
    }
}

@Composable
fun App(vm: AppViewModel) {
    var settings by remember { mutableStateOf(false) }
    var console by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Hotkey?>(null) }
    var editorOpen by remember { mutableStateOf(false) }
    val confirm by vm.confirmFor.collectAsState()
    val snackHost = remember { SnackbarHostState() }
    // Kept here so returning from the editor/settings doesn't jump the main screen back to the top.
    val mainScroll = androidx.compose.foundation.rememberScrollState()
    LaunchedEffect(Unit) { vm.snack.collect { snackHost.currentSnackbarData?.dismiss(); snackHost.showSnackbar(it) } }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { Box(Modifier.fillMaxSize()) {
        // Full-screen pages live in the activity window (not Dialogs) so dispatchKeyEvent sees
        // every button press, which the "press any button to bind" flow relies on.
        vm.onMainScreen = !(editorOpen || settings || console)
        when {
            editorOpen -> HotkeyEditor(vm, editing) { editorOpen = false; editing = null; vm.capture.value = null }
            settings -> SettingsScreen(vm) { settings = false }
            console -> ConsoleScreen(vm) { console = false }
            else -> MainScreen(vm, onSettings = { settings = true }, onConsole = { console = true },
                onEdit = { editing = it; editorOpen = true }, scroll = mainScroll)
        }
        SnackbarHost(snackHost, Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.safeDrawing))
    } }
    confirm?.let { h ->
        AlertDialog(onDismissRequest = { vm.confirmFor.value = null },
            title = { Text("Run “${h.label}”?") },
            text = { Text(h.steps.joinToString("\n") { s -> "• " + s.describe { id -> vm.hotkeys.value.firstOrNull { it.id == id }?.label } }) },
            confirmButton = { TextButton(onClick = vm::confirmRun) { Text("Run") } },
            dismissButton = { TextButton(onClick = { vm.confirmFor.value = null }) { Text("Cancel") } })
    }
}
