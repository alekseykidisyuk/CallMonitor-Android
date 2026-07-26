# Changelog

All notable changes to CallVault are documented here. The format is based on
[Keep a Changelog](https://keepachangelog.com/), and this project uses semantic-ish versioning.

## [1.4.7] — 2026-07-26

The headline: **CallVault can now record calls made inside apps** — WhatsApp, Signal, Telegram — both
sides of the conversation. Opt in under Settings → Experimental → VoIP calls.

### Added
- **VoIP call recording (opt-in, experimental).** Until now CallVault only recorded carrier phone
  calls; a call placed inside a messaging app could not be captured at all without root. It now can be,
  and it records **both sides** — the other person as well as you — as a normal recording that appears
  in the list and plays like any other.
  - **Off by default.** Enable under **Settings → Experimental → VoIP calls**. Turning it on asks you
    to confirm first: recording app calls is more tightly regulated than recording phone calls, and in
    many places every participant has to agree beforehand.
  - Verified working with **WhatsApp, Signal and Telegram**. It is genuinely experimental — an app can
    block recording, and that cannot be known until a call is under way. If only your side was
    captured, CallVault tells you so rather than leaving you to discover it later.
  - Recordings are named `…_voip-<App>[_<contact>]` and show the **calling app's icon** in the list.
  - Carrier Wi-Fi calling (VoWiFi/VoLTE) is **not** covered by this — it works differently and is out
    of reach by this route.
- **The "What's new" note now covers the last three releases**, each labelled with its version, instead
  of introducing one feature and leaving the rest unmentioned. It scrolls, and appears once per update.

### Fixed
- **VoIP recordings could be attributed to the wrong app.** A Telegram call was labelled as WhatsApp,
  and later as Google, because the app was identified by scanning notifications. The app is now taken
  from the audio stream being recorded, which cannot belong to anyone else.
- **Contact names were missing for some apps.** Telegram publishes the contact in a different field
  from WhatsApp, so its calls came out unnamed.
- **Calls with no contact name showed no app icon** — a Signal call saved as `…_voip_Signal` was read
  as a call with someone *named* "Signal", losing the app badge.

### Note
- Contact names for app calls come from the calling app's own call notification, which is the only
  place Android exposes them. If notifications are turned off for that app, its recordings are saved
  correctly but without a name — Settings now says so.
- The VoIP feature's text is currently English-only.

## [1.4.6] — 2026-07-26

The headline: **recording is now resistant to the background helper being stopped mid-call** — opt in under Settings → Reliability.

### Added
- **Resilient recording (opt-in).** Until now, the background helper held the microphone and did the
  encoding for the whole call — so if Android stopped it part-way through, the recording stopped with
  it and you were left with a half-length file. With this on, the helper only *starts* the capture and
  then hands it to CallVault itself, which keeps it and does the encoding. The helper can then be
  killed at any point mid-call and the recording simply carries on to the end.
  - **Off by default**, and turning it off restores exactly the previous behaviour. Enable it under
    **Settings → Reliability**, or from the one-time note shown after updating.
  - It doesn't change how a recording *starts*, so it complements the "Charging only" USB fix from
    1.4.5 rather than replacing it.

### Fixed
- **A call could occasionally not record at all.** When starting a recording, CallVault read a USB
  setting over its ADB connection with no time limit. Normally that is instant, but if the connection
  had gone half-dead the read never returned, and the recording never started — leaving an empty file.
  It is now capped at 1.5 seconds and falls back to the last known value. This affected every
  recording, not just the new opt-in.
- **The "Voice performance" audio source now uses the fast direct path.** It was matched against a
  source name that doesn't exist, so it silently fell back to the slower scrcpy path.

### Note
- CallVault now ships a small native library and therefore requires a **64-bit ARM device**
  (`arm64-v8a`) — effectively every phone running Android 11 or newer.

## [1.4.5] — 2026-07-24

The headline: **recording no longer stops if you lock the screen during a call** — on phones where it did.

### Added
- **Keep recording when the screen locks.** On many phones (OnePlus, Xiaomi, Samsung…), locking the
  screen during a call restarts the USB connection, which was killing the recorder mid-call. CallVault
  can now set your phone's **Default USB Configuration to "Charging only"** from inside the app, which
  prevents it — no digging through system menus.
  - A new **Settings → Reliability** section lets you pick the USB mode (Charging only is recommended)
    and holds the off-Wi-Fi recording option too.
  - A **setup step** offers the one-tap fix during onboarding.
  - If USB is on a data mode, the **Home screen** and the **recorder notification** show a gentle
    "locking the screen may stop recording — tap to fix" prompt.
  - Note: with "Charging only", plugging into a PC defaults to charging — pick "File transfer" manually
    when you actually want to move files.

## [1.4.4] — 2026-07-24

### Fixed
- **Call audio quality restored at the same bitrate — the far party is clear again.** Recording captured
  the call in stereo (your side on one channel, the other person's on the other) and encoded it as
  stereo, which split the bitrate and starved each side — so the **other party** sounded noticeably
  worse at the default 24 kbps. Calls are mono content, so CallVault now downmixes to a single channel
  and gives the whole bitrate to the voice. Same setting, much better quality.
- **No false "recording paused" warning while recording actually works.** The post-update permission
  banner now only appears when a call genuinely couldn't be recorded (the recorder isn't running), not
  while it's warm and recording normally — and CallVault keeps quietly restoring the permission in the
  background.

## [1.4.3] — 2026-07-24

### Fixed
- **No more false "recording paused" warning after an update.** When an update dropped a permission
  but recording kept working (the recorder was still running), CallVault showed an alarming
  "paused after update" banner anyway. It now **silently restores the permission** over the
  connection that's already open — no action, no reinstall — and only shows a (reworded, honest)
  prompt when it genuinely can't, telling you it may still be working and how to keep it that way.

## [1.4.2] — 2026-07-24

### Added
- **Support development.** An optional "♥ Support" button next to the app title on Home, and a
  matching row in Settings → About, open the maintainer's Ko-fi page in your browser. Entirely
  optional — CallVault stays fully free and open source.

### Fixed
- **The "What's new: off-Wi-Fi recording" note no longer pops up after every update.** It's a
  one-time introduction now — shown once, then never again (a small "updated to …" banner still
  confirms an update landed).

## [1.4.1] — 2026-07-24

A focused fix for a problem some people hit after updating to 1.4.0.

### Fixed
- **Recording no longer breaks after updating without a reinstall.** When CallVault was updated in place
  (e.g. via Obtainium or a sideloaded APK), Android could quietly drop a permission the app needs to run
  the recorder — leaving recording dead until a full clean reinstall. CallVault now heals this itself:
  right after an update it reconnects over any still-open channel and restores the permission
  automatically. If it can't (nothing to reconnect through), the Home screen shows a clear
  **"Recording paused after update"** banner — tap it and turn Wireless debugging on once, and recording
  restores itself. **No reinstall needed.**

## [1.4.0] — 2026-07-23

The headline: **record calls even without Wi-Fi**, plus a much faster, more reliable recorder.

### Added
- **Offline recording (opt-in) — record with no Wi-Fi.** A new option (Settings → Debug → "Offline
  recording") lets CallVault capture calls even when you're not on a Wi-Fi network — for the important
  call you get on the road. It's **off by default** and shows a short security note when you turn it on,
  because it opens a local debugging port on your own device. You can also enable it straight from the
  "What's new" note after updating.
- **The recorder stays warm and comes back fast.** CallVault now keeps its privileged recorder ready and
  relaunches it within a few seconds when the system reclaims it — so a call after your phone has been
  idle is captured almost immediately instead of after a long "starting up" wait.

### Changed
- **New audio-capture engine.** Calls are recorded through a direct on-device audio path instead of the
  previous screen-mirroring helper. Capture begins from the first moment (no clipped beginnings), the
  daemon boots faster, and the app has fewer moving parts. (Falls back to the old path automatically if a
  device can't use the new one.)
- **One clear "ready to record" notification** instead of the occasional duplicates.

### Fixed
- **No more Wireless-Debugging notification flapping** on and off while idle.
- **Recovery after the system reclaims the recorder is now seconds, not up to a minute** — the old
  behaviour left "starting up" showing long after recording was actually ready.

## [1.3.1] — 2026-07-22

A reliability release for recordings saved to cloud folders (e.g. Google Drive), based on field reports.

### Fixed
- **Recordings to a Google Drive folder no longer fail or vanish.** Some storage providers (Google
  Drive, and other cloud/synced folders) reject the read-write mode the recorder needs, and report a
  file's size as 0 immediately after writing (their upload is asynchronous). This caused recordings to
  either fail to start (`Unsupported mode: rw`) or be **falsely detected as empty and deleted**.
  CallVault now records into on-device storage first and copies the finished file into such folders,
  and it trusts the actual captured size instead of the provider's delayed report.
- **Honest error messages.** A storage failure is no longer mislabeled as "ADB connection failed".

### Added
- **Cloud folders are blocked as the recording folder.** Picking Google Drive / OneDrive / Dropbox as
  the *device* recording folder is now refused with guidance to choose on-device storage — use the
  **Google Drive backup** option for cloud copies instead. Any existing cloud recording folder now
  shows a warning in Settings so it's no longer mistaken for local storage.

## [1.3.0] — 2026-07-21

The headline feature: **in-app updates**. CallVault can now tell you when a new version is out and
install it for you, without hunting for the APK on GitHub.

### Added
- **In-app updater (manual).** CallVault checks GitHub daily (and when you open the app) for a new
  release. When one is available you get a notification and an **Update** banner on the Home screen;
  tap it and CallVault downloads, verifies, and installs the update itself, then reopens on the new
  version and confirms with an "updated" banner + notification. No more manually downloading APKs.
  - The download is **resumable** — a slow or flaky connection resumes where it left off instead of
    restarting the ~80 MB file.
  - Every update is **signature-pinned**: the downloaded APK is checked against CallVault's release
    certificate (and must be a genuinely newer build) before anything is installed.
  - The install also re-grants the permission an app update otherwise drops, so recording keeps
    working seamlessly across updates.
  - A **"Check for updates"** toggle (Settings → Updates, on by default) turns the checks off if you
    prefer to update manually.

### Notes
- Installing is always an explicit choice — CallVault never installs an update on its own.
- Distribution is still sideload/F-Droid/Obtainium; the in-app updater is an added convenience for
  GitHub releases, not a replacement.

## [1.2.3] — 2026-07-19

### Fixed
- **Filenames no longer lose the caller for unsaved numbers.** With a file-name template using
  `{contact_name}`, calls from numbers not saved in your contacts produced a name with an empty
  contact segment — no way to tell who the recording was from. The placeholder now falls back to
  the **phone number itself** when there is no saved contact (and it's not voicemail). If your
  template already includes `{phone_number}`, the fallback stays empty so the number isn't
  written twice.

## [1.2.2] — 2026-07-19

A field-report fix release: voicemail calls get their proper name, and the app now tells the truth
when recording can't work instead of pretending everything is fine.

### Fixed
- **Voicemail calls are now labeled correctly.** Carrier voicemail short codes (e.g. `123` in
  France) were being "standardized" into an invalid international number (`+33123`), which broke
  contact-name resolution — and voicemail isn't a real contact anyway, so lookups always came up
  empty. Short codes now keep their raw form, and calls to the carrier voicemail number are labeled
  with a localized **"Voicemail"** name in file names and the recordings list.
- **No more false "Ready to record" while Developer options is off.** If Developer options is
  disabled (e.g. after an OS update or manual toggle), the recorder daemon cannot survive and every
  "recording" comes out empty — but the Home screen still showed green. It now shows a clear red
  **"Developer options is off"** status explaining what to re-enable.
- **Empty recordings are no longer saved as if they succeeded.** A 0-byte file (daemon died before
  capture started) used to be cataloged, copied to Drive, and shown as an unplayable entry
  ("Can't read this file"). It is now deleted and reported with an error notification instead.
- **You are warned during the call if recording silently stops.** The app now watches the recorder
  daemon while a recording is live and immediately notifies **"this call is NOT being recorded"**
  if the daemon dies mid-call, instead of discovering the loss after hanging up.
- **Post-reboot and startup notifications are now translated.** The "Ready to record calls /
  Listening for calls after restart" notification (and the boot-time "Preparing call recorder…")
  were hardcoded in English; they now follow the app language like everything else.

### Internal
- Cross-country detection is preserved for invalid/foreign numbers (the ignore-cross-country
  auto-record rules keep working for them).
- The unit-test harness (JUnit/Robolectric) now lives on `main`, with tests covering the number
  enrichment, voicemail matching, file-name fallback, and Developer-options detection.

## [1.2.1] — 2026-06-26

A localization release: every shipped language is now fully translated, fixing screens that
appeared in English even when the rest of the app was localized.

### Fixed
- **Onboarding and main screens now follow the selected language.** The disclaimer/first page, the
  permissions and setup wizard, and the home screen (recordings list + status) previously fell back to
  English for many strings — most visibly the **first page stayed English** even with a French
  device/app language. All shipped locales (fr, de, es, it, hu, pl, ru, vi, zh-rCN) are now at **100%
  coverage** (289 UI strings each).
- **Recorder & pairing notifications are now translated.** The cold-start "Call recorder starting up…
  / Ready to record calls" status and the wireless-debugging pairing notifications were hardcoded in
  English; they are now string resources and localized in every language (using each locale's official
  Android wording for "Wireless debugging").

### Changed
- **Translation coverage is now enforced at build time.** Lint treats `MissingTranslation` and
  `ExtraTranslation` as errors, so a new untranslated string can no longer ship silently.

### Internal
- Hardened two notification posts (`DebugNotificationHelper`, `RecorderReadinessNotifier`) with an
  explicit `POST_NOTIFICATIONS` check, resolving the corresponding `MissingPermission` lint errors.

## [1.2.0] — 2026-06-25

A reliability release focused on **recording the first call after a reboot**, plus a rebuilt,
user-facing debug/bug-report flow and an audio default better suited to voice.

### Added
- **Debug section (always visible).** Settings now has a simple **Debug** section: turn on diagnostic
  logging, see an **"ON" reminder** (in-app warning + a persistent notification) so you don't leave it
  running, and **Share debug logs** in one tap via the system share-sheet to send a bug report. Logs
  are phone-number **redacted**.
- **24 kbps audio bit rate**, flagged **Recommended** and now the **default** for Opus — plenty for
  intelligible voice; higher rates only inflate file size.

### Changed
- **Removed the hidden "Developer Options"** (the 7-tap unlock, test-call simulator, and the
  redaction-off "Debug mode"). Log **redaction is now always on** and cannot be turned off.
- After a reboot **or an app update** the app briefly shows **"Call recorder starting up…"**, flipping
  to **"Ready to record calls"** once recording is actually possible — so you know when a call will be
  captured. Only appears while the recorder daemon is cold (nothing shown when it's already warm).

### Fixed
- **First call after a reboot now records.** A new bounded post-boot **live call-state listener**
  detects calls in real time instead of relying on the system `PHONE_STATE` broadcast, which on a
  freshly-booted device could arrive **~9 seconds late** — after the call had already ended.
- **Faster recorder warm-up after boot** (~5 s vs ~15 s): trimmed a redundant Wireless-Debugging wait
  and skip the stale-daemon scan in the first 90 s after boot (a reboot already cleared any daemon).
- **Daemon cold-start no longer over-waits.** The launcher returns the instant the daemon's binder
  arrives instead of blocking out the full keep-alive window, recovering calls that were previously
  aborted 1–3 s too late.
- **Redaction can no longer be left disabled.** A leftover developer "Debug mode" flag could keep real
  phone numbers in shared logs; redaction is now unconditional.
- **Number-less recordings rename correctly.** The end-of-call CallLog rename now uses
  `DocumentsContract.renameDocument` (the previous call threw on single-document SAF URIs), so files
  get the contact/number in their name.

## [1.1.1] — unreleased

A stability + features release built on top of v1.1.0. It keeps v1.1.0's proven on-demand
Wireless-Debugging behaviour (the daemon "keep-alive" experiment that made Wireless Debugging
repeatedly turn itself on was **not** included) and adds the safe fixes plus new settings.

### Added
- **Retention / auto-delete.** Automatically delete recordings older than a chosen period
  (Daily / Weekly / Bi-weekly / Monthly, or Keep forever). Set **one period for both device & cloud,
  or a separate period for each**. A daily background sweep runs at a **time you choose**, anchored to
  the **device's local time zone** (re-anchored automatically when you change time zone). Defaults to
  off; enabling it asks for confirmation.

### Changed
- **Settings reorganised.** "Recording & storage" is now **two separate sections** — **Recording**
  (filename template, auto-record rules) and **Storage** (target, device folder, Drive folder).
- **Accordion settings.** Sections open **one at a time** (Recording open by default); opening one
  collapses the others.

### Fixed
- **Phantom Drive recordings** no longer appear in the Home list — the list is now backed by a
  standalone on-device catalog instead of Drive's stale index.
- **Folder pickers** now open at their own currently-selected folder (best-effort; OEM file pickers
  may still ignore the hint).
- **Onboarding no longer skips the ADB step** after a reinstall (Auto Backup disabled), plus clearer
  guidance for the OEM per-app battery mode (e.g. OxygenOS "Allow background activity").
- **Stuck microphone** fixed: recording start is aborted if the call ends during daemon cold-start.

### Performance
- Faster scrcpy socket connect (tighter polling).

### Security / internal
- Removed the exported debug-only broadcast receivers used during development.

## [1.1.0] — 2026-06-11

Complete visual redesign ("Signal" theme) plus UX polish: setup wizard, Home screen with in-app
playback and filters, smoother Wireless-Debugging pairing. See the
[v1.1.0 release](https://github.com/madkongo/CallVault/releases/tag/v1.1.0).
