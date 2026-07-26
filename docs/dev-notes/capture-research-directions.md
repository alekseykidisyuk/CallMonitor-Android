# Capture & Persistence — Research Directions

**Status:** forward-looking investigation tracks. **Nothing here is proven or built.** These are the "what if" paths worth probing, each with a code-grounded read of *what it would let us replace* and the *open risks that gate it*.

Companion to `spike-audio-handoff.md` (which proved **Option B** — the app can hold a handed-off `IAudioRecord`+cblk and keep capturing after the daemon dies). That proof unlocks the tracks below. Read §7 of that doc first for the CREATE-vs-SUSTAIN framing this builds on.

**Devices:** primary OnePlus 12 / A16 / SDK 36 (`6011b07e`); secondary OnePlus 9 Pro / A14.

---

## Track A — Pre-created idle capture track (persistence via a permanently-held handoff)

**Status (2026-07-26): RESEARCHED, ON HOLD. One experiment away from a decision.** The go/no-go
question was settled empirically on-device; the blocker then moved somewhere nobody expected. Read
this before re-opening — most of the original open risks are now closed, and the two that matter are
not the ones this section originally worried about.

### The idea

Option B proved the app can *hold* a live capture track after the daemon dies. Track A asks: what if
the app holds the track **from before the call even starts** — created once by a briefly-alive daemon,
handed off, then kept indefinitely? If an idle `VOICE_CALL` track simply begins yielding audio when a
call becomes active, **no daemon is needed at call start at all**, and the daemon collapses from a
per-call requirement to a once-per-boot "track factory".

### What a working Track A would replace

| Today's machinery | Track A effect |
|---|---|
| `ensureServerRunning` at ring (`RecordingForegroundService`) and at record start (`AudioRecordingEngine`) | gone at call time — the app already holds the track |
| `DaemonKeepAliveService` (persistent FGS + 60 s watchdog) | collapses to "create once per boot" |
| Cold start (`ensureServerRunning` budgets 24 s; `AdbShell.ensureConnected` can still hang ~75 s) | gone except the single post-boot creation |
| WD enable/disable churn — the primary self-inflicted daemon killer | minimised to once per boot |
| Loopback off-Wi-Fi opt-in and its `0.0.0.0` listener | **reduced, NOT retired** — see "the payoff is smaller than it first looked" |

### VERIFIED — creation with no call active WORKS

Probed on the OnePlus 12 / A16 with a shell-uid `AudioRecord(VOICE_CALL)` and no call in progress:
creation **succeeds**, routes to `AUDIO_DEVICE_IN_TELEPHONY_RX`, is reported `silenced:false`, and
survived 240 s / ~1875 reads untouched. Input availability is a static `attachedDevices` property of
the device's `audio_policy_configuration.xml`, not something gated on telephony state.

So the question this section was originally gated on — "can you even pre-create it?" — is answered YES.

### VERIFIED — but the pre-created stream is SILENT for the whole call

The blocker is the vendor HAL, not the framework. `voice_check_and_set_incall_rec_usecase()` runs
**once per `start_input_stream()`**. A stream started before the call is pinned to
`USECASE_AUDIO_RECORD_AFE_PROXY` and stays there — so it keeps delivering silence for the entire call,
while looking perfectly healthy from every angle the app can see.

**Candidate fix, UNTESTED:** `stop()` then `start()` on the existing track at call-connect, forcing
RecordThread standby → HAL restart → use-case re-evaluation. This preserves the whole point of Track A
because **permission is checked at creation, not at start** — so the app can do it with no daemon and
no ADB.

### VERIFIED — risks that are now CLOSED

- **No idle timeout, no reaper.** `RecordThread` standbys purely on `mActiveTracks.size() == 0`; there
  is no aging mechanism for record tracks anywhere in AudioFlinger. A held track is not timed out.
- **Entering a call does not invalidate inputs.** `AudioPolicyManager::setPhoneState` touches outputs
  only — no `closeInput`, no `updateInputRouting`.
