# Minimal libjxl-only build: hwy + brotli + libjxl (arm64-v8a).
# Reuses staging in %TEMP%\decoder_build. aom/avif/jpeg are NOT rebuilt
# (their .a already live in jniLibs and are unchanged).
#
# Usage:
#   .\build_libjxl_only.ps1
#
# Outputs (copied to jniLibs\arm64-v8a):
#   libjxl_dec.a, libjxl_threads.a
#
# NOTE: SKCMS=OFF -> libjxl builds color mgmt from third_party/lcms (lcms2.16).
#   The google/skcms submodule is an empty stub (no sources in tree), so skcms
#   path is unusable offline.

[CmdletBinding()]
param(
    [switch]$SkipHwy,
    [switch]$SkipBrotli,
    [switch]$SkipJxl,
    [string]$Abi = "arm64-v8a"
)

$ErrorActionPreference = "Stop"

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Definition
$ProjectRoot = Resolve-Path (Join-Path $ScriptDir "..\..\..\..\..")
$Ndk = $env:NDK
if (-not $Ndk) { $Ndk = "C:\Users\AMDDMA\AppData\Local\Android\Sdk\ndk\27.0.12077973" }
$Cmake = $env:CMAKE
if (-not $Cmake) { $Cmake = "C:\Users\AMDDMA\AppData\Local\Android\Sdk\cmake\3.22.1\bin\cmake.exe" }
$Ninja = Join-Path (Split-Path -Parent $Cmake) "ninja.exe"
$WorkDir = Join-Path $env:TEMP "decoder_build"
if (-not (Test-Path $WorkDir)) { New-Item -ItemType Directory -Path $WorkDir -Force | Out-Null }

# ---------------------------------------------------------------- highway
$HwyVersion = "1.2.0"
$HwyDir = Join-Path $WorkDir "highway-$HwyVersion"
$HwyBuild = Join-Path $WorkDir "hwy_build"
$HwyStaging = Join-Path $WorkDir "hwy_staging"

if (-not (Test-Path $HwyDir)) { throw "highway-$HwyVersion not found under $WorkDir (run download first)" }

if (-not $SkipHwy) {
    if (Test-Path $HwyBuild) { Remove-Item -Recurse -Force $HwyBuild }
    New-Item -ItemType Directory -Path $HwyBuild -Force | Out-Null
    Write-Host "=== Configuring highway for $Abi ==="
    & $Cmake -S $HwyDir -B $HwyBuild -G Ninja `
        -DCMAKE_MAKE_PROGRAM="$Ninja" `
        -DCMAKE_TOOLCHAIN_FILE="$Ndk\build\cmake\android.toolchain.cmake" `
        -DCMAKE_SYSTEM_NAME=Android -DANDROID_NDK="$Ndk" -DANDROID_ABI="$Abi" `
        -DANDROID_PLATFORM="android-26" -DCMAKE_BUILD_TYPE=Release `
        -DCMAKE_INSTALL_PREFIX="$HwyStaging" `
        -DHWY_ENABLE_CONTRIB=OFF -DHWY_ENABLE_EXAMPLES=OFF -DHWY_ENABLE_TESTS=OFF `
        -DBUILD_SHARED_LIBS=OFF -DHWY_FORCE_STATIC=ON
    if ($LASTEXITCODE -ne 0) { throw "hwy configure failed" }
    Write-Host "=== Building highway ($Abi) ==="
    & $Cmake --build $HwyBuild --config Release
    if ($LASTEXITCODE -ne 0) { throw "hwy build failed" }
    Write-Host "=== Installing highway ==="
    & $Cmake --install $HwyBuild --config Release
    if ($LASTEXITCODE -ne 0) { throw "hwy install failed" }
}
$HwyLib = Get-ChildItem -Path $HwyBuild -Recurse -Filter "libhwy.a" -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $HwyLib) { throw "libhwy.a not found" }
Write-Host "  hwy lib: $($HwyLib.FullName) ($([math]::Round($HwyLib.Length/1MB,2)) MB)"

# ---------------------------------------------------------------- brotli
$BrotliVersion = "1.1.0"
$BrotliDir = Join-Path $WorkDir "brotli-$BrotliVersion"
$BrotliBuild = Join-Path $WorkDir "brotli_build"
$BrotliStaging = Join-Path $WorkDir "brotli_staging"

if (-not (Test-Path $BrotliDir)) { throw "brotli-$BrotliVersion not found" }

if (-not $SkipBrotli) {
    if (Test-Path $BrotliBuild) { Remove-Item -Recurse -Force $BrotliBuild }
    New-Item -ItemType Directory -Path $BrotliBuild -Force | Out-Null
    Write-Host "=== Configuring brotli for $Abi ==="
    & $Cmake -S $BrotliDir -B $BrotliBuild -G Ninja `
        -DCMAKE_MAKE_PROGRAM="$Ninja" `
        -DCMAKE_TOOLCHAIN_FILE="$Ndk\build\cmake\android.toolchain.cmake" `
        -DCMAKE_SYSTEM_NAME=Android -DANDROID_NDK="$Ndk" -DANDROID_ABI="$Abi" `
        -DANDROID_PLATFORM="android-26" -DCMAKE_BUILD_TYPE=Release `
        -DCMAKE_INSTALL_PREFIX="$BrotliStaging" `
        -DBUILD_SHARED_LIBS=OFF -DBROTLI_DISABLE_TESTS=ON
    if ($LASTEXITCODE -ne 0) { throw "brotli configure failed" }
    Write-Host "=== Building brotli ($Abi) ==="
    & $Cmake --build $BrotliBuild --config Release
    if ($LASTEXITCODE -ne 0) { throw "brotli build failed" }
    & $Cmake --install $BrotliBuild --config Release
    if ($LASTEXITCODE -ne 0) { throw "brotli install failed" }
}
$BrotliCommonLib = Get-ChildItem -Path $BrotliBuild -Recurse -Filter "libbrotlicommon.a" -ErrorAction SilentlyContinue | Select-Object -First 1
$BrotliDecLib = Get-ChildItem -Path $BrotliBuild -Recurse -Filter "libbrotlidec.a" -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $BrotliCommonLib -or -not $BrotliDecLib) { throw "brotli libs not found" }
Write-Host "  brotli common: $($BrotliCommonLib.FullName)"

# ---------------------------------------------------------------- libjxl
$JxlVersion = "0.12.0"
$JxlDir = Join-Path $WorkDir "libjxl-$JxlVersion"
$JxlBuild = Join-Path $WorkDir "jxl_build"

if (-not (Test-Path $JxlDir)) { throw "libjxl-$JxlVersion not found" }

# Ensure third_party/highway + brotli point at our built dirs (junctors).
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

if (-not $SkipJxl) {
    if (Test-Path $JxlBuild) { Remove-Item -Recurse -Force $JxlBuild }
    New-Item -ItemType Directory -Path $JxlBuild -Force | Out-Null
    Write-Host "=== Configuring libjxl $JxlVersion for $Abi (SKCMS=OFF, lcms2) ==="
    & $Cmake -S $JxlDir -B $JxlBuild -G Ninja `
        -DCMAKE_MAKE_PROGRAM="$Ninja" `
        -DCMAKE_TOOLCHAIN_FILE="$Ndk\build\cmake\android.toolchain.cmake" `
        -DCMAKE_SYSTEM_NAME=Android -DANDROID_NDK="$Ndk" -DANDROID_ABI="$Abi" `
        -DANDROID_PLATFORM="android-26" -DCMAKE_BUILD_TYPE=Release `
        -DJPEGXL_ENABLE_BROTLI=ON -DBUILD_SHARED_LIBS=OFF -DBUILD_TESTING=OFF `
        -DJPEGXL_ENABLE_TOOLS=OFF -DJPEGXL_ENABLE_EXAMPLES=OFF -DJPEGXL_ENABLE_BENCHMARK=OFF `
        -DJPEGXL_ENABLE_SJPEG=OFF -DJPEGXL_ENABLE_OPENEXR=OFF `
        -DJPEGXL_ENABLE_SKCMS=OFF -DJPEGXL_BUNDLE_LIBPNG=OFF `
        -DJPEGXL_ENABLE_MANPAGES=OFF -DJPEGXL_ENABLE_DOXYGEN=OFF `
        -DJPEGXL_ENABLE_PLUGINS=OFF -DJPEGXL_ENABLE_DEVTOOLS=OFF -DJPEGXL_ENABLE_JNI=OFF `
        -DJPEGXL_STATIC=ON `
        -DCMAKE_INCLUDE_PATH="$HwyStaging\include;$BrotliStaging\include" `
        -DCMAKE_LIBRARY_PATH="$HwyStaging\lib;$BrotliStaging\lib"
    if ($LASTEXITCODE -ne 0) { throw "libjxl configure failed (exit $LASTEXITCODE)" }

    Write-Host "=== Building libjxl ($Abi) ==="
    & $Cmake --build $JxlBuild --config Release --parallel
    if ($LASTEXITCODE -ne 0) { throw "libjxl build failed" }
}

