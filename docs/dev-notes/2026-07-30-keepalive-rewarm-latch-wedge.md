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

## What triggered it — UNKNOWN, and an earlier claim here was wrong

An earlier version of this note asserted that the 1.5.3 USB install churned `adbd` and started the
chain. **That was an assumption written as a finding, and the maintainer's counter-evidence is
strong:** the cable has been connected and disconnected many times across many versions without this
ever happening. Cable churn alone does not explain it.

What the thread order does pin down is *where* it parked. TIDs had wrapped past `pid_max`, so the true
creation order was `cv-usbdefault-refresh` ×2 → `cv-keepalive-rewarm` (21033) → `cv-shell-probe` ×4
(21034-21089). The rewarm thread **spawned those probes**, so `ensureConnected` returned and
`waitForShellReady` ran and failed. With only one probe round present, it then parked on launch
attempt 2 — at `AdbShell.forceReconnect`, which is `@Synchronized` and called under
`heavyOperationLock`, holding both.

The concrete hang site is `connectLoopback`:

```kotlin
runCatching { mgr.connect("127.0.0.1", port) }   // no timeout anywhere
```

libadb's `connect` performs the ADB handshake. If adbd accepts the TCP connection but never completes
`CNXN`/`AUTH`, this blocks forever — while holding the AdbShell monitor and `heavyOperationLock`.

**A 1.5.3 regression was looked for and not found.** 1.5.3 changed **nothing** under
`integrations/**`, `server/**`, or `CallVaultApplication.kt` — verified by
`git diff v1.5.2..v1.5.3 --name-only`. Its 906 added lines are the setup-health feature: additive
bookkeeping that is either `Dispatchers.IO`-dispatched or `runCatching`-wrapped, with no ADB connect,
no lock acquisition, and no new startup path. `SetupPrerequisites.missing()` is called at call start
but performs only synchronous preference and `Settings.Global` reads.

That is a negative result, not a conclusion. **The evidence that would settle it still exists and has
not been read: the app's own `app_debug.log`**, which is a file that survived the force-stop and
covers yesterday. It is app-private and the release build is not debuggable, so it can only be
obtained by exporting a debug report from the app itself.

## The fix — implemented 2026-07-30

Layers 1 and 2 are done; layer 3 stays on the backlog.

1. ✅ **The latch cannot stick.** The two fields became `RewarmGate` (`RewarmGate.kt`), a small
   synchronized class that expires an in-flight attempt after `REWARM_STUCK_MS` (90 s) so a wedged
   one can be superseded instead of vetoing every future attempt. Pure logic, so it is unit-tested
   directly — nine cases in `RewarmGateTest`, including the wedge itself.
2. ✅ **`ensureServerRunning` is bounded.** `launchDaemonBounded()` runs it on a worker and
   `join(LAUNCH_BUDGET_MS)` (45 s), abandoning it on timeout exactly as `probeShellOnce` abandons a
   hung probe. On timeout it also calls the new `AdbShell.dropConnection()`, which closes the
   half-dead socket so the abandoned thread unwinds and **releases `heavyOperationLock`** — without
   that, every later attempt would queue behind it: bounded, but never succeeding.
3. ⬜ **Bound `AdbShell.ensureConnected` itself**, and make `isConnected` prove liveness rather than
   trusting a flag. Still open; this is the root, and layers 1-2 contain it rather than remove it.

Two things the tests caught that review had not:

- A `Long.MIN_VALUE` sentinel for "never attempted" **overflowed** `now - lastAttemptAtMs`, so the
  throttle silently blocked the very *first* warm-up. Replaced with an explicit flag.
- `tryEnter` runs on the watchdog's handler thread while `leave` runs on the worker it spawned, so
  the state crosses threads — the field it replaced was `@Volatile` for that reason. Both methods are
  synchronized.

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
