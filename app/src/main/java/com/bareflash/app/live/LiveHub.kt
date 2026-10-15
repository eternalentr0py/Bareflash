package com.bareflash.app.live

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationManagerCompat
import com.bareflash.app.data.Extras
import com.bareflash.app.log.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.concurrent.thread

/**
 * The one place that knows what is running and what to show for it.
 *
 * View models and the export manager call [begin], [progress] and [end]; the
 * hub keeps the wake-lock service alive, shows the live notification only while
 * the app is NOT on screen, removes it when you come back, and posts a result
 * notification if the job ended while you were away.
 *
 * It never touches USB or an engine and holds no reference to a view model.
 */
object LiveHub {

    /** Notification id of the running command (also the foreground-service id). */
    const val RUN_ID = 2001
    private const val RESULT_ID = 2002
    private const val EXPORT_BASE = 3000
    private const val RESULT_EXPORT_BASE = 4000
    private const val POST_INTERVAL_MS = 1000L
    private const val HOLD_MAX_MS = 5 * 60_000L

    private lateinit var app: Application
    private val lock = Any()
    private val sessions = LinkedHashMap<Int, LiveSnapshot>()
    private val lastPost = HashMap<Int, Long>()
    private val postedResults = HashSet<Int>()
    private val main = Handler(Looper.getMainLooper())
    private var exportSeq = 0
    private var queueIndex = 0
    private var queueTotal = 0
    private var holdToken = 0L
    private val dismissed = HashSet<Int>()
    @Volatile private var promotionCheckQueued = false

    private val _promotion = MutableStateFlow(PromotionCheck.Unknown)

    /** What the system did with the last live notification (Android 16 and later). */
    val promotion: StateFlow<PromotionCheck> = _promotion

    fun init(a: Application) {
        app = a
        LiveNotificationFactory.createChannels(a)
        AppVisibility.install(a)
        AppVisibility.onChange { visible -> if (visible) onForeground() else onBackground() }
    }

    fun enabled(): Boolean = Extras.liveNotifications.value

    private fun notifId(s: LiveSnapshot) = s.id

    // ---- called by view models --------------------------------------------

    /** Starts (or, inside a queue, continues) a job. Returns its id. */
    fun begin(
        kind: LiveKind,
        engine: LiveEngine,
        title: String,
        target: String = "",
        device: String = "",
    ): Int {
        val snap: LiveSnapshot
        synchronized(lock) {
            val id = if (kind == LiveKind.Export) EXPORT_BASE + (++exportSeq) else RUN_ID
            val prev = sessions[id]
            val keepClock = prev != null && prev.status != LiveStatus.Done
            snap = LiveSnapshot(
                id = id, kind = kind, engine = engine, title = title, target = target, device = device,
                status = LiveStatus.Starting,
                startedAtWallMs = if (keepClock) prev!!.startedAtWallMs else System.currentTimeMillis(),
                queueIndex = queueIndex, queueTotal = queueTotal,
            )
            sessions[id] = snap
            holdToken++
            dismissed.remove(id)
            promotionCheckQueued = false
        }
        if (snap.needsService) ensureService()
        post(snap, force = true)
        return snap.id
    }

    /** Engine progress. Throttled to about one notification update per second. */
    fun progress(id: Int, done: Long, total: Long, speedBps: Double, etaMs: Long, stage: String) {
        val snap: LiveSnapshot
        synchronized(lock) {
            val cur = sessions[id] ?: return
            snap = cur.copy(
                status = LiveStatus.Running, bytesDone = done, bytesTotal = total,
                speedBps = speedBps, etaMs = etaMs, stage = stage.ifBlank { cur.stage },
            )
            sessions[id] = snap
        }
        post(snap, force = false)
    }

    /** The chip or phone name, once known ("MT6765 \u00b7 Preloader"). */
    fun device(id: Int, device: String) {
        val snap: LiveSnapshot
        synchronized(lock) {
            val cur = sessions[id] ?: return
            if (cur.device == device) return
            snap = cur.copy(device = device)
            sessions[id] = snap
        }
        post(snap, force = false)
    }

    /** Sets what the notification says while nothing is moving ("Waiting for the device"). */
    fun status(id: Int, status: LiveStatus, note: String = "") {
        val snap: LiveSnapshot
        synchronized(lock) {
            val cur = sessions[id] ?: return
            snap = cur.copy(status = status, note = note, speedBps = 0.0, etaMs = -1L)
            sessions[id] = snap
        }
        post(snap, force = true)
    }

