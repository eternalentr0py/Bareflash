package com.bareflash.app.ui

import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bareflash.app.AppGraph
import com.bareflash.app.console.ConsoleViewModel
import com.bareflash.app.console.DeviceInfo
import com.bareflash.app.console.JobKind
import com.bareflash.app.console.JobProgress
import com.bareflash.app.console.JobResult
import com.bareflash.app.console.LinkMode
import com.bareflash.app.console.LinkState
import com.bareflash.app.console.ConsoleStatus
import com.bareflash.app.data.IslandMaterial
import com.bareflash.app.data.IslandMotion
import com.bareflash.app.data.IslandSettings
import com.bareflash.app.data.IslandSize
import com.bareflash.app.data.ProgressStyle
import com.bareflash.app.samsung.RunPhase
import com.bareflash.app.samsung.SamsungLink
import com.bareflash.app.samsung.SamsungViewModel
import com.bareflash.app.ui.theme.IslandDisplay
import com.bareflash.app.ui.theme.LocalNcAccent
import com.bareflash.app.ui.theme.Nc
import com.bareflash.app.ui.theme.NcIsland
import com.bareflash.app.ui.theme.PlexMono
import kotlinx.coroutines.delay
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

// ===========================================================================
// Controller: expanded flag + Appearance preview state (store.ts:
// islandExpanded / islandPreview). One instance lives in MainShell.
// ===========================================================================

enum class IslandPreview(val label: String) {
    Live("Live"), Idle("Idle"), Preloader("Preloader"), Brom("BROM"), Flash("Flash"), Dump("Dump")
}

@Stable
class IslandController {
    var expanded by mutableStateOf(false)
    var preview by mutableStateOf(IslandPreview.Live)
        private set

    fun select(p: IslandPreview) {
        preview = p
        expanded = p == IslandPreview.Brom || p == IslandPreview.Flash ||
            p == IslandPreview.Dump || p == IslandPreview.Preloader
    }

    fun reset() {
        preview = IslandPreview.Live
        expanded = false
    }
}

val LocalIsland = compositionLocalOf { IslandController() }

// ===========================================================================
// Metrics (dynamic-island.tsx SIZE / islandMetrics)
// ===========================================================================

private data class SizeSpec(val row: Int, val minW: Int, val compactW: Int, val largeW: Int, val radiusBoost: Int)

private fun sizeSpec(p: IslandSize) = when (p) {
    IslandSize.COMPACT -> SizeSpec(32, 108, 176, 308, -4)
    IslandSize.REGULAR -> SizeSpec(36, 126, 204, 342, 0)
    IslandSize.PROMINENT -> SizeSpec(40, 148, 228, 358, 4)
}

/** Height of the top (camera) row; MainShell uses it to clear content. */
fun islandRow(p: IslandSize): Dp = sizeSpec(p).row.dp

private enum class Presentation { Minimal, Compact, Large, XLarge }
private enum class Led { Idle, Listening, Busy, Ok, Err }
private enum class JobStatus { Running, Success, Error }

private data class Metrics(val width: Dp, val height: Dp, val radius: Dp, val row: Dp)

private fun metrics(s: IslandSettings, p: Presentation): Metrics {
    val z = sizeSpec(s.sizePreset)
    val expanded = p == Presentation.Large || p == Presentation.XLarge
    val width = when (p) {
        Presentation.Minimal -> z.minW
        Presentation.Compact -> z.compactW
        else -> z.largeW
    }
    val extra = when (p) {
        Presentation.XLarge -> 158
        Presentation.Large -> 122
        else -> 0
    }
    val radius = if (expanded) s.cornerRadius + z.radiusBoost else z.row / 2
    return Metrics(width.dp, (z.row + extra).dp, radius.dp, z.row.dp)
}

// ===========================================================================
// View model (previewModel / liveModel)
// ===========================================================================

private data class IslandDevice(
    val model: String,
    val hw: String,
    val modeLabel: String,
    val fwLabel: String,
    val fwValue: String,
    /** Heading shown while connected and idle; null derives "HW <code>" (MediaTek). */
    val connectedTitle: String? = null,
    /** Label for [hw] in the expanded grid. */
    val hwLabel: String = "HW"
)

