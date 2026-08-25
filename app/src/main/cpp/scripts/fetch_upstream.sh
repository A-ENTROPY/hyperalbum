#!/usr/bin/env bash
# =============================================================================
# fetch_upstream.sh — download prebuilt libavif + libjxl for Android.
# =============================================================================
# This script is the build-time bridge between "we want real AVIF / JXL
# decoding" and "we have a .so we can drop into jniLibs/<abi>/". Run it
# on Linux/macOS with the Android NDK installed and `cmake`/`ninja` on PATH.
#
# It builds each library from upstream source for the four ABIs we ship
# (arm64-v8a, armeabi-v7a, x86_64, x86) at API 26, then copies the resulting
# .so files into `app/src/main/jniLibs/<abi>/`.
#
# Usage:
#   NDK=/path/to/android-ndk-r27 ./fetch_upstream.sh
#   ./gradlew :app:assembleRelease -PnativeDecoder=real
# =============================================================================
set -euo pipefail

NDK=${NDK:-${ANDROID_NDK_ROOT:-${ANDROID_NDK_HOME:-}}}
if [[ -z "$NDK" || ! -d "$NDK" ]]; then
    echo "ERROR: NDK not found. Set NDK=/path/to/android-ndk-r27 and re-run." >&2
    exit 1
fi

# Abis we ship — must match app/build.gradle.kts abiFilters.
ABIS=(arm64-v8a armeabi-v7a x86_64 x86)
MIN_SDK=26

WORKDIR=$(mktemp -d)
trap 'rm -rf "$WORKDIR"' EXIT

build_one() {
    local NAME=$1 URL=$2 SRC_DIR=$3
    echo ">>> Cloning $NAME from $URL"
    git clone --depth=1 --branch="$SRC_DIR" "$URL" "$WORKDIR/$NAME"
    for ABI in "${ABIS[@]}"; do
        echo ">>> Building $NAME for $ABI"
        cmake -B "$WORKDIR/$NAME/build-$ABI" -S "$WORKDIR/$NAME" -G Ninja \
            -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
            -DANDROID_ABI=$ABI -DANDROID_PLATFORM=android-$MIN_SDK \
            -DCMAKE_BUILD_TYPE=Release \
            -DBUILD_SHARED_LIBS=ON \
            -DAVIF_BUILD_TESTS=OFF -DAVIF_BUILD_APPS=OFF \
            -DAVIF_ENABLE_GTEST=OFF -DAVIF_CODEC_AOM=ON \
            -DJPEGXL_ENABLE_BENCHMARK=OFF \
            -DJPEGXL_ENABLE_VIEWERS=OFF -DJPEGXL_ENABLE_SJPEG=OFF \
            -DJPEGXL_ENABLE_SKCMS=OFF -DJPEGXL_ENABLE_PLUGINS=OFF \
            -DJPEGXL_ENABLE_TOOLS=OFF
        cmake --build "$WORKDIR/$NAME/build-$ABI" --parallel
        mkdir -p "app/src/main/jniLibs/$ABI"
        if [[ -f "$WORKDIR/$NAME/build-$ABI/lib${NAME}.so" ]]; then
            cp "$WORKDIR/$NAME/build-$ABI/lib${NAME}.so" "app/src/main/jniLibs/$ABI/"
        fi
    done
}

echo "=== Building libavif ==="
build_one avif https://github.com/AOM-UVA/libavif.git main

echo "=== Building libjxl ==="
build_one jxl https://github.com/AOM-UVA/libjxl.git main

echo
echo "=== Done ==="
echo "Dropped into app/src/main/jniLibs/<abi>/libavif.so + libjxl.so"
echo "Next: ./gradlew :app:assembleRelease -PnativeDecoder=real"