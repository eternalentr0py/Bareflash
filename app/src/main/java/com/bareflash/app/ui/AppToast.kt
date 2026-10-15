package com.bareflash.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.bareflash.app.ui.theme.Nc
import com.bareflash.app.ui.theme.NcEasing
import com.bareflash.app.ui.theme.motionMs
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/** USB plug noise that belongs on the island / terminal, not in a toast. */
internal fun isUsbStatusNoise(message: String): Boolean {
    val m = message.lowercase()
    return "reconnected" in m ||
        "still on usb" in m ||
        "did not come back" in m ||
        "device disconnected" in m ||
        "waiting for it to come back" in m ||
        "unplug it and plug it back" in m
}

/**
 * Compact in-app toast. Swipe left, right, or down to dismiss.
 * Auto-hides after a few seconds. Not used for USB attach/detach.
 */
@Composable
fun AppToastHost(
    message: String?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val filtered = message?.takeUnless { isUsbStatusNoise(it) }
    var held by remember { mutableStateOf<String?>(null) }
    if (filtered != null) held = filtered
    val showing = held
    val dismiss = rememberUpdatedState(onDismiss)

    LaunchedEffect(filtered) {
        if (filtered == null) return@LaunchedEffect
        delay(4_000)
        held = null
        dismiss.value()
    }

    val enterMs = motionMs(220)
    val exitMs = motionMs(160)

    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(
            visible = showing != null,
            enter = fadeIn(tween(enterMs, easing = NcEasing.EmphasizedDecelerate)) +
                slideInVertically(tween(enterMs, easing = NcEasing.EmphasizedDecelerate)) { it / 3 },
            exit = fadeOut(tween(exitMs, easing = NcEasing.EmphasizedAccelerate)) +
                slideOutVertically(tween(exitMs, easing = NcEasing.EmphasizedAccelerate)) { it / 4 },
        ) {
            val text = showing.orEmpty()
            val density = LocalDensity.current
            val threshold = with(density) { 48.dp.toPx() }
            var ox by remember(text) { mutableFloatStateOf(0f) }
            var oy by remember(text) { mutableFloatStateOf(0f) }
            val ax by animateFloatAsState(ox, label = "toastX")
            val ay by animateFloatAsState(oy, label = "toastY")

            Box(
                Modifier
                    .padding(horizontal = 14.dp, vertical = 8.dp)
                    .offset { IntOffset(ax.roundToInt(), ay.roundToInt()) }
                    .graphicsLayer {
                        val dist = maxOf(abs(ax), ay.coerceAtLeast(0f))
                        alpha = (1f - dist / (threshold * 1.8f)).coerceIn(0.25f, 1f)
                    }
                    .pointerInput(text) {
                        detectDragGestures(
                            onDragEnd = {
                                if (abs(ox) > threshold || oy > threshold) {
                                    held = null
                                    dismiss.value()
                                } else {
                                    ox = 0f
                                    oy = 0f
                                }
                            },
                            onDrag = { change, drag ->
                                change.consume()
                                ox += drag.x
                                oy = (oy + drag.y).coerceAtLeast(0f)
                            },
                        )
                    }
                    .shadow(8.dp, RoundedCornerShape(14.dp), clip = false)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Text(
                    text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Nc.Fg,
                )
            }
        }
    }
}