/**
 * Everything the island draws, independent of which engine produced it. The
 * MediaTek / Qualcomm console and the Samsung flasher each build one of these,
 * so the island itself stays engine-agnostic.
 */
private data class IslandFeed(
    val status: ConsoleStatus,
    val device: IslandDevice?,
    val job: JobProgress?,
    val result: JobResult?,
    /** Title when a device is connected but nothing is running, e.g. "Qualcomm device". */
    val idleLabel: String,
    /** Line shown in the expanded island when nothing is connected. */
    val idleHint: String,
    /** Called once the finished-run card has been shown. */
    val clearResult: () -> Unit
)

private data class IslandJob(
    val name: String,
    val kindLabel: String,
    val target: String,
    val stage: String,
    val progress: Float,
    val measurable: Boolean,
    val bytesDone: Long,
    val bytesTotal: Long,
    val speedBps: Double,
    val etaMs: Long,
    val status: JobStatus
)

private data class IslandView(
    val presentation: Presentation,
    val led: Led,
    val left: String,
    val right: String,
    val title: String,
    val subtitle: String,
    val badge: String,
    val device: IslandDevice?,
    val job: IslandJob?
)

private const val GB = 1024L * 1024L * 1024L

private val previewPreloader = IslandDevice("MT6789", "0x1209", "Preloader", "Preloader", "PL-2024.08.12")
private val previewBrom = IslandDevice("MT6789", "0x1209", "BROM", "BROM", "0x0A018000")

/** Heading of the expanded job card. While it runs it says what is happening ("FLASHING"), not the tab name. */
private fun kindLabel(kind: JobKind, name: String, running: Boolean = true) = when (kind) {
    JobKind.Flash -> if (running) "FLASHING" else "FLASH"
    JobKind.Dump -> if (running) "DUMPING" else "DUMP"
    else -> name
}

/** Pill text before the first byte has moved; afterwards the pill shows the percentage. */
private fun pillVerb(kind: JobKind) = when (kind) {
    JobKind.Flash -> "FLASHING"
    JobKind.Dump -> "DUMPING"
    JobKind.Erase -> "ERASING"
    JobKind.Control -> "RUNNING"
}

/** True once the engine has reported real progress, so the pill can switch from the verb to the percentage. */
private fun hasProgress(v: IslandJob) = v.measurable && v.bytesDone > 0L

private fun previewView(p: IslandPreview, expanded: Boolean): IslandView {
    fun job(name: String, kind: JobKind, target: String, stage: String, frac: Float, total: Long, mbps: Double, eta: Long) =
        IslandJob(
            name, kindLabel(kind, name), target, stage, frac, true,
            (frac * total).toLong(), total, mbps * 1024 * 1024, eta, JobStatus.Running
        )
    return when (p) {
        IslandPreview.Idle -> IslandView(
            if (expanded) Presentation.Large else Presentation.Minimal, Led.Idle, "", "",
            "Idle", "No host on the line", "", null, null
        )
        IslandPreview.Preloader -> IslandView(
            if (expanded) Presentation.Large else Presentation.Compact, Led.Ok, "PL", previewPreloader.model,
            "Dimensity 1080", "MTK PreLoader USB VCOM", "PL", previewPreloader, null
        )
        IslandPreview.Brom -> IslandView(
            if (expanded) Presentation.Large else Presentation.Compact, Led.Ok, "BROM", previewBrom.hw,
            "Boot ROM", previewBrom.fwValue, "BROM", previewBrom, null
        )
        IslandPreview.Flash -> {
            val j = job("Flash scatter", JobKind.Flash, "super", "Writing sparse super", 0.42f, (3.5 * GB).toLong(), 18.4, 78_000)
            IslandView(
                if (expanded) Presentation.XLarge else Presentation.Compact, Led.Busy,
                "${(j.progress * 100).roundToInt()}%", formatEta(j.etaMs), j.name, j.stage, "PL", previewPreloader, j
            )
        }
        IslandPreview.Dump -> {
            val j = job("Dump full flash", JobKind.Dump, "emmc", "Dumping user area", 0.61f, 8 * GB, 22.1, 142_000)
            IslandView(
                if (expanded) Presentation.XLarge else Presentation.Compact, Led.Busy,
                "${(j.progress * 100).roundToInt()}%", formatEta(j.etaMs), j.name, j.stage, "PL", previewPreloader, j
            )
        }
        IslandPreview.Live -> error("Live is built by liveView")
    }
}

