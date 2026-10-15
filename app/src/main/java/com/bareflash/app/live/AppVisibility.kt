package com.bareflash.app.live

import android.app.Activity
import android.app.Application
import android.os.Bundle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Whether any Bareflash screen is on screen right now. Live notifications are
 * for when you are NOT looking at the app: they are removed the moment an
 * activity starts and come back when the last one stops (home, recents, screen
 * off). Counting started activities needs no extra dependency.
 */
object AppVisibility {
    private val _visible = MutableStateFlow(false)
    val visible: StateFlow<Boolean> = _visible

    private var started = 0
    private var listener: ((Boolean) -> Unit)? = null

    /** Called on the main thread whenever visibility flips. */
    fun onChange(l: (Boolean) -> Unit) { listener = l }

    fun isVisible(): Boolean = _visible.value

    fun install(app: Application) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                started++
                set(true)
            }

            override fun onActivityStopped(activity: Activity) {
                if (started > 0) started--
                // A rotation stops and restarts the activity; only a real stop of the
                // last one (not a configuration change) counts as leaving the app.
                if (started == 0 && !activity.isChangingConfigurations) set(false)
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            // Back from Home before the activity was ever stopped: no onStart, but it is on screen again.
            override fun onActivityResumed(activity: Activity) { set(true) }
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    /** MainActivity.onUserLeaveHint: the user pressed Home or Recents. Earlier than onStop. */
    fun userLeaving() {
        if (_visible.value) {
            _visible.value = false
            listener?.invoke(false)
        }
    }

    private fun set(v: Boolean) {
        if (_visible.value == v) return
        _visible.value = v
        listener?.invoke(v)
    }
}
