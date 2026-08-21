# Call summaries — UI plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to
> implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn the measured summarisation engine into something a user can actually use, without
promising more than the measurements support.

**Spec:** `docs/dev-notes/2026-08-21-summarisation-spike-plan.md` — read its Results and Verdict
first. Everything below assumes those numbers.

**Status:** not started. The engine works and is measured; none of this exists.

---

## What the measurements dictate

These are not preferences. Each one closes off a design that would otherwise look reasonable.

| Measured | What it rules out |
|---|---|
| ~90 s for a 3½-minute call | Any "summarise" button that leaves the user watching a spinner |
| ~1.9 GB resident | Running it while a call is being recorded, or on a phone that cannot spare it |
| 3.46 GB download | Shipping the model; it must be fetched, resumable, and deletable |
| Invents nothing on clean text, **reads noise as names on dirty text** | Presenting a summary as fact without the transcript one tap away |
| Quality is capped by the transcript | Offering summaries for recordings that have no transcript yet |

**The last one is the strongest constraint and the easiest to get wrong.** A summary of a bad
transcript is a bad summary, and the user cannot tell which they are looking at unless the transcript
is right there.

---

## The shape

**A summary is part of a recording, not a place of its own.** It goes on the playback screen, above
the transcript row, because it answers the question the transcript answers more slowly.

```
┌─────────────────────────────────────┐
│ ↗  גבריאל 2b    Yesterday, 15:57  📄🗑│   header (exists)
├─────────────────────────────────────┤
│ ▁▃▅▂▇▃▁  waveform, controls, speed  │   player (exists)
├─────────────────────────────────────┤
│ ✨ Summary                          │   NEW
│ השיחה התמקדה בדיון על פיטורי עובדת…│
│                                     │
│ Decisions                           │
│  • [1:30] מיגרציה של הפיירוול       │   ← tappable, seeks
│ To do                               │
│  • [2:15] לחזור ללקוח עד יום חמישי  │
│                                     │
│ Generated from the transcript ·  ⟳  │   provenance + redo
├─────────────────────────────────────┤
│ 📄 Read the transcript              │   (exists)
├─────────────────────────────────────┤
│ ✎ Note                              │   (exists)
└─────────────────────────────────────┘
```

**Why sections rather than a paragraph.** The structured prompt returns intent, decisions and action
items separately, and those are what someone returns to a call for. A paragraph buries them.

**Why the timestamps are the point.** `[1:30]` next to a decision is a jump into the call. The
transcript sheet already seeks on tap; a summary made of jump points is a table of contents for a
conversation, and it is the one thing this feature can offer that reading the transcript cannot.

**Why "Generated from the transcript" is not optional.** It is the honest label for a machine's
reading of a machine's transcription of a call. It also explains, without an apology, why a summary
can be wrong — and it is the affordance that sends someone to the transcript when it looks off.

---

## What NOT to build

- **No summary in the recordings list.** The list is already tight; the name barely fits. A summary
  belongs where someone has decided to deal with the call.
- **No automatic summarisation of the back catalogue.** It is ~90 s and ~1.9 GB per call. Sweeping a
  library is hours of CPU for pages nobody asked for — the same mistake the waveform pass made.
- **No `participants` field, until diarisation exists.** Measured: it read the ringtone marker
  `*ביב*` as a person and named them. Confident wrong names are worse than no names.
- **No `sentiment`.** Low value, and being confidently wrong about the tone of someone's private
  conversation is a bad trade.

---

## Global Constraints

- **Never while recording.** `TranscriptionEngine.isRunning` already exists for the same reason;
  summarisation needs the same guard and one of its own.
- **Never without a transcript.** The action does not appear for a recording that has none.
- **The model is downloaded, never bundled.** `ModelRepository` + `ModelDownloadWorker` already do
  resumable, size-verified, progress-reporting downloads. Reuse, do not rewrite.
- **Summaries are private.** Stored in `transcripts.db`, deleted by `TranscriptCascade` with the
  recording, and never logged.
- **Grammar-constrained output.** `llama_sampler_init_grammar` exists in llama.cpp. Without it the
  JSON is invalid often enough to break the screen — measured, not theorised.
- No `Co-Authored-By` trailers. Branch stays local.

---

## Task 1: Make the JSON impossible to malform

**Files:** `app/src/main/cpp/llamacv.cpp`, `LlamaNative.kt`, `SummaryEngine.kt`
**Test:** `app/src/androidTest/.../SummaryGrammarTest.kt`

Measured: Gemma returned `"summary": "…` with no closing quote. `JSON.parse` throws, and no prompt
wording fixes it — a 2B model under a token cap drops a quote. A grammar makes the bad token
unsampleable.

- [ ] **Step 1: Write the failing instrumented test** — generate with a grammar, assert the result
      parses as JSON with all expected keys, over a deliberately awkward transcript.
- [ ] **Step 2: Run it, watch it fail** (no grammar parameter yet).
- [ ] **Step 3: Add a GBNF string parameter to `generate`**, and when present:

```cpp
llama_sampler_chain_add(sampler,
    llama_sampler_init_grammar(vocab, grammar_text, "root"));
```

