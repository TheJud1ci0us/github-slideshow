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

# Build the CPU code several times, each tuned for a generation of phone CPUs
# (ARMv8.0, ARMv8.2 + dot product, + FP16, ARMv8.6 + int8 matrix multiply).
# At runtime ggml loads the fastest variant this phone supports, so recent
# phones get the newest instructions and older phones still work.
set(BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL ON CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS ON CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)

# Graphics chip (GPU): ggml's OpenCL backend, with kernels tuned for Qualcomm Adreno. It is
# built as one more plug-in (libggml-opencl.so) that links against the phone's own OpenCL
# driver (/vendor/lib64/libOpenCL.so). Khronos' headers and ICD loader are only used to
# compile and link it; the loader isn't packaged. On a phone without a usable OpenCL driver
# the plug-in simply doesn't load and everything runs on the CPU.
include(FetchContent)
FetchContent_Declare(opencl_headers
    GIT_REPOSITORY https://github.com/KhronosGroup/OpenCL-Headers.git
    GIT_TAG v2024.10.24
    GIT_SHALLOW TRUE)
FetchContent_Declare(opencl_icd_loader
    GIT_REPOSITORY https://github.com/KhronosGroup/OpenCL-ICD-Loader.git
    GIT_TAG v2024.10.24
    GIT_SHALLOW TRUE)
FetchContent_MakeAvailable(opencl_headers)
set(OPENCL_ICD_LOADER_HEADERS_DIR ${opencl_headers_SOURCE_DIR} CACHE PATH "" FORCE)
set(ENABLE_OPENCL_LAYERINFO OFF CACHE BOOL "" FORCE)
set(BUILD_TESTING OFF CACHE BOOL "" FORCE)
FetchContent_MakeAvailable(opencl_icd_loader)
set(OpenCL_INCLUDE_DIR ${opencl_headers_SOURCE_DIR} CACHE PATH "" FORCE)
set(OpenCL_LIBRARY OpenCL CACHE STRING "" FORCE)
set(GGML_OPENCL ON CACHE BOOL "" FORCE)
set(GGML_OPENCL_USE_ADRENO_KERNELS ON CACHE BOOL "" FORCE)
set(GGML_OPENCL_EMBED_KERNELS ON CACHE BOOL "" FORCE)

add_subdirectory(${WHISPER_SOURCE_DIR} whisper_build)

# whisper.cpp writes the CPU plug-ins to a bin/ folder; move them to where
# Gradle collects the native libraries to package in the APK.
foreach (backend ${GGML_AVAILABLE_BACKENDS})
    set_target_properties(${backend} PROPERTIES LIBRARY_OUTPUT_DIRECTORY ${CMAKE_LIBRARY_OUTPUT_DIRECTORY})
endforeach ()

add_library(ascolta_whisper SHARED whisper_jni.cpp)
target_link_libraries(ascolta_whisper PRIVATE whisper ggml ggml-base android log)
