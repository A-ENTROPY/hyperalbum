# SmartVision Native Decoder Build Script (PowerShell)
# =====================================================
# Builds libaom (AV1 decoder) + libavif statically for arm64-v8a Android,
# then links them into libsmartvision_decoder.so.
#
# Requirements:
#   * Android NDK r27 (or r26) at $env:NDK or default SDK location
#   * Android SDK cmake at $env:CMAKE or $Sdk\cmake\3.22.1\bin
#   * Internet access for first-time source downloads
#
# Outputs:
#   * $Env:TEMP\decoder_build\libaom-<v>\libaom.a
#   * $Env:TEMP\decoder_build\libavif-<v>\avif.lib
#   * $ProjectRoot\app\src\main\jniLibs\arm64-v8a\libsmartvision_decoder.so
#
# Usage:
#   .\scripts\build_native_decoder.ps1
#   .\scripts\build_native_decoder.ps1 -SkipAom   # reuse a cached aom build
#   .\scripts\build_native_decoder.ps1 -SkipAvif  # only rebuild the wrapper

[CmdletBinding()]
param(
    [switch]$SkipAom,
    [switch]$SkipAvif,
    [switch]$SkipHwy,
    [switch]$SkipBrotli,
    [switch]$SkipJxl,
    [string]$Abi = "arm64-v8a"
)

$ErrorActionPreference = "Stop"

# -------------------------------------------------------------------- paths
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Definition
# ScriptDir = ...\app\src\main\cpp\scripts → project root five levels up
$ProjectRoot = Resolve-Path (Join-Path $ScriptDir "..\..\..\..\..")
$Ndk = $env:NDK
if (-not $Ndk) {
    $Ndk = "C:\Users\AMDDMA\AppData\Local\Android\Sdk\ndk\27.0.12077973"
}
if (-not (Test-Path $Ndk)) {
    throw "Android NDK not found at $Ndk. Set `$env:NDK first."
}
$Cmake = $env:CMAKE
if (-not $Cmake) {
    $Cmake = "C:\Users\AMDDMA\AppData\Local\Android\Sdk\cmake\3.22.1\bin\cmake.exe"
}
if (-not (Test-Path $Cmake)) {
    throw "cmake.exe not found at $Cmake. Set `$env:CMAKE first."
}
$Tar = "C:\Windows\System32\tar.exe"
if (-not (Test-Path $Tar)) {
    throw "System tar.exe not found (expected in C:\Windows\System32)"
}
$Ninja = Join-Path (Split-Path -Parent $Cmake) "ninja.exe"

$WorkDir = Join-Path $env:TEMP "decoder_build"
if (-not (Test-Path $WorkDir)) {
    New-Item -ItemType Directory -Path $WorkDir -Force | Out-Null
}

# -------------------------------------------------------------------- helpers
function Download-Archive {
    param([string]$Url, [string]$Out)
    if (Test-Path $Out) {
        Write-Host "  [cached] $Out"
        return
    }
    Write-Host "  [download] $Url"
    Invoke-WebRequest -Uri $Url -OutFile $Out -TimeoutSec 120
}

function Extract-Archive {
    param([string]$Archive, [string]$Dest)
    if (-not (Test-Path $Dest)) { New-Item -ItemType Directory -Path $Dest -Force | Out-Null }
    & $Tar -xzf $Archive -C $Dest
}

# -------------------------------------------------------------------- AOM
$AomVersion = "3.11.0"
$AomDir = Join-Path $WorkDir "libaom-$AomVersion"
$AomBuild = Join-Path $WorkDir "aom_build"
$AomArchive = Join-Path $WorkDir "libaom-$AomVersion.tar.gz"

