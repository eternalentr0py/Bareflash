package com.bareflash.app.live

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ServiceCompat

/**
 * Keeps the CPU awake while a command runs (screen can still turn off) and,
 * only while the app is in the background, carries the live notification as its
 * foreground notification. When you come back to the app it steps out of the
 * foreground so the notification disappears; the wake lock stays held until the
 * job ends. [LiveHub] is the only caller.
 */
class LiveService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var foreground = false

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply { setReferenceCounted(false) }
        }
        wakeLock?.let { if (!it.isHeld) it.acquire(MAX_HOLD_MS) }
        // Started while the app is hidden (startForegroundService): the system requires
        // a foreground notification right now, whatever the setting says.
        if (!AppVisibility.isVisible() && LiveHub.enabled()) {
            if (!promote()) {
                // The system must get a foreground notification right now; show the bare one.
                runCatching { startForegroundBare() }
            }
        }
        return START_NOT_STICKY
    }

    /** Becomes a foreground service carrying the live notification. Returns false if the system refused. */
    fun promote(): Boolean {
        return try {
            val n = LiveHub.primaryNotification(this)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(this, LiveHub.RUN_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(LiveHub.RUN_ID, n)
            }
            foreground = true
            true
        } catch (e: Exception) {
            // Android 12+ refuses startForeground from the background in some cases.
            foreground = false
            false
        }
    }

    private fun startForegroundBare() {
        val n = LiveNotificationFactory.live(this, LiveHub.primarySnapshot())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(this, LiveHub.RUN_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(LiveHub.RUN_ID, n)
        }
        foreground = true
    }

    /** Steps out of the foreground and removes the notification (the app is on screen again). */
    fun demote() {
        if (!foreground) return
        foreground = false
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
    }

    fun isForeground(): Boolean = foreground

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        foreground = false
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val WAKE_LOCK_TAG = "Bareflash:live"

        /** Safety ceiling so a bug can never pin the CPU awake forever. */
        private const val MAX_HOLD_MS = 8 * 60 * 60 * 1000L

        @Volatile var instance: LiveService? = null
            private set

        fun start(ctx: Context, preferForeground: Boolean) {
            val i = Intent(ctx, LiveService::class.java)
            runCatching {
                if (AppVisibility.isVisible() || !preferForeground) ctx.startService(i)
                else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
                else ctx.startService(i)
            }
        }

        fun stop(ctx: Context) {
            runCatching { ctx.stopService(Intent(ctx, LiveService::class.java)) }
        }
    }
}

/** The notification's Stop button. Reaches the same entry point as the Console's Stop. */
class LiveStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_STOP) LiveControl.cancel?.invoke()
    }

    companion object {
        const val ACTION_STOP = "com.bareflash.app.action.STOP_LIVE"
    }
}

/**
 * The user swiped the live notification away. Google's guidance is to never repost a
 * Live Update the user dismissed (they may switch Live Updates off for the app instead),
 * so the hub stops updating it until the next job begins.
 */
class LiveDismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_DISMISSED) LiveHub.onDismissed(intent.getIntExtra(EXTRA_ID, -1))
    }

    companion object {
        const val ACTION_DISMISSED = "com.bareflash.app.action.LIVE_DISMISSED"
        const val EXTRA_ID = "id"
    }
}

/** Set by whichever view model owns the running job; the Stop action calls it. */
object LiveControl {
    @Volatile var cancel: (() -> Unit)? = null
}
