# JXL ≥16K 后台预解码为整张 JPEG 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** JXL 源长边 16K-32K 时后台异步整图 stride=8 解码 → compress 成单张 JPEG 95 落盘 cacheDir，viewer 切换到现有 `SubSamplingZoomableImage`（Telephoto + `BitmapRegionDecoder`）实现 1:1 无损缩放。

**Architecture:** 单新类 `JxlFullResPrecacher`（后台协程 + 已有 `mediaLoader.loadFullUri(uri, JXL, 4096)` 拿 4096 输出 → `Bitmap.compress(JPEG,95)` 落盘 → Main 回调 file）。`PhotoViewerActivity` isNextGen 分支新增 `fullResFile` state，非 null 时渲染 `SubSamplingZoomableImage`。仅动 JXL 路径。

**Tech Stack:** Kotlin 协程，libjxl JNI（stride=8 已有），Android `Bitmap.compress`，Telephoto `SubSamplingImageSource.contentUri`。

---

## 文件结构

| 文件 | 动作 | 职责 |
|---|---|---|
| `app/src/main/java/com/smartvision/gallery/ui/viewer/JxlFullResPrecacher.kt` | 新建 | 后台预解码 + JPEG 落盘 + MD5 命名 + 去重 job |
| `app/src/main/java/com/smartvision/gallery/SmartVisionApp.kt` | 修改 | 单例 `jxlFullResPrecacher`（lazy，复用 cacheCoordinator 不必要，直接 context.cacheDir） |
| `app/src/main/java/com/smartvision/gallery/ui/viewer/PhotoViewerActivity.kt` | 修改 | isNextGen 分支加 fullResFile state + 预解码触发（16K≤源≤32K）+ 显示切换 |

**不改：** JNI、NativeBridge、JxlProgressiveController、SubSamplingZoomableImage、Decoder 接口。

---

### Task 1: JxlFullResPrecacher 新建

**Files:**
- Create: `app/src/main/java/com/smartvision/gallery/ui/viewer/JxlFullResPrecacher.kt`

- [ ] **Step 1: 写文件**

```kotlin
package com.smartvision.gallery.ui.viewer

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.smartvision.gallery.data.model.DecodedPayload
import com.smartvision.gallery.decoder.MediaLoader
import com.smartvision.gallery.decoder.format.MediaFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * Background pre-decode for JXL sources with a long edge ≥ 16K.
 *
 * libjxl 0.10.3 has no ROI/crop API, so the only safe way to expose
 * native-pixel zoom is to decode the whole image once at stride=8
 * (32K → 4096 output, ~48 MB temp heap) and store it as a JPEG 95.
 * [com.smartvision.gallery.ui.viewer.SubSamplingZoomableImage] then
 * tile-decodes the JPEG with BitmapRegionDecoder, giving true 1:1 zoom
 * without ever holding the full-res bitmap in memory.
 */
class JxlFullResPrecacher(
    private val context: Context,
    private val mediaLoader: MediaLoader,
    private val scope: CoroutineScope
) {
    companion object {
        /** Sources below this long edge already zoom crisp via stride=1. */
        const val MIN_TRIGGER_LONG_EDGE_PX: Long = 16384L

        /** Stride-8 output cap — 32K/8 = 4096, Canvas-safe (≤ 48 MP). */
        const val OUTPUT_TARGET_PX: Int = 4096

        const val JPEG_QUALITY = 95
    }

    private val jobs = mutableMapOf<Uri, Job>()

    fun cacheFile(uri: Uri): File =
        File(context.cacheDir, "JXL_FullRes/${md5(uri.toString())}.jpg")

    fun cacheExists(uri: Uri): Boolean {
        val f = cacheFile(uri)
        return f.exists() && f.length() > 0
    }

    /**
     * Starts (or returns the running) background pre-decode for [uri].
     * Calls [onReady] on the main thread with the JPEG file once it exists
     * — either freshly written or already cached. Silent no-op on failure:
     * the viewer keeps its DC progressive preview.
     */
    fun start(uri: Uri, onReady: (File) -> Unit): Job? {
        if (cacheExists(uri)) {
            scope.launch(Dispatchers.Main) { onReady(cacheFile(uri)) }
            return null
        }
        jobs[uri]?.let { return it }
        val job = scope.launch(Dispatchers.IO) {
            try {
                val payload = mediaLoader.loadFullUri(
                    uri = uri,
                    format = MediaFormat.JXL,
                    maxDimensionPx = OUTPUT_TARGET_PX
                )
                val bitmap = (payload as? DecodedPayload.BitmapPayload)?.bitmap
                if (bitmap == null) {
                    jobs.remove(uri)
                    return@launch
                }
                val target = cacheFile(uri)
                target.parentFile?.mkdirs()
                FileOutputStream(target).use { fos ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, fos)
                }
                bitmap.recycle()
                withContext(Dispatchers.Main) { onReady(target) }
            } catch (e: Exception) {
                // Pre-decode failure is non-fatal: fall back to the DC preview.
            } finally {
                jobs.remove(uri)
            }
        }
        jobs[uri] = job
        return job
    }

    private fun md5(s: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(s.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}
```

- [ ] **Step 2: 编译验证**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL (无编译错误)

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/smartvision/gallery/ui/viewer/JxlFullResPrecacher.kt
git commit -m "feat(viewer): add JxlFullResPrecacher background JPEG predecode"
```

---

### Task 2: SmartVisionApp 注册单例

**Files:**
- Modify: `app/src/main/java/com/smartvision/gallery/SmartVisionApp.kt`（在 `mediaLoader` lazy 之后）

- [ ] **Step 1: 加 lazy 单例**

在 `val mediaLoader ... }`（约 104 行）后插入：

```kotlin
    val jxlFullResPrecacher: JxlFullResPrecacher by lazy {
        JxlFullResPrecacher(this, mediaLoader, CoroutineScope(SupervisorJob() + Dispatchers.Default))
    }