if (-not $SkipAom) {
    if (-not (Test-Path $AomDir)) {
        Download-Archive -Url "https://storage.googleapis.com/aom-releases/libaom-$AomVersion.tar.gz" -Out $AomArchive
        Extract-Archive -Archive $AomArchive -Dest $WorkDir
    }
    if (Test-Path $AomBuild) { Remove-Item -Recurse -Force $AomBuild }
    New-Item -ItemType Directory -Path $AomBuild -Force | Out-Null

    Write-Host "=== Configuring libaom for $Abi ==="
    & $Cmake `
        -S $AomDir `
        -B $AomBuild `
        -G Ninja `
        -DCMAKE_MAKE_PROGRAM="$Ninja" `
        -DCMAKE_TOOLCHAIN_FILE="$Ndk\build\cmake\android.toolchain.cmake" `
        -DCMAKE_SYSTEM_NAME=Android `
        -DCMAKE_ANDROID_NDK="$Ndk" `
        -DANDROID_ABI="$Abi" `
        -DANDROID_PLATFORM="android-26" `
        -DCMAKE_BUILD_TYPE=Release `
        -DCONFIG_AV1_ENCODER=0 `
        -DCONFIG_AV1_DECODER=1 `
        -DCONFIG_INSPECTION=0 `
        -DCONFIG_MULTITHREAD=1 `
        -DCONFIG_UNIT_TESTS=0 `
        -DCONFIG_TEST_DATA=0 `
        -DENABLE_EXAMPLES=0 `
        -DENABLE_TOOLS=0 `
        -DENABLE_DOCS=0 `
        -DBUILD_SHARED_LIBS=OFF
    if ($LASTEXITCODE -ne 0) { throw "aom configure failed (exit $LASTEXITCODE)" }

    Write-Host "=== Building libaom ($Abi) ==="
    & $Cmake --build $AomBuild --config Release
    if ($LASTEXITCODE -ne 0) { throw "aom build failed (exit $LASTEXITCODE)" }
}

# Locate aom output. When -SkipAom is set the aom build dir is gone, so fall
# back to the previously-copied static lib under jniLibs/<abi>/.
$AomLib = Get-ChildItem -Path $AomBuild -Recurse -Filter "libaom.a" -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $AomLib) {
    $AomLib = Get-ChildItem -Path $AomBuild -Recurse -Filter "*.a" -ErrorAction SilentlyContinue | Where-Object { $_.Name -like "*aom*" } | Select-Object -First 1
}
if (-not $AomLib) {
    $JniFallback = Join-Path $ProjectRoot "app\src\main\jniLibs\$Abi\libaom.a"
    if (Test-Path $JniFallback) {
        $AomLib = Get-Item $JniFallback
        Write-Host "  [cached-skip] aom lib: $($AomLib.FullName) ($([math]::Round($AomLib.Length/1MB,2)) MB)"
    }
}
if (-not $AomLib) {
    throw "libaom.a not found under $AomBuild after build (run without -SkipAom first)"
}
if (-not $AomLib.FullName.EndsWith("libaom.a")) {
    Write-Host "  aom lib: $($AomLib.FullName) ($([math]::Round($AomLib.Length/1MB,2)) MB)"
}

# -------------------------------------------------------------------- libavif
$LibavifDir = Join-Path $WorkDir "libavif-main"
# jniLibs/<abi>/ holds the cross-built static libs (incl. libdav1d.a).
$JniTarget = Join-Path $ProjectRoot "app\src\main\jniLibs\$Abi"
$LibavifBuild = Join-Path $WorkDir "libavif_build"
$LibavifArchive = Join-Path $WorkDir "libavif.tar.gz"

