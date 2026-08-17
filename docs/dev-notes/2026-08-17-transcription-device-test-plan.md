# Transcription — everything that needs the phone, or needs you

**Purpose:** one place for every check that cannot be answered from the desk, so the phone is borrowed
**once**, at the end, rather than a dozen times. Nothing here blocks further coding — it accumulates
until we install a build on the OP12 and work through it.

**Status:** Plan 1 verified on device. Plans 2 / 2A / 3 have items waiting here.

> **Why batched:** the OP12 is a daily driver running a real release build that records real calls.
> Every session on it costs recording time and risks the setup. See
> `instrumented-tests-on-the-daily-driver` and `ask-before-assuming-phone-connected`.

---

## A. Decisions — ✅ ALL ANSWERED 2026-08-17

- [x] **A1. Language: auto-detect, with manual override.** Shipped as built — auto-detect is the
  default and the Language dropdown pins Hebrew (or five others) when wanted. No change was needed.

- [x] **A2. Default tier: Best (574 MB).** Changed in `1a759af`. Fast produces Hebrew *gist*, Best
  produces clean Hebrew, and a transcript you cannot trust is not worth the battery it cost. Cost
  accepted: ~2.2× real time, so a 30-minute call takes about 65 minutes.

- [x] **A3. Silent, but with a progress pill beside the Home title.** Same slot and shape as the
  existing `SupportPill` (`HomeScreen.kt:212`), visible **only while running**, showing position in
  the backlog, tapping through to a sheet with the current call and a Cancel button. → Plan 3, Task 6.

- [x] **A4. Calls per run is configurable** — 5 / 10 / 25 / 50 / No limit, default 25. Shipped in
  `1a759af`.

- [x] **A5. Detect the channel mapping automatically from ringback**, falling back to neutral
  "Speaker A / B" when the carrier gives nothing to go on. No prompt, no scripted test call.
  → Plan 2A, Task 4.

  **VoIP needs no calibration at all**: `VoipCaptureSession.kt:40` interleaves the two streams
  itself — *"LEFT near, RIGHT far"* — so for app calls the mapping is known by construction. Only
  carrier calls are ambiguous, because Android documents `VOICE_CALL` as "uplink + downlink" without
  ever specifying the channel order, leaving it an OEM decision.

---

## B. On-device tests

### How to install

`adb install -r` **preserves** the `WRITE_SECURE_SETTINGS` grant (the installer is shell-privileged),
so installing over your release build does **not** break recording — unlike the Obtainium path. See
`reinstall-drops-write-secure-settings`.

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && export PATH="$JAVA_HOME/bin:$PATH"
export ANDROID_SERIAL=6011b07e
./gradlew assembleDebug         # or assembleRelease for a true shipping check
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

> **Use a release build for anything about speed.** Debug builds compile whisper at `-O0` unless the
> `-O3` line in `app/src/main/cpp/CMakeLists.txt` is present — that line is what took a 47 s clip from
> 10m26s to 49 s. A timing measured on a build missing it is meaningless.

### B1. Model download — Plan 2, Task 2

- [ ] Settings → Transcription → **Download model** over Wi-Fi. Completes and shows "Downloaded and ready".
- [ ] **Resume:** start the 574 MB Best download, turn Wi-Fi off part-way, turn it back on. It must
      *continue*, not restart. Check the file size climbs from where it stopped rather than from zero.
- [ ] **Mobile data is refused:** on mobile data only, the download does not start (unmetered constraint).
- [ ] **Delete model** frees the space and the row flips back to "Download model".
- [ ] **Corrupt file is rejected:** truncate the downloaded `.bin` on device, then trigger a
      transcription. It must fail cleanly, not crash the app — a bad ggml file crashes in native code,
      which is exactly what the digest gate exists to prevent.

### B2. Manual transcription end-to-end — Plans 2 + 3

- [ ] Transcribe one real Hebrew call. Output is **Hebrew script**, not Latin.
- [ ] Timings look right — the last segment lands near the call's real duration, not at 1/10th of it
      (that would mean the centisecond→millisecond conversion regressed).
- [ ] A 30-minute call takes roughly 30 minutes at Fast tier. Materially slower means the `-O3` line
      is missing from the build.
