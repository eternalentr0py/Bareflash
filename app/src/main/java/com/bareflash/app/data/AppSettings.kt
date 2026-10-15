package com.bareflash.app.data

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class AppearanceSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val oled: Boolean = false,
    val preset: String = "material_you",
    /** Accent id: "auto" (follows the engine, default) or a fixed theme id. */
    val accent: String = "auto"
)

enum class IslandSize { COMPACT, REGULAR, PROMINENT }
enum class IslandMaterial { OLED, FROSTED, SOLID }
enum class IslandMotion { APPLE, SNAPPY, SOFT }
enum class ProgressStyle { BAR, RING, BOTH }

/**
 * Dynamic Island preferences. Field-for-field the web preview's
 * `IslandSettings` (store.ts); persisted as one JSON blob.
 */
data class IslandSettings(
    val enabled: Boolean = true,
    val tapToExpand: Boolean = true,
    val autoExpandOnConnect: Boolean = true,
    val autoExpandOnJob: Boolean = true,
    val autoCollapseSec: Float = 2.4f,
    val compactLiveActivity: Boolean = true,
    val sizePreset: IslandSize = IslandSize.REGULAR,
    val cornerRadius: Int = 42,
    val material: IslandMaterial = IslandMaterial.OLED,
    val motion: IslandMotion = IslandMotion.APPLE,
    val speed: Float = 1f,
    val overshoot: Boolean = true,
    val opacity: Float = 1f,
    val shadow: Boolean = true,
    val pulseLed: Boolean = true,
    val showLenses: Boolean = false,
    val showLed: Boolean = true,
    val showDevice: Boolean = true,
    val showMode: Boolean = true,
    val showHw: Boolean = true,
    val showCommand: Boolean = true,
    val showProgressBar: Boolean = true,
    val showPercent: Boolean = true,
    val showEta: Boolean = true,
    val showSpeed: Boolean = true,
    val showTarget: Boolean = true,
    val showStage: Boolean = true,
    val progressStyle: ProgressStyle = ProgressStyle.BOTH
)

/** Persistent defaults applied to commands; a flag typed by hand always wins. */
data class ProtocolSettings(
    val debugMode: Boolean = false,
    val skipWatchdog: Boolean = false,
    val alreadyInPreloader: Boolean = false,
    val readSocId: Boolean = false,
    val useCustomFiles: Boolean = false,
    /** Force Preloader crash into BROM even when already exploitable. */
    val enforceCrash: Boolean = false,
    /** Prefer stock Download Agent over custom/kamakiri path. */
    val stockDa: Boolean = false,
    /** MT6261/MT2301 IoT handshake. */
    val iotMode: Boolean = false,
    val payloadType: String = "",       // "", amonet, kamakiri, kamakiri2, carbonara
    val vid: String = "",
    val pid: String = "",
    val sectorSize: String = "0x200",
    /** UART log level passed as --uartloglevel (empty = omit). */
    val uartLogLevel: String = "",
    val preloaderPath: String = "",     // app-private copy of the picked file
    val preloaderName: String = "",
    val daPath: String = "",
    val daName: String = "",
    /** SLA/DAA auth file (auth_sv5.auth), --auth */
    val authPath: String = "",
    val authName: String = "",
    /** Certificate file for secure devices, --cert */
    val certPath: String = "",
    val certName: String = "",
)

data class GeneralSettings(
    /** Last partition name confirmed in the Dump dialog. Empty until first use. */
    val lastDumpPartition: String = "",
    /** Last partition name confirmed in the Flash dialog. Empty until first use. */
    val lastFlashPartition: String = "",
    /** Short vibration when a command finishes (success / fail / cancel). */
    val vibrateOnCommandEnd: Boolean = true,
    /** Notification-stream beep when a command finishes. Off by default. */
    val soundOnCommandEnd: Boolean = false,
    /**
     * Persistable SAF tree URI for dumps. Empty means the app-private
     * `dumps/` folder. Writes go straight to this tree — no internal copy.
     */
    val dumpFolderUri: String = "",
    /** Display name of [dumpFolderUri], e.g. "MTK" or "Download". */
    val dumpFolderName: String = "",
)

data class AppSettings(
    val terminal: TerminalSettings = TerminalSettings(),
    val appearance: AppearanceSettings = AppearanceSettings(),
    val protocols: ProtocolSettings = ProtocolSettings(),
    val general: GeneralSettings = GeneralSettings(),
    val island: IslandSettings = IslandSettings(),
    val look: LookSettings = LookSettings(),
    val accessibility: AccessibilitySettings = AccessibilitySettings(),
    val pinned: List<String> = DEFAULT_PINNED,
    /** Argument values from the last Run in the command review sheet, by command. */
    val lastReviewArgs: Map<String, List<String>> = emptyMap()
) {
    companion object {
        /** Same commands the previous fixed Quick Action buttons ran. */
        val DEFAULT_PINNED = listOf("crash", "printgpt", "gettargetconfig", "dumppreloader", "dumpbrom")
    }
}
