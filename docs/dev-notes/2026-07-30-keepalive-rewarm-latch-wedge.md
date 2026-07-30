# The keep-alive rewarm latch can permanently disable recording

**Found:** 2026-07-30, on the maintainer's OP12, by diagnosis — not by the app noticing.
**Severity:** high. Recording stops completely, silently, until the app process is killed. No user
would ever discover the recovery.
**Introduced:** `a2b248e`, shipped in **v1.4.0**. **Not a 1.5.3 regression** — 1.5.3's install merely
supplied the trigger.

## Symptom

"Since yesterday recordings aren't working." The app looked healthy from the outside: process alive,
`DaemonKeepAliveService` foreground, battery-exempt, standby bucket EXEMPTED,
`WRITE_SECURE_SETTINGS` granted, adbd's tcpip listener up on its port.

But the daemon was **dead and never being relaunched**, and had been for ~21 hours.

## Evidence, in the order it landed

1. `pgrep -f com.baba.callvault.server.Recorder[S]erver` → nothing. No daemon = no recording.
2. Launching the daemon **by hand** with the app's own command worked first try
   (`RecorderServer starting pid=… uid=2000`). So the daemon was fine; the app was not launching it.
3. The app held exactly one socket to adbd, in **`CLOSE_WAIT`**:

   ```
   127.0.0.1:34114 → 127.0.0.1:51392   state 08   uid 10546
   ```

   adbd had hung up; the app never closed its end. Sampled three times over 36 s: **identical local
   port, no new connection attempts, no daemon.** The app was not retrying.
4. Thread list for the app process still contained a live **`cv-keepalive-rewarm`** thread —
   state `S`, `00:00:00` CPU. Parked, not spinning. Plus four leaked `cv-shell-probe` threads,
   which is `probeShellOnce`'s documented abandon-on-timeout behaviour firing over and over against
   the dead socket.

## Root cause

`DaemonKeepAliveService.maybeRewarm`:

```kotlin
private fun maybeRewarm(force: Boolean = false) {
    if (rewarming) return                    // ← guard
    ...
    rewarming = true
    Thread {
        val ok = runCatching { RecorderServerLauncher.ensureServerRunning(applicationContext) }…
        rewarming = false                    // ← ONLY cleared here
    }.start()
}
```

`rewarming` is a latch cleared exclusively by the worker thread finishing. The worker calls
`ensureServerRunning`, which takes `synchronized(AdbShell.heavyOperationLock)` and then
`AdbShell.ensureConnected` — and **`ensureConnected` is unbounded** (already a known backlog item,
previously filed as a latency problem). On a half-dead connection it blocks indefinitely.

So one hung relaunch attempt latches `rewarming` **true for the life of the process**, and the 60 s
watchdog then returns at its first line, forever. The daemon is never relaunched again. The only
escape is killing the app — which nothing in the product ever does or suggests.

`ensureConnected` returning `true` on a stale connection is what starts the chain: it trusts
`AdbConnectionManager.isConnected`, which is a state flag, not a liveness check. A `CLOSE_WAIT`
socket satisfies it.

## Why yesterday

The 1.5.3 release was installed over USB at 13:15. Attaching or detaching USB churns `adbd`, which
dropped the app's loopback connection. The socket went to `CLOSE_WAIT`, the first watchdog rewarm
wedged on it, and the latch closed. Everything after that was silence.

The latch bug is from v1.4.0. Any adbd churn can trigger it — a cable, a screen-off adbd restart, a
debugging-switch toggle. **This has almost certainly been happening to users in the field**, reported
as "it just stopped recording", and is a strong candidate for reports we have already dismissed.

## The fix

Three layers, in priority order:

1. **The latch must not be able to stick.** Record `rewarmStartedAtMs` alongside the flag and treat
   `rewarming` as expired past a deadline (~2× the `ensureServerRunning` budget, so ~60 s), so a
   wedged attempt cannot veto every future one. Cheapest correct fix; do this first.
2. **Bound `ensureServerRunning` at the call site.** Run it on a thread and `join(timeout)`,
   abandoning it exactly as `probeShellOnce` already abandons a hung probe. That pattern is present
   and proven in this codebase — apply it here.
3. **Bound `AdbShell.ensureConnected` itself**, and make `isConnected` prove liveness rather than
   trusting a flag. This is the existing backlog entry, now demonstrated to cause a total outage
   rather than a delay — reprioritise it accordingly.

Worth adding regardless: a **stuck-rewarm** signal in the setup-health card. The status card added in
1.5.3 reports an empty recording; it cannot report "the daemon has been down for 21 hours", which is
the louder failure.

## Recovery, for anyone hitting this before the fix ships

```bash
adb shell am force-stop com.baba.callvault
adb shell am start -n com.baba.callvault/.MainActivity   # must relaunch: a force-stopped app gets no broadcasts
```

Verified on device: daemon back within ~25 s, socket `ESTABLISHED` on a fresh port, zero wedged
threads, stable on re-check. Non-destructive — no effect on ADB pairing or app data.

## What made this expensive to find

The app's log lives in app-private storage and the release build is not debuggable, so on a release
device the log is unreadable — `run-as` is refused and logcat is silent on this ROM. The entire
diagnosis had to come from `/proc/net/tcp`, `ps -T` thread names, and `dumpsys`. Every conclusion
above was reached *without* a single line of the app's own logging. See
`2026-07-28-daemon-and-system-logs-design.md`.
