package com.bareflash.app

import com.bareflash.app.log.AppLog
import android.app.Application
import com.bareflash.app.data.SettingsRepository
import com.bareflash.app.dump.DumpBridge
import com.bareflash.app.export.ExportManager

/** Process-wide singletons. */
object AppGraph {
    lateinit var settings: SettingsRepository
        private set
    lateinit var export: ExportManager
        private set

    internal fun init(app: Application) {
        if (!::settings.isInitialized) settings = SettingsRepository(app)
        com.bareflash.app.data.Extras.init(app)
        DumpBridge.init(app)
        com.bareflash.app.data.CommandPresets.init(app)
        if (!::export.isInitialized) export = ExportManager(app)
    }
}

class MtkApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        UsbTuning.init(this)
        AppGraph.init(this)
        com.bareflash.app.live.LiveHub.init(this)
    }
}