private fun deviceOf(info: DeviceInfo?, status: ConsoleStatus): IslandDevice? {
    if (info == null) return null
    val (modeLabel, fwLabel) = when (status.mode) {
        LinkMode.BootRom -> "BROM" to "BROM"
        LinkMode.Da -> "DA" to "DA"
        LinkMode.Preloader -> "Preloader" to "Preloader"
        LinkMode.Unknown -> "USB" to "Link"
    }
    return IslandDevice(
        model = "%04X:%04X".format(info.vid, info.pid),
        hw = info.hwCode ?: "—",
        modeLabel = modeLabel,
        fwLabel = fwLabel,
        fwValue = info.targetConfig?.let { "cfg $it" } ?: "—"
    )
}

private fun badgeOf(d: IslandDevice?): String = when (d?.modeLabel) {
    "Preloader" -> "PL"
    null -> ""
    else -> d.modeLabel.uppercase()
}

private fun liveView(
    expanded: Boolean,
    status: ConsoleStatus,
    dev: IslandDevice?,
    job: JobProgress?,
    result: JobResult?,
    idleLabel: String
): IslandView {
    fun toJob(j: JobProgress, st: JobStatus, frac: Float = j.fraction) = IslandJob(
        j.title, kindLabel(j.kind, j.title, running = st == JobStatus.Running), j.target, j.stage.ifBlank { j.title }, frac, j.measurable,
        j.bytesDone, j.bytesTotal, j.speedBps, j.etaMs, st
    )
    if (job != null && status.state == LinkState.Running) {
        val v = toJob(job, JobStatus.Running)
        return IslandView(
            if (expanded) Presentation.XLarge else Presentation.Compact, Led.Busy,
            if (hasProgress(v)) "${(v.progress * 100).roundToInt()}%" else pillVerb(job.kind),
            formatEta(v.etaMs), v.name, v.stage, badgeOf(dev), dev, v
        )
    }
    if (result != null) {
        val ok = result.outcome == ConsoleViewModel.RESULT_OK
        val stub = JobProgress(result.title, result.kind, result.target)
        val v = toJob(stub, if (ok) JobStatus.Success else JobStatus.Error, result.fraction)
        val cancelled = result.outcome == ConsoleViewModel.RESULT_CANCELLED
        return IslandView(
            if (expanded) Presentation.Large else Presentation.Compact,
            if (ok) Led.Ok else Led.Err, if (ok) "OK" else "ERR", result.title, result.title,
            if (ok) "Complete" else if (cancelled) "Stopped" else "Failed", badgeOf(dev), dev, v
        )
    }
    return when (status.state) {
        LinkState.Detecting, LinkState.Reconnecting -> IslandView(
            if (expanded) Presentation.Large else Presentation.Compact, Led.Listening,
            if (status.state == LinkState.Reconnecting) "WAIT" else "SCAN", "USB",
            if (status.state == LinkState.Reconnecting) "Reconnecting" else "Listening",
            if (status.state == LinkState.Reconnecting) "Waiting for the device to come back" else "Waiting for handshake",
            "", null, null
        )
        LinkState.Error -> IslandView(
            if (expanded) Presentation.Large else Presentation.Compact, Led.Err, "ERR",
            status.message.ifBlank { "Error" }, status.message.ifBlank { "Error" }, "Check the console log", badgeOf(dev), dev, null
        )
        LinkState.Connected, LinkState.Running -> {
            val d = dev
            IslandView(
                if (expanded) Presentation.Large else Presentation.Compact, Led.Ok,
                badgeOf(d).ifEmpty { "USB" }, d?.hw?.takeIf { it != "—" } ?: (d?.model ?: ""),
                d?.connectedTitle ?: d?.let { x -> x.hw.takeIf { it != "—" }?.let { code -> "${x.hwLabel} $code" } } ?: idleLabel,
                d?.let { if (it.modeLabel == "BROM") "BROM ${it.fwValue}" else it.model } ?: "",
                badgeOf(d), d, null
            )
        }
        LinkState.Disconnected -> IslandView(
            if (expanded) Presentation.Large else Presentation.Minimal, Led.Idle, "", "",
            "Idle", "Bareflash", "", null, null
        )
    }
}

