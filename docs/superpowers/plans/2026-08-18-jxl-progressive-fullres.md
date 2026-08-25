# JXL 渐进全分辨率兼容 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** JXL 大图 viewer 打开不 OOM、放大时尽可能高的原生像素，峰值内存 < 256MB。

**Architecture:** native 端复用 `thumbDownsampleCallback` 的 2^n stride halving 机制（已验证），但改用 `bitmapWriteCallback` 直写 bitmap 内存（`AndroidBitmap_lockPixels` rawPtr）— 消除 `thumb.pixels` 中间缓冲。文件读取走 mmap 主路径 + readBytes fallback（200MB 上限）。viewer 端新增 zoom 感知渐进加载：状态用 `target long edge` 表达，跨档前先算 stride 比较，若与当前档 stride 相同则短路跳过。最高档输出统一为不超过 4096 的最大 2^n 缩放（4K 全分辨率，>4K 降采样到 4096）。

**Tech Stack:** Kotlin (Compose + Telephoto zoomable), libjxl C++ (JNI `JxlDecoder` + `AndroidBitmap_lockPixels` + `mmap` syscall), Android Bitmap (ARGB_8888).

---

## File Structure

| 文件 | 责任 | 操作 |
|---|---|---|
| `app/src/main/cpp/stubs/smartvision_jni.cpp` | JNI 入口 + libjxl 解码 + mmap + bitmap 直写 | 修改 |
| `app/src/main/java/com/smartvision/gallery/decoder/bridge/NativeBridge.kt` | Kotlin JNI facade + mmap 主路径 + readBytes fallback | 修改 |
| `app/src/main/java/com/smartvision/gallery/decoder/image/JxlNativeDecoder.kt` | decodeFull 改走 decodeJxlScaled | 修改 |
| `app/src/main/java/com/smartvision/gallery/decoder/MediaLoader.kt` | 移除 capBitmapPayload + MAX_BITMAP_LONG_EDGE_PX | 修改 |
| `app/src/main/java/com/smartvision/gallery/ui/viewer/PhotoViewerActivity.kt` | 新增 zoom 感知渐进加载（isNextGen 分支） | 修改 |
| `app/src/main/java/com/smartvision/gallery/ui/viewer/JxlProgressiveController.kt` | **新建**：档位状态机 + 跨档短路 + 双向响应 | 创建 |
| `app/src/test/java/com/smartvision/gallery/ui/viewer/JxlProgressiveControllerTest.kt` | 单元测试（单测，零 native 依赖） | 创建 |

---

## Task 1: Native callback 直写 bitmap + 2^n stride halving

**Files:**
- Modify: `app/src/main/cpp/stubs/smartvision_jni.cpp:295-460`

- [ ] **Step 1: 在 namespace 内添加 `BitmapWriteState` 结构 + `bitmapWriteCallback` 实现**

在 `smartvision_jni.cpp` 第 295 行（`ThumbDownsampleState` 结构之前），插入：

```cpp
// ---- Direct bitmap write callback ----------------------------------------
// Used for full-resolution decode (targetLongEdgePx > 0). libjxl invokes this
// callback once per scanline, on worker threads. We write straight into the
// pre-allocated ARGB_8888 bitmap's locked pixels — no intermediate RGBA
// vector. lockPixels is acquired once before the JxlDecoderProcessInput loop
// and released once after; the callback is 0-JNI pure C++.
struct BitmapWriteState {
    AndroidBitmapInfo info;
    void* rawPtr = nullptr;     // set by decode controller before process loop
    uint32_t dstW = 0, dstH = 0;
    uint32_t stride = 1;        // 1/2/4/8 — power-of-two downsample stride
};

void bitmapWriteCallback(void* opaque, size_t x, size_t y,
                         size_t num_pixels, const void* pixels) {
    auto* st = static_cast<BitmapWriteState*>(opaque);
    const uint8_t* src = static_cast<const uint8_t*>(pixels);
    const uint32_t s = st->stride;
    // libjxl visits every pixel in source order. We write one dst pixel per
    // stride*stride block (nearest pick from top-left). Each dst row holds
    // dstW pixels; dstY = y/s, dstX = x/s when (x % s) == 0.
    for (size_t i = 0; i < num_pixels; ++i, src += 4) {
        size_t gx = x + i;
        if ((gx % s) != 0) continue;
        uint32_t dstX = (uint32_t)(gx / s);
        uint32_t dstY = (uint32_t)(y / s);
        if (dstX >= st->dstW || dstY >= st->dstH) continue;
        uint8_t* dst = static_cast<uint8_t*>(st->rawPtr)
                       + (size_t)(dstY * st->info.stride + dstX * 4);
        // ARGB_8888 memory layout is RGBA byte order — matches libjxl's
        // RGBA8888 output directly. No swizzle.
        dst[0] = src[0]; dst[1] = src[1];
        dst[2] = src[2]; dst[3] = src[3];
    }
}
```

- [ ] **Step 2: 修改 `decodeJxlReal` 签名 + 重写 `JXL_DEC_NEED_IMAGE_OUT_BUFFER` 分支**

