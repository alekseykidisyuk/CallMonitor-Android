// JNI bridge to whisper.cpp for on-device call transcription.
//
// Deliberately ours rather than upstream's examples/whisper.android JNI, which hardcodes
// `params.language = "en"` and returns a single pre-formatted string. Both are disqualifying here:
// the calls this app records are frequently not English (decoding Hebrew as English produces
// garbage), and transcript storage needs per-segment start/end times rather than one blob.
//
// Runs in the app process, never in the privileged shell-uid recorder daemon — transcription needs
// no privilege and must not share a process with the capture path.
//
// Thread-safety: whisper.cpp forbids touching one context from more than one thread at a time.
// Nothing is locked here; TranscriptionEngine serialises every call onto a single thread instead.

#include <jni.h>
#include "whisper.h"

static inline whisper_context *ctx_of(jlong p) {
    return reinterpret_cast<whisper_context *>(p);
}

extern "C" {

JNIEXPORT jstring JNICALL
Java_com_baba_callvault_transcription_WhisperNative_systemInfo(JNIEnv *env, jobject) {
    return env->NewStringUTF(whisper_print_system_info());
}

JNIEXPORT jlong JNICALL
Java_com_baba_callvault_transcription_WhisperNative_initContext(JNIEnv *env, jobject, jstring path) {
    const char *p = env->GetStringUTFChars(path, nullptr);
    whisper_context_params cp = whisper_context_default_params();
    whisper_context *c = whisper_init_from_file_with_params(p, cp);
    env->ReleaseStringUTFChars(path, p);
    return reinterpret_cast<jlong>(c);
}

JNIEXPORT void JNICALL
Java_com_baba_callvault_transcription_WhisperNative_freeContext(JNIEnv *, jobject, jlong ptr) {
    if (ptr != 0) whisper_free(ctx_of(ptr));
}

JNIEXPORT void JNICALL
Java_com_baba_callvault_transcription_WhisperNative_transcribe(
        JNIEnv *env, jobject, jlong ptr, jfloatArray audio, jint threads, jstring language) {
    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_realtime   = false;  // upstream sets this true; it floods logcat on a long call
    params.print_progress   = false;
    params.print_timestamps = false;
    params.print_special    = false;
    params.translate        = false;  // transcribe in the spoken language, never translate to English
    params.n_threads        = threads;
    params.offset_ms        = 0;
    params.no_context       = true;
    params.single_segment   = false;

    // nullptr language => let whisper detect it. Anything else is an ISO code such as "he".
    const char *lang = (language != nullptr) ? env->GetStringUTFChars(language, nullptr) : nullptr;
    params.language        = lang;
    params.detect_language = (lang == nullptr);

    const jsize n = env->GetArrayLength(audio);
    jfloat *samples = env->GetFloatArrayElements(audio, nullptr);
    whisper_full(ctx_of(ptr), params, samples, n);
    // JNI_ABORT: whisper never writes to the input, so there is nothing to copy back.
    env->ReleaseFloatArrayElements(audio, samples, JNI_ABORT);

    if (lang != nullptr) env->ReleaseStringUTFChars(language, lang);
}

JNIEXPORT jint JNICALL
Java_com_baba_callvault_transcription_WhisperNative_segmentCount(JNIEnv *, jobject, jlong ptr) {
    return whisper_full_n_segments(ctx_of(ptr));
}

// whisper reports segment times in CENTISECONDS; every consumer in this app works in milliseconds.
JNIEXPORT jlong JNICALL
Java_com_baba_callvault_transcription_WhisperNative_segmentStartMs(JNIEnv *, jobject, jlong ptr, jint i) {
    return whisper_full_get_segment_t0(ctx_of(ptr), i) * 10;
}

JNIEXPORT jlong JNICALL
Java_com_baba_callvault_transcription_WhisperNative_segmentEndMs(JNIEnv *, jobject, jlong ptr, jint i) {
    return whisper_full_get_segment_t1(ctx_of(ptr), i) * 10;
}

JNIEXPORT jstring JNICALL
Java_com_baba_callvault_transcription_WhisperNative_segmentText(JNIEnv *env, jobject, jlong ptr, jint i) {
    return env->NewStringUTF(whisper_full_get_segment_text(ctx_of(ptr), i));
}

}
