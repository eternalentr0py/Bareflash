package com.bareflash.app.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import kotlin.math.roundToInt

/** Material 3 motion easings (the same curves AOSP uses). */
object NcEasing {
    val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
    val Standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val StandardDecelerate = CubicBezierEasing(0f, 0f, 0f, 1f)
    val StandardAccelerate = CubicBezierEasing(0.3f, 0f, 1f, 1f)
}

/** Animation speed from Settings: 1 = stock, 0 = off. */
val LocalMotionSpeed = staticCompositionLocalOf { 1f }

/** Standard M3 durations in ms. */
object NcDuration {
    const val Short = 150
    const val Medium = 300
    const val Long = 450
    const val Page = 380
}

/** Scales [base] milliseconds by the user's animation speed (0 -> instant). */
@Composable
fun motionMs(base: Int): Int {
    val speed = LocalMotionSpeed.current
    return if (speed <= 0f) 0 else (base / speed).roundToInt().coerceAtLeast(1)
}

@Composable
fun <T> motionTween(
    base: Int = NcDuration.Medium,
    delay: Int = 0,
    easing: androidx.compose.animation.core.Easing = NcEasing.Emphasized
): FiniteAnimationSpec<T> {
    val d = motionMs(base)
    return tween(durationMillis = d, delayMillis = if (d == 0) 0 else delay, easing = easing)
}
