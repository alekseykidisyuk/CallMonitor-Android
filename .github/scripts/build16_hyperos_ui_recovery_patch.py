from pathlib import Path

# Build 16 replaces the build-15 Accessibility implementation with a UI-only recovery path that
# does not depend on background startActivity() being permitted on Android 15/HyperOS. It also makes
# DaemonKeepAlive hand off to Accessibility before entering a 45s doomed ADB launch.

keepalive = Path("app/src/main/java/com/baba/callvault/services/recording/DaemonKeepAliveService.kt")
s = keepalive.read_text(encoding="utf-8")

# Apply the build-15 keep-alive integration if this is still the branch's build-14 source.
old_import = "import com.baba.callvault.integrations.adb.AdbShell\n"
new_import = old_import + "import com.baba.callvault.integrations.adb.HyperOsWirelessDebugAccessibilityService\n"
if "import com.baba.callvault.integrations.adb.HyperOsWirelessDebugAccessibilityService" not in s:
    assert old_import in s
    s = s.replace(old_import, new_import, 1)

marker = "    private fun maybeRewarm(force: Boolean = false) {\n"
helper = '''    /**
     * HyperOS denies WRITE_SECURE_SETTINGS even to shell on the Redmi Note 12 test device.
     * Try the normal route first; if the switch stays off, hand the exact UI toggle to the
     * one-time-authorized accessibility service.
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
if "private fun restoreWirelessDebuggingWithHyperOsFallback" not in s:
    assert marker in s
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
if old_restore in s:
    s = s.replace(old_restore, new_restore, 1)

old_rebuild = '''                    runCatching { AdbShell.enableWirelessDebugging(applicationContext) }
                        .onFailure { AppLogger.w(TAG, "keep-alive: could not re-enable Wireless debugging: ${it.message}") }
