package com.bareflash.app.console

import com.bareflash.app.log.AppLog
import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Short haptic / notification-tone when a command finishes. Used so the
 * user notices completion with the screen off (long dumps) without looking
 * at the notification. Sound is opt-in; vibration is on by default.
 *
 * Outcomes differ so success, failure and cancel are distinguishable by feel
 * and pitch. Plays on STREAM_NOTIFICATION so system volume / DND still apply.
 */
class CommandCompletionCue(private val ctx: Context) {

    enum class Outcome { Success, Error, Cancelled }

    fun play(outcome: Outcome, vibrate: Boolean, sound: Boolean) {
        if (vibrate) runCatching { vibrate(outcome) }
        if (sound) runCatching { tone(outcome) }
    }

    private fun vibrate(outcome: Outcome) {
        val vib = vibrator() ?: return
        if (!vib.hasVibrator()) return
        val effect = when (outcome) {
            Outcome.Success -> VibrationEffect.createWaveform(longArrayOf(0, 40, 60, 40), -1)
            Outcome.Error -> VibrationEffect.createWaveform(longArrayOf(0, 80, 50, 80, 50, 80), -1)
            Outcome.Cancelled -> VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE)
        }
        vib.vibrate(effect)
    }

    private fun tone(outcome: Outcome) {
        val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        if (audio.ringerMode == AudioManager.RINGER_MODE_SILENT) return
        val tone = when (outcome) {
            Outcome.Success -> ToneGenerator.TONE_PROP_ACK
            Outcome.Error -> ToneGenerator.TONE_PROP_NACK
            Outcome.Cancelled -> ToneGenerator.TONE_PROP_BEEP
        }
        val durationMs = when (outcome) {
            Outcome.Error -> 180
            else -> 120
        }
        val gen = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
        try {
            gen.startTone(tone, durationMs)
            // Keep the generator alive for the tone; releasing immediately cuts it off.
            android.os.Handler(ctx.mainLooper).postDelayed({ runCatching { gen.release() } }, durationMs + 50L)
        } catch (t: Throwable) {
            AppLog.w("cue", "Completion tone failed", t)
            runCatching { gen.release() }
        }
    }

    private fun vibrator(): Vibrator? {
        return if (Build.VERSION.SDK_INT >= 31) {
            ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }
}