$JxlLib = Get-ChildItem -Path $JxlBuild -Recurse -Filter "libjxl_dec.a" -ErrorAction SilentlyContinue | Select-Object -First 1
$JxlThreadsLib = Get-ChildItem -Path $JxlBuild -Recurse -Filter "libjxl_threads.a" -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $JxlLib) { throw "libjxl_dec.a not found" }
Write-Host "  jxl lib:       $($JxlLib.FullName) ($([math]::Round($JxlLib.Length/1MB,2)) MB)"
if ($JxlThreadsLib) { Write-Host "  jxl threads:   $($JxlThreadsLib.FullName)" }

# ---------------------------------------------------------------- copy jxl to jniLibs
$JniTarget = Join-Path $ProjectRoot "app\src\main\jniLibs\$Abi"
Write-Host "=== Copying libjxl to $JniTarget ==="
Copy-Item -Path $JxlLib.FullName -Destination (Join-Path $JniTarget "libjxl_dec.a") -Force
if ($JxlThreadsLib) { Copy-Item -Path $JxlThreadsLib.FullName -Destination (Join-Path $JniTarget "libjxl_threads.a") -Force }
# hwy/brotli libs are unchanged (same versions) but re-copy to be safe.
Copy-Item -Path $HwyLib.FullName -Destination (Join-Path $JniTarget "libhwy.a") -Force
Copy-Item -Path $BrotliCommonLib.FullName -Destination (Join-Path $JniTarget "libbrotlicommon.a") -Force
Copy-Item -Path $BrotliDecLib.FullName -Destination (Join-Path $JniTarget "libbrotlidec.a") -Force
$BrotliEncLib = Get-ChildItem -Path $BrotliBuild -Recurse -Filter "libbrotlienc.a" -ErrorAction SilentlyContinue | Select-Object -First 1
if ($BrotliEncLib) { Copy-Item -Path $BrotliEncLib.FullName -Destination (Join-Path $JniTarget "libbrotlienc.a") -Force }

Write-Host ""
Write-Host "=== libjxl $JxlVersion build complete ==="
Write-Host "libjxl_dec.a + libjxl_threads.a copied to $JniTarget"