'''
if old_rebuild in s:
    s = s.replace(old_rebuild, '                    restoreWirelessDebuggingWithHyperOsFallback("rebuild-connection")\n', 1)

old_delay = "            watchdogHandler.postDelayed(this, WATCHDOG_INTERVAL_MS)\n"
new_delay = "            watchdogHandler.postDelayed(this, if (alive) WATCHDOG_INTERVAL_MS else RECOVERY_WATCHDOG_INTERVAL_MS)\n"
if old_delay in s:
    s = s.replace(old_delay, new_delay, 1)

old_const = "        private const val WATCHDOG_INTERVAL_MS = 60_000L\n"
if "private const val RECOVERY_WATCHDOG_INTERVAL_MS" not in s:
    assert old_const in s
    s = s.replace(old_const, old_const + "        private const val RECOVERY_WATCHDOG_INTERVAL_MS = 5_000L\n", 1)

# Build 16: after requesting the accessibility switch, do NOT spend 45 seconds trying to launch the
# daemon through an endpoint that we already know does not exist. Release the gate and let the 5-second
# watchdog retry as soon as Accessibility has enabled Wireless debugging.
launch_marker = '''            val ok = try {
                launchDaemonBounded()
            } finally {
'''
preflight = '''            if (!AdbShell.isWirelessDebuggingEnabled(applicationContext) &&
                !AdbShell.isLoopbackArmed(applicationContext)
            ) {
                AppLogger.i(TAG, "keep-alive: waiting for HyperOS UI recovery before launching recorder daemon")
                rewarmGate.leave()
                return@Thread
            }

            val ok = try {
                launchDaemonBounded()
            } finally {
'''
assert launch_marker in s
s = s.replace(launch_marker, preflight, 1)
keepalive.write_text(s, encoding="utf-8")

# Full build-16 Accessibility implementation. The important design change is that it does not require
# CallMonitor to launch Settings from the background. Android 15 may silently deny that BAL. Instead it
# uses GLOBAL_ACTION_QUICK_SETTINGS, clicks the Settings gear, then navigates Settings via its own search
# UI using accessibility actions. Every transition stays user-visible and inside SystemUI/Settings.
accessibility = Path("app/src/main/java/com/baba/callvault/integrations/adb/HyperOsWirelessDebugAccessibilityService.kt")
accessibility.write_text(r'''/*
 * CallMonitor Android — HyperOS accessibility fallback for restoring Wireless Debugging after reboot.
 *
 * Derived from the CallVault GPLv3 codebase. See LICENSE at repository root.
 */

package com.baba.callvault.integrations.adb

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.services.boot.AdbConnectionService
import com.baba.callvault.utils.AppLogger

/**
 * Optional, one-time-enabled fallback for Xiaomi/HyperOS devices that deny WRITE_SECURE_SETTINGS.
 *
 * Android 15/HyperOS may silently reject a Settings activity launch from a background service even
 * when startActivity() itself does not throw. Build 16 therefore has a second, UI-driven path:
 * Quick Settings -> Settings gear -> Settings search -> Wireless debugging -> switch -> confirmation.
 * This path uses only Accessibility global/click/set-text actions after the user has explicitly enabled
 * this service once.
 */
class HyperOsWirelessDebugAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var recoveryDeadlineMs = 0L
    private var lastClickMs = 0L
    private var lastQuickSettingsMs = 0L
    private var searchQuerySet = false
    private var expiryLogged = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        AppLogger.i(TAG, "Accessibility fallback connected")

        val inferredPostBootRecovery = runCatching {
            AppPreferences(this).isPrivilegedTransportSetUp() &&
                !AdbShell.isLoopbackArmed(this) &&
                !AdbShell.isWirelessDebuggingEnabled(this)
        }.getOrDefault(false)

        if (isRecoveryPending(this) || inferredPostBootRecovery) {
            if (inferredPostBootRecovery) setRecoveryPending(this, true)
            handler.post { beginRecoveryIfPossible() }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!isRecoveryPending(this)) return
        when (event?.packageName?.toString()) {
            SETTINGS_PACKAGE -> handler.post { driveSettingsUi() }
            SYSTEMUI_PACKAGE -> handler.post { driveSystemUi() }
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (instance === this) instance = null
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun beginRecoveryIfPossible() {
        if (!isRecoveryPending(this)) return

        if (AdbShell.isWirelessDebuggingEnabled(this)) {
            finishRecoverySuccess()
            return
        }

        if (isDeviceLocked()) {
            AppLogger.d(TAG, "Accessibility recovery pending; waiting for device unlock")
            handler.postDelayed(::beginRecoveryIfPossible, LOCK_RETRY_MS)
            return
        }

        if (!isWifiConnected()) {
            AppLogger.d(TAG, "Accessibility recovery pending; waiting for Wi-Fi")
            handler.postDelayed(::beginRecoveryIfPossible, WIFI_RETRY_MS)
            return
        }

        val now = SystemClock.elapsedRealtime()
        if (recoveryDeadlineMs == 0L || now > recoveryDeadlineMs) {
            recoveryDeadlineMs = now + RECOVERY_WINDOW_MS
            expiryLogged = false
            searchQuerySet = false
        }

        openQuickSettings()
    }

    private fun openQuickSettings() {
        if (!isRecoveryPending(this)) return
        if (AdbShell.isWirelessDebuggingEnabled(this)) {
            finishRecoverySuccess()
            return
        }
        if (isDeviceLocked()) {
            handler.postDelayed(::beginRecoveryIfPossible, LOCK_RETRY_MS)
            return
        }

        val now = SystemClock.elapsedRealtime()
        if (now - lastQuickSettingsMs < QUICK_SETTINGS_GUARD_MS) {
            handler.postDelayed(::driveSystemUi, RETRY_MS)
            return
        }
        lastQuickSettingsMs = now

        val ok = performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
        AppLogger.i(TAG, "Accessibility opened Quick Settings=$ok")
        handler.postDelayed(::driveSystemUi, UI_SETTLE_MS)
    }

    private fun driveSystemUi() {
        if (!isRecoveryPending(this)) return
        if (AdbShell.isWirelessDebuggingEnabled(this)) {
            finishRecoverySuccess()
            return
        }
        if (restartIfExpired()) return

        val root = rootInActiveWindow ?: run {
            handler.postDelayed(::driveSystemUi, RETRY_MS)
            return
        }
        val pkg = root.packageName?.toString()
        if (pkg == SETTINGS_PACKAGE) {
            driveSettingsUi()
            return
        }
        if (pkg != SYSTEMUI_PACKAGE) {
            AppLogger.d(TAG, "Accessibility recovery surface is $pkg; reopening Quick Settings")
            handler.postDelayed(::openQuickSettings, RETRY_MS)
            return
        }

        val gear = findDescendant(root) { node ->
            val id = node.viewIdResourceName.orEmpty()
            val text = node.text?.toString().orEmpty()
            val desc = node.contentDescription?.toString().orEmpty()
            node.isVisibleToUser && node.isEnabled && (
                id.contains("settings_button", ignoreCase = true) ||
                    id.endsWith(":id/settings", ignoreCase = true) ||
                    SETTINGS_GEAR_LABELS.any { label ->
                        text.equals(label, ignoreCase = true) || desc.equals(label, ignoreCase = true)
                    }
                )
        }

        val clickableGear = gear?.let { findClickableAncestor(it) ?: it.takeIf { n -> n.isClickable } }
        if (clickableGear != null && clickWithGuard(clickableGear)) {
            AppLogger.i(TAG, "Accessibility clicked Settings gear from Quick Settings")
            handler.postDelayed(::driveSettingsUi, SETTINGS_OPEN_SETTLE_MS)
            return
        }

        AppLogger.d(TAG, "Accessibility could not find Settings gear yet; retrying Quick Settings")
        handler.postDelayed(::openQuickSettings, QUICK_SETTINGS_RETRY_MS)
    }

    private fun driveSettingsUi() {
        if (!isRecoveryPending(this)) return

        if (AdbShell.isWirelessDebuggingEnabled(this)) {
            finishRecoverySuccess()
            return
        }
        if (restartIfExpired()) return

        val root = rootInActiveWindow ?: run {
            handler.postDelayed(::driveSettingsUi, RETRY_MS)
            return
        }
        val pkg = root.packageName?.toString()
        if (pkg == SYSTEMUI_PACKAGE) {
            driveSystemUi()
            return
        }
        if (pkg != SETTINGS_PACKAGE) {
            handler.postDelayed(::openQuickSettings, RETRY_MS)
            return
        }

        // HyperOS may show a confirmation dialog after the switch is pressed.
        if (clickMatchingConfirmation(root)) {
            AppLogger.i(TAG, "Accessibility confirmed Wireless debugging")
            handler.postDelayed(::driveSettingsUi, RETRY_MS)
            return
        }

        val wirelessLabel = findFirstTextNode(root, WIRELESS_DEBUG_LABELS)
        if (wirelessLabel != null) {
            findSwitchNear(wirelessLabel)?.let { switch ->
                if (switch.isChecked) {
                    handler.postDelayed(::driveSettingsUi, RETRY_MS)
                    return
                }
                if (clickWithGuard(switch)) {
                    AppLogger.i(TAG, "Accessibility pressed Wireless debugging switch")
                    handler.postDelayed(::driveSettingsUi, RETRY_MS)
                    return
                }
            }

            // A search result / Developer-options row has no local switch: open the row.
            findClickableAncestor(wirelessLabel)?.let { row ->
                if (clickWithGuard(row)) {
                    AppLogger.i(TAG, "Accessibility opened Wireless debugging row")
                    handler.postDelayed(::driveSettingsUi, SETTINGS_OPEN_SETTLE_MS)
                    return
                }
            }
        }

        if (looksLikeWirelessDebuggingPage(root)) {
            findFirstSwitch(root)?.let { switch ->
                if (!switch.isChecked && clickWithGuard(switch)) {
                    AppLogger.i(TAG, "Accessibility pressed Wireless debugging switch via page fallback")
                    handler.postDelayed(::driveSettingsUi, RETRY_MS)
                    return
                }
            }
        }

        // If Settings search is already open, populate the query. HyperOS updates results live.
        val editor = findDescendant(root) { node -> node.isVisibleToUser && node.isEnabled && node.isEditable }
        if (editor != null) {
            if (!searchQuerySet) {
                val args = Bundle().apply {
                    putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        WIRELESS_SEARCH_QUERY,
                    )
                }
                val set = editor.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                searchQuerySet = set
                AppLogger.i(TAG, "Accessibility entered Wireless-debugging search query=$set")
            }
            handler.postDelayed(::driveSettingsUi, SEARCH_RESULT_SETTLE_MS)
            return
        }

        // We are on the Settings landing page: click the search affordance instead of trying to start a
        // Settings Activity from the background (the operation HyperOS silently rejected in build 15).
        val searchNode = findDescendant(root) { node ->
            if (!node.isVisibleToUser || !node.isEnabled) return@findDescendant false
            val id = node.viewIdResourceName.orEmpty()
            val text = node.text?.toString().orEmpty()
            val desc = node.contentDescription?.toString().orEmpty()
            id.contains("search", ignoreCase = true) ||
                SETTINGS_SEARCH_LABELS.any { label ->
                    text.contains(label, ignoreCase = true) || desc.contains(label, ignoreCase = true)
                }
        }
        val clickableSearch = searchNode?.let { findClickableAncestor(it) ?: it.takeIf { n -> n.isClickable } }
        if (clickableSearch != null && clickWithGuard(clickableSearch)) {
            AppLogger.i(TAG, "Accessibility opened Settings search")
            handler.postDelayed(::driveSettingsUi, SETTINGS_OPEN_SETTLE_MS)
            return
        }

        AppLogger.d(TAG, "Accessibility cannot locate Settings search yet; retrying")
        handler.postDelayed(::driveSettingsUi, RETRY_MS)
    }

    private fun restartIfExpired(): Boolean {
        if (recoveryDeadlineMs == 0L || SystemClock.elapsedRealtime() <= recoveryDeadlineMs) return false
        if (!expiryLogged) {
            expiryLogged = true
            AppLogger.w(TAG, "Accessibility Wireless-debugging recovery window expired; restarting UI recovery")
        }
        recoveryDeadlineMs = 0L
        searchQuerySet = false
        handler.postDelayed(::beginRecoveryIfPossible, RECOVERY_RESTART_MS)
        return true
    }

    private fun clickMatchingConfirmation(root: AccessibilityNodeInfo): Boolean {
        val button = findFirstTextNode(root, CONFIRM_LABELS) ?: return false
        val wirelessContext = WIRELESS_DEBUG_LABELS.any { text ->
            root.findAccessibilityNodeInfosByText(text).isNotEmpty()
        } || WIRELESS_CONFIRM_CONTEXT.any { text ->
            root.findAccessibilityNodeInfosByText(text).isNotEmpty()
        }
        if (!wirelessContext) return false
        val clickable = findClickableAncestor(button) ?: button.takeIf { it.isClickable } ?: return false
        return clickWithGuard(clickable)
    }

    private fun findSwitchNear(label: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var node: AccessibilityNodeInfo? = label
        repeat(5) {
            val current = node ?: return null
            findDescendant(current) { candidate ->
                candidate.isCheckable && candidate.isEnabled &&
                    candidate.className?.toString()?.contains("Switch", ignoreCase = true) == true
            }?.let { return it }
            node = current.parent
        }
        return null
    }

    private fun findClickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        repeat(6) {
            val n = current ?: return null
            if (n.isClickable && n.isEnabled) return n
            current = n.parent
        }
        return null
    }

    private fun findFirstTextNode(root: AccessibilityNodeInfo, candidates: List<String>): AccessibilityNodeInfo? =
        candidates.asSequence()
            .flatMap { root.findAccessibilityNodeInfosByText(it).asSequence() }
            .firstOrNull { it.isVisibleToUser }

    private fun findFirstSwitch(root: AccessibilityNodeInfo): AccessibilityNodeInfo? =
        findDescendant(root) { node ->
            node.isCheckable && node.isEnabled &&
                node.className?.toString()?.contains("Switch", ignoreCase = true) == true
        }

    private fun findDescendant(
        root: AccessibilityNodeInfo,
        predicate: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (predicate(node)) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let(queue::addLast)
        }
        return null
    }

    private fun looksLikeWirelessDebuggingPage(root: AccessibilityNodeInfo): Boolean =
        WIRELESS_PAGE_MARKERS.any { root.findAccessibilityNodeInfosByText(it).isNotEmpty() }

    private fun clickWithGuard(node: AccessibilityNodeInfo): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - lastClickMs < CLICK_GUARD_MS) return false
        lastClickMs = now
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun finishRecoverySuccess() {
        setRecoveryPending(this, false)
        recoveryDeadlineMs = 0L
        searchQuerySet = false
        AppLogger.i(TAG, "Accessibility recovery enabled Wireless debugging; handing back to recorder recovery")
        AdbConnectionService.start(applicationContext)
        handler.postDelayed({ runCatching { performGlobalAction(GLOBAL_ACTION_BACK) } }, RETURN_BACK_DELAY_MS)
    }

    private fun isDeviceLocked(): Boolean = runCatching {
        getSystemService(KeyguardManager::class.java)?.isDeviceLocked == true
    }.getOrDefault(false)

    private fun isWifiConnected(): Boolean = runCatching {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }.getOrDefault(false)

    companion object {
        private const val TAG = "CM:HyperOsAccessibility"
        private const val SETTINGS_PACKAGE = "com.android.settings"
        private const val SYSTEMUI_PACKAGE = "com.android.systemui"
        private const val PREFS = "callmonitor_hyperos_accessibility"
        private const val KEY_PENDING = "wireless_debug_recovery_pending"
        private const val RECOVERY_WINDOW_MS = 45_000L
        private const val LOCK_RETRY_MS = 1_000L
        private const val WIFI_RETRY_MS = 1_500L
        private const val UI_SETTLE_MS = 900L
        private const val SETTINGS_OPEN_SETTLE_MS = 1_100L
        private const val SEARCH_RESULT_SETTLE_MS = 1_200L
        private const val RETRY_MS = 500L
        private const val QUICK_SETTINGS_RETRY_MS = 1_200L
        private const val QUICK_SETTINGS_GUARD_MS = 1_500L
        private const val CLICK_GUARD_MS = 700L
        private const val RECOVERY_RESTART_MS = 2_000L
        private const val RETURN_BACK_DELAY_MS = 1_200L
        private const val WIRELESS_SEARCH_QUERY = "Отладка по Wi-Fi"

        private val WIRELESS_DEBUG_LABELS = listOf(
            "Отладка по Wi-Fi",
            "Беспроводная отладка",
            "Wireless debugging",
        )
        private val WIRELESS_PAGE_MARKERS = listOf(
            "IP-адрес и порт",
            "IP address & port",
            "Подключенные устройства",
            "Paired devices",
            "Подключить устройство с помощью кода подключения",
            "Pair device with pairing code",
        )
        private val SETTINGS_GEAR_LABELS = listOf("Настройки", "Settings")
        private val SETTINGS_SEARCH_LABELS = listOf("Поиск", "Search")
        private val CONFIRM_LABELS = listOf("Разрешить", "Включить", "Allow", "OK")
        private val WIRELESS_CONFIRM_CONTEXT = listOf("Wi-Fi", "беспровод", "Wireless")

        @Volatile
        private var instance: HyperOsWirelessDebugAccessibilityService? = null

        fun requestWirelessDebugging(context: Context): Boolean {
            setRecoveryPending(context, true)
            val enabled = isEnabled(context)
            if (enabled) instance?.handler?.post { instance?.beginRecoveryIfPossible() }
            return enabled
        }

        fun isEnabled(context: Context): Boolean {
            val expected = ComponentName(context, HyperOsWirelessDebugAccessibilityService::class.java)
                .flattenToString()
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
        }

        fun openAccessibilitySettings(context: Context) {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    },
                )
            }
        }

        private fun isRecoveryPending(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_PENDING, false)

        private fun setRecoveryPending(context: Context, pending: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_PENDING, pending)
                .apply()
        }
    }
}
''', encoding="utf-8")

# Accessibility must observe SystemUI as well as Settings, because build 16 intentionally opens Quick
# Settings and clicks the Settings gear before navigating inside Settings.
xml = Path("app/src/main/res/xml/callmonitor_accessibility_service.xml")
x = xml.read_text(encoding="utf-8")
x = x.replace(
    'android:packageNames="com.android.settings"',
    'android:packageNames="com.android.settings,com.android.systemui"',
)
xml.write_text(x, encoding="utf-8")