if (-not $SkipAvif) {
    if (-not (Test-Path $LibavifDir)) {
        Download-Archive -Url "https://codeload.github.com/AOMediaCodec/libavif/tar.gz/refs/heads/main" -Out $LibavifArchive
        Extract-Archive -Archive $LibavifArchive -Dest $WorkDir
    }
    if (Test-Path $LibavifBuild) { Remove-Item -Recurse -Force $LibavifBuild }
    New-Item -ItemType Directory -Path $LibavifBuild -Force | Out-Null

    Write-Host "=== Configuring libavif for $Abi ==="
    & $Cmake `
        -S $LibavifDir `
        -B $LibavifBuild `
        -G Ninja `
        -DCMAKE_MAKE_PROGRAM="$Ninja" `
        -DCMAKE_TOOLCHAIN_FILE="$Ndk\build\cmake\android.toolchain.cmake" `
        -DCMAKE_SYSTEM_NAME=Android `
        -DCMAKE_ANDROID_NDK="$Ndk" `
        -DANDROID_ABI="$Abi" `
        -DANDROID_PLATFORM="android-26" `
        -DCMAKE_BUILD_TYPE=Release `
        -DAVIF_LIBYUV=OFF `
        -DAVIF_BUILD_TESTS=OFF `
        -DAVIF_BUILD_APPS=OFF `
        -DAVIF_ENABLE_GTEST=OFF `
        -DAVIF_CODEC_AOM=ON `
        -DAOM_TARGET="-DAOM_TARGET_CPU=generic" `
        -DAVIF_CODEC_DAV1D=SYSTEM `
        -DDAV1D_INCLUDE_DIR="$JniTarget" `
        -DDAV1D_LIBRARY="$JniTarget\libdav1d.a" `
        -DAVIF_LOCAL=$false `
        -DAVIF_BUILD_SHARED_LIBS=OFF `
        -DBUILD_SHARED_LIBS=OFF `
        -DCMAKE_INCLUDE_PATH="$($AomLib.DirectoryName);$($AomLib.DirectoryName)\config" `
        -DCMAKE_LIBRARY_PATH="$AomLib.DirectoryName"
    if ($LASTEXITCODE -ne 0) { throw "libavif configure failed (exit $LASTEXITCODE)" }

    Write-Host "=== Building libavif ($Abi) ==="
    & $Cmake --build $LibavifBuild --config Release
    if ($LASTEXITCODE -ne 0) { throw "libavif build failed (exit $LASTEXITCODE)" }
}

# Locate avif output (Windows emits avif.lib, Android emits libavif.a)
$AvifLib = Get-ChildItem -Path $LibavifBuild -Recurse -Include "avif.lib","libavif.a" -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $AvifLib) {
    $AvifLib = Get-ChildItem -Path $LibavifBuild -Recurse -Filter "*.a" -ErrorAction SilentlyContinue | Where-Object { $_.Name -like "*avif*" } | Select-Object -First 1
}
if (-not $AvifLib) {
    throw "avif static lib not found under $LibavifBuild after build"
}
Write-Host "  avif lib: $($AvifLib.FullName) ($([math]::Round($AvifLib.Length/1MB,2)) MB)"

# -------------------------------------------------------------------- highway (SIMD, required by libjxl)
$HwyVersion = "1.2.0"
$HwyDir = Join-Path $WorkDir "highway-$HwyVersion"
$HwyBuild = Join-Path $WorkDir "hwy_build"
$HwyStaging = Join-Path $WorkDir "hwy_staging"
$HwyArchive = Join-Path $WorkDir "highway-$HwyVersion.tar.gz"

if (-not $SkipHwy) {
    if (-not (Test-Path $HwyDir)) {
        Download-Archive -Url "https://github.com/google/highway/archive/refs/tags/$HwyVersion.tar.gz" -Out $HwyArchive
        Extract-Archive -Archive $HwyArchive -Dest $WorkDir
        if (-not (Test-Path $HwyDir)) {
            $found = Get-ChildItem -Path $WorkDir -Directory | Where-Object { $_.Name -like "highway*" } | Select-Object -First 1
            if ($found) { Rename-Item -Path $found.FullName -NewName "highway-$HwyVersion" }
        }
    }
    if (Test-Path $HwyBuild) { Remove-Item -Recurse -Force $HwyBuild }
    New-Item -ItemType Directory -Path $HwyBuild -Force | Out-Null

    Write-Host "=== Configuring highway for $Abi ==="
    & $Cmake `
        -S $HwyDir `
        -B $HwyBuild `
        -G Ninja `
        -DCMAKE_MAKE_PROGRAM="$Ninja" `
        -DCMAKE_TOOLCHAIN_FILE="$Ndk\build\cmake\android.toolchain.cmake" `
        -DCMAKE_SYSTEM_NAME=Android `
        -DCMAKE_ANDROID_NDK="$Ndk" `
        -DANDROID_ABI="$Abi" `
        -DANDROID_PLATFORM="android-26" `
        -DCMAKE_BUILD_TYPE=Release `
        -DCMAKE_INSTALL_PREFIX="$HwyStaging" `
        -DHWY_ENABLE_CONTRIB=OFF `
        -DHWY_ENABLE_EXAMPLES=OFF `
        -DHWY_ENABLE_TESTS=OFF `
        -DBUILD_SHARED_LIBS=OFF `
        -DHWY_FORCE_STATIC=ON
    if ($LASTEXITCODE -ne 0) { throw "highway configure failed (exit $LASTEXITCODE)" }

    Write-Host "=== Building highway ($Abi) ==="
    & $Cmake --build $HwyBuild --config Release
    if ($LASTEXITCODE -ne 0) { throw "highway build failed (exit $LASTEXITCODE)" }

    Write-Host "=== Installing highway ($Abi) ==="
    & $Cmake --install $HwyBuild --config Release
    if ($LASTEXITCODE -ne 0) { throw "highway install failed (exit $LASTEXITCODE)" }
}

