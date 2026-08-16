// JNI bridge to whisper.cpp for on-device call transcription.
//
// Deliberately ours rather than upstream's examples/whisper.android JNI, which hardcodes
// `params.language = "en"` and returns a single pre-formatted string. Both are disqualifying here:
// the calls this app records are frequently not English (decoding Hebrew as English produces
// garbage), and transcript storage needs per-segment start/end times rather than one blob.
//
// Runs in the app process, never in the privileged shell-uid recorder daemon — transcription needs
// no privilege and must not share a process with the capture path.

#include <jni.h>
#include "whisper.h"

extern "C" JNIEXPORT jstring JNICALL
Java_com_baba_callvault_transcription_WhisperNative_systemInfo(JNIEnv *env, jobject) {
    return env->NewStringUTF(whisper_print_system_info());
}
