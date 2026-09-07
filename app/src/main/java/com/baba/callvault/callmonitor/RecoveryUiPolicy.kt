package com.baba.callvault.callmonitor

/** Main-thread gate for every recovery UI action. Persist State to retain the budget on reconnect. */
class RecoveryUiPolicy(initial: State = State()) {
    data class State(val boot: Int = -1, val attempts: Int = 0, val deadline: Long = 0,
                     val nextStart: Long = 0)
    enum class Decision { START, CONTINUE, WAIT, STOP }
    var state = initial
        private set

    fun evaluate(now: Long, boot: Int, wifi: Boolean, unlocked: Boolean,
                 allowStart: Boolean): Decision {
        if (state.boot != boot) state = State(boot = boot)
        if (state.deadline != 0L && (!wifi || !unlocked || now >= state.deadline)) {
            state = state.copy(deadline = 0, nextStart = now + COOLDOWN_MS)
        }
        if (!wifi || !unlocked) return Decision.WAIT
        if (state.deadline != 0L) return Decision.CONTINUE
        if (state.attempts >= MAX_ATTEMPTS) return Decision.STOP
        if (!allowStart || now < state.nextStart) return Decision.WAIT
        state = state.copy(attempts = state.attempts + 1, deadline = now + WINDOW_MS)
        return Decision.START
    }

    fun succeeded(now: Long) {
        state = state.copy(deadline = 0, nextStart = now + COOLDOWN_MS)
    }

    companion object {
        const val WINDOW_MS = 45_000L
        const val COOLDOWN_MS = 300_000L
        const val MAX_ATTEMPTS = 3
    }
}
