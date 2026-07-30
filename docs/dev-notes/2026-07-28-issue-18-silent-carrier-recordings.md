# Issue #18 — carrier recordings that are the right size and play back silent

**Issue:** https://github.com/madkongo/CallVault/issues/18
**Device:** Samsung Galaxy Z Fold 6, **SM-F956U** (US model), Android 16 (API 36), Android 16 throughout
**Status:** two hypotheses live (S1, S2) plus one advanced (A1); A2 killed 2026-07-30. **One artifact — a single recording file — would
discriminate between almost all of them.** Do not ship another diagnostic build before reading
"Why we keep guessing" at the bottom.

> This note has been wrong twice. Both times the error was inferring a fact instead of reading it:
> once reading "it worked" as "audio was audible", once inferring the OS version from the device's
> launch OS rather than the issue form. Facts below are cited to where they come from.

---

## What is established

From the **issue form** (not inferred — the reporter's own checklist):

| | |
|---|---|
| Experimental features | **VoIP recording ON**, resilient recording OFF, **offline recording ON** |
| Debug switches | USB debugging ON, Wireless debugging OFF |
| Call type | Carrier call |
| Codec | AAC 24 kbps · storage target **Both** |
| Symptom | "creates a recording that shows it contains data, however the playback is empty" |

From the **three exported logs**:

- Opus has **always** produced 0-byte files on this device. He switched to AAC because of that.
- He has been **switching capture sources**: log A has seven calls on `mic-voice-communication`
  between six on `voice-call`, in the order voice-call → mic → back to voice-call. **He never said
  which produced audio.** Going back to `voice-call` weakly suggests mic did not help.
- Every file is full-duration at full rate. Fitting `bytes = R·(T − d)` over all calls:

| log | build | source | path | kbps |
|---|---|---|---|---|
| A | 1.4.9 / 1.5.2 | `voice-call` | direct | 24.9, 24.1, 24.0, 23.9, 24.1 |
| A | 1.4.9 / 1.5.2 | `mic-voice-communication` | direct | 25.6, 25.5, 25.6, 25.5 |
| B | 1.5.2 ("VoIP off" test) | `voice-call` | direct | 23.1, 24.1 |
| C | 1.5.2-diag-scrcpy | `voice-call` | scrcpy | 25.8, 26.9, 26.9, 26.2, 26.6 |

  So **the encoder ran the whole call every time and was fed data at full rate.** Whatever is wrong,
  the capture is *delivering samples* — it is not stalling, erroring, or truncating.
- **No error appears anywhere in any log.**

From the **code**:

- The direct path (`DirectAudioRecorderSession`) opens `CHANNEL_IN_STEREO` **first**, falls back to
  mono, and always encodes **mono** — averaging via `PcmDownmix.stereoToMono`, `m = (l + r) / 2`.
- The scrcpy path is **stereo end to end**, with no downmix (`ScrcpyConfig.AUDIO_CHANNELS = 2`).
- Both paths run as **shell uid** in the daemon.
- The diag build's byte rate sits above every direct-path call, which is the mono→stereo signature.
  Independently: a binder can only reach the app process by a daemon *delivering* it, delivery
  happens once at daemon startup (`RecorderServer:109`) with no re-delivery, and installing the diag
  APK killed the app process — so the daemon serving those calls was launched from the diag APK.
  **The diagnostic did run.**

## What is NOT established

- **That anything ever worked.** "I switched to AAC and it worked" appears in the same breath as
  "those files are always recorded with zero bytes" and "there's data in it because it shows the byte
  size". It very plausibly means *"AAC produced non-empty files"*, not *"AAC produced audible audio"*.
  Every regression hypothesis rests on this sentence.
- **That the files are digitally silent.** Nobody has decoded one. "Silent" is a playback observation.
- **Whether the mic source produced audio.**
- **Whether stereo or mono capture was used** — the daemon logs `captureCh=` but daemon logs never
  reach the export (see below).

---

## Hypotheses

### S1 — The device blocks call capture for the shell uid. Nothing ever worked.

The boring one, and the most likely. US Samsung models are the notorious case. Both paths run as
shell, so both are silent; Opus's 0 bytes are an unrelated second bug; the OP12 is unaffected because
OnePlus does not block it.

*Predicts:* every source silent, including plain `mic`. *Killed by:* any source producing audio.
*Requires:* "it worked" to have meant "non-empty files" — which is the natural reading.

### S2 — Capture is fine; the failure is at playback.

He may be playing the Drive copy, or playing in something that cannot render the file. Note the file
is **48 kHz mono AAC-LC in m4a** on the direct path — legal but not the most common shape.

*Predicts:* the file decodes to real audio on a PC. *Killed by:* PC playback also silent.
*Cost:* zero. Already asked once, unanswered.

### A1 — Our downmix cancels the audio (advanced; the one I would test first)

The direct path prefers **stereo** capture, then averages the channels. `(l + r) / 2` is exactly zero
whenever the two channels are opposite. Devices do return correlated channel pairs on call/voice
sources — an echo/AEC reference alongside the primary is common — and a reference that is the
inverse of the signal averages to silence.

What makes this fit better than it first looks:

- **The downmix only executes where stereo capture initialises.** If the OP12's `voice-call` route is
  mono, `downmix` is false there and this code path has *never been exercised on a working device*.
  That is precisely the shape of a bug that survives all local testing.
- It explains the diag result **without** needing the diagnostic to have failed. scrcpy keeps both
  channels, so the diag file is not digitally silent — but a phone rendering a stereo file through a
  **mono** output downmixes L+R itself, and cancels it again at playback. He would report "still
  silent" for both builds, truthfully, from two different mechanisms.

*Predicts:* the direct-path file is all zeros; the **diag file has real audio in L and R that cancels
only when mixed to mono** — so it plays fine on a PC with headphones. *Killed by:* diag file
all-zeros in both channels, or direct-path capture reported as `captureCh=1`.

### A2 — The armed VoIP audio policy diverts the carrier downlink

**VoIP recording is confirmed ON** in the issue form. `VoipAudioPolicy.arm()` registers a system-wide
`AudioMix` on `USAGE_VOICE_COMMUNICATION` routed `ROUTE_FLAG_LOOP_BACK_RENDER` — which a carrier
downlink also matches. It is armed permanently by design (a policy registered mid-call attaches to
nothing), and **nothing disarms it for a carrier call**: `sync()` is called only from app start, the
Settings toggle, and daemon-ready. It sits upstream of both capture paths, so the diagnostic build
could never have cleared or convicted it.

**DEAD — 2026-07-30.** Carrier recording works on the maintainer's OP12 **with VoIP both on and
off**, which is exactly the test this hypothesis needed. An armed policy does not break carrier
capture.

Two things survive it and are worth keeping:

- **Nothing disarms the policy for a carrier call**, which is still untidy even though it is
  harmless. Not a bug to chase; note it and move on.
- The reason this took a round to kill is that the policy's state is unobservable from any log. See
  "Why we keep guessing".

### B1 — Opus 0-byte files (separate, real, and worth fixing regardless)

Opus has no encoder on this device → `supports()` returns false → scrcpy fallback → 0 bytes. Whatever
happens to issue #18, silently writing a 0-byte file is the wrong failure mode.

---

## The one thing that would settle this

**Ask for a file, not for more logs.** A single `.m4a` answers, in one `ffprobe` and one decode:

| observation | conclusion |
|---|---|
| 1 channel | direct path ran |
| 2 channels | scrcpy path ran — settles the "did the diag build run" argument outright |
| decodes to non-zero PCM | **S2** — capture works, this is a playback problem |
| all zeros | capture-side; S1 or A2 |
| 2 channels, L ≈ −R | **A1 confirmed** |

Second cheapest, and free: *did the `mic-voice-communication` calls have audio?* That splits
source-specific (workaround exists today) from global.

Third: the OP12 repro for A2, with VoIP recording switched **on**.

---

## Why we keep guessing — the actual defect to fix

1. **Daemon logs never reach a bug report.** The daemon runs as shell uid and cannot write the app's
   private log file, so `Direct capture started: … captureCh=… encodeCh=… rate=…` — the single line
   that would decide A1 — exists only in a process nobody can read. Every capture decision is
   invisible. Design: `2026-07-28-daemon-and-system-logs-design.md`; no plan yet.
2. **No level metric.** A byte count cannot distinguish silence from audio. An RMS/peak over the
   captured PCM, logged once per call, would have answered this on day one. This is the deferred
   `SILENT` check, and it should carry **channel count and level**, not just an all-zeros flag.
3. **The log export carries no settings snapshot.** VoIP on/off, source, codec, storage target — all
   of it had to be dug out of an issue form or guessed at.
4. **The redaction regex eats byte counts.** `AppLogger.redact` matches any bare 7–11 digit integer;
   one call's size logged as `[PHONE_REDACTED] bytes`. It destroys the numbers these investigations
   run on.
5. **A diagnostic build must be able to fail.** The `diag/scrcpy-only` build varied the one thing
   sitting *below* two of the three live hypotheses, so "still silent" was its most likely outcome
   whether or not the theory was right. Before the next one: state what result would kill the theory.

## Prior dead ends

- **The direct path's unattributed `AudioRecord`** (no package attribution → AppOps answers
  `MODE_IGNORED` with silence). This motivated `diag/scrcpy-only`. The scrcpy path carries scrcpy's
  `com.android.shell` fake-context workaround and was **also silent**, so this is dead as a *sole*
  cause. It remains a real latent wart.
- **An Android/One UI upgrade.** Dead: the reporter has been on Android 16 the whole time.
- **US Samsung firmware, fitted to the evidence.** An early analysis asserted this confidently and
  was wrong. S1 is the disciplined version of the same idea: stated as a hypothesis, with the
  observation that would kill it.
