# Backlog

Agreed work that is **not** started, so it does not get lost between sessions. Ordered by the value it
delivers, not by effort. Anything already researched lives in `capture-research-directions.md`; this
file is for decided product and engineering work.

Status key: 🔵 agreed, not started · 🟡 in progress · ✅ done (kept briefly, then deleted)

---

## Current state — 2026-07-29

Kept at the top so a session can start from disk instead of from recall. **Update it whenever a
release is cut, a branch lands, or something starts or stops being blocked.**

**Released:** `v1.5.2` is the latest release users can get. Also published: `v1.5.2-diag-scrcpy`, a
**pre-release** debug build for issue #18 — invisible to the in-app updater, on branch
`diag/scrcpy-only`, **never to be merged**.

**Unreleased:** `main` is **18 commits ahead of `v1.5.2`** and **3 ahead of `origin/main`**. The bulk
is the **setup-health** feature — the status card now reports what real calls proved, with a call-log
sweep for calls CallVault never observed — plus the SAF delete fix and the capture-path log fix.
**1.5.3 has not been cut**: no CHANGELOG entry, no version bump.

**Hard constraint on the next release:** versionCode must exceed **10624**, which the diagnostic
pre-release used. See the `release-version-bump` memory for why a lower number is unrecoverable
without an uninstall.

**Blocked on other people:**

| Waiting for | Who | Unblocks |
|---|---|---|
| Diagnostic APK result on a real call | issue #18 reporter | Confirms or kills the v1.4.0 `DirectAudioRecorderSession` regression hypothesis — see `2026-07-28-issue-18-silent-carrier-recordings.md` |
| A carrier call with VoIP recording switched off | maintainer | Rules VoIP in or out as a factor in issue #18 |
| A Galaxy S24 FE in hand | maintainer | The resilient-recording fix below, written and unit-tested but never run on the device it was written for |

**Written but unplanned:** `2026-07-28-daemon-and-system-logs-design.md` — a design for getting daemon
diagnostics into a bug report, with no implementation plan yet. Issue #18 is the standing argument for
it: twice, the answer lived in the daemon's process where no bug report can reach.

**Also argued for by issue #18, not yet scheduled:** `SILENT` detection (an all-zeros check on the
daemon's PCM, cheap on the direct path) and a settings snapshot in the log-export header (the export
carries device and version but not the toggles, which is why the VoIP question above needs a manual
test at all).

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

## 🔵 Split `AppPreferences` into per-domain interfaces

**Why.** `AppPreferences` is 686 lines, 60 keys and 107 accessors, and every subsystem in the app
reaches into it: the knowledge-graph build on 2026-07-27 measured 188 edges and a betweenness of
0.190, bridging 33 of 243 communities — from `DaemonKeepAliveService` to `Theme` to
`VoipRecordingCoordinator`. That number is a symptom, not the finding.

The finding is *how* it clustered. Community detection split the class's own members into six groups,
and the dividing line is the **storage primitive, not the subject**: boolean writers in one group,
string writers in another, int accessors in a third, `getStringSet`/`setLong` in a fourth. Only the
storage/sync accessors clustered by meaning. There is no domain structure inside the file for the
algorithm to find, because the file has none below the comment headers.

**What.** Keep one `SharedPreferences` instance and the `Key` enum. Expose them through roughly nine
narrow interfaces — `RecordingPrefs`, `StoragePrefs`, `TransportPrefs`, `UpdatePrefs`,
`AppearancePrefs`, `FilterPrefs`, `DebugPrefs`… — each 5–12 methods, with `AppPreferences` as the
single implementation satisfying all of them. Consumers depend on the slice they actually use.

**The boundaries are already written in the file.** `Key` has 13 comment-delimited groups, and they
map nearly one-to-one onto communities that formed independently elsewhere in the graph: Storage
Routing / Sync Schedule, Retention, ADB, In-app updates, Persistent recorder server, Automation +
Filters, Developer & Debug, Audio quality, UI & Appearance. Those comments are doing an interface's
job. Cut along them and the split needs no new judgement.

**What this does not buy.** It does not decouple anything — `DaemonKeepAliveService` still needs its
settings. The wins are narrower and worth stating honestly: each consumer's dependency becomes
legible, tests can fake a 6-method interface instead of a 107-method class, and the "General" section
restructure above gets a seam to cut along. It is a wide, mechanical diff across most of the app, on
a file that has caused none of the recent failures — real value, no urgency. Do it *with* the Settings
restructure, not on its own.

---

## ⛔ Switching Wireless debugging off — NOT POSSIBLE, and why

Three attempts, all failed, all for the same underlying reason. **Do not try routes 1 or 2 again.**

**`adbd` only runs while USB debugging or Wireless debugging is enabled.** `service.adb.tcp.port` says
*where* `adbd` listens — it is not a reason for `adbd` to exist. With both switches off there is no
`adbd`, so there is nothing to launch the daemon over and nothing to keep it alive.

Measured on a OnePlus 12 (1.5.0-wdoff3, USB debugging off throughout):

```
11:02:15  Dropping Wireless debugging before launch
11:03:18  shell not ready within 60000ms (150 probes)   <- a full minute, never returned
11:03:20  Loopback self-healed after 1500ms             <- 1.5s AFTER WD was switched back on
```

The apparent success later in that log (11:04:25) is confounded — it is the exact moment USB debugging
was enabled, which starts `adbd`. **Every "it worked" observation in this investigation turned out to
have a second debugging switch on somewhere**, which is the single lesson most worth keeping.

