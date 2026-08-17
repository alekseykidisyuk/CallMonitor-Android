# Transcription Plan 3 — The button, the modal, and search

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development
> (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make transcription visible and usable — one button per recording that transcribes on
demand and then turns into the way to read the transcript, in a near-full-screen modal with
timestamps and speaker labels.

**Architecture:** A single state-driven affordance on `RecordingRow`. The row observes its
transcript state from `transcripts.db` and renders one of four things from the same slot. Reading
opens a `ModalBottomSheet` expanded to near-full height.

**Tech Stack:** Compose Material 3, Room `Flow`s, WorkManager (`runNow` from Plan 2, Task 5).

**Spec:** `docs/dev-notes/2026-08-16-on-device-transcription-design.md`
**Depends on:** Plan 2 (storage + pipeline). Speaker labels render whatever Plan 2A stored; if 2A has
not run, the same UI shows timestamps only with no code change.

---

## The one affordance, in four states

Requirement, as given: *"manual transcript should be a button on the recording"*, and *"the same
button … should change into a clickable button of a transcribed recording which opens an almost full
screen modal"*. So it is **one slot in the row**, never two competing buttons:

| Transcript state | What the slot shows | Tapping it |
|---|---|---|
| `NONE` | outline icon + "Transcribe" | enqueues this one recording immediately |
| `QUEUED` / `RUNNING` | small indeterminate spinner | nothing (a long-press cancels) |
| `DONE` | filled icon, tinted | opens the transcript modal |
| `FAILED` | error-tinted icon | opens a short error sheet with **Retry** |

`RecordingRow` (`HomeScreen.kt:1233`) already takes per-row callbacks and owns local state like
`expanded` and `deleteTarget`, so this follows the established shape rather than introducing a new
one.

## Global Constraints

Same as Plan 2. Additionally: **no transcript text in logs.** Segment text is the content of a
private phone call; `AppLogger` may record counts and states, never text.

---

## Task 1: Per-row transcript state

**Files:**
- Create: `app/src/main/java/com/baba/callvault/data/transcripts/TranscriptRepository.kt`
- Modify: `app/src/main/java/com/baba/callvault/ui/viewmodels/HomeViewModel.kt`
- Test: `app/src/test/java/com/baba/callvault/data/transcripts/TranscriptRepositoryTest.kt`

**Interfaces:**
- Consumes: `TranscriptDao` (Plan 2, Task 1), `TranscriptionScheduler.runNow` (Plan 2, Task 5).
- Produces:
  - `TranscriptRepository.statesFor(displayNames): Flow<Map<String, TranscriptState>>`
  - `TranscriptRepository.transcript(displayName): Flow<TranscriptWithSegments?>`
  - `TranscriptRepository.transcribeNow(displayName)`, `retry(displayName)`, `delete(displayName)`

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test
fun `a recording with no transcript row reports NONE`() {
    // The common case on first run: absence must be a state, not a null the UI has to special-case.
}

@Test
fun `state updates flow to observers when a worker finishes`() { /* ... */ }

@Test
fun `states are emitted for every requested name even when the table is empty`() { /* ... */ }
```

- [ ] **Step 2: FAIL. Step 3: Implement. Step 4: Green. Step 5: Commit.**

> Query states for the **visible** rows only. Home lists years of calls; observing every transcript
> to render a handful of icons would read the whole table on every change.

---

## Task 2: The button in the row

**Files:**
- Modify: `app/src/main/java/com/baba/callvault/ui/screens/HomeScreen.kt` (`RecordingRow`, ~1233)
- Modify: `app/src/main/res/values/strings.xml` (+ all 9 locales)
- Test: `app/src/test/java/com/baba/callvault/ui/TranscriptButtonTest.kt`

**Interfaces:**
- Consumes: `TranscriptState`, and new `RecordingRow` params `transcriptState: TranscriptState`,
  `onTranscribe: () -> Unit`, `onOpenTranscript: () -> Unit`.

- [ ] **Step 1: Write the failing tests** — one per state, asserting the right content description
  and the right callback:

```kotlin
@Test fun `shows Transcribe and calls onTranscribe when there is no transcript`() { /* ... */ }
@Test fun `shows a spinner and ignores taps while running`() { /* ... */ }
@Test fun `opens the transcript when done`() { /* ... */ }
@Test fun `offers retry when the last attempt failed`() { /* ... */ }
```

- [ ] **Step 2: FAIL. Step 3: Implement one composable, `TranscriptAction`,** switching on the state.
  Every state needs a distinct `contentDescription` — this is the only affordance whose meaning
  changes under the user, so a screen reader must not read all four as "button".
- [ ] **Step 4: Green.**
- [ ] **Step 5: Handle the no-model case.** If no model is installed, tapping Transcribe must explain
  and offer to download rather than silently enqueuing work that will `retry()` forever. Test it.
- [ ] **Step 6: Commit.**

---

## Task 3: The transcript modal

**Files:**
- Create: `app/src/main/java/com/baba/callvault/ui/common/TranscriptSheet.kt`
- Test: `app/src/test/java/com/baba/callvault/ui/TranscriptSheetTest.kt`

**Interfaces:**
- Consumes: `TranscriptWithSegments`, `RecordingPlaybackController` (to seek).
- Produces: `TranscriptSheet(transcript, onDismiss, onSeekTo, onShare, onRetranscribe, onDelete)`

**Shape:** `ModalBottomSheet` with `rememberModalBottomSheetState(skipPartiallyExpanded = true)`, so
it opens **at near-full height** as asked, keeping the M3 dismiss gestures rather than a custom
full-screen dialog.

```
┌─────────────────────────────────────┐
│ ──                                  │  drag handle
│ גבריאל · 17 Aug, 15:38 · 12:04      │  header: who, when, duration
│ [ Search in transcript          🔍 ] │
├─────────────────────────────────────┤
│ You    00:00   שלום, מדבר דניאל…    │  ← speaker label only when present
│ Them   00:04   היי, מה שלומך?       │
│ You    00:09   רציתי לוודא לגבי…    │
│                                     │
├─────────────────────────────────────┤
│  Copy      Share      Re-transcribe │
└─────────────────────────────────────┘
```

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test
fun `renders every segment with its start timestamp`() { /* ... */ }

@Test
fun `renders speaker labels only when segments carry a speaker`() {
    // Plan 2A may not have run, or the capture may have been mono. A null speaker must render as
    // plain timestamped text, not as an empty column or the word "null".
}

@Test
fun `tapping a segment seeks playback to its start`() {
    // The reason timestamps are stored at all: a transcript you cannot navigate from is just text.
}

@Test
fun `shows an explicit empty state when no speech was recognised`() {
    // TranscriptionEngine returns an empty list for silence. "No speech detected" is a result;
    // a blank sheet reads as a bug.
}

@Test
fun `lays out right to left for Hebrew segments`() { /* ... */ }
```

- [ ] **Step 2: FAIL. Step 3: Implement** with a `LazyColumn` — a long call is thousands of segments.
- [ ] **Step 4: Green.**
- [ ] **Step 5: RTL check.** These transcripts are mostly Hebrew. Verify on device that segment text
  is right-aligned and that the timestamp/speaker columns do not visually collide with it.
- [ ] **Step 6: Commit.**

---

## Task 4: Search across all transcripts

**Files:**
- Modify: `HomeScreen.kt` (extend the existing search affordance)
- Modify: `HomeViewModel.kt`
- Test: `app/src/test/java/com/baba/callvault/ui/TranscriptSearchTest.kt`

**Interfaces:**
- Consumes: `TranscriptDao.search` (Plan 2, Task 1).

- [ ] **Step 1: Failing tests**

```kotlin
@Test fun `finds a recording by a word spoken inside it`() { /* ... */ }
@Test fun `shows the matching snippet and jumps to that timestamp`() { /* ... */ }
@Test fun `an unmatched query returns no rows rather than every row`() { /* ... */ }
@Test fun `a query with FTS syntax characters does not crash`() {
    // MATCH takes an expression: a stray quote or "*" from a user typing naturally is a syntax
    // error, not a no-match. Quote the term before it reaches SQLite.
}
```

- [ ] **Step 2: FAIL. Step 3: Implement. Step 4: Green. Step 5: Commit.**

> Known and accepted from the spec: `unicode61` does whole-word matching with no Hebrew stemming, so
> inflected forms will not match. Say so in the empty-state copy rather than letting it read as a bug.

---

## Task 5: Share, copy, delete

- [ ] Share/copy the transcript as plain text with timestamps, via the existing share sheet.
- [ ] **Deleting a transcript must be possible without deleting the recording** — a user may want the
  audio but not a searchable text of it. Wire to `TranscriptRepository.delete`.
- [ ] Re-transcribe replaces in place (Plan 2's `replaceSegments` already guarantees no duplicates).
- [ ] Commit.

---

## Definition of done

- All unit tests green; `assembleDebug` succeeds
- The row button walks NONE → RUNNING → DONE on a real recording and opens the modal
- Segments render right-to-left, with speaker labels when present and without when not
- Tapping a segment seeks the player to that point
- Searching a spoken word finds the call and jumps to the moment
- No transcript text appears in any log

**Not in this plan:** diarizing the existing back catalogue (Plan 4, still open).
