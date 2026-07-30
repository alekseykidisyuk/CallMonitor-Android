# Issue #18 — carrier recordings that are the right size and completely silent

**Status:** diagnostic run by the reporter on 2026-07-29 — **still silent. The regression hypothesis is
dead.** See "The diagnostic result" below. Next step is a capture-source sweep, not a code fix.

> **v1.5.3 shipped on 2026-07-29 and contains no fix for this.** Verified, not assumed:
> `DirectAudioRecorderSession.kt` is byte-identical between `v1.5.2` and `v1.5.3`, and
> `diag/scrcpy-only` is not merged. There is nothing to fix yet because the cause is still
> unconfirmed — the diagnostic APK has not been run by anyone.
>
> Two things 1.5.3 does carry that touch this case without solving it: the setup-health card now
> reports an **empty recording**, which would have caught his Opus era (0-byte files) at the first
> call instead of over months; and the capture-path log line now names the route actually taken.
> Neither catches his *current* symptom — a file of the correct size that plays back silent. That
> needs the deferred **`SILENT`** detection (all-zeros check on the daemon's PCM), still unbuilt.
>
> **Open action:** the reporter may reasonably assume 1.5.3 supersedes the debug APK, since it is
> newer. A comment saying otherwise was offered and not yet sent.
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

## The diagnostic result — 2026-07-29

The reporter ran `1.5.2-diag-scrcpy (10624)` (confirmed in the export header) across six carrier calls
and reports **the recordings are still silent**. No error appears anywhere in the log.

### First: proving the diagnostic actually ran

This mattered more than the result. The log never names the daemon build, and it shows only
`Recorder daemon already connected; reusing existing binder` — never a launch line. `supports()` lives
in the **daemon**, which is a detached `app_process` that survives app updates, so a stale pre-diag
daemon serving the new app would have produced a false "still broken" while never running the change.

It was provable anyway, from the file sizes. Fitting `bytes = R·(T − d)` over each log's calls
(`T` = dispatch→release window) separates steady-state byte rate from startup delay:

Measured over every call in all three logs (window = `startRecording dispatched` → `Releasing
session resources`), grouped by build and selected source:

| log | build | source | path | kbps (calls >10 s) |
|---|---|---|---|---|
| A | 1.4.9 / 1.5.2 | `voice-call` | direct | 24.9, 24.1, 24.0, 23.9, 24.1 |
| A | 1.4.9 / 1.5.2 | `mic-voice-communication` | direct | 25.6, 25.5, 25.6, 25.5 |
| B | 1.5.2 | `voice-call` | direct | 23.1, 24.1 |
| C | **1.5.2-diag-scrcpy** | `voice-call` | scrcpy | **25.8, 26.9, 26.9, 26.2, 26.6** |

Every diag call sits **above every direct-path call**, including the mic captures. That is what the
code predicts: the direct path encodes **mono**
(`DirectAudioRecorderSession.ENCODE_CHANNELS = 1`) and lands on its 24 kbps target; scrcpy-server
"always outputs stereo" (`ScrcpyConfig.AUDIO_CHANNELS = 2`), and AAC-LC overshoots a 24 kbps target
with two channels. Fitting `bytes = R·(T − d)` also puts diag startup at 1.36 s against 0.88 s for
the direct path, consistent with spawning scrcpy.

So the evidence says **the scrcpy path ran** — contrary to the "the debug version never ran properly"
read. It is inference, not proof. The **decisive** check is one line of `ffprobe` on any of his files:
**2 channels = scrcpy ran, 1 channel = it did not.**

(Sizes alone still cannot distinguish silence from audio — CBR encodes zeros at the same rate. They
distinguish *which encoder configuration produced them*, which is a different question.)

### So the direct-path hypothesis is dead — but the diagnostic could not test the real suspect

The unattributed `AudioRecord` is **not** the cause: scrcpy's fake-`com.android.shell` context did not
help. The fault is upstream of the capture path.

**The diagnostic was built to answer the wrong question.** It swaps *which* capture path runs. The
leading suspect — an armed VoIP audio policy — sits **above both paths**, so this build was
structurally incapable of clearing or convicting it. "Still silent" was the guaranteed outcome either
way. That is a flaw in the diagnostic's design, not (necessarily) in how the reporter ran it.

### The hypothesis that actually fits: the VoIP policy is armed during carrier calls

`VoipAudioPolicy.arm()` registers a **system-wide** dynamic `AudioMix` matching
`USAGE_VOICE_COMMUNICATION`, routed `ROUTE_FLAG_LOOP_BACK_RENDER`. A **carrier** call's downlink is
rendered as voice communication too, so the mix matches telephony as well as VoIP apps and reroutes
that stream through a submix.

Three properties of the current design make this fit the evidence exactly:

1. **It is armed permanently.** `VoipCaptureController`: "armed when the user enables the feature,
   re-armed whenever the daemon is (re)launched, and left armed" — because a policy registered
   mid-call attaches to nothing. Deliberate, and correct for VoIP.
2. **Nothing disarms it for a carrier call.** `sync()` is called from app start, the Settings toggle,
   and daemon-ready. There is no `MODE_IN_CALL` hook. The mix is live through every carrier call.
3. **It is invisible.** `sync()` logs only on failure, and `arm`/`disarm` log inside the *daemon*,
   which cannot write the app's log file at all (shell uid vs app-private cache). No export we have
   can show whether the policy was armed.

It also explains everything the other hypotheses had to strain for: both paths silent (the policy is
upstream of both), no error, correctly-sized files, nothing device-specific needed beyond a ROM that
honours the reroute, and **"AAC worked on an earlier version"** — the VoIP engine shipped in
**v1.4.7** (`2b50fbe`); he first reported on **1.4.9**. Anything before 1.4.7, or before he switched
the toggle on, predates the mechanism.

The open assumption is that he enabled the toggle — it is default-off (`VOIP_RECORDING_ENABLED =
false`) and he has never confirmed it, despite being asked twice.

### This is reproducible without a Samsung

Turn VoIP recording **on** on any device, make an ordinary carrier call, and check whether the
recording goes silent. If it reproduces on the OP12, the bug is ours and the reporter is off the
critical path entirely.

### The fix, if it reproduces

Disarm on carrier-call start and re-arm on IDLE. A carrier call and a VoIP call are mutually
exclusive, so nothing is lost: the "must arm before the track exists" constraint only binds for VoIP
calls, and re-arming after the carrier call ends happens with no VoIP track in flight.

### The other unanswered question — did the mic source work?

Log A contains **seven** calls on `mic-voice-communication` alongside six on `voice-call`; he has been
switching sources all along and never said which produced audio. This single answer splits the
diagnosis:

- **mic works, `voice-call` silent** → source-specific block, and he has a workaround today.
- **both silent** → a global cause: the policy above, or playback.

`ScrcpyAudioSource` offers eleven sources, all selectable in Settings, so a sweep costs nothing.

### Two diagnostic-quality defects this exposed

1. **The log cannot identify which daemon build served a call.** The header reports the *app* version;
   the daemon's is nowhere. Since the daemon outlives app updates, every future diagnostic build has
   the same false-negative hole — this one only closed by arithmetic. The daemon should report its
   version on binder delivery, and the export header should carry it.
2. **The redaction regex eats byte counts.** `AppLogger.redact` matches any bare 7–11 digit integer, so
   one call's size logged as `[PHONE_REDACTED] bytes`. It destroys precisely the numbers these
   investigations depend on. It should not redact digits that are not in a phone-number context.

## Honest caveats

- **This is a hypothesis, not a finding.** An earlier analysis in this investigation confidently blamed
  US Samsung firmware and fitted the evidence to it; that was wrong, and it was wrong because the
  reporter's "and it worked" was read as "produced files" rather than "produced audible recordings".
  Re-read the primary source before trusting a chain of inference built on it.
- ~~The diagnostic APK has **not been run** by anyone.~~ Run on 2026-07-29; see the result above. The
  false-negative risk flagged here was real and nearly unfalsifiable — only the byte-rate fit ruled it
  out. Do not ship another diagnostic build until the daemon reports its own version.
- "Silent" is still the **reporter's** word. Nobody has decoded one of these files to confirm the PCM
  is all zeros rather than very quiet. Asking for one file would settle it — and its channel count
  would independently corroborate which path ran.
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
