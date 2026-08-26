/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.transcription

/**
 * The three whisper decoding knobs that were left at a default nobody chose.
 *
 * Gathered into one value rather than passed as three loose ints so that the benchmark and the app
 * take **the same code path** — a measurement made through a second, simpler path measures the
 * second path. [DEFAULT] is what ships; the instrumented benchmark constructs variants of it.
 *
 * Immutable, like everything else here: a run's settings must still read the same afterwards as
 * they did going in, or the number written next to them is not the number that produced them.
 *
 * **[DEFAULT] costs nothing.** Measured end to end on a real 4:05 Hebrew call on the OP9 Pro with
 * `large-v3-turbo-q8_0`: what shipped before took **362 s**, what ships now takes **358 s**. VAD
 * removes more audio than beam search adds work, so the two land together for free. Every number
 * below comes from that one call — one call, one language, one device, and the transcripts are the
 * evidence rather than a WER score, because there is no reference transcript to score against.
 */
data class DecodeSettings(
    /**
     * Beam width, or 1 for greedy.
     *
     * MEASURED (Whisper paper arXiv:2212.04356, Appendix D, `large-v2`): beam-5 improves twelve of
     * fourteen sets — CallHome 17.6 → 16.4 WER (−6.8% rel), CORAAL 16.2 → 14.2 (−12.3% rel) — and
     * telephone corpora are precisely our domain. It makes the two noisiest far-field sets worse
     * (AMI-SDM1 36.4 → 39.9), and arXiv:2501.11378 finds higher beam widths hallucinate **more** on
     * non-speech, which is why this must never be raised without [useVad] on to remove the
     * non-speech first.
     *
     * Cost is decoder-side only, and turbo is 32 encoder layers against 4 decoder layers, so the
     * folklore "beam-5 is 3–5× slower" does not describe this model.
     *
     * MEASURED on a real 4:05 Hebrew call, OP9 Pro, `large-v3-turbo-q8_0`, VAD on for both:
     * greedy 341 s, **beam-5 358 s — +5.0%**, for identical segment counts. Not 3×, not even 1.1×.
     * The architectural argument was right, and the folklore is describing a model whose decoder is
     * not four layers deep.
     *
     * What the 5% bought on that call: "את לא הגדרנו" → "עוד לא הגדרנו" (negation restored — greedy
     * with VAD had inverted the sentence), one of the two spoken "80 אלף" figures recovered from
     * "8 אלף", and a coherent sign-off where greedy produced "שכף, ביי". Against that it spelled
     * "מרובים" as "מרוביים" and "ימין" as "ידמין". Net positive, and cheap enough that it would be
     * worth keeping even if it were merely neutral.
     */
    val beamSize: Int = 5,

    /**
     * Cap on the previous windows' text re-injected as conditioning. 0 disables it entirely; -1
     * leaves whisper's own default in place, which is what the benchmark's baseline needs.
     *
     * `no_context = true` does **not** turn this off — it clears carry-over from a previous *call*,
     * once. Within a run `prompt_past` is rebuilt after every 30-second window and re-injected,
     * gated only on this number. Rolling conditioning is the documented mechanism behind repetition
     * loops, and also the only thing holding proper nouns, punctuation and casing steady across a
     * window boundary, so this is a trade in both directions.
     *
     * The nominal whisper.cpp default is 16384, but it is clamped by
     * `min(n_max_text_ctx, n_text_ctx / 2)` and `n_text_ctx` is 448 on every large model — so the
     * **default has always meant 224 tokens, not 16384**. The research note's premise that we run
     * "with full rolling conditioning" overstates it; the window was never unbounded.
     *
     * MEASURED on a real 4:05 Hebrew call, OP9 Pro, `large-v3-turbo-q8_0`, VAD and beam-5 held on:
     *
     * | `n_max_text_ctx` | wall | segments | chars |
     * |---|---|---|---|
     * | 0 (off)        | 332 s | **79** | **2475** |
     * | 64 (upstream's middle ground) | **310 s** | 42 | 2662 |
     * | default (224)  | 358 s | 47 | **2749** |
     *
     * **0 is disqualified, and not marginally.** It fragmented the call into 79 segments of one to
     * two seconds — "אתה רוצה פה" / "אבטחה על הדבר הזה" / "שיהיה לך שימוש" as three separate
     * segments — which is far below the 8–9-word length where downstream quality peaks, and it
     * dropped terminal punctuation almost everywhere. It also produced the *least* text of any
     * variant. That is the documented cost of losing rolling conditioning, and it showed up exactly
     * as predicted.
     *
     * **64 is fast and tidy but loses speech**, which is why it is not what ships: it was the only
     * variant to drop two real utterances the other four all captured — "פתאום 80 אלף" and
     * "יש לך תקציב, יש לך שיר". Dropped speech is invisible in a segment count and unrecoverable
     * downstream, so it outweighs the 13% it saves and the handful of words it gets right.
     *
     * So: leave whisper's own cap alone. Worth revisiting with a second call — 64's speed and its
     * longer segments are real — but not on one call's evidence when that call lost content.
     */
    val maxTextCtx: Int = -1,

    /**
     * Whether to trim non-speech with the bundled Silero model before decoding.
     *
     * MEASURED: Whisper paper Table 7, 10.6 → 10.2 avg WER (−3.8% rel) helping all seven datasets;
     * WhisperX (arXiv:2303.00747) TED-LIUM 10.5 → 9.7 with 5-gram repetitions 131 → 75 (−43%);
     * arXiv:2501.11378 measures a 40.3% non-speech hallucination rate without it. Careless Whisper
     * (arXiv:2402.08021) traces hallucinated phrases to **long non-vocal durations**, which is the
     * defining profile of a phone call.
     *
     * MEASURED on a real 4:05 Hebrew call, OP9 Pro, `large-v3-turbo-q8_0`, greedy for both: 54
     * segments / 2645 chars without, **47 segments / 2706 chars with**. Fewer segments and *more*
     * text at once — so the trimming merged fragments rather than deleting speech, which is the
     * result that had to hold before any of this could ship. The VAD kept 14 speech stretches out
     * of 245 seconds of audio.
     *
     * The single clearest win was a recovered utterance: "אני רוצה לעשות פיצ'ר אחד לראות איך אתה
     * עובד, לתמחיר אותו" — the buyer stating what he actually wants, the crux of the negotiation —
     * which is **absent entirely** from the no-VAD transcript. It also turned "ושנח זור אליך" into
     * "בסדר, אני אחזור אליך". It is not free: it misread both spoken "80 אלף" figures as "8 אלף"
     * on this call, which beam search then partly undid.
     *
     * Off only for measurement. There is no user-facing setting: nothing here is a preference.
     */
    val useVad: Boolean = true,
) {
    init {
        require(beamSize >= 1) { "beamSize must be >= 1, was $beamSize" }
        // -1 is "leave whisper's default alone" and is distinct from 0, which is "no conditioning
        // at all". Anything below -1 would be silently reinterpreted by the native side.
        require(maxTextCtx >= -1) { "maxTextCtx must be >= -1, was $maxTextCtx" }
    }

    /** Compact one-line form for logs, so a transcript in a bug report says how it was produced. */
    override fun toString(): String =
        "beam=$beamSize ctx=$maxTextCtx vad=${if (useVad) "on" else "off"}"

    companion object {
        /** What ships. Every field's justification is on the field. */
        val DEFAULT = DecodeSettings()
    }
}