- **Shell UID is immune to proc-state silencing.** `isServiceUid(uid) → uid < AID_APP_START` makes
  `UidPolicy::getUidState()` return `PROCESS_STATE_TOP` permanently for uid 2000. This is why the
  ~60 s-after-screen-lock silencing that several open-source projects report does not apply to us.
  Confirmed on-device that `com.android.shell` also holds `CAPTURE_AUDIO_OUTPUT`, which clears the
  separate in-call gate.
- **It does not fight other apps.** `AUDIO_SOURCE_VOICE_CALL` is a *virtual source*
  (`AudioPolicyService::isVirtualSource`), which excludes it from the concurrent-capture arbitration
  entirely: it can neither silence nor be silenced by the user's voice recorder, Assistant, camera,
  WhatsApp or VoIP. Those all keep working, and so does the tap.

### VERIFIED — the risks that actually matter now

**1. Eviction.** The protection rule in `getInputForDevice()` is `active && !IDLE`. So:

| Hold strategy | Preemption | Idle cost |
|---|---|---|
| held **started** | protected (shell uid is permanently TOP) | permanent `PARTIAL_WAKE_LOCK "AudioIn"` — the device never deep-sleeps — plus a 24/7 mic indicator attributed to **"Shell"**, not CallVault |
| held **stopped** | vulnerable — any app opening the same profile closes it | free: HAL standby, no wakelock, no indicator |

On this device the trade-off is sharp: `dumpsys media.audio_policy` shows `Telephony Rx` sharing an
input profile with the built-in/back/headset/SCO mics at `maxOpenCount: 2`, and `VOICE_CALL` has
**source priority 0, the lowest there is** — so it is first to be closed when that profile fills.

Given the HAL finding forces a stop/start at call-connect anyway, **stopped** is probably the right
choice: it avoids the wakelock and the indicator, and the start you must do anyway re-arms the
use-case. The cost is accepting eviction, which requires detection.

**2. Blind failure.** A holder of a bare binder + cblk cannot self-heal: `restoreRecord_l` is
client-side logic inside the `AudioRecord` object, which lives in the daemon, and
`EVENT_NEW_IAUDIORECORD` is never dispatched on the record path. Mitigated in the shipped code by the
`CBLK_INVALID` + stalled-ring detectors in `nativeDrainToPipe`, which make the death visible rather
than silent — but they cannot recover it.

### The payoff is smaller than this section originally claimed

The original text said Track A could **retire** loopback. It cannot. The held track can be evicted, and
re-creating it needs the daemon and therefore ADB — off Wi-Fi, that means loopback. The honest claim is
that the ADB path becomes a **rarely-used recovery mechanism instead of a per-call dependency**. Still
a large win; not a deletion.

### No prior art — anywhere

Surveyed BCR, ShizuCallRecorder, cally, Ever Call Recorder, ACR/ACR Phone, Cube ACR, Boldbeast,
Skvalex, Truecaller, Automatic Call Recorder, Shizuku itself, and LSPosed/Magisk modules: **not one
pre-arms a capture track.** The field solves "no privileged work at call time" either by making the
*permission* permanent (root/Magisk/Xposed) or by keeping a *process* warm (Shizuku-class).
ShizuCallRecorder's `ACTION_STANDBY` at RINGING is the closest, and it starts a *process*.

The only real precedent in any category is **WebRTC/LiveKit `prewarmRecording()`**, which starts an
`AudioRecord` early and hands the already-running instance to the consumer — Option B's pattern, but
within one process and seconds ahead, never across a process boundary or across calls. Their caveat
transfers: prewarming freezes format and effects at arm time, before the call's route is known.

No XDA thread, GitHub issue or writeup describes anyone holding a capture track across sessions. The
absence is real, not a gap in searching. **You would be first — with nobody's scar tissue to learn
from.**

### The one experiment that decides it

Hold a pre-created `VOICE_CALL` track across a real call-connect, `stop()`/`start()` it at off-hook,
and confirm **real audio rather than silence**. Everything else is understood. Check with
`AudioRecordingConfiguration.isClientSilenced()` (API 30+, registered before `startRecording`) plus an
RMS floor — **never** by checking whether the ring advances, because a silenced track advances frames,
timestamps and the ring at full rate while delivering zeros.

## Track B — VoIP call capture (WhatsApp / Signal / Telegram / etc.)

