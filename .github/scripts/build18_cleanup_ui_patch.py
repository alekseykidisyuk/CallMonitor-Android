from pathlib import Path

p = Path("app/src/main/java/com/baba/callvault/integrations/adb/HyperOsWirelessDebugAccessibilityService.kt")
s = p.read_text(encoding="utf-8")

old = '''    private fun finishRecoverySuccess() {
        setRecoveryPending(this, false)
        recoveryDeadlineMs = 0L
        searchQuerySet = false
        AppLogger.i(TAG, "Accessibility recovery enabled Wireless debugging; handing back to recorder recovery")
        AdbConnectionService.start(applicationContext)
        handler.postDelayed({ runCatching { performGlobalAction(GLOBAL_ACTION_BACK) } }, RETURN_BACK_DELAY_MS)
    }
'''

new = '''    private fun finishRecoverySuccess() {
        setRecoveryPending(this, false)
        recoveryDeadlineMs = 0L
        searchQuerySet = false
        AppLogger.i(TAG, "Accessibility recovery enabled Wireless debugging; handing back to recorder recovery")
        AdbConnectionService.start(applicationContext)

        // The recovery UI is ours, so clean it up after success. A single BACK is not enough on
        // HyperOS: Quick Settings -> Settings -> Search -> Wireless debugging creates several visible
        // layers. Walk BACK only while the foreground surface still belongs to Settings/SystemUI;
        // stop immediately once Android has returned to the user's previous app/home screen.
        handler.postDelayed(
            { cleanupRecoveryUi(CLEANUP_MAX_BACKS, CLEANUP_NULL_RETRIES) },
            RETURN_BACK_DELAY_MS,
        )
    }

    private fun cleanupRecoveryUi(remainingBacks: Int, remainingNullRetries: Int) {
        if (remainingBacks <= 0) {
            AppLogger.w(TAG, "Accessibility UI cleanup reached BACK limit; leaving current screen untouched")
            return
        }

        val pkg = rootInActiveWindow?.packageName?.toString()
        when (pkg) {
            SETTINGS_PACKAGE, SYSTEMUI_PACKAGE -> {
                val backed = runCatching { performGlobalAction(GLOBAL_ACTION_BACK) }.getOrDefault(false)
                AppLogger.i(TAG, "Accessibility cleanup BACK from $pkg=$backed")
                handler.postDelayed(
                    {
                        cleanupRecoveryUi(
                            remainingBacks = remainingBacks - if (backed) 1 else 0,
                            remainingNullRetries = remainingNullRetries,
                        )
                    },
                    CLEANUP_BACK_DELAY_MS,
                )
            }

            null -> {
                if (remainingNullRetries > 0) {
                    handler.postDelayed(
                        { cleanupRecoveryUi(remainingBacks, remainingNullRetries - 1) },
                        CLEANUP_BACK_DELAY_MS,
                    )
                } else {
                    AppLogger.d(TAG, "Accessibility UI cleanup stopped because active package is unavailable")
                }
            }

            else -> AppLogger.i(TAG, "Accessibility UI cleanup complete; returned to $pkg")
        }
    }
'''

assert old in s, "finishRecoverySuccess block not found after build16/build17 patches"
s = s.replace(old, new, 1)

old_consts = '''        private const val RETURN_BACK_DELAY_MS = 1_200L
        private const val WIRELESS_SEARCH_QUERY = "Отладка по Wi-Fi"
'''
new_consts = '''        private const val RETURN_BACK_DELAY_MS = 1_200L
        private const val CLEANUP_BACK_DELAY_MS = 450L
        private const val CLEANUP_MAX_BACKS = 6
        private const val CLEANUP_NULL_RETRIES = 4
        private const val WIRELESS_SEARCH_QUERY = "Отладка по Wi-Fi"
'''
assert old_consts in s, "cleanup constants marker not found"
s = s.replace(old_consts, new_consts, 1)

p.write_text(s, encoding="utf-8")
