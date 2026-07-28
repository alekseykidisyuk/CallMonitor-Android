/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.health

import android.app.Application
import android.content.Context
import android.provider.Settings
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.server.IRecorderService
import com.baba.callvault.server.RecorderConnection
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SetupPrerequisitesTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val preferences = AppPreferences(context)

    /**
     * Satisfies every prerequisite EXCEPT the ones a test deliberately breaks, so each test isolates
     * exactly one precedence step instead of accidentally also tripping an earlier one.
     */
    private fun satisfyAllPrerequisites() {
        preferences.setRecordingFolderUri("content://tree/a".toUri())
        preferences.setAdbPaired(true)
        Settings.Global.putString(context.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, "1")
        shadowOf(ApplicationProvider.getApplicationContext<Application>())
            .grantPermissions(android.Manifest.permission.WRITE_SECURE_SETTINGS)
    }

    @After
    fun tearDown() {
        // RecorderConnection is a process-wide singleton; leaving it connected would leak into
        // whichever test runs next in the same JVM.
        RecorderConnection.onBinderDied()
    }

    @Test
    fun `missing recording folder is reported first, ahead of every later prerequisite`() {
        satisfyAllPrerequisites()
        preferences.setRecordingFolderUri(null)

        assertEquals(Prerequisite.RECORDING_FOLDER, SetupPrerequisites.missing(context))
    }

    @Test
    fun `missing ADB pairing is reported once the folder is set`() {
        satisfyAllPrerequisites()
        preferences.setAdbPaired(false)

        assertEquals(Prerequisite.ADB_PAIRING, SetupPrerequisites.missing(context))
    }

    @Test
    fun `developer options explicitly disabled is reported once folder and pairing are fine`() {
        satisfyAllPrerequisites()
        Settings.Global.putString(context.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, "0")

        assertEquals(Prerequisite.DEVELOPER_OPTIONS, SetupPrerequisites.missing(context))
    }

    @Test
    fun `missing secure settings grant is reported only when the daemon is also disconnected`() {
        satisfyAllPrerequisites()
        shadowOf(ApplicationProvider.getApplicationContext<Application>())
            .denyPermissions(android.Manifest.permission.WRITE_SECURE_SETTINGS)

        assertEquals(Prerequisite.SECURE_SETTINGS_GRANT, SetupPrerequisites.missing(context))
    }

    @Test
    fun `a missing secure settings grant is excused while the daemon is connected`() {
        // The grant is only needed to relaunch a DEAD daemon; a live one means recording works right
        // now regardless of the grant, so this must NOT read as missing.
        satisfyAllPrerequisites()
        shadowOf(ApplicationProvider.getApplicationContext<Application>())
            .denyPermissions(android.Manifest.permission.WRITE_SECURE_SETTINGS)
        RecorderConnection.onBinderReceived(mockk<IRecorderService>(relaxed = true))

        assertNull(SetupPrerequisites.missing(context))
    }

    @Test
    fun `nothing is missing once every prerequisite is met`() {
        satisfyAllPrerequisites()

        assertNull(SetupPrerequisites.missing(context))
    }

    @Test
    fun `an absent developer-options global is not treated as missing`() {
        // Mirrors DeveloperOptions.isExplicitlyDisabled: a ROM that doesn't expose the global must
        // not paint a permanent red banner.
        preferences.setRecordingFolderUri("content://tree/a".toUri())
        preferences.setAdbPaired(true)
        shadowOf(ApplicationProvider.getApplicationContext<Application>())
            .grantPermissions(android.Manifest.permission.WRITE_SECURE_SETTINGS)

        assertNull(SetupPrerequisites.missing(context))
    }

    // ── recordGapForMissingPrerequisite: the CallSessionManager seam ──────────────────────────
    //
    // CallSessionManager.evaluateAndStartService() is not practical to drive directly under
    // Robolectric: it is a process-wide singleton wired to live TelephonyManager broadcasts, a
    // 500ms coroutine "verification window", and contact-lookup content-resolver queries, none of
    // which this feature touches. The actual decision it needs — "if a prerequisite is missing,
    // report this call's gap; otherwise report nothing" — is entirely captured by
    // [recordGapForMissingPrerequisite], which CallSessionManager calls verbatim. Testing here
    // exercises the exact function production code runs, rather than re-deriving the same branch
    // inside the test.

    @Test
    fun `a prerequisite-missing call start records a gap`() {
        val store = SetupHealthStore(context)

        store.recordGapForMissingPrerequisite(Prerequisite.DEVELOPER_OPTIONS, 1_000L, "Alice")

        val facts = store.read()
        assertEquals(1_000L, facts.lastGapAt)
        assertEquals("Alice", facts.lastGapLabel)
    }

    @Test
    fun `a prerequisites-met call start records nothing`() {
        val store = SetupHealthStore(context)

        store.recordGapForMissingPrerequisite(null, 1_000L, "Alice")

        val facts = store.read()
        assertEquals(0L, facts.lastGapAt)
        assertNull(facts.lastGapLabel)
    }
}
