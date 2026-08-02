/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.integrations.adb

import android.content.Context
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.utils.AppLogger
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Measures WHY arming the classic-tcpip loopback listener fails, on devices where it does.
 *
 * **Why this exists.** Issue #22 (Galaxy S25, One UI 8.5 / Android 16) shows three arm attempts across
 * two calls, all ending `Loopback arm result = false`, and `service.adb.tcp.port` never holding our
 * port afterwards. From the app's own log that single "false" is the end of the story — it cannot say
 * whether adbd refused to set the property, set it but needed longer than our 2 s to start listening,
 * or started listening on a socket that then rejected our key. Those three have completely different
 * fixes, so the build that ships to a reporter has to be able to tell them apart.
 *
 * **The measurements, and what each one rules out:**
 *  - `service.adb.tcp.port` read directly, before and repeatedly after the arm — separates "adbd never
 *    took the request" from "adbd took it and is slow".
 *  - A **raw TCP connect** to the port, with no ADB handshake — separates "nothing is listening" from
 *    "listening, but the CNXN/AUTH handshake fails". [AdbShell.connectLoopback] conflates the two: it
 *    reports the same failure for a closed port and for a rejected key.
 *  - `setprop` on the property from our shell, read back — answers whether One UI blocks writing it at
 *    all. If the write sticks, arming has a second route that does not depend on the `tcpip:` service:
 *    set the property, then let the next adbd restart pick it up.
 *
 * Everything here is read-only with respect to recording and best-effort: any failure is logged and
 * ignored. It only runs when the user has debug logging switched on, because that is the only time
 * `AppLogger` writes anywhere.
 */
object LoopbackDiagnostics {

    private const val TAG = "CV:LoopbackDiag"

    /** The property adbd consults on startup to decide whether to listen on TCP, and on which port. */
    private const val TCP_PORT_PROP = "service.adb.tcp.port"

    /** A raw connect to a local port either answers immediately or not at all; no need to wait long. */
    private const val RAW_PROBE_TIMEOUT_MS = 400

    /** How long to keep watching for the listener after firing the arm, and how often to look. */
    private const val WATCH_BUDGET_MS = 12_000L
    private const val WATCH_INTERVAL_MS = 500L

    /** Hard cap on one diagnostic shell command, so a wedged stream can never stall a recording. */
    private const val SHELL_CAP_MS = 2_000L

    /**
     * Logs the ADB-transport state as one line: the three settings that decide which transports exist,
     * and whether anything is actually listening on our loopback port.
     *
     * @param label where in the arm sequence this snapshot was taken, so the log reads as a timeline.
     */
    fun snapshot(context: Context, label: String) {
        val port = AppPreferences(context).getLoopbackAdbPort()
        AppLogger.i(
            TAG,
            "[$label] $TCP_PORT_PROP='${systemProperty(TCP_PORT_PROP)}' " +
                "wanted=$port rawPortOpen=${isRawPortOpen(port)} " +
                "adb_enabled=${AdbShell.isUsbDebuggingEnabled(context)} " +
                "adb_wifi_enabled=${AdbShell.isWirelessDebuggingEnabled(context)}"
        )
    }

