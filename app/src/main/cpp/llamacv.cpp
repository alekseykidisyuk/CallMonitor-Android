/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

// JNI bridge to llama.cpp, for summarising a transcript on the device.
//
// Deliberately the same shape as whispercv.cpp next door: an opaque context pointer paired with
// exactly one free, one owner serialising access, and a real abort flag — because a long generate
// is one blocking native call that neither coroutine cancellation nor WorkManager can interrupt.
//
// This stub exists to prove the library builds and links. The generate loop lands in Task 2.

#include <jni.h>

#include "llama.h"

extern "C" JNIEXPORT jstring JNICALL
Java_com_baba_callvault_summary_LlamaNative_systemInfo(JNIEnv *env, jobject /* this */) {
    return env->NewStringUTF(llama_print_system_info());
}
