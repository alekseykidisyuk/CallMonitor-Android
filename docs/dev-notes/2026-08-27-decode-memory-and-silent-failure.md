# ✅ VERIFIED 2026-08-27 — decode peak memory, and silent-failure reporting

Three fixes on branch `fix/decode-peak-memory`. **Confirmed by the maintainer on the OP12,
2026-08-27**, on a real carrier call, a real 14:41 transcription and a real VoIP call. What was
actually observed is recorded below — including the one thing that remains untested.

| Commit | Change | Status |
|---|---|---|
| `cca1ee5` | Stop widening the whole call to float32 at 48 kHz | ✅ VERIFIED 2026-08-27 |
| `6836aff` | Decode straight into shorts instead of staging bytes | ✅ VERIFIED 2026-08-27 |
| `892509e` | A header-only file is no longer reported as a successful call | ✅ VERIFIED 2026-08-27 (no regression) |

## What is claimed, and how strongly

**📐 CALCULATED, not measured — peak decode memory ~259 MB → ~144 MB at fifteen minutes, 48 kHz.**
This is arithmetic over the allocations, not a heap dump. The two allocations removed:

1. `pcm16ToMonoFloat` widened the entire call to float32 *while still at the input rate*, so 86.4 MB of
   `ShortArray` sat alongside 172.8 MB of `FloatArray` — 259 MB against a 256 MB heap.
2. `decodePcm` staged the call as bytes in a `ByteArrayOutputStream`, called `toByteArray()`, then
   copied that into a `ShortArray` — three full-length buffers at overlapping moments, peaking near
   220 MB on its own. Fixing only (1) would have left the ceiling exactly where it was.

**✅ established, because the compiler and tests enforce it — output is unchanged.** Six equivalence
tests pin the new single-pass path to the old two-step one across mono, stereo, 44.1 kHz, the target
rate, the aliasing guarantee and the edge cases. The anti-alias filter was tuned against thirteen real
recordings and must not shift.

**The silent-failure fix is a narrower claim than it sounds.** `sizeBytes > 0` was the entire test for
"did this call record", so all seven of the 98-byte files from August were classified `Verified`. There
is now a 1 KiB floor and a distinct `FailureReason.NO_AUDIO`. **It catches a file with no samples. It
does not catch a full-length file of silence** — that needs a PCM peak check on the capture path, the
same follow-on that would let carrier capture answer `farPartyHeard` instead of passing null.

## What has NOT changed, on purpose

`TranscriptionLengthLimit.MAX_MINUTES` is still **15**. The arithmetic says it can roughly double, but
this limit was *already* set once on an empirical wall nobody understood, and setting it again on a
calculation would repeat the mistake. It moves only after a long call is measured on a device.

## What the maintainer observed, 2026-08-27

1. **Ordinary carrier call — fine.** This was the real regression risk: the new 1 KiB floor could have
   started rejecting genuine recordings. It did not. `892509e` is clear.
2. **A 14:41 recording transcribed fine.** Hebrew quality is unchanged and still poor, which is the
   known ceiling rather than anything these commits touch — see the transcription-quality research. What
   matters here is that a long real call decoded and transcribed with no garbling, which is the
   behavioural proof that the single-pass resampler is equivalent to the two-step path it replaced.
3. **VoIP call recorded fine.** The other caller of the outcome classifier, also clear.

### ✅ VERIFIED 2026-08-27 — the 20-minute limit holds, and now it is measured

14:41 sat *below* the old 15-minute guard, so nothing had exercised the freed memory — and the guard
was the only thing preventing the test. The maintainer chose to raise it rather than add a test-only
override, on the grounds that a real long call is the only way to find the honest ceiling.

📐 Peak is about **9.6 MB per minute** — 5.76 MB of interleaved PCM plus 3.84 MB of 16 kHz float
output. 20 minutes is 192 MB against a 256 MB heap; 25 would be 240 MB and leave nothing for the app,
which is why it is 20 and not higher.

**MEASURED on the OP12, an 18:58 call transcribed successfully.** Java heap sampled every 30 s
through the run: a peak of **132 MB observed**, settling to 95–111 MB while whisper worked, then
released to ~9 MB on completion. Against a 256 MB ceiling that is comfortable, and the decode spike
that precedes it got through unobserved but evidently survived. The arithmetic predicted ~182 MB for
this length and reality came in below it, so the estimate is conservative in the safe direction.

**The heap ceiling is the ROM's, not ours.** `dalvik.vm.heapgrowthlimit` is **256m on the OP12** and
**384m on the OP9 Pro** — the weaker phone has the larger ceiling, because the limit is an OEM policy
value and has nothing to do with installed RAM. For heap-cap questions the **OP12 is the stricter
device**. `dalvik.vm.heapsize` is 512m on both, which is what `android:largeHeap="true"` would buy;
we deliberately do not set it, because a larger heap makes the app a fatter target for the low-memory
killer at exactly the moment a call is being recorded.

### Fixed after this round — VoIP now confirms a recording (`031079e`)

The gap below was real and is closed: `showRecordingEnded()` is now shared, and the VoIP path calls it
on a Verified outcome only. 🧪 VERIFYING — needs one app call to confirm the toast and vibration land.

### Found while checking, not caused by these commits

