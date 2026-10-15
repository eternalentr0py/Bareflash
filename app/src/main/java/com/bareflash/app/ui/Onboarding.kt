package com.bareflash.app.ui

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * First-run state: whether the disclaimer has been accepted, and whether the
 * tutorial is on screen.
 *
 * Stored in its own preference file on purpose. Settings backups (export /
 * import) never touch it, so restoring a backup on a fresh install cannot
 * skip the disclaimer. The key carries a version: bump [KEY_ACCEPTED] if the
 * disclaimer text ever changes in a way people must agree to again.
 */
object Onboarding {
    private const val FILE = "onboarding"
    private const val KEY_ACCEPTED = "disclaimer_accepted_v1"
    private const val KEY_TUTORIAL_SEEN = "tutorial_seen_v1"

    private var prefs: SharedPreferences? = null

    private val _accepted = MutableStateFlow(false)
    val accepted: StateFlow<Boolean> = _accepted.asStateFlow()

    private val _tutorialVisible = MutableStateFlow(false)
    val tutorialVisible: StateFlow<Boolean> = _tutorialVisible.asStateFlow()

    /** Call once, before the first frame. Safe to call again. */
    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        prefs = p
        _accepted.value = p.getBoolean(KEY_ACCEPTED, false)
    }

    /** "I understand": remembered for good, and the tutorial opens once on the very first run. */
    fun accept() {
        prefs?.edit()?.putBoolean(KEY_ACCEPTED, true)?.apply()
        _accepted.value = true
        if (prefs?.getBoolean(KEY_TUTORIAL_SEEN, false) != true) openTutorial()
    }

    /** Opens the tutorial (first run, or "Tutorial" in Settings). Counts as seen straight away. */
    fun openTutorial() {
        prefs?.edit()?.putBoolean(KEY_TUTORIAL_SEEN, true)?.apply()
        _tutorialVisible.value = true
    }

    fun closeTutorial() {
        _tutorialVisible.value = false
    }
}
