package com.baba.callvault.callmonitor

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import com.baba.callvault.integrations.adb.HyperOsWirelessDebugAccessibilityService
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.LooperMode
import java.time.Duration

@ConscryptMode(ConscryptMode.Mode.OFF)
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34], application=Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class QuietRecoveryServiceTest {
    @Test fun pendingRecoveryAndSettingsEventStormStayQuietWithoutWifi() {
        val controller = Robolectric.buildService(HyperOsWirelessDebugAccessibilityService::class.java).create()
        val service = controller.get()
        try {
            val prefs = service.getSharedPreferences("callmonitor_hyperos_accessibility", Context.MODE_PRIVATE)
            prefs.edit().clear().putBoolean("wireless_debug_recovery_pending", true).commit()
            Settings.Global.putInt(service.contentResolver, "adb_wifi_enabled", 0)
            Settings.Global.putInt(service.contentResolver, Settings.Global.BOOT_COUNT, 20)
            shadowOf(service.getSystemService(ConnectivityManager::class.java)).clearAllNetworks()
            repeat(120) { i ->
                val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
                event.packageName = if (i % 2 == 0) "com.android.settings" else "com.android.systemui"
                service.onAccessibilityEvent(event)
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
            }
            assertTrue("Offline recovery must not open or dismiss any system screen",
                shadowOf(service).globalActionsPerformed.isEmpty())
            assertEquals(0, prefs.getInt("ui_attempts", 0))
            assertTrue(prefs.getBoolean("wireless_debug_recovery_pending", false))
        } finally { controller.destroy() }
    }
}
