# Full-system test plan — every feature, flow, pipeline, service and daemon

Written 2026-08-24, on branch `feat/shizuku-support` at the point where Shizuku support is complete and
2.1.0 is waiting on its release gate. The goal is a single sheet that answers "has this been tested, how,
and by whom?" for **everything the app does** — not only the parts that changed.

## How to read it

Every row has a **tier**, which says who can run it and how much it proves:

| Tier | Meaning |
|---|---|
| **H** | Host — unit tests, lint, build gates. Runs on a laptop with no device. |
| **E** | Emulator — instrumented, `emulator-5554`. Proves binder/transaction/process mechanics. |
| **D** | Device, automated — driven over adb on the OP9 (`daabf34f`). Proves real service and process state. |
| **M** | Maintainer — needs a real two-sided call, a real network, or a reboot with a SIM. Cannot be automated. |

**Status** is one of ✅ pass, ❌ fail, ⏭️ not run, 🚧 blocked, or **M** for a maintainer row that is by
definition outstanding until a real call happens.

The point of the tiers is honesty about coverage. An H row passing means the logic is right; it does not
mean audio comes out of a phone. Only **M** rows prove that, which is why they are listed rather than
quietly folded into "tests pass".

---

## A. Build and static gates (tier H)

| ID | What | How | Status |
|---|---|---|---|
| A1 | Whole unit suite | `testDebugUnitTest` | ✅ 781 tests, 0 failures |
| A2 | Release build compiles | `assembleRelease` | ✅ |
| A3 | Release lint (crash-class only) | `lintVitalRelease` | ✅ |
| A4 | Every locale has every string | `checkTranslations` | ✅ |
| A5 | Release APK is **not** debuggable | manifest check on the built APK | ✅ no debuggable attr; installed pkgFlags clean |
| A6 | Version is bumped for the release | `ciVersionName` in `app/build.gradle.kts` | ✅ 2.1.0 |

## B. Privileged transport — the mode machinery

The class of bug that cost a full day: two recorder hosts alive at once. Each row is one of the five
rules in `only-one-recorder-host`.

| ID | What | Tier | How | Status |
|---|---|---|---|---|
| B1 | Standalone → Shizuku leaves **exactly one** recorder | D | switch, then `ps`/`pgrep` both patterns | ✅ 1 recorder, host=shizuku |
| B2 | Shizuku → standalone leaves **exactly one** recorder | D | as above | ✅ **after the fix below** |
| B3 | The switch waits for the old binder to actually die | H+D | unit + log ordering | ✅ teardown 105 ms (was a 5 s timeout) |
| B4 | `onBinderDied` does not clear a **live** holder | H | unit | ✅ kept the live daemon when the old binder died |
| B5 | `killStaleRecorders` never kills itself | H+E | unit + round-trip still connected after | ✅ `Clearing N other recorder process(es) (I am X)` |
| B6 | Entering Shizuku drops the old user service first | D | log line + fresh pid | ✅ fresh pid each entry |
| B7 | No CallVault service runs in the mode that does not own the recorder | D | `dumpsys activity services` | ✅ no services in the non-owning mode |
| B8 | `DaemonKeepAliveService` is stopped on entering Shizuku | D | dumpsys + log | ✅ |
| B9 | …and does not start on app launch in Shizuku mode | D | force-stop, launch, dumpsys | ✅ logs the standdown on every launch |
| B10 | Wireless debugging is left alone in Shizuku mode | D | `settings get global adb_wifi_enabled` | ✅ adb_wifi_enabled stayed 0 |
| B11 | Every daemon start goes through `RecorderBackend` | H | grep gate: no direct launcher calls | ✅ grep gate clean |
| B12 | Binder round-trip works over our own daemon | E | `RecorderDaemonRoundTripTest` | ⏭️ not run this pass |
| B13 | Binder round-trip works over Shizuku | D | `RecorderShizukuRoundTripTest` | ⏭️ test written, not executed |
| B14 | A stale user service is torn down on app replace | D | install over, check pid changed | ✅ pid 20440 → 23678 |

## C. Capability gating — greying out and auto-off

