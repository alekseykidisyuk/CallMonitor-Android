/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.services.recording.DaemonKeepAliveService
import com.baba.callvault.system.AppLock
import com.baba.callvault.ui.screens.AppLockScreen

/**
 * MainActivity is the single Android Activity entry point for CallVault.
 * This is called when Android want to show the application UI to the user.
 *
 * It attaches the Compose content tree to the window and draws edge-to-edge (the navy background
 * extends behind the transparent system bars), and it holds the app lock — the one piece of state
 * that has to live at the Activity level, because it is about the window rather than about any screen.
 */
class MainActivity : AppCompatActivity() {

    /**
     * Whether the current visit has been authenticated.
     *
     * Reset in [onStop] rather than `onPause`, and this is the load-bearing detail: the biometric
     * prompt is a dialog, so it pauses the activity without stopping it. Clearing this in `onPause`
     * would re-lock the app the instant the prompt appeared and ask again for ever.
     */
    private var isUnlocked by mutableStateOf(false)

    /** Guards against a second prompt while one is already on screen, for the same reason. */
    private var isPrompting = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            if (isUnlocked || !AppLock.isEnabled(this)) {
                AppNavigationScreen()
            } else {
                // A door rather than a blank screen: the prompt can be dismissed, and someone who
                // dismissed it by accident needs a way back in that is not "kill the app".
                AppLockScreen(onUnlock = ::promptForUnlock)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        applySecureFlag()
        if (AppLock.isEnabled(this) && !isUnlocked) promptForUnlock()
    }

    override fun onResume() {
        super.onResume()
        // Anchor the recorder daemon whenever the app is opened. Modern Android requires a foreground
        // context to (re)start a foreground service, so we do it here rather than from Application.onCreate.
        // Idempotent — no-op if the keep-alive service is already running.
        //
        // Deliberately NOT behind the lock: recording is the app's job and must not wait on someone
        // being present to authenticate. The lock hides what was said, it does not stop the recorder.
        if (AppPreferences(applicationContext).isPrivilegedTransportSetUp()) {
            DaemonKeepAliveService.start(applicationContext)
        }
    }

    override fun onStop() {
        super.onStop()
        // Re-lock on the way out, so returning from the recents list asks again.
        if (AppLock.isEnabled(this)) isUnlocked = false
    }

    /**
     * Keeps the window's content out of screenshots and the recents thumbnail.
     *
     * Part of the same setting rather than a second one: a lock that still shows the last transcript
     * as a thumbnail in the app switcher is not a lock, and nobody who wanted the first would decline
     * the second.
     */
    private fun applySecureFlag() {
        if (AppLock.isEnabled(this)) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    private fun promptForUnlock() {
        if (isPrompting) return
        isPrompting = true

        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    isPrompting = false
                    isUnlocked = true
                }

                override fun onAuthenticationError(code: Int, message: CharSequence) {
                    // Errors and cancellations both land here, and neither unlocks anything. The
                    // lock screen stays, with its own button, so a mis-tap is recoverable without
                    // this having to tell the two apart.
                    isPrompting = false
                }

                override fun onAuthenticationFailed() {
                    // A rejected fingerprint. The prompt is still up and will try again; doing
                    // anything here would only get in its way.
                }
            }
        )

        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(getString(R.string.app_lock_prompt_title))
                .setAllowedAuthenticators(AppLock.allowedAuthenticators())
                .build()
        )
    }
}
