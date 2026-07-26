# Capture & Persistence — Research Directions

**Status:** forward-looking investigation tracks. **Nothing here is proven or built.** These are the "what if" paths worth probing, each with a code-grounded read of *what it would let us replace* and the *open risks that gate it*.

Companion to `spike-audio-handoff.md` (which proved **Option B** — the app can hold a handed-off `IAudioRecord`+cblk and keep capturing after the daemon dies). That proof unlocks the tracks below. Read §7 of that doc first for the CREATE-vs-SUSTAIN framing this builds on.

**Devices:** primary OnePlus 12 / A16 / SDK 36 (`6011b07e`); secondary OnePlus 9 Pro / A14.

---

## Track A — Pre-created idle capture track (persistence via a permanently-held handoff)

### The idea

Option B proved the app can *hold* a live capture track after the daemon dies. Track A asks: what if the app holds the track **from before the call even starts** — created once by a briefly-alive daemon (post-boot / post-onboarding), handed off, then kept indefinitely? If an idle `VOICE_CALL` track simply begins yielding audio when a call becomes active, **no daemon is needed at call start at all.** The daemon collapses from a per-call requirement to a once-per-boot "track factory."

### Code investigation — what a working Track A would replace

Grounded in the current bootstrap + recording paths:

| Today's machinery | Why it exists | Track A effect |
|---|---|---|
| `RecorderServerLauncher.ensureServerRunning` at ring/standby (`RecordingForegroundService.kt:248`) and at record start (`AudioRecordingEngine.kt:340`) | launch/confirm the daemon so it can create the track when a call fires | **gone at call time** — the app already holds the track; it just starts draining its cblk |
| `DaemonKeepAliveService` — persistent FGS + 60s watchdog + immediate relaunch | its own docstring: keep the daemon *"WARM … so a call is captured instantly instead of paying a cold-start that outlasts a short call"* | **collapses** to "create the track once per boot, re-create only if the held track is lost" — no per-call warmth needed |
| Cold-start latency (first call after reboot/reap missed — see `post-reboot-daemon-coldstart-latency` memory) | daemon launches lazily, slower than a short call's first seconds | **gone**, except the single post-boot creation |
| **Loopback / classic-tcpip off-WiFi opt-in** (`AdbShell.connectLoopback`/`armLoopbackIfNeeded`, `OFFLINE_RECORDING_ENABLED`, `LOOPBACK_ADB_PORT`) | the only way to (re)launch the daemon at call time without WiFi, since WD is WiFi-only | **potentially RETIRED** — see below |
| WD-enable churn (`AdbShell.enableWirelessDebugging`) — memory's *primary* self-inflicted daemon killer | re-enabled transiently on every launch/relaunch | **minimized to once-per-boot** (one create), so the adbd-restart churn that kills daemons all but disappears |

### The headline payoff: off-WiFi **without** loopback

Off-WiFi recording today needs loopback *only because* the daemon must be (re)launched at call time and WD dies off-WiFi. But if the app is **already holding a live capture track** created earlier while on WiFi, then **no ADB is needed at call time at all** — the call records straight from the held cblk. A track created once on WiFi and held across the WiFi→cellular transition means **off-WiFi calls record with no loopback, no `0.0.0.0` open port.**

That would resolve the loopback security tradeoff outright (the `adb tcpip` listener binds all interfaces and can't be loopback-bound without root — see `loopback-tcpip-offwifi` memory). Track A is the one path that makes off-WiFi *free and safe* instead of a warned opt-in.

### What still needs the daemon (honest limit)

**CREATE, once per boot.** `service.adb.tcp.port` and any tcpip state clear on reboot, and a fresh capture track is needed after a reboot anyway. So the model is **"daemon + ADB once per boot"** (a single create+handoff at BootReceiver / first connectivity), not "daemon never." The bootstrap doesn't vanish — it de-escalates from per-call to per-boot.

### Open risks — must validate before betting on it

1. **Idle silencing over hours** — the appop/UID-state monitor may zero-fill (or the RecordThread may STANDBY) a track whose creator uid (shell) is gone and that has no active reader for a long time. This is the M3e silencing risk, but stretched from seconds to hours. **The decisive unknown.**
2. **Does `VOICE_CALL` gate on an active call at CREATE or only at data-time?** If `getInputForAttr` refuses to *create* a `VOICE_CALL` input when no call is active, you can't pre-create it — you'd have to create at call start (defeating the point), OR hold a different source and switch. Needs a direct probe.
3. **Multi-hour track validity** — does the `IAudioRecord`+cblk survive doze, audio-policy reconfig, and audio route changes (BT/speaker/wired) for hours, and resume cleanly when a call starts?
4. **Battery** — cost of an always-open capture track.
5. **Reboot re-arm** — needs one connectivity moment post-boot to re-create; a persisted "held" flag can't be trusted across reboot (detect real state by probing the held cblk).

### Cheap first probe (no productionization)

On the main device: daemon creates a `VOICE_CALL` `AudioRecord` with **no call active**, hands off to the app, app holds it idle **10–30 min through screen-off/doze**, then place a real call and check whether the held track produces real audio **without re-touching the daemon**. Green here = risks 1–3 mostly cleared; then measure battery (risk 4).

### Decision gate

If the probe shows a held `VOICE_CALL` track goes silent or invalid over idle → Track A is dead, fall back to Option B's SUSTAIN-only win (keep WD/loopback/keep-alive as today). If it survives → this is a *bigger* architectural win than Option B alone: it retires per-call bootstrap, cold-start, and the loopback security surface in one move.

---

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
