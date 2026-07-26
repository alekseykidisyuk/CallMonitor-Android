# Backlog

Agreed work that is **not** started, so it does not get lost between sessions. Ordered by the value it
delivers, not by effort. Anything already researched lives in `capture-research-directions.md`; this
file is for decided product and engineering work.

Status key: 🔵 agreed, not started · 🟡 in progress · ✅ done (kept briefly, then deleted)

---

## 🔵 Manual "Check for updates" in Settings

**Why.** A release only surfaces two ways: a check when the app opens, throttled to once per 6 hours
(`UpdateScheduler.checkNowIfDue`), and a 24-hour periodic worker. Open the app shortly *before* a
release lands and you cannot see that release until the throttle expires or the daily worker runs —
with no way to ask. This happened for real while testing 1.4.7: the phone checked at 14:21, the release
was published at 14:29, and the only way to force a check was toggling the update switch off and on,
which is not something a user would ever guess.

**What.** A row under Settings ▸ Updates that runs a check immediately and reports the outcome in
place — "You're up to date" / "Version X is available". Bypasses the 6-hour throttle, since the user
asked explicitly; keep the throttle for the automatic path so relaunches cannot hammer the GitHub API.

**Notes.** `UpdateScheduler` already has everything needed — a one-time `UpdateCheckWorker` request is
what `checkNowIfDue` enqueues. The work is the UI row, the in-place result, and not letting repeated
taps stack (unique work + KEEP, as the install path does).

---

## 🔵 Settings restructure: a "General" section

**Why.** Settings has grown top-level sections that are really peers of each other, so the screen reads
as a flat list of everything rather than a shape.

**What.** A new top-level **General** section, with today's sections becoming sub-sections inside it:

- General
  - Visual settings
  - Experimental *(keeps its own Resilience / VoIP sub-grouping)*
  - Updates

**Notes.** `SettingsScreen` already has `SettingsSubHeader`, used for the Resilience/VoIP split inside
Experimental, so the nesting pattern exists. Section expand-state is persisted by key — keep the
existing keys where a section keeps its identity, or the user's expanded/collapsed state resets. That
is why `SECTION_EXPERIMENTAL` is still the string `"reliability"` after that rename.

---

## 🔵 `AdbShell.ensureConnected` is unbounded on the recording-start path

**Why.** It can block for ~75 s while a recording is trying to start. 1.4.6 capped one such read at
1.5 s after it caused calls to be missed entirely; this is the same class of problem, not yet fixed.
It was the agreed next priority before VoIP took over.

---

## 🔵 Smaller, known, and worth not forgetting

- **VoIP strings are English-only.** Every other feature is translated into 9 locales.
- **Stale screenshots** in `docs/screenshots` (21 June) — predate the current UI.
- **`WD_DISABLE_WHEN_IDLE` is a dead preference** — read by nothing.
- **Wrong contact label** on some recordings (reported, not diagnosed).
- **CI release workflow is broken**: its `SIGNING_KEYSTORE` is a *different* key from the release key,
  so it fails at signing. Releases are built locally with `signing/callvault-signing.keystore`
  (cert `c875ffd0…`). Left broken deliberately — fixing it means putting the real key in CI.
- **No instrumentation tests at all** (`app/src/androidTest` does not exist). 16 unit-test files cover
  parsing, version comparison and policy decisions; everything device-shaped is verified by hand on a
  real call. That is the honest state, and it is why regressions here are found by making phone calls.