    /** "Step 2 of 4" for the next [begin]. Pass 0 and 0 when no queue is running. */
    fun setQueue(index: Int, total: Int) {
        synchronized(lock) { queueIndex = index; queueTotal = total }
    }

    /**
     * Ends a job. With [keepOpen] the notification stays as a "between steps" /
     * "waiting for the device" card instead of ending, so a queue or an automatic
     * resume does not lose the foreground service (it could not be restarted from
     * the background). A held job that nobody continues is closed after five minutes.
     */
    fun end(id: Int, result: LiveResult, keepOpen: Boolean = false, note: String = "") {
        val snap: LiveSnapshot
        synchronized(lock) { snap = sessions[id] ?: return }
        if (keepOpen && snap.needsService) {
            val held: LiveSnapshot
            val token: Long
            synchronized(lock) {
                held = snap.copy(
                    status = if (note.isNotBlank() && note.contains("device", ignoreCase = true)) LiveStatus.Waiting else LiveStatus.Between,
                    note = note, speedBps = 0.0, etaMs = -1L,
                )
                sessions[id] = held
                token = ++holdToken
            }
            post(held, force = true)
            main.postDelayed({ closeIfStillHeld(id, token) }, HOLD_MAX_MS)
            return
        }
        synchronized(lock) {
            sessions.remove(id)
            lastPost.remove(id)
            holdToken++
        }
        postResult(id, result)
        if (snap.needsService) releaseServiceIfIdle() else cancelQuietly(notifId(snap))
    }

    private fun closeIfStillHeld(id: Int, token: Long) {
        val snap: LiveSnapshot
        synchronized(lock) {
            if (holdToken != token) return
            snap = sessions[id] ?: return
            if (snap.status != LiveStatus.Waiting && snap.status != LiveStatus.Between) return
        }
        end(
            id,
            LiveResult(
                kind = snap.kind, engine = snap.engine, outcome = LiveOutcome.Cancelled,
                title = snap.title, target = snap.target,
                elapsedMs = System.currentTimeMillis() - snap.startedAtWallMs, bytes = snap.bytesDone, avgBps = 0.0,
                detail = "The device did not come back.", hint = "Plug it in again and resume from the Dumps tab.",
            )
        )
    }

    // ---- visibility ---------------------------------------------------------

    /** The user swiped the live notification away: stop updating it until the next job. */
    internal fun onDismissed(id: Int) {
        if (id < 0) return
        synchronized(lock) { dismissed += id }
        AppLog.i("live", "Live notification $id was dismissed by the user; not reposting it for this job.")
    }

    private fun onForeground() {
        synchronized(lock) { lastPost.clear(); dismissed.clear() }
        LiveService.instance?.demote()
        val ids = synchronized(lock) { sessions.keys.toList() }
        ids.forEach { cancelQuietly(it) }
        val results = synchronized(lock) { postedResults.toList().also { postedResults.clear() } }
        results.forEach { cancelQuietly(it) }
    }

    private fun onBackground() {
        if (!enabled()) return
        val snaps = synchronized(lock) { sessions.values.toList() }
        if (snaps.isEmpty()) return
        runOnMain {
            snaps.filter { !it.needsService }.forEach { post(it, force = true) }
            if (snaps.any { it.needsService }) {
                val svc = LiveService.instance
                if (svc != null) {
                    if (!svc.isForeground() && !svc.promote()) {
                        // The system refused the foreground state; a plain ongoing notification still shows.
                        snaps.firstOrNull { it.needsService }?.let { post(it, force = true) }
                    }
                } else {
                    snaps.firstOrNull { it.needsService }?.let { post(it, force = true) }
                }
            }
        }
    }

    // ---- posting ---------------------------------------------------------------

    private fun post(snap: LiveSnapshot, force: Boolean) {
        if (!enabled() || AppVisibility.isVisible()) return
        if (!LiveNotificationFactory.canPost(app)) return
        val now = SystemClock.elapsedRealtime()
        synchronized(lock) {
            if (snap.id in dismissed) return
            val last = lastPost[snap.id] ?: 0L
            if (!force && now - last < POST_INTERVAL_MS) return
            lastPost[snap.id] = now
        }
        val n = runCatching { LiveNotificationFactory.live(app, snap) }
            .onFailure { AppLog.w("live", "Could not build the live notification", it) }
            .getOrNull() ?: return
        runCatching { NotificationManagerCompat.from(app).notify(notifId(snap), n) }
        queuePromotionCheck(snap)
    }

