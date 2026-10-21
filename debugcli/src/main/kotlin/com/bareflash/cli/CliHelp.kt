package com.bareflash.cli

/** The text behind `help`. */
public object CliHelp {

    public fun overview(): String = """
        Bareflash ADB and fastboot console. Everything runs on this phone; no adb or fastboot program is involved.

        Type a command the way you would on a computer:
          adb shell ls /sdcard        run a command on the device
          adb push file.zip /sdcard/  copy to the device     adb pull /sdcard/DCIM   copy from it
          fastboot getvar all         ask the bootloader     fastboot flash boot boot.img

        The prefix is optional. With ADB connected, anything you type runs in the device shell
        (cd works and is remembered). With fastboot connected, type fastboot commands directly.

        help adb         the adb commands this console understands
        help fastboot    the fastboot commands
        help <command>   one command in detail

        Keys: Tab completes, Up and Down walk the history, Ctrl+C stops a running command, Ctrl+L clears.
        Commands that could destroy data ask for confirmation first; the riskiest ask you to type a word.
        Paths on this phone are relative to your home folder; ~ and absolute paths work too.
    """.trimIndent()

    public fun topic(word: String): String? = when (word) {
        "adb" -> """
            adb commands:
              shell [command]                run a command on the device (no command: see how it works)
              exec-out <command> [> file]    raw output; save binary output such as screencap -p
              push <local> <remote>          copy a file or folder to the device
              pull <remote> [local]          copy a file or folder from the device (default: Downloads)
              reboot [bootloader|recovery|sideload|fastboot|download|edl]
              logcat [options]               follow the system log (Ctrl+C stops)
              uninstall <package>            remove an app
              devices | get-state | get-serialno | version
            Not available here: install, root, forward, reverse, tcpip, connect. Each says what to do instead.
        """.trimIndent()
        "fastboot" -> """
            fastboot commands:
              getvar <name|all>              read bootloader variables
              flash <partition> <file>       write an image (large images are sent in pieces)
              erase <partition>
              boot <file>                    boot an image once without writing it
              reboot [bootloader|recovery|fastboot] | continue
              set_active <a|b>
              flashing <unlock|lock|unlock_critical|lock_critical|get_unlock_ability>
              oem <command>
              create-logical-partition <name> <size> | delete-logical-partition <name> | resize-logical-partition <name> <size>
            Not available here: update, flashall, format, -w. Each says what to do instead.
        """.trimIndent()
        "shell" -> "adb shell <command>: runs the command on the device. With ADB connected you can leave out \"adb shell\". Each command is a separate shell, so cd is remembered by this console, but environment variables are not. Input is not forwarded: commands that wait for typing see end of input."
        "cd" -> "cd <folder>: changes where shell commands run. cd alone goes to /, cd - to the previous folder."
        "push" -> "adb push <local> <remote>: copies a file or a whole folder to the device. If <remote> is an existing folder, the item is placed inside it."
        "pull" -> "adb pull <remote> [local]: copies a file or a whole folder from the device. Without <local> it goes to the Downloads folder."
        "flash" -> "fastboot flash <partition> <file>: overwrites the partition with the image. You must type the partition name to confirm."
        "getvar" -> "fastboot getvar <name>: prints one variable. fastboot getvar all prints every variable."
        "reboot" -> "adb reboot [target] or fastboot reboot [target]. Targets: bootloader, recovery, fastboot (userspace), and for adb also sideload, download (Samsung) and edl (Qualcomm)."
        else -> null
    }
}
