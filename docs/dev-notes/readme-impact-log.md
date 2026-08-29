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
| R9 | **Works with self-hosted sync** — recordings are staged privately and only appear in the folder once complete, so Syncthing, FolderSync and Nextcloud can never pick up a truncated or 0-byte file. | Worth stating plainly: this is a concrete, checkable advantage over recorders that mux straight into the destination, and it needs no network code of ours. | ✅ VERIFIED 2026-08-27 |
| R10 | **App calls now carry speaker labels**, not just carrier calls. | If the README describes speaker attribution, it should not imply carrier-only. | ✅ VERIFIED 2026-08-27 |
| R11 | **Transcription limit is 60 minutes**, not 15 and no longer 20. Chunked passes bound peak memory by one ≈ 6-minute chunk, so length stopped driving the heap. | Any stated limit must match. Write **60**. The earlier 20 in this row was correct on 2026-08-27 and was superseded the next day — noted so nobody trusts a stale row. | 🧪 60 is 📐 CALCULATED, not measured — hold until a real long call completes |
| R5 | **Bluetooth headsets work** — field-proven by the maintainer's daily AirPods use. LE Audio/LC3 is untested. | Worth stating, since "does it work with my headphones" is an obvious pre-install question. Do not claim LE Audio. | ✅ VERIFIED by daily use / LE Audio 🧪 untested |

| R14 | **Search covers summaries and notes**, not just the spoken transcript. A call is findable by the words its summary used for the outcome — which are often not words anyone said aloud — and by the note the user typed themselves. | If the README describes search, it must not say "search your transcripts"; it searches transcripts, summaries and notes. Worth stating, because the summary is where a decision is recorded in plain language. | 🧪 VERIFYING — migration proven on real SQLite (9 instrumented tests, emulator), not yet used on the maintainer's own library |

| R15 | **Transcripts export as TXT, Markdown, SRT, VTT or JSON.** Subtitle formats mean a recording and its transcript can be opened together in a player or editor; Markdown carries the summary and the note with it. | If the README lists what you can do with a transcript, it currently implies copy and share only. Worth stating the subtitle formats specifically — no other FOSS recorder in the survey exports them. | 🧪 VERIFYING — 20 unit tests over the formats, not yet opened in a real player |

| R12 | **CallVault says when a recording contains no audio.** | Safe to state, but state it *narrowly*: it catches a file with no audio samples, **not** a full-length recording of silence. An over-broad claim here would manufacture exactly the false confidence the fix exists to remove. | ✅ VERIFIED 2026-08-27 |

## Blocked on verification — do not write these into the README yet

| # | Change | Why it is held |
|---|---|---|
| R13 | **Long calls can be transcribed** — chunked passes removed the length ceiling that used to refuse anything over 15 minutes. | The regression that killed the first attempt is confirmed gone, but on **one** call, by **one** user, in **one** language (Hebrew, 26:41, 2026-08-28). Agreed with the maintainer that this is not enough to publish a claim on. Needs the French tester on a call that crosses a chunk seam, plus one call over 20 minutes from someone other than the maintainer. See `2026-08-27-decode-memory-and-silent-failure.md`. |
| R11 | The **60-minute** figure itself. | Same hold as R13, and additionally the number is arithmetic rather than a measurement — no call anywhere near 60 minutes has been transcribed. Publishing a limit we have never reached invites exactly the bug report it would cause. |

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
