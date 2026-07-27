# Track A — implementation plan

**Goal:** take the daemon off the call path. A capture track is created once while privileged, handed to
the app, and simply **started** when a call begins — so a call no longer waits for a daemon launch.

**Why it is worth doing:** a dead daemon costs an 18-second cold start, measured, which is longer than a
short call. That is how a real outgoing call was lost on 2026-07-27.

**WORKING end to end on a OnePlus 12, 2026-07-27.** Two consecutive calls recorded through a held
track with the creating daemon killed and never consulted:

```
14:14:57.483  OFFHOOK
14:14:57.824  Instant: starting the held track (no daemon involved)
14:14:57.826  IAudioRecord.start accepted from the app
14:14:57.827  Instant: capture live
```

**3 ms from start to live**, 344 ms from OFFHOOK (the rest is metadata and SAF file creation, which the
old path also pays) — against an 18-second daemon cold start. Recordings verified by ear.

**Proven earlier the same day, not assumed:**

- A track created with no call active, held **stopped**, started at call-connect: **−6.7 dBFS**
- The **app** may start a track the **daemon** created: accepted by AudioFlinger from an unprivileged uid
- With the creating daemon **killed by pid**: **−6.3 dBFS over 1,151,936 samples** during a verified call

---

## Shape

```
once per boot / on arm            per call
──────────────────────            ────────
daemon creates VOICE_CALL         app: HeldRecordControl.start(binder)
  track, STOPPED                  app: drain ring → encoder → SAF file
hands binder + cblk to app        app: HeldRecordControl.stop(binder)
daemon may die freely             ← no daemon, no ADB, no debugging switch
```

Everything after the handoff already exists: `HandoffReceiver` drains the ring and `HandoffEncoder`
writes the container. Track A is the *arming* and the *per-call start*, not a new capture pipeline.

## Non-negotiables

1. **Opt-in, default off, experimental** — same as Resilient recording and VoIP. It changes how
   recording *starts*, so a regression costs whole calls rather than degrading quality.
2. **Off means byte-identical to today.** The existing path must not be touched, only bypassed.
3. **Every failure falls back to the daemon path, and says so.** Silent fallback is acceptable at call
   time (recording the call matters more than explaining); silent *failure* is not.
4. **No permanent wakelock, no 24/7 mic indicator.** The track is held STOPPED, which costs neither —
   that is the reason it is held stopped and not started.

---

## Work, in order

### 1. Arming and the held track — `HeldTrackStore`

App-side holder for `binder + cblk + geometry`, plus `isUsable()` (binder ping).

- **Arm** when: the toggle is switched on, `RecorderConnection.onDaemonReady`, and app start.
- **Re-arm** when: the stored track fails a ping, or a call ends having used it (see §4).
- Arming calls the existing `startHandoffHeld` and must never block a call — it runs off the call path
  entirely, so a slow arm is invisible.

**Note:** the track dies with the *app* process (the app holds the only ref once the daemon is gone),
so arming must happen on every app start. That is cheap and needs the daemon, which is alive then.

### 2. Per-call start — a branch in `AudioRecordingEngine.startPipeline`

```
if (trackAEnabled && heldTrack.isUsable() && source == voice-call) → start held track, drain, encode
else                                                              → today's path, unchanged
```

The branch sits **before** `ensureServerRunning`, because skipping that call is the entire point.

### 3. Failure handling

| Failure | Detection | Response |
|---|---|---|
| No track armed | `isUsable()` false at call start | fall back to daemon path, re-arm after |
| `start()` refused | return value | fall back, re-arm after |
| Track evicted mid-call | `CBLK_INVALID` + stalled-ring detectors already in `nativeDrainToPipe` | finalise what was captured, notify honestly, re-arm |
| Ring geometry rejected | `HandoffGeometry.validationError()` | fall back |

Eviction is the risk that matters: `VOICE_CALL` has **source priority 0** on this device and shares an
input profile at `maxOpenCount: 2`, so another input client can take it. It cannot be prevented — only
detected and recovered from.

### 4. Reuse across calls — ANSWERED: reusable

Measured on a OnePlus 12, 2026-07-27: **two consecutive calls on the same held track**, both recorded,
both sounding correct, with no re-arm between them. So arm once per boot — the simpler shape — and no
re-arm-after-call logic is needed.

### 5. Surfacing it

- Settings ▸ Experimental ▸ **Instant recording**, with the honest description: recording starts
  immediately because capture is prepared in advance; if the prepared capture is lost, CallVault falls
  back to the slower path.
- The readiness notification should distinguish *armed* from merely *daemon alive* — they are no longer
  the same thing.
- The debug log must record which path each recording used. Without that, "did Track A actually run?"
  becomes guesswork, exactly as "did the observer fire?" did.

---

## Test plan (OnePlus 12)

Order matters: each step assumes the previous passed.

1. ✅ **Arms** — toggle on, log shows armed, no call involved.
2. ✅ **Survives the daemon** — `kill -9` the daemon by pid; the track is still usable.
3. ✅ **Records a call with the daemon dead** — verified by ear, not by file size.
4. ✅ **Instant start** — 3 ms to live, no `ensureServerRunning` anywhere on the call path.
5. ✅ **Second call** — reusable; no re-arm needed.
6. ✅ **Reboot** — re-arms without intervention. Measured: boot 14:24:01 → armed 14:24:18, i.e.
   **16.4 s**, during which a call falls back to the daemon path (correctly, and still recorded). The
   Wireless-debugging flicker users will see at boot *is* the arming: it comes on to reach the daemon
   and goes off again once USB debugging can hold `adbd`.
7. **Idle for hours, then call** — the eviction question, and the one that needs patience rather than
   cleverness.
8. **Screen lock mid-call** — the classic `adbd` killer; the recording must be unaffected.
9. **Toggle off** — today's behaviour returns exactly.

**Do not skip 9.** The reversibility guarantee is what makes shipping this defensible.

## Rollback

The toggle. Off restores today's path completely, because the existing code is bypassed rather than
modified.

## Not in scope

- Removing the debugging switch — impossible without root; the daemon is still needed to *create* the
  track once per boot.
- Retiring the loopback — re-creating an evicted track needs the daemon, hence ADB.
- VoIP calls — Track A is the `VOICE_CALL` (carrier) path; VoIP has its own armed policy already.