    /**
     * Asks the system, once per job, whether it really promoted the notification. When it did
     * not, the log says why (the user switch, or the phone maker keeping Live Updates for its
     * own apps), and Settings shows it instead of leaving the user guessing.
     */
    private fun queuePromotionCheck(snap: LiveSnapshot) {
        if (Build.VERSION.SDK_INT < 36 || !snap.needsService || promotionCheckQueued) return
        promotionCheckQueued = true
        main.postDelayed({ checkPromotion(notifId(snap)) }, 2000L)
    }

    private fun checkPromotion(id: Int) {
        if (Build.VERSION.SDK_INT < 36) return
        val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val n = runCatching { nm.activeNotifications.firstOrNull { it.id == id }?.notification }.getOrNull() ?: return
        val promoted = (n.flags and Notification.FLAG_PROMOTED_ONGOING) != 0
        _promotion.value = if (promoted) PromotionCheck.Promoted else PromotionCheck.NotPromoted
        if (promoted) {
            AppLog.i("live", "The system promoted the live notification (status bar chip and lock screen).")
        } else {
            AppLog.w(
                "live",
                "The system did NOT promote the live notification. promotable=${n.hasPromotableCharacteristics()} " +
                    "userAllowed=${LiveNotificationFactory.canPromote(app)} maker=${Build.MANUFACTURER} " +
                    "sdk=${Build.VERSION.SDK_INT}. Samsung keeps third-party Live Updates behind " +
                    "Developer options > Live notifications for all apps."
            )
        }
    }

    private fun postResult(id: Int, r: LiveResult) {
        if (!enabled() || AppVisibility.isVisible()) return
        if (!LiveNotificationFactory.canPost(app)) return
        val rid = if (id == RUN_ID) RESULT_ID else RESULT_EXPORT_BASE + (id - EXPORT_BASE) % 100
        val n = runCatching { LiveNotificationFactory.result(app, r) }.getOrNull() ?: return
        synchronized(lock) { postedResults += rid }
        runCatching { NotificationManagerCompat.from(app).notify(rid, n) }
    }

    private fun cancelQuietly(id: Int) {
        runCatching { NotificationManagerCompat.from(app).cancel(id) }
    }

    // ---- service ---------------------------------------------------------------

    private fun ensureService() {
        LiveService.start(app, preferForeground = enabled())
        if (!AppVisibility.isVisible() && enabled()) {
            runOnMain { LiveService.instance?.let { if (!it.isForeground()) it.promote() } }
        }
    }

    private fun releaseServiceIfIdle() {
        val idle = synchronized(lock) { sessions.values.none { it.needsService } }
        if (!idle) return
        LiveService.instance?.demote()
        LiveService.stop(app)
        cancelQuietly(RUN_ID)
    }

    /** What the foreground service shows. Always something, so startForeground can never be left without one. */
    internal fun primarySnapshot(): LiveSnapshot =
        synchronized(lock) { sessions.values.firstOrNull { it.needsService } }
            ?: LiveSnapshot(RUN_ID, LiveKind.Other, LiveEngine.App, "Bareflash is working", status = LiveStatus.Starting)

    internal fun primaryNotification(ctx: Context): Notification =
        LiveNotificationFactory.live(ctx, primarySnapshot())

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    // ---- settings preview ----------------------------------------------------------

    /**
     * A 40 second pretend read, so the notification can be seen without risking a real
     * device: start it, then press Home. It goes through the same service and builders.
     */
    fun preview() {
        if (synchronized(lock) { sessions.isNotEmpty() }) return
        val id = begin(LiveKind.Read, LiveEngine.Mtk, "Read partitions", "userdata", "MT6765 \u00b7 Preloader")
        thread(name = "live-preview", isDaemon = true) {
            val total = 8L * 1024 * 1024 * 1024
            val started = SystemClock.elapsedRealtime()
            val speed = 38.0 * 1024 * 1024
            while (true) {
                val el = SystemClock.elapsedRealtime() - started
                if (el >= 40_000L) break
                val done = (el / 40_000.0 * total).toLong()
                progress(id, done, total, speed, ((total - done) / speed * 1000.0).toLong(), "userdata")
                try { Thread.sleep(500) } catch (_: InterruptedException) { break }
            }
            end(
                id,
                LiveResult(
                    LiveKind.Read, LiveEngine.Mtk, LiveOutcome.Success, "Read partitions", "userdata",
                    elapsedMs = 40_000L, bytes = total, avgBps = speed, detail = "Preview", hasDumps = true,
                )
            )
        }
    }
}
