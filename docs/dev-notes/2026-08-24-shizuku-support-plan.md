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
- `RecorderUserService` — the class Shizuku instantiates, a subclass of the impl adding nothing but the
  two constructors Shizuku will call. Both `@Keep`. The `(Context)` one is strongly preferred; see the
  classpath warning under phase 2.
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

**Phase 2 — the Shizuku backend. DONE 2026-08-24, with one open question.** `dev.rikka.shizuku:api` +
`:provider` (13.1.5, the versions this app shipped with before `0d7636b`), the `API_V23` permission, a
`<queries>` entry for `moe.shizuku.privileged.api`, `rikka.shizuku.ShizukuProvider`,
`RecorderUserService` and `ShizukuBackend`.

Proven on the emulator by `RecorderShizukuRoundTripTest` against a real Shizuku 13.6.0 server: Shizuku
instantiates our service (`Started by Shizuku (with Context) pid=… uid=2000`), the binder reaches
`RecorderConnection`, `startRecording`/`stopRecording` dispatch, and a non-empty file comes out. **Two
hosts, one recorder, same seam** — exactly the claim.

🔴 **Open: the Shizuku-hosted process cannot open the direct AudioRecord, and falls back to scrcpy.**

```
AudioFlinger could not create record track, status: -1
Direct capture unavailable, falling back to scrcpy: AudioRecord would not initialise for source 7
```

Our own daemon opens the same source on the same emulator moments earlier and gets
`Recording via DIRECT AudioRecord`. This matters: direct capture is what removed the ~1–2 s front clip
in v1.4.0, so a Shizuku user would silently get the older, worse path.

**Six hypotheses tested and eliminated on 2026-08-24**, each by measurement rather than reasoning:

| # | Hypothesis | How it was tested | Result |
|---|---|---|---|
| 1 | Different uid | Both hosts log their own identity | **Identical** — `uid=2000` |
| 2 | Shizuku's `Context` binds the process to our package | Deleted the `(Context)` constructor and re-ran | **No change** — still falls back |
| 3 | Different `opPackageName` presented to AudioFlinger | `AudioAttribution.opPackageName()` in both | **Identical** — `<none>` in both |
| 4 | Missing audio group (gid 1005) | `/proc/self/status` groups in both | **Identical** lists, and *neither* has gid 1005 |
| 5 | Different SELinux domain | `/proc/self/attr/current` in both | **Identical** — `u:r:shell:s0` |
| 6 | A leftover scrcpy/daemon holding the single emulator mic | Killed everything, then ran Shizuku **first** | **Still fails** — the failure follows the host, not the order |
| 7 | Process name `com.baba.callvault:recorder` confuses the audio stack | Launched our own daemon with `--nice-name=com.baba.callvault:recorder` | **Still succeeds** — the name is not it |

So two processes that are identical in uid, groups, SELinux context, presented package and even process
name behave differently. Whatever remains is in **how the process was created** — Shizuku spawns its
user service itself, and something about that spawn is not visible in `/proc` identity.

**The next thing to try is a phone, not more emulator work.** The emulator's audio HAL is unusual and
this may not reproduce on real hardware at all. Upstream ShizuCallRecorder only ever used scrcpy under
Shizuku, which is *weak* evidence the limitation is real — they may simply never have tried.

**Not a blocker for the feature.** Shizuku mode records correctly today via the scrcpy fallback, which
is the path the app shipped with until v1.4.0.

⚠️ **A Context from Shizuku is not optional.** Without it the fallback reads `java.class.path`, and in a
Shizuku-hosted process that names **Shizuku's own APK**, not ours. The scrcpy extractor then failed with
"APK is missing scrcpy asset entry" against a package that indeed has no such asset — a failure that
reads as ours and is not. `ShizukuClasspath.apkFrom` now requires the entry to belong to our package,
and returns empty rather than something confidently wrong.

