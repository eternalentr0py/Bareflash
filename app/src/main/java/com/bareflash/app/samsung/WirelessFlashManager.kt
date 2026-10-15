package com.bareflash.app.samsung

import com.bareflash.app.log.AppLog
import com.bareflash.app.log.LogLevel
import com.bareflash.app.log.LogSource
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.SocketTimeoutException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Wireless flashing: Samsung watches in wireless download mode connect to the
 * phone on [PORT]. This keeps one listener alive while wireless is enabled,
 * remembers the newest connected peer, and hands it to a flash session.
 * The listener pauses while a flash owns the connection.
 */
class WirelessFlashManager(private val scope: CoroutineScope) {

    data class State(
        val enabled: Boolean = false,
        val listening: Boolean = false,
        /** "192.168.x.y:13579" for the user to type on the watch. */
        val address: String? = null,
        val peer: String? = null,
        val error: String? = null
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val lock = Any()
    private var job: Job? = null
    private var server: ServerSocket? = null
    private var connected: BrokkrTcpTransport? = null
    private var pausedForFlash = false

    fun setEnabled(on: Boolean) {
        synchronized(lock) {
            if (on == _state.value.enabled) return
            _state.value = State(enabled = on)
            if (on) startLocked() else { stopLocked(); dropConnectedLocked() }
        }
    }

    /** Hands the live connection to a flash and pauses accepting until [finishFlashSession]. */
    fun takeConnectedTransportForFlash(): BrokkrTcpTransport? = synchronized(lock) {
        val t = connected?.takeIf { it.isConnected() } ?: return null
        pausedForFlash = true
        connected = null
        stopLocked()
        _state.value = _state.value.copy(listening = false, peer = null)
        t
    }

    fun finishFlashSession() {
        synchronized(lock) {
            pausedForFlash = false
            if (_state.value.enabled) startLocked()
        }
    }

    fun close() = synchronized(lock) {
        _state.value = State()
        stopLocked()
        dropConnectedLocked()
    }

    // ---- internals ------------------------------------------------------

    private fun startLocked() {
        if (job?.isActive == true || pausedForFlash) return
        job = scope.launch(Dispatchers.IO) { listenLoop() }
    }

    private fun stopLocked() {
        job?.cancel(); job = null
        runCatching { server?.close() }; server = null
    }

    private fun dropConnectedLocked() {
        connected?.close(); connected = null
    }

    private suspend fun listenLoop() {
        val ss = try {
            ServerSocket().apply {
                reuseAddress = true
                soTimeout = ACCEPT_POLL_MS
                bind(InetSocketAddress(PORT), BACKLOG)
            }
        } catch (t: Throwable) {
            AppLog.log(LogLevel.Error, LogSource.Samsung, "wireless", "Cannot listen on port $PORT", t)
            synchronized(lock) {
                _state.value = _state.value.copy(listening = false, error = "Cannot listen on port $PORT: ${t.message}")
            }
            return
        }
        synchronized(lock) {
            server = ss
            _state.value = _state.value.copy(
                listening = true, error = null,
                address = (hostAddress() ?: "this device") + ":" + PORT
            )
        }
        var lastHostCheck = 0L
        while (currentCoroutineContext().isActive && !ss.isClosed) {
            try {
                val sock = ss.accept()
                val transport = try { BrokkrTcpTransport.wrap(sock) } catch (t: Throwable) { runCatching { sock.close() }; continue }
                synchronized(lock) {
                    connected?.close()
                    connected = transport
                    _state.value = _state.value.copy(peer = transport.getId())
                }
            } catch (_: SocketTimeoutException) {
                // idle tick: drop a dead peer, refresh the advertised address
                synchronized(lock) {
                    val c = connected
                    if (c != null && !c.isConnected()) {
                        c.close(); connected = null
                        _state.value = _state.value.copy(peer = null)
                    }
                }
                val now = System.currentTimeMillis()
                if (now - lastHostCheck > 3000) {
                    lastHostCheck = now
                    val addr = (hostAddress() ?: "this device") + ":" + PORT
                    synchronized(lock) { if (_state.value.address != addr) _state.value = _state.value.copy(address = addr) }
                }
            } catch (_: Throwable) {
                if (ss.isClosed) break
                delay(250)
            }
        }
    }

    /** First site-local IPv4 on a Wi-Fi / hotspot style interface. */
    private fun hostAddress(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .sortedBy { if (it.name.startsWith("wlan") || it.name.startsWith("ap")) 0 else 1 }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }
            ?.hostAddress
    }.getOrNull()

    companion object {
        const val PORT = 13579
        private const val BACKLOG = 4
        private const val ACCEPT_POLL_MS = 500
    }
}