// ===========================================================================
// Formatting (lib/utils)
// ===========================================================================

internal fun formatBytes(n: Long): String {
    if (n < 1024) return "$n B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var v = n / 1024.0
    var i = 0
    while (v >= 1024 && i < units.size - 1) { v /= 1024.0; i++ }
    return String.format(java.util.Locale.US, "%.1f %s", v, units[i])
}

internal fun formatSpeed(bps: Double): String {
    val mb = bps / (1024.0 * 1024.0)
    return if (mb >= 1.0) String.format(java.util.Locale.US, "%.1f MB/s", mb)
    else String.format(java.util.Locale.US, "%.0f KB/s", bps / 1024.0)
}

internal fun formatEta(ms: Long): String {
    if (ms < 0) return "--:--"
    val total = (ms / 1000.0).roundToInt()
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format(java.util.Locale.US, "%d:%02d:%02d", h, m, s)
    else String.format(java.util.Locale.US, "%d:%02d", m, s)
}

// ===========================================================================
// Motion (styles.css .di[data-motion])
// ===========================================================================

private class MotionSpec(val expandMs: Int, val collapseMs: Int, val expandEase: Easing, val heightFactor: Float)

private val CollapseEase = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)

private fun motionSpec(m: IslandMotion, overshoot: Boolean) = when (m) {
    IslandMotion.APPLE -> MotionSpec(
        560, 400,
        if (overshoot) CubicBezierEasing(0.32f, 0.72f, 0f, 1.18f) else CubicBezierEasing(0.32f, 0.72f, 0f, 1f),
        0.9f
    )
    IslandMotion.SNAPPY -> MotionSpec(320, 220, CubicBezierEasing(0.2f, 0f, 0f, 1f), 1f)
    IslandMotion.SOFT -> MotionSpec(780, 550, NcSmoothOut, 1f)
}