    /**
     * Watches for the listener to appear after the arm was fired, and reports the first moment each
     * signal turns true.
     *
     * Two signals, deliberately: the property is what adbd was *asked* for, the open socket is what it
     * actually *did*. A run where the property flips and the socket never opens is a different bug from
     * one where neither ever moves, and only the timeline distinguishes them.
     *
     * @return true if a raw connect to the port succeeded within the budget.
     */
    fun watchForListener(context: Context): Boolean {
        val port = AppPreferences(context).getLoopbackAdbPort()
        val started = android.os.SystemClock.elapsedRealtime()
        var propSeenAtMs = -1L
        var socketSeenAtMs = -1L

        while (android.os.SystemClock.elapsedRealtime() - started < WATCH_BUDGET_MS) {
            val elapsed = android.os.SystemClock.elapsedRealtime() - started
            if (propSeenAtMs < 0 && systemProperty(TCP_PORT_PROP) == port.toString()) {
                propSeenAtMs = elapsed
                AppLogger.i(TAG, "watch: $TCP_PORT_PROP became '$port' after ${elapsed}ms")
            }
            if (isRawPortOpen(port)) {
                socketSeenAtMs = elapsed
                AppLogger.i(TAG, "watch: port $port accepted a raw TCP connect after ${elapsed}ms")
                break
            }
            Thread.sleep(WATCH_INTERVAL_MS)
        }

        if (socketSeenAtMs < 0) {
            AppLogger.w(
                TAG,
                "watch: nothing listening on $port after ${WATCH_BUDGET_MS}ms " +
                    "(property ${if (propSeenAtMs >= 0) "DID flip at ${propSeenAtMs}ms" else "never flipped"}, " +
                    "final value='${systemProperty(TCP_PORT_PROP)}')"
            )
        }
        return socketSeenAtMs >= 0
    }

    /**
     * Asks the device, through our own shell, what it thinks of the tcpip property — while a connection
     * is still up, because firing the arm drops it.
     *
     * The `setprop` write is the interesting half. `service.adb.tcp.port` normally lives in a property
     * context the shell user may write; if One UI has moved it out of reach, that alone explains why
     * arming can never take on this device, and it is invisible from the app side.
     *
     * Safe to run: setting the property does not by itself open a port (adbd reads it on startup), and
     * the value written is the same port the arm is about to request anyway.
     */
    fun shellProbe(context: Context) {
        val port = AppPreferences(context).getLoopbackAdbPort()
        AppLogger.i(TAG, "shell: whoami='${shell(context, "id -un")}' getprop='${shell(context, "getprop $TCP_PORT_PROP")}'")

        val setpropOutput = shell(context, "setprop $TCP_PORT_PROP $port 2>&1")
        val readBack = shell(context, "getprop $TCP_PORT_PROP")
        val stuck = readBack == port.toString()
        AppLogger.i(
            TAG,
            "shell: setprop $TCP_PORT_PROP $port -> ${if (stuck) "STUCK" else "REJECTED"} " +
                "(readback='$readBack'${if (setpropOutput.isBlank()) "" else ", said='$setpropOutput'"})"
        )
        if (!stuck) {
            AppLogger.w(TAG, "shell: the tcpip port property is not writable from our shell on this device")
        }
    }

    /**
     * Whether anything accepts a TCP connection on the loopback port right now — no ADB handshake, no
     * keys, no library. A closed port fails here; a listener that would reject our key does not.
     */
    fun isRawPortOpen(port: Int): Boolean = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", port), RAW_PROBE_TIMEOUT_MS)
            true
        }
    }.getOrDefault(false)

    /**
     * Runs one short command over the live ADB shell and returns its trimmed output ("" on any failure).
     *
     * Bounded on a throwaway daemon thread for the same reason every other shell read in this app is:
     * over a half-dead connection the read blocks indefinitely, and this one sits on the critical path
     * of starting a recording.
     */
    private fun shell(context: Context, command: String): String {
        val result = java.util.concurrent.atomic.AtomicReference("")
        val worker = Thread {
            runCatching {
                AdbShell.openShell(context, command).use { stream ->
                    stream.openInputStream().use { result.set(String(it.readBytes()).trim()) }
                }
            }.onFailure { AppLogger.d(TAG, "shell '$command' failed: ${it.message}") }
        }.apply { isDaemon = true; name = "cv-loopback-diag" }
        worker.start()
        runCatching { worker.join(SHELL_CAP_MS) }
        return result.get()
    }

    /** Reads a system property via the hidden `SystemProperties.get` (reflection; public SDK-safe). */
    private fun systemProperty(key: String): String = runCatching {
        Class.forName("android.os.SystemProperties")
            .getMethod("get", String::class.java)
            .invoke(null, key) as? String ?: ""
    }.getOrDefault("")
}