- [ ] **Step 4: Write the grammar** as a Kotlin constant beside the prompt — an object with the seven
      remaining keys, strings and string arrays, `sentiment` omitted.
- [ ] **Step 5: Run the test; it passes.** Then run it ten times over: invalid JSON must be
      impossible, not merely rare.
- [ ] **Step 6: Commit.**

## Task 2: Parse, store, and cascade

**Files:** `data/transcripts/db/` (v2→v3 migration), `summary/CallSummary.kt`, `TranscriptCascade.kt`
**Test:** `SummaryParsingTest.kt`, plus a migration test

- [ ] Failing test: a malformed summary is rejected rather than stored half-parsed.
- [ ] `CallSummary` data class; parse with `kotlinx.serialization`, `ignoreUnknownKeys = true`.
- [ ] Room v2→v3, hand-written like `MIGRATION_1_2`. **No destructive fallback** — a summary costs
      ninety seconds of someone's battery.
- [ ] Extend `TranscriptCascade` so a deleted recording takes its summary. Test it.
- [ ] Commit.

## Task 3: The job

**Files:** `summary/SummaryWorker.kt`, `SummaryScheduler.kt`
**Interfaces:** mirrors `TranscriptionWorker` — same progress keys, same abort watcher.

- [ ] Failing test for the queue: a summary is not attempted without a transcript, nor while
      `TranscriptionEngine.isRunning`.
- [ ] Worker with `setProgress`, honouring `isStopped`, calling `SummaryEngine.requestAbort`.
- [ ] Reuse `TranscriptionProgress` for the figure shown — same problem, same fix, and it is already
      tested.
- [ ] Commit.

## Task 4: The model, in Settings

**Files:** `TranscriptionModel.kt` (or a sibling catalogue), `SettingsScreen.kt`

- [ ] Add Gemma 4 E2B to the model catalogue: URL, exact size, checksum.
- [ ] A **Summaries** section in Settings, next to Transcription: download/delete, size on disk, and
      the state of the download.
- [ ] Commit.

## Task 5: The requirements modal

**Files:** `ui/common/SummaryRequirementsDialog.kt`, strings in 11 locales

Shown once before the first download. The numbers are measured, so quote them.

- [ ] Copy naming: **3.5 GB to download**, about **2 GB of memory while it runs**, roughly **a minute
      and a half per five minutes of call**, and that it needs a recent phone.
- [ ] "Don't ask again", reflected as a Settings switch — the pattern the transcription warning
      already uses.
- [ ] Translations. Commit.

## Task 6: The summary on the playback screen

**Files:** `ui/screens/PlaybackScreen.kt`, `ui/common/SummaryCard.kt`

- [ ] `SummaryCard`: intent as a headline, summary as prose, then Decisions and To-do as lists.
- [ ] Items beginning `[m:ss]` render the stamp as a chip and **seek on tap**, reusing
      `TranscriptTimestamp` and the seek path the transcript sheet already uses.
- [ ] Empty arrays render as nothing at all — never "No decisions", which is noise.
- [ ] Absent summary: a single row offering to make one, in the same shape as the transcript row.
- [ ] While running: the percentage, not a spinner.
- [ ] Footer: "Generated from the transcript", and a redo action.
- [ ] RTL: each item through `BidiText`, per line, as the transcript sheet does.
- [ ] Commit.

## Task 7: On-device pass

- [ ] Ask before starting; it is a daily driver and this saturates the CPU.
- [ ] A Hebrew call and an English one, end to end from the button.
- [ ] Stop mid-run: no half-summary stored, no row left spinning.
- [ ] Delete the recording: summary goes with it.
- [ ] A phone that is **not** the OP12.
- [ ] Fill in the results here.

---

## Open questions

1. **Does it run on 8 GB? STILL UNANSWERED, and it gates Task 5.** Attempted on the OP9 Pro
   (7.4 GB, Snapdragon 888) on 2026-08-21 and abandoned after several runs. What is known:

   - The model **loads** — 1.7 GB resident, so memory is not obviously the wall.
   - Generation then **never starts**: ~35 s of CPU for the load, then every thread sleeping at 0%
     CPU with the instrumentation still marked active. Not frozen by the OS (freezer cgroup is `/`,
     cpuset `foreground`), not killed, not out of memory, no native crash in logcat.
   - One real cause was found and fixed along the way: a killed run leaves `SummaryEngine`'s mutex
     held, and because the app process survives, the *next* instrumentation attaches to the same
     process and blocks on it for ever. **Force-stop the app between runs.** That got the model
     loading; it did not get generation running.

   Worth trying next: run it as a foreground activity rather than an instrumented test, and check
   whether the OP12 shows the same stall when its process is reused. Do not assume the phone is too
   small — nothing measured says that yet.
2. **Chunk size is a quality dial.** Measured: smaller chunks produced *better* summaries, because
   less is compressed at once. Worth tuning deliberately rather than defaulting to "as few as fit".
3. **Does the merge prompt hold up over a ninety-minute call?** Never tested past two chunks.
