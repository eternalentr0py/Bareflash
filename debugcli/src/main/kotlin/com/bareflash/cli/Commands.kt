package com.bareflash.cli

import com.bareflash.adb.AdbRebootTarget
import com.bareflash.fastboot.FlashingCommand
import com.bareflash.fastboot.RebootTarget

/** A typed line, understood. Executing it is [CommandRunner]'s job. */
public sealed interface DeviceCommand {

    // ---- ADB ---------------------------------------------------------------

    /** Runs [command] in the device shell. Empty means "adb shell" with nothing after it. */
    public data class AdbShell(val command: String) : DeviceCommand

    /** Raw, binary-safe output of [command], printed or written to [saveTo] on this phone. */
    public data class AdbExecOut(val command: String, val saveTo: String?) : DeviceCommand

    public data class AdbPush(val local: String, val remote: String) : DeviceCommand
    public data class AdbPull(val remote: String, val local: String?) : DeviceCommand
    public data class AdbReboot(val target: AdbRebootTarget) : DeviceCommand
    public data class AdbInfo(val what: Info) : DeviceCommand {
        public enum class Info { DEVICES, GET_STATE, GET_SERIALNO, VERSION, NO_SERVER }
    }

    // ---- Fastboot ------------------------------------------------------------

    public data class FbGetvar(val name: String) : DeviceCommand
    public data object FbDevices : DeviceCommand
    public data class FbFlash(val partition: String, val path: String) : DeviceCommand
    public data class FbErase(val partition: String) : DeviceCommand
    public data class FbReboot(val target: RebootTarget) : DeviceCommand
    public data class FbSetActive(val slot: String) : DeviceCommand
    public data class FbOem(val args: String) : DeviceCommand
    public data class FbFlashing(val command: FlashingCommand) : DeviceCommand
    public data class FbBoot(val path: String) : DeviceCommand
    public data object FbContinue : DeviceCommand
    public data object FbVersion : DeviceCommand
    public data class FbLogical(val op: Op, val name: String, val sizeBytes: Long?) : DeviceCommand {
        public enum class Op { CREATE, DELETE, RESIZE }
    }

    // ---- Both ---------------------------------------------------------------

    /** A real adb/fastboot command that this client does not provide, with what to do instead. */
    public data class Unsupported(val tool: String, val verb: String, val hint: String) : DeviceCommand
}
