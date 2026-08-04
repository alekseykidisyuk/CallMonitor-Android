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

/**
 * The device's **Default USB Configuration** (Developer options → "Default USB Configuration") — i.e.
 * the USB functions applied when the screen is unlocked (`screen_unlocked_functions`).
 *
 * **Why CallVault cares.** On OnePlus/Xiaomi/Samsung a DATA default (File transfer, etc.) makes the USB
 * gadget renegotiate on every screen on/off transition, which **restarts `adbd`** — and that kills the
 * shell-uid recorder daemon. If it happens mid-call (user locks the phone) the recording stops. Setting
 * the default to **"No data transfer / Charging only"** (no screen-unlocked functions) removes that
 * churn, so the daemon survives a screen lock and the recording continues. Confirmed on-device.
 *
 * Read via `dumpsys usb` and changed via `svc usb setScreenUnlockedFunctions <fn>`, both over the app's
 * embedded ADB shell (shell uid holds the privilege). Both are framework-level → OEM-agnostic.
 */
enum class UsbDefaultMode(
    /** Argument to `svc usb setScreenUnlockedFunctions` ("" = charging/off). */
    val svcArg: String,
) {
    /** "No data transfer / Charging only" — RECOMMENDED; the recorder survives a screen lock. */
    CHARGING(""),
    /** "File transfer / Android Auto" (MTP). */
    FILE_TRANSFER("mtp"),
    /** "PTP". */
    PTP("ptp"),
    /** "USB tethering" (RNDIS). */
    TETHERING("rndis"),
    /** "MIDI". */
    MIDI("midi"),
    /**
     * "Debugging only" — One UI 8 / Android 16's new default, which carries no data function.
     *
     * Safe for the same reason [CHARGING] is: nothing renegotiates when the screen unlocks. Recognised
     * because issue #22 showed the cost of not recognising it — an unknown value is treated as "we
     * don't know", which silences the advice this screen exists to give.
     */
    DEBUGGING_ONLY("adb"),
    /** Couldn't read / unrecognised value. */
    UNKNOWN(""),
}

/** What, if anything, the user should be told about the current Default USB Configuration. */
enum class UsbNotice {
    /** Nothing to say: a known-safe mode. */
    NONE,

    /** A data mode is set — locking the screen mid-call can kill the recorder. */
    DATA_MODE_RISK,

    /** We could not read the setting, and the recorder is not coming up. Worth suggesting. */
    COULD_NOT_CHECK,
}

object UsbDefaultConfig {

    private const val TAG = "CV:UsbDefault"

    /** The only mode in which recording reliably survives a mid-call screen lock. */
    val RECOMMENDED = UsbDefaultMode.CHARGING

    /** Modes offered to the user in the picker (UNKNOWN is never a choice). */
    val SELECTABLE = listOf(
        UsbDefaultMode.CHARGING,
        UsbDefaultMode.DEBUGGING_ONLY,
        UsbDefaultMode.FILE_TRANSFER,
        UsbDefaultMode.PTP,
        UsbDefaultMode.TETHERING,
        UsbDefaultMode.MIDI,
    )

    /** Modes in which nothing renegotiates on a screen transition, so the daemon survives one. */
    private val SAFE = setOf(UsbDefaultMode.CHARGING, UsbDefaultMode.DEBUGGING_ONLY)

    /**
     * Reads the current Default USB Configuration over the ADB shell (`dumpsys usb`). Returns null when
     * there is no live shell to read through (caller should fall back to [cached]); does NOT force a
     * connection, so it never causes WD/adbd churn. Caches the value on success. Call OFF the main thread.
     */
    fun readViaShell(context: Context): UsbDefaultMode? = parseAndCache(context, runShell(context, READ_CMD, ensure = true))

    /**
     * Like [readViaShell] but reads ONLY if an ADB connection is already up — never forces one, so it
     * causes no WD/adbd churn. Use for opportunistic cache refreshes on paths that already hold a
     * connection (e.g. right after a daemon launch). Returns null when nothing was read. OFF main thread.
     */
    fun readIfConnected(context: Context): UsbDefaultMode? = parseAndCache(context, runShell(context, READ_CMD, ensure = false))

    private fun parseAndCache(context: Context, out: String?): UsbDefaultMode? {
        if (out == null) return null
        // Filter for the relevant line in Kotlin rather than a `| grep` (pipes are fragile over `shell:`).
        val line = out.lineSequence().firstOrNull { it.contains("screen_unlocked_functions") }
        if (line == null) {
            // The command ran and the field simply is not there. Measured on a OnePlus 12 (ColorOS):
            // `dumpsys usb` prints 237 lines and not one of them mentions screen_unlocked_functions, so
            // the setting is permanently unreadable on that ROM and every retry will fail the same way.
            // Worth saying out loud — a silent null here is indistinguishable from a shell that failed,
            // which is the confusion this whole area was built on.
            AppLogger.i(TAG, "dumpsys usb has no screen_unlocked_functions line; this ROM does not expose the Default USB Configuration")
            return null
        }
        val mode = parse(line)
        if (mode != UsbDefaultMode.UNKNOWN) AppPreferences(context).setUsbDefaultMode(mode.name)
        AppLogger.i(TAG, "Default USB Configuration read: $mode (raw: '${line.trim()}')")
        return mode
    }

    /** The last successfully-read value (persisted), for UI shown while no shell is available. */
    fun cached(context: Context): UsbDefaultMode =
        runCatching { UsbDefaultMode.valueOf(AppPreferences(context).getUsbDefaultMode() ?: "") }
            .getOrDefault(UsbDefaultMode.UNKNOWN)

