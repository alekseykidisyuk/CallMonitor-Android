from pathlib import Path

p = Path("app/src/main/java/com/baba/callvault/integrations/adb/HyperOsWirelessDebugAccessibilityService.kt")
s = p.read_text(encoding="utf-8")

old = '''        val wirelessLabel = findFirstTextNode(root, WIRELESS_DEBUG_LABELS)
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
'''

new = '''        // Search mode must be handled BEFORE generic Wireless-debugging text matching. On HyperOS the
        // search EditText itself contains exactly "Отладка по Wi-Fi". Build 16 treated that EditText as
        // the target row, repeatedly clicked/focused the search box and never opened the actual result.
        val editor = findDescendant(root) { node -> node.isVisibleToUser && node.isEnabled && node.isEditable }
        if (editor != null) {
            val current = editor.text?.toString().orEmpty()
            if (!searchQuerySet || !current.contains(WIRELESS_SEARCH_QUERY, ignoreCase = true)) {
                val args = Bundle().apply {
                    putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        WIRELESS_SEARCH_QUERY,
                    )
                }
                val set = editor.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                searchQuerySet = set
                AppLogger.i(TAG, "Accessibility entered Wireless-debugging search query=$set")
                handler.postDelayed(::driveSettingsUi, SEARCH_RESULT_SETTLE_MS)
                return
            }

            val result = findWirelessSearchResult(root)
            if (result != null && clickWithGuard(result)) {
                AppLogger.i(TAG, "Accessibility clicked Wireless-debugging SEARCH RESULT")
                handler.postDelayed(::driveSettingsUi, SETTINGS_OPEN_SETTLE_MS)
                return
            }

            AppLogger.d(TAG, "Accessibility search query is present but result row is not clickable yet")
            handler.postDelayed(::driveSettingsUi, SEARCH_RESULT_SETTLE_MS)
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

            // Developer-options row has no local switch: open the row.
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
'''

assert old in s, "build16 Settings-search block not found"
s = s.replace(old, new, 1)

marker = '''    private fun findFirstSwitch(root: AccessibilityNodeInfo): AccessibilityNodeInfo? =
        findDescendant(root) { node ->
            node.isCheckable && node.isEnabled &&
                node.className?.toString()?.contains("Switch", ignoreCase = true) == true
        }

'''
helper = '''    /** Return the first real Settings search-result row, never the search EditText itself. */
    private fun findWirelessSearchResult(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        for (label in WIRELESS_DEBUG_LABELS) {
            val matches = root.findAccessibilityNodeInfosByText(label)
            for (node in matches) {
                if (!node.isVisibleToUser || node.isEditable) continue
                val cls = node.className?.toString().orEmpty()
                if (cls.contains("EditText", ignoreCase = true)) continue

                val row = findClickableAncestor(node) ?: node.takeIf { it.isClickable && it.isEnabled } ?: continue
                // Search-bar containers can expose a non-editable child carrying the same text. Reject any
                // candidate whose clickable row also contains an editable descendant.
                val hasEditor = findDescendant(row) { candidate -> candidate.isEditable }
                if (hasEditor != null) continue
                return row
            }
        }
        return null
    }

'''
assert marker in s, "findFirstSwitch marker not found"
s = s.replace(marker, marker + helper, 1)

p.write_text(s, encoding="utf-8")
