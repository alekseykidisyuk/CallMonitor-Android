# Call summaries — UI plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to
> implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn the measured summarisation engine into something a user can actually use, without
promising more than the measurements support.

**Spec:** `docs/dev-notes/2026-08-21-summarisation-spike-plan.md` — read its Results and Verdict
first. Everything below assumes those numbers.

**Status (2026-08-23): Tasks 1–6 done, Task 7 partly.** Shipped as **1.6.0**. **613 unit tests, 0
failures**, plus 6 instrumented tests on an emulator, and the feature translated into all ten
locales.

Task 4 was done **before** Task 3, because the worker needs a model path and paths come from the
catalogue — the plan's numbering was written before that dependency was visible.

Verified on a device rather than merely compiled: the migrations against real SQLite, and the whole
3.46 GB download fetched, resumed, verified and installed. That found three defects no unit test
could — a finished download still offering itself, an invisible resume, and a progress bar rendering
pink. All fixed.

**The one thing still missing is the one that matters most: no summary has ever been produced.** The
emulator has no recordings to summarise and not enough memory to generate. Everything up to the
button is verified; what the button does has only been tested with the model faked.

---

## What the measurements dictate

These are not preferences. Each one closes off a design that would otherwise look reasonable.

| Measured | What it rules out |
|---|---|
| ~97 s for a real call, ~130 s with timestamps | Any "summarise" button that leaves the user watching a spinner |
| **3.0–3.5 GB peak PSS** | Running it while a call is being recorded, or on a phone that cannot spare it |
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
- **No automatic summarisation of the back catalogue.** It is a minute and a half or more, and about
  3.5 GB of memory, per call. Sweeping a
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

## Task 1: Make the JSON impossible to malform — **DONE**

**Files:** `app/src/main/cpp/llamacv.cpp`, `LlamaNative.kt`, `SummaryEngine.kt`
**Test:** `app/src/androidTest/.../SummaryGrammarTest.kt`

Measured: Gemma returned `"summary": "…` with no closing quote. `JSON.parse` throws, and no prompt
wording fixes it — a 2B model under a token cap drops a quote. A grammar makes the bad token
unsampleable.

- [x] **Step 1–2: Prove it fails without a grammar.** Done differently and better than planned: the
      grammar was exercised through a desktop `llama-cli` build of the vendored submodule rather
      than an instrumented test. That is not a shortcut — it is what caught the real bug. An invalid
      grammar makes `llama-cli` refuse to start, whereas on Android `llama_sampler_init_grammar`
      returns null, the native side logs it, and generation carries on **unconstrained**, so the
      output still looks like JSON and nothing says the constraint was never applied.
- [x] **Step 3: Add a GBNF string parameter to `generate`**, and when present:

```cpp
llama_sampler_chain_add(sampler,
    llama_sampler_init_grammar(vocab, grammar_text, "root"));
```

- [x] **Step 4: Write the grammar** as a Kotlin constant beside the prompt — `SummaryGrammar.JSON`,
      six keys, `sentiment` and `participants` both omitted. **Every rule on one line:** GBNF ends a
      rule at the newline, so a `root` spread over several lines parses as several broken rules.
- [x] **Step 5: Verified.** Grammar-constrained output parsed on every run.
- [x] **Step 6: Committed** — `95414ee`, fixed at `7bb166a`.

**Still open from this task:** an on-device `SummaryGrammarTest` that runs the constrained path ten
times over. The desktop harness proved the grammar is valid; it does not prove the Android bridge
applies it, and that bridge is exactly where the silent failure lived. Fold it into Task 7.

## Task 2: Parse, store, and cascade — **DONE** (`5ba3c6f`, `c64e977`)

**Files:** `summary/CallSummary.kt`, `data/transcripts/db/CallSummaryEntry.kt`, `CallSummaryDao.kt`,
`TranscriptDatabase.kt` (v2→v3), `TranscriptCascade.kt`
**Test:** `CallSummaryTest.kt` (11), `CallSummaryStorageTest.kt` (7), `TranscriptSchemaMigrationTest.kt` (3)