```

import 加：`com.smartvision.gallery.ui.viewer.JxlFullResPrecacher`

- [ ] **Step 2: 编译验证**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/smartvision/gallery/SmartVisionApp.kt
git commit -m "feat(viewer): expose JxlFullResPrecacher singleton"
```

---

### Task 3: PhotoViewerActivity isNextGen 分支接入

**Files:**
- Modify: `app/src/main/java/com/smartvision/gallery/ui/viewer/PhotoViewerActivity.kt`（914-1043 行 isNextGen 分支）

- [ ] **Step 1: 加 state**

在 923 行 `var tooLarge ...` 后加：

```kotlin
                    var fullResFile by remember(uri) { mutableStateOf<File?>(null) }
```

- [ ] **Step 2: 触发预解码**

在 944 行 `tooLarge = JxlProgressiveController.isTooLarge(sourceProbe)` 后、946 行 initialTarget 计算前插入：

```kotlin
                        // 16K-32K sources: kick off the background full-res
                        // pre-decode now. It re-decodes at stride=8 into a
                        // JPEG cache file; when ready the branch below swaps
                        // to SubSamplingZoomableImage for native-pixel zoom
                        // (BitmapRegionDecoder tile decoding under the hood).
                        if (!tooLarge && sourceProbe >= JxlFullResPrecacher.MIN_TRIGGER_LONG_EDGE_PX) {
                            app.jxlFullResPrecacher.start(uri) { file ->
                                fullResFile = file
                            }
                        }
```

- [ ] **Step 3: 显示切换**

把 969 行 `when {` 改为在 tooLarge 分支后插一个 fullResFile 分支：

```kotlin
                    when {
                        fullResFile != null -> SubSamplingZoomableImage(
                            uri = Uri.fromFile(fullResFile!!),
                            format = com.smartvision.gallery.decoder.format.MediaFormat.JPEG,
                            maxScale = maxScale,
                            midScale = midScale,
                            highScale = highScale,
                            onSingleTap = onSingleTap,
                        )
                        bitmap == null && !decodeError -> CircularProgressIndicator(
                            color = Color.White.copy(alpha = 0.6f),
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(32.dp)
                        )
                        decodeError -> CorruptImagePlaceholder()
                        else -> { /* 现有 DC progressive 分支原样保留 */ }
                    }
```

注意：`File` import 已在 PhotoViewerActivity 顶部（1183 行附近有 `import java.io.File`？若无则加）。

- [ ] **Step 4: 编译验证**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: 设备验证**

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -W -n com.smartvision.gallery.debug/.ui.viewer.PhotoViewerActivity -f 0x14008000 \
  --es extra_uri 'file:///sdcard/Download/test16k.jxl' --es extra_display_name 'test16k.jxl' --ei extra_start_index 0
```

验证：
- [ ] 首屏 <200ms 出 1024 预览（DC progressive 路径）
- [ ] 5-10s 后自动切换到 JPEG 全分辨率（无闪烁/无黑屏）
- [ ] 缩放至 40×：1:1 无锯齿（BitmapRegionDecoder 原生像素）
- [ ] `run-as com.smartvision.gallery.debug ls files/` 无报错（确认未 crash）
- [ ] 切回同图：立即显示全分辨率（cacheFile 命中）

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/smartvision/gallery/ui/viewer/PhotoViewerActivity.kt
git commit -m "feat(viewer): swap to SubSampling full-res JPEG for 16K-32K JXL"
```

---

### Task 4: 更新 rev.5 spec 记录

**Files:**
- Modify: `docs/superpowers/specs/2026-08-19-jxl-32k-safety-limit-spec.md`

- [ ] **Step 1: 追加快照**

在文件末尾追加：

```markdown
## rev.6 追加（2026-08-19）：≥16K JXL 后台 JPEG 预解码

见 `docs/superpowers/specs/2026-08-19-jxl-tile-cache-design.md`。实现参数：stride=8（target=4096）、
单文件 JPEG 质量 95、cacheDir `JXL_FullRes/<md5(uri)>.jpg`、触发范围源长边 16384..32768、
首屏 1024 DC 预览 + 后台切换 SubSamplingZoomableImage（BitmapRegionDecoder 1:1）。
```

- [ ] **Step 2: Commit**

```bash
git add docs/superpowers/specs/2026-08-19-jxl-32k-safety-limit-spec.md
git commit -m "docs: record rev.6 JXL full-res JPEG predecode"
```

---

## 验收标准（总）

- [ ] 32K JXL：首屏 1024 预览 → 后台完成后切换 JPEG 全分辨率，40× 缩放 1:1 无锯齿
- [ ] 16K JXL：同行为，JPEG 更小
- [ ] 8K 及以下：不触发预解码，rev.4 stride 方案不变
- [ ] >32K：tooLarge banner，不预解码
- [ ] 预解码失败静默降级（仍用 DC 预览）
- [ ] 切回已缓存图：跳过解码直接全分辨率

## 已知限制

- 32K stride=8 输出 4096×3072（12.6MP），非源 1:1；8× 缩放内视觉等效 1:1（屏幕像素 ≥ 源像素）
- JPEG 95 视觉无损
- 首次 5-10s 后台代价，期间 1024 预览
- 4096 输出临时占 48MB heap，写完即 recycle