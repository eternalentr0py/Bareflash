package com.bareflash.app.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Usb
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.ViewModule
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bareflash.app.AppGraph
import com.bareflash.app.console.ConsoleViewModel
import com.bareflash.app.data.NavLabelMode
import com.bareflash.app.dump.DumpBridge
import com.bareflash.app.ui.theme.LocalLook
import com.bareflash.app.ui.theme.LocalNcAccent
import com.bareflash.app.ui.theme.LocalNcScreen
import com.bareflash.app.ui.theme.NcAccent
import com.bareflash.app.ui.theme.NcEasing
import com.bareflash.app.ui.theme.NcIsland
import com.bareflash.app.ui.theme.NcLive
import com.bareflash.app.ui.theme.Nc
import com.bareflash.app.ui.theme.motionMs
import kotlinx.coroutines.flow.SharedFlow
import kotlin.math.max

/** Tabs shown for each engine. QDL swaps nothing out; it adds its Session setup tab. */
fun tabsFor(mode: com.bareflash.app.engine.EngineMode): List<Tab> = when (mode) {
    com.bareflash.app.engine.EngineMode.MTK -> listOf(Tab.Console, Tab.Commands, Tab.Dumps, Tab.Settings)
    com.bareflash.app.engine.EngineMode.QDL -> listOf(Tab.Console, Tab.Commands, Tab.Session, Tab.Dumps, Tab.Settings)
    com.bareflash.app.engine.EngineMode.BROKKR -> listOf(Tab.Flash, Tab.Progress, Tab.Devices, Tab.Log, Tab.Settings)
}

enum class Tab(val label: String, val filled: ImageVector, val outlined: ImageVector) {
    Console("Console", Icons.Filled.Terminal, Icons.Outlined.Terminal),
    Commands("Commands", Icons.Filled.ViewModule, Icons.Outlined.ViewModule),
    Session("Session", Icons.Filled.Tune, Icons.Outlined.Tune),
    Dumps("Dumps", Icons.Filled.Archive, Icons.Outlined.Archive),
    Settings("Settings", Icons.Filled.Settings, Icons.Outlined.Settings),