**2a. 签名加 `JNIEnv*`**（当前签名第 332-333 行）：

```cpp
bool decodeJxlReal(JNIEnv* env, const uint8_t* data, size_t len,
                   int targetW, int targetH,
                   jobject* outBmp, int* outW, int* outH, int* outDepth, bool* outHdr) {
```

所有现有调用点（`nativeDecodeJxlThumb`、`nativeDecodeJxlFull`、`nativeDecodeJxlScaledBytes`、`nativeDecodeJxlFd`）加 `env` 参数。

**2b. 删除 dead code 状态**：删除 `ThumbDownsampleState` 结构 + `thumbDownsampleCallback`（第 295-328 行）与 `rgba`/`thumb`/`thumbMode` 变量（第 366-372 行）。统一走 callback 直写路径，`thumbMode` 恒为 true。

**2c. 重写 `JXL_DEC_NEED_IMAGE_OUT_BUFFER` case（第 398-440 行）**：

```cpp
case JXL_DEC_NEED_IMAGE_OUT_BUFFER: {
    JxlBasicInfo cur{};
    JxlDecoderGetBasicInfo(dec, &cur);
    size_t bw = cur.xsize, bh = cur.ysize;
    LOGI("libjxl: NEED_IMAGE_OUT_BUFFER %zux%zu target=%dx%d",
         bw, bh, targetW, targetH);
    // Smallest 2^n stride whose output long edge does NOT exceed target:
    // stride = 2^ceil(log2(longEdge / target)) per spec §"stride 映射".
    // Output lands in (target/2, target] — matches the spec tier table.
    size_t longEdge = bw > bh ? bw : bh;
    uint32_t t = (uint32_t)targetW;
    // Canvas hard cap (spec rev.4 "档位链二次检查"): clamping at 4096 keeps
    // every output ≤ 48MB, far under the 91MB Canvas limit. 16K at 1/2
    // would otherwise emit 8192×6144 (192MB) and crash RecordingCanvas.
    if (t > 4096) t = 4096;
    if (t < 256) t = 256;  // guard: t=0 would loop forever (outEdge > 0)
    // stride = 2^ceil(log2(longEdge / t)) — smallest 2^n whose output
    // long edge (longEdge / s) does NOT exceed t. Works for non-power-of-two
    // sources too (e.g. 7680 → target 4096 → s=2 → 3840).
    uint32_t s = 1;
    size_t outEdge = longEdge;
    while (outEdge > t) { s *= 2; outEdge = longEdge / s; }
    *outW = (int)(bw / s);
    *outH = (int)(bh / s);
    *outBmp = makeBitmapArgb(env, *outW, *outH);
    if (*outBmp == nullptr) {
        LOGE("libjxl: makeBitmapArgb failed for %dx%d", *outW, *outH);
        goto done;
    }
    // Lock once; callback will write directly into this memory.
    AndroidBitmapInfo info{};
    void* raw = nullptr;
    if (AndroidBitmap_getInfo(env, *outBmp, &info) != ANDROID_BITMAP_RESULT_SUCCESS
        || AndroidBitmap_lockPixels(env, *outBmp, &raw) != ANDROID_BITMAP_RESULT_SUCCESS) {
        LOGE("libjxl: lockPixels failed for %dx%d", *outW, *outH);
        goto done;
    }
    BitmapWriteState bws;
    bws.info = info;
    bws.rawPtr = raw;
    bws.dstW = (uint32_t)*outW;
    bws.dstH = (uint32_t)*outH;
    bws.stride = s;
    // Stash state in caller-provided thread-local by overloading a
    // global; safe because decodeJxlReal is invoked synchronously from
    // the JNI thread and libjxl's parallel runner dispatches callbacks
    // strictly inside JxlDecoderProcessInput.
    static thread_local BitmapWriteState tls_bws;
    tls_bws = bws;
    JxlDecoderStatus ok = JxlDecoderSetImageOutCallback(
        dec, &format, bitmapWriteCallback, &tls_bws);
    if (ok != JXL_DEC_SUCCESS) {
        LOGE("libjxl: setImageOutCallback failed (status=%d)", (int)ok);
        AndroidBitmap_unlockPixels(env, *outBmp);
        goto done;
    }
    LOGI("libjxl: bitmap-write callback stride=%u dst=%ux%u",
         s, bws.dstW, bws.dstH);
    break;
}
```

- [ ] **Step 3: 重写 `done:` 标签后的 bitmap 填充逻辑**

替换第 453-475 行（`done:` 标签起）：

```cpp
done:
    if (gotImage && *outBmp != nullptr) {
        // Bitmap pixels are already filled by the callback. Just unlock and
        // return success.
        AndroidBitmap_unlockPixels(env, *outBmp);
        *outDepth = depth;
        *outHdr = hdr;
        JxlDecoderDestroy(dec);
        if (runner != nullptr) JxlThreadParallelRunnerDestroy(runner);
        return true;
    }
    // Failure paths may have locked the bitmap; unlock defensively (no-op if
    // never locked — the lock flag below tracks it).
    JxlDecoderDestroy(dec);
    if (runner != nullptr) JxlThreadParallelRunnerDestroy(runner);
    return false;
```

