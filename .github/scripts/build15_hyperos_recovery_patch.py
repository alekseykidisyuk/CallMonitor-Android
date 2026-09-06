from pathlib import Path

keepalive = Path("app/src/main/java/com/baba/callvault/services/recording/DaemonKeepAliveService.kt")
s = keepalive.read_text(encoding="utf-8")

old_import = "import com.baba.callvault.integrations.adb.AdbShell\n"
new_import = old_import + "import com.baba.callvault.integrations.adb.HyperOsWirelessDebugAccessibilityService\n"
assert old_import in s
if "import com.baba.callvault.integrations.adb.HyperOsWirelessDebugAccessibilityService" not in s:
    s = s.replace(old_import, new_import, 1)

marker = "    private fun maybeRewarm(force: Boolean = false) {\n"
helper = '''    /**
     * HyperOS denies WRITE_SECURE_SETTINGS even to shell on the Redmi Note 12 test device.
     * Always try the normal route first; if the switch is still off, hand the exact UI toggle to the
     * one-time-authorized Settings-only accessibility service. This path is called by the persistent
     * keep-alive, so it survives cases where the dedicated boot recovery service never gets scheduled.
     */
    private fun restoreWirelessDebuggingWithHyperOsFallback(reason: String) {
        runCatching { AdbShell.enableWirelessDebugging(applicationContext) }
            .onFailure { AppLogger.w(TAG, "keep-alive: direct Wireless-debugging enable failed ($reason): ${it.message}") }

        if (AdbShell.isWirelessDebuggingEnabled(applicationContext)) return

        val accessibilityEnabled =
            HyperOsWirelessDebugAccessibilityService.requestWirelessDebugging(applicationContext)
        if (accessibilityEnabled) {
            AppLogger.w(TAG, "keep-alive: Wireless debugging still off; requested HyperOS accessibility fallback ($reason)")
        } else {
            AppLogger.w(TAG, "keep-alive: Wireless debugging still off and accessibility fallback is not enabled ($reason)")
        }
    }

'''
assert marker in s
if "private fun restoreWirelessDebuggingWithHyperOsFallback" not in s:
    s = s.replace(marker, helper + marker, 1)

old_restore = '''                RecoveryStep.RESTORE_WIRELESS_DEBUGGING -> {
                    AppLogger.w(TAG, "keep-alive: no TCP endpoint to dial — switching Wireless debugging back on")
                    runCatching { AdbShell.enableWirelessDebugging(applicationContext) }
                        .onFailure { AppLogger.w(TAG, "keep-alive: could not re-enable Wireless debugging: ${it.message}") }
                }
'''
new_restore = '''                RecoveryStep.RESTORE_WIRELESS_DEBUGGING -> {
                    AppLogger.w(TAG, "keep-alive: no TCP endpoint to dial — restoring Wireless debugging")
                    restoreWirelessDebuggingWithHyperOsFallback("restore-endpoint")
                }
'''
assert old_restore in s
s = s.replace(old_restore, new_restore, 1)

old_rebuild = '''                    runCatching { AdbShell.enableWirelessDebugging(applicationContext) }
                        .onFailure { AppLogger.w(TAG, "keep-alive: could not re-enable Wireless debugging: ${it.message}") }
'''
assert old_rebuild in s
s = s.replace(old_rebuild, '                    restoreWirelessDebuggingWithHyperOsFallback("rebuild-connection")\n', 1)

old_delay = "            watchdogHandler.postDelayed(this, WATCHDOG_INTERVAL_MS)\n"
new_delay = "            watchdogHandler.postDelayed(this, if (alive) WATCHDOG_INTERVAL_MS else RECOVERY_WATCHDOG_INTERVAL_MS)\n"
assert old_delay in s
s = s.replace(old_delay, new_delay, 1)

old_const = "        private const val WATCHDOG_INTERVAL_MS = 60_000L\n"
new_const = old_const + "        private const val RECOVERY_WATCHDOG_INTERVAL_MS = 5_000L\n"
assert old_const in s
if "private const val RECOVERY_WATCHDOG_INTERVAL_MS" not in s:
    s = s.replace(old_const, new_const, 1)

keepalive.write_text(s, encoding="utf-8")

accessibility = Path("app/src/main/java/com/baba/callvault/integrations/adb/HyperOsWirelessDebugAccessibilityService.kt")
a = accessibility.read_text(encoding="utf-8")
import_marker = "import com.baba.callvault.services.boot.AdbConnectionService\n"
assert import_marker in a
if "import com.baba.callvault.data.AppPreferences" not in a:
    a = a.replace(import_marker, "import com.baba.callvault.data.AppPreferences\n" + import_marker, 1)

old_connected = '''    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        AppLogger.i(TAG, "Accessibility fallback connected")
        if (isRecoveryPending(this)) {
            handler.post { beginRecoveryIfPossible() }
        }
    }
'''
new_connected = '''    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        AppLogger.i(TAG, "Accessibility fallback connected")

        // Do not rely exclusively on AdbConnectionService having run first. HyperOS may restart the
        // keep-alive after boot while delaying/skipping the dedicated recovery service. If offline mode
        // is configured and reboot cleared loopback, the accessibility service can infer the same
        // recovery need itself as soon as Android binds it.
        val inferredPostBootRecovery = runCatching {
            AppPreferences(this).isOfflineRecordingEnabled() &&
                !AdbShell.isLoopbackArmed(this) &&
                !AdbShell.isWirelessDebuggingEnabled(this)
        }.getOrDefault(false)

        if (isRecoveryPending(this) || inferredPostBootRecovery) {
            if (inferredPostBootRecovery) setRecoveryPending(this, true)
            handler.post { beginRecoveryIfPossible() }
        }
    }
'''
assert old_connected in a
a = a.replace(old_connected, new_connected, 1)
accessibility.write_text(a, encoding="utf-8")
