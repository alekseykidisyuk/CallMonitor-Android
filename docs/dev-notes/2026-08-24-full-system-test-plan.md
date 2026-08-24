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
| A1 | Whole unit suite | `testDebugUnitTest` | |
| A2 | Release build compiles | `assembleRelease` | |
| A3 | Release lint (crash-class only) | `lintVitalRelease` | |
| A4 | Every locale has every string | `checkTranslations` | |
| A5 | Release APK is **not** debuggable | manifest check on the built APK | |
| A6 | Version is bumped for the release | `ciVersionName` in `app/build.gradle.kts` | |

## B. Privileged transport — the mode machinery

The class of bug that cost a full day: two recorder hosts alive at once. Each row is one of the five
rules in `only-one-recorder-host`.

| ID | What | Tier | How | Status |
|---|---|---|---|---|
| B1 | Standalone → Shizuku leaves **exactly one** recorder | D | switch, then `ps`/`pgrep` both patterns | |
| B2 | Shizuku → standalone leaves **exactly one** recorder | D | as above | |
| B3 | The switch waits for the old binder to actually die | H+D | unit + log ordering | |
| B4 | `onBinderDied` does not clear a **live** holder | H | unit | |
| B5 | `killStaleRecorders` never kills itself | H+E | unit + round-trip still connected after | |
| B6 | Entering Shizuku drops the old user service first | D | log line + fresh pid | |
| B7 | No CallVault service runs in the mode that does not own the recorder | D | `dumpsys activity services` | |
| B8 | `DaemonKeepAliveService` is stopped on entering Shizuku | D | dumpsys + log | |
| B9 | …and does not start on app launch in Shizuku mode | D | force-stop, launch, dumpsys | |
| B10 | Wireless debugging is left alone in Shizuku mode | D | `settings get global adb_wifi_enabled` | |
| B11 | Every daemon start goes through `RecorderBackend` | H | grep gate: no direct launcher calls | |
| B12 | Binder round-trip works over our own daemon | E | `RecorderDaemonRoundTripTest` | |
| B13 | Binder round-trip works over Shizuku | D | `RecorderShizukuRoundTripTest` | |
| B14 | A stale user service is torn down on app replace | D | install over, check pid changed | |

## C. Capability gating — greying out and auto-off

| ID | What | Tier | How | Status |
|---|---|---|---|---|
| C1 | `ModeCapability` maps every capability for both modes | H | unit | |
| C2 | Switching to Shizuku turns **off** what it cannot do | H | `disableWhatModeCannotDo` unit | |
| C3 | Returning to standalone never silently turns anything **on** | H | unit — the asymmetry is the point | |
| C4 | The reconcile also runs on **app start**, not only on a switch | H+D | unit + prefs after launch | |
| C5 | Greyed rows are actually disabled in the UI, not just unset | D | UI dump of Settings in Shizuku mode | |
| C6 | Handoff is ruled out under Shizuku | H | `HandoffPolicy` unit | |
| C7 | Setup prerequisites differ correctly per mode | H | `SetupPrerequisitesModeTest` | |

## D. Capture pipelines — the three paths

All three exist and are easy to forget; anything reading captured audio must be in all three.

| ID | What | Tier | How | Status |
|---|---|---|---|---|
| D1 | Carrier call, standalone, **direct** AudioRecord | M | real call, then check the log names the direct path | |
| D2 | Carrier call, standalone, **handoff** (resilient on) | M | real call — this is the OP12's default path | |
| D3 | Carrier call, **Shizuku** (scrcpy) | M | real call on the OP9 | |
| D4 | Two-sided audio present in D1/D2/D3 | M | **listen** to the file — a lost far side is invisible in logs | |
| D5 | VoIP call, standalone | M | real WhatsApp call | |
| D6 | VoIP arming survives a mode round-trip | M | round-trip, then a real VoIP call | |
| D7 | VoIP is not offered under Shizuku | H+D | capability unit + UI | |
| D8 | A missed VoIP call notifies rather than retries | H | unit — retry is impossible by design | |
| D9 | Speaker turns are produced in standalone | M | real call, check `Stored speaker turns` | |
| D10 | Speaker turns are absent under Shizuku **and the user is told** | D | known gap — nothing surfaces it | |
| D11 | Offline (loopback) recording is standalone-only | H | capability unit | |
| D12 | The daemon survives a mid-call kill when resilient is on | M | kill the daemon during a real call | |

## E. Services, receivers and the daemon (tier D unless noted)

Every component declared in the manifest, plus the workers.