    // Brokkr (Samsung) mode
    Flash("Flash", Icons.Filled.Bolt, Icons.Outlined.Bolt),
    Progress("Progress", Icons.Filled.Speed, Icons.Outlined.Speed),
    Devices("Devices", Icons.Filled.Usb, Icons.Outlined.Usb),
    Log("Log", Icons.Filled.Description, Icons.Outlined.Description)
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * App shell: the stock Material 3 navigation bar, Material fade-through
 * between tabs, and the status island + scrim layered on top.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MainShell(
    vm: ConsoleViewModel,
    console: @Composable () -> Unit,
    commands: @Composable () -> Unit,
    session: @Composable () -> Unit,
    dumps: @Composable () -> Unit,
    settings: @Composable () -> Unit,
    switchToConsole: SharedFlow<Unit>? = null,
    samsungVm: com.bareflash.app.samsung.SamsungViewModel? = null,
    brokkr: @Composable (Tab) -> Unit = {}
) {
    var tabState by rememberSaveable { mutableStateOf(Tab.Console) }
    var showModes by rememberSaveable { mutableStateOf(false) }
    // Bumped when the already-selected Settings tab is tapped again so SettingsScreen returns to its root.
    var settingsRootKey by rememberSaveable { mutableIntStateOf(0) }
    val controller = remember { IslandController() }
    val app by AppGraph.settings.state.collectAsStateWithLifecycle()
    val island = app.island
    val consoleRunning by vm.running.collectAsStateWithLifecycle()
    val mode by vm.mode.collectAsStateWithLifecycle()
    val samsungRun = samsungVm?.run?.collectAsStateWithLifecycle()?.value
    // One "busy" flag for both worlds: keeps the screen on and locks the mode switcher.
    val running = consoleRunning || (samsungRun?.active == true)
    val tabs = remember(mode) { tabsFor(mode) }
    // Switching engines can remove the tab you are on (Session exists only in QDL, Flash only
    // for Samsung). Resolve it during composition so no frame ever draws a tab that does not
    // belong to the new mode, then persist the corrected value.
    val tab = if (tabState in tabs) tabState else tabs.first()
    fun selectTab(t: Tab) { tabState = t }
    LaunchedEffect(mode) { if (tabState !in tabs) tabState = tabs.first() }
    // Starting a Brokkr flash jumps to its Progress tab so the run is always in view.
    val brokkrBusy = samsungRun?.active == true
    LaunchedEffect(brokkrBusy) {
        if (brokkrBusy && mode == com.bareflash.app.engine.EngineMode.BROKKR) selectTab(Tab.Progress)
    }
    val view = LocalView.current
    DisposableEffect(running) {
        val window = view.context.findActivity()?.window
        if (running) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    // Running a command from any tab (Commands, mainly) jumps to Console so
    // the command and its output are always visible, rather than continuing
    // silently behind whichever tab you happened to be on.
    if (switchToConsole != null) {
        LaunchedEffect(switchToConsole) {
            switchToConsole.collect { selectTab(Tab.Console) }
        }
    }

    // Dump command with no folder yet: system tree picker. Result resumes or
    // cancels the pending command in ConsoleViewModel.
    val dumpFolderLauncher = rememberLauncherForActivityResult(
        object : ActivityResultContracts.OpenDocumentTree() {
            override fun createIntent(context: Context, input: Uri?): Intent {
                return super.createIntent(context, input).addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
                )
            }
        }
    ) { uri -> vm.onDumpFolderPicked(uri) }
    LaunchedEffect(Unit) {
        vm.requestDumpFolder.collect {
            dumpFolderLauncher.launch(DumpBridge.currentUri())
        }
    }

    // Back stack: island collapse (IslandBackHandler, below) -> sheets/dialogs
    // -> settings subpage (SettingsScreen) -> any other tab -> Console -> system.
    BackHandler(enabled = tab != tabs.first()) { selectTab(tabs.first()) }

    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val row = islandRow(island.sizePreset)
    val islandTop = ((statusTop - row) / 2).coerceAtLeast(6.dp)
    val contentTop = if (island.enabled) max(statusTop.value, (islandTop + row).value).dp + 10.dp else statusTop + 8.dp
    val imeVisible = WindowInsets.isImeVisible
    val look = LocalLook.current
    val gutter = (16 * look.spacingScale).dp

    // Material "fade through": the outgoing tab fades out fast, the incoming
    // one fades in and settles from 92% scale. Durations follow the
    // animation-speed setting (0 = instant).
    val outMs = motionMs(90)
    val inMs = motionMs(210)
    val scaleMs = motionMs(300)

    val islandAccent = remember(NcLive.palette) {
        NcAccent("island", "Island", NcLive.palette.islandAccent, NcLive.palette.islandOnAccent)
    }

    // Short notices from console / Samsung (not USB plug events).
    var toast by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        vm.events.collect { toast = it }
    }
    LaunchedEffect(samsungVm) {
        val s = samsungVm ?: return@LaunchedEffect
        s.events.collect { toast = it }
    }