    /**
     * True when the cached Default USB Configuration is a DATA mode (File transfer, etc.) — i.e. locking
     * the screen mid-call may restart adbd and kill the recorder. Safe modes and UNKNOWN return false, so
     * we never warn without a confirmed data value.
     */
    fun isScreenLockRisk(context: Context): Boolean = cached(context).let { it !in SAFE && it != UsbDefaultMode.UNKNOWN }

    /**
     * What to tell the user, given the USB mode we last read and whether the recorder is up.
     *
     * **Why [recorderReady] is an input, and why it no longer gates everything.** The warning used to be
     * shown only when the recorder was ready. That inverted it: a data USB mode kills the daemon, a dead
     * daemon means not-ready, and not-ready hid the very warning that explained the death. Issue #22's
     * reporter sat on "Connecting the recorder…" across two calls and was never told about the setting.
     * A risk is now stated whether or not the recorder is up — it is more urgent when it is not.
     *
     * [UsbNotice.COULD_NOT_CHECK] is the other half of that lesson. An unreadable setting used to resolve
     * to silence, indistinguishable to the user from "checked, you're fine". It is now said out loud —
     * but only while the recorder is failing to come up, so a working phone is never nagged about a
     * check that did not matter.
     */
    fun noticeFor(mode: UsbDefaultMode, recorderReady: Boolean): UsbNotice = when {
        mode in SAFE -> UsbNotice.NONE
        mode == UsbDefaultMode.UNKNOWN -> if (recorderReady) UsbNotice.NONE else UsbNotice.COULD_NOT_CHECK
        else -> UsbNotice.DATA_MODE_RISK
    }

    /**
     * Re-reads the setting over a fresh, retrying shell, but ONLY when nothing was ever read.
     *
     * The opportunistic refresh on the recording path gets one un-retried attempt over a connection that
     * is busy starting a recording; on the device in issue #22 it failed with "Stream closed" and the
     * value stayed unknown forever. This is the calm path — called when the daemon has just come up, with
     * nothing else competing for the shell. No-op once a value is known, so it costs one read per install
     * in the normal case. Call OFF the main thread.
     */
    fun readIfUnknown(context: Context): UsbDefaultMode? {
        if (cached(context) != UsbDefaultMode.UNKNOWN) return null
        return readViaShell(context)
    }

    /**
     * Sets the Default USB Configuration over the ADB shell (ensures a connection first). Returns true if
     * the command was delivered.
     *
     * NOTE: switching to [UsbDefaultMode.CHARGING] drops USB *data* (including USB-adb) until the user
     * picks a data mode again — harmless in normal wireless/loopback use, but it means a cable plugged
     * into a PC defaults to charging. Call OFF the main thread.
     */
    fun setViaShell(context: Context, mode: UsbDefaultMode): Boolean {
        if (mode == UsbDefaultMode.UNKNOWN) return false
        // `svc` applies the change ON-DEVICE even when its (empty) response stream closes early, so we
        // can't trust the stream result. Fire it, cache optimistically, then CONFIRM by reading back.
        runShell(context, "svc usb setScreenUnlockedFunctions ${mode.svcArg}".trimEnd(), ensure = true)
        AppPreferences(context).setUsbDefaultMode(mode.name)
        val readback = runCatching { readViaShell(context) }.getOrNull()
        // A null read-back means the link dropped (expected when switching to CHARGING kills USB-adb) —
        // treat that as applied; only a read-back showing a DIFFERENT mode is a real failure.
        val ok = readback == null || readback == mode
        AppLogger.i(TAG, "Set Default USB Configuration to $mode (read-back=$readback, ok=$ok)")
        return ok
    }

    /**
     * Classifies a `screen_unlocked_functions=` line. Data functions win: the value is a comma-separated
     * list, and `mtp,adb` is a file-transfer default that happens to include adb — still a data mode.
     */
    internal fun parse(dumpsysLine: String): UsbDefaultMode {
        val v = dumpsysLine.substringAfter("screen_unlocked_functions=", "").trim().lowercase()
        return when {
            v.contains("mtp") -> UsbDefaultMode.FILE_TRANSFER
            v.contains("ptp") -> UsbDefaultMode.PTP
            v.contains("rndis") -> UsbDefaultMode.TETHERING
            v.contains("midi") -> UsbDefaultMode.MIDI
            // adb alone carries no data function — One UI 8's "Debugging only".
            v == "adb" -> UsbDefaultMode.DEBUGGING_ONLY
            v.isEmpty() || v == "none" -> UsbDefaultMode.CHARGING
            else -> UsbDefaultMode.UNKNOWN
        }
    }

    /**
     * Runs [cmd] over the embedded ADB shell and returns its stdout. The wireless/loopback link is
     * flaky ("Stream closed"), so retry once with a fresh connection — mirroring the daemon launcher.
     * Returns null if both attempts fail. Call OFF the main thread.
     */
    private fun runShell(context: Context, cmd: String, ensure: Boolean): String? {
        val attempts = if (ensure) 2 else 1
        repeat(attempts) { attempt ->
            val connected = when {
                !ensure -> AdbConnectionManager.getInstance(context).isConnected // opportunistic: never force
                attempt == 0 -> AdbShell.ensureConnected(context)
                else -> AdbShell.forceReconnect(context)
            }
            if (!connected) return@repeat
            val out = runCatching {
                AdbShell.openShell(context, cmd).use { s -> s.openInputStream().use { String(it.readBytes()) } }
            }.onFailure { AppLogger.d(TAG, "USB shell cmd attempt ${attempt + 1} failed ('$cmd'): ${it.message}") }
                .getOrNull()
            if (out != null) return out
        }
        if (ensure) AppLogger.w(TAG, "USB shell cmd failed after retry ('$cmd')")
        return null
    }

    private const val READ_CMD = "dumpsys usb"
}
