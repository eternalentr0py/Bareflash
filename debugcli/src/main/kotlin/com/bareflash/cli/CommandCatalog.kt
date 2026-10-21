package com.bareflash.cli

import com.bareflash.usb.DebugProtocol

public enum class ArgKind { TEXT, NUMBER, LOCAL_PATH, REMOTE_PATH, PACKAGE, PARTITION, CHOICE }

/** One blank to fill in on a [CatalogCommand]. */
public class CatalogArg(
    public val name: String,
    public val label: String,
    public val kind: ArgKind = ArgKind.TEXT,
    public val hint: String = "",
    public val default: String = "",
    public val choices: List<String> = emptyList(),
    public val optional: Boolean = false,
)

/**
 * A ready-made command for the Commands tab. [template] is a normal command
 * line with `{name}` blanks; a value is inserted as one safely quoted word, and
 * `{name!}` inserts it as typed. [risk] is the level shown on the card; a
 * test checks it against [RiskAssessor] so the two cannot drift apart.
 */
public class CatalogCommand(
    public val id: String,
    public val category: String,
    public val title: String,
    public val description: String,
    public val requires: DebugProtocol,
    public val template: String,
    public val args: List<CatalogArg> = emptyList(),
    public val risk: RiskLevel = RiskLevel.SAFE,
)

public sealed interface FillResult {
    public data class Ok(val line: String) : FillResult
    public data class Missing(val label: String) : FillResult
    public data class Invalid(val label: String, val why: String) : FillResult
}

public object TemplateFiller {
    private val PLACEHOLDER = Regex("\\{([a-z0-9_]+)(!?)}")
    private val NUMBER = Regex("-?\\d+")

    /** Substitutes [values] (by arg name) into the template, using each arg's default for a blank. */
    public fun fill(c: CatalogCommand, values: Map<String, String>): FillResult {
        val sb = StringBuilder()
        var last = 0
        for (m in PLACEHOLDER.findAll(c.template)) {
            sb.append(c.template, last, m.range.first)
            last = m.range.last + 1
            val arg = c.args.firstOrNull { it.name == m.groupValues[1] }
                ?: throw IllegalStateException("Template of ${c.id} uses unknown blank {${m.groupValues[1]}}")
            val value = (values[arg.name] ?: "").trim().ifEmpty { arg.default }
            if (value.isEmpty()) {
                if (!arg.optional) return FillResult.Missing(arg.label)
                if (sb.isNotEmpty() && sb.last() == ' ') sb.setLength(sb.length - 1)
                continue
            }
            if ('\n' in value || '\r' in value || '\u0000' in value) return FillResult.Invalid(arg.label, "must be a single line")
            if (arg.kind == ArgKind.NUMBER && !NUMBER.matches(value)) return FillResult.Invalid(arg.label, "must be a number")
            if (arg.kind == ArgKind.CHOICE && value !in arg.choices) return FillResult.Invalid(arg.label, "must be one of ${arg.choices.joinToString(", ")}")
            sb.append(if (m.groupValues[2] == "!") value else CommandLine.quote(value))
        }
        sb.append(c.template, last, c.template.length)
        return FillResult.Ok(sb.toString().trim())
    }
}

public object CommandCatalog {

    public fun forProtocol(p: DebugProtocol): List<CatalogCommand> = ALL.filter { it.requires == p }

    /** Category names in the order they first appear. */
    public fun categories(p: DebugProtocol): List<String> = forProtocol(p).map { it.category }.distinct()

    private fun adb(id: String, cat: String, title: String, desc: String, template: String, args: List<CatalogArg> = emptyList(), risk: RiskLevel = RiskLevel.SAFE) =
        CatalogCommand(id, cat, title, desc, DebugProtocol.ADB, template, args, risk)

    private fun fb(id: String, cat: String, title: String, desc: String, template: String, args: List<CatalogArg> = emptyList(), risk: RiskLevel = RiskLevel.SAFE) =
        CatalogCommand(id, cat, title, desc, DebugProtocol.FASTBOOT, template, args, risk)

