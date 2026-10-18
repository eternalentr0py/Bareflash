package com.bareflash.app.adbfb

import android.app.Application
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bareflash.app.log.AppLog
import com.bareflash.fastboot.FastbootClient
import com.bareflash.fastboot.FastbootFailException
import com.bareflash.fastboot.RebootTarget
import com.bareflash.usb.CancelToken
import com.bareflash.usb.DebugProtocol
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * State and actions for ADB / fastboot mode. Like the Samsung engine it is
 * independent of the MediaTek and Qualcomm console: it owns its own USB
 * receiver and its own connection.
 *
 * All USB traffic runs on one dedicated thread, so a protocol client is never
 * used by two callers at once. Only fastboot is wired up in this step; ADB
 * devices are detected and listed but cannot be opened yet.
 */
class AdbFbViewModel(app: Application) : AndroidViewModel(app) {

    private val ctx: Context = app.applicationContext
    private val usb = ctx.getSystemService(Context.USB_SERVICE) as UsbManager

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "adbfb-usb").apply { isDaemon = true } }
    private val io = worker.asCoroutineDispatcher()

    /** True while this mode is the visible engine; gates automatic permission prompts. */
    @Volatile var active = false
        private set

    @Volatile private var cancelRequested = false
    @Volatile private var connection: AdbFbConnection? = null
    @Volatile private var fastboot: FastbootClient? = null
    private val permissionRequested = HashSet<String>()

    private val _devices = MutableStateFlow<List<AdbFbDevice>>(emptyList())
    val devices: StateFlow<List<AdbFbDevice>> = _devices.asStateFlow()

    private val _link = MutableStateFlow(AdbFbLink())
    val link: StateFlow<AdbFbLink> = _link.asStateFlow()

    private val _busy = MutableStateFlow(false)
    /** True while a USB operation runs. The mode switcher locks while this is set. */
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _vars = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    /** `getvar all` of the connected fastboot device, sorted by name. */
    val vars: StateFlow<List<Pair<String, String>>> = _vars.asStateFlow()

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val logLines: StateFlow<List<String>> = _log.asStateFlow()

    // ---- devices and permission ------------------------------------------

    fun setActive(on: Boolean) {
        if (on == active) return
        active = on
        if (on) refreshDevices() else permissionRequested.clear()
    }

    fun refreshDevices(forcePrompt: Boolean = false) {
        val found = AdbFbUsb.scan(usb)
        _devices.value = found.map { d ->
            AdbFbDevice(
                name = d.deviceName,
                vid = d.vendorId,
                pid = d.productId,
                title = titleOf(d),
                protocols = AdbFbUsb.debugInterfaces(d).map { it.protocol }.distinct(),
                permitted = usb.hasPermission(d),
            )
        }
        if (!active) return
        for (d in found) {
            if (usb.hasPermission(d)) continue
            if (forcePrompt) permissionRequested.remove(d.deviceName)
            if (permissionRequested.add(d.deviceName)) requestPermission(d)
        }
    }

    private fun titleOf(d: UsbDevice): String =
        listOfNotNull(d.manufacturerName, d.productName)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .ifBlank { "Android device" }

    private fun requestPermission(d: UsbDevice) {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
        // Explicit package + EXTRA_DEVICE: Android 12+ refuses implicit USB permission intents.
        val intent = Intent(ACTION_PERMISSION).apply {
            setPackage(ctx.packageName)
            putExtra(UsbManager.EXTRA_DEVICE, d)
        }
        usb.requestPermission(d, PendingIntent.getBroadcast(ctx, d.deviceId, intent, flags))
    }

    // ---- connection --------------------------------------------------------

    /** Opens [protocol] on the named device and, for fastboot, reads its variables. */
    fun connect(deviceName: String, protocol: DebugProtocol) {
        if (_busy.value) return
        val device = usb.deviceList[deviceName]
        if (device == null) {
            _link.value = AdbFbLink(AdbFbLinkState.FAILED, message = "The device is no longer attached.")
            refreshDevices()
            return
        }
        if (!usb.hasPermission(device)) {
            requestPermission(device)
            return
        }
        if (protocol != DebugProtocol.FASTBOOT) {
            _link.value = AdbFbLink(
                AdbFbLinkState.IDLE,
                message = "ADB is detected, but the ADB client is still being built. Fastboot works now.",
            )
            return
        }
        launchWork {
            closeConnection()
            _link.value = AdbFbLink(AdbFbLinkState.CONNECTING, deviceName, protocol)
            try {
                val conn = AdbFbUsb.open(usb, device, protocol)
                connection = conn
                val client = FastbootClient(conn.transport, CancelToken { cancelRequested }, ::note)
                fastboot = client
                note("Connected to ${titleOf(device)} (fastboot).")
                readVars(client)
                _link.value = AdbFbLink(AdbFbLinkState.CONNECTED, deviceName, protocol)
            } catch (e: Exception) {
                fail("Connect", e)
            }
        }
    }

    /** Re-reads `getvar all`. */
    fun refreshVars() {
        if (_link.value.state != AdbFbLinkState.CONNECTED) return
        launchWork {
            val client = fastboot ?: return@launchWork
            try {
                readVars(client)
            } catch (e: Exception) {
                fail("Read variables", e)
            }
        }
    }

    /** Reboots the connected fastboot device. Not destructive: no data is touched. */
    fun reboot(target: RebootTarget) {
        if (_link.value.state != AdbFbLinkState.CONNECTED) return
        launchWork {
            val client = fastboot ?: return@launchWork
            try {
                note("Rebooting: ${target.name.lowercase()}")
                client.reboot(target)
                note("The device accepted the command and is restarting.")
                closeConnection()
                _vars.value = emptyList()
                _link.value = AdbFbLink(message = "The device is restarting. It shows up again when it is ready.")
            } catch (e: Exception) {
                fail("Reboot", e)
            }
        }
    }

    /** Closes the connection. If an operation is running it is asked to stop first. */
    fun disconnect() {
        if (_busy.value) {
            cancelRequested = true
            return
        }
        launchWork {
            closeConnection()
            _vars.value = emptyList()
            _link.value = AdbFbLink()
            note("Disconnected.")
        }
    }

    private fun readVars(client: FastbootClient) {
        val all = client.getvarAll()
        _vars.value = all.entries.sortedBy { it.key }.map { it.key to it.value }
        note("Read ${all.size} variables" + (all["product"]?.let { " (product: $it)" } ?: "") + ".")
    }

    private fun launchWork(block: () -> Unit) {
        if (!_busy.compareAndSet(false, true)) return
        cancelRequested = false
        viewModelScope.launch(io) {
            try {
                block()
            } finally {
                _busy.value = false
            }
        }
    }

    /**
     * Reports a failed operation. A FAIL answer from the device means the
     * protocol is still in step, so the link stays up. Anything else (timeout,
     * unplug, malformed reply, cancel) may have left the device mid-command, so
     * the link is dropped instead of reused.
     */
    private fun fail(what: String, e: Exception) {
        val reason = e.message ?: e.javaClass.simpleName
        AppLog.w(TAG, "$what failed: $reason", e)
        note("$what failed: $reason")
        if (e is FastbootFailException) return
        closeConnection()
        _vars.value = emptyList()
        _link.value = AdbFbLink(AdbFbLinkState.FAILED, message = "$what failed: $reason")
    }

    private fun closeConnection() {
        val c = connection
        connection = null
        fastboot = null
        if (c != null) runCatching { c.close() }
    }

    // ---- log ---------------------------------------------------------------

    private fun note(line: String) {
        AppLog.i(TAG, line)
        _log.update { (it + line).takeLast(MAX_LOG_LINES) }
    }

    fun logText(): String = _log.value.joinToString("\n")

    fun clearLog() {
        _log.value = emptyList()
    }

    // ---- USB events --------------------------------------------------------

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_PERMISSION -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    note(if (granted) "USB access granted." else "USB access was denied. Tap Grant to ask again.")
                    refreshDevices()
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> refreshDevices()
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val gone: UsbDevice? =
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                        }
                    if (gone != null) {
                        permissionRequested.remove(gone.deviceName)
                        val c = connection
                        if (c != null && c.device.deviceName == gone.deviceName) {
                            c.markDetached()
                            // A running transfer fails on its own and reports the disconnect.
                            // Closing the connection under it is what we avoid.
                            if (!_busy.value) {
                                note("The device was disconnected.")
                                closeConnection()
                                _vars.value = emptyList()
                                _link.value = AdbFbLink(message = "The device was disconnected.")
                            }
                        }
                    }
                    refreshDevices()
                }
            }
        }
    }

    init {
        val filter = IntentFilter().apply {
            addAction(ACTION_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ctx.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            ctx.registerReceiver(receiver, filter)
        }
        refreshDevices()
    }

    override fun onCleared() {
        runCatching { ctx.unregisterReceiver(receiver) }
        cancelRequested = true
        closeConnection()
        worker.shutdownNow()
        super.onCleared()
    }

    private companion object {
        const val TAG = "adbfb"
        const val ACTION_PERMISSION = "com.bareflash.app.ADBFB_USB_PERMISSION"
        const val MAX_LOG_LINES = 500
    }
}