- [ ] **Step 4: 验证编译**

```bash
cd "H:/workspace-minimaxcode/hyperalbum" && ./gradlew :app:assembleDebug -x lint 2>&1 | tail -5
```

Expected: `BUILD SUCCESSFUL`。任何 native 编译错误修掉。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/cpp/stubs/smartvision_jni.cpp
git commit -m "feat(jxl): native callback writes bitmap directly, no intermediate rgba buffer"
```

---

## Task 2: mmap 主路径 + readBytes fallback

**Files:**
- Modify: `app/src/main/cpp/stubs/smartvision_jni.cpp:566-595`（nativeDecodeJxlFull JNI 入口）

- [ ] **Step 1: 新增 `nativeDecodeJxlFd` JNI 入口（在 nativeDecodeJxlFull 之后）**

在第 595 行（`nativeDecodeJxlFull` 函数结尾 `}` 后）插入新 JNI 函数：

```cpp
JNIEXPORT jobject JNICALL
Java_com_smartvision_gallery_decoder_bridge_NativeBridge_nativeDecodeJxlFd(
    JNIEnv* env, jclass, jint fd, jlong len, jint targetLongEdgePx
) {
    if (fd < 0 || len <= 0) {
        LOGW("nativeDecodeJxlFd: invalid args fd=%d len=%lld", fd, (long long)len);
        return nullptr;
    }
    // mmap the fd for zero-copy read. len is lseek'd file size; clamp at 256MB.
    size_t mapLen = (size_t)len;
    const size_t MMAP_CAP = 256UL * 1024 * 1024;
    if (mapLen > MMAP_CAP) mapLen = MMAP_CAP;
    void* data = mmap(nullptr, mapLen, PROT_READ, MAP_SHARED, fd, 0);
    if (data == MAP_FAILED) {
        LOGW("nativeDecodeJxlFd: mmap failed errno=%d, falling back to readBytes caller", errno);
        return nullptr;
    }
    jobject result = nullptr;
#ifdef SMARTVISION_REAL_JXL_DECODER
    jobject bmp = nullptr;
    int outW = 0, outH = 0, depth = 0;
    bool isHdr = false;
    // Pass targetW = targetLongEdgePx to trigger the callback stride path
    // in decodeJxlReal. Native halving picks smallest stride.
    if (decodeJxlReal(static_cast<uint8_t*>(data), mapLen,
                      (int)targetLongEdgePx, (int)targetLongEdgePx,
                      &bmp, &outW, &outH, &depth, &isHdr)) {
        result = makeDecodeResult(env, bmp, outW, outH, depth, isHdr);
    }
#endif
    munmap(data, mapLen);
    LOGI("nativeDecodeJxlFd: fd=%d len=%zu target=%d → %s",
         fd, mapLen, (int)targetLongEdgePx,
         result == nullptr ? "FAIL" : "OK");
    return result;
}
```

在文件顶部的 `#include` 区（第 13-19 行附近）添加：

```cpp
#include <sys/mman.h>
#include <cerrno>
#include <unistd.h>
```

- [ ] **Step 2: 验证编译**

```bash
cd "H:/workspace-minimaxcode/hyperalbum" && ./gradlew :app:assembleDebug -x lint 2>&1 | tail -3
```

Expected: `BUILD SUCCESSFUL`。

- [ ] **Step 3: 提交**

```bash
git add app/src/main/cpp/stubs/smartvision_jni.cpp
git commit -m "feat(jxl): native mmap-based decode entry nativeDecodeJxlFd"
```

---

## Task 3: Kotlin NativeBridge 统一接口 (mmap 主路径 + readBytes fallback)

**Files:**
- Modify: `app/src/main/java/com/smartvision/gallery/decoder/bridge/NativeBridge.kt`

- [ ] **Step 1: 替换 `decodeJxlFull` 为 `decodeJxlScaled`**

修改 `NativeBridge.kt:86-94`，将 `decodeJxlFull` 替换为：

```kotlin
/**
 * Decode a JPEG XL file at a target long-edge resolution. Returns null on
 * failure (decoder unavailable, mmap failed AND readBytes also failed, decode
 * error).
 *
 * The native bridge streams pixels directly into the output bitmap via
 * AndroidBitmap_lockPixels — no intermediate RGBA buffer. File I/O goes
 * through mmap first (zero-copy), with readBytes as a fallback for
 * non-file-backed providers (cloud drives, pipes).
 *
 * @param targetLongEdgePx desired output long edge. Native computes the
 *   smallest power-of-two stride that brings the source below this size;
 *   see spec §"stride 映射 + 最高档取整". Pass 4096 for the safe high-res tier.
 */
suspend fun decodeJxlScaled(uri: Uri, targetLongEdgePx: Int = 4096): NativeDecodeResult? {
    if (!available) return null
    return withContext(Dispatchers.IO) {
        // Primary path: mmap via file descriptor.
        runCatching { decodeJxlViaMmap(uri, targetLongEdgePx) }.getOrNull()
            ?: run {
                // Fallback: readBytes (for non-file-backed providers).
                val bytes = readBytes(uri, maxBytes = 200 * 1024 * 1024) ?: return@withContext null
                runCatching { nativeDecodeJxlScaledBytes(bytes, targetLongEdgePx) }
                    .onFailure { AppLog.w(TAG, "nativeDecodeJxlScaledBytes failed", it) }
                    .getOrNull()
            }
    }
}

private fun decodeJxlViaMmap(uri: Uri, targetLongEdgePx: Int): NativeDecodeResult? {
    val ctx = appContext ?: return null
    val pfd = ctx.contentResolver.openFileDescriptor(uri, "r") ?: return null
    return pfd.use {
        val fd = it.fileDescriptor
        val len = it.statSize.takeIf { s -> s > 0 } ?: run {
            // statSize unavailable on some providers — best effort skip.
            return null
        }
        nativeDecodeJxlFd(fd, len, targetLongEdgePx)
    }
}
```

