cmake_minimum_required(VERSION 3.22.1)
project(loamllama CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ---------------------------------------------------------------------------
# llama.cpp
#
# Every option below was chosen from on-device measurement, not from docs.
# See the roadmap's "Phase 2 spike" and "Runtime CPU dispatch picks the wrong
# variant" sections for the numbers; the short version is that getting these
# wrong costs between 42% and 4x of prompt-processing speed, with the app
# working correctly and nothing in any log to suggest a problem.
# ---------------------------------------------------------------------------

# Runtime CPU dispatch. ANDROID_ABI=arm64-v8a alone targets baseline armv8-a,
# which compiles out every dotprod/i8mm kernel and costs 4x on prompt
# processing. Hardcoding the fast arch instead would SIGILL on older devices,
# so the answer is one shared object per feature set, selected at load time.
#
# These three go together: ALL_VARIANTS requires BACKEND_DL, BACKEND_DL
# requires shared libs, and llama.cpp errors out if GGML_CPU_ARM_ARCH is also
# set. Do not set it.
set(GGML_BACKEND_DL        ON  CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS  ON  CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS      ON  CACHE BOOL "" FORCE)

# Nothing here needs a network stack, a server, or a CLI. LLAMA_BUILD_APP and
# LLAMA_BUILD_COMMON default to LLAMA_STANDALONE, which add_subdirectory
# already turns off, but being explicit keeps a future llama.cpp bump from
# quietly pulling them back in.
set(LLAMA_CURL             OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS      OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS      OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER     OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP        OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON     OFF CACHE BOOL "" FORCE)

# OpenMP is not available in the NDK sysroot in a form worth fighting for, and
# llama.cpp's own threadpool handles this workload.
set(GGML_OPENMP            OFF CACHE BOOL "" FORCE)

add_subdirectory(
    ${CMAKE_CURRENT_SOURCE_DIR}/../../../llama.cpp
    ${CMAKE_CURRENT_BINARY_DIR}/llama.cpp
    EXCLUDE_FROM_ALL
)

# ---------------------------------------------------------------------------
# The JNI shim
# ---------------------------------------------------------------------------

add_library(loamllama SHARED loam_jni.cpp)

target_link_libraries(loamllama
    PRIVATE llama ggml android log
)

# The CPU backend variants are dlopen'd at runtime rather than linked, so
# nothing here references them and CMake would not otherwise build them.
# Without this the app loads, finds no CPU backend, and fails at model load.
foreach(variant
        android_armv8.0_1
        android_armv8.2_1
        android_armv8.2_2
        android_armv8.6_1)
    if (TARGET ggml-cpu-${variant})
        add_dependencies(loamllama ggml-cpu-${variant})
    endif()
endforeach()

# armv9 variants are deliberately absent from that list. They build, they load,
# and they are 1.79x SLOWER than armv8.6_1 on a Tensor G3, because its SVE is
# 128-bit and the SVE2 kernels lose to well-tuned NEON+i8mm. ggml's selector
# picks by feature count and has no way to know. Excluding them here means
# dispatch falls back to armv8.6_1; a device with genuinely fast SVE2 loses
# some speed rather than breaking. The APK also excludes them, in
# llama/build.gradle.kts — belt and braces, since the two mechanisms fail
# differently.
