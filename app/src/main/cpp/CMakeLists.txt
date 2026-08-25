# SmartVision Native Decoder
# =============================================================================
# Builds `libsmartvision_decoder.so` with optional static linkage of libavif +
# libaom (AV1 decoder). Set ENABLE_REAL_NATIVE_DECODER=ON to enable.
#
# When enabled, place the prebuilt static libraries under
# `app/src/main/jniLibs/<abi>/` (or set -DAVIF_STATIC_LIB / -DLIBAOM_STATIC_LIB
# to absolute paths). The build expects:
#   * libaom.a  (libaom v3.x)
#   * libavif.a (libavif v1.x with aom decoder)
# =============================================================================

cmake_minimum_required(VERSION 3.22.1)
project(smartvision_decoder LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_VISIBILITY_PRESET hidden)
set(CMAKE_C_VISIBILITY_PRESET hidden)
set(CMAKE_VISIBILITY_INLINES_HIDDEN ON)

# Toggle that gates the real libavif / libjxl integration in smartvision_jni.cpp.
# The JNI surface is identical; only the body of the decode functions changes.
option(ENABLE_REAL_NATIVE_DECODER
    "Link the real libaom + libavif static libraries. Requires prebuilt .a files under jniLibs/<abi>/."
    OFF)

# Even when the option is ON, the real decoder is only wired for ABIs that
# have a vendored .a triplet. Other ABIs fall back to the stub path automatically.
set(_ABI_HAS_DECODER OFF)
if(EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libaom.a"
   AND EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libavif.a"
   AND EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libjxl_dec.a")
    set(_ABI_HAS_DECODER ON)
endif()
if(ENABLE_REAL_NATIVE_DECODER AND NOT _ABI_HAS_DECODER)
    message(STATUS
        "SmartVision native decoder: no vendored libs for ${ANDROID_ABI}, falling back to stub")
    set(ENABLE_REAL_NATIVE_DECODER OFF)
endif()

# Additional path for find_package (libjxl config, highways, etc). Populated
# by scripts/build_native_decoder.ps1 when building the vendored deps.
set(CMAKE_PREFIX_PATH "" CACHE PATH "Search path for vendored CMake packages")

# Optional override paths (relative to the project root or absolute).
set(LIBAOM_STATIC_LIB "" CACHE FILEPATH "Path to libaom.a")
set(AVIF_STATIC_LIB "" CACHE FILEPATH "Path to libavif.a")
set(JXL_STATIC_LIB "" CACHE FILEPATH "Path to libjxl.a")
set(DAV1D_STATIC_LIB "" CACHE FILEPATH "Path to libdav1d.a")
set(JXL_THREADS_STATIC_LIB "" CACHE FILEPATH "Path to libjxl_threads.a")
set(HWY_STATIC_LIB "" CACHE FILEPATH "Path to libhwy.a")
set(BROTLI_COMMON_STATIC_LIB "" CACHE FILEPATH "Path to libbrotlicommon.a")
set(BROTLI_DEC_STATIC_LIB "" CACHE FILEPATH "Path to libbrotlidec.a")
set(BROTLI_ENC_STATIC_LIB "" CACHE FILEPATH "Path to libbrotlienc.a")

add_compile_options(
    -Wall
    -Wno-unused-function
    -Wno-unused-parameter
    -fno-rtti
    -fno-exceptions
    -ffunction-sections
    -fdata-sections
)

add_link_options(
    -Wl,--gc-sections
    -Wl,--exclude-libs,ALL
)

# -----------------------------------------------------------------------------
# smartvision_decoder shared library
# -----------------------------------------------------------------------------
add_library(smartvision_decoder SHARED
    stubs/smartvision_jni.cpp
    stubs/decoder_stub.cpp
    stubs/jxl_to_jpeg.cpp
    stubs/jxl_to_raw.cpp
    stubs/avif_to_raw.cpp
)

target_include_directories(smartvision_decoder PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/stubs
)

# -----------------------------------------------------------------------------
# Real libavif + libaom + libjxl integration.
# -----------------------------------------------------------------------------
if(ENABLE_REAL_NATIVE_DECODER)
    if(NOT LIBAOM_STATIC_LIB OR NOT AVIF_STATIC_LIB OR NOT JXL_STATIC_LIB)
        # Fall back to jniLibs/<abi>/ if the user didn't override the paths.
        set(_jniRoot "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}")
        if(NOT LIBAOM_STATIC_LIB)
            set(LIBAOM_STATIC_LIB "${_jniRoot}/libaom.a")
        endif()
        if(NOT AVIF_STATIC_LIB)
            set(AVIF_STATIC_LIB "${_jniRoot}/libavif.a")
        endif()
        if(NOT JXL_STATIC_LIB)
            set(JXL_STATIC_LIB "${_jniRoot}/libjxl_dec.a")
        endif()
        if(NOT JXL_THREADS_STATIC_LIB)
            set(JXL_THREADS_STATIC_LIB "${_jniRoot}/libjxl_threads.a")
        endif()
        if(NOT HWY_STATIC_LIB)
            set(HWY_STATIC_LIB "${_jniRoot}/libhwy.a")
        endif()
        if(NOT BROTLI_COMMON_STATIC_LIB)
            set(BROTLI_COMMON_STATIC_LIB "${_jniRoot}/libbrotlicommon.a")
        endif()
        if(NOT BROTLI_DEC_STATIC_LIB)
            set(BROTLI_DEC_STATIC_LIB "${_jniRoot}/libbrotlidec.a")
        endif()
        if(NOT BROTLI_ENC_STATIC_LIB)
            set(BROTLI_ENC_STATIC_LIB "${_jniRoot}/libbrotlienc.a")
        endif()
        if(NOT DAV1D_STATIC_LIB)
            set(DAV1D_STATIC_LIB "${_jniRoot}/libdav1d.a")
        endif()
    endif()

    if(NOT EXISTS "${LIBAOM_STATIC_LIB}")
        message(FATAL_ERROR
            "libaom.a not found at ${LIBAOM_STATIC_LIB}. "
            "Run scripts/build_native_decoder.ps1 first, or set -DLIBAOM_STATIC_LIB=... .")
    endif()
    if(NOT EXISTS "${AVIF_STATIC_LIB}")
        message(FATAL_ERROR
            "libavif.a not found at ${AVIF_STATIC_LIB}. "
            "Run scripts/build_native_decoder.ps1 first, or set -DAVIF_STATIC_LIB=... .")
    endif()
    if(NOT EXISTS "${JXL_STATIC_LIB}")
        message(FATAL_ERROR
            "libjxl.a not found at ${JXL_STATIC_LIB}. "
            "Run scripts/build_native_decoder.ps1 first, or set -DJXL_STATIC_LIB=... .")
    endif()

    # All headers live alongside the static libs in jniLibs/<abi>/.
    get_filename_component(_libRoot "${AVIF_STATIC_LIB}" DIRECTORY)

    add_library(aom STATIC IMPORTED GLOBAL)
    set_target_properties(aom PROPERTIES
        IMPORTED_LOCATION "${LIBAOM_STATIC_LIB}")
    target_include_directories(aom INTERFACE "${_libRoot}")
    target_link_libraries(smartvision_decoder PRIVATE aom)

    add_library(avif STATIC IMPORTED GLOBAL)
    set_target_properties(avif PROPERTIES
        IMPORTED_LOCATION "${AVIF_STATIC_LIB}")
    target_include_directories(avif INTERFACE "${_libRoot}")
    target_link_libraries(smartvision_decoder PRIVATE avif)

    # dav1d (AV1 soft decoder, ≈2-3× faster than aom). libavif resolves it at
    # runtime via dlsym once codecChoice = AVIF_CODEC_CHOICE_DAV1D; must be
    # linked after avif so its symbols stay in the dynamic table. See
    # scripts/build_native_decoder.ps1 for how libdav1d.a is cross-built.
    add_library(dav1d STATIC IMPORTED GLOBAL)
    set_target_properties(dav1d PROPERTIES
        IMPORTED_LOCATION "${DAV1D_STATIC_LIB}")
    target_include_directories(dav1d INTERFACE "${_libRoot}")
    target_link_libraries(smartvision_decoder PRIVATE dav1d)

    add_library(jxl STATIC IMPORTED GLOBAL)
    set_target_properties(jxl PROPERTIES
        IMPORTED_LOCATION "${JXL_STATIC_LIB}")
    if(EXISTS "${JXL_THREADS_STATIC_LIB}")
        add_library(jxl_threads STATIC IMPORTED GLOBAL)
        set_target_properties(jxl_threads PROPERTIES
            IMPORTED_LOCATION "${JXL_THREADS_STATIC_LIB}")
    endif()
    target_include_directories(jxl INTERFACE "${_libRoot}")

    add_library(hwy STATIC IMPORTED GLOBAL)
    set_target_properties(hwy PROPERTIES
        IMPORTED_LOCATION "${HWY_STATIC_LIB}")
    target_include_directories(hwy INTERFACE "${_libRoot}")

    add_library(brotlicommon STATIC IMPORTED GLOBAL)
    set_target_properties(brotlicommon PROPERTIES
        IMPORTED_LOCATION "${BROTLI_COMMON_STATIC_LIB}")
    target_include_directories(brotlicommon INTERFACE "${_libRoot}")

    add_library(brotlidec STATIC IMPORTED GLOBAL)
    set_target_properties(brotlidec PROPERTIES
        IMPORTED_LOCATION "${BROTLI_DEC_STATIC_LIB}")
    target_include_directories(brotlidec INTERFACE "${_libRoot}")

    if(EXISTS "${BROTLI_ENC_STATIC_LIB}")
        add_library(brotlienc STATIC IMPORTED GLOBAL)
        set_target_properties(brotlienc PROPERTIES
            IMPORTED_LOCATION "${BROTLI_ENC_STATIC_LIB}")
        target_include_directories(brotlienc INTERFACE "${_libRoot}")
    endif()

    target_link_libraries(smartvision_decoder PRIVATE
        jxl jxl_threads hwy brotlicommon brotlidec brotlienc)

    # libjpeg-turbo static library for streaming JXL→JPEG transcoding.
    # Built from libjpeg-turbo 3.0.4 via scripts/build_native_decoder.ps1.
    # Used to encode 1:1 JPEG cache files from libjxl scanline callbacks
    # without ever materializing the full-resolution Bitmap in memory
    # (a 32K source would be a 3GB ARGB_8888 Bitmap → OOM).
    add_library(jpeg STATIC IMPORTED GLOBAL)
    set_target_properties(jpeg PROPERTIES
        IMPORTED_LOCATION "${_libRoot}/libjpeg.a")
    target_include_directories(jpeg INTERFACE "${_libRoot}")
    target_link_libraries(smartvision_decoder PRIVATE jpeg)

    target_compile_definitions(smartvision_decoder PRIVATE
        SMARTVISION_REAL_AVIF_DECODER=1
        SMARTVISION_REAL_JXL_DECODER=1)

    message(STATUS "SmartVision native decoder: linking real libavif + libaom + libjxl")
    message(STATUS "  libaom: ${LIBAOM_STATIC_LIB}")
    message(STATUS "  libavif: ${AVIF_STATIC_LIB}")
    message(STATUS "  libjxl:  ${JXL_STATIC_LIB}")
    message(STATUS "  libhwy:  ${HWY_STATIC_LIB}")
    message(STATUS "  brotli:  ${BROTLI_COMMON_STATIC_LIB}")
else()
    message(STATUS
        "SmartVision native decoder: stub mode (set ENABLE_REAL_NATIVE_DECODER=ON to enable real decoders)")
endif()

find_library(log-lib log)
find_library(jnigraphics-lib jnigraphics)
target_link_libraries(smartvision_decoder PRIVATE ${log-lib} ${jnigraphics-lib} android)