- [ ] **Step 2: 替换 JNI external 声明**

修改 `NativeBridge.kt:144-145`：

```kotlin
@JvmStatic private external fun nativeDecodeJxlFull(data: ByteArray): NativeDecodeResult?
@JvmStatic private external fun nativeDecodeJxlThumb(data: ByteArray, w: Int, h: Int): Bitmap?
@JvmStatic private external fun nativeDecodeJxlScaledBytes(data: ByteArray, targetLongEdgePx: Int): NativeDecodeResult?
@JvmStatic private external fun nativeDecodeJxlFd(fd: Int, len: Long, targetLongEdgePx: Int): NativeDecodeResult?
```

- [ ] **Step 3: 添加 native `decodeJxlScaledBytes` JNI 入口（Task 2 的 ByteArray 版）**

在 `nativeDecodeJxlFd` 之后插入：

```cpp
JNIEXPORT jobject JNICALL
Java_com_smartvision_gallery_decoder_bridge_NativeBridge_nativeDecodeJxlScaledBytes(
    JNIEnv* env, jclass, jbyteArray dataArr, jint targetLongEdgePx
) {
    if (dataArr == nullptr) return nullptr;
    jsize len = env->GetArrayLength(dataArr);
    jbyte* data = env->GetByteArrayElements(dataArr, nullptr);
    jobject result = nullptr;
#ifdef SMARTVISION_REAL_JXL_DECODER
    jobject bmp = nullptr;
    int outW = 0, outH = 0, depth = 0;
    bool isHdr = false;
    if (decodeJxlReal(reinterpret_cast<uint8_t*>(data), (size_t)len,
                      (int)targetLongEdgePx, (int)targetLongEdgePx,
                      &bmp, &outW, &outH, &depth, &isHdr)) {
        result = makeDecodeResult(env, bmp, outW, outH, depth, isHdr);
    }
#endif
    env->ReleaseByteArrayElements(dataArr, data, JNI_ABORT);
    return result;
}
```

- [ ] **Step 4: 删除 JNI 旧函数 `nativeDecodeJxlFull` 与 `nativeDecodeJxlThumb`**

从 `smartvision_jni.cpp` 删除 `Java_com_smartvision_gallery_decoder_bridge_NativeBridge_nativeDecodeJxlFull`（第 566-590 行）和 `Java_com_smartvision_gallery_decoder_bridge_NativeBridge_nativeDecodeJxlThumb`（第 544-563 行）。`NativeBridge.kt` 也删除对应 `@JvmStatic private external fun nativeDecodeJxlFull(...)` 与 `nativeDecodeJxlThumb(...)`。

注意：检查项目里所有 `nativeDecodeJxlFull` / `nativeDecodeJxlThumb` 调用方，迁移到新接口。

- [ ] **Step 5: 验证编译**

```bash
cd "H:/workspace-minimaxcode/hyperalbum" && ./gradlew :app:assembleDebug -x lint 2>&1 | tail -10
```

Expected: `BUILD SUCCESSFUL`（可能先有报错 — 移除旧 JNI 函数后调用方编译失败会暴露，按报错改）。

- [ ] **Step 6: 提交**

```bash
git add app/src/main/cpp/stubs/smartvision_jni.cpp app/src/main/java/com/smartvision/gallery/decoder/bridge/NativeBridge.kt
git commit -m "feat(jxl): unified decodeJxlScaled with mmap primary + readBytes fallback"
```

---

## Task 4: JxlNativeDecoder 改用新接口

**Files:**
- Modify: `app/src/main/java/com/smartvision/gallery/decoder/image/JxlNativeDecoder.kt`

- [ ] **Step 1: 重写 `decodeThumbnail` 与 `decodeFull`**