**Phase 3 — the grants. DONE 2026-08-24.** `grantAppOp`, `grantRole` and `hostUid` appended to the AIDL
(appended, never inserted — the ids are positional and `RecorderTransactionCodesTest` pins all 17), with
`PrivilegedGrants` running them inside `RecorderServiceImpl`. They therefore work on **both** hosts, so
the ADB path gains the same capability rather than only Shizuku.

### Does any of this need root? No — and that was worth checking

The maintainer asked, because ShizuCallRecorder ships root and non-root features side by side and some
might be root-only. Checked on 2026-08-24 by reading their code and then running the commands as shell
on the emulator:

- Upstream is **explicitly non-root** — "the first non-root FOSS call recorder app for Android 11+" —
  and their `ShellCommandExecutor` runs plain `appops` / `cmd role` commands with no `su` anywhere.
- `appops set --user 0 <pkg> <op> allow` — **works as shell.** Verified: `grantAppOp(…, RECORD_AUDIO)
  → true` through our own binder.
- `cmd role add-role-holder --user 0 <role> <pkg>` — **works as shell.** It refuses CallVault the
  dialer role, but for **qualification, not privilege**: `not qualified for android.app.role.DIALER due
  to missing RequiredComponent … action='android.intent.action.DIAL'`. We declare no dialer component.
  **Root would not change that, and neither would Sui.** When the dialer-mode branch lands and declares
  the component, this same call is expected to succeed as shell.
- The contrast case, for calibration: `cat /data/system/packages.xml` is refused to shell. That is what
  a genuine root requirement looks like, and nothing we need looks like it.

So Shizuku mode is shell-equivalent to the ADB mode, and neither needs root. `hostUid()` exists anyway,
because a Shizuku started rooted (Sui) hands us uid 0, and "Shizuku is running" says nothing about
which of the two you got.

### 🚩 `grantAppOpByUid` is deliberately NOT offered

Upstream exposes it and documents it as taking priority over the package-level value. That was true
once. On Android 14+ it **exits 0 and does nothing** whenever the op backs a runtime permission
(`RECORD_AUDIO`, `READ_PHONE_STATE`, …):

```
W AppOpService: Ignored setUidMode call for runtime permission app op:
uid = 10275, code = RECORD_AUDIO, mode = allow, callingUid = 2000
```

An API whose success means nothing is worse than no API, so it is not offered. For the same reason
every grant here **reads the result back** from `appops get` / `cmd role get-role-holders` instead of
trusting an exit code — and `GrantOutput` excludes the `Uid mode:` line, which appears *first* and
usually still says `ignore`, so a naive parse reports the opposite of the truth.

**Phase 4 — the UI. DONE 2026-08-24.**

`RecorderBackend.ensureRunning()` is the single decision point. Six places started the recorder by
calling `RecorderServerLauncher` directly — app start, boot, an incoming call, the offline path,
post-pairing, post-update — and each would otherwise have needed its own Shizuku branch. It keeps the
launcher's exact shape (blocking, returns whether a binder is now available) so none of the six had to
be restructured.

`switchTo()` stops the old backend **before** starting the new one, and `BackendChoice` holds the rule
— including that an unchanged mode tears nothing down, since Settings can re-save the same value and
killing a warm recorder for that would drop the next call.

Settings ▸ General ▸ **How CallVault gets permission** reports the truth rather than a toggle's wish:
not installed / not running / not permitted / ready, re-read on every recomposition because a Shizuku
server can stop at any moment and never survives a reboot.

Onboarding offers the fork **only when a Shizuku server is actually running**. Everyone else sees the
setup they always saw; CallVault must never read as an instruction to install a second app.

Verified on the emulator by driving the real UI, not by reading the code:

```
before:  "Shizuku is running on this phone"  [Use CallVault] [Use Shizuku]
         "Wireless debugging (ADB)"  "Not connected"
after:   "Allow CallVault in Shizuku to finish. Nothing records until you do."
         [Use CallVault] [Allow CallVault in Shizuku]
         (the Wireless debugging card is GONE)
prefs:   <string name="privileged_mode">shizuku</string>
```

Strings in all ten locales.

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
