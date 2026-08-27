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

### 🧪 VERIFYING — the limit was raised to 20 minutes on the strength of the arithmetic

14:41 sat *below* the old 15-minute guard, so nothing had exercised the freed memory — and the guard
was the only thing preventing the test. The maintainer chose to raise it rather than add a test-only
override, on the grounds that a real long call is the only way to find the honest ceiling.

📐 Peak is about **9.6 MB per minute** — 5.76 MB of interleaved PCM plus 3.84 MB of 16 kHz float
output. 20 minutes is 192 MB against a 256 MB heap; 25 would be 240 MB and leave nothing for the app,
which is why it is 20 and not higher.

**This is the one part still unproven.** If a call between 15 and 20 minutes dies rather than being
refused, `MAX_MINUTES` is the number to lower — and that failure is worth more than the calculation.

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
