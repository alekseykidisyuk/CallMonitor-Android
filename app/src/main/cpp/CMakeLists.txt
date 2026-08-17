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

# Optimise the inference code even in a debug build.
#
# AGP compiles debug variants with CMAKE_BUILD_TYPE=Debug, and the NDK's Debug flags are -O0. For
# ggml that is not a mild slowdown: it is roughly 13x. Measured on the OP12, a 47 s clip took
# 10m26s at -O0 against ~47 s for the same model in an -O3 build, which makes an on-device debug
# run useless for timing and painful for anything else.
#
# This is placed before add_subdirectory so whisper, ggml and our own whispercv all inherit it;
# audiohandoff is declared above and deliberately keeps the default -O0 so it stays debuggable.
add_compile_options($<$<CONFIG:Debug>:-O3>)

set(WHISPER_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP            OFF CACHE BOOL "" FORCE)

add_subdirectory(${WHISPER_ROOT} ${CMAKE_CURRENT_BINARY_DIR}/whisper.cpp)

add_library(whispercv SHARED whispercv.cpp)
target_link_libraries(whispercv whisper ${log-lib})
