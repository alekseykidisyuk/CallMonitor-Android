# Shizuku mode: what works, what cannot, and what is greyed out

A sweep of **every** setting and pipeline against both privileged modes, so the answer to "does X work
under Shizuku?" is written down rather than rediscovered on a lost call.

Evidence is marked: **measured** = observed on a device or emulator; **read** = traced through the code
and not yet exercised; **assumed** = neither, and therefore a job, not a fact.

---

## The two facts everything follows from

1. **A Shizuku-hosted process cannot get an `AudioRecord` into RECORDING state.** *(measured — emulator
   as a failed create, OnePlus 9 Pro as a failed start)* Capture there happens through **scrcpy**, which
   spawns its own process. Anything needing a privileged `AudioRecord` **in the recorder process** is out.
2. **In Shizuku mode there is no embedded ADB connection at all.** Anything that drives adb has nothing
   to talk to.

ShizuCallRecorder — running on Shizuku far longer than we have — captures **only** through scrcpy, which
is the same conclusion reached independently.

---

## Pipelines

| Pipeline | Standalone | Shizuku | Evidence | Handling |
|---|---|---|---|---|
| **Carrier call recording** | direct AudioRecord | **scrcpy** | measured (real call, 46 KB) | works; quality note below |
| **Resilient recording (handoff)** | ✅ | ❌ | measured — produced a 0-byte file | `HandoffPolicy` rules it out; setting greyed + auto-off |
| **VoIP (app call) recording** | ✅ | ❌ | read — `VoipCaptureSession` runs two `AudioRecord`s against a dynamic policy | greyed + auto-off |
| **Speaker attribution** | ✅ | ❌ | read — `SpeakerTurnDetector` lives only in `DirectAudioRecorderSession`; the scrcpy path never sees raw channels | *not yet surfaced — see gaps* |
| **Offline recording (loopback adb)** | ✅ | ❌ | read — pure embedded-ADB machinery | auto-off |
| **Wireless-debugging control / USB default mode** | ✅ | n/a | read | auto-off; inert |
| **Daemon keep-alive** | ✅ | n/a | read — Shizuku restarts its own `daemon(true)` service | inert in `start()`; auto-off |
| **Daemon lifecycle after app update** | kill + relaunch | **restart the user service** | measured — a stale service kept a dead APK path and lost scrcpy | version = versionCode, plus explicit teardown on `MY_PACKAGE_REPLACED` |
| **Boot** | `BootReceiver` → call monitor + warmup | same | read | was skipped entirely (gated on pairing); fixed |
| **App-start warmup** | ✅ | ✅ | read | was skipped (same gate); fixed |
| **Onboarding completion** | pairing | choosing Shizuku | read | was never satisfiable; fixed |
| **Silent in-app update** | `pm install` over adb | ❌ → system installer | read | `UNAVAILABLE` → one-tap install |
| **Privileged grants (appops / roles)** | ✅ | ✅ | measured — `grantAppOp → true` | works in both; ColorOS needs "Disable permission monitoring" |
| **Transcription / summaries** | ✅ | ✅ | read — operates on finished files | untouched |
| **Storage, Drive sync, retention** | ✅ | ✅ | read — file-level | untouched |
| **Call detection, filters, auto-record rules** | ✅ | ✅ | read — telephony only | untouched |
| **Diagnostics / log export** | ✅ | ❌ | read — `SystemLogCollector` runs `logcat -g` over `AdbShell` | **gap, see below** |

## Settings, one by one

**Unaffected by the mode** — recording folder, storage target, Drive folder, sync schedule and time,
retention (all four keys), transcription mode/hour/minute/charging/batch/confirm/model/language/ask,
summary language and requirements, speaker map override + confirmed *(stored fine; nothing new to store
under Shizuku)*, carrier recording master switch, auto-record incoming/outgoing, all four ignore rules
and both contact lists, file-name template, audio codec, audio bitrate, theme, dynamic colour, toasts,
vibration, logging toggle, debug flags, developer-mode unlock, update-check toggle and all update
bookkeeping keys, disclaimer, wizard-completed.

**Greyed out and turned off automatically in Shizuku mode** (`ModeCapability` +
`AppPreferences.disableWhatModeCannotDo`):

| Setting | Capability |
|---|---|
| Resilient recording | `RESILIENT_RECORDING` |
| VoIP recording + VoIP auto-start | `VOIP_RECORDING` |
| Offline recording | `OFFLINE_RECORDING` |
| Keep daemon warm (persistent server) | `DAEMON_KEEP_ALIVE` |
| Turn Wireless debugging off when idle | `WIRELESS_DEBUGGING_CONTROL` |

Only ever switched **off**, and only what the mode cannot do — so returning to standalone never silently
re-enables something the user had deliberately turned off.

**Audio source** stays fully offered: under Shizuku every source goes to scrcpy, which is the engine the
list was written for in the first place.

---

## Gaps still open

1. 🔴 **Speaker attribution is silently absent under Shizuku.** A call recorded there has no turns, so its
   transcript cannot be attributed. Nothing tells the user. The transcript sheet should say so rather
   than simply never offering names.
2. 🔴 **Diagnostics/log export needs the ADB shell.** `SystemLogCollector` grows the logcat ring and reads
   it via `AdbShell`, so bug-report export is degraded in Shizuku mode. The user service *can* run
   processes (`PrivilegedGrants` already does), so this is routable — it just has not been routed.
3. 🟡 **Front clip returns.** scrcpy costs the ~1–2 s of audio that direct capture removed in v1.4.0.
   Inherent to the mode; worth stating in the UI beside the greyed-out rows.
4. 🟡 **Cold-start race.** Shizuku's bind is asynchronous (10 s budget). A call arriving seconds after a
   reboot — before the user has started Shizuku — cannot record, and Shizuku must be started by hand on
   every boot. The status card names it; nothing pre-warms it.

## Must be checked before this ships

- [ ] **Switching back to standalone works fully.** *(the maintainer asked for this explicitly)* Turning
      Shizuku off must stop the user service, relaunch our own daemon, and record a real call — and the
      settings that were auto-disabled must be re-enableable. **Nothing about the return path has been
      exercised yet**; only the outward journey has.
- [ ] A real VoIP call in **standalone** after a mode round-trip, since arming happens on a daemon binder.
- [ ] Speaker attribution still works in standalone after a round-trip.
- [ ] A reboot in Shizuku mode: start Shizuku, confirm the first call records.