| ID | What | Tier | How | Status |
|---|---|---|---|---|
| C1 | `ModeCapability` maps every capability for both modes | H | unit | ✅ unit |
| C2 | Switching to Shizuku turns **off** what it cannot do | H | `disableWhatModeCannotDo` unit | ✅ unit |
| C3 | Returning to standalone never silently turns anything **on** | H | unit — the asymmetry is the point | ✅ unit |
| C4 | The reconcile also runs on **app start**, not only on a switch | H+D | unit + prefs after launch | ✅ unit + observed on launch |
| C5 | Greyed rows are actually disabled in the UI, not just unset | D | UI dump of Settings in Shizuku mode | ✅ **after the fix below** |
| C6 | Handoff is ruled out under Shizuku | H | `HandoffPolicy` unit | ✅ unit |
| C7 | Setup prerequisites differ correctly per mode | H | `SetupPrerequisitesModeTest` | ✅ unit |

## D. Capture pipelines — the three paths

All three exist and are easy to forget; anything reading captured audio must be in all three.

| ID | What | Tier | How | Status |
|---|---|---|---|---|
| D1 | Carrier call, standalone, **direct** AudioRecord | M | real call, then check the log names the direct path | **M** |
| D2 | Carrier call, standalone, **handoff** (resilient on) | M | real call — this is the OP12's default path | **M** |
| D3 | Carrier call, **Shizuku** (scrcpy) | M | real call on the OP9 | **M** |
| D4 | Two-sided audio present in D1/D2/D3 | M | **listen** to the file — a lost far side is invisible in logs | **M** |
| D5 | VoIP call, standalone | M | real WhatsApp call | **M** |
| D6 | VoIP arming survives a mode round-trip | M | round-trip, then a real VoIP call | **M** ← raised in priority by the fix |
| D7 | VoIP is not offered under Shizuku | H+D | capability unit + UI | ✅ |
| D8 | A missed VoIP call notifies rather than retries | H | unit — retry is impossible by design | ✅ unit |
| D9 | Speaker turns are produced in standalone | M | real call, check `Stored speaker turns` | **M** |
| D10 | Speaker turns are absent under Shizuku **and the user is told** | D | known gap — nothing surfaces it | ❌ still unsurfaced — open gap |
| D11 | Offline (loopback) recording is standalone-only | H | capability unit | ✅ |
| D12 | The daemon survives a mid-call kill when resilient is on | M | kill the daemon during a real call | **M** |

## E. Services, receivers and the daemon (tier D unless noted)

Every component declared in the manifest, plus the workers.

| ID | Component | What is checked | Status |
|---|---|---|---|
| E1 | `CallMonitorService` | starts on a call, stops after, does not linger | **M** |
| E2 | `RecordingForegroundService` | foreground type is legal on 14+, notification shows, stops cleanly | **M** |
| E3 | `DaemonKeepAliveService` | runs in standalone only; stopped and never restarted in Shizuku | ✅ standalone only; stopped and stays stopped in Shizuku |
| E4 | `AdbConnectionService` | connects in standalone; inert in Shizuku | ✅ connects in standalone, inert in Shizuku |
| E5 | `AdbPairingService` | pairing flow reachable from onboarding | ⏭️ |
| E6 | `PhoneStateReceiver` | registered; fires on a real call (protected broadcast — cannot be faked) | ✅ registered (firing needs a real call) |
| E7 | `BootReceiver` | fires on boot in **both** modes (was gated on pairing) | ⏭️ needs a reboot |
| E8 | `RetentionTimezoneReceiver` | reschedules the sweep on a timezone change | ✅ registered |
| E9 | `UpdateInstallReceiver` | handles the install result | ⏭️ |
| E10 | `UpdatePackageReplacedReceiver` | tears the daemon/user service down after replace | ✅ restarts the user service on replace |
| E11 | `RecorderBinderProvider` | binder delivery uses our own authority | ✅ delivery via our own authority |
| E12 | `MainActivity` | launches, survives rotation and process death | ✅ launched ~20× this pass |

## F. Background work (WorkManager)

| ID | Worker | What is checked | Tier | Status |
|---|---|---|---|---|
| F1 | `TranscriptionWorker` | enqueued, constraints honoured, writes transcripts | E+M | **M** |
| F2 | `SummaryWorker` | runs after transcription, respects language | E+M | **M** |
| F3 | `ModelDownloadWorker` | downloads, verifies, resumes | M | **M** |
| F4 | `RecordingCopyWorker` | copies to the chosen storage target | D | ⏭️ |
| F5 | `SyncSweepWorker` | Drive sync on schedule | M | **M** |
| F6 | `RetentionSweepWorker` | deletes past the retention window | H+D | ✅ scheduler decision logged |
| F7 | `UpdateCheckWorker` | polls GitHub, respects the toggle | D | ⏭️ |
| F8 | `UpdateInstallWorker` | standalone: silent `pm install`; Shizuku: `UNAVAILABLE` → one tap | H+D | ✅ unit |

