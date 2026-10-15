package com.bareflash.app.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Small switches that do not belong to the older settings blob. Each one is a
 * StateFlow so Compose and the view models read the same value.
 *
 * - [liveNotifications]: show the live progress notification when the app is in the background.
 * - [autoResume]: continue an interrupted single-file read by itself when the device comes back.
 * - [readOnlyLock]: refuse every write, erase and flash.
 * - [chipProfiles]: apply the saved protocol settings when a known chip is detected.
 */
object Extras {
    private const val PREFS = "extras"

    private var prefs: SharedPreferences? = null

    private val _live = MutableStateFlow(true)
    val liveNotifications: StateFlow<Boolean> = _live

    private val _auto = MutableStateFlow(true)
    val autoResume: StateFlow<Boolean> = _auto

    private val _ro = MutableStateFlow(false)
    val readOnlyLock: StateFlow<Boolean> = _ro

    private val _chip = MutableStateFlow(true)
    val chipProfiles: StateFlow<Boolean> = _chip

    fun init(app: Context) {
        val p = app.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        _live.value = p.getBoolean("live", true)
        _auto.value = p.getBoolean("auto_resume", true)
        _ro.value = p.getBoolean("read_only", false)
        _chip.value = p.getBoolean("chip_profiles", true)
    }

    fun setLiveNotifications(v: Boolean) { _live.value = v; prefs?.edit()?.putBoolean("live", v)?.apply() }
    fun setAutoResume(v: Boolean) { _auto.value = v; prefs?.edit()?.putBoolean("auto_resume", v)?.apply() }
    fun setReadOnlyLock(v: Boolean) { _ro.value = v; prefs?.edit()?.putBoolean("read_only", v)?.apply() }
    fun setChipProfiles(v: Boolean) { _chip.value = v; prefs?.edit()?.putBoolean("chip_profiles", v)?.apply() }

    /** Raw access for small stores that live next to the switches (chip profiles). */
    internal fun prefs(): SharedPreferences? = prefs
}
