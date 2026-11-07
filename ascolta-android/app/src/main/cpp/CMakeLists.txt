cmake_minimum_required(VERSION 3.22.1)
project(ascolta_whisper LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Speech recognition is unusably slow without optimizations, so optimize
# even in debug builds. (These flags come after the build-type flags, so they win.)
add_compile_options(-O3 -DNDEBUG)

# Use a local checkout in ascolta-android/third_party/whisper.cpp when present
# (useful offline); otherwise fetch the pinned release from GitHub.
set(WHISPER_CPP_TAG v1.7.6)
set(LOCAL_WHISPER_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/whisper.cpp)

if (EXISTS ${LOCAL_WHISPER_DIR}/CMakeLists.txt)
    set(WHISPER_SOURCE_DIR ${LOCAL_WHISPER_DIR})
else ()
    include(FetchContent)
    FetchContent_Declare(
        whisper_cpp
        GIT_REPOSITORY https://github.com/ggml-org/whisper.cpp.git
        GIT_TAG ${WHISPER_CPP_TAG}
        GIT_SHALLOW TRUE
    )
    FetchContent_GetProperties(whisper_cpp)
    if (NOT whisper_cpp_POPULATED)
        FetchContent_Populate(whisper_cpp)
    endif ()
    set(WHISPER_SOURCE_DIR ${whisper_cpp_SOURCE_DIR})
endif ()

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)

if (ANDROID_ABI STREQUAL "arm64-v8a")
    # ARMv8.2 with half-precision math: every mainstream phone since ~2018.
    set(GGML_CPU_ARM_ARCH "armv8.2-a+fp16" CACHE STRING "" FORCE)
endif ()

add_subdirectory(${WHISPER_SOURCE_DIR} whisper_build EXCLUDE_FROM_ALL)

add_library(ascolta_whisper SHARED whisper_jni.cpp)
target_link_libraries(ascolta_whisper PRIVATE whisper android log)
target_link_options(ascolta_whisper PRIVATE -Wl,--gc-sections)
