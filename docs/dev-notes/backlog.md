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

## 🔵 Control over what gets recorded

Three related asks, all about the user deciding rather than the rules deciding:

- **Manual VoIP recording** — start/stop an app call's recording by hand. The plumbing exists
  (`VoipRecordingCoordinator.start/stop`); what's missing is a control surface and a mode where the
  detector arms but does not auto-start.
- **Choose when to record** — per-call rather than by rule, including a prompt at call start. The
  carrier path already has a standby notification with a "Record" action
  (`ACTION_MANUAL_START`) — that pattern extends to VoIP rather than needing a new one.
- **Turn cellular recording off independently.** Today carrier recording is governed by the automatic
  rules and VoIP is a separate opt-in; there is no way to say "app calls only". Careful: this is not
  the same as the existing per-contact ignore rules, and it must not silently disable recording for
  someone who expected it — default on, and state clearly what it does.

---

## 🔵 Resilient recording is rejected on One UI *(investigating)*

The audio handoff fails on a Galaxy S24 FE:

```
handoff rejected: geometry doesn't fit ashmem (dataOff=232 wrapFrames=8192 frameSize=4 > 20480)
```

It fails **safely** — the receiver rejects the geometry rather than reading past the mapping, and the
recording falls back to the normal daemon path, so calls are still captured. But the resilience the
feature exists to provide is inactive on that device, and the README says so rather than implying it
works everywhere.

The numbers say the ring buffer is larger than the shared memory the daemon handed over
(8192 frames × 4 bytes + 232 > 20480), so either the frame count reported by the daemon is not the one
backing the mapping on this device, or One UI sizes the cblk region differently. Start by logging the
actual ashmem size next to the computed geometry on both devices and comparing — the OnePlus numbers
are known-good, so the difference should be obvious. See `spike-audio-handoff.md` for the ring layout
(`DATA_OFF=232`, `mFront`/`mRear` word offsets) that this arithmetic comes from.

---

## 🔵 "Test my setup" — prove the whole path works, before it matters

**Why.** This app fails *silently*, and the failure is discovered after the call you needed. Every
recovery mechanism shipped so far (screen-lock USB fix, resilient recording, fast daemon recovery)
reduces the chance of a failure without ever telling the user whether their setup works **right now**.
Every naming bug in the VoIP feature was found by making real phone calls, because there is no other
way to exercise the path.

**What.** One action that runs the real pipeline end to end and reports which step failed:

1. ADB connection alive (and how long it took — this is where the ~75 s stall would show).
2. Daemon reachable, binder responsive.
3. Capture starts on the configured audio source.
4. Encoder produces non-silent frames.
5. File is created in the chosen SAF folder, catalogued, and appears in the list.
6. Clean teardown, and the test file removed.

Report per step, with the failing step named in plain language and a link to the setting that fixes it.

**Notes.** Do not fake it end-to-end: a test that stubs any step will pass while the real path is
broken, which is worse than no test. The daemon already exposes what is needed; a `VOICE_CALL` source
cannot be exercised outside a call, so the test should use `MIC` and say so, or capture briefly from
the configured source and report "could not verify without a live call" rather than implying more than
it checked. Reuse `voipFarPartyHeard`'s honesty pattern — report what was actually observed.

---

## 🔵 Per-app VoIP support, checked at runtime

**Why.** Settings currently says VoIP recording is experimental and "some apps block recording, and
this cannot be known until a call is under way". Half of that is now avoidable: whether an app opts out
of capture is readable **before** a call, from the audio flags its playback carries.

**What.** A per-app list under Settings ▸ Experimental ▸ VoIP calls — installed calling apps with a
real status each: verified working, not yet tried, or blocks capture. Turns a vague warning into a
fact, and tells the user *which* of their apps will work rather than leaving them to discover it.

**Notes.** The distinction that matters: `FLAG_NO_MEDIA_PROJECTION` (0x800) is bypassable by this
route and is what WhatsApp, Telegram and Signal all set; `FLAG_NO_SYSTEM_CAPTURE` (0x1000,
`ALLOW_CAPTURE_BY_NONE`) is checked before any permission and is **not** bypassable. Reading the flag
requires an active playback track, so it cannot be sampled at rest for an app that is not in a call —
expect "not yet tried" to be a real state, and record the observed result after each call instead of
promising a prediction. Feeds the README's tested-devices table.

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