@Composable
private fun rememberReduceMotion(): Boolean {
    val ctx = LocalContext.current
    return remember(ctx) {
        Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}

// ===========================================================================
// The island
// ===========================================================================

/** The island for the MediaTek and Qualcomm engines (the console view model). */
@Composable
fun DynamicIsland(vm: ConsoleViewModel, controller: IslandController, modifier: Modifier = Modifier) {
    val status by vm.status.collectAsStateWithLifecycle()
    val device by vm.device.collectAsStateWithLifecycle()
    val job by vm.job.collectAsStateWithLifecycle()
    val result by vm.jobResult.collectAsStateWithLifecycle()
    val mode by vm.mode.collectAsStateWithLifecycle()
    val feed = IslandFeed(
        status = status,
        device = deviceOf(device, status),
        job = job,
        result = result,
        idleLabel = "${mode.label} device",
        idleHint = "Line idle. Connect a ${mode.label} device to wake the island.",
        clearResult = vm::clearJobResult
    )
    IslandHost(feed, controller, modifier)
}

/**
 * The island for the Samsung engine. A flash run is mapped onto the same job
 * model the console engines use: overall bytes, speed, ETA and the partition
 * being written, so the island looks and behaves exactly like it does for a
 * MediaTek flash (auto-expand on start, compact progress pill, result card).
 */
@Composable
fun SamsungIsland(vm: SamsungViewModel, controller: IslandController, modifier: Modifier = Modifier) {
    val run by vm.run.collectAsStateWithLifecycle()
    val devices by vm.devices.collectAsStateWithLifecycle()
    val link by vm.link.collectAsStateWithLifecycle()
    val wl by vm.wireless.state.collectAsStateWithLifecycle()
    // The finished-run card is shown on the island once. The run itself stays on the
    // Progress tab until it is dismissed there, so the island never clears it.
    var shownRun by remember { mutableStateOf(0L) }

    val ready = devices.firstOrNull { it.ready }
    val peer = wl.peer
    val device: IslandDevice? = when {
        link == SamsungLink.USB && ready != null -> IslandDevice(
            model = ready.product, hw = ready.id, modeLabel = "Odin",
            fwLabel = "Link", fwValue = "USB",
            connectedTitle = "Samsung device", hwLabel = "Port"
        )
        link == SamsungLink.WIRELESS && peer != null -> IslandDevice(
            model = peer, hw = "—", modeLabel = "Odin",
            fwLabel = "Link", fwValue = "Wireless",
            connectedTitle = "Samsung watch"
        )
        else -> null
    }

    val target = run.model.ifBlank { run.deviceIds.firstOrNull().orEmpty() }
    val job = if (run.active) JobProgress(
        title = "Samsung flash",
        kind = JobKind.Flash,
        target = target,
        stage = run.activeItem?.partition?.takeIf { it.isNotBlank() } ?: run.stage,
        bytesDone = run.overallDone,
        bytesTotal = run.overallTotal,
        speedBps = run.speedBps,
        etaMs = run.etaMs
    ) else null
    val finished = run.phase == RunPhase.Success || run.phase == RunPhase.Failed || run.phase == RunPhase.Cancelled
    val result = if (finished && run.startedAt != shownRun) JobResult(
        id = run.startedAt,
        title = "Samsung flash",
        kind = JobKind.Flash,
        target = target,
        outcome = when (run.phase) {
            RunPhase.Success -> ConsoleViewModel.RESULT_OK
            RunPhase.Cancelled -> ConsoleViewModel.RESULT_CANCELLED
            else -> ConsoleViewModel.RESULT_ERROR
        },
        fraction = run.fraction
    ) else null

    val feed = IslandFeed(
        status = ConsoleStatus(
            state = when {
                run.active -> LinkState.Running
                device != null -> LinkState.Connected
                else -> LinkState.Disconnected
            }
        ),
        device = device,
        job = job,
        result = result,
        idleLabel = "Samsung device",
        idleHint = "Line idle. Put a Samsung device in download mode to wake the island.",
        clearResult = { shownRun = run.startedAt }
    )
    IslandHost(feed, controller, modifier)
}

@Composable
private fun IslandHost(feed: IslandFeed, controller: IslandController, modifier: Modifier = Modifier) {
    val island by AppGraph.settings.state.collectAsStateWithLifecycle()
    val cfg = island.island
    val status = feed.status
    val job = feed.job
    val result = feed.result
    val cfgNow by rememberUpdatedState(cfg)
    val clearResult by rememberUpdatedState(feed.clearResult)

    // ---- behaviours: auto expand / auto collapse (store.ts detect/startCommand/finishJob)
    val running = job != null && status.state == LinkState.Running
    var prevState by remember { mutableStateOf(status.state) }
    LaunchedEffect(status.state) {
        val from = prevState
        prevState = status.state
        if (controller.preview != IslandPreview.Live) return@LaunchedEffect
        val entering = (status.state == LinkState.Detecting && from == LinkState.Disconnected) ||
            (status.state == LinkState.Reconnecting) ||
            (status.state == LinkState.Connected &&
                (from == LinkState.Disconnected || from == LinkState.Detecting ||
                    from == LinkState.Reconnecting || from == LinkState.Error))
        if (!entering || !cfgNow.autoExpandOnConnect) return@LaunchedEffect
        controller.expanded = true
        val sec = cfgNow.autoCollapseSec
        if (sec > 0f) {
            delay((sec * 1000).toLong())
            if (!running) controller.expanded = false
        }
    }
    LaunchedEffect(running) {
        if (!running || controller.preview != IslandPreview.Live) return@LaunchedEffect
        if (!cfgNow.autoExpandOnJob) return@LaunchedEffect
        controller.expanded = true
        if (cfgNow.compactLiveActivity && cfgNow.autoCollapseSec > 0f) {
            delay(max(1200L, (cfgNow.autoCollapseSec * 1000).toLong()))
            controller.expanded = false
        }
    }
    LaunchedEffect(result?.id) {
        if (result == null || controller.preview != IslandPreview.Live) return@LaunchedEffect
        controller.expanded = true
        delay(1600)
        clearResult()
        controller.expanded = false
    }

    if (!cfg.enabled) return

    val view = if (controller.preview == IslandPreview.Live) {
        liveView(controller.expanded, status, feed.device, job, result, feed.idleLabel)
    } else previewView(controller.preview, controller.expanded)

    val m = metrics(cfg, view.presentation)
    val open = view.presentation == Presentation.Large || view.presentation == Presentation.XLarge
    val insetOpen = max(24, (m.radius.value * 0.58f).roundToInt()).dp

    // ---- size / radius animation
    val spec = motionSpec(cfg.motion, cfg.overshoot)
    val speed = cfg.speed.coerceIn(0.55f, 1.6f)
    val reduce = rememberReduceMotion()
    val ms = if (reduce) 0 else ((if (open) spec.expandMs else spec.collapseMs) / speed).roundToInt()
    val heightMs = if (open) (ms * spec.heightFactor).roundToInt() else ms
    val ease = if (open) spec.expandEase else CollapseEase
    val w by animateDpAsState(m.width, tween(ms, easing = ease), label = "islandW")
    val h by animateDpAsState(m.height, tween(heightMs, easing = ease), label = "islandH")
    val r by animateDpAsState(m.radius, tween(ms, easing = ease), label = "islandR")
    val shape = RoundedCornerShape(r.coerceAtLeast(0.dp))

    val bodyMs = if (reduce) 0 else 280
    val bodyAlpha by animateFloatAsState(
        if (open) 1f else 0f,
        tween(if (reduce) 0 else if (open) bodyMs else 120, delayMillis = if (open && !reduce) 90 else 0, easing = NcSmoothOut),
        label = "islandBody"
    )
    val bodyDy by animateDpAsState(
        if (open) 0.dp else (-4).dp,
        tween(if (reduce) 0 else if (open) bodyMs else 120, delayMillis = if (open && !reduce) 90 else 0, easing = NcSmoothOut),
        label = "islandBodyDy"
    )

    val fill = when (cfg.material) {
        IslandMaterial.OLED -> Nc.Island
        IslandMaterial.SOLID -> Color(0xFF111116)
        IslandMaterial.FROSTED -> Color(8, 8, 12).copy(alpha = 0.88f)
    }
    val ring = if (cfg.shadow) Color(0x14FFFFFF) else Color(0x0FFFFFFF)
    val swipePx = with(androidx.compose.ui.platform.LocalDensity.current) { 24.dp.toPx() }
    val desc = if (open) "Collapse status island" else "Expand status island"

    Box(
        modifier
            .alpha(cfg.opacity)
            .then(if (cfg.shadow) Modifier.shadow(18.dp, shape, clip = false, ambientColor = Color.Black, spotColor = Color.Black) else Modifier)
            .width(w)
            .height(h)
            .clip(shape)
            .background(fill)
            .border(1.dp, ring, shape)
            .semantics { contentDescription = desc }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Button) {
                if (cfg.enabled && cfg.tapToExpand) controller.expanded = !controller.expanded
            }
            .pointerInput(open, swipePx) {
                var total = 0f
                detectVerticalDragGestures(
                    onDragStart = { total = 0f },
                    onVerticalDrag = { _, d -> total += d },
                    onDragEnd = {
                        if (open && total < -swipePx) controller.expanded = false
                        else if (!open && total > swipePx) controller.expanded = true
                    }
                )
            }
    ) {
        Column(Modifier.fillMaxWidth()) {
            CompactRow(view, cfg, open, m.row, insetOpen)
            Column(
                Modifier
                    .fillMaxWidth()
                    .wrapContentHeight(Alignment.Top, unbounded = true)
                    .graphicsLayer { alpha = bodyAlpha; translationY = bodyDy.toPx() }
                    .padding(start = insetOpen, end = insetOpen, bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Body(view, cfg, feed.idleHint)
            }
        }
    }
}

// ---- top row -----------------------------------------------------------------

@Composable
private fun CompactRow(view: IslandView, cfg: IslandSettings, open: Boolean, row: Dp, insetOpen: Dp) {
    val minimal = view.presentation == Presentation.Minimal
    val sideStyle = TextStyle(
        fontFamily = IslandDisplay, fontWeight = FontWeight.SemiBold, fontSize = 11.sp,
        letterSpacing = 0.44.sp, fontFeatureSettings = "tnum"
    )
    Box(Modifier.fillMaxWidth().height(row)) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = if (open) insetOpen else 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (!open) {
                // left
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (cfg.showLed && !minimal) StatusLed(view.led, cfg.pulseLed)
                    val job = view.job
                    if (!minimal && cfg.progressStyle != ProgressStyle.BAR && job != null &&
                        job.status == JobStatus.Running && job.measurable
                    ) ProgressRing(job.progress, 16.dp)
                    if (!minimal) Text(view.left, style = sideStyle, color = NcIsland.Fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                // right
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End) {
                    if (!minimal) Text(view.right, style = sideStyle, color = NcIsland.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (cfg.showLenses) {
            Row(
                Modifier.align(Alignment.Center),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Lens(58.dp, 12.dp)
                Lens(12.dp, 12.dp)
            }
        }
    }
}

@Composable
private fun Lens(w: Dp, h: Dp) {
    Canvas(Modifier.size(w, h)) {
        val corner = CornerRadius(size.height / 2)
        val base = Brush.radialGradient(
            0f to Color(0xFF0B0B10), 0.7f to Color(0xFF030305), 1f to Color(0xFF030305),
            center = Offset(size.width / 2, size.height / 2),
            radius = hypot(size.width / 2, size.height / 2)
        )
        drawRoundRect(base, cornerRadius = corner)
        val hl = Brush.radialGradient(
            0f to Color(0x73505A6E), 0.42f to Color.Transparent, 1f to Color.Transparent,
            center = Offset(size.width * 0.35f, size.height * 0.30f),
            radius = hypot(size.width * 0.65f, size.height * 0.70f)
        )
        drawRoundRect(hl, cornerRadius = corner)
        val s = 1.dp.toPx()
        drawRoundRect(
            Color(0x14FFFFFF), topLeft = Offset(s / 2, s / 2), size = Size(size.width - s, size.height - s),
            cornerRadius = CornerRadius((size.height - s) / 2), style = Stroke(s)
        )
    }
}

@Composable
private fun StatusLed(state: Led, pulseIdle: Boolean, modifier: Modifier = Modifier) {
    val accent = LocalNcAccent.current
    val reduce = rememberReduceMotion()
    val color = when (state) {
        Led.Listening -> NcIsland.Warn
        Led.Ok -> NcIsland.Ok
        Led.Err -> NcIsland.Err
        else -> accent.color
    }
    val t = rememberInfiniteTransition(label = "led")
    val pulse by t.animateFloat(
        0.35f, 1f,
        infiniteRepeatable(tween(900, easing = androidx.compose.animation.core.FastOutSlowInEasing), RepeatMode.Reverse),
        label = "ledPulse"
    )
    val busy by t.animateFloat(
        0.7f, 1f, infiniteRepeatable(tween(450, easing = LinearEasing), RepeatMode.Reverse), label = "ledBusy"
    )
    val a = when {
        reduce -> if (state == Led.Idle) 0.45f else 1f
        state == Led.Idle -> if (pulseIdle) pulse else 0.45f
        state == Led.Busy -> busy
        else -> 1f
    }
    // Opacity pulse only (a scale pulse overflows the pill); glow stays <= 4dp.
    Canvas(modifier.size(7.dp).alpha(a)) {
        drawCircle(color.copy(alpha = 0.35f), radius = size.minDimension / 2 + 2.dp.toPx())
        drawCircle(color)
    }
}

@Composable
private fun ProgressRing(value: Float, size: Dp) {
    val accent = LocalNcAccent.current
    Canvas(Modifier.size(size)) {
        val stroke = 2.5.dp.toPx()
        val d = this.size.minDimension - stroke
        val tl = Offset(stroke / 2, stroke / 2)
        drawArc(Color(0x1FFFFFFF), 0f, 360f, false, tl, Size(d, d), style = Stroke(stroke))
        drawArc(
            accent.color, -90f, 360f * value.coerceIn(0f, 1f), false, tl, Size(d, d),
            style = Stroke(stroke, cap = StrokeCap.Round)
        )
    }
}

// ---- expanded body -------------------------------------------------------------

@Composable
private fun Body(view: IslandView, cfg: IslandSettings, idleHint: String) {
    val accent = LocalNcAccent.current
    val device = view.device
    val job = view.job
    val mono10 = TextStyle(fontFamily = PlexMono, fontSize = 10.sp, fontFeatureSettings = "tnum")

    if (cfg.showDevice || cfg.showMode || cfg.showLed) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                if (cfg.showLed) StatusLed(view.led, cfg.pulseLed, Modifier.padding(top = 6.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        view.title,
                        style = TextStyle(fontFamily = IslandDisplay, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, letterSpacing = 0.35.sp),
                        color = NcIsland.Fg, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        view.subtitle,
                        fontSize = 12.sp, color = NcIsland.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
            if (device != null && cfg.showMode && view.badge.isNotEmpty()) {
                Text(
                    view.badge.uppercase(),
                    style = TextStyle(fontFamily = IslandDisplay, fontWeight = FontWeight.SemiBold, fontSize = 10.sp, letterSpacing = 1.sp),
                    color = accent.color, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .widthIn(max = 112.dp)
                        .clip(CircleShape)
                        .background(accent.dim)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
    }

    if (device != null && job == null && (cfg.showHw || cfg.showDevice)) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (cfg.showDevice) GridRow("Device", device.model, mono10)
            if (cfg.showHw) {
                GridRow(device.hwLabel, device.hw, mono10)
                GridRow(device.fwLabel, device.fwValue, mono10)
            }
        }
    }

    if (job != null && cfg.showCommand) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    job.kindLabel,
                    style = TextStyle(fontFamily = IslandDisplay, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, letterSpacing = 0.3.sp),
                    color = NcIsland.Fg, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false)
                )
                if (cfg.showTarget && job.target.isNotEmpty()) {
                    Text(job.target, style = mono10, color = NcIsland.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 8.dp))
                }
            }
            if (cfg.showProgressBar && job.status == JobStatus.Running) ProgressBar(job)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                val pct = if (job.measurable) "${(job.progress * 100).roundToInt()}%" else "…"
                val left = buildString {
                    if (cfg.showPercent) append(pct)
                    if (cfg.showPercent && cfg.showSpeed) append(" · ")
                    if (cfg.showSpeed && job.status == JobStatus.Running && job.speedBps > 0) append(formatSpeed(job.speedBps))
                }
                Text(left, style = mono10, color = NcIsland.Muted, maxLines = 1)
                Text(
                    if (cfg.showEta && job.status == JobStatus.Running) "ETA ${formatEta(job.etaMs)}"
                    else when (job.status) { JobStatus.Running -> "running"; JobStatus.Success -> "success"; JobStatus.Error -> "error" },
                    style = mono10, color = NcIsland.Muted, maxLines = 1
                )
            }
            if (cfg.showStage || cfg.showTarget) {
                val line = buildString {
                    if (cfg.showStage) append(job.stage)
                    if (cfg.showStage && cfg.showTarget) append(" · ")
                    if (job.status == JobStatus.Running && job.measurable) append("${formatBytes(job.bytesDone)} / ${formatBytes(job.bytesTotal)}")
                }
                Text(line, style = mono10, color = NcIsland.Subtle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }

    if (view.led == Led.Idle && device == null && job == null) {
        Text(idleHint, fontSize = 12.sp, color = NcIsland.Muted)
    }
}