- [x] Failing test: a malformed summary is rejected rather than stored half-parsed.
- [x] `CallSummary` data class — **`org.json`, not `kotlinx.serialization`.** The plan named a
      library the project does not have; six known keys under a grammar do not justify adding a
      compiler plugin to the build. Unknown keys are ignored either way, so nothing is lost.
- [x] Room v2→v3, hand-written like `MIGRATION_1_2`. No destructive fallback.
- [x] Extend `TranscriptCascade` so a deleted recording takes its summary. Tested.
- [x] **Beyond the plan:** a drift guard comparing both migrations against Room's exported
      `createSql`. `MIGRATION_1_2` had never been tested. The guard catches the realistic failure —
      an entity gaining a column while its migration silently does not — and was itself verified by
      dropping a column and watching it fire. It does **not** open a v2 database and upgrade it;
      that needs Room's `MigrationTestHelper`, and is worth adding the day a migration does more
      than add a table, because one that transforms rows can be well-formed and still lose data.
- [x] Commit.

## Task 3: The job — **DONE** (`9f4dbb4`, `87b2cf5`)

**Files:** `summary/SummaryQueue.kt`, `SummaryRunner.kt`, `SummaryWorker.kt`, `SummaryScheduler.kt`,
`SummaryProgress.kt`, plus `SummaryPrompt.forMergeJson`
**Test:** `SummaryQueueTest` (10), `SummaryRunnerTest` (10), `SummaryProgressTest` (9)

- [x] The gate: no summary without a finished, non-empty transcript, none while
      `TranscriptionEngine.isRunning`, none while another is running, none without the model. The
      order the reasons are checked in is a UI decision — durable problems before transient ones.
- [x] Worker with `setProgress`, honouring `isStopped`, calling `SummaryEngine.requestAbort`. It
      re-checks `TranscriptionEngine.isRunning` on start as well as at the tap, because a queued job
      can begin long after it was asked for.
- [x] Reuse `TranscriptionProgress` for the figure shown. Chunks stop at **85%** so the merge — the
      longest single generation of the run — does not happen behind a bar that already reads
      finished. Same defect as the transcription that vanished at 70%.
- [x] **Beyond the plan:** `forMergeJson`. The existing merge prompt returns prose, but the merge's
      output goes through `CallSummary.parse` under the same grammar, so it has to name the same six
      keys or two passes of the model produce nothing. Timestamps are copied verbatim, never
      recomputed — a stamp is only valid against the original recording.
- [x] Commit.

**Not yet reachable from anywhere.** `SummaryScheduler.runNow` has no caller until Task 6 adds the
card, so none of this has run on a device.

## Task 4: The model, in Settings — **in progress**

**Files:** `summary/SummaryModel.kt`, `transcription/model/DownloadableModel.kt`, `SettingsScreen.kt`

**Do this before Task 3.** The worker needs a model *path*, and paths come from the catalogue — the
plan's original numbering was written before that dependency was visible.

- [x] Add Gemma 4 E2B to the model catalogue: URL, exact size, checksum (`0da4ac1`). A sibling enum
      rather than a case in `TranscriptionModel`, which is described by a real-time factor against
      audio and has no meaning here. `ModelRepository` was widened to `DownloadableModel` first, as
      its own revertable commit (`60b6b8c`).
- [ ] Generalise `ModelDownloadWorker` the same way. **The open risk:** that path was built for
      190–574 MB and this is 3.46 GB. Resume, progress reporting and a download long enough to
      cross a network change all behave differently at six times the size.
- [ ] A **Summaries** section in Settings, next to Transcription: download/delete, size on disk, and
      the state of the download.
- [ ] Name the licence where the user can see it before downloading: Gemma is under Google's Terms
      of Use, not an OSI-free licence. The app stays clean because it never ships the weights, but
      the person accepting them deserves to be told.
- [ ] A **Summaries** section in Settings, next to Transcription: download/delete, size on disk, and
      the state of the download.
- [ ] Commit.

## Task 5: The requirements modal — **DONE** (`f59421c`)

**Files:** `ui/common/SummaryRequirementsDialog.kt`, strings in 11 locales

Shown once before the first download. The numbers are measured, so quote them.