    private fun arg(
        name: String, label: String, kind: ArgKind = ArgKind.TEXT, hint: String = "", default: String = "",
        choices: List<String> = emptyList(), optional: Boolean = false,
    ) = CatalogArg(name, label, kind, hint, default, choices, optional)

    private val PACKAGE = arg("package", "Package name", ArgKind.PACKAGE, "com.example.app")
    private val REMOTE = arg("path", "Folder or file on the device", ArgKind.REMOTE_PATH, "/sdcard", "/sdcard")
    private val PARTITION = arg("partition", "Partition", ArgKind.PARTITION, "boot")
    private val DANGER = RiskLevel.DANGEROUS
    private val CAUTION = RiskLevel.CAUTION

    public val ALL: List<CatalogCommand> = listOf(
        // ---- ADB: device
        adb("adb-getprop", "Device", "All properties", "Every system property: model, build, hardware, and more.", "adb shell getprop"),
        adb("adb-android-version", "Device", "Android version", "The Android release the device runs.", "adb shell getprop ro.build.version.release"),
        adb("adb-model", "Device", "Model name", "The marketing name of the device.", "adb shell getprop ro.product.model"),
        adb("adb-battery", "Device", "Battery status", "Level, charging state, temperature and health.", "adb shell dumpsys battery"),
        adb("adb-storage", "Device", "Storage usage", "Free and used space on each filesystem.", "adb shell df -h"),
        adb("adb-memory", "Device", "Memory", "RAM totals and use.", "adb shell cat /proc/meminfo"),
        adb("adb-cpu", "Device", "CPU info", "Processor details.", "adb shell cat /proc/cpuinfo"),
        adb("adb-processes", "Device", "Running processes", "Every process on the device.", "adb shell ps -A"),
        adb("adb-ip", "Device", "Network addresses", "IP addresses of the device's interfaces.", "adb shell ip addr"),
        adb("adb-uptime", "Device", "Uptime", "How long the device has been running.", "adb shell uptime"),

        // ---- ADB: apps
        adb("adb-apps-user", "Apps", "Installed apps (yours)", "Apps you installed, not the system ones.", "adb shell pm list packages -3"),
        adb("adb-apps-all", "Apps", "All installed apps", "Every package, system apps included.", "adb shell pm list packages"),
        adb("adb-app-find", "Apps", "Find an app", "Packages whose name contains the text.", "adb shell pm list packages {filter}", listOf(arg("filter", "Part of the name", hint = "chrome"))),
        adb("adb-app-path", "Apps", "Where an app is stored", "The APK path of a package.", "adb shell pm path {package}", listOf(PACKAGE)),
        adb("adb-app-info", "Apps", "App details", "Version, permissions and install info for a package.", "adb shell dumpsys package {package}", listOf(PACKAGE)),
        adb("adb-app-launch", "Apps", "Launch an app", "Starts the app's launcher screen.", "adb shell monkey -p {package} -c android.intent.category.LAUNCHER 1", listOf(PACKAGE)),
        adb("adb-app-stop", "Apps", "Force stop an app", "Stops the app's processes.", "adb shell am force-stop {package}", listOf(PACKAGE)),
        adb("adb-app-clear", "Apps", "Clear an app's data", "Deletes the app's data and settings, like a fresh install.", "adb shell pm clear {package}", listOf(PACKAGE), CAUTION),
        adb("adb-app-uninstall", "Apps", "Uninstall an app", "Removes the app and its data.", "adb shell pm uninstall {package}", listOf(PACKAGE), CAUTION),
        adb("adb-app-disable", "Apps", "Disable an app", "Hides the app for the current user without uninstalling it.", "adb shell pm disable-user --user 0 {package}", listOf(PACKAGE), CAUTION),
        adb("adb-app-enable", "Apps", "Enable an app", "Turns a disabled app back on.", "adb shell pm enable {package}", listOf(PACKAGE)),
        adb("adb-app-grant", "Apps", "Grant a permission", "Gives the app a runtime permission.", "adb shell pm grant {package} {permission}", listOf(PACKAGE, arg("permission", "Permission", hint = "android.permission.CAMERA"))),
        adb("adb-app-revoke", "Apps", "Revoke a permission", "Takes a runtime permission away from the app.", "adb shell pm revoke {package} {permission}", listOf(PACKAGE, arg("permission", "Permission", hint = "android.permission.CAMERA")), CAUTION),

        // ---- ADB: files
        adb("adb-ls", "Files", "List a folder", "Names, sizes and permissions.", "adb shell ls -la {path}", listOf(REMOTE)),
        adb("adb-du", "Files", "Size of a folder", "Total space used by a folder.", "adb shell du -sh {path}", listOf(REMOTE)),
        adb("adb-cat", "Files", "Show a file", "Prints a text file.", "adb shell cat {path}", listOf(arg("path", "File on the device", ArgKind.REMOTE_PATH, "/sdcard/notes.txt"))),
        adb("adb-mkdir", "Files", "Make a folder", "Creates a folder and any missing parents.", "adb shell mkdir -p {path}", listOf(arg("path", "New folder", ArgKind.REMOTE_PATH, "/sdcard/NewFolder"))),
        adb("adb-rm", "Files", "Delete a file", "Deletes one file permanently.", "adb shell rm {path}", listOf(arg("path", "File on the device", ArgKind.REMOTE_PATH, "/sdcard/old.txt")), CAUTION),
        adb("adb-rm-r", "Files", "Delete a folder", "Deletes a folder and everything in it, permanently.", "adb shell rm -r {path}", listOf(arg("path", "Folder on the device", ArgKind.REMOTE_PATH, "/sdcard/OldFolder")), DANGER),
        adb("adb-pull", "Files", "Copy from the device to this phone", "Copies a file or folder. Leave the destination empty to use Downloads.", "adb pull {remote} {local}",
            listOf(arg("remote", "On the device", ArgKind.REMOTE_PATH, "/sdcard/DCIM"), arg("local", "On this phone", ArgKind.LOCAL_PATH, "Download/", optional = true))),
        adb("adb-push", "Files", "Copy from this phone to the device", "Copies a file or folder to the device.", "adb push {local} {remote}",
            listOf(arg("local", "On this phone", ArgKind.LOCAL_PATH, "Download/file.zip"), arg("remote", "On the device", ArgKind.REMOTE_PATH, "/sdcard/Download/", "/sdcard/Download/"))),

        // ---- ADB: screen and input
        adb("adb-screenshot", "Screen & input", "Screenshot", "Saves a PNG of the device's screen on this phone.", "adb exec-out screencap -p > {file}", listOf(arg("file", "Save as", ArgKind.LOCAL_PATH, "screenshot.png", "screenshot.png"))),
        adb("adb-key-home", "Screen & input", "Press Home", "", "adb shell input keyevent KEYCODE_HOME"),
        adb("adb-key-back", "Screen & input", "Press Back", "", "adb shell input keyevent KEYCODE_BACK"),
        adb("adb-key-recents", "Screen & input", "Press Recents", "", "adb shell input keyevent KEYCODE_APP_SWITCH"),
        adb("adb-key-power", "Screen & input", "Press Power", "Turns the screen on or off.", "adb shell input keyevent KEYCODE_POWER"),
        adb("adb-key-wake", "Screen & input", "Wake the screen", "", "adb shell input keyevent KEYCODE_WAKEUP"),
        adb("adb-key-volume-up", "Screen & input", "Volume up", "", "adb shell input keyevent KEYCODE_VOLUME_UP"),
        adb("adb-key-volume-down", "Screen & input", "Volume down", "", "adb shell input keyevent KEYCODE_VOLUME_DOWN"),
        adb("adb-tap", "Screen & input", "Tap the screen", "Taps at a pixel position.", "adb shell input tap {x} {y}", listOf(arg("x", "X", ArgKind.NUMBER, "540"), arg("y", "Y", ArgKind.NUMBER, "960"))),
        adb("adb-swipe", "Screen & input", "Swipe", "Swipes between two pixel positions.", "adb shell input swipe {x1} {y1} {x2} {y2} {ms}",
            listOf(arg("x1", "From X", ArgKind.NUMBER, "540"), arg("y1", "From Y", ArgKind.NUMBER, "1500"), arg("x2", "To X", ArgKind.NUMBER, "540"), arg("y2", "To Y", ArgKind.NUMBER, "500"), arg("ms", "Duration (ms)", ArgKind.NUMBER, "300", "300"))),
        adb("adb-text", "Screen & input", "Type text", "Types into the focused field. Use %s for a space.", "adb shell input text {text}", listOf(arg("text", "Text", hint = "hello%sworld"))),
        adb("adb-wm-size", "Screen & input", "Screen size", "", "adb shell wm size"),
        adb("adb-wm-density", "Screen & input", "Screen density", "", "adb shell wm density"),
        adb("adb-brightness", "Screen & input", "Set brightness", "0 to 255.", "adb shell settings put system screen_brightness {value}", listOf(arg("value", "Brightness", ArgKind.NUMBER, "128")), CAUTION),

        // ---- ADB: logs
        adb("adb-logcat-last", "Logs", "Recent log lines", "The last lines of the system log.", "adb shell logcat -d -t {lines}", listOf(arg("lines", "How many lines", ArgKind.NUMBER, "200", "200"))),
        adb("adb-logcat-errors", "Logs", "Recent errors", "Errors from the system log.", "adb shell logcat -d '*:E'"),
        adb("adb-logcat-live", "Logs", "Live log", "Follows the system log until you stop it.", "adb logcat"),
        adb("adb-logcat-clear", "Logs", "Clear the log", "Empties the log buffer.", "adb shell logcat -c"),
        adb("adb-dmesg", "Logs", "Kernel log", "Kernel messages. Some devices need root for this.", "adb shell dmesg"),

        // ---- ADB: power
        adb("adb-reboot", "Power", "Reboot", "Restarts the device.", "adb reboot"),
        adb("adb-reboot-bootloader", "Power", "Reboot to bootloader", "Restarts into fastboot mode.", "adb reboot bootloader"),
        adb("adb-reboot-recovery", "Power", "Reboot to recovery", "", "adb reboot recovery"),
        adb("adb-reboot-fastbootd", "Power", "Reboot to fastbootd", "Userspace fastboot, for logical partitions.", "adb reboot fastboot"),
        adb("adb-reboot-download", "Power", "Reboot to download mode", "Samsung download (Odin) mode. Only some devices honour this.", "adb reboot download"),
        adb("adb-reboot-edl", "Power", "Reboot to EDL", "Qualcomm emergency download mode. Only some devices honour this.", "adb reboot edl"),
        adb("adb-wifi", "Power", "Wi-Fi on or off", "Switches the device's Wi-Fi.", "adb shell svc wifi {state}", listOf(arg("state", "State", ArgKind.CHOICE, choices = listOf("enable", "disable"), default = "enable")), CAUTION),

        // ---- Fastboot: info
        fb("fb-getvar-all", "Info", "All variables", "Everything the bootloader reports.", "fastboot getvar all"),
        fb("fb-product", "Info", "Product", "", "fastboot getvar product"),
        fb("fb-serial", "Info", "Serial number", "", "fastboot getvar serialno"),
        fb("fb-slot", "Info", "Current slot", "The slot the device boots from (A/B devices).", "fastboot getvar current-slot"),
        fb("fb-unlocked", "Info", "Bootloader unlocked?", "", "fastboot getvar unlocked"),
        fb("fb-userspace", "Info", "Userspace fastboot?", "yes means fastbootd, no means the bootloader.", "fastboot getvar is-userspace"),
        fb("fb-max-download", "Info", "Largest download", "How big one transfer to the device can be.", "fastboot getvar max-download-size"),

        // ---- Fastboot: reboot
        fb("fb-reboot", "Reboot", "Reboot", "Restarts into Android.", "fastboot reboot"),
        fb("fb-reboot-bootloader", "Reboot", "Reboot to bootloader", "", "fastboot reboot bootloader"),
        fb("fb-reboot-recovery", "Reboot", "Reboot to recovery", "", "fastboot reboot recovery"),
        fb("fb-reboot-fastbootd", "Reboot", "Reboot to fastbootd", "", "fastboot reboot fastboot"),
        fb("fb-continue", "Reboot", "Continue boot", "Leaves fastboot and boots normally.", "fastboot continue"),

        // ---- Fastboot: flash
        fb("fb-flash", "Flash", "Flash a partition", "Writes an image file to a partition. This overwrites what is there.", "fastboot flash {partition} {file}",
            listOf(PARTITION, arg("file", "Image file on this phone", ArgKind.LOCAL_PATH, "Download/boot.img")), DANGER),
        fb("fb-boot", "Flash", "Boot an image once", "Boots an image from this phone without writing it to flash.", "fastboot boot {file}", listOf(arg("file", "Image file on this phone", ArgKind.LOCAL_PATH, "Download/boot.img")), CAUTION),
        fb("fb-erase", "Flash", "Erase a partition", "Erases a partition. This cannot be undone.", "fastboot erase {partition}", listOf(PARTITION), DANGER),
        fb("fb-set-active", "Flash", "Set the active slot", "Chooses the slot to boot from (A/B devices).", "fastboot set_active {slot}", listOf(arg("slot", "Slot", ArgKind.CHOICE, choices = listOf("a", "b"), default = "a")), CAUTION),

        // ---- Fastboot: bootloader
        fb("fb-unlock", "Bootloader", "Unlock the bootloader", "Erases all user data and can void the warranty.", "fastboot flashing unlock", risk = DANGER),
        fb("fb-lock", "Bootloader", "Lock the bootloader", "Erases user data. A device with modified software may not boot afterwards.", "fastboot flashing lock", risk = DANGER),
        fb("fb-unlock-critical", "Bootloader", "Unlock critical partitions", "Allows flashing the bootloader itself. Erases user data.", "fastboot flashing unlock_critical", risk = DANGER),
        fb("fb-lock-critical", "Bootloader", "Lock critical partitions", "Erases user data.", "fastboot flashing lock_critical", risk = DANGER),
        fb("fb-unlock-ability", "Bootloader", "Can it be unlocked?", "Asks whether OEM unlocking is allowed.", "fastboot flashing get_unlock_ability"),
        fb("fb-oem", "Bootloader", "OEM command", "A vendor-specific command, sent as you type it.", "fastboot oem {command!}", listOf(arg("command", "Command", hint = "device-info")), DANGER),

        // ---- Fastboot: logical partitions
        fb("fb-create-logical", "Logical partitions", "Create a logical partition", "Needs fastbootd.", "fastboot create-logical-partition {name} {size}", listOf(arg("name", "Name", hint = "system_b"), arg("size", "Size in bytes", ArgKind.NUMBER, "0", "0")), CAUTION),
        fb("fb-delete-logical", "Logical partitions", "Delete a logical partition", "Needs fastbootd. Deletes the data in it.", "fastboot delete-logical-partition {name}", listOf(arg("name", "Name", hint = "system_b")), DANGER),
        fb("fb-resize-logical", "Logical partitions", "Resize a logical partition", "Needs fastbootd. Can destroy the data in it.", "fastboot resize-logical-partition {name} {size}", listOf(arg("name", "Name", hint = "system_b"), arg("size", "Size in bytes", ArgKind.NUMBER, "0")), DANGER),
    )
}