### Grounding — do NOT re-derive the known dead ends

Prior feasibility work (`voip-recording-feasibility` memory, 2026-07-22) concluded, for **non-root** VoIP both-sides capture:
- **Bluetooth SCO/HFP software proxy** — doesn't get the downlink (that source is mic/uplink only); `startBluetoothSco` deprecated/no-op on A16. Dead.
- **AI acoustic reconstruction** (denoise earpiece bleed) — fatal physics (earpiece bleed is below the mic noise floor except on speakerphone); denoisers *remove* the faint remote voice. Dead.
- **Only honest cheap path evaluated then:** "records **speakerphone** VoIP acoustically + light denoise," marketed truthfully — after confirming mic-concurrency isn't blocked.

### The angle that was NOT evaluated: our privileged daemon + output/playback capture

We already ship (debug-only) two **downlink** capture sources via the shell-uid scrcpy path, which a normal app can't use the same way:
- **`OUTPUT`** → `REMOTE_SUBMIX` — the final mixed audio rendered to the speaker (`ScrcpyAudioSource.kt:104-117`).
- **`PLAYBACK`** → `AudioPlaybackCapture` API — other apps' audio output, *respecting `allowAudioPlaybackCapture` opt-out* (`ScrcpyAudioSource.kt:120-133`).

Both capture the **remote VoIP party's rendered voice (downlink)**. Combined with an uplink source we already support — `VOICE_COMMUNICATION` (`DirectAudioRecorderSession.kt:267`, tuned for VoIP with AEC/AGC) or plain MIC — that is **both directions, captured digitally (not acoustically)**. The prior analysis dismissed non-acoustic VoIP for *normal apps*; it never evaluated the **privileged daemon + REMOTE_SUBMIX** specifically.

### Investigation questions (in order)

1. **Uplink mic-concurrency first (20 min, cheapest kill-switch).** Start a `VOICE_COMMUNICATION` capture in the daemon while a WhatsApp/Signal call owns the mic — does Android silence/deny our track (A10+ concurrency rules)? If silenced, the uplink half is blocked and the rest is moot. *(This is the "test this FIRST" from the VoIP memory.)*
2. **Does daemon-side `OUTPUT`/REMOTE_SUBMIX capture the VoIP remote party during a live call?** VoIP audio routes with usage `VOICE_COMMUNICATION`, which is often *excluded* from submix/capture even for privileged capture. Empirically test whether the shell/scrcpy submix actually contains the far-end voice, or silence.
3. **Does `PLAYBACK`/`AudioPlaybackCapture` capture it, or is VoIP audio non-capturable?** `VOICE_COMMUNICATION`-usage playback is capture-exempt for normal MediaProjection clients and apps can set `allowAudioPlaybackCapture=false`. Does the daemon's privileged context bypass either? Test per-app (Signal is stricter than WhatsApp, likely).
4. **Separation quality** — do we get clean L/R directions (like `VOICE_CALL` stereo — proven independent in the handoff work) or only a downmixed blob? Downlink-only + mic gives two streams we control; submix gives one mixed stream (includes our own mic echo).

### If a working path exists

Downlink via `OUTPUT` or `PLAYBACK` + uplink via `VOICE_COMMUNICATION` (not silenced) → **VoIP both-sides becomes viable non-acoustically**, using capture sources already in the codebase, gated behind the same daemon we already run for cellular. This would be a genuinely new capability, not a rehash of the dead BT/AI paths.

### Caveats to keep honest

- **Per-app opt-outs and usage exemptions** may make it work for some apps and not others → must be surfaced honestly per app, never "records all VoIP."
- **Ties into Track A:** a held `OUTPUT`/`PLAYBACK` track could be pre-created and held the same way — a daemon-free VoIP capture once the track exists.
- Legal/consent framing for VoIP is stricter than cellular in many jurisdictions.

---

## Track C — Toward a native install (kill the Developer-Options / Wireless-Debugging / USB ceremony)

**The dream:** no developer options, no wireless-debugging pairing, no USB — the app is *install + grant permissions*, like any native app.

### The hard wall (state it first, so we don't chase ghosts)