$HwyLib = Get-ChildItem -Path $HwyBuild -Recurse -Filter "libhwy.a" -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $HwyLib) { throw "libhwy.a not found under $HwyBuild after build" }
Write-Host "  hwy lib: $($HwyLib.FullName) ($([math]::Round($HwyLib.Length/1MB,2)) MB)"

# -------------------------------------------------------------------- brotli (compression, required by libjxl)
$BrotliVersion = "1.1.0"
$BrotliDir = Join-Path $WorkDir "brotli-$BrotliVersion"
$BrotliBuild = Join-Path $WorkDir "brotli_build"
$BrotliStaging = Join-Path $WorkDir "brotli_staging"
$BrotliArchive = Join-Path $WorkDir "brotli-$BrotliVersion.tar.gz"

if (-not $SkipBrotli) {
    if (-not (Test-Path $BrotliDir)) {
        Download-Archive -Url "https://github.com/google/brotli/archive/refs/tags/v$BrotliVersion.tar.gz" -Out $BrotliArchive
        Extract-Archive -Archive $BrotliArchive -Dest $WorkDir
        if (-not (Test-Path $BrotliDir)) {
            $found = Get-ChildItem -Path $WorkDir -Directory | Where-Object { $_.Name -like "brotli*" } | Select-Object -First 1
            if ($found) { Rename-Item -Path $found.FullName -NewName "brotli-$BrotliVersion" }
        }
    }
    if (Test-Path $BrotliBuild) { Remove-Item -Recurse -Force $BrotliBuild }
    New-Item -ItemType Directory -Path $BrotliBuild -Force | Out-Null

    Write-Host "=== Configuring brotli for $Abi ==="
    & $Cmake `
        -S $BrotliDir `
        -B $BrotliBuild `
        -G Ninja `
        -DCMAKE_MAKE_PROGRAM="$Ninja" `
        -DCMAKE_TOOLCHAIN_FILE="$Ndk\build\cmake\android.toolchain.cmake" `
        -DCMAKE_SYSTEM_NAME=Android `
        -DCMAKE_ANDROID_NDK="$Ndk" `
        -DANDROID_ABI="$Abi" `
        -DANDROID_PLATFORM="android-26" `
        -DCMAKE_BUILD_TYPE=Release `
        -DCMAKE_INSTALL_PREFIX="$BrotliStaging" `
        -DBUILD_SHARED_LIBS=OFF `
        -DBROTLI_DISABLE_TESTS=ON
    if ($LASTEXITCODE -ne 0) { throw "brotli configure failed (exit $LASTEXITCODE)" }

    Write-Host "=== Building brotli ($Abi) ==="
    & $Cmake --build $BrotliBuild --config Release
    if ($LASTEXITCODE -ne 0) { throw "brotli build failed (exit $LASTEXITCODE)" }

    Write-Host "=== Installing brotli ($Abi) ==="
    & $Cmake --install $BrotliBuild --config Release
    if ($LASTEXITCODE -ne 0) { throw "brotli install failed (exit $LASTEXITCODE)" }
}

$BrotliCommonLib = Get-ChildItem -Path $BrotliBuild -Recurse -Filter "libbrotlicommon.a" -ErrorAction SilentlyContinue | Select-Object -First 1
$BrotliDecLib = Get-ChildItem -Path $BrotliBuild -Recurse -Filter "libbrotlidec.a" -ErrorAction SilentlyContinue | Select-Object -First 1
$BrotliEncLib = Get-ChildItem -Path $BrotliBuild -Recurse -Filter "libbrotlienc.a" -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $BrotliCommonLib) { throw "libbrotlicommon.a not found" }
if (-not $BrotliDecLib)    { throw "libbrotlidec.a not found" }
Write-Host "  brotli common: $($BrotliCommonLib.FullName) ($([math]::Round($BrotliCommonLib.Length/1MB,2)) MB)"
Write-Host "  brotli dec:    $($BrotliDecLib.FullName) ($([math]::Round($BrotliDecLib.Length/1MB,2)) MB)"
if ($BrotliEncLib) {
    Write-Host "  brotli enc:    $($BrotliEncLib.FullName) ($([math]::Round($BrotliEncLib.Length/1MB,2)) MB)"
}

# -------------------------------------------------------------------- libjxl
$JxlVersion = "0.12.0"
$JxlDir = Join-Path $WorkDir "libjxl-$JxlVersion"
$JxlBuild = Join-Path $WorkDir "jxl_build"
$JxlArchive = Join-Path $WorkDir "libjxl-$JxlVersion.tar.gz"

if (-not $SkipJxl) {
    if (-not (Test-Path $JxlDir)) {
        Download-Archive -Url "https://github.com/libjxl/libjxl/archive/refs/tags/v$JxlVersion.tar.gz" -Out $JxlArchive
        Extract-Archive -Archive $JxlArchive -Dest $WorkDir
        if (-not (Test-Path $JxlDir)) {
            $found = Get-ChildItem -Path $WorkDir -Directory | Where-Object { $_.Name -like "libjxl*" } | Select-Object -First 1
            if ($found) { Rename-Item -Path $found.FullName -NewName "libjxl-$JxlVersion" }
        }
    }
    if (Test-Path $JxlBuild) { Remove-Item -Recurse -Force $JxlBuild }
    New-Item -ItemType Directory -Path $JxlBuild -Force | Out-Null

    # libjxl uses third_party/highway + third_party/brotli when they exist.
    # If they're empty (no submodule init), symlink our prebuilt ones.
    $JxlThirdParty = Join-Path $JxlDir "third_party"
    $JxlHwyTarget = Join-Path $JxlThirdParty "highway"
    $JxlBrotliTarget = Join-Path $JxlThirdParty "brotli"
    if (-not (Test-Path (Join-Path $JxlHwyTarget "CMakeLists.txt"))) {
        if (Test-Path $JxlHwyTarget) { Remove-Item -Recurse -Force $JxlHwyTarget }
        New-Item -ItemType Junction -Path $JxlThirdParty -Name "highway" -Target $HwyDir -Force | Out-Null
    }
    if (-not (Test-Path (Join-Path $JxlBrotliTarget "CMakeLists.txt"))) {
        if (Test-Path $JxlBrotliTarget) { Remove-Item -Recurse -Force $JxlBrotliTarget }
        New-Item -ItemType Junction -Path $JxlThirdParty -Name "brotli" -Target $BrotliDir -Force | Out-Null
    }

    Write-Host "=== Configuring libjxl for $Abi ==="
    & $Cmake `
        -S $JxlDir `
        -B $JxlBuild `
        -G Ninja `
        -DCMAKE_MAKE_PROGRAM="$Ninja" `
        -DCMAKE_TOOLCHAIN_FILE="$Ndk\build\cmake\android.toolchain.cmake" `
        -DCMAKE_SYSTEM_NAME=Android `
        -DCMAKE_ANDROID_NDK="$Ndk" `
        -DANDROID_ABI="$Abi" `
        -DANDROID_PLATFORM="android-26" `
        -DCMAKE_BUILD_TYPE=Release `
        -DJPEGXL_ENABLE_BROTLI=ON `
        -DBUILD_SHARED_LIBS=OFF `
        -DBUILD_TESTING=OFF `
        -DJPEGXL_ENABLE_TOOLS=OFF `
        -DJPEGXL_ENABLE_EXAMPLES=OFF `
        -DJPEGXL_ENABLE_BENCHMARK=OFF `
        -DJPEGXL_ENABLE_SJPEG=OFF `
        -DJPEGXL_ENABLE_OPENEXR=OFF `
        -DJPEGXL_ENABLE_SKCMS=ON `
        -DJPEGXL_BUNDLE_LIBPNG=OFF `
        -DJPEGXL_ENABLE_MANPAGES=OFF `
        -DJPEGXL_ENABLE_DOXYGEN=OFF `
        -DJPEGXL_ENABLE_PLUGINS=OFF `
        -DJPEGXL_ENABLE_DEVTOOLS=OFF `
        -DJPEGXL_ENABLE_JNI=OFF `
        -DJPEGXL_STATIC=ON `
        -DCMAKE_INCLUDE_PATH="$HwyStaging\include;$BrotliStaging\include" `
        -DCMAKE_LIBRARY_PATH="$HwyStaging\lib;$BrotliStaging\lib"
    if ($LASTEXITCODE -ne 0) { throw "libjxl configure failed (exit $LASTEXITCODE)" }

    Write-Host "=== Building libjxl ($Abi) ==="
    & $Cmake --build $JxlBuild --config Release
    if ($LASTEXITCODE -ne 0) { throw "libjxl build failed (exit $LASTEXITCODE)" }
}

$JxlLib = Get-ChildItem -Path $JxlBuild -Recurse -Filter "libjxl_dec.a" -ErrorAction SilentlyContinue | Select-Object -First 1
$JxlThreadsLib = Get-ChildItem -Path $JxlBuild -Recurse -Filter "libjxl_threads.a" -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $JxlLib) { throw "libjxl_dec.a not found under $JxlBuild after build" }
Write-Host "  jxl lib:       $($JxlLib.FullName) ($([math]::Round($JxlLib.Length/1MB,2)) MB)"
if ($JxlThreadsLib) {
    Write-Host "  jxl threads:   $($JxlThreadsLib.FullName) ($([math]::Round($JxlThreadsLib.Length/1MB,2)) MB)"
}

# -------------------------------------------------------------------- copy all libs to jniLibs
$JniTarget = Join-Path $ProjectRoot "app\src\main\jniLibs\$Abi"

Write-Host "=== Copying libraries to $JniTarget ==="
Copy-Item -Path $AomLib.FullName -Destination (Join-Path $JniTarget "libaom.a") -Force
Copy-Item -Path $AvifLib.FullName -Destination (Join-Path $JniTarget "libavif.a") -Force
Copy-Item -Path (Join-Path $WorkDir "dav1d-1.4.3\build-android\src\libdav1d.a") -Destination (Join-Path $JniTarget "libdav1d.a") -Force
$Dav1dHdrSrc = Join-Path $WorkDir "dav1d-1.4.3"
if (Test-Path (Join-Path $Dav1dHdrSrc "include\dav1d")) {
    Copy-Item -Path (Join-Path $Dav1dHdrSrc "include\dav1d") -Destination $JniTarget -Recurse -Force
    Copy-Item -Path (Join-Path $Dav1dHdrSrc "build-android\include\dav1d\version.h") -Destination (Join-Path $JniTarget "dav1d") -Force
}
Copy-Item -Path $HwyLib.FullName -Destination (Join-Path $JniTarget "libhwy.a") -Force
Copy-Item -Path $BrotliCommonLib.FullName -Destination (Join-Path $JniTarget "libbrotlicommon.a") -Force
Copy-Item -Path $BrotliDecLib.FullName -Destination (Join-Path $JniTarget "libbrotlidec.a") -Force
if ($BrotliEncLib) {
    Copy-Item -Path $BrotliEncLib.FullName -Destination (Join-Path $JniTarget "libbrotlienc.a") -Force
}
Copy-Item -Path $JxlLib.FullName -Destination (Join-Path $JniTarget "libjxl_dec.a") -Force
if ($JxlThreadsLib) {
    Copy-Item -Path $JxlThreadsLib.FullName -Destination (Join-Path $JniTarget "libjxl_threads.a") -Force
}

# -------------------------------------------------------------------- libjpeg-turbo (streaming JXL→JPEG transcoder)
$JpegVersion = "3.0.4"
$JpegDir = Join-Path $WorkDir "libjpeg-turbo-$JpegVersion"
$JpegBuild = Join-Path $WorkDir "jpeg_build"
$JpegArchive = Join-Path $WorkDir "libjpeg-turbo-$JpegVersion.tar.gz"

if (-not (Test-Path $JpegDir)) {
    Download-Archive -Url "https://github.com/libjpeg-turbo/libjpeg-turbo/archive/refs/tags/$JpegVersion.tar.gz" -Out $JpegArchive
    Extract-Archive -Archive $JpegArchive -Dest $WorkDir
}
if (Test-Path $JpegBuild) { Remove-Item -Recurse -Force $JpegBuild }
New-Item -ItemType Directory -Path $JpegBuild -Force | Out-Null

Write-Host "=== Configuring libjpeg-turbo for $Abi ==="
& $Cmake `
    -S $JpegDir `
    -B $JpegBuild `
    -G Ninja `
    -DCMAKE_MAKE_PROGRAM="$Ninja" `
    -DCMAKE_TOOLCHAIN_FILE="$Ndk\build\cmake\android.toolchain.cmake" `
    -DCMAKE_SYSTEM_NAME=Android `
    -DCMAKE_ANDROID_NDK="$Ndk" `
    -DANDROID_ABI="$Abi" `
    -DANDROID_PLATFORM="android-26" `
    -DCMAKE_BUILD_TYPE=Release `
    -DENABLE_SHARED=OFF `
    -DENABLE_STATIC=ON `
    -DWITH_TURBOJPEG=OFF `
    -DWITH_JAVA=OFF `
    -DWITH_FUZZ=OFF `
    -DWITH_SIMD=ON
if ($LASTEXITCODE -ne 0) { throw "libjpeg-turbo configure failed (exit $LASTEXITCODE)" }

Write-Host "=== Building libjpeg-turbo ($Abi) ==="
& $Cmake --build $JpegBuild --config Release
if ($LASTEXITCODE -ne 0) { throw "libjpeg-turbo build failed (exit $LASTEXITCODE)" }

$JpegLib = Get-ChildItem -Path $JpegBuild -Recurse -Filter "libjpeg.a" -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $JpegLib) { throw "libjpeg.a not found under $JpegBuild after build" }
Write-Host "  jpeg lib:      $($JpegLib.FullName) ($([math]::Round($JpegLib.Length/1MB,2)) MB)"

Copy-Item -Path $JpegLib.FullName -Destination (Join-Path $JniTarget "libjpeg.a") -Force
foreach ($h in @("jpeglib.h","jconfig.h" ,"jmorecfg.h","jerror.h")) {
    $src = Join-Path $JpegDir $h
    if (-not (Test-Path $src)) { $src = Join-Path $JpegBuild $h }
    if (Test-Path $src) { Copy-Item -Path $src -Destination $JniTarget -Force }
}
Write-Host "  [headers] jpeglib.h jconfig.h jmorecfg.h jerror.h"

# copy headers alongside the libs for CMake to find
$JxlHdrSrc = Join-Path $JxlDir "include" "jxl"
$HwyHdrSrc = Join-Path $HwyDir
$BrotliHdrSrc = Join-Path $BrotliDir
if (Test-Path $JxlHdrSrc) {
    Copy-Item -Path $JxlHdrSrc -Destination $JniTarget -Recurse -Force
    Write-Host "  [headers] jxl/"
}
if (Test-Path (Join-Path $HwyHdrSrc "hwy" "highway.h")) {
    Copy-Item -Path (Join-Path $HwyHdrSrc "hwy") -Destination $JniTarget -Recurse -Force
    Write-Host "  [headers] hwy/"
}
if (Test-Path (Join-Path $BrotliHdrSrc "include" "brotli")) {
    Copy-Item -Path (Join-Path $BrotliHdrSrc "include" "brotli") -Destination $JniTarget -Recurse -Force
    Write-Host "  [headers] brotli/"
}

# -------------------------------------------------------------------- summary
Write-Host ""
Write-Host "=== Native decoder build complete ==="
Write-Host "  libaom     : $($AomLib.FullName)"
Write-Host "  libavif    : $($AvifLib.FullName)"
Write-Host "  libhwy     : $($HwyLib.FullName)"
Write-Host "  libbrotli  : $($BrotliCommonLib.DirectoryName)"
Write-Host "  libjxl     : $($JxlLib.FullName)"
Write-Host ""
Write-Host "All libraries copied to $JniTarget"
Write-Host ""
Write-Host "Next: re-run CMake with -DENABLE_REAL_NATIVE_DECODER=ON, then assembleDebug."