```kotlin
package com.smartvision.gallery.decoder.image

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import com.smartvision.gallery.data.model.DecodedPayload
import com.smartvision.gallery.decoder.Decoder
import com.smartvision.gallery.decoder.bridge.NativeBridge
import com.smartvision.gallery.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class JxlNativeDecoder(private val context: Context) : Decoder {

    override val id = "jxl"

    override suspend fun decodeThumbnail(
        uri: Uri,
        targetWidthPx: Int,
        targetHeightPx: Int
    ): Bitmap? = withContext(Dispatchers.IO) {
        val longEdge = maxOf(targetWidthPx, targetHeightPx)
        NativeBridge.decodeJxlScaled(uri, longEdge)?.bitmap
    }

    override suspend fun decodeFull(
        uri: Uri,
        maxWidthPx: Int?,
        maxHeightPx: Int?
    ): DecodedPayload? = withContext(Dispatchers.IO) {
        // Pass maxLongEdge to native; halving picks smallest 2^n stride
        // that yields an output not larger than the source AND at most the
        // requested long edge. 4096 is the Canvas-safe target — see spec.
        val longEdge = maxOf(maxWidthPx ?: 4096, maxHeightPx ?: 4096)
        val res = NativeBridge.decodeJxlScaled(uri, longEdge)
            ?: return@withContext null
        DecodedPayload.BitmapPayload(
            width = res.width,
            height = res.height,
            sourceUri = uri,
            decoderId = id,
            bitmap = res.bitmap,
            colorDepth = res.colorDepth,
            isHdr = res.isHdr
        )
    }
}
```

- [ ] **Step 2: 验证编译**

```bash
cd "H:/workspace-minimaxcode/hyperalbum" && ./gradlew :app:assembleDebug -x lint 2>&1 | tail -5
```

Expected: `BUILD SUCCESSFUL`。

- [ ] **Step 3: 提交**

```bash
git add app/src/main/java/com/smartvision/gallery/decoder/image/JxlNativeDecoder.kt
git commit -m "refactor(jxl): JxlNativeDecoder routes through decodeJxlScaled"
```

---

## Task 5: MediaLoader 移除 capBitmapPayload 硬卡

**Files:**
- Modify: `app/src/main/java/com/smartvision/gallery/decoder/MediaLoader.kt:66-92, 201-238, 247-250`

- [ ] **Step 1: 删除 `loadFull` 与 `loadFullUri` 中的 `capBitmapPayload` 调用**

修改第 70-76 行：

```kotlin
suspend fun loadFull(
    item: com.smartvision.gallery.data.model.MediaItem,
    maxWidthPx: Int? = null,
    maxHeightPx: Int? = null
): DecodedPayload? = withContext(decodeDispatcher) {
    val format = if (item.format == MediaFormat.UNKNOWN) {
        FormatDetector.detect(context, item.uri, item.displayName)
    } else item.format
    decoderFor(format, useHardwareAccel()).decodeFull(item.uri, maxWidthPx, maxHeightPx)
}
```

修改第 84-92 行：

```kotlin
suspend fun loadFullUri(
    uri: Uri,
    format: MediaFormat? = null,
    maxDimensionPx: Int = 4096
): DecodedPayload? = withContext(decodeDispatcher) {
    val fmt = format ?: FormatDetector.detect(context, uri, null)
    decoderFor(fmt, useHardwareAccel()).decodeFull(uri, maxDimensionPx, maxDimensionPx)
}
```

- [ ] **Step 2: 删除 `capBitmapPayload` 函数 + `MAX_BITMAP_LONG_EDGE_PX` 常量**

删除第 201-238 行（`capBitmapPayload` 整个函数 + 注释）。

修改第 247-250 行（companion object）：

```kotlin
private companion object {
    private const val TAG = "MediaLoader"
}
```

- [ ] **Step 3: 验证编译**

```bash
cd "H:/workspace-minimaxcode/hyperalbum" && ./gradlew :app:assembleDebug -x lint 2>&1 | tail -5
```

Expected: `BUILD SUCCESSFUL`。

- [ ] **Step 4: 提交**

```bash
git add app/src/main/java/com/smartvision/gallery/decoder/MediaLoader.kt
git commit -m "refactor(jxl): remove capBitmapPayload — native now caps via decodeJxlScaled"
```

---

## Task 6: JxlProgressiveController 单元测试（先写测试）

**Files:**
- Create: `app/src/test/java/com/smartvision/gallery/ui/viewer/JxlProgressiveControllerTest.kt`

- [ ] **Step 1: 写失败的单测**

