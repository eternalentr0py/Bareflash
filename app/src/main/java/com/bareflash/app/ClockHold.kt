package com.bareflash.app

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.Process
import com.bareflash.app.log.AppLog
import java.io.File
import kotlin.concurrent.thread

/**
 * Holds every CPU cluster at its top clock while a command runs.
 *
 * The transfer thread already busy-waits (see UsbTuning.busyWait), which keeps
 * its own core fast. But each USB completion is raised by an interrupt that is
 * serviced on whichever core the kernel picked -- often one in the little
 * cluster -- and that cluster's clock stays low because nothing else is
 * running there. Rotating the screen "fixed" this only because the UI work
 * woke every cluster. One low-priority spinner per cluster does the same,
 * permanently, for the length of a command.
 *
 * It is a heat and battery cost, so it stops itself when the device reports
 * moderate thermal pressure and is a switch in Settings -> General.
 */
object ClockHold {
    private const val TAG = "ClockHold"

    @Volatile private var run = false
    @Volatile private var sink = 0L
    private var spinners: List<Thread> = emptyList()
    private var wanted = false
    private var pm: PowerManager? = null
    private var thermal: PowerManager.OnThermalStatusChangedListener? = null

    @Synchronized
    fun start(ctx: Context) {
        if (wanted) return
        wanted = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val p = ctx.getSystemService(PowerManager::class.java)
            pm = p
            if (p != null && p.currentThermalStatus >= PowerManager.THERMAL_STATUS_MODERATE) {
                AppLog.w(TAG, "Not holding clocks: device is already thermally throttled")
                return
            }
            val l = PowerManager.OnThermalStatusChangedListener { status ->
                if (status >= PowerManager.THERMAL_STATUS_MODERATE) {
                    stopSpinners("thermal status $status")
                } else if (status <= PowerManager.THERMAL_STATUS_LIGHT) {
                    startSpinners()
                }
            }
            thermal = l
            runCatching { p?.addThermalStatusListener(ctx.mainExecutor, l) }
        }
        startSpinners()
    }

    @Synchronized
    fun stop() {
        wanted = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            thermal?.let { l -> runCatching { pm?.removeThermalStatusListener(l) } }
        }
        thermal = null
        stopSpinners(null)
    }

    /** CPU ids grouped by max frequency, fastest cluster first, as affinity masks. */
    private fun clusterMasks(): List<Long> {
        val n = Runtime.getRuntime().availableProcessors().coerceAtMost(63)
        val byFreq = sortedMapOf<Long, Long>(compareByDescending { it })
        val count = HashMap<Long, Int>()
        for (i in 0 until n) {
            val f = runCatching {
                File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq").readText().trim().toLong()
            }.getOrDefault(0L)
            byFreq[f] = (byFreq[f] ?: 0L) or (1L shl i)
            count[f] = (count[f] ?: 0) + 1
        }
        if (byFreq.size < 2 && byFreq.keys.firstOrNull() == 0L) return emptyList() // sysfs unreadable
        val out = ArrayList<Long>()
        var first = true
        for ((f, mask) in byFreq) {
            // A lone prime core is where the transfer thread wants to live; leave it alone.
            if (first && (count[f] ?: 0) == 1 && byFreq.size > 1) { first = false; continue }
            first = false
            out.add(mask)
        }
        return out
    }

    @Synchronized
    private fun startSpinners() {
        if (run || !wanted || !LibusbNative.available) return
        val masks = clusterMasks()
        if (masks.isEmpty()) return
        run = true
        spinners = masks.mapIndexed { idx, mask ->
            thread(name = "clock-hold-$idx", isDaemon = true) {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                runCatching { LibusbNative.nativeSetThreadAffinity(mask) }
                var x = 88172645463325252L + idx
                while (run) {
                    // Cheap integer churn the JIT cannot fold away.
                    x = x xor (x shl 13); x = x xor (x ushr 7); x = x xor (x shl 17)
                }
                sink = x
            }
        }
        AppLog.i(TAG, "Holding ${masks.size} CPU cluster(s) at full speed (masks ${masks.joinToString { "0x" + it.toString(16) }})")
    }

    @Synchronized
    private fun stopSpinners(reason: String?) {
        if (!run) return
        run = false
        spinners = emptyList()
        if (reason != null) AppLog.w(TAG, "Released CPU clock hold: $reason")
    }
}
