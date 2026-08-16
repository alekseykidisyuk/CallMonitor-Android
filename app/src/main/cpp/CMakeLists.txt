# Native half of "Resilient recording" (audio-capture handoff): extracts the IAudioRecord binder +
# cblk ashmem fd in the daemon, and drains the cblk ring in the app.
cmake_minimum_required(VERSION 3.22.1)
project(audiohandoff)

add_library(audiohandoff SHARED audiohandoff.cpp)

find_library(log-lib log)

target_link_libraries(audiohandoff ${log-lib})

# --- On-device transcription (whisper.cpp) ---
#
# Built from the tag-pinned third_party/whisper.cpp submodule. Nothing here is forked: only our own
# small JNI (whispercv.cpp) is ours. arm64-v8a only, matching the app's existing abiFilters.
#
# GGML_OPENMP must stay OFF: the Android NDK ships no OpenMP runtime and the link fails with it on.
set(WHISPER_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/whisper.cpp)

set(WHISPER_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP            OFF CACHE BOOL "" FORCE)

add_subdirectory(${WHISPER_ROOT} ${CMAKE_CURRENT_BINARY_DIR}/whisper.cpp)

add_library(whispercv SHARED whispercv.cpp)
target_link_libraries(whispercv whisper ${log-lib})