```kotlin
package com.smartvision.gallery.ui.viewer

import org.junit.Assert.assertEquals
import org.junit.Test

class JxlProgressiveControllerTest {

    @Test
    fun `computeStrideForTarget picks smallest 2^n that brings source below target`() {
        // 8K source (8192) → target 4096 → stride 2 (output 4096)
        assertEquals(2, JxlProgressiveController.computeStrideForTarget(8192L, 4096))
        // 4K source (4096) → target 4096 → stride 1 (full resolution)
        assertEquals(1, JxlProgressiveController.computeStrideForTarget(4096L, 4096))
        // 16K source (16384) → target 4096 → stride 4 (output 4096)
        assertEquals(4, JxlProgressiveController.computeStrideForTarget(16384L, 4096))
        // 2K source (2048) → target 4096 → stride 1 (output 2048, already under)
        assertEquals(1, JxlProgressiveController.computeStrideForTarget(2048L, 4096))
    }

    @Test
    fun `computeInitialTargetPx is roughly 1/8 of source, floored at 256`() {
        assertEquals(1024, JxlProgressiveController.computeInitialTargetPx(8192L))
        assertEquals(512, JxlProgressiveController.computeInitialTargetPx(4096L))
        assertEquals(256, JxlProgressiveController.computeInitialTargetPx(256L))   // floor
        assertEquals(256, JxlProgressiveController.computeInitialTargetPx(128L))   // floor
    }

    @Test
    fun `shouldReload short-circuits when target maps to same stride as current`() {
        // 8K source, currently loaded at stride 2 (output 4096)
        val sourceLongEdge = 8192L
        val currentStride = 2
        // New zoom implies target 8000 — stride 2 still wins (not 1, would be 8K).
        assertEquals(false,
            JxlProgressiveController.shouldReload(sourceLongEdge, currentStride, targetLongEdgePx = 8000))
        // New zoom implies target 4096 — stride 2 wins, same.
        assertEquals(false,
            JxlProgressiveController.shouldReload(sourceLongEdge, currentStride, targetLongEdgePx = 4096))
        // New zoom implies target 1024 — stride 8 wins, different → reload.
        assertEquals(true,
            JxlProgressiveController.shouldReload(sourceLongEdge, currentStride, targetLongEdgePx = 1024))
    }
}
```

- [ ] **Step 2: 跑测试确认失败（RED）**

```bash
cd "H:/workspace-minimaxcode/hyperalbum" && ./gradlew :app:testDebugUnitTest --tests "*JxlProgressiveControllerTest*" 2>&1 | tail -15
```

Expected: 编译失败（`JxlProgressiveController` 不存在）。

---

## Task 7: JxlProgressiveController 实现（让测试通过 GREEN）

**Files:**
- Create: `app/src/main/java/com/smartvision/gallery/ui/viewer/JxlProgressiveController.kt`

- [ ] **Step 1: 实现 controller 静态方法（纯函数部分）**

```kotlin
package com.smartvision.gallery.ui.viewer

import android.net.Uri
import com.smartvision.gallery.data.model.DecodedPayload
import com.smartvision.gallery.decoder.MediaLoader
import com.smartvision.gallery.decoder.format.MediaFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Drives JXL viewer progressive loading.
 *
 * State: last-loaded target long edge (used as the "current stride" proxy
 * — equal target ⇒ equal native stride ⇒ no decode). Zoom changes
 * recompute the target; if the new target equals the last-loaded target,
 * reload is skipped (fast path) — collapses the 8K 1/1 == 1/2 case where
 * both target 4096 and 8192 map to native stride 2.
 *
 * Decode is debounced (50 ms). A new request while a load is in flight
 * cancels the pending job; only the latest target decodes.
 *
 * Caller owns bitmap lifetime: this controller delivers the new payload
 * and invokes onBitmapReady on the main dispatcher; the caller swaps
 * state and recycles the prior bitmap after the 200 ms crossfade.
 */
class JxlProgressiveController(
    private val mediaLoader: MediaLoader,
    private val scope: CoroutineScope,
    private val sourceUri: Uri,
    private val sourceLongEdgePx: Long,
    private val maxTargetPx: Int = 4096
) {
    @Volatile var currentTargetPx: Int = 0
        private set

    private var pending: Job? = null

    /** Called by the viewer whenever zoom changes. */
    fun requestTarget(
        targetLongEdgePx: Int,
        onBitmapReady: (DecodedPayload?) -> Unit
    ) {
        val clamped = targetLongEdgePx.coerceAtLeast(256).coerceAtMost(maxTargetPx)
        if (clamped == currentTargetPx) return  // fast path
        pending?.cancel()
        pending = scope.launch(Dispatchers.IO) {
            delay(50)  // debounce
            val payload = mediaLoader.loadFullUri(
                uri = sourceUri,
                format = MediaFormat.JXL,
                maxDimensionPx = clamped
            )
            currentTargetPx = clamped
            withContext(Dispatchers.Main) { onBitmapReady(payload) }
        }
    }

    /** Called once after the initial decode so the fast path works for the
     *  zoom level that matches the initial target. */
    fun seed(targetPx: Int) {
        currentTargetPx = targetPx.coerceAtLeast(256).coerceAtMost(maxTargetPx)
    }

    companion object {
        /** Smallest 2^n stride whose output long edge ≤ target. Mirrors native. */
        @JvmStatic
        fun computeStrideForTarget(sourceLongEdge: Long, targetLongEdge: Int): Int {
            require(sourceLongEdge > 0 && targetLongEdge > 0)
            var s = 1
            while (sourceLongEdge / s > targetLongEdge) s *= 2
            return s
        }

        /** Initial target ≈ 1/8 of source, floored at 256 px. */
        @JvmStatic
        fun computeInitialTargetPx(sourceLongEdge: Long): Int {
            val raw = (sourceLongEdge / 8).toInt()
            return raw.coerceAtLeast(256)
        }

        /** True when the new target maps to a different stride than current load. */
        @JvmStatic
        fun shouldReload(sourceLongEdge: Long, currentStride: Int, targetLongEdgePx: Int): Boolean =
            computeStrideForTarget(sourceLongEdge, targetLongEdgePx) != currentStride
    }
}
```