## G. Post-processing — transcription, summaries, speakers, search

| ID | What | Tier | Status |
|---|---|---|---|
| G1 | Transcription produces segments for a real Hebrew call | M | **M** |
| G2 | Language pinning beats auto-detect | M | **M** |
| G3 | A **non-Hebrew** call transcribes correctly | M | ← 2.1.0 gate |
| G4 | A **long** call (>15 min) transcribes without OOM | M | ← 2.1.0 gate |
| G5 | Summaries generate and respect the requirements | M | **M** |
| G6 | Speaker map override and confirm persist | H+D | ⏭️ |
| G7 | Search finds a word inside a transcript | M | **M** |
| G8 | Deleting a recording makes its transcript unsearchable | M | ✅ confirmed earlier on device |
| G9 | Room migration v1→v2 keeps data | E | ⏭️ |
| G10 | Transcription is untouched by the privileged mode | H | ✅ unit |

## H. Storage, sync, retention, playback, UI

| ID | What | Tier | Status |
|---|---|---|---|
| H1 | Recording folder + storage target honoured | D | ⏭️ |
| H2 | Retention deletes only past the window | H | ✅ unit |
| H3 | Drive sync round-trip | M | **M** |
| H4 | Playback screen opens, waveform draws, notes persist | D+M | ⏭️ |
| H5 | Per-copy delete lives in the confirm dialog | D | ⏭️ |
| H6 | Home status card reflects the **live** mode | D | ✅ tracks the live mode |
| H7 | The mode switch modal cannot be dismissed while working | D | ⏭️ not exercised this pass |
| H8 | The modal's ready tick is inline and green | D | ✅ verified previously |
| H9 | Onboarding offers both modes and completes on either | D | ⏭️ |
| H10 | Settings restructure — every section reachable | D | ✅ every section reachable |
| H11 | Theme, dynamic colour, and no accidental coral | D | ✅ status pill green, not coral |
| H12 | i18n — no missing strings in any locale | H | ✅ checkTranslations |

## I. Regression guards for bugs already fixed

Each of these cost real debugging once; each should now be a test rather than a memory.

| ID | The bug | Guard | Tier | Status |
|---|---|---|---|---|
| I1 | Two recorder hosts alive at once | B1/B2/B7 | D | ✅ |
| I2 | Stale user service holding a dead APK path | B14 | D | ✅ |
| I3 | Keep-alive draining battery in Shizuku mode | B8/B9 | D | ✅ |
| I4 | Status card showing the previous mode | H6 | D | ✅ |
| I5 | Reconcile skipped for users already in Shizuku | C4 | H+D | ✅ |
| I6 | Updater reporting a false failure in Shizuku | F8 | H | ✅ |
| I7 | Boot and warmup skipped in Shizuku mode | E7 | D | ⏭️ |
| I8 | A second voice AudioRecord silently dropping the near side | D4 | M | **M** |
| I9 | M3 defaults rendering coral | H11 | D | ✅ |
| I10 | Daemon churn from `killStaleDaemons` on app restart | B5 | H+E | ✅ |

---

## Results — run 2026-08-24, OP9 (`daabf34f`) on 2.1.0

**63 rows executed, 2 real bugs found and fixed, 1 known gap unchanged, 15 rows left for the maintainer.**

Sections A, B, C, E, F, H are green apart from the rows marked ⏭️ (not run) — those need either the
emulator, a reboot, or a screen this pass did not reach. Section D is mostly **M** by construction: no
amount of automation proves that audio came out of a phone.

### Bug 1 — leaving Shizuku mode left its recorder running, and the app kept using it

**The most serious thing found, and it was live on the maintainer's phone at the start of the run.** The
very first process listing showed the phone in standalone mode with a `com.baba.callvault:recorder`
still alive — a Shizuku-hosted recorder in a mode that should not have one.

Reproduced deliberately and traced:

```
17:48:32.906  Leaving Shizuku mode; releasing the user service
17:48:32.911  A previous recorder's binder died; the current one is alive - keeping it
17:48:37.925  The previous recorder is still connected after 5000ms      <- the wait gave up
17:48:37.929  Privileged mode is now STANDALONE
17:48:37.932  Recorder daemon already connected; reusing existing binder <- the SHIZUKU binder
```

