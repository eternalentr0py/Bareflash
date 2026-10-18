package com.bareflash.app

import com.bareflash.app.log.AppLog
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import android.graphics.Color
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bareflash.app.console.ConsoleViewModel
import com.bareflash.app.ui.CommandsScreen
import com.bareflash.app.ui.ConsoleScreen
import com.bareflash.app.ui.DumpsScreen
import com.bareflash.app.ui.MainShell
import com.bareflash.app.ui.SettingsScreen
import com.bareflash.app.ui.a11yModal
import com.bareflash.app.ui.a11yHideFromReader
import com.bareflash.app.ui.LocalAccessibility
import com.bareflash.app.ui.AccessibilityAnnouncer
import androidx.compose.runtime.CompositionLocalProvider
import com.bareflash.app.ui.theme.BareflashTheme
import com.bareflash.app.ui.theme.LocalNcDark
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import com.bareflash.app.engine.EngineMode
import com.bareflash.app.ui.DisclaimerScreen
import com.bareflash.app.ui.Onboarding
import com.bareflash.app.ui.TutorialOverlay

class MainActivity : ComponentActivity() {

    // Shared by the Console and Commands tabs. Created lazily, and only after the
    // disclaimer is accepted: creating it starts USB scanning.
    private val vm: ConsoleViewModel by viewModels()

    // Samsung (Odin) mode owns its own state and USB handling.
    private val samsung: com.bareflash.app.samsung.SamsungViewModel by viewModels()

    // ADB / fastboot mode owns its own state and USB handling too.
    private val adbfb: com.bareflash.app.adbfb.AdbFbViewModel by viewModels()

    // A USB-attach launch that arrived before the disclaimer was accepted.
    private var deferredIntent: Intent? = null

    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* export still
            works without it; the in-app progress bar on the Dumps tab covers that case. */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Onboarding.init(this)
        setContent {
            val settings by AppGraph.settings.state.collectAsStateWithLifecycle()
            val accepted by Onboarding.accepted.collectAsStateWithLifecycle()
            if (!accepted) {
                // First run: nothing else is touched until the user agrees.
                BareflashTheme(settings.appearance, settings.look, EngineMode.MTK, settings.accessibility) {
                    SystemBarsEffect()
                    DisclaimerScreen(onExit = { finishAffinity() }, onAccept = Onboarding::accept)
                }
            } else {
                AppContent()
            }
        }
        handleIntent(intent)
    }

    @Composable
    private fun SystemBarsEffect() {
        // Bar icons follow the in-app light / dark choice, not just the system's.
        val dark = LocalNcDark.current
        DisposableEffect(dark) {
            enableEdgeToEdge(
                statusBarStyle = if (dark) SystemBarStyle.dark(Color.TRANSPARENT)
                else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
                navigationBarStyle = if (dark) SystemBarStyle.dark(Color.TRANSPARENT)
                else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
            )
            onDispose { }
        }
    }

    @Composable
    private fun AppContent() {
        val settings by AppGraph.settings.state.collectAsStateWithLifecycle()
        val engine by vm.mode.collectAsStateWithLifecycle()
        val tutorial by Onboarding.tutorialVisible.collectAsStateWithLifecycle()

        // Things that must wait for the disclaimer: the notification prompt, the
        // crash notice, and a USB device that launched the app.
        LaunchedEffect(Unit) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            // If the previous run crashed, say so plainly instead of quietly
            // reopening, and name the file so a bug report can include the stack trace.
            AppLog.consumeCrashNotice()?.let { what ->
                vm.log("The app closed unexpectedly last time it ran ($what).\nThe full report is in Settings \u2192 Logging.\n")
            }
            deferredIntent?.let { pending ->
                deferredIntent = null
                handleIntent(pending)
            }
        }
        LaunchedEffect(engine) {
            samsung.setActive(engine == EngineMode.BROKKR)
            adbfb.setActive(engine == EngineMode.ADB_FASTBOOT)
        }
        CompositionLocalProvider(LocalAccessibility provides settings.accessibility) {
            BareflashTheme(settings.appearance, settings.look, engine, settings.accessibility) {
                SystemBarsEffect()
                AccessibilityAnnouncer(vm)
                Box(Modifier.fillMaxSize()) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .then(if (tutorial) Modifier.a11yHideFromReader() else Modifier)
                    ) {
                        MainShell(
                            vm = vm,
                            console = { ConsoleScreen(vm) },
                            commands = { CommandsScreen(vm) },
                            session = { com.bareflash.app.ui.QdlSessionScreen(vm) },
                            dumps = { DumpsScreen(vm) },
                            settings = { SettingsScreen(vm) },
                            switchToConsole = vm.switchToConsole,
                            samsungVm = samsung,
                            brokkr = { tab -> com.bareflash.app.ui.samsung.BrokkrTabs(samsung, tab) },
                            adbfbVm = adbfb,
                            adbfb = { tab -> com.bareflash.app.ui.adbfb.AdbFbTabs(adbfb, tab) }
                        )
                    }
                    if (tutorial) {
                        TutorialOverlay(onClose = Onboarding::closeTutorial)
                    }
                }
            }
        }
    }

    /** Home or Recents pressed: the live notification takes over from here. */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        com.bareflash.app.live.AppVisibility.userLeaving()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** Launched by plugging in a supported device: start detection. */
    private fun handleIntent(intent: Intent?) {
        val i = intent ?: return
        if (i.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return
        // Never touch USB before the disclaimer has been accepted; replay it afterwards.
        if (!Onboarding.accepted.value) {
            deferredIntent = i
            return
        }
        val dev: android.hardware.usb.UsbDevice? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                i.getParcelableExtra(UsbManager.EXTRA_DEVICE, android.hardware.usb.UsbDevice::class.java)
            else @Suppress("DEPRECATION") i.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        // An ADB or fastboot interface decides the mode whatever the vendor. This runs before the
        // vendor checks below because Samsung and MediaTek phones expose ADB in normal use too.
        if (dev != null && com.bareflash.app.adbfb.AdbFbUsb.isDebugDevice(dev)) {
            vm.setMode(EngineMode.ADB_FASTBOOT)
            adbfb.setActive(true)
            adbfb.refreshDevices()
        } else if (dev?.vendorId == com.bareflash.app.samsung.BrokkrUsbTransport.SAMSUNG_VID) {
            // A Samsung phone in download mode launches us too; hand it to the Samsung engine.
            vm.setMode(EngineMode.BROKKR)
            samsung.setActive(true)
            samsung.refreshDevices()
        } else {
            vm.detectDevice()
        }
    }
}