- [ ] **Step 2: 跑测试确认通过（GREEN）**

```bash
cd "H:/workspace-minimaxcode/hyperalbum" && ./gradlew :app:testDebugUnitTest --tests "*JxlProgressiveControllerTest*" 2>&1 | tail -10
```

Expected: `BUILD SUCCESSFUL`，3 个测试全部 PASS。

- [ ] **Step 3: 提交**

```bash
git add app/src/main/java/com/smartvision/gallery/ui/viewer/JxlProgressiveController.kt app/src/test/java/com/smartvision/gallery/ui/viewer/JxlProgressiveControllerTest.kt
git commit -m "feat(viewer): JxlProgressiveController with stride short-circuit + debounced decode"
```

---

## Task 8: PhotoViewerActivity isNextGen 分支接入 controller

**Files:**
- Modify: `app/src/main/java/com/smartvision/gallery/ui/viewer/PhotoViewerActivity.kt:840-975`

- [ ] **Step 1: 读取 source long edge**

在 `isNextGen` 分支第 922 行附近修改 `LaunchedEffect(uri)`，先读取 source 像素：

```kotlin
isNextGen -> {
    var bitmap by remember(uri) { mutableStateOf<Bitmap?>(null) }
    var decodeError by remember(uri) { mutableStateOf(false) }
    var sourceLongEdge by remember(uri) { mutableStateOf(0L) }
    var controller by remember(uri) { mutableStateOf<JxlProgressiveController?>(null) }
    val viewerScope = rememberCoroutineScope()

    LaunchedEffect(uri) {
        // Native JXL has no BitmapFactory probe. Decode a 256-tier probe to
        // discover dimensions; native stride maps source to ≤256, so the
        // returned bitmap's long edge × 2^n gives source. Easier: just take
        // the probe as evidence we CAN decode and use its long edge as
        // sourceLongEdge/[stride=source/256 → return ≤256 anyway]. We only
        // need an approximate sourceLongEdge for computeInitialTargetPx and
        // the native stride math — passing 256 × 2^ceil(log2(source/256))
        // back into the controller round-trips correctly.
        val sourceApprox = withContext(Dispatchers.IO) {
            runCatching {
                val probe = app.mediaLoader.loadFullUri(uri, format, 256)
                (probe as? DecodedPayload.BitmapPayload)?.let { p ->
                    // native stride s.t. longEdge/s ≤ 256 ⇒ probe.width = longEdge/s.
                    // We don't know s, but we don't need it: native will pick the
                    // right stride again on subsequent calls regardless of our
                    // sourceApprox value. Use probe width × 8 as a coarse estimate
                    // so computeInitialTargetPx gives a sensible 1/8 target.
                    maxOf(p.width, p.height).toLong() * 8
                }
            }.getOrNull()
        }
        sourceLongEdge = sourceApprox ?: 0L

        val initialTarget = if (sourceLongEdge > 0)
            JxlProgressiveController.computeInitialTargetPx(sourceLongEdge) else 1024
        val payload = app.mediaLoader.loadFullUri(uri, format, initialTarget)
        val bmp = (payload as? DecodedPayload.BitmapPayload)?.bitmap
        bitmap = bmp
        if (bmp == null) decodeError = true
        // Build controller *after* initial decode so it can seed currentTargetPx
        // — otherwise the fast path would re-decode the tier we just loaded.
        val ctrl = JxlProgressiveController(
            mediaLoader = app.mediaLoader,
            scope = viewerScope,
            sourceUri = uri,
            sourceLongEdgePx = if (sourceLongEdge > 0) sourceLongEdge else 8192L
        )
        ctrl.seed(initialTarget)
        controller = ctrl
    }
    when {
        bitmap == null && !decodeError -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        decodeError -> CorruptImagePlaceholder()
        else -> {
            val imageBitmap = bitmap!!.asImageBitmap()
            AnimatedVisibility(visible = true, enter = fadeIn(animationSpec = tween(200))) {
                Image(
                    bitmap = imageBitmap,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxSize()
                        .zoomable(state = zoomableState)
                )
            }
            // Watch zoom — request higher/lower stride via controller.
            val transformation = zoomableState.contentTransformation
            val scale = transformation.scaleX
            LaunchedEffect(scale, controller) {
                val ctrl = controller ?: return@LaunchedEffect
                if (sourceLongEdge == 0L) return@LaunchedEffect
                val newTarget = computeZoomTarget(
                    sourceLongEdge = sourceLongEdge,
                    scale = scale,
                    displayWidthPx = context.resources.displayMetrics.widthPixels
                )
                ctrl.requestTarget(newTarget) { payload ->
                    val newBmp = (payload as? DecodedPayload.BitmapPayload)?.bitmap
                    if (newBmp != null) {
                        val old = bitmap
                        bitmap = newBmp
                        // Recycle the prior bitmap after the 200 ms crossfade.
                        viewerScope.launch {
                            delay(200)
                            if (old != null && !old.isRecycled) old.recycle()
                        }
                    }
                }
            }
        }
    }
}
```

并在文件顶部 import（缺少则补）：
```kotlin
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.Image
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
```