On **stock, non-root** Android, privileged call-audio capture (`VOICE_CALL` / `CAPTURE_AUDIO_OUTPUT`) requires a **privileged process** (shell-uid or system). The **only non-root vector to spawn one is ADB.** There is **no app-grantable permission, no consent dialog, and no default-app role — including default dialer — that gives an app-uid process `VOICE_CALL` capture.** Proven twice in our own code:
- The Option B spike: the app process can't even `dlopen` the audio libs; the permission is uid-checked at `AudioFlinger::createRecord`, not appop-grantable to an app uid.
- The dialer work (`dialer-mode-status` memory): even as the **forced default dialer with `InCallService` bound**, recording still rode the ADB daemon — `DialerDefaultEnforcer.enforce()` must call `AdbShell.ensureConnected()`. Being the dialer gives call *control/state*, not call *audio*.

So **full both-sides cellular capture with zero ADB is not achievable on stock non-root.** Every credible non-root recorder (SCR, etc.) hits this exact wall. Don't promise otherwise.

### Vectors investigated — honest verdicts (don't re-derive)

| Vector | Gives an app-uid process call audio without ADB? | Verdict |
|---|---|---|
| Request `CAPTURE_AUDIO_OUTPUT` at runtime | No — `signature\|privileged`, not requestable | ❌ dead |
| Default dialer / `InCallService` role | No — control/state only; still needs the daemon (proven) | ❌ dead for audio |
| Device Owner / DPC | Can grant *runtime* perms silently but NOT signature/privileged; setup needs ADB or QR-factory-reset anyway | ❌ dead + more friction |
| Carrier privileges (cert on SIM) | Cert must be whitelisted on the carrier's SIM; no call-audio API in the surface | ❌ dead |
| Assistant / `VoiceInteractionService` role | Hotword/assist audio, not `VOICE_CALL` | ❌ dead |
| Accessibility service | No audio-capture capability at all | ❌ dead |
| One-time `pm grant`/`appops` to bless the app uid | Perm is uid-checked at AudioFlinger, not appop-grantable; app uid can't reach the libs | ❌ dead |
| `sharedUserId=android.uid.shell` | Signature-checked, deprecated | ❌ dead |
| **MediaProjection `AudioPlaybackCapture`** | **Yes for VoIP/media *downlink*** (app-uid, one consent) — but **CANNOT** capture telephony `VOICE_CALL` (exempt), and A14+ re-consents per session | 🟡 **partial — VoIP/media only** |
| **On-device wireless pairing (Android 11+)** | Removes the *computer* from setup (pairing code entered in-app), not ADB itself | 🟢 **friction reducer** |
| **Root (optional tier)** | Yes — truly install + grant-su, no ADB ceremony | 🟢 **viable for rooted users** |

### The creative reframe — a tiered capability model (this is the real out-of-the-box output)

Instead of one all-or-nothing path, ship **tiers**, so the app is *useful on first launch with zero ceremony* and power users opt up:

- **Tier 0 — true "install + permissions", ZERO developer options.**
  - **VoIP:** MediaProjection `AudioPlaybackCapture` (remote downlink) + MIC (local uplink) — app-uid, one consent, no ADB. (This is Track B done *app-side*, respecting per-app opt-outs.)
  - **Cellular:** become the default dialer (already-built machinery), force **speakerphone**, capture acoustically via MIC (`RECORD_AUDIO` only). Honest, limited (speakerphone only).
  - Net: the app **records *something* for a user who will never touch developer options** — the true native-install experience, at reduced capability. Strong default first-run.
- **Tier 1 — one-time on-device pairing, then autonomous ("feels native after onboarding").**
  - Current ADB daemon path, but: (a) **on-device wireless pairing** (no computer — pairing code in-app), (b) **Track A** held-track so ADB is touched *at most once per boot*, (c) app silently self-manages WD/loopback thereafter. After a ~2-min onboarding the user never sees developer options again → full-quality both-sides cellular that *feels* native day-to-day.
  - Irreducible friction: the **one-time** WRITE_SECURE_SETTINGS grant, and reinstall dropping it (`reinstall-drops-write-secure-settings` memory; in-place updates preserve it).