@Composable
private fun GridRow(label: String, value: String, style: TextStyle) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = style, color = NcIsland.Muted, maxLines = 1)
        Spacer(Modifier.width(12.dp))
        Text(
            value, style = style, color = NcIsland.Fg, maxLines = 1, overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End, modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun ProgressBar(job: IslandJob) {
    val accent = LocalNcAccent.current
    val sweep = rememberInfiniteTransition(label = "sweep")
    val x by sweep.animateFloat(-0.3f, 1f, infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "sweepX")
    val frac by animateFloatAsState(job.progress, tween(180, easing = LinearEasing), label = "barFill")
    Canvas(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape)) {
        drawRect(Color(0x14FFFFFF))
        if (job.measurable) {
            drawRect(accent.brush, size = Size(size.width * frac, size.height))
        } else {
            drawRect(accent.brush, Offset(size.width * x, 0f), Size(size.width * 0.3f, size.height))
        }
    }
}

// ===========================================================================
// Scrim + back handling
// ===========================================================================

@Composable
fun IslandScrim(controller: IslandController, enabled: Boolean, modifier: Modifier = Modifier) {
    val on = enabled && controller.expanded
    val a by animateFloatAsState(if (on) 0.45f else 0f, tween(220, easing = NcSmoothOut), label = "scrim")
    if (a > 0.001f || on) {
        Box(
            modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = a))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                    controller.expanded = false
                }
        )
    }
}

/** Highest-priority back step: an open island collapses before anything else. */
@Composable
fun IslandBackHandler(controller: IslandController, enabled: Boolean) {
    BackHandler(enabled = enabled && controller.expanded) { controller.expanded = false }
}