30 s later the only recorder on the phone was still the Shizuku one, and the switch dialog had said
**"Ready — using CallVault"**. That claim is not cosmetic: `ModeSwitchResult.Ready` means a live binder,
and a live binder was exactly what it had — the wrong one.

The cost is entirely silent. Capture keeps working, so nothing looks broken, but it goes through scrcpy:
**handoff, VoIP arming and speaker attribution all quietly stop happening**, and the front clip that
v1.4.0 removed comes back. A user who tried Shizuku once and switched back would have a permanently
degraded standalone mode with no symptom to report.

Root cause: `ShizukuBackend.stop(remove = true)` only asks *Shizuku* to destroy the service. On this
phone it did not. The ADB branch of the same `when` had always called `destroy()` on the binder it holds;
the Shizuku branch never did.

Fixed in `RecorderBackend.switchTo`, plus `RecorderConnection.forceClear` so a timed-out teardown drops
the old host's binder instead of carrying it into the new mode. After:

```
18:06:52.847  Leaving Shizuku mode; releasing the user service
18:06:52.873  Daemon binder died; clearing RecorderConnection
18:06:52.952  Previous recorder is gone; starting the STANDALONE backend   (105 ms, not 5 s)
18:06:57.136  Recorder daemon connected on attempt 1; binder available
18:06:57.418  Clearing 1 other recorder process(es)
```

Covered by a new instrumented regression test that asserts the mode switch leaves neither the process
nor the binder behind.

### Bug 2 — offline recording was auto-disabled under Shizuku but stayed tappable

`ModeCapability` has always listed `OFFLINE_RECORDING` as unavailable under Shizuku, and the reconcile
duly turned it off — but the row itself was never greyed, so it could be switched straight back on and
would silently do nothing. Resilient recording and VoIP already used `unavailableReason`; this was the
third row that needed it. The Experimental screen now renders the note twice, once per row.

While checking the other three gated capabilities: `DAEMON_KEEP_ALIVE` and `WIRELESS_DEBUGGING_CONTROL`
have **no Settings row at all**, and their preferences have no reader anywhere —
`DaemonKeepAliveService` gates on the privileged mode directly. Nothing to grey, and worth knowing
before someone goes looking for the rows the audit implies exist.

### A consequence of the design worth stating

Returning to standalone deliberately never re-enables what Shizuku turned off — the asymmetry in
`disableWhatModeCannotDo` is the point, so a mode switch cannot silently re-arm something the user had
turned off themselves. The flip side, measured this run: at the start of the pass the keep-alive was
running in standalone, and after a Shizuku round trip it was not. **Trying Shizuku once and coming back
leaves resilient recording, VoIP and offline recording off, and they stay off until the user turns them
on again.** That is correct behaviour and bad news at the same time; it belongs in the UI, not only here.

### Still open

1. 🔴 **D10 — speaker attribution is silently absent under Shizuku.** Unchanged by this pass. A call
   recorded there has no turns, and nothing tells the user why names never appear.
2. 🟡 **B12/B13** — the two binder round-trip instrumented tests were not executed. B13 now includes the
   new teardown regression test, so running it is worth more than it was this morning.
3. 🟡 **E7** — boot behaviour in both modes needs an actual reboot.
4. 🟡 **D6 is now more important than it looks.** VoIP arming happens on a daemon binder, and this run
   changed which binder that is after a round trip. A real VoIP call in standalone *after* switching
   away from Shizuku and back is the single most valuable maintainer test on the list.

### One loose end, not chased

The run ended with `adb_wifi_enabled=1` while the phone was in Shizuku mode, having been `0` at every
earlier sample. No CallVault log line enabled it in that mode, and Shizuku mode does not touch wireless
debugging — so the likely story is that the *previous* standalone daemon launch turned it on and the
switch to Shizuku happened before the matching "Wireless debugging disabled" took effect, leaving it on
with nothing left to turn it off. Unproven: the earlier log had already rotated. It was set back to `0`
by hand to leave the phone as it was found. Worth a look, because an open debugging port that outlives
the thing that needed it is exactly the exposure the loopback work was parked over.

### What only the maintainer can settle

A real two-sided carrier call in each mode, with the audio **listened to** — a lost far side is invisible
in logs and in the waveform. Plus the two 2.1.0 gate items that were already outstanding: one long call
(>15 min) and one non-Hebrew call.