**A VoIP recording ends with no confirmation of any kind.** The maintainer noticed the end-of-recording
toast and vibration were missing after a VoIP call and asked whether these changes broke it. They did
not: `handleStateChangeToasts` is called from exactly one place, `RecordingForegroundService`, and
`VoipRecordingCoordinator` carries an explicit comment that the VoIP path *deliberately* does not go
through it. VoIP can raise an error notification and nothing else, so it has never produced that
feedback. Pre-existing, and it lands squarely on the silent-failure theme — logged in the backlog.

## Related

- Root cause and the dead ends: `2026-08-27-competitive-research-synthesis.md` (Tier 0), on branch
  `docs/competitive-research-2026-08-27`. **Its Tier 0 entries need the same 🧪 VERIFYING marker when
  these branches meet.**
- 🚨 `offset_ms`/`duration_ms` are **not** a route to chunked decoding — confirmed twice at the source.
  `whisper_full` builds the mel for the whole file before those parameters are read.

---

## ✅ VERIFIED 2026-08-27 — Batch 1 (A2, A5, A6, A8)

Confirmed by the maintainer on the OP12: an ordinary carrier call was fine, transcription showed the
new foreground notification, and a VoIP call produced speaker labels for the first time.

| Commit | Change | Status |
|---|---|---|
| `1590acc` | A6 — a call with no number no longer throws through the ignore check | ⚠️ ships unverified |
| `5c09077` | A5 — `IntentCompat` for the Android 13 parcelable bug | ⚠️ **untestable here** |
| `2288d59` | A2 — transcription and summarisation run in the foreground | ✅ VERIFIED 2026-08-27 |
| `a475f17` | A8 — VoIP keeps the speaker separation it already had | ✅ VERIFIED 2026-08-27 |

**Two are honestly unverified and must not be described otherwise.** A5 fixes a platform bug that only
exists on Android 13; both test devices are newer, so no test on this hardware can exercise it — it
ships on code review. A6 needs a call that arrives with no number (Teams, a withheld number, a short
code), which cannot be summoned on demand; it may stay unverified indefinitely.

**A8 was larger than the research described.** Feeding the detector was only half: nothing *collected*
the turns for VoIP, because `SpeakerTurnsRepository.collectAfterCall` is called only from
`RecordingForegroundService`, which the VoIP path deliberately bypasses. Both halves were needed, and
either alone would have looked correct while producing nothing.

## ✅ VERIFIED 2026-08-27 — Batch 2 (A3, A4)

| Commit | Change | Status |
|---|---|---|
| `9227f80` | A3 — bound the strings and whitespace the grammar left open | ✅ VERIFIED 2026-08-27 |
| `3453e30` | A4 — say so when the grammar is being ignored | ✅ VERIFIED 2026-08-27 (no regression) |

**The trap worth carrying out of this one:** `llama-grammar.cpp` silently rewrites a repetition bound
**above 2000** to `UINT64_MAX` — unbounded. A future edit that raises a limit to be generous would
therefore restore the original defect invisibly. There is now a test that fails if any bound reaches
2000, because this is not something anyone would catch by reading.

A4's value is not that it changes output — it should not — but that a grammar which stops applying can
no longer do so in silence.

## ✅ VERIFIED 2026-08-27 — Batch 3 (A7)

| Commit | Change | Status |
|---|---|---|
| `f5d5063` | A7 — nothing appears in the user's folder until the recording is complete | ✅ VERIFIED 2026-08-27 |

Confirmed on the OP12 across both capture paths: a carrier call and a VoIP call each recorded, landed
in the folder, and **appeared only after the call ended** — which is the behaviour the change exists to
produce, not merely a sign that nothing broke. Google Drive upload still works.

Two things this pass taught that the research description did not contain:

- **"Record private, publish complete" was not enough on its own.** Creating the destination up front
  and filling it at the end still leaves a **0-byte file** in the folder for the whole call, and a sync
  tool that uploads once takes the empty one. The destination had to not exist at all until there was a
  finished recording. Truncated and empty are the same bug wearing different clothes.
- **The dangerous part compiled cleanly.** `RecordingForegroundService` read `currentRecordingUri`
  *before* `release()`, and publishing now happens inside release. Every test passed and it would have
  silently skipped the call-log rename fallback. Isolating A7 into its own cycle is what made it
  findable.

## Where A0–A8 finished

| | Change | Status |
|---|---|---|
| A0 | A header-only file is not a successful call | ✅ VERIFIED |
| A1 | Decode peak memory 259 MB → 144 MB, limit 15 → 20 min | ✅ VERIFIED (measured: 132 MB peak on an 18:58 call) |
| A2 | Workers run in the foreground | ✅ VERIFIED |
| A3 | Grammar strings and whitespace bounded | ✅ VERIFIED |
| A4 | An ignored grammar says so | ✅ VERIFIED |
| A5 | `IntentCompat` for the Android 13 parcelable bug | ⚠️ **UNVERIFIED — untestable on this hardware** |
| A6 | A call with no number does not throw | ⚠️ **UNVERIFIED — needs a no-number call** |
| A7 | Nothing appears in the folder until complete | ✅ VERIFIED |
| A8 | VoIP keeps its speaker separation | ✅ VERIFIED |

A5 and A6 stay unverified rather than being rounded up. A5 fixes a platform bug that exists only on
Android 13 and both test devices are newer; A6 needs a call that arrives with no number, which cannot be
summoned on demand.
