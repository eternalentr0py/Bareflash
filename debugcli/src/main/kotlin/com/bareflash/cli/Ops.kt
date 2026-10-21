package com.bareflash.cli

import com.bareflash.adb.AdbCancelledException
import com.bareflash.adb.AdbClosedException
import com.bareflash.adb.AdbConnection
import com.bareflash.adb.AdbDeviceInfo
import com.bareflash.adb.AdbRebootTarget
import com.bareflash.adb.AdbTimeoutException
import com.bareflash.adb.RemoteFile
import com.bareflash.adb.RemoteFileStat
import com.bareflash.adb.ShellEvent
import com.bareflash.adb.ShellResult
import com.bareflash.adb.openLegacyShell
import com.bareflash.adb.openShell
import com.bareflash.adb.openSync
import com.bareflash.adb.reboot
import com.bareflash.adb.shell
import com.bareflash.fastboot.ByteSource
import com.bareflash.fastboot.FastbootClient
import com.bareflash.fastboot.FlashProgress
import com.bareflash.fastboot.FlashingCommand
import com.bareflash.fastboot.RebootTarget
import com.bareflash.usb.CancelToken
import java.io.InputStream
import java.io.OutputStream

/**
 * What the command runner needs from an ADB connection. [NativeAdbOps] is the
 * real thing; tests can supply a simple fake, and the runner's logic (working
 * directory, recursive copies, error handling) is tested through this seam.
 */
public interface AdbOps {
    public val info: AdbDeviceInfo

    /**
     * Runs [command] and streams its output. Returns the exit code, or null if
     * the device cannot report one. Throws [AdbCancelledException] when
     * [cancel] fires. Stdin is closed at once, so commands that read it see end of file.
     */
    public fun runShell(command: String, cancel: CancelToken, onStdout: (ByteArray) -> Unit, onStderr: (ByteArray) -> Unit): Int?

    /** Raw output of [command] without a terminal or exit code. */
    public fun execRaw(command: String, cancel: CancelToken, onData: (ByteArray) -> Unit)

    /** Runs a short command and collects its output. */
    public fun shell(command: String): ShellResult

    public fun stat(path: String): RemoteFileStat?
    public fun list(path: String): List<RemoteFile>
    public fun push(input: InputStream, remotePath: String, mode: Int, mtimeSeconds: Long, cancel: CancelToken, onProgress: (Long) -> Unit): Long
    public fun pull(remotePath: String, output: OutputStream, cancel: CancelToken, onProgress: (Long) -> Unit): Long
    public fun reboot(target: AdbRebootTarget)
}

public class NativeAdbOps(private val connection: AdbConnection) : AdbOps {
    override val info: AdbDeviceInfo get() = connection.info

    override fun runShell(command: String, cancel: CancelToken, onStdout: (ByteArray) -> Unit, onStderr: (ByteArray) -> Unit): Int? {
        if (connection.supports("shell_v2")) {
            connection.openShell(command).use { session ->
                try {
                    session.closeStdin()
                } catch (_: AdbClosedException) {
                    // A quick command may already have finished and closed the stream. Its output
                    // is still queued, so keep reading; a real link failure surfaces on the next read.
                }
                while (true) {
                    if (cancel.isCancelled()) throw AdbCancelledException()
                    val event = try {
                        session.readEvent(POLL_MS)
                    } catch (_: AdbTimeoutException) {
                        continue
                    } ?: return null
                    when (event) {
                        is ShellEvent.Stdout -> onStdout(event.data)
                        is ShellEvent.Stderr -> onStderr(event.data)
                        is ShellEvent.Exit -> return event.code
                    }
                }
            }
        }
        connection.openLegacyShell(command).use { stream ->
            val buf = ByteArray(16 * 1024)
            while (true) {
                if (cancel.isCancelled()) throw AdbCancelledException()
                val n = try {
                    stream.read(buf, 0, buf.size, POLL_MS)
                } catch (_: AdbTimeoutException) {
                    continue
                }
                if (n < 0) return null
                onStdout(buf.copyOf(n))
            }
        }
    }

    override fun execRaw(command: String, cancel: CancelToken, onData: (ByteArray) -> Unit) {
        connection.open("exec:$command").use { stream ->
            val buf = ByteArray(16 * 1024)
            while (true) {
                if (cancel.isCancelled()) throw AdbCancelledException()
                val n = try {
                    stream.read(buf, 0, buf.size, POLL_MS)
                } catch (_: AdbTimeoutException) {
                    continue
                }
                if (n < 0) return
                onData(buf.copyOf(n))
            }
        }
    }

    override fun shell(command: String): ShellResult = connection.shell(command)

    override fun stat(path: String): RemoteFileStat? = connection.openSync().use { it.stat(path) }

    override fun list(path: String): List<RemoteFile> = connection.openSync().use { it.list(path) }

    override fun push(input: InputStream, remotePath: String, mode: Int, mtimeSeconds: Long, cancel: CancelToken, onProgress: (Long) -> Unit): Long =
        connection.openSync().use { it.push(input, remotePath, mode, mtimeSeconds, cancel, onProgress) }

    override fun pull(remotePath: String, output: OutputStream, cancel: CancelToken, onProgress: (Long) -> Unit): Long =
        connection.openSync().use { it.pull(remotePath, output, cancel, onProgress) }

    override fun reboot(target: AdbRebootTarget) {
        connection.reboot(target)
    }

    private companion object {
        const val POLL_MS = 300L
    }
}

/** What the command runner needs from a fastboot connection. */
public interface FastbootOps {
    public fun getvarAll(): Map<String, String>

    /** Null if the device does not know the variable. */
    public fun getvar(name: String): String?
    public fun flash(partition: String, image: ByteSource, progress: FlashProgress)
    public fun erase(partition: String)
    public fun reboot(target: RebootTarget)
    public fun setActive(slot: String)
    public fun oem(args: String): FastbootClient.Result
    public fun flashing(command: FlashingCommand): FastbootClient.Result
    public fun boot(image: ByteSource)
    public fun continueBoot()
    public fun createLogicalPartition(name: String, sizeBytes: Long): FastbootClient.Result
    public fun deleteLogicalPartition(name: String): FastbootClient.Result
    public fun resizeLogicalPartition(name: String, sizeBytes: Long): FastbootClient.Result
}

public class NativeFastbootOps(private val client: FastbootClient) : FastbootOps {
    override fun getvarAll(): Map<String, String> = client.getvarAll()
    override fun getvar(name: String): String? = client.getvarOrNull(name)
    override fun flash(partition: String, image: ByteSource, progress: FlashProgress): Unit = client.flash(partition, image, progress = progress)
    override fun erase(partition: String): Unit = client.erase(partition)
    override fun reboot(target: RebootTarget): Unit = client.reboot(target)
    override fun setActive(slot: String): Unit = client.setActive(slot)
    override fun oem(args: String): FastbootClient.Result = client.oem(args)
    override fun flashing(command: FlashingCommand): FastbootClient.Result = client.flashing(command)
    override fun boot(image: ByteSource): Unit = client.boot(image)
    override fun continueBoot(): Unit = client.continueBoot()
    override fun createLogicalPartition(name: String, sizeBytes: Long): FastbootClient.Result = client.createLogicalPartition(name, sizeBytes)
    override fun deleteLogicalPartition(name: String): FastbootClient.Result = client.deleteLogicalPartition(name)
    override fun resizeLogicalPartition(name: String, sizeBytes: Long): FastbootClient.Result = client.resizeLogicalPartition(name, sizeBytes)
}
