package com.bareflash.app.ui

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlinx.coroutines.delay

/**
 * Motion for the tutorial: a staggered entrance for every block on a page and
 * an animated icon medallion at the top of each page. Both stand still when
 * the system animation scale is off.
 *
 * Entrance animations only run while the page is the *current* pager page.
 * HorizontalPager keeps neighbours composed; without this gate the reveals
 * fire early and later pages look blank.
 */

@Composable
internal fun rememberTutorialReduceMotion(): Boolean {
    val ctx = LocalContext.current
    return remember(ctx) {
        Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}

/** Counts the blocks on one page so each one enters a little after the one above it. */
internal class RevealScope {
    var next = 0
    /** True only while this page is the pager's current page. */
    var active: Boolean = false
}

internal val LocalReveal = compositionLocalOf { RevealScope() }

/** Fades a block in and slides it up, delayed by its position on the page. */
@Composable
internal fun Modifier.reveal(): Modifier {
    val scope = LocalReveal.current
    val order = remember { scope.next++ }
    val reduce = rememberTutorialReduceMotion()
    val active = scope.active
    val progress = remember { Animatable(if (reduce) 1f else 0f) }
    LaunchedEffect(active, reduce) {
        if (reduce) {
            progress.snapTo(1f)
            return@LaunchedEffect
        }
        if (!active) {
            // Reset so the animation plays again when the user returns to this page.
            progress.snapTo(0f)
            return@LaunchedEffect
        }
        delay((order * 70L).coerceAtMost(420L))
        progress.animateTo(1f, tween(380, easing = FastOutSlowInEasing))
    }
    return this.graphicsLayer {
        alpha = progress.value
        translationY = (1f - progress.value) * 24.dp.toPx()
    }
}

internal enum class HeroMotion { Pulse, Bounce, Spin, Wiggle, Drift }

/** An icon in a tonal circle with a soft ring that keeps expanding behind it. */
@Composable
internal fun AnimatedHero(icon: ImageVector, motion: HeroMotion, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val reduce = rememberTutorialReduceMotion()
    val t = rememberInfiniteTransition(label = "hero")
    val ring by t.animateFloat(
        0f, 1f, infiniteRepeatable(tween(2200, easing = LinearEasing), RepeatMode.Restart), label = "heroRing"
    )
    val beat by t.animateFloat(
        0f, 1f, infiniteRepeatable(tween(2000, easing = LinearEasing), RepeatMode.Restart), label = "heroBeat"
    )
    Box(modifier.size(72.dp), contentAlignment = Alignment.Center) {
        if (!reduce) {
            Box(
                Modifier
                    .size(56.dp)
                    .graphicsLayer {
                        val s = 1f + ring * 0.45f
                        scaleX = s
                        scaleY = s
                        alpha = (1f - ring) * 0.3f
                    }
                    .clip(CircleShape)
                    .background(cs.primary)
            )
        }
        Box(
            Modifier.size(56.dp).clip(CircleShape).background(cs.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon, null, tint = cs.onPrimaryContainer,
                modifier = Modifier.size(28.dp).graphicsLayer {
                    val wave = if (reduce) 0f else sin(beat * 2f * PI.toFloat())
                    val grow = if (reduce || motion != HeroMotion.Pulse) 1f else 1f + 0.12f * wave
                    val lift = when {
                        reduce -> 0f
                        motion == HeroMotion.Bounce -> -6.dp.toPx() * abs(wave)
                        motion == HeroMotion.Drift -> 4.dp.toPx() * wave
                        else -> 0f
                    }
                    val turn = when {
                        reduce -> 0f
                        motion == HeroMotion.Spin -> beat * 360f
                        motion == HeroMotion.Wiggle -> 12f * wave
                        else -> 0f
                    }
                    scaleX = grow
                    scaleY = grow
                    translationY = lift
                    rotationZ = turn
                }
            )
        }
    }
}
