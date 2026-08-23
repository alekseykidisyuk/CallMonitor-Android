# Transcription Plan 2A — Capture-side speaker track

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development
> (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** Record *who was speaking when* at capture time, so transcripts can be labelled by speaker
without any diarization model — while leaving the recorded audio byte-for-byte unchanged.

**Architecture:** The daemon already captures the two call directions on separate stereo channels and
averages them to mono before encoding. This plan taps that existing loop to accumulate per-window
channel energy, coalesces it into a compact turn list, and hands it to the app after the recording
stops via a new additive AIDL method — the same shape as the existing `voipFarPartyHeard()`.

**Tech Stack:** Kotlin, AIDL, Room (the `transcripts.db` from Plan 2).

**Spec:** `docs/dev-notes/2026-08-16-on-device-transcription-design.md`
**Runs independently of Plan 2** — nothing here needs a transcript to exist.

---

## STATUS — 2026-08-23

| task | state |
|---|---|
| 1 — detector as pure logic | ✅ done, `866dd2e` — 13 tests |
| 2 — tap the capture loop | ✅ done, `918ccbd` |
| 3 — hand the turns to the app | ✅ done, `f9a2e29` + `9467dcd` |
| 4 — learn the channel mapping from ringback | ✅ done, `77d2023` + `289e88d` |
| 5 — VoIP path | ⬜ not started, and mostly unnecessary — see below |
| the display half — see below | ✅ done, `9641c5b` … `e9f5404` |

**702 unit tests, 0 failures.** 6 migration tests pass against real SQLite on an emulator.

**No longer inert.** A transcript made from now on carries a speaker per segment, the transcript
sheet names the two sides once the mapping is trusted, and the summariser is told who said what. The
recorded audio is still untouched: same source, same mono downmix, same encoder settings.

### The display half, as built

- **`SpeakerLabeller`** attributes each transcribed segment to the side that held **two thirds** of
  its voiced time. Whisper cuts on pauses, not on turn boundaries, so a segment routinely straddles
  a handover; anything short of two thirds is left unlabelled rather than guessed. Silence counts
  for nobody — it is most of a call, and counting it would push every segment containing a pause
  below the threshold.
- **`TranscriptionRunner`** reads the turns once per recording as it stores the segments. No turns —
  a mono capture, an older daemon, any call recorded before this existed — simply means unlabelled
  rows, which is what every transcript looked like until now.
- **`SpeakerNames`** turns `A`/`B` into "You" and the contact's name **on every read**, in the sheet
  and in the copy/share text alike. Nothing is written back. This is the whole reason the stored
  labels are neutral: a mapping learned tomorrow improves every transcript already on the phone, and
  a mapping *lost* falls back to "Speaker A"/"Speaker B" rather than leaving behind a name that is
  now a guess.
- **The summariser** resolves the same way, for the model's eyes only, and the prompt now explains
  what the prefixes mean — otherwise a model reports them, and "A asked about the invoice" is a
  worse summary than the unlabelled version was. While the mapping is unknown the prompt says so and
  forbids guessing a name. The label injected for the user is the English word "You", which is safe
  here because the entire prompt is English and the output language is pinned separately.
- Three new strings, in all ten locales.

**Nothing here has run on a real call.** It needs an outgoing call to hear ringback at all, and two
before it will commit. The log line to watch is `CV:SpeakerTurns: Channel mapping now stands at …`,
which is Step 6's inspectability requirement met by the cheapest thing that could work.

Until those two calls happen, the *expected* result on the device is neutral "Speaker A"/"Speaker B"
labels on newly transcribed calls — that is the feature working, not failing. Names appear only
after the mapping is corroborated.

### Where Task 3's blocker went

The August note said this needed to touch `AudioRecordingEngine.finalizeStagingIfNeeded()` — the
recording hot path — and a migration to a database that had never carried one. Neither turned out to
be true by the time it was picked up:

- **The hot path is untouched.** `RecordingForegroundService.routeFinalRecording` already launches an
  IO scope that catalogues the finished recording with the `displayName` in hand, detached from the
  dying service. The turns are collected there, before transcription is queued.
- **The migration is routine now.** That database has since gone to v2 and v3 for notes and
  summaries, and `TranscriptMigrationInstrumentedTest` runs every migration against real SQLite on a
  device. v3→v4 was written the same way and is covered by the same test.

### Task 4 as built, versus as planned

Built as designed, with one deliberate split: **the daemon reports, the app decides.** The daemon
does not know whether a call was outgoing and cannot know what other calls observed, so it says only
what this call's ringback suggested. The app discards the observation for incoming calls and applies
the corroboration rule.

The rule came out stricter than "two calls agree": a value wins only if two or more saw it **and**
nothing else matched them, and it is recomputed from stored observations on every read rather than
cached. That is what lets a mapping be **lost** as well as gained — and deleting the calls that
taught it un-teaches it, which a cached preference would not.

---

## HARD CONSTRAINT — no second voice capture during a carrier recording (2026-08-23)

**Opening a second voice `AudioRecord` while `VOICE_CALL` is recording costs the recording its near
side.** Measured on the OP12 by A/B on real calls:

| probe | result |
|---|---|
| `VOICE_DOWNLINK` open alongside the recording | far party only — the user's own turn is **digital silence** |
| probe off | both sides, as always |

Confirmed by ear and by an energy profile of the file: on a call where both people spoke, one burst
of speech and silence through the user's turn. The likely mechanism is that the HAL cannot serve two
voice captures and re-routes the combined stream to downlink only — silently, with no error at open,
at start, or on any read.

**This is why the probe is `PROBE_ENABLED = false`.** Every guard it had covered a *failure*: a
refused open, a refused start, a silent source. The damage came from the call **succeeding**.

It also rules out the obvious next idea — capturing `VOICE_UPLINK` + `VOICE_DOWNLINK` as two records
and interleaving them the way `VoipCaptureSession` does — because that is still two concurrent voice
captures. VoIP gets away with it because its far side is an AudioPolicy loopback and its near side is
plain `MIC`; neither is a *voice* input.

**Rule: nothing may open a second voice capture during a carrier recording. Verify any change here
by recording a two-sided call and listening for BOTH voices — a half-recording looks completely
normal in the logs, in the file size, and in the waveform.**

### What ringback measured, before that

The original detector could never have worked here either. On a real outgoing call the capture is
**silent throughout the ringing phase** — rms 0 across twenty seconds, no energy in the 300-600 Hz
band. The ringback the caller hears is generated locally and never enters `VOICE_CALL`. An incoming
call has no ringing phase to offer at all.

### So how does the mapping get learned?

Both automatic routes are closed on this hardware: ringback is not in the audio, and the downlink
comparison cannot run without damaging the recording. What remains is to **ask, once**: on the first
transcript that has both sides labelled, show a line from each and let the user say which is theirs.
One tap, ever, and every transcript on the phone gains names — past and future — because names are
resolved at display time. This reverses the 2026-08-17 decision (*detect it, do not ask*), which was
right while detection looked possible.

## Why this runs early, ahead of the UI

Speaker labels obtained this way are **exact and free**, but only for calls recorded *after* this
ships. A call recorded tomorrow without it can never be labelled except by a model. Every day this
waits produces more permanently unlabelable recordings, so it is sequenced before Plan 3 even though
nothing user-visible appears until Plan 3 renders it.

The user's decision (2026-08-17): *start here, see how well it works, and compare with a diarization
model later.* Plan 4 remains open for the model, and both write the same nullable `speaker` column,
so choosing the model later needs no migration.

## What this plan must NOT change

- **The recorded audio.** Encoding stays mono at the current bit rate. The v1.4.4 field report was
  that stereo Opus at 24 kbps split the bit rate and *starved the far party*; the mono-encode rule is
  a MUST-NOT-UNDO. This plan reads the stereo buffer that already exists in memory and changes
  nothing about what is written.
- **Recording reliability.** Every addition here is inside a `runCatching`. A fault in speaker
  detection must degrade to "no speaker data", never to a failed or truncated recording. Recording
  works today; this feature is worth nothing beside it.

## Global Constraints

Same as Plan 2: GPLv3 header on new files, `AppLogger` with a `CV:` tag, strings in all **10** locales
(de, es, fr, hu, it, pl, pt-rBR, ru, vi, zh-rCN — merge with `scripts/merge-translations.py`, then
`./gradlew :app:checkTranslations`, which fails the build on drift), immutable data, no attribution
trailers, no real call audio in the repo.

---

## Task 1: The detector, as pure testable logic

Written first and separately from the daemon precisely because the daemon is the risky place. All the
decisions live here, where they can be tested on synthetic PCM with no device.

**Files:**
- Create: `app/src/main/java/com/baba/callvault/server/speakers/SpeakerTurnDetector.kt`
- Create: `app/src/main/java/com/baba/callvault/server/speakers/SpeakerTurn.kt`
- Test: `app/src/test/java/com/baba/callvault/server/speakers/SpeakerTurnDetectorTest.kt`

**Interfaces:**
- Consumes: interleaved stereo PCM-16 chunks (the same `ByteArray` the downmix already receives).
- Produces:
  - `enum class SpeakerChannel { A, B, BOTH, SILENCE }`
  - `data class SpeakerTurn(val startMs: Long, val channel: SpeakerChannel)` — turns are contiguous,
    so a turn runs until the next one starts (or the recording ends)
  - `SpeakerTurnDetector(sampleRate: Int)` with `fun accept(pcm: ByteArray, len: Int)` and
    `fun finish(): List<SpeakerTurn>`
  - `SpeakerTurnCodec.encode(turns): String` / `decode(String): List<SpeakerTurn>`

> **Channels are named A and B, not You and Them, and that is deliberate.** The capture reliably puts
> the two *directions* on separate channels, but which index is uplink is an OEM detail this codebase
> has never asserted. Task 4 learns the mapping automatically; until it has, a confident "You" that is
> actually the other party is worse than a neutral label. The mapping is applied at the display layer,
> never baked into stored data.

- [ ] **Step 1: Write the failing tests**

```kotlin
class SpeakerTurnDetectorTest {

    private val sampleRate = 48_000

    @Test
    fun `reports channel A while only the left channel carries speech`() {
        // Arrange
        val detector = SpeakerTurnDetector(sampleRate)

        // Act
        detector.accept(stereo(leftAmplitude = 8000, rightAmplitude = 0, millis = 500), Int.MAX_VALUE)
        val turns = detector.finish()

        // Assert
        assertEquals(listOf(SpeakerChannel.A), turns.map { it.channel })
    }

    @Test
    fun `reports BOTH when the two channels are comparably loud`() {
        // Double-talk is common on real calls and must not be forced onto one speaker.
        val detector = SpeakerTurnDetector(sampleRate)
        detector.accept(stereo(8000, 8000, millis = 500), Int.MAX_VALUE)
        assertEquals(listOf(SpeakerChannel.BOTH), detector.finish().map { it.channel })
    }

    @Test
    fun `reports SILENCE when neither channel is above the noise floor`() {
        val detector = SpeakerTurnDetector(sampleRate)
        detector.accept(stereo(5, 5, millis = 500), Int.MAX_VALUE)
        assertEquals(listOf(SpeakerChannel.SILENCE), detector.finish().map { it.channel })
    }

    @Test
    fun `coalesces consecutive identical windows into one turn`() {
        // The output must stay small: a 30-minute call at 100 ms windows is 18000 windows, but only a
        // few hundred real turns. Storing per-window would be wasteful and unreadable.
        val detector = SpeakerTurnDetector(sampleRate)
        repeat(10) { detector.accept(stereo(8000, 0, millis = 100), Int.MAX_VALUE) }
        assertEquals(1, detector.finish().size)
    }

    @Test
    fun `emits a new turn with the correct start time when the speaker changes`() {
        val detector = SpeakerTurnDetector(sampleRate)
        detector.accept(stereo(8000, 0, millis = 1000), Int.MAX_VALUE)
        detector.accept(stereo(0, 8000, millis = 1000), Int.MAX_VALUE)

        val turns = detector.finish()

        assertEquals(listOf(SpeakerChannel.A, SpeakerChannel.B), turns.map { it.channel })
        assertEquals(0L, turns[0].startMs)
        assertEquals(1000L, turns[1].startMs)
    }

    @Test
    fun `ignores a trailing partial frame instead of throwing`() {
        // Mirrors PcmDownmix.stereoToMono, which leaves a truncated final frame unconsumed.
        val detector = SpeakerTurnDetector(sampleRate)
        detector.accept(ByteArray(5), 5)
        detector.finish()   // must not throw
    }

    @Test
    fun `round trips through the codec`() {
        val turns = listOf(SpeakerTurn(0, SpeakerChannel.A), SpeakerTurn(1200, SpeakerChannel.BOTH))
        assertEquals(turns, SpeakerTurnCodec.decode(SpeakerTurnCodec.encode(turns)))
    }

    /** Builds interleaved stereo PCM-16 of [millis] with constant per-channel amplitude. */
    private fun stereo(leftAmplitude: Int, rightAmplitude: Int, millis: Int): ByteArray { /* ... */ }
}
```

- [ ] **Step 2: Run, expect FAIL**

Run: `./gradlew testDebugUnitTest --tests "*SpeakerTurnDetectorTest*"`
Expected: FAIL — unresolved reference `SpeakerTurnDetector`.

- [ ] **Step 3: Implement the detector**

Constants, all named (no magic numbers):

```kotlin
private const val WINDOW_MS = 100                // resolution of a turn boundary
private const val SILENCE_FLOOR = 300            // mean |sample| below this is silence (PCM-16)
private const val DOMINANCE_RATIO = 2.0          // one channel must be this much louder to "win"
```

Per window accumulate `sum|L|` and `sum|R|`; classify:

```
both below SILENCE_FLOOR                    -> SILENCE
louder >= DOMINANCE_RATIO * quieter         -> A or B
otherwise                                   -> BOTH
```

Then coalesce equal consecutive windows. Keep the whole class allocation-free per chunk (accumulate
into `Long`s); it runs on the capture thread and must not add GC pressure to a live recording.

- [ ] **Step 4: Run tests to green.**

- [ ] **Step 5: Commit**

```bash
git commit -m "feat(speakers): detect per-channel speaker turns from stereo capture"
```

---

## Task 2: Tap the capture loop

**Files:**
- Modify: `app/src/main/java/com/baba/callvault/server/DirectAudioRecorderSession.kt:121-140`
- Test: covered by Task 1 plus a new assertion that a mono capture yields no turns.

**Interfaces:**
- Consumes: `SpeakerTurnDetector`.
- Produces: `DirectAudioRecorderSession.speakerTurns(): List<SpeakerTurn>` after `stop()`.

The hook point already exists. Today:

```kotlin
val downmix = captureChannels == 2
...
val (buf, len) = if (downmix) mono to PcmDownmix.stereoToMono(pcm, read, mono) else pcm to read
```

- [ ] **Step 1: Add the detector, guarded**

Only when `downmix` is true — a mono capture carries no direction information, and the fallback
sources (`VOICE_COMMUNICATION`/`MIC`) are mono. Wrap in `runCatching` so a detector fault can never
break the recording:

```kotlin
// Speaker turns come free here: the stereo buffer is already in hand and the two call directions are
// already on separate channels. Guarded because a recording that works is worth more than a label.
if (downmix) runCatching { detector?.accept(pcm, read) }
    .onFailure { AppLogger.w(TAG, "Speaker detection failed; continuing without turns", it) }
```

- [ ] **Step 2: Add a test that a mono capture produces an empty turn list**, so the absence of
  speaker data is an expected state rather than a crash downstream.
- [ ] **Step 3: Verify the recording is unchanged.** Record a call, confirm the output file's codec,
  channel count and bit rate are identical to a build without this change. **If the audio changed at
  all, stop — the tap is wrong.**
- [ ] **Step 4: Commit.**

---

## Task 3: Hand the turns to the app

**Files:**
- Modify: `app/src/main/aidl/com/baba/callvault/server/IRecorderService.aidl`
- Modify: `app/src/main/java/com/baba/callvault/server/RecorderServer.kt`
- Modify: the app-side recorder connection that calls `stopRecording()`
- Create: `app/src/main/java/com/baba/callvault/data/transcripts/db/SpeakerTurnEntry.kt` (+ DAO methods)

**Interfaces:**
- Produces: `String speakerTurns()` on the AIDL interface — the encoded turn list for the recording
  that just stopped, or an empty string when there is none.

- [ ] **Step 1: Add the AIDL method at the END of the interface**

Ordering matters: AIDL assigns transaction IDs by declaration order, so appending is the only
backward-compatible edit. Model it on `voipFarPartyHeard()`, which is already a post-stop query:

```java
/**
 * Encoded speaker turns for the recording that just stopped, or "" when none were detected.
 *
 * Queried after stopRecording(), mirroring voipFarPartyHeard(). Empty whenever the capture was mono
 * (the fallback sources carry no direction information), so callers must treat absence as normal.
 */
String speakerTurns();
```

- [ ] **Step 2: Handle the stale-daemon case**

A warm daemon from a previous version does not have this method, and the call will fail. The app must
catch and treat it as "no speaker data" — never as a recording failure:

```kotlin
val turns = runCatching { service.speakerTurns() }.getOrNull().orEmpty()
```

Add a test that a throwing service yields an empty list and no exception escapes.

- [ ] **Step 3: Persist**, keyed by `displayName` in `transcripts.db` (Plan 2, Task 1) — the same
  database, because this data has the same "expensive/impossible to regenerate" property. Add
  `SpeakerTurnEntry(displayName, startMs, channel)` and include it in the existing `deleteFor()`
  cascade so deleting a recording removes its turns too.

> If Plan 2 has not run yet, this task creates `transcripts.db` with only the speaker-turn table and
> Plan 2's Task 1 adds the rest as migration v1→v2. Either order works; do not duplicate the database.

- [ ] **Step 4: Commit.**

---

## Task 4: Learn which channel is which — automatically, from ringback

**Decided 2026-08-17 (A5): detect it, do not ask.** It cannot be hardcoded — Android documents
`VOICE_CALL` as "uplink + downlink" and never specifies the channel order, leaving it an OEM
decision — but it can be *learned*, with no prompt and no scripted call.

**VoIP needs none of this.** `VoipCaptureSession` opens the two streams itself and interleaves them
`LEFT near, RIGHT far` (`VoipCaptureSession.kt:40`), so app calls are correct by construction. This
task is carrier-only.

**The signal:** on an **outgoing** call, between the moment recording starts and the moment the far
side answers, the ringback tone comes from the network — present on the far-party channel, absent
from the near one, which carries only room noise. Whichever channel holds a sustained periodic tone
in that window is the far party.

**Files:**
- Create: `app/src/main/java/com/baba/callvault/server/speakers/ChannelMapDetector.kt`
- Create: `app/src/main/java/com/baba/callvault/data/ChannelMap.kt` (the persisted result)
- Test: `app/src/test/java/com/baba/callvault/server/speakers/ChannelMapDetectorTest.kt`

**Interfaces:**
- Produces: `ChannelMap` — `UNKNOWN` / `A_IS_NEAR` / `B_IS_NEAR`, persisted once learned and reused by
  every later call, **including incoming ones**, which have no ringback phase to learn from.

- [ ] **Step 1: Write the failing tests.** Synthetic PCM only — no device needed:

```kotlin
@Test fun `identifies the channel carrying a sustained tone as the far party`()
@Test fun `reports UNKNOWN when neither channel carries a tone`()      // carrier sends no ringback
@Test fun `reports UNKNOWN when both channels carry tonal energy`()    // leakage: refuse to guess
@Test fun `reports UNKNOWN for an incoming call`()                     // no ringback phase at all
@Test fun `ignores a tone too short to be ringback`()                  // a dial blip is not ringback
```

- [ ] **Step 2: FAIL. Step 3: Implement.** Classify per window on *sustained periodicity* — a narrow
  band holding most of the energy, for seconds — not a hardcoded frequency: ringback is ~400 Hz in
  Israel and differs by country.

- [ ] **Step 4: Require corroboration.** Hold the first result as provisional and promote it to the
  persisted `ChannelMap` only once **two calls agree**. One noisy call must not permanently mislabel
  every transcript thereafter.

- [ ] **Step 5: Refuse to guess.** Anything short of a confident, corroborated answer leaves the map
  `UNKNOWN` and the UI shows neutral "Speaker A / Speaker B". **A confident wrong attribution — your
  words shown as theirs — is a far worse defect than no attribution at all.**

- [ ] **Step 6:** Make the learned mapping inspectable (a debug log line, or a Settings detail row),
  so device check B7 can be verified without instrumentation.

- [ ] **Step 7: Commit.**

---

## Task 5: VoIP path (follow-up)

`VoipCaptureSession` mixes two *separate* sources — the far party from the loopback policy and the
near party from the microphone — so the separation is even cleaner there than in the carrier path.
Apply the same detector to the two streams before they are mixed, behind the same guard.

- [ ] Mirror Tasks 1–3 for `VoipCaptureSession.kt`, then re-run Task 4's mapping check for VoIP.

---

## Definition of done

- `./gradlew testDebugUnitTest` green
- A recorded call's audio file is **bit-identical in format** to one recorded before this change
- A mono/fallback capture produces an empty turn list and no error
- A stale daemon produces an empty turn list and no error
- Deleting a recording deletes its speaker turns
- The channel mapping is documented with the device it was verified on

**Not in this plan:** rendering the labels (Plan 3), and diarizing the existing back catalogue
(Plan 4, still open pending how well this performs).
