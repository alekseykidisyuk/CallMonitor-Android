# 🧪 VERIFYING — decode peak memory, and silent-failure reporting

Three fixes on branch `fix/decode-peak-memory`. **None of them is verified.** Unit tests pass, the
translation gate passes, the build installs — and none of that is the same as a real call on a real
phone. This file stays at 🧪 VERIFYING until the maintainer reports back, at which point it becomes
`✅ VERIFIED <date>` or `❌ NOT WORKING <date>` with what actually happened.

| Commit | Change | Status |
|---|---|---|
| `cca1ee5` | Stop widening the whole call to float32 at 48 kHz | 🧪 VERIFYING |
| `6836aff` | Decode straight into shorts instead of staging bytes | 🧪 VERIFYING |
| `892509e` | A header-only file is no longer reported as a successful call | 🧪 VERIFYING |

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

## What settles it

1. **An ordinary two-sided call**, carrier. Confirm it records, both sides are audible, and the Home
   status card says the setup is verified — i.e. the new floor has not started rejecting real
   recordings. This is the regression risk of `892509e`.
2. **Transcribe a call of roughly 10–15 minutes.** Previously this was at or over the wall. Confirm it
   completes rather than dying, and that the transcript is not garbled — garbling would mean the
   single-pass resampler is not equivalent after all, which the unit tests say it is.
3. **A VoIP call**, since `VoipRecordingCoordinator` is the other caller of the outcome classifier.
4. If a recording *does* come out empty or header-only, confirm the card now says the call contains no
   audio rather than claiming success.

Anything above 15 minutes still refuses before it starts, by design — that is the untouched limit, not
a failure of these fixes.

## Related

- Root cause and the dead ends: `2026-08-27-competitive-research-synthesis.md` (Tier 0), on branch
  `docs/competitive-research-2026-08-27`. **Its Tier 0 entries need the same 🧪 VERIFYING marker when
  these branches meet.**
- 🚨 `offset_ms`/`duration_ms` are **not** a route to chunked decoding — confirmed twice at the source.
  `whisper_full` builds the mel for the whole file before those parameters are read.