- [x] Copy naming: **3.5 GB to download**, about **3.5 GB of memory while it runs**, roughly **a
      minute and a half for a short call**, and that it needs a recent phone.

      **These numbers were wrong in an earlier draft and matter more than the wording around them.**
      Memory is 3.0–3.5 GB peak PSS measured while the model was loaded, not the ~2 GB first written
      here — that reading was taken after the model had been freed. 3.5 GB resident genuinely does
      rule out smaller phones, which is the whole reason this dialog exists. Timing for a long call
      is still an **extrapolation** (2–4 minutes for ten minutes of audio), so the copy must not
      quote a per-minute rate as though it had been measured. See `SummaryModel.peakMemoryBytes`.
- [x] "Don't ask again", reflected as a Settings switch — the pattern the transcription warning
      already uses.
- [x] Translations. Commit.

## Task 6: The summary on the playback screen — **DONE** (`f59421c`)

**Files:** `ui/screens/PlaybackScreen.kt`, `ui/common/SummaryCard.kt`

- [x] `SummaryCard`: intent as a headline, summary as prose, then Decisions and To-do as lists.
- [x] Items beginning `[m:ss]` render the stamp as a chip and **seek on tap**, reusing
      `TranscriptTimestamp` and the seek path the transcript sheet already uses.
- [x] Empty arrays render as nothing at all — never "No decisions", which is noise.
- [x] Absent summary: a single row offering to make one, in the same shape as the transcript row.
- [x] While running: the percentage, not a spinner.
- [x] Footer: "Generated from the transcript", and a redo action.
- [x] RTL: each item through `BidiText`, per line, as the transcript sheet does.
- [x] Commit.

## Task 7: On-device pass — **PARTLY DONE**

**Done on an emulator (2026-08-23), because it can be hammered:**

- [x] The migrations, against real SQLite. v1→v3 keeps a transcript, v2→v3 keeps a note, the new
      table is usable immediately, and a second upgrade is a no-op. This is the one that protects an
      existing phone — no destructive fallback means a wrong migration is a crash on first launch.
- [x] **The whole 3.46 GB download**, end to end: fetched, resumed through an unrelated APK install,
      verified against its SHA-256, renamed into place. Plus cancel, resume-from-offset (server
      answers HTTP 206 at exactly the right byte), discard, and the free-space guard.
- [x] The JNI bridge loads on arm64 and refuses a missing model without crashing.
- [x] **The model loads and tokenises on 2.5 GB of RAM** — far under the measured 3.5 GB peak PSS,
      because llama.cpp memory-maps the weights. See the open questions.
- [x] Every Settings state rendered and photographed: absent, waiting, downloading, paused with
      bytes banked, installed, and the requirements dialog.

**Still needs the maintainer's phone. None of this can be done here:**

- [ ] Ask before starting; it is a daily driver and this saturates the CPU.
- [ ] A Hebrew call and an English one, end to end from the button. **No summary has ever been
      produced through the app** — the emulator has no recordings and not enough memory to generate.
- [ ] Stop mid-run: no half-summary stored, no row left spinning.
- [ ] Delete the recording: summary goes with it.
- [ ] A phone that is **not** the OP12.
- [ ] The on-device grammar check owed from Task 1 — ten constrained runs, proving the *Android*
      bridge applies the grammar. The desktop harness proved the grammar is valid; the bridge is
      where the silent failure lived.
- [ ] Fill in the results here.

---

## Open questions

1. **Does it run on 8 GB? PARTLY ANSWERED — and it no longer gates Task 5, which shipped.**

   **New evidence (2026-08-23):** the model loads and tokenises on a **2.5 GB** emulator. It is
   memory-mapped, so the 3.46 GB never has to fit in RAM — the kernel pages it in on demand, and
   the measured 3.5 GB peak PSS counts mapped file pages that are reclaimable rather than a hard
   floor. **Generation on an under-spec device is still untested.** That gives a better hypothesis
   for the OP9 stall than deadlock: generation walks the whole model per token, so a device without
   room pages from storage continuously and looks stopped while crawling.

   Earlier attempt, for the record: Attempted on the OP9 Pro
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
