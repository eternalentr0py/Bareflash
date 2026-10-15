package com.bareflash.app

import android.content.Context
import android.os.Build
import android.os.PerformanceHintManager
import android.os.Process
import android.os.SystemClock
import android.util.Log
import kotlin.concurrent.thread

/**
 * Transfer-thread scheduling helpers for the life of a command.
 *
 * Always (cheap, default path):
 *  - One-shot [Process.THREAD_PRIORITY_URGENT_DISPLAY] on the transfer thread
 *  - Pin to fast cores when libusb-native is available
 *  - Optional ADPF session (API 31+) created once; fed only from [tick]
 *
 * Gated by Settings -> Experimental (default **off**, heat when on):
 *  - [UsbTuning.holdClusters] → [ClockHold] spinners on every cluster
 *  - [UsbTuning.boostKeeper] → side thread re-asserting priority + ADPF
 *    every 100 ms for the whole command
 *
 * Busy-wait lives in native ([UsbTuning.busyWait] / bfusb), not here.
 *
 * Call [begin] on the transfer thread, [tick] from progress callbacks, and
 * [end] when the command finishes.
 */
object PerfBoost {
    private const val TAG = "PerfBoost"
    private const val TARGET_NS = 1_500_000L
    private const val REPORT_NS = 3_000_000L
    private const val TICK_MIN_MS = 50L
    private const val KEEPER_PERIOD_MS = 100L

    private var session: PerformanceHintManager.Session? = null
    private var lastTick = 0L
    @Volatile private var transferTid: Int = -1
    @Volatile private var keeperRunning = false
    private var keeperThread: Thread? = null

    @Synchronized
    fun begin(ctx: Context) {
        endKeeperOnly()
        transferTid = Process.myTid()
        runCatching {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
        }
        runCatching {
            if (LibusbNative.available) {
                val n = LibusbNative.nativePinToFastCores()
                if (n > 0) Log.i(TAG, "pinned transfer thread to $n fast cores")
            }
        }
        if (UsbTuning.holdClusters.value) {
            runCatching { ClockHold.start(ctx) }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching {
                val mgr = ctx.getSystemService(PerformanceHintManager::class.java)
                session = mgr?.createHintSession(intArrayOf(transferTid), TARGET_NS)
                lastTick = 0L
            }.onFailure { Log.w(TAG, "hint session unavailable: ${it.message}") }
        }
        if (UsbTuning.boostKeeper.value) {
            startKeeper()
            Log.i(TAG, "boost keeper ON for tid=$transferTid")
        } else {
            Log.i(TAG, "boost keeper off; one-shot priority+affinity for tid=$transferTid")
        }
    }

    /** Cheap; safe to call on every progress update from the transfer thread. */
    @Synchronized
    fun tick() {
        if (UsbTuning.boostKeeper.value) reassertPriority()
        val s = session ?: return
        val now = SystemClock.elapsedRealtime()
        if (now - lastTick < TICK_MIN_MS) return
        lastTick = now
        runCatching { s.reportActualWorkDuration(REPORT_NS) }
    }

    @Synchronized
    fun end() {
        endKeeperOnly()
        runCatching { ClockHold.stop() }
        runCatching { session?.close() }
        session = null
        transferTid = -1
        Log.i(TAG, "boost released")
    }

    private fun reassertPriority() {
        val tid = transferTid
        if (tid <= 0) return
        runCatching {
            Process.setThreadPriority(tid, Process.THREAD_PRIORITY_URGENT_DISPLAY)
        }
    }

    private fun startKeeper() {
        keeperRunning = true
        keeperThread = thread(name = "perf-boost-keeper", isDaemon = true) {
            runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY) }
            while (keeperRunning) {
                reassertPriority()
                val s = session
                if (s != null) {
                    runCatching { s.reportActualWorkDuration(REPORT_NS) }
                }
                try {
                    Thread.sleep(KEEPER_PERIOD_MS)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
    }

    private fun endKeeperOnly() {
        keeperRunning = false
        keeperThread?.interrupt()
        keeperThread = null
    }
}
