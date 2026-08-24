# Shizuku support — design and plan

**Status:** designed 2026-08-24, not started. Decisions below were taken with the maintainer.

**Goal.** A phone that already runs Shizuku should be able to use CallVault without pairing anything.
Standalone stays the default and the identity of the app; Shizuku becomes a second way in, not a
replacement.

---

## What was verified first

**We already own the reference implementation.** Commit `0d7636b` ("remove all Shizuku code") deleted
ShizuCallRecorder's `ShizukuConnectionManager.kt` (394 lines) and `ShellService.kt` (468 lines) — a
working Shizuku integration *for this app*, by the people we forked from. `git show 0d7636b^:<path>`
brings any of it back. No guessing from upstream docs required.

**Every Shizuku variant is the same target.** thedjchi's fork and symbuzzer's fork both ship
`applicationId = "moe.shizuku.privileged.api"` and the same `moe.shizuku.manager.permission.API_V23`.
One integration covers stock Shizuku, both forks, and Sui (the root variant). Nothing variant-specific
is needed, and nothing variant-specific should be written.

**The seam already exists.** Our daemon's binder arrives at `RecorderConnection.onBinderReceived()`.
Shizuku's `ServiceConnection.onServiceConnected(name, binder)` hands over a binder of the same
interface. Both paths meet at that one function, and everything downstream — the sessions, the three
capture paths, the AIDL — does not know or care which produced it.

**The privilege level is identical.** A Shizuku user service runs as **shell, uid 2000** (or root under
Sui), which is exactly what our `app_process` daemon runs as today. `CAPTURE_AUDIO_OUTPUT`, the
`VOICE_CALL` source and the dynamic audio policy all behave the same. This is the reason the change is
tractable at all.

**Upstream's own service has grown past recording.** Their current `IShellService.aidl` is
`setLogCallback`, `startRecording`, `stopRecording`, `grantAppOpByPackage`, `grantAppOpByUid`,
`grantRole`, `destroy` — recording *plus* the privileged grants the app needs (appops, the DIALER
role). That answers the scope question below.

---

## Decisions

| # | Decision | Why |
|---|---|---|
| 1 | **Shizuku hosts our recorder as a user service** (`Shizuku.bindUserService`), not `Shizuku.newProcess` running our launch command | The supported public API, and Shizuku owns the lifecycle: `daemon(true)` survives app death and Shizuku restarts it. `newProcess` is restricted/internal and gives Shizuku no idea it is managing our process. |
| 2 | **Scope = the recorder plus the privileged grants**, matching upstream's AIDL | The maintainer asked for ShizuCallRecorder's shape, and their service does exactly this. Notably it does *not* include an app updater — they have none. |
| 3 | **Onboarding detects, then offers** | If Shizuku is installed and running, offer it and skip pairing entirely. If it is not, go straight to the standalone wizard and never mention Shizuku — nobody should be asked to choose between two things they have not heard of. The buttons are **"Use Shizuku"** and **"Use CallVault"** (not "set up my own"). |

**Consequence of #2 that has no upstream answer: our in-app updater.** It installs through
`AdbShell.openExec` + `pm install`, which a Shizuku-only user does not have. It must not fail silently.
For the first release it **degrades honestly**: the updater notices Shizuku mode, downloads the APK as
usual and hands it to the system installer (an ordinary sideload tap), saying so. Routing `pm install`
through the user service is a later, small addition — deliberately out of the first cut so the first
cut stays about recording.

---

## The shape

Two backends, one seam, no third concept:

```
                      ┌─ AdbBackend ──────► ADB shell ─► app_process RecorderServer ─┐
PrivilegedMode ──────►│                                                              ├─► RecorderConnection.onBinderReceived()
  (a preference)      └─ ShizukuBackend ──► Shizuku.bindUserService ─────────────────┘         │
                                                                                                ▼
                                                                        every capture path, unchanged
```

- `RecorderServiceImpl : IRecorderService.Stub()` — today's anonymous `createStub()` lifted into a
  named class. `RecorderServer.main()` keeps using it; the Shizuku service wraps it. **One
  implementation of the recorder, forever.** Two would drift, and the drift would be silent.
- `RecorderUserService` — the class Shizuku instantiates. Needs a `()` constructor and, since API v13,
  may take `(Context)`; both must be `@Keep`, and R8 must keep the class.
- `PrivilegedMode { STANDALONE, SHIZUKU }` in `AppPreferences`, defaulting to `STANDALONE`.

### The transaction-code detail that will bite if it is missed