- **Route 2 (make the daemon outlive `adbd`)** — impossible without root. Init kills the service's POSIX
  process group AND its cgroup on stop, explicitly so `setsid` cannot escape. Shizuku dies the same way
  ([#311](https://github.com/RikkaApps/Shizuku/issues/311)); its community's workaround is `adb tcpip`
  ([#864](https://github.com/RikkaApps/Shizuku/issues/864)), i.e. exactly our loopback — which does not
  solve it either.
- **Route 1 (launch over the loopback after dropping WD)** — cannot work, per the above. It also cost
  **two minutes of delayed readiness** at boot, since each attempt burns the full timeout. Reverted.

**One device difference:** on a Galaxy S24 FE the listener *did* return ~1.5 s after WD was dropped with
USB debugging off, so its `adbd` behaves differently from the OnePlus. If this is ever revisited, the
only defensible shape is **opportunistic and remembered**: after the daemon is up and idle (never on the
launch path), try the drop once, poll briefly, record the answer for that device — success keeps WD off,
failure re-enables it and never retries. That still leaves OnePlus-class devices with WD on.

**The real escape is to stop needing the daemon at call time** — Track A in
`capture-research-directions.md`. That is the only route that removes the debugging switch entirely.

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

## 🟡 Resilient recording on One UI — cause found, fix written, UNTESTED on the device

**Root cause, confirmed against AOSP source rather than guessed.** The handoff sized the ring from
`AudioRecord.getBufferSizeInFrames()`. That returns `cblk->mBufferSizeInFrames`, a **logical value the
client rewrites on every attach** — it is *not* the field that sizes the ring. The physical ring is
`roundup(frameCount) * frameSize`, allocated immediately after the control block by
`AudioFlinger::TrackBase::TrackBase`. The two agree in stock AOSP only because both are seeded from the
same `frameCount`; nothing enforces it, and on a Galaxy S24 FE they diverged — 8192 reported against a
4096-frame allocation.

So this was never a Samsung quirk: **we were reading the wrong quantity**, and the OnePlus happened to
agree. Refusing the delivery was correct — trusting the report would have read 12 KB past the end.

**Fix:** derive the ring from the mapping, which is ground truth on every device —
`wrapFrames = min(roundup(reported), largest power of two fitting in (ashmemSize - dataOff) / frameSize)`.
Self-correcting, no device table, cannot over-read by construction.

**Also fixed alongside it:** `DATA_OFF` was hardcoded to 232, which is `sizeof(audio_track_cblk_t)` on
Android **13+** only — it is 228 on Android 12 and 224 on Android 11, and `minSdk` is 30. On those two
releases the ring was being read misaligned rather than failing loudly. Now derived from
`Build.VERSION.SDK_INT`.

**Trap for whoever tests this:** the native drain rounds whatever frame count it is given up to a power
of two and masks with it, so it must be passed `wrapFrames`, **not** `frameCount` — otherwise it
recreates the oversized ring and reads out of bounds, with the Java-side check now passing. Both call
sites were updated; a third caller added later would reintroduce the bug silently.

**Still needs a Galaxy S24 FE** to confirm Resilient recording actually works there. Covered by unit
tests including the exact S24 FE numbers, but no device has run it.

---

## 🔵 "Test my setup" — prove the whole path works, before it matters

> **Superseded (2026-07-28) by [the setup-health design](2026-07-28-setup-health-status-design.md).**
> The button is gone: a test the user has to remember to press is not read by the people who need it
> most. The status card reports what real calls proved instead, and a call-log sweep catches calls
> CallVault never saw. The reasoning below still holds — only the shape of the answer changed.

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

- **Every locale has fallen behind, and nothing catches it.** Measured 2026-07-29: the base has 465
  strings, 445 of them translatable (20 carry `translatable="false"`). Eight of the nine locales — de,
  es, hu, it, pl, ru, vi, zh-rCN — are missing **47**; fr is missing **29**. Those strings render in
  English inside an otherwise translated screen. This entry previously read "three VoIP strings are
  untranslated"; that was true when written and rotted, and the three keys it named
  (`voip_recording_arming`, `voip_recording_names_hint`, `voip_recording_unavailable`) are in fact
  present in **zero** locales. There is no coverage check in the build — the `fix/complete-translations`
  branch claims to have added one, but nothing enforcing it exists in the tree, so drift is silent by
  design. Any translation work should land the check alongside it, or this entry rots again.
- **Stale screenshots** in `docs/screenshots` (21 June) — predate the current UI.
- **`WD_DISABLE_WHEN_IDLE` is a dead preference** — read by nothing.
- **Wrong contact label** on some recordings (reported, not diagnosed).
- **CI release workflow is broken**: its `SIGNING_KEYSTORE` is a *different* key from the release key,
  so it fails at signing. Releases are built locally with `signing/callvault-signing.keystore`
  (cert `c875ffd0…`). Left broken deliberately — fixing it means putting the real key in CI.
  It also still names its artifact `ShizuCallRecorder-<version>.apk` and titles the release the same,
  while the in-app updater only accepts an asset named exactly `CallVault.apk`
  (`GitHubReleases.APK_ASSET_NAME`). A release published by this workflow would therefore be **invisible
  to the updater** — fix the naming at the same time as the signing, or the first CI release silently
  reaches nobody.
- **No instrumentation tests at all** (`app/src/androidTest` does not exist). 16 unit-test files cover
  parsing, version comparison and policy decisions; everything device-shaped is verified by hand on a
  real call. That is the honest state, and it is why regressions here are found by making phone calls.
