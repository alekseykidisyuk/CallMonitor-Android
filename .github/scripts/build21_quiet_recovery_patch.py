"""Apply after 16–19: bound the HyperOS UI fallback without touching audio or delivery."""
from pathlib import Path
import re

p = Path('app/src/main/java/com/baba/callvault/integrations/adb/HyperOsWirelessDebugAccessibilityService.kt')
s = p.read_text(encoding='utf-8')

def replace(old, new):
    global s
    assert s.count(old) == 1, f'Expected exactly one build18 marker: {old[:100]}'
    s = s.replace(old, new, 1)

replace('import com.baba.callvault.data.AppPreferences',
        'import com.baba.callvault.callmonitor.RecoveryUiPolicy\nimport com.baba.callvault.data.AppPreferences')
replace('    private var recoveryDeadlineMs = 0L', '''    private val recoveryPrefs by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    private val uiPolicy by lazy {
        RecoveryUiPolicy(RecoveryUiPolicy.State(
            boot = recoveryPrefs.getInt("ui_boot", -1),
            attempts = recoveryPrefs.getInt("ui_attempts", 0),
            deadline = recoveryPrefs.getLong("ui_deadline", 0),
            nextStart = recoveryPrefs.getLong("ui_next_start", 0),
        ))
    }
    private var queuedStep: Runnable? = null
    private var ownsRecoveryUi = false

    // All callers, including watchdog requests, are marshalled onto the main looper.
    // First queued step wins: screen-event storms cannot multiply or starve navigation.
    private fun scheduleStep(action: () -> Unit, delayMs: Long = 0) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (queuedStep != null) return
        val step = Runnable { queuedStep = null; action() }
        queuedStep = step
        handler.postDelayed(step, delayMs)
    }

    private fun cancelStep() {
        queuedStep?.let { handler.removeCallbacks(it) }
        queuedStep = null
    }

    private fun saveUiPolicy() {
        val state = uiPolicy.state
        // Commit only on state transitions; persist the budget before performing a UI action.
        recoveryPrefs.edit().putInt("ui_boot", state.boot)
            .putInt("ui_attempts", state.attempts).putLong("ui_deadline", state.deadline)
            .putLong("ui_next_start", state.nextStart).commit()
    }

    private fun recoveryDecision(allowStart: Boolean): RecoveryUiPolicy.Decision {
        val before = uiPolicy.state
        val boot = runCatching { Settings.Global.getInt(contentResolver, Settings.Global.BOOT_COUNT) }
            .getOrDefault(-1)
        val decision = uiPolicy.evaluate(SystemClock.elapsedRealtime(), boot,
            isWifiConnected(), !isDeviceLocked(), allowStart)
        if (before != uiPolicy.state) saveUiPolicy()
        return decision
    }

    private fun mayDriveUi(): Boolean {
        if (!isRecoveryPending(this)) return false
        if (AdbShell.isWirelessDebuggingEnabled(this)) {
            finishRecoverySuccess()
            return false
        }
        if (recoveryDecision(false) == RecoveryUiPolicy.Decision.CONTINUE) return true
        cancelStep()
        ownsRecoveryUi = false
        searchQuerySet = false
        scheduleStep(::beginRecoveryIfPossible, QUIET_POLL_MS)
        return false
    }''')
replace('    private var expiryLogged = false\n', '')

start = s.index('    override fun onAccessibilityEvent(')
end = s.index('    override fun onInterrupt()', start)
s = s[:start] + '''    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!isRecoveryPending(this)) return
        // Events must pass the same network/lock/budget gate as the initial request.
        when (event?.packageName?.toString()) {
            SETTINGS_PACKAGE -> scheduleStep(::driveSettingsUi)
            SYSTEMUI_PACKAGE -> scheduleStep(::driveSystemUi)
        }
    }

''' + s[end:]

start = s.index('    private fun beginRecoveryIfPossible()')
end = s.index('    private fun openQuickSettings()', start)
s = s[:start] + '''    private fun beginRecoveryIfPossible() {
        if (!isRecoveryPending(this)) return
        if (AdbShell.isWirelessDebuggingEnabled(this)) {
            finishRecoverySuccess()
            return
        }
        when (recoveryDecision(true)) {
            RecoveryUiPolicy.Decision.START -> {
                searchQuerySet = false
                openQuickSettings()
            }
            RecoveryUiPolicy.Decision.CONTINUE -> driveSystemUi()
            RecoveryUiPolicy.Decision.WAIT -> {
                ownsRecoveryUi = false
                scheduleStep(::beginRecoveryIfPossible, QUIET_POLL_MS)
            }
            RecoveryUiPolicy.Decision.STOP -> {
                ownsRecoveryUi = false
                AppLogger.w(TAG, "Accessibility recovery paused: per-boot UI attempt limit reached")
            }
        }
    }

''' + s[end:]

