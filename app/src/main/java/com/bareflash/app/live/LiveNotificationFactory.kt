package com.bareflash.app.live

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import com.bareflash.app.MainActivity
import com.bareflash.app.R
import com.bareflash.app.dump.DumpBridge

/**
 * Builds the two notifications Bareflash ever shows:
 *
 * 1. The live one, while something is running and you are not in the app. On
 *    Android 16 and later it is a promoted Live Update (status bar chip, lock
 *    screen, always-on display, Samsung Now Bar) with a segmented progress bar
 *    and a tracker. On older versions it is a clean ongoing progress notification.
 * 2. The result one, when it ends while you are away.
 *
 * Nothing here talks to USB or an engine; it only turns a [LiveSnapshot] into pixels.
 */
internal object LiveNotificationFactory {

    const val CH_LIVE = "bf_live"
    const val CH_RESULT = "bf_result"

    private val LEGACY_CHANNELS = listOf("command_keepalive", "command_progress", "export_progress")

    private const val OK = 0xFF2E9E57.toInt()
    private const val ERR = 0xFFD64545.toInt()
    private const val WARN = 0xFFE0A030.toInt()

    // ---- channels --------------------------------------------------------

    fun createChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        LEGACY_CHANNELS.forEach { runCatching { nm.deleteNotificationChannel(it) } }
        nm.createNotificationChannel(
            NotificationChannel(CH_LIVE, "Live activity", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Progress of a read, write or flash while the app is in the background."
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_RESULT, "Results", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "How a read, write or flash ended, when it finished while you were away."
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }
        )
    }

    // ---- permission and capability ----------------------------------------

    fun canPost(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return nm.areNotificationsEnabled()
    }

    /** True when the system will show the notification as a promoted Live Update. */
    fun canPromote(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < 36) return false
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return runCatching { nm.canPostPromotedNotifications() }.getOrDefault(false)
    }

    /** Samsung phones route Live notifications through their own system; see [samsungExtras]. */
    fun isSamsung(): Boolean = Build.MANUFACTURER.equals("samsung", ignoreCase = true)

    // ---- pending intents -----------------------------------------------------

    private fun dismissIntent(ctx: Context, id: Int): PendingIntent {
        val i = Intent(ctx, LiveDismissReceiver::class.java)
            .setAction(LiveDismissReceiver.ACTION_DISMISSED)
            .setPackage(ctx.packageName)
            .putExtra(LiveDismissReceiver.EXTRA_ID, id)
        return PendingIntent.getBroadcast(ctx, 100 + id, i, flags())
    }

    /**
     * One UI 7 (Android 15) Live notification / Now Bar fields. One UI only honors these for
     * packages Samsung has allowlisted, and ignores them for everyone else, so this is harmless
     * on other phones. Android 16 and later use the platform Live Updates API instead and these
     * are deliberately not added there.
     */
    private fun samsungExtras(s: LiveSnapshot): android.os.Bundle? {
        if (!isSamsung() || Build.VERSION.SDK_INT >= 36) return null
        val head = LiveFormat.headline(s)
        val detail = LiveFormat.detailLine(s)
        return android.os.Bundle().apply {
            putInt("android.ongoingActivityNoti.style", 1)
            putString("android.ongoingActivityNoti.primaryInfo", head)
            putString("android.ongoingActivityNoti.secondaryInfo", detail)
            putString("android.ongoingActivityNoti.chipExpandedText", LiveFormat.chip(s))
            putString("android.ongoingActivityNoti.nowbarPrimaryInfo", head)
            putString("android.ongoingActivityNoti.nowbarSecondaryInfo", detail)
        }
    }

    private fun flags() = PendingIntent.FLAG_UPDATE_CURRENT or
        (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_IMMUTABLE else 0)

    private fun openApp(ctx: Context): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(ctx, 0, i, flags())
    }

    private fun stopAction(ctx: Context): PendingIntent {
        val i = Intent(ctx, LiveStopReceiver::class.java)
            .setAction(LiveStopReceiver.ACTION_STOP)
            .setPackage(ctx.packageName)
        return PendingIntent.getBroadcast(ctx, 1, i, flags())
    }

    private fun openDumps(ctx: Context): PendingIntent? {
        val tree = DumpBridge.currentUri() ?: return null
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(tree, "vnd.android.document/directory")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching { PendingIntent.getActivity(ctx, 2, view, flags()) }.getOrNull()
    }

    // ---- the live notification ------------------------------------------------

    fun live(ctx: Context, s: LiveSnapshot): Notification =
        if (Build.VERSION.SDK_INT >= 36) promoted(ctx, s) else classic(ctx, s)

    private fun indeterminate(s: LiveSnapshot) = !s.measurable || s.status != LiveStatus.Running

    /** Android 16+: a promoted Live Update with a segmented progress bar. */
    @RequiresApi(36)
    private fun promoted(ctx: Context, s: LiveSnapshot): Notification {
        val accent = s.engine.color
        val style = Notification.ProgressStyle()
            .setStyledByProgress(true)
            .setProgressSegments(listOf(Notification.ProgressStyle.Segment(100).setColor(accent)))
            .setProgress(if (indeterminate(s)) 0 else s.percent)
            .setProgressIndeterminate(indeterminate(s))
            .setProgressStartIcon(Icon.createWithResource(ctx, R.drawable.ic_live_phone))
            .setProgressEndIcon(Icon.createWithResource(ctx, R.drawable.ic_live_folder))
            .setProgressTrackerIcon(Icon.createWithResource(ctx, R.drawable.ic_stat_live))

        val b = Notification.Builder(ctx, CH_LIVE)
            .setSmallIcon(R.drawable.ic_stat_live)
            .setContentTitle(LiveFormat.headline(s))
            .setContentText(LiveFormat.detailLine(s))
            .setSubText(LiveFormat.subText(s))
            .setColor(accent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(openApp(ctx))
            .setDeleteIntent(dismissIntent(ctx, s.id))
            .setShowWhen(true)
            .setWhen(s.startedAtWallMs)
            .setUsesChronometer(true)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .setStyle(style)
            // Same switch as Builder.setRequestPromotedOngoing; set as the extra it stands for so it
            // compiles against every SDK 36 stub.
            .addExtras(android.os.Bundle().apply { putBoolean("android.requestPromotedOngoing", true) })
            .setShortCriticalText(LiveFormat.chip(s))
        if (s.kind != LiveKind.Export || s.status != LiveStatus.Done) {
            b.addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(ctx, R.drawable.ic_live_stop), "Stop", stopAction(ctx)
                ).build()
            )
        }
        return b.build()
    }

    /** Android 8 to 15: an ongoing progress notification, expanded to show every figure. */
    private fun classic(ctx: Context, s: LiveSnapshot): Notification {
        val title = if (s.measurable && s.status == LiveStatus.Running) "${LiveFormat.headline(s)} \u00b7 ${s.percent}%"
        else LiveFormat.headline(s)
        val detail = LiveFormat.detailLine(s)
        val big = buildString {
            append(detail)
            if (s.stage.isNotBlank() && s.stage != s.target) append('\n').append(s.stage)
            append("\nElapsed ").append(LiveFormat.elapsed(System.currentTimeMillis() - s.startedAtWallMs))
        }
        val b = NotificationCompat.Builder(ctx, CH_LIVE)
            .setSmallIcon(R.drawable.ic_stat_live)
            .setContentTitle(title)
            .setContentText(detail)
            .setSubText(LiveFormat.subText(s))
            .setColor(s.engine.color)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(openApp(ctx))
            .setDeleteIntent(dismissIntent(ctx, s.id))
            .setShowWhen(true)
            .setWhen(s.startedAtWallMs)
            .setUsesChronometer(true)
            .setProgress(100, if (indeterminate(s)) 0 else s.percent, indeterminate(s))
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setStyle(NotificationCompat.BigTextStyle().bigText(big))
        b.addAction(R.drawable.ic_live_stop, "Stop", stopAction(ctx))
        samsungExtras(s)?.let { b.addExtras(it) }
        return b.build()
    }

    // ---- the result notification ------------------------------------------------

    fun result(ctx: Context, r: LiveResult): Notification {
        val (word, icon, color) = when (r.outcome) {
            LiveOutcome.Success -> Triple("complete", R.drawable.ic_stat_done, OK)
            LiveOutcome.Cancelled -> Triple("stopped", R.drawable.ic_stat_live, WARN)
            LiveOutcome.Failure -> Triple("failed", R.drawable.ic_stat_error, ERR)
        }
        val what = r.target.ifBlank { r.title }
        val title = if (what.isBlank()) "${LiveFormat.pastVerb(r.kind)} $word" else "${LiveFormat.pastVerb(r.kind)} $word \u00b7 $what"
        val facts = buildList {
            add(LiveFormat.elapsed(r.elapsedMs))
            if (r.bytes > 0L) add(LiveFormat.bytes(r.bytes))
            LiveFormat.speed(r.avgBps).takeIf { it.isNotEmpty() }?.let { add("avg $it") }
        }.joinToString(" \u00b7 ")
        val text = when {
            r.outcome == LiveOutcome.Failure && r.detail.isNotBlank() -> r.detail
            r.detail.isNotBlank() && r.outcome == LiveOutcome.Success -> "${r.detail} \u00b7 $facts"
            else -> facts
        }
        val big = buildString {
            append(text)
            if (r.outcome != LiveOutcome.Success && r.hint.isNotBlank()) append('\n').append(r.hint)
            if (r.outcome == LiveOutcome.Failure) append('\n').append("Took ").append(facts)
        }
        val b = NotificationCompat.Builder(ctx, CH_RESULT)
            .setSmallIcon(icon)
            .setContentTitle(title)
            .setContentText(text)
            .setSubText(r.engine.label)
            .setColor(color)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(if (r.outcome == LiveOutcome.Failure) NotificationCompat.CATEGORY_ERROR else NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(openApp(ctx))
            .setShowWhen(true)
            .setWhen(System.currentTimeMillis())
            .setStyle(NotificationCompat.BigTextStyle().bigText(big))
        if (r.outcome == LiveOutcome.Success && r.hasDumps) {
            openDumps(ctx)?.let { b.addAction(R.drawable.ic_live_open, "Open dumps", it) }
        }
        return b.build()
    }
}
