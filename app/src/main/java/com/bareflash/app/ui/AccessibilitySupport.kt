package com.bareflash.app.ui

import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.invisibleToUser
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bareflash.app.console.ConsoleViewModel
import com.bareflash.app.console.JobKind
import com.bareflash.app.console.LinkState
import com.bareflash.app.data.AccessibilitySettings
import kotlin.math.floor

val LocalAccessibility = staticCompositionLocalOf { AccessibilitySettings() }

/** Full-screen overlay: TalkBack treats it as a dialog and keeps focus inside. */
fun Modifier.a11yModal(title: String): Modifier = semantics {
    this[SemanticsProperties.IsDialog] = Unit
    paneTitle = title
}

/** Hide underlay content from the screen reader while a modal is open. */
fun Modifier.a11yHideFromReader(): Modifier = semantics { invisibleToUser() }

fun announceA11y(context: android.content.Context, text: String) {
    if (text.isBlank()) return
    val am = context.getSystemService(AccessibilityManager::class.java) ?: return
    if (!am.isEnabled) return
    @Suppress("DEPRECATION")
    val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_ANNOUNCEMENT)
    event.className = "android.widget.Toast"
    event.packageName = context.packageName
    event.text.add(text)
    am.sendAccessibilityEvent(event)
}

@Composable
fun rememberAnnounce(): (String) -> Unit {
    val ctx = LocalContext.current
    return remember(ctx) { { msg: String -> announceA11y(ctx, msg) } }
}

private fun jobVerb(kind: JobKind, running: Boolean): String = when (kind) {
    JobKind.Flash -> if (running) "Flashing" else "Flash"
    JobKind.Dump -> if (running) "Dumping" else "Dump"
    JobKind.Erase -> if (running) "Erasing" else "Erase"
    JobKind.Control -> if (running) "Running" else "Command"
}

/**
 * Speaks job and connection changes when Accessibility settings allow it and a
 * screen reader is active.
 */
@Composable
fun AccessibilityAnnouncer(vm: ConsoleViewModel) {
    val a11y = LocalAccessibility.current
    val announce = rememberAnnounce()
    val status by vm.status.collectAsStateWithLifecycle()
    val job by vm.job.collectAsStateWithLifecycle()
    val result by vm.jobResult.collectAsStateWithLifecycle()
    val device by vm.device.collectAsStateWithLifecycle()

    LaunchedEffect(job?.title, job?.target, status.state) {
        if (!a11y.announceJobs) return@LaunchedEffect
        val j = job ?: return@LaunchedEffect
        if (status.state != LinkState.Running) return@LaunchedEffect
        val target = j.target.takeIf { it.isNotBlank() }?.let { " $it" } ?: ""
        announce("${jobVerb(j.kind, running = true)}$target started")
    }

    LaunchedEffect(result?.id) {
        if (!a11y.announceJobs) return@LaunchedEffect
        val r = result ?: return@LaunchedEffect
        val label = jobVerb(r.kind, running = false)
        val target = r.target.takeIf { it.isNotBlank() }?.let { " $it" } ?: ""
        when (r.outcome) {
            ConsoleViewModel.RESULT_OK -> announce("$label$target finished successfully")
            ConsoleViewModel.RESULT_CANCELLED -> announce("$label$target cancelled")
            ConsoleViewModel.RESULT_ERROR -> announce("$label$target failed")
            else -> Unit
        }
    }

    LaunchedEffect(job?.title, job?.let { floor((it.fraction * 100).toDouble() / 25.0).toInt() }) {
        if (!a11y.announceProgress) return@LaunchedEffect
        val j = job ?: return@LaunchedEffect
        if (status.state != LinkState.Running || !j.measurable) return@LaunchedEffect
        val pct = (j.fraction * 100).toInt().coerceIn(0, 100)
        if (pct in listOf(25, 50, 75)) {
            announce("${jobVerb(j.kind, running = true)} $pct percent")
        }
    }

    LaunchedEffect(status.state, device?.vid, device?.pid) {
        if (!a11y.announceConnections) return@LaunchedEffect
        when (status.state) {
            LinkState.Connected -> {
                val id = device?.let { "VID ${it.vid.toString(16)} PID ${it.pid.toString(16)}" } ?: "Device"
                announce("$id connected")
            }
            LinkState.Disconnected -> announce("Device disconnected")
            LinkState.Error -> {
                val msg = status.message.takeIf { it.isNotBlank() } ?: "Connection error"
                announce(msg)
            }
            else -> Unit
        }
    }
}