    CompositionLocalProvider(
        LocalIsland provides controller,
        LocalContentColor provides Nc.Fg,
        LocalSettingsRootKey provides settingsRootKey
    ) {
        Box(Modifier.fillMaxSize().background(LocalNcScreen.current)) {
            Column(Modifier.fillMaxSize().imePadding()) {
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .consumeWindowInsets(WindowInsets.systemBars)
                        .padding(start = gutter, end = gutter, top = contentTop, bottom = 8.dp)
                ) {
                    AnimatedContent(
                        targetState = tab,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        transitionSpec = {
                            (fadeIn(tween(inMs, delayMillis = if (inMs == 0) 0 else outMs, easing = NcEasing.EmphasizedDecelerate)) +
                                scaleIn(
                                    initialScale = 0.92f,
                                    animationSpec = tween(scaleMs, delayMillis = if (scaleMs == 0) 0 else outMs, easing = NcEasing.EmphasizedDecelerate)
                                )) togetherWith
                                fadeOut(tween(outMs, easing = NcEasing.EmphasizedAccelerate)) using
                                SizeTransform(clip = false)
                        },
                        label = "tabs"
                    ) { t ->
                        when (t) {
                            Tab.Console -> console()
                            Tab.Commands -> commands()
                            Tab.Session -> session()
                            Tab.Dumps -> dumps()
                            Tab.Settings -> settings()
                            Tab.Flash, Tab.Progress, Tab.Devices, Tab.Log -> brokkr(t)
                        }
                    }
                }
                AnimatedVisibility(
                    visible = !imeVisible,
                    enter = expandVertically(tween(motionMs(250), easing = NcEasing.EmphasizedDecelerate)) + fadeIn(tween(motionMs(200))),
                    exit = shrinkVertically(tween(motionMs(200), easing = NcEasing.EmphasizedAccelerate)) + fadeOut(tween(motionMs(120)))
                ) {
                    BottomNav(
                        selected = tab,
                        tabs = tabs,
                        mode = mode,
                        modeLocked = running,
                        onOpenModes = { showModes = true },
                        onSelect = { t ->
                            if (t == Tab.Settings && tab == Tab.Settings) {
                                settingsRootKey++
                            }
                            selectTab(t)
                        }
                    )
                }
            }

            IslandScrim(controller, island.enabled)
            if (island.enabled) {
                // The island is a black pill in both themes, so it keeps light ink and
                // the dark scheme's accent whatever the app theme is.
                CompositionLocalProvider(LocalNcAccent provides islandAccent, LocalContentColor provides NcIsland.Fg) {
                    val islandModifier = Modifier.align(Alignment.TopCenter).padding(top = islandTop)
                    if (mode == com.bareflash.app.engine.EngineMode.BROKKR) {
                        // Samsung flashes report through their own view model; the island
                        // shows them exactly like a console job.
                        if (samsungVm != null) SamsungIsland(samsungVm, controller, islandModifier)
                    } else {
                        DynamicIsland(vm, controller, islandModifier)
                    }
                }
            }
            if (showModes) {
                ModeSheet(
                    mode = mode,
                    locked = running,
                    onSelect = vm::setMode,
                    onDismiss = { showModes = false }
                )
            }
            IslandBackHandler(controller, island.enabled)
            AppToastHost(
                message = toast,
                onDismiss = { toast = null },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 72.dp)
            )
        }
    }
}

/** The stock Material 3 navigation bar: mode slot first, then the tabs; pill indicator and filled icon when selected. */
@Composable
private fun BottomNav(
    selected: Tab,
    tabs: List<Tab>,
    mode: com.bareflash.app.engine.EngineMode,
    modeLocked: Boolean,
    onOpenModes: () -> Unit,
    onSelect: (Tab) -> Unit
) {
    val labels = LocalLook.current.navLabels
    NavigationBar(containerColor = Nc.Raised) {
        // Mode switch: first slot, opens the mode sheet.
        ModeNavItem(mode, modeLocked, onOpenModes)
        tabs.forEach { t ->
            val on = selected == t
            NavigationBarItem(
                selected = on,
                // Always invoke onSelect so re-tapping the selected tab can pop nested UI (Settings).
                onClick = { onSelect(t) },
                icon = { Icon(if (on) t.filled else t.outlined, contentDescription = t.label) },
                label = if (labels == NavLabelMode.NEVER) null else ({ Text(t.label, maxLines = 1) }),
                alwaysShowLabel = labels == NavLabelMode.ALWAYS
            )
        }
    }
}
