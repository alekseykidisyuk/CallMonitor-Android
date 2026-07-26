<div align="center">

<img src="docs/screenshots/banner.svg" alt="CallVault" width="100%"/>

[![Release](https://img.shields.io/github/v/release/madkongo/CallVault?style=for-the-badge&label=Latest&labelColor=16223A&color=2DD4BF)](https://github.com/madkongo/CallVault/releases/latest)
[![Downloads](https://img.shields.io/github/downloads/madkongo/CallVault/total?style=for-the-badge&label=Downloads&labelColor=16223A&color=2DD4BF)](https://github.com/madkongo/CallVault/releases)
[![License](https://img.shields.io/badge/License-GPL%20v3%20%2B%20%C2%A77-FB7185?style=for-the-badge&labelColor=16223A)](LICENSE)
[![Android](https://img.shields.io/badge/Android-11%2B-2DD4BF?style=for-the-badge&labelColor=16223A&logo=android&logoColor=white)](#requirements)

</div>

> **CallVault is a fork of [ShizuCallRecorder](https://github.com/kitsumed/ShizuCallRecorder)** (Copyright © kitsumed (Med)), re-architected to run **self-contained over embedded ADB** instead of Shizuku. It is a modified, independent version — not endorsed by or affiliated with the original author. See [NOTICE.md](./NOTICE.md).

## What is CallVault?

**CallVault** is a **non-root, FOSS call recorder for Android** that records **both sides** of a phone call. It drives a privileged shell session entirely **on-device** — no root, no [Shizuku](https://github.com/RikkaApps/Shizuku), no companion PC — using an embedded ADB client ([libadb-android](https://github.com/MuntashirAkon/libadb-android)) over Android's own **Wireless Debugging**. Audio is captured through a direct on-device path, with [scrcpy-server](https://github.com/genymobile/scrcpy) as an automatic fallback.

You pair **once**; after that it's hands-free.

<div align="center">

| Home | Setup Wizard | Settings |
|:---:|:---:|:---:|
| <img src="docs/screenshots/home.png" width="240"/> | <img src="docs/screenshots/wizard.png" width="240"/> | <img src="docs/screenshots/settings.png" width="240"/> |

</div>

## Features

| | Feature |
|:---:|---|
| 🎙️ | Records **both sides** of incoming & outgoing calls (incl. Bluetooth / headset) |
| 💬 | **Recording app calls — VoIP (opt-in, experimental)** — records calls made inside **WhatsApp, Signal and Telegram**, both sides, with the calling app's icon on each recording (off by default; not carrier Wi-Fi calling) |
| 📶 | **Offline recording (opt-in)** — record calls even with **no Wi-Fi network**, for calls on the road (off by default; opens a local, RSA-gated debugging port when enabled) |
| 🔓 | **Keeps recording when the screen locks** — on many phones that otherwise stop a recording mid-call, fixed from inside the app in one tap |
| 🛡️ | **Resilient recording (opt-in)** — makes recording **resistant to interruptions**: a recording already in progress is unaffected if Android stops the background helper mid-call (off by default) |
| 🤖 | **Automatic** recording with per-call rules — ignore anonymous, cross-country, or specific contacts |
| ☁️ | Save to **device, a cloud folder, or both**, with optional **scheduled sync** (immediate / daily / weekly) |
| 🧹 | **Retention / auto-delete** — remove recordings after a chosen age, separately for device & cloud, swept daily at a time you pick (your local time zone) |
| ▶️ | In-app **recordings list** with playback, **contact-name** resolution, source badges, and filters |
| 🗂️ | Filter recordings by **source, direction, contact, or date**; play & delete each copy individually |
| 🎚️ | **Opus** or **AAC** at your chosen bitrate; [BCR](https://github.com/chenxiaolong/BCR)-compatible file names |
| 🔒 | **No root, no Shizuku, no PC** — everything runs on-device; Wireless Debugging is set up automatically and stays on while CallVault is ready ([why](#how-it-works)) |
| ⬆️ | **In-app updates** — CallVault notices new GitHub releases and installs them on a tap (signature-pinned, resumable download); never auto-installs |
| 🎨 | Clean, modern UI — no telemetry, no ads, no nonsense |

## How it works

After a one-time pairing, CallVault runs a **persistent privileged daemon** — a detached `app_process` under the shell user, in the spirit of Shizuku. Recording commands flow to it over **binder IPC**, so no ADB connection is needed at record time.

- **Wireless Debugging stays on while CallVault is ready.** Android shuts `adbd` down when its last connection is removed, and the daemon runs inside it — so switching Wireless Debugging off stops the daemon too. Measured on both a OnePlus 12 and a Galaxy S24 FE. Enabling **USB debugging** gives `adbd` a second connection and CallVault then switches Wireless Debugging off by itself. You never toggle anything manually after the first pair.
  > Earlier versions of this README said the daemon *survived* Wireless Debugging being turned off, and CallVault tried to turn it off on that basis. That was wrong on every device tested, and it caused a start-up loop; since 1.4.9 CallVault does not try. Getting back to "off" without USB debugging is on the [roadmap](#roadmap).
- Call audio is captured by the daemon through a **direct `AudioRecord` path** and muxed into a file you own (via the Storage Access Framework) — on the device and/or a cloud folder you pick through the system file picker. `scrcpy-server` is launched only as a fallback, when the direct path can't handle the chosen source or codec on your device.

### Keeping a recording alive when the screen locks

On many phones (OnePlus, Xiaomi, Samsung…), locking the screen during a call renegotiates the USB connection, which restarts the system's ADB daemon and takes the recorder down with it — mid-call.

The fix is to set the phone's **Default USB Configuration** to **"Charging only"**, and CallVault can do that for you: the setup wizard offers it, **Settings ▸ Experimental** has it, and if USB is on a data mode the Home screen and the recorder notification show a "locking the screen may stop recording — tap to fix" prompt. The trade-off is that plugging into a PC then defaults to charging, so pick *File transfer* manually when you actually want to move files.

### Resilient recording (opt-in)

Normally the daemon holds the microphone and encodes for the whole call, so if Android stops it part-way through — locking the screen can do this on some OEMs — the recording stops with it.

With **Resilient recording** on, the daemon only *creates* the capture and then **hands it to the app**, which holds it and does the encoding itself. Because the app is the one Android keeps alive, the daemon can die at any point mid-call and the recording simply carries on to the end.

It doesn't change how a recording *starts* — the daemon is still needed for that — so it's a completeness guarantee, not a replacement for the setup above. Off by default; enable under **Settings ▸ Experimental**.

### Recording app calls — VoIP (opt-in, experimental)

Calls placed inside WhatsApp, Signal or Telegram are not phone calls, so the usual capture cannot see
them. CallVault handles them a different way: the privileged helper registers an **audio policy** that
*duplicates* the call's incoming audio into a private mix — the other party keeps hearing you and you
keep hearing them, nothing is diverted — and pairs it with the microphone for your own side. The two
are written into a single recording, one side per channel.

This is a genuinely new capability: the only other working approach we know of needs **root**.

Off by default; enable under **Settings ▸ Experimental ▸ VoIP calls**, which asks you to confirm first.
Recording app calls is more tightly regulated than recording phone calls, and in many places every
participant must agree beforehand.

Honest limits:

- **Verified on one device and one Android version so far** — see the table below. An app can refuse to
  be captured, and that only becomes apparent once a call is under way; if only your side was recorded,
  CallVault says so.
- **Carrier Wi-Fi calling (VoWiFi / VoLTE) is not covered.** Its audio never becomes a normal playback
  stream, so this route cannot reach it.
- **Contact names come from the calling app's own call notification** — the only place Android exposes
  them. An app that isn't allowed to post notifications produces correctly recorded but unnamed files.

#### VoIP compatibility — tested devices

VoIP capture depends on the device's audio stack and on each app's own settings, so it can differ
between phones and can change when a calling app updates. This table records what has actually been
tested rather than what is assumed to work.

| Device | Android | OS build | Apps verified | Result | Reported by |
|---|---|---|---|---|---|
| OnePlus 12 (CPH2581) | 16 (API 36) | OxygenOS 16.0.8 (`CPH2581_16.0.8.300`, patch 2026-06-01) | WhatsApp 2.26.27.85 · Telegram 12.9.1 · Signal 8.19.2 | ✅ Both sides recorded on all three | maintainer |
| Samsung Galaxy S24 FE (SM-S721B) | 16 (API 36) | One UI 8.5 (`BP4A.251205.006.S721BXXSCDZF3`, patch 2026-06-05) | WhatsApp 2.26.28.77 | ✅ Both sides recorded, app + contact named correctly (carrier calls also verified) | maintainer |

**Please add yours.** If you try VoIP recording, open an issue with your device, Android version, OS
build, the calling app and its version, and whether both sides were captured — including failures,
which are just as useful. A "no" entry saves the next person the experiment.

#### Device differences worth knowing

Two behaviours differ between manufacturers and shaped the code, both found on a Samsung after a
OnePlus had happily hidden them:

- **`adbd` stops when its last transport is removed.** CallVault's helper is a child of an `adbd`
  shell, so switching Wireless debugging off takes the helper down with it. CallVault therefore leaves
  Wireless debugging on while the helper is running, and says so in its notification. Only **USB
  debugging** currently keeps `adbd` alive independently — with it enabled, CallVault switches Wireless
  debugging off again. ("Record without Wi-Fi" does *not* help here: its listener is a saved setting
  rather than a live connection, so it only returns *after* `adbd` restarts.)
- **The audio "mode owner" can be the system rather than the calling app.** On One UI a WhatsApp call
  reports uid 1000 (`system`), so an app label taken from it is wrong — it produced recordings named
  after Samsung's *Device maintenance*. Platform uids are now rejected and the app's own audio track is
  used instead.
- **An app call can raise the phone's telephony state.** On One UI a WhatsApp call reports `OFFHOOK`,
  so the carrier recorder also tried to record it — producing an empty second file (named from the call
  log, so with an unrelated contact's name) and an error notification, while the VoIP recording itself
  was perfectly fine. The carrier path now stands down when the VoIP recorder owns the call.
- **Resilient recording is not confirmed working on One UI.** Its audio handoff is rejected on this
  device (`geometry doesn't fit ashmem`). It fails *safely* — CallVault falls back to the normal path
  and the call is still recorded — but the extra protection it offers is not active there. Under
  investigation; see `docs/dev-notes/backlog.md`.

## Requirements

- **Android 11 or newer** (best on Android 12+; on Android 11 the screen must be unlocked during a call).
- **A 64-bit ARM device (`arm64-v8a`)** — which is effectively every phone shipping Android 11+. The APK carries a native library for that ABI only, so it will not install on 32-bit-only or x86 devices.
- **Wireless Debugging** available in Developer Options.
- **Developer options must stay enabled.** If you turn Developer options off later (or an OS update
  resets it), the recorder can't run and calls come out empty — the Home screen will show a red
  **"Developer options is off"** status until you re-enable it (Wireless debugging itself stays
  automatic; you never toggle that manually).
- No root, no PC, no Shizuku.

> [!IMPORTANT]
> CallVault relies on hidden internal Android APIs and `scrcpy-server`, so it can break on new Android releases or specific OEM builds. Behavior is **non-deterministic** across devices — read the [Disclaimer](#disclaimer).

## Install

1. Download the latest **`CallVault.apk`** from the [**Releases**](https://github.com/madkongo/CallVault/releases/latest) page (or use [Obtainium](https://github.com/ImranR98/Obtainium) for auto-updates from this repo).
2. Open it and allow installing from unknown sources if prompted.

> After the first install, CallVault can update **itself** — it checks GitHub for new releases and offers a one-tap install on the Home screen (toggle off under Settings → Updates). Installing is always your choice; it never updates on its own.

> CallVault is sideloaded only — it **cannot** be on the Google Play Store (Play prohibits both call recording and the embedded-ADB privilege mechanism it depends on). F-Droid is the intended catalog.

## How to use

**One-time setup (in-app):**

1. **Enable Wireless Debugging:** *Settings → System → Developer options → Wireless debugging → On.*
   (If Developer options aren't visible: *Settings → About phone → tap "Build number" 7 times.*) — CallVault detects this and offers a shortcut.
2. **Open CallVault** and accept the disclaimer.
3. On the **Permissions** screen, grant **Notifications**, then tap **Pair**. CallVault opens the Wireless-debugging screen and waits — when you flip the toggle on, pairing starts automatically.
4. Tap **"Pair device with pairing code"**, and type the 6-digit code into CallVault's notification. After a few seconds you'll get **"Paired ✓"** — tap it to return.
5. Grant the remaining permissions, then complete the **Setup Wizard**: where to save recordings, upload schedule (if you picked a cloud folder), auto-record rules, **reliability** (the screen-lock fix and off-Wi-Fi recording), audio quality, and file-name format.

**Day-to-day:** the **Home** screen shows app status and your recordings — tap one to play, expand a *Device + Drive* recording to play/delete each copy, and filter by source/direction/contact/date. Settings is one tap away.

> [!TIP]
> On OEMs that aggressively kill background apps (OnePlus/OxygenOS, Xiaomi, etc.), allow CallVault in **Auto-launch / Startup Manager** and exclude it from **battery optimization** so it records reliably and starts after a reboot. See [dontkillmyapp.com](https://dontkillmyapp.com/).
>
> If a recording ever stops when you lock the screen, set USB to **"Charging only"** — see [above](#keeping-a-recording-alive-when-the-screen-locks). Turning on **Resilient recording** additionally protects a call that is already being recorded.

## Roadmap

Planned, in rough priority order. Nothing here is promised by a date, and anything marked *investigating*
may turn out not to be possible — this is what is actually being worked on, not a wish list.

**Control over what gets recorded**

- **Manual VoIP recording** — start and stop an app call's recording yourself, instead of it always
  being automatic.
- **Choose when to record** — decide per call rather than by rule alone, including a prompt at the start
  of a call for people who want to make that decision each time.
- **Turn cellular recording off independently** — today the automatic rules cover carrier calls; VoIP is
  a separate opt-in. Someone who only wants app calls recorded should be able to say so.

**Getting Wireless Debugging back off**

Since 1.4.9 it stays on while CallVault is ready, because switching it off stops the helper. Three
routes, roughly in order of how promising they look:

- **Hand the helper over to a connection that outlives the switch** — start it over the loopback
  listener *after* Wireless Debugging is already off, instead of before. Needs "Record without Wi-Fi".
- **Make the helper genuinely outlive `adbd`** — Shizuku's server appears to manage this, so the
  technique is worth comparing against ours. This would fix it for everyone, with no extra settings.
- **Shizuku support** — let people who already run Shizuku use it instead of CallVault's own setup, as
  [requested](https://github.com/RikkaApps/Shizuku). Optional, alongside the built-in method, not
  replacing it.

**Reliability and honesty about it**

- **"Test my setup"** — one action that runs the whole pipeline and reports which step fails. This app
  fails silently, and the failure is usually discovered after the call that mattered.
- **Per-app VoIP support** — show which of your installed calling apps actually work, instead of a
  blanket "experimental, may not work".
- **Resilient recording on One UI** *(investigating)* — the audio handoff is rejected on Samsung; it
  falls back safely, but the protection is inactive there.
- **Faster, bounded startup** — one ADB call on the recording path is still unbounded and can stall.

**Smaller things already agreed**

- A manual **"Check for updates"** button — a release published just after you opened the app is
  currently unreachable for up to six hours.
- A **General** settings section grouping Visual settings, Experimental and Updates.
- Translations for the VoIP feature (it is English-only today).
- Protection against losing the ADB pairing — uninstalling wipes it with no recovery path.

Engineering detail for each of these lives in [`docs/dev-notes/backlog.md`](docs/dev-notes/backlog.md).

## Building from source

This is a standard single-module Android project at the repo root. See [BUILDING.md](./BUILDING.md):

```bash
./gradlew :app:assembleDebug   # → app/build/outputs/apk/debug/app-debug.apk
```

Requires JDK 17, the Android SDK, and — since CallVault now builds a small native library for Resilient recording — the **NDK and CMake** (`sdkmanager "ndk;27.2.12479018" "cmake;3.22.1"`).

CallVault is **reflection-heavy** (hidden APIs, the daemon is launched by class name) — a minified release build will break it, so minification stays off.

## Credits & attribution

CallVault is a modified fork of **[ShizuCallRecorder](https://github.com/kitsumed/ShizuCallRecorder)** by **kitsumed (Med)**; the original project's name, trademarks, and logos are the property of their owner and are used here only for this required attribution. The ADB pairing/mDNS code is adapted from [RikkaApps/Shizuku](https://github.com/RikkaApps/Shizuku) (Apache-2.0).

Built on the work of:
- [ShizuCallRecorder](https://github.com/kitsumed/ShizuCallRecorder) — the upstream project this is forked from
- [scrcpy](https://github.com/genymobile/scrcpy) — the fallback audio-capture server
- [libadb-android](https://github.com/MuntashirAkon/libadb-android) — the embedded ADB client

## License

Licensed under the [GNU General Public License v3.0](LICENSE). ⚠️ **Additional Terms** under GPLv3 Section 7 apply (at the end of the license file), including trademark protection and the mandatory fork-attribution requirements that this project complies with.

> CallVault is **not affiliated with, endorsed by, or supported by** kitsumed/ShizuCallRecorder, Shizuku, scrcpy, or Google/Android. "Android" is a trademark of Google LLC.

## Disclaimer

**Recording phone calls may be subject to complex and varying laws in different countries and jurisdictions.** You may need consent from all parties before recording. The developers and contributors are **not responsible** for any misuse or legal consequences. Learn more: [Telephone call recording laws](https://en.wikipedia.org/wiki/Telephone_call_recording_laws). **This is not legal advice** — consult a legal professional for your situation. It is **your responsibility** to verify that CallVault's behavior on your device complies with your local laws, and to stop immediately any activity that would constitute a legal infraction.
