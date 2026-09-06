/*
 * CallMonitor Android — HyperOS accessibility fallback for restoring Wireless Debugging after reboot.
 *
 * Derived from the CallVault GPLv3 codebase. See LICENSE at repository root.
 */

package com.baba.callvault.integrations.adb

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.baba.callvault.services.boot.AdbConnectionService
import com.baba.callvault.utils.AppLogger

/**
 * Optional, one-time-enabled fallback for Xiaomi/HyperOS devices that deny WRITE_SECURE_SETTINGS.
 *
 * The service is intentionally narrow:
 *  - it only receives events from com.android.settings (enforced in XML config);
 *  - it does nothing unless CallMonitor has explicitly marked a post-boot WD recovery as pending;
 *  - it only opens the Wireless-debugging/Developer-options settings UI and clicks the Wireless
 *    debugging row/switch (plus the matching confirmation button when present);
 *  - once WD is on it immediately hands control back to [AdbConnectionService] and clears its pending flag.
 *
 * This exists because HyperOS on the test Redmi Note 12 denies WRITE_SECURE_SETTINGS even to shell,
 * making the normal programmatic adb_wifi_enabled write impossible. Accessibility is the remaining
 * non-root route to automate the exact switch the user otherwise has to flip manually after every reboot.
 */
class HyperOsWirelessDebugAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var recoveryDeadlineMs = 0L
    private var lastLaunchMs = 0L
    private var lastClickMs = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        AppLogger.i(TAG, "Accessibility fallback connected")
        if (isRecoveryPending(this)) {
            handler.post { beginRecoveryIfPossible() }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!isRecoveryPending(this)) return
        if (event?.packageName?.toString() != SETTINGS_PACKAGE) return
        handler.post { driveSettingsUi() }
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

        if (!isWifiConnected()) {
            AppLogger.i(TAG, "Accessibility recovery pending; waiting for Wi-Fi")
            return
        }

        val now = SystemClock.elapsedRealtime()
        if (now - lastLaunchMs < RELAUNCH_GUARD_MS) return
        lastLaunchMs = now
        recoveryDeadlineMs = now + RECOVERY_WINDOW_MS

        val openedDirect = runCatching {
            startActivity(
                Intent(ACTION_WIRELESS_DEBUGGING_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    setPackage(SETTINGS_PACKAGE)
                },
            )
            true
        }.getOrDefault(false)

        if (!openedDirect) {
            runCatching {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        setPackage(SETTINGS_PACKAGE)
                    },
                )
            }.onFailure { AppLogger.w(TAG, "Could not open Developer options: ${it.message}") }
        }

        handler.postDelayed(::driveSettingsUi, UI_SETTLE_MS)
    }

    private fun driveSettingsUi() {
        if (!isRecoveryPending(this)) return

        if (AdbShell.isWirelessDebuggingEnabled(this)) {
            finishRecoverySuccess()
            return
        }

        if (recoveryDeadlineMs > 0L && SystemClock.elapsedRealtime() > recoveryDeadlineMs) {
            AppLogger.w(TAG, "Accessibility Wireless-debugging recovery window expired; will retry on next trigger")
            return
        }

        val root = rootInActiveWindow ?: run {
            handler.postDelayed(::driveSettingsUi, RETRY_MS)
            return
        }

        // HyperOS may show a confirmation dialog after the switch is pressed.
        if (clickMatchingConfirmation(root)) {
            handler.postDelayed(::driveSettingsUi, RETRY_MS)
            return
        }

        val label = findFirstTextNode(root, WIRELESS_DEBUG_LABELS)
        if (label != null) {
            // On the actual Wireless-debugging page, the label and switch live in the same row.
            findSwitchNear(label)?.let { switch ->
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

            // On Developer options the row has a chevron instead of a switch: open the row first.
            findClickableAncestor(label)?.let { row ->
                if (clickWithGuard(row)) {
                    AppLogger.i(TAG, "Accessibility opened Wireless debugging settings row")
                    handler.postDelayed(::driveSettingsUi, UI_SETTLE_MS)
                    return
                }
            }
        }

        // Fallback for translations we did not anticipate: only use the first switch when the page
        // unmistakably contains Wireless-debugging-specific detail text such as IP/paired devices.
        if (looksLikeWirelessDebuggingPage(root)) {
            findFirstSwitch(root)?.let { switch ->
                if (!switch.isChecked && clickWithGuard(switch)) {
                    AppLogger.i(TAG, "Accessibility pressed Wireless debugging switch via page fallback")
                    handler.postDelayed(::driveSettingsUi, RETRY_MS)
                    return
                }
            }
        }

        handler.postDelayed(::driveSettingsUi, RETRY_MS)
    }

    private fun clickMatchingConfirmation(root: AccessibilityNodeInfo): Boolean {
        val hasWirelessContext = WIRELESS_DEBUG_LABELS.any { text ->
            root.findAccessibilityNodeInfosByText(text).isNotEmpty()
        }
        if (!hasWirelessContext) return false

        val button = findFirstTextNode(root, CONFIRM_LABELS) ?: return false
        val clickable = findClickableAncestor(button) ?: button.takeIf { it.isClickable } ?: return false
        return clickWithGuard(clickable)
    }

    private fun findSwitchNear(label: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var node: AccessibilityNodeInfo? = label
        repeat(4) {
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
        repeat(5) {
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
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let(queue::addLast)
            }
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
        AppLogger.i(TAG, "Accessibility recovery enabled Wireless debugging; handing back to recorder recovery")
        AdbConnectionService.start(applicationContext)
        handler.postDelayed({
            runCatching { performGlobalAction(GLOBAL_ACTION_BACK) }
        }, RETURN_BACK_DELAY_MS)
    }

    private fun isWifiConnected(): Boolean = runCatching {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }.getOrDefault(false)

    companion object {
        private const val TAG = "CM:HyperOsAccessibility"
        private const val SETTINGS_PACKAGE = "com.android.settings"
        private const val ACTION_WIRELESS_DEBUGGING_SETTINGS = "android.settings.WIRELESS_DEBUGGING_SETTINGS"
        private const val PREFS = "callmonitor_hyperos_accessibility"
        private const val KEY_PENDING = "wireless_debug_recovery_pending"
        private const val RECOVERY_WINDOW_MS = 20_000L
        private const val RELAUNCH_GUARD_MS = 4_000L
        private const val UI_SETTLE_MS = 700L
        private const val RETRY_MS = 500L
        private const val CLICK_GUARD_MS = 900L
        private const val RETURN_BACK_DELAY_MS = 1_200L

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
        private val CONFIRM_LABELS = listOf("Разрешить", "Включить", "Allow", "OK")

        @Volatile
        private var instance: HyperOsWirelessDebugAccessibilityService? = null

        /**
         * Marks a reboot recovery as requiring the UI fallback. Returns true when the accessibility
         * service is enabled in Android settings. If it is already connected, recovery starts now;
         * otherwise [onServiceConnected] picks up the persisted pending flag later.
         */
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