- **Tier 2 — root (optional).** Truly install + grant-su, no ceremony, best persistence. Rooted users only; auto-detect root and offer it.

**Product framing:** Tier 0 is the zero-friction default that works immediately (VoIP + speakerphone). An in-app "unlock full call recording" flow upgrades to Tier 1 for users who want both-sides cellular quality. The daemon becomes *invisible infrastructure* the average user opts into once — not a barrier at the door.

### What to probe / decide

1. **Tier 0 VoIP viability** = Track B's mic-concurrency + `allowAudioPlaybackCapture` tests, but done **app-side via MediaProjection** (no daemon) — cheapest, unlocks a real zero-ADB feature.
2. **Tier 0 cellular** = confirm default-dialer + force-speakerphone + MIC gives an acceptable acoustic recording (honest quality bar).
3. **Tier 1 onboarding** = polish on-device wireless pairing so no PC is ever required; measure real onboarding drop-off.
4. **A14+ MediaProjection re-consent** — check whether a persistent foreground-service capture token survives, or if each VoIP call needs a fresh consent tap (UX cost of Tier 0 VoIP).

---

## Creative ideas / cross-cutting

1. **Universal capture abstraction — daemon as a "track factory."** If Track A holds, generalize the handoff: the app holds handed-off tracks for *any* source (`VOICE_CALL`, `OUTPUT`, `PLAYBACK`), and the daemon's entire runtime role shrinks to "create N tracks once per boot and hand them off." Everything else (encode, mux, lifecycle, output file) is already app-side (`AudioRecordingEngine` owns the SAF fd + codec today).

2. **Reframe bootstrap from per-call to per-boot.** The whole ADB/WD/loopback dependency exists to have a privileged process *ready at call time*. Track A moves that to *once at boot* — `BootReceiver` does a single create+handoff, and there is no ADB/WD/loopback in the hot path ever again. This is the strategic prize: it doesn't just improve persistence, it removes the per-call attack/failure surface.

3. **Loopback retirement.** If Track A works, `OFFLINE_RECORDING_ENABLED` + the `0.0.0.0` tcpip listener can be deleted, closing the one real security surface in the product (see `loopback-tcpip-offwifi` memory). Document this as an explicit payoff of Track A, not an afterthought.

4. **Dual-hold redundancy.** Hold both a `VOICE_CALL` track (cellular) and an `OUTPUT`/`PLAYBACK` track (VoIP) simultaneously, so *any* call type is covered without knowing in advance which will ring. Cost = two idle tracks' battery (measure in Track A's probe).

5. **Proactive re-create on state change.** Rather than a periodic watchdog, listen for audio-route / call-state / screen transitions and refresh a stale held track *before* the next call, so a track that silently went invalid during idle is renewed at the cheapest moment (device already awake, possibly on WiFi).

6. **Graceful degradation ladder.** If a held track is invalid at call start and we're off-WiFi with no loopback, fall back to: (a) fast daemon relaunch if any transport exists → (b) Option B SUSTAIN-only (launch at call start, survive mid-call death) → (c) honest "not recorded" state. A clear ladder keeps the UX truthful when the ideal path isn't available.

---

## Relationship to what's shipped / proven

- **Option B (proven):** SUSTAIN fix — every *started* recording completes through daemon death. Keeps WD/loopback/keep-alive.
- **Track A (unproven):** CREATE fix — collapses bootstrap to once-per-boot, could retire loopback and off-WiFi opt-in. Gated on idle-track survival.
- **Track B (unproven):** new capability — non-acoustic VoIP via privileged output/playback capture. Gated on mic-concurrency + capture-exemption tests.
- **Track C (reframe):** tiered capability model toward a native install. Hard wall confirmed — zero-ADB full cellular capture is impossible on stock non-root. But Tier 0 (MediaProjection VoIP + speakerphone acoustic, zero dev-options) + Tier 1 (one-time on-device pairing, then autonomous via Track A) + Tier 2 (root) make the daemon *invisible infrastructure* rather than a door barrier.

Update this doc as each probe runs. Cross-referenced from `spike-audio-handoff.md` §7 and memory `spike-audio-handoff-status`.
