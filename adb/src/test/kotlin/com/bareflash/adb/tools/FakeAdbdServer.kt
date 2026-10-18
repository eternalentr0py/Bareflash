package com.bareflash.adb.tools

import com.bareflash.adb.AdbWire
import com.bareflash.adb.FakeAdbdCore
import com.bareflash.adb.FakeFs
import com.bareflash.adb.AdbMessage
import com.bareflash.adb.ShellOutcome
import java.io.DataInputStream
import java.net.ServerSocket
import java.net.Socket

/**
 * Serves the fake adbd over TCP so the real `adb` host binary can be pointed
 * at it (`adb connect 127.0.0.1:<port>`). Used by tools/interop-real-adb.sh;
 * it is not part of the unit tests.
 *
 * Usage: FakeAdbdServer <port> [auth]
 */
fun main(args: Array<String>) {
    val port = args.getOrNull(0)?.toInt() ?: 6000
    val auth = args.getOrNull(1) == "auth"
    val fs = FakeFs().also {
        it.put("/sdcard/hello.txt", "hello from the fake device\n".toByteArray())
        it.put("/sdcard/big.bin", ByteArray(300_000) { i -> (i * 31 + 7).toByte() })
        it.put("/sdcard/Docs/a.txt", "a".toByteArray())
    }
    val keys = java.util.Collections.synchronizedList(ArrayList<java.security.PublicKey>())
    val server = ServerSocket(port, 8, java.net.InetAddress.getLoopbackAddress())
    println("fake adbd listening on 127.0.0.1:$port auth=$auth")
    System.out.flush()
    while (true) {
        val s = server.accept()
        Thread { serve(s, fs, auth, keys) }.apply { isDaemon = true }.start()
    }
}

private fun handler(cmd: String): ShellOutcome = when {
    cmd.startsWith("exit ") -> ShellOutcome(exit = cmd.removePrefix("exit ").trim().toInt())
    cmd.startsWith("echo ") -> ShellOutcome(stdout = (cmd.removePrefix("echo ") + "\n").toByteArray())
    cmd.startsWith("err ") -> ShellOutcome(stderr = (cmd.removePrefix("err ") + "\n").toByteArray(), exit = 1)
    cmd == "getprop ro.product.model" -> ShellOutcome(stdout = "Fake Phone\n".toByteArray())
    else -> ShellOutcome(stdout = "ran: $cmd\n".toByteArray())
}

private val trace = System.getenv("FAKE_ADBD_TRACE") == "1"

private fun serve(socket: Socket, fs: FakeFs, auth: Boolean, keys: MutableList<java.security.PublicKey>) {
    val core = FakeAdbdCore(fs = fs, requireAuth = auth, shellHandler = ::handler, authorizedKeys = keys)
    try {
        val input = DataInputStream(socket.getInputStream().buffered())
        val out = socket.getOutputStream()
        while (true) {
            val hb = ByteArray(AdbWire.HEADER_SIZE)
            input.readFully(hb)
            val h = AdbWire.decodeHeader(hb, 16 * 1024 * 1024)
            val payload = ByteArray(h.length).also { input.readFully(it) }
            val inMsg = AdbMessage(h.command, h.arg0, h.arg1, payload)
            if (trace) println("<- $inMsg")
            for (r in core.onMessage(inMsg)) {
                if (trace) println("-> $r")
                out.write(AdbWire.encodeHeader(r, false))
                out.write(r.data)
                out.flush()
            }
        }
    } catch (_: java.io.IOException) {
        // peer went away
    } catch (e: Exception) {
        println("server error: $e")
        e.printStackTrace(System.out)
    } finally {
        socket.close()
    }
}