# Guard both navigation entry points and the actions themselves; an event cannot create a window.
for name, marker in [('openQuickSettings', '        val now = SystemClock.elapsedRealtime()'),
                     ('driveSystemUi', '        val root = rootInActiveWindow'),
                     ('driveSettingsUi', '        val root = rootInActiveWindow')]:
    start = s.index(f'    private fun {name}() {{')
    body = s.index('\n', start) + 1
    end = s.index(marker, body)
    s = s[:body] + '        if (!mayDriveUi()) return\n\n' + s[end:]

start = s.index('    private fun restartIfExpired()')
end = s.index('    private fun clickMatchingConfirmation', start)
s = s[:start] + s[end:]
replace('    private fun clickWithGuard(node: AccessibilityNodeInfo): Boolean {',
        '    private fun clickWithGuard(node: AccessibilityNodeInfo): Boolean {\n        if (!mayDriveUi()) return false')
replace('        val ok = performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)',
        '        if (!mayDriveUi()) return\n        val ok = performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)\n        ownsRecoveryUi = ownsRecoveryUi || ok')
replace('                val set = editor.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)',
        '                if (!mayDriveUi()) return\n                val set = editor.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)')

replace('        recoveryDeadlineMs = 0L', '''        cancelStep()
        uiPolicy.succeeded(SystemClock.elapsedRealtime())
        saveUiPolicy()
        val shouldCleanUp = ownsRecoveryUi
        ownsRecoveryUi = false''')
replace('        // The recovery UI is ours, so clean it up after success.',
        '        if (!shouldCleanUp) return\n\n        // The recovery UI is ours, so clean it up after success.')
replace('    private fun cleanupRecoveryUi(remainingBacks: Int, remainingNullRetries: Int) {',
        '''    private fun cleanupRecoveryUi(remainingBacks: Int, remainingNullRetries: Int) {
        if (!isWifiConnected() || isDeviceLocked() || isRecoveryPending(this)) return''')
replace('remainingBacks = remainingBacks - if (backed) 1 else 0,',
        'remainingBacks = remainingBacks - 1, // Count attempts, even if Android rejects BACK.')
replace('    }.getOrDefault(false)\n\n    private fun isWifiConnected()',
        '    }.getOrDefault(true)\n\n    private fun isWifiConnected()')
replace('''        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)''',
        '''        // Local Wi-Fi is sufficient for ADB; cellular may still be the default Internet route.
        cm.allNetworks.any { network ->
            cm.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }''')

# Coalesce all navigation callbacks. Cleanup callbacks remain a separate, strictly bounded chain.
s = re.sub(r'handler\.postDelayed\(::(\w+), (\w+)\)', r'scheduleStep(::\1, \2)', s)
replace('            handler.post { beginRecoveryIfPossible() }',
        '            scheduleStep(::beginRecoveryIfPossible)')
replace('            if (enabled) instance?.handler?.post { instance?.beginRecoveryIfPossible() }',
        '            if (enabled) instance?.let { service ->\n                service.handler.post { service.scheduleStep(service::beginRecoveryIfPossible) }\n            }')
replace('        private const val RECOVERY_WINDOW_MS = 45_000L',
        '        private const val QUIET_POLL_MS = 5_000L')
for const in ['LOCK_RETRY_MS', 'WIFI_RETRY_MS', 'RECOVERY_RESTART_MS']:
    s, count = re.subn(r'        private const val '+const+r' = [\d_]+L\n', '', s)
    assert count == 1

assert 'restartIfExpired' not in s and 'recoveryDeadlineMs' not in s
assert 'handler.postDelayed(::' not in s
for name in ['openQuickSettings', 'driveSystemUi', 'driveSettingsUi']:
    assert f'private fun {name}() {{\n        if (!mayDriveUi()) return' in s
assert 'remainingBacks - if' not in s
p.write_text(s, encoding='utf-8')
print('PASS: build21 guarded UI entry/actions, coalesced navigation, persisted bounded retries, bounded cleanup')