- [ ] **Quality judgement (yours):** is Fast tier good enough to be useful, or is Best needed? This
      settles A2.

### B3. Automatic mode — Plan 2, Tasks 5 + 6

- [ ] Set Automatic with a run time a few minutes ahead, put the phone on charge. The run fires.
- [ ] With **Only while charging** on and the phone unplugged, it does **not** run.
- [ ] Backlog drains **oldest first**.
- [ ] **Interrupt it:** force-stop the app mid-backlog. On the next run it resumes at the next
      recording rather than re-transcribing what was already done. This is the checkpoint, and it is
      the difference between an hour wasted and an hour saved.
- [ ] Switching back to **Manual** cancels the scheduled work (nothing runs at the chosen time next day).

### B4. Settings UI — Plan 2, Task 6

- [ ] The mode dropdown reads **Manually / Automatically**, and Hour / Minute / Only-while-charging
      appear **only** under Automatically.
- [ ] Model and Language remain visible in both modes.
- [ ] Check one non-English locale renders without clipping — the German and Russian strings are long.

### B5. Deletion and privacy — Plan 2, Task 1

The cascade is enforced by code rather than a foreign key, so it deserves a real check rather than
only the unit tests.

- [ ] Transcribe a call, delete the recording, confirm the transcript is gone and **searching for a
      word from it returns nothing**.
- [ ] Same via the **retention sweep** (the per-copy delete path, which is how expiring calls
      actually go). Set a short retention, let it expire, confirm no transcript survives.
- [ ] For a call synced to Drive: deleting **only the device copy** must *keep* the transcript, since
      the recording still exists.

### B6. Survival — Plan 2, Task 1

- [ ] Transcripts survive an app **update** (install a newer build over the top).
- [ ] Transcripts survive the recordings catalog being rebuilt. This is the whole reason they live in
      their own database.

### B7. Speaker channel mapping — Plan 2A

Now **automatic** (decision A5): the app detects the far-party channel from the ringback tone on an
outgoing call. So this is no longer a scripted test — it is a check that the automatic detection
reached the *right* conclusion on your carrier, and stayed quiet when it could not.

- [ ] Make one ordinary **outgoing** carrier call. Afterwards, check the detected mapping and confirm
      the transcript attributes your lines to you.
- [ ] Confirm the conclusion is **stable** across two or three more outgoing calls, not flip-flopping.
- [ ] **Incoming** calls: there is no ringback to learn from, so confirm they reuse the mapping
      already learned rather than guessing.
- [ ] If the carrier sends no in-band ringback, confirm the labels fall back to neutral
      "Speaker A / B" — **a wrong attribution is far worse than none**.
- [ ] VoIP (WhatsApp): mapping is known by construction, so confirm "You / Them" is right there
      *without* any calibration having happened.

### B8. Speaker track must not harm recording — Plan 2A

- [ ] A call recorded with the capture tap has the **same codec, channel count and bit rate** as one
      recorded before it. If the audio changed at all, the tap is wrong.
- [ ] A fallback/mono capture produces no speaker data and **no error**.
- [ ] A warm daemon from the previous version produces no speaker data and **no error** (the AIDL
      method it does not have must not break a recording).

### B9. Transcript UI — Plan 3

- [ ] The row button walks **Transcribe → spinner → open**.
- [ ] The modal opens near full height and Hebrew renders **right-to-left**, with timestamps and
      speaker columns not colliding with the text.
- [ ] Tapping a segment **seeks playback** to that moment.
- [ ] Searching a word you know was spoken finds the call and jumps to it.
- [ ] A call with no speech shows **"no speech detected"**, not a blank sheet.

---

## C. Only answerable by living with it

Not pass/fail — things to notice over a few days.

- [ ] **Heat and battery** after an overnight backlog. Sustained all-core work on 190–574 MB models is
      the heaviest thing this app has ever done.
- [ ] **Storage**: 574 MB of model plus transcripts, against whatever headroom you keep.
- [ ] **Is auto mode actually wanted?** Once the backlog is done it only handles new calls; you may
      find manual is enough.

---

## Recording results

Fill in as we go, so a later session does not re-run what already passed.

| test | date | device / OS | result |
|---|---|---|---|
| | | | |