| ID | Component | What is checked | Status |
|---|---|---|---|
| E1 | `CallMonitorService` | starts on a call, stops after, does not linger | |
| E2 | `RecordingForegroundService` | foreground type is legal on 14+, notification shows, stops cleanly | |
| E3 | `DaemonKeepAliveService` | runs in standalone only; stopped and never restarted in Shizuku | |
| E4 | `AdbConnectionService` | connects in standalone; inert in Shizuku | |
| E5 | `AdbPairingService` | pairing flow reachable from onboarding | |
| E6 | `PhoneStateReceiver` | registered; fires on a real call (protected broadcast — cannot be faked) | |
| E7 | `BootReceiver` | fires on boot in **both** modes (was gated on pairing) | |
| E8 | `RetentionTimezoneReceiver` | reschedules the sweep on a timezone change | |
| E9 | `UpdateInstallReceiver` | handles the install result | |
| E10 | `UpdatePackageReplacedReceiver` | tears the daemon/user service down after replace | |
| E11 | `RecorderBinderProvider` | binder delivery uses our own authority | |
| E12 | `MainActivity` | launches, survives rotation and process death | |

## F. Background work (WorkManager)

| ID | Worker | What is checked | Tier | Status |
|---|---|---|---|---|
| F1 | `TranscriptionWorker` | enqueued, constraints honoured, writes transcripts | E+M | |
| F2 | `SummaryWorker` | runs after transcription, respects language | E+M | |
| F3 | `ModelDownloadWorker` | downloads, verifies, resumes | M | |
| F4 | `RecordingCopyWorker` | copies to the chosen storage target | D | |
| F5 | `SyncSweepWorker` | Drive sync on schedule | M | |
| F6 | `RetentionSweepWorker` | deletes past the retention window | H+D | |
| F7 | `UpdateCheckWorker` | polls GitHub, respects the toggle | D | |
| F8 | `UpdateInstallWorker` | standalone: silent `pm install`; Shizuku: `UNAVAILABLE` → one tap | H+D | |

## G. Post-processing — transcription, summaries, speakers, search

| ID | What | Tier | Status |
|---|---|---|---|
| G1 | Transcription produces segments for a real Hebrew call | M | |
| G2 | Language pinning beats auto-detect | M | |
| G3 | A **non-Hebrew** call transcribes correctly | M | ← 2.1.0 gate |
| G4 | A **long** call (>15 min) transcribes without OOM | M | ← 2.1.0 gate |
| G5 | Summaries generate and respect the requirements | M | |
| G6 | Speaker map override and confirm persist | H+D | |
| G7 | Search finds a word inside a transcript | M | |
| G8 | Deleting a recording makes its transcript unsearchable | M | |
| G9 | Room migration v1→v2 keeps data | E | |
| G10 | Transcription is untouched by the privileged mode | H | |

## H. Storage, sync, retention, playback, UI

| ID | What | Tier | Status |
|---|---|---|---|
| H1 | Recording folder + storage target honoured | D | |
| H2 | Retention deletes only past the window | H | |
| H3 | Drive sync round-trip | M | |
| H4 | Playback screen opens, waveform draws, notes persist | D+M | |
| H5 | Per-copy delete lives in the confirm dialog | D | |
| H6 | Home status card reflects the **live** mode | D | |
| H7 | The mode switch modal cannot be dismissed while working | D | |
| H8 | The modal's ready tick is inline and green | D | |
| H9 | Onboarding offers both modes and completes on either | D | |
| H10 | Settings restructure — every section reachable | D | |
| H11 | Theme, dynamic colour, and no accidental coral | D | |
| H12 | i18n — no missing strings in any locale | H | |

## I. Regression guards for bugs already fixed

Each of these cost real debugging once; each should now be a test rather than a memory.

| ID | The bug | Guard | Tier | Status |
|---|---|---|---|---|
| I1 | Two recorder hosts alive at once | B1/B2/B7 | D | |
| I2 | Stale user service holding a dead APK path | B14 | D | |
| I3 | Keep-alive draining battery in Shizuku mode | B8/B9 | D | |
| I4 | Status card showing the previous mode | H6 | D | |
| I5 | Reconcile skipped for users already in Shizuku | C4 | H+D | |
| I6 | Updater reporting a false failure in Shizuku | F8 | H | |
| I7 | Boot and warmup skipped in Shizuku mode | E7 | D | |
| I8 | A second voice AudioRecord silently dropping the near side | D4 | M | |
| I9 | M3 defaults rendering coral | H11 | D | |
| I10 | Daemon churn from `killStaleDaemons` on app restart | B5 | H+E | |

---

## Results

Filled in as the plan is executed.
