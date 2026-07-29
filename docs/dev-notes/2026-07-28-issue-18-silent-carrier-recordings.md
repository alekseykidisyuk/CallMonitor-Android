# Issue #18 — carrier recordings that are the right size and completely silent

**Status:** hypothesis formed, diagnostic build published, **awaiting the reporter's result**
**Issue:** https://github.com/madkongo/CallVault/issues/18
**Reporter's device:** Samsung Galaxy Z Fold 6, **SM-F956U** (US model), Android 16, CallVault 1.5.2

---

## What was reported

Carrier recordings are created, show a plausible byte size, and play back **silent**. Storage target is
Both, so the local original is intact and untouched. Opus recordings have **always** produced 0-byte
files on this device; the reporter switched to **AAC 24 kbps**, and AAC **worked** on some earlier
version he no longer remembers.

## What the logs prove

Two independent calls, byte counts against capture duration at 24 kbps CBR (3,000 B/s):

| call | capture window | size | implied duration |
|---|---|---|---|
| outgoing | 39.9 s | 119,253 B | 39.75 s |
| incoming | 98.8 s | 296,917 B | 98.97 s |
| outgoing (later run) | 18.3 s | 52,821 B | 17.6 s |
| outgoing (later run) | 39.6 s | 119,125 B | 39.7 s |

The encoder ran for the full call every time and wrote a complete payload. No error appears anywhere in
his logs. **Truncation and container damage are ruled out.**

Critically: **CBR AAC encodes silence at exactly the same bitrate.** These sizes are equally consistent
with a stream of zeros, so they say nothing about whether audio was captured. That is the whole question.

## The hypothesis

**A regression introduced in v1.4.0** (`7df300a`, 2026-07-23), which added
`DirectAudioRecorderSession` — the direct `AudioRecord` → `MediaCodec` → `MediaMuxer` path that replaced
scrcpy for the common case.

The mechanism: that session opens the mic with the legacy constructor and **no package attribution**:

```kotlin
@Suppress("MissingPermission") // shell uid holds CAPTURE_AUDIO_OUTPUT; the daemon is not an app.
AudioRecord(androidSource, SAMPLE_RATE, channelMask, ENCODING_PCM_16BIT, minBuf * BUFFER_FACTOR)
```

AppOps evaluates `OP_RECORD_AUDIO` against the **package**, not merely the uid, and when it answers
`MODE_IGNORED` Android **feeds the caller silence rather than failing**: `state == STATE_INITIALIZED`,
reads succeed, every sample is zero. That is precisely the observed fingerprint — full duration, correct
size, no error, no audio.

scrcpy, the path CallVault used before v1.4.0, works around exactly this by building its `AudioRecord`
with a fake context whose package is `com.android.shell`. Our direct path dropped that workaround. The
code comment above shows the assumption behind it — true about the uid, and beside the point.

Why the codecs differ: AAC has an encoder on this device, so `supports()` holds and it takes the direct
path. Opus is a separate, older failure (0 bytes, never worked) and is **not** explained by this.

Why it was never caught: the OnePlus 12 tolerates the unattributed record. This is ROM-dependent.

## The diagnostic

Published as a **pre-release** so no existing install can be offered it:
https://github.com/madkongo/CallVault/releases/tag/v1.5.2-diag-scrcpy

- branch `diag/scrcpy-only` (commit `f0e3ba4`) = the `v1.5.2` tag with **one** change:
  `DirectAudioRecorderSession.supports()` returns `false`, forcing the scrcpy fallback
- versionCode **10624**, signed with the usual key, installs over 1.5.2 with no uninstall
- asset deliberately **not** named `CallVault.apk`, which is the only name the updater will download

**Reading the result:**

- **audio returns** → the v1.4.0 direct path is the regression. Fix: attribute the `AudioRecord` the way
  scrcpy does (`AudioRecord.Builder().setContext(...)` with a shell-package context, public since API
  31), keeping the scrcpy path as fallback where attribution is still refused.
- **still silent** → the hypothesis is dead; capture is silent on both paths and the cause is elsewhere
  (ROM-level block, or something upstream of capture entirely).

## Honest caveats

- **This is a hypothesis, not a finding.** An earlier analysis in this investigation confidently blamed
  US Samsung firmware and fitted the evidence to it; that was wrong, and it was wrong because the
  reporter's "and it worked" was read as "produced files" rather than "produced audible recordings".
  Re-read the primary source before trusting a chain of inference built on it.
- The diagnostic APK has **not been run** by anyone. If the scrcpy-only path has its own fault on that
  device, the reporter will report "still broken" for the wrong reason — a false negative.
- Whether VoIP recording was enabled during his tests is **not discernible from the logs**:
  `VoipCaptureController.sync()` logs only on failure, `VoipCallDetector` logs once at service start,
  and a carrier call sets `MODE_IN_CALL`, so an armed VoIP feature emits nothing during a carrier call.

## What this issue argues for, independently of its outcome

1. **Daemon diagnostics never reach a bug report.** Twice in this investigation the answer lived in
   `RecorderServer`'s log lines, which exist only in the daemon's process. Design written up in
   `2026-07-28-daemon-and-system-logs-design.md`.
2. **The unreleased `EMPTY_FILE` health reporting** would have surfaced his entire Opus era as "your
   last call produced an empty recording" instead of months of silent accumulation.
3. **The deferred `SILENT` detection** would have caught the AAC era at the first call — AAC takes the
   direct path, so PCM is visible in the daemon and an all-zeros check is cheap.
4. **The log export header carries device and version but not app settings.** Adding a settings snapshot
   (experimental toggles, storage target, audio source, codec) would have answered the VoIP question at
   a glance.