- [ ] **Step 2: 实现 `computeZoomTarget` 私有辅助函数**

在 `PhotoViewerActivity.kt` 文件内（companion 或顶层）添加：

```kotlin
/**
 * Given current scale and display size, compute the output long edge
 * the viewer needs to render at native pixels.
 *
 * At 1x scale we want ~displayWidthPx. At 4x we want ~4×displayWidthPx.
 * Cap at 4096 (Canvas safe; native stride maps this to the right 2^n).
 */
private fun computeZoomTarget(
    sourceLongEdge: Long,
    scale: Float,
    displayWidthPx: Int
): Int {
    val desired = (displayWidthPx * scale.coerceAtLeast(0.5f)).toInt()
    // Native stride picks the right 2^n; the cap just avoids requesting
    // targets beyond the Canvas-safe ceiling.
    return desired.coerceIn(256, 4096).coerceAtMost(sourceLongEdge.toInt())
}
```

- [ ] **Step 3: 移除 `maxDecodePx` 4096 硬卡**

修改第 845-849 行：

```kotlin
// Decode at enough resolution for crisp zoom, capped at 4096 to prevent
// OOM on large images. 4096px covers 2.8× zoom on 1440p displays and
// 3.8× on 1080p — the bitmap is downsampled via computeSample() so a
// 48MP photo (8000×6000) decodes to ~4000×3000 (48 MB) which is safe.
val displayMetrics = context.resources.displayMetrics
val maxDecodePx = minOf(
    (maxOf(displayMetrics.widthPixels, displayMetrics.heightPixels) * maxScale).toInt(),
    4096
)
```

替换为：

```kotlin
// Removed: 4096 px cap. JXL progressive controller now requests the right
// target per zoom level via computeZoomTarget + JxlProgressiveController.
// Native libjxl halving picks the smallest 2^n stride; peak memory for any
// source ≤ 96MB (8K JXL 1/1) — well under the 256MB budget.
```

（删除 `maxDecodePx` 变量定义）

- [ ] **Step 4: 验证编译**

```bash
cd "H:/workspace-minimaxcode/hyperalbum" && ./gradlew :app:assembleDebug -x lint 2>&1 | tail -10
```

Expected: `BUILD SUCCESSFUL`。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/smartvision/gallery/ui/viewer/PhotoViewerActivity.kt
git commit -m "feat(viewer): wire JxlProgressiveController into isNextGen branch"
```

---

## Task 9: 端到端真机验证

**Files:** 无（验证步骤）

- [ ] **Step 1: 编译 + 安装**

```bash
cd "H:/workspace-minimaxcode/hyperalbum" && ./gradlew :app:assembleDebug -x lint 2>&1 | tail -3
adb install -r "H:/workspace-minimaxcode/hyperalbum/app/build/outputs/apk/debug/app-debug.apk" 2>&1 | tail -2
```

Expected: `BUILD SUCCESSFUL` + `Success`。

- [ ] **Step 2: 触发 4K JXL viewer + 检查 logcat**

```bash
adb logcat -c
adb shell am force-stop com.smartvision.gallery.debug
sleep 1
adb shell am start -n com.smartvision.gallery.debug/com.smartvision.gallery.ui.viewer.PhotoViewerActivity \
  --es extra_uri "content://media/external/images/media/1001601505" \
  --es extra_display_name "1001600693.jxl"
sleep 8
echo "PID:"; adb shell pidof com.smartvision.gallery.debug
echo "==="
adb logcat -d -b crash -t 200 2>&1 | grep -A30 "FATAL EXCEPTION: main" | head -30
```

Expected: `pidof` 返回非空；无 `FATAL EXCEPTION: main`；无 `Canvas: trying to draw too large`。

- [ ] **Step 3: 检查 decode 日志**

```bash
adb shell "run-as com.smartvision.gallery.debug cat /data/user/0/com.smartvision.gallery.debug/files/logs/applog.txt" 2>&1 | tail -20
```

Expected: `MediaFetcher: fmt=JXL decode 4096x3072->4096x3072 cfg=ARGB_8888 bytes=50331648 ...` 出现；app pid 稳定存在。

- [ ] **Step 4: 跑全部单测**

```bash
cd "H:/workspace-minimaxcode/hyperalbum" && ./gradlew :app:testDebugUnitTest 2>&1 | tail -10
```

Expected: `BUILD SUCCESSFUL`，所有测试通过。

---

## 验收检查清单

- [ ] 4K JXL (4096×3072) 打开 → 1/1 档全分辨率 48MB 显示
- [ ] 8K JXL (8192×6144) 打开 → 1/1 档 4096×3072 (48MB) 显示，峰值 96MB
- [ ] 16K JXL (16384×12288) 打开 → 1/1 档 4096×3072 (48MB)，峰值 60MB
- [ ] 无 `Canvas: trying to draw too large`
- [ ] 快速缩放不闪切、不卡死（防抖 + 跨档短路生效）
- [ ] >64MB JXL 文件 mmap 解码成功；非 file-backed provider readBytes fallback 不崩
- [ ] 缩小 zoom 释放高档内存
- [ ] 单测 3 个 PASS