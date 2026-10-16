package com.bareflash.app

import android.content.Context
import android.content.SharedPreferences
import com.bareflash.app.log.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Switches for the USB throughput work, shown in Settings -> Experimental.
 * Changes apply from the next command.
 *
 * Efficient (safe default on):
 *  - [fusedRead]: MediaTek READ_DATA frame loop in one native call per batch.
 *
 * Experimental (default **off**, needs a device test):
 *  - [daWindowsEnv]: tell the MediaTek XFlash DA the host is Windows instead
 *    of Linux when the environment is set up. On the MT6765 DA that appears to
 *    lift the read frame from 64 KiB to 2 MiB (see docs/USB_PERFORMANCE.md).
 *    Takes effect when the DA is next loaded, i.e. from the next command.
 *  - [daLogUsb]: diagnostic. Sends the MediaTek XFlash DA's own log lines
 *    (including its per-transfer speed dump) over USB instead of its UART so
 *    they show up in Settings -> Logging. Same timing as [daWindowsEnv].
 *
 * Power / heat fallbacks (default **off** — turn on only to A/B the
 * "rotation restores speed" behaviour):
 *  - [busyWait]: poll instead of sleep while a USB transfer is outstanding.
 *  - [holdClusters]: light spinner per CPU cluster ([ClockHold]).
 *  - [boostKeeper]: side thread that re-asserts transfer-thread priority and
 *    keeps feeding ADPF for the whole command ([PerfBoost] keeper).
 */
object UsbTuning {
    private const val PREFS = "usb_tuning"
    private var prefs: SharedPreferences? = null

    private val _fused = MutableStateFlow(true)
    val fusedRead: StateFlow<Boolean> = _fused.asStateFlow()

    /** MediaTek DA setup_env reports system_os=Windows (0) instead of Linux (1). */
    private val _daWin = MutableStateFlow(false)
    val daWindowsEnv: StateFlow<Boolean> = _daWin.asStateFlow()

    /** MediaTek DA setup_env asks for log_channel=USB; the host logs the DA's lines. */
    private val _daLog = MutableStateFlow(false)
    val daLogUsb: StateFlow<Boolean> = _daLog.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busyWait: StateFlow<Boolean> = _busy.asStateFlow()

    private val _hold = MutableStateFlow(false)
    val holdClusters: StateFlow<Boolean> = _hold.asStateFlow()

    private val _keeper = MutableStateFlow(false)
    val boostKeeper: StateFlow<Boolean> = _keeper.asStateFlow()

    /** SHA-256 of every dump, computed on a side thread while the file is written. */
    private val _hashDumps = MutableStateFlow(true)
    val hashDumps: StateFlow<Boolean> = _hashDumps.asStateFlow()

    fun init(ctx: Context) {
        val p = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        _fused.value = p.getBoolean("fused_read", true)
        _daWin.value = p.getBoolean("da_windows_env", false)
        _daLog.value = p.getBoolean("da_log_usb", false)
        // Heat sources default off so a normal dump does not cook the phone.
        _busy.value = p.getBoolean("busy_wait", false)
        _hold.value = p.getBoolean("hold_clusters", false)
        _keeper.value = p.getBoolean("boost_keeper", false)
        _hashDumps.value = p.getBoolean("hash_dumps", true)
        AppLog.i(
            "usb-tuning",
            "fusedRead=${_fused.value} busyWait=${_busy.value} " +
                "holdClusters=${_hold.value} boostKeeper=${_keeper.value} " +
                "daWindowsEnv=${_daWin.value} daLogUsb=${_daLog.value}"
        )
    }

    fun setFusedRead(on: Boolean) = set("fused_read", on, _fused)

    fun setDaWindowsEnv(on: Boolean) = set("da_windows_env", on, _daWin)

    fun setDaLogUsb(on: Boolean) = set("da_log_usb", on, _daLog)

    fun setBusyWait(on: Boolean) {
        set("busy_wait", on, _busy)
        applyToNative()
    }

    fun setHoldClusters(on: Boolean) = set("hold_clusters", on, _hold)

    fun setBoostKeeper(on: Boolean) = set("boost_keeper", on, _keeper)

    fun setHashDumps(on: Boolean) = set("hash_dumps", on, _hashDumps)

    /** Default cool path: fused on, heat sources off. */
    fun applyPresetCool() {
        setFusedRead(true)
        setBusyWait(false)
        setHoldClusters(false)
        setBoostKeeper(false)
    }

    /** Max sustained clocks: all heat toggles on (uses more power). */
    fun applyPresetSpeed() {
        setFusedRead(true)
        setBusyWait(true)
        setHoldClusters(true)
        setBoostKeeper(true)
    }

    private fun set(key: String, on: Boolean, flow: MutableStateFlow<Boolean>) {
        flow.value = on
        prefs?.edit()?.putBoolean(key, on)?.apply()
        AppLog.i("usb-tuning", "$key=$on")
    }

    /** Pushes the wait mode into the native layer; safe to call repeatedly. */
    fun applyToNative() {
        if (LibusbNative.available) {
            runCatching { LibusbNative.nativeSetBusyWait(_busy.value) }
                .onFailure { AppLog.w("usb-tuning", "Could not set wait mode", it) }
        }
    }
}