Shizuku destroys a user service by calling **transaction `16777114`** directly on the binder
(confirmed in `RikkaApps/Shizuku-API`'s demo AIDL, and upstream pins it the same way).

Our `IRecorderService.aidl` uses **implicit** transaction IDs — `startRecording` is 1 through
`speakerTurns` at 14 — and `destroy()` currently sits at 4. Do **not** renumber the AIDL to add
16777114: every implicit ID would shift, and a warm daemon from the previous version would mis-dispatch
every call it received. Instead `RecorderServiceImpl` **overrides `onTransact`** and routes code
16777114 to the same `destroy()`. Zero wire change, and the ADB path is untouched.

---

## Phases

Each phase ends somewhere shippable, and nothing before phase 4 changes behaviour for an existing user.

**Phase 1 — the seam, no Shizuku. DONE 2026-08-24.** `RecorderServiceImpl` now holds the recorder and
all its state; `RecorderServer` is only the `app_process` entrypoint. `onTransact` routes 16777114 to
`destroy()`, so the AIDL keeps its positional IDs — guarded by `RecorderTransactionCodesTest`, which
fails if anyone reorders a method. `PrivilegedMode` added, defaulting to `STANDALONE`, and not yet read
by anything. 737 unit tests green, `lintVitalRelease` clean, release APK builds.

Verified **on the emulator** (Pixel 6, Android 16, arm64, google_apis) by
`RecorderDaemonRoundTripTest`, which launches the daemon as shell and drives it over a real binder:
daemon runs at **uid 2000**, `Binder delivery finished ok=true`, `isRecording`/`startRecording`/
`stopRecording` all dispatch through the new `onTransact`, capture starts on the **direct AudioRecord**
path, and the file comes back non-empty. That covers the whole of what the refactor touched except the
audio itself.

**Still owed: a real two-sided carrier call on a phone.** The emulator has no downlink, so it cannot
say whether both sides of a call still arrive — the failure mode that is invisible in logs, file size
and waveform.

**Phase 2 — the Shizuku backend.** Add `dev.rikka.shizuku:api` + `:provider`, the manifest permission
and `ShizukuProvider`, `RecorderUserService`, and a `ShizukuBackend` that binds and feeds
`RecorderConnection`. Reachable only from a debug affordance. *Acceptance:* `RecorderDaemonRoundTripTest`
passing again with its launch line swapped for `Shizuku.bindUserService` — the test was written so that
is the only difference between the two backends.

**Phase 3 — the grants.** `grantAppOp` / `grantRole` equivalents on the user service, so the
permissions the standalone path grants over ADB have a Shizuku route (this is what upstream's service
grew to do, and it is what the dialer-role work will need).

**Phase 4 — the UI.** Onboarding detect-and-offer; a status row on Home/Settings showing which mode is
active and whether Shizuku is running; the mode switch, which must safely tear down one backend before
starting the other. Strings + the ten locales.

**Phase 5 — the honest edges.** Updater degradation message; what the keep-alive service and the
wireless-debugging plumbing should do in Shizuku mode (mostly: nothing, and say so); the missed-call
reporting must not blame the user for a Shizuku that was not running.

---

## Open risks, stated before they are discovered

- **The screen-off adbd kill probably is not fixed by this.** Our own notes say Shizuku hits the same
  wall. Do not sell Shizuku mode as the cure until it is measured; it is a pairing-avoidance feature
  first.
- **The keep-alive FGS and the wireless-debugging plumbing are meaningless in Shizuku mode.** They must
  become inert rather than firing and failing.
- **VoIP arming may get *better*.** Arming is fixed at track creation and a missed call is
  unrecoverable; a Shizuku `daemon(true)` service that Shizuku restarts may be present more often than
  ours is. Worth measuring, not assuming.
- **All three capture paths must be exercised**, not just the direct one — the handoff path is what the
  reference phone actually uses for carrier calls, and VoIP is a third that is routinely forgotten.
- ~~**R8.**~~ Checked during phase 1: `isMinifyEnabled = false` for release, so nothing is shrunk or
  obfuscated and a reflectively-instantiated user service is in no danger. `@Keep` is on the class
  anyway, for the day that changes.

## Testing on the emulator

**More is testable there than first assumed.** The initial write-up of this plan said the emulator could
only cover binding and lifecycle. In practice it also covers binder delivery, transaction dispatch, the
direct AudioRecord capture path and file output — see phase 1 above. Two failures worth remembering, both
found by running it rather than reasoning about it:

- `UiAutomation.executeShellCommand` is **not a shell**. Redirection, `&` and a trailing `sleep` are
  silently dropped, so `RecorderServerLauncher`'s literal command reaches nothing — no daemon, no log
  line, no error. `setsid env CLASSPATH=… app_process …` is a pure exec chain and works.
- The returned pipe must be **held open** for the life of the daemon. Reading it to EOF blocks forever on
  a process running a Looper, and closing it early kills the child before `app_process` finishes loading
  — the same race `LAUNCH_KEEPALIVE_SEC` exists for on the ADB path.

Shizuku runs on an emulator: install the APK, then `adb shell sh /storage/emulated/0/Android/data/moe.shizuku.privileged.api/start.sh`, which is the same route a real device uses over wireless debugging. That covers binding, permission, the user service lifecycle and `destroy`. What the emulator **cannot** tell us is whether the audio is right — there is no carrier call and no real downlink — so every phase that touches capture still ends on a real phone, with a two-sided call listened to by ear.
