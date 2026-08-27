# README impact log

The README rewrite is **approved and held** — 1,450 words down from 3,439, screenshots staged, not to
be published until the maintainer gives the go-ahead on a release. Work has continued since it was
approved, so this file tracks what would need to change in it *before* it goes up.

**Rule: every change that alters what the README claims gets a line here, in the same turn it is made.**
An approved document that quietly goes stale is worse than one that was never written, because nobody
re-reads it before publishing.

Status markers follow the global convention: 🧪 VERIFYING · ✅ VERIFIED · ❌ NOT WORKING · 📐 CALCULATED.

---

## Needs a README change

| # | Change | What the README needs | Status |
|---|---|---|---|
| R1 | **Licence-server death at paid rivals** — a paid recorder's domain lapsed and *retroactively* capped "lifetime" users to 30-second recordings; three of five paid incumbents are now 404 on Play US. | A line in the positioning. This is the strongest FOSS argument found in the whole research pass and the README currently does not make it. It reframes free software from a *preference* (privacy, price) into avoiding a *loss*: "I paid, I complied, and I lost it anyway." | ✅ evidence verified from reviews |
| R2 | **Setup wall is a trust failure, not a difficulty failure.** Complaints about rivals are about *disclosure order* — "they don't tell you until after you subscribed", "all my UPI apps stopped working". | The setup section should state consequences **before** the steps, including any effect on other apps. A reader who decides against it there is a success, not a lost install. | ✅ evidence verified |
| R3 | **We are the only FOSS recorder that is stock-unrooted, needs no companion app, records both sides of VoIP, and transcribes on-device.** Not "best at" — only. | The comparison table already exists; this claim should be stated plainly rather than left for the reader to infer. BCR lists unrooted stock support as a *non-feature*. | ✅ verified against their docs |
| R4 | **Off-Wi-Fi recording shipped in v1.4.0**, not parked. | If the README describes Wi-Fi as a requirement anywhere, that is wrong. Note the real limit instead: tcpip clears on reboot and re-arming needs Wi-Fi once. | ✅ verified in source and in the v1.5.8 tag |
| R5 | **Bluetooth headsets work** — field-proven by the maintainer's daily AirPods use. LE Audio/LC3 is untested. | Worth stating, since "does it work with my headphones" is an obvious pre-install question. Do not claim LE Audio. | ✅ VERIFIED by daily use / LE Audio 🧪 untested |

## Blocked on verification — do not write these into the README yet

| # | Change | Why it is held |
|---|---|---|
| R6 | Longer calls can be transcribed | 🧪 VERIFYING. `MAX_MINUTES` is still 15 and the memory improvement is 📐 CALCULATED, not measured. The README must not promise a limit that has not moved. |
| R7 | "CallVault tells you when a recording has no audio" | 🧪 VERIFYING. True in code as of `892509e`, but unconfirmed on a device, and it catches header-only files rather than silence — an over-broad claim here would be exactly the false confidence the fix exists to remove. |

## Explicitly NOT going in the README

- **Consent beeps / recording announcements.** No public API at any privilege level we can reach — the
  platform dialer uses a HAL path unavailable even at shell uid. If the README mentions legal
  compliance, it should say we cannot inject a beep rather than implying we might.
- **Wear OS, launcher-icon hiding.** Zero demand and provably non-functional respectively.
- **Any hardware-acceleration claim.** NNAPI, Hexagon and Vulkan are all ruled out for our stack.

## Review checklist before publishing

1. Re-read this file top to bottom; fold in every ✅ row.
2. Check nothing in the 🧪 section has silently been written in anyway.
3. Confirm the screenshots still match the current UI — the Home status card gained a new failure
   message in `892509e`.
4. Confirm the GPLv3 §7 attribution is present: an in-UI "fork of ShizuCallRecorder" notice plus a repo
   link. That obligation is unchanged and is not optional.
