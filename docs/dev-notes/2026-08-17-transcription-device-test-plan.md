# Transcription — everything that needs the phone, or needs you

**Purpose:** one place for every check that cannot be answered from the desk, so the phone is borrowed
**once**, at the end, rather than a dozen times. Nothing here blocks further coding — it accumulates
until we install a build on the OP12 and work through it.

**Status:** Plan 1 verified on device. Plans 2 / 2A / 3 have items waiting here.

> **Why batched:** the OP12 is a daily driver running a real release build that records real calls.
> Every session on it costs recording time and risks the setup. See
> `instrumented-tests-on-the-daily-driver` and `ask-before-assuming-phone-connected`.

---

## A. Decisions I need from you

These change what gets built or shipped. None are urgent, but they are cheaper to answer before the
device session than during it.

- [ ] **A1. Default language: auto-detect, or Hebrew?**
  Currently **auto-detect**. Naming the language outright is more reliable, and mis-detection fails
  *silently* — it returns fluent text in the wrong language rather than an error. Most of your calls
  are Hebrew, so defaulting to Hebrew would be more accurate for you; auto-detect is the better
  default for other users. **My recommendation: default to Hebrew.**

- [ ] **A2. Default model tier: Fast (190 MB) or Best (574 MB)?**
  Currently **Fast**. Measured: Fast ≈ real time and produces Hebrew *gist*; Best ≈ 2.2× real time and
  produces clean Hebrew. On a 30-minute call that is ~30 min versus ~65 min. Only you can say whether
  gist-quality is worth having.

- [ ] **A3. Should a long automatic run be visible?**
  Right now it is **silent** — no notification. An overnight backlog could be hours of CPU with
  nothing on screen explaining why the phone is warm. Options: leave silent, add a quiet ongoing
  notification, or only notify when it finishes.

- [ ] **A4. Batch size per scheduled run — currently 25 recordings.**
  At Fast tier that could be several hours if the calls are long. Cap it lower, cap by *total
  duration* instead of count, or leave it?

- [ ] **A5. Speaker labels: "You / Them" or "Speaker A / B"?**
  Depends on B7 below. If the channel mapping proves stable on your phone, "You / Them" is far more
  useful. If it varies, neutral labels are the honest choice — a transcript that confidently
  attributes your words to the other person is worse than one that does not guess.

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

### B7. Speaker channel mapping — Plan 2A ⚠️ *blocks A5, and must be done before 2A ships*

Empirical and device-specific: nothing in the code can determine which stereo channel is you.

- [ ] Place a call where **the other party speaks first and alone** for several seconds while you stay
      silent. Note which channel is active.
- [ ] Repeat with the roles reversed to confirm.
- [ ] Repeat on a **VoIP** call (WhatsApp), which uses a different capture path.
- [ ] Record the mapping *with the Android version it was observed on*.

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
