package com.baba.callvault.callmonitor

import org.junit.Assert.*
import org.junit.Test

class RecoveryUiPolicyTest {
    private val start = RecoveryUiPolicy.Decision.START
    private val go = RecoveryUiPolicy.Decision.CONTINUE
    private val wait = RecoveryUiPolicy.Decision.WAIT
    private val stop = RecoveryUiPolicy.Decision.STOP

    @Test fun rebootOfflineAndRepeatedScreenEventsNeverStartUiOrConsumeBudget() {
        val p = RecoveryUiPolicy()
        repeat(1000) {
            assertEquals(wait, p.evaluate(it * 1000L, 12, false, true, it % 2 == 0))
        }
        assertEquals(0, p.state.attempts)
        assertEquals(start, p.evaluate(1_000_000, 12, true, true, true))
    }

    @Test fun screenEventsCannotStartAnUninitialisedWindow() {
        val p = RecoveryUiPolicy()
        assertEquals(wait, p.evaluate(100, 1, true, true, false))
        assertEquals(start, p.evaluate(100, 1, true, true, true))
        assertEquals(go, p.evaluate(101, 1, true, true, false))
    }

    @Test fun lockingOrLosingWifiStopsAnActiveWindowImmediately() {
        for (wifi in listOf(false, true)) {
            val p = RecoveryUiPolicy()
            assertEquals(start, p.evaluate(100, 1, true, true, true))
            assertEquals(wait, p.evaluate(200, 1, wifi, !wifi, false))
            assertEquals(0L, p.state.deadline)
            assertEquals(wait, p.evaluate(201, 1, true, true, true))
            assertEquals(start, p.evaluate(300_200, 1, true, true, true))
        }
    }

    @Test fun lockedPhoneWaitsWithoutUsingAttempt() {
        val p = RecoveryUiPolicy()
        assertEquals(wait, p.evaluate(100, 1, true, false, true))
        assertEquals(0, p.state.attempts)
        assertEquals(start, p.evaluate(200, 1, true, true, true))
    }

    @Test fun watchdogCannotExtendDeadlineOrBypassCooldown() {
        val p = RecoveryUiPolicy()
        assertEquals(start, p.evaluate(100, 1, true, true, true))
        for (now in 101L until 45_100L step 1000L) {
            assertEquals(go, p.evaluate(now, 1, true, true, true))
            assertEquals(45_100L, p.state.deadline)
        }
        assertEquals(wait, p.evaluate(45_100, 1, true, true, true))
        assertEquals(wait, p.evaluate(345_099, 1, true, true, true))
        assertEquals(start, p.evaluate(345_100, 1, true, true, true))
    }

    @Test fun reconnectCannotResetBudgetAndRetriesEndAfterThreeWindows() {
        var p = RecoveryUiPolicy()
        var now = 100L
        repeat(3) {
            assertEquals(start, p.evaluate(now, 1, true, true, true))
            now += RecoveryUiPolicy.WINDOW_MS
            p.evaluate(now, 1, true, true, true)
            p = RecoveryUiPolicy(p.state) // Accessibility service recreated
            now += RecoveryUiPolicy.COOLDOWN_MS
        }
        assertEquals(stop, p.evaluate(now, 1, true, true, true))
        assertEquals(stop, p.evaluate(now + 1_000_000, 1, true, true, true))
        assertEquals(start, p.evaluate(100, 2, true, true, true))
        assertEquals(1, p.state.attempts)
    }

    @Test fun successDoesNotEraseBudgetForARepeatedFailingDaemon() {
        val p = RecoveryUiPolicy()
        p.evaluate(100, 1, true, true, true)
        p.succeeded(200)
        assertEquals(1, p.state.attempts)
        assertEquals(wait, p.evaluate(201, 1, true, true, true))
    }
}
