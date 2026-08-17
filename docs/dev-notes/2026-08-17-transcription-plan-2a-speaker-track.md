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

Same as Plan 2: GPLv3 header on new files, `AppLogger` with a `CV:` tag, strings in all 9 locales,
immutable data, no attribution trailers, no real call audio in the repo.

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
> has never asserted. Task 4 determines the mapping empirically; until then a confident "You" that is
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

## Task 4: Determine which channel is which — on device

**This task is empirical and cannot be done from the desk.** It answers the one question the code
cannot: is channel A the near party or the far party?

> Deferred to the batched device session — see
> **`docs/dev-notes/2026-08-17-transcription-device-test-plan.md`**, items B7 and B8. Until B7 is
> answered the UI must use neutral "Speaker A / B" labels, so this does not block Tasks 1–3.

- [ ] **Step 1:** Place a call where the far party speaks first and alone for several seconds, and the
  near party stays silent. Record it.
- [ ] **Step 2:** Read back the stored turns and check which channel is active during that window.
- [ ] **Step 3:** Repeat once with the roles reversed to confirm, and once on a VoIP call.
- [ ] **Step 4:** Record the mapping in this file **with the device and Android version it was
  observed on**, and add it as a constant with a comment saying it is empirical, not specified.
- [ ] **Step 5:** If the mapping proves inconsistent across call types, keep the neutral
  "Speaker A / Speaker B" labels in the UI rather than guessing at "You / Them". A wrong attribution
  in a call transcript is a serious defect — worse than no attribution.

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
