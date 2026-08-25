# JXL ≥16K 后台预解码为整张 JPEG 设计

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task.

**Goal:** JXL 源 ≥16K 时后台异步将整图 stride=8 解码后 compress 成单张 JPEG 95 落盘，viewer 通过现有 `SubSamplingZoomableImage`（Telephoto + `BitmapRegionDecoder`）按视区加载，实现 1:1 无损缩放。32K 上限不变，>32K 仍走 tooLarge banner。

**Architecture:** 单类 `JxlFullResPrecacher` 后台协程：mmap → libjxl stride=8 解码 → Bitmap compress JPEG 95 → 写 cacheDir → 通知 controller 切换显示路径。viewer 端直接复用现有 `SubSamplingZoomableImage(uri=jpegFileUri)`，零新 UI 代码。

**Tech Stack:** Kotlin 协程，libjxl JNI（已有 stride=8 解码能力），Android `Bitmap.compress(JPEG, 95)`，Telephoto `SubSamplingImageSource.contentUri`（内置 `BitmapRegionDecoder` 分块解码）。

---

## 决策

| 决策 | 值 | 理由 |
|---|---|---|
| 触发阈值 | 源长边 ≥ 16K | <16K 走 rev.4 stride=1 输出 4096，已清晰 |
| 预解码 stride | 8（源/8） | 32K→4096 输出；16K→2048 输出。Canvas 48MP 安全，Bitmap 48MB 临时 |
| JPEG 质量 | 95 | 视觉无损，压缩比高，`BitmapRegionDecoder` 原生支持 |
| 单文件 vs 多 tile | **单文件** | Telephoto `BitmapRegionDecoder` 自己按视区分块，不需要我切 |
| 缓存位置 | `context.cacheDir/JXL_FullRes/<md5(uri)>.jpg` | 应用清缓存自动释放 |
| 首屏策略 | 先 display stride=32 的 1024 预览（已有 rev.5 tooLarge 分支思路复用）；后台完成后切到 JPEG 全分辨率 | 冷启动 <200ms 出图 |
| 切换机制 | `JxlProgressiveController` 收到完成信号 → 设置 `fullResCacheFile` state → `PhotoViewerActivity` 从 isNextGen DC 分支切到 `SubSamplingZoomableImage` | 透明过渡 |
| 32K 源输出 | 4096×3072 JPEG ~3-8MB | 磁盘合理 |
| 16K 源输出 | 2048×1536 JPEG ~1-3MB | 首次预解码代价小 |

## 数据流

```
源 JXL (32768×24576)
  │
  ├─ 首屏 (0ms): NativeBridge.decodeJxlScaled(uri, 1024)
  │    → stride=32 → 1024 预览 → 立即显示
  │
  └─ 后台 (Dispatchers.IO):
       1. JxlFullResPrecacher.start(uri, sourceLongEdge)
       2. 若 cacheFile 已存在 → 跳过解码，直接 onComplete
       3. nativeDecodeJxlFd(uri, target=4096, stride=8)
          → 4096×3072 ARGB_8888 Bitmap (48MB 临时)
       4. FileOutputStream(cacheFile).use { bitmap.compress(JPEG, 95, it) }
       5. bitmap.recycle()
       6. onComplete(cacheFile) on Main

显示切换:
  PhotoViewerActivity 持 fullResFile state
    ├─ null → isNextGen DC progressive 分支（1024 预览）
    └─ not null → SubSamplingZoomableImage(uri=Uri.fromFile(fullResFile))
       → BitmapRegionDecoder 按视区解码 → 1:1 无损

用户缩放操作:
  任何 scale → BitmapRegionDecoder 自动选 sample size → 1:1 原生像素
```

## 关键类型

### JxlFullResPrecacher (新, ~80 行)

```kotlin
class JxlFullResPrecacher(
    private val context: Context,
    private val mediaLoader: MediaLoader,
    private val scope: CoroutineScope
) {
    private val jobs = mutableMapOf<Uri, Job>()

    fun start(
        uri: Uri,
        sourceLongEdge: Long,
        onReady: (File) -> Unit
    ): Job? {
        val cacheFile = cacheFile(uri)
        if (cacheFile.exists() && cacheFile.length() > 0) {
            scope.launch(Dispatchers.Main) { onReady(cacheFile) }
            return null
        }
        jobs[uri]?.let { return it }
        val job = scope.launch(Dispatchers.IO) {
            try {
                val outputPx = (sourceLongEdge / 8).toInt().coerceAtMost(6144)
                val payload = mediaLoader.loadFullUri(uri, MediaFormat.JXL, outputPx)
                val bitmap = payload.bitmap ?: return@launch
                cacheFile.parentFile?.mkdirs()
                FileOutputStream(cacheFile).use { fos ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 95, fos)
                }
                bitmap.recycle()
                withContext(Dispatchers.Main) { onReady(cacheFile) }
            } catch (e: Exception) {
                // 静默降级，仍用 DC 预览
            }
        }
        jobs[uri] = job
        return job
    }

    private fun cacheFile(uri: Uri): File =
        File(context.cacheDir, "JXL_FullRes/${md5(uri.toString())}.jpg")

    companion object {
        fun md5(s: String): String = /* ... */
    }
}
```

### PhotoViewerActivity isNextGen 分支改动

```kotlin
// 新增 state
var fullResFile by remember(uri) { mutableStateOf<File?>(null) }

// LaunchedEffect(uri) 中，预解码分支
if (sourceLongEdge in 16384..32768) {
    app.jxlFullResPrecacher.start(uri, sourceLongEdge) { file ->
        fullResFile = file
    }
}

// 显示分支
when {
    tooLarge -> /* rev.5 banner + 1024 预览 */
    fullResFile != null -> SubSamplingZoomableImage(
        uri = Uri.fromFile(fullResFile),
        format = MediaFormat.JPEG,
        maxScale = maxScale,
        midScale = midScale,
        highScale = highScale,
        onSingleTap = onSingleTap,
    )
    else -> /* rev.5 isNextGen DC progressive 分支 */
}
```

## 改动文件

| 文件 | 类型 | 改动 |
|---|---|---|
| `app/src/main/java/com/smartvision/gallery/ui/viewer/JxlFullResPrecacher.kt` | 新增 | 后台预解码 + JPEG 落盘 |
| `app/src/main/java/com/smartvision/gallery/ui/viewer/PhotoViewerActivity.kt` | 修改 | isNextGen 分支新增 fullResFile state + 切换显示 |
| `app/src/main/java/com/smartvision/gallery/SmartVisionApp.kt` | 修改 | 单例 `jxlFullResPrecacher` |
| `docs/superpowers/specs/2026-08-19-jxl-32k-safety-limit-spec.md` | 修改 | 追加 rev.6 章节引用本设计 |

**不改：** JNI、NativeBridge、Decoder 接口、SubSamplingZoomableImage、JxlProgressiveController（保留作为 <16K 路径）。

## 缓存生命周期

- 创建：首次打开 ≥16K 且 ≤32K JXL 时后台生成
- 读取：同 session 切回该图时直接读 cacheFile
- 失效：cacheFile 不存在或 length=0 时重建
- 清理：`context.cacheDir` 被系统/用户清理时自动删除

## 验收标准

- [ ] 32K JXL 冷启动：<200ms 显示 1024 预览；5-10s 后台完成后切到 JPEG 全分辨率
- [ ] 32K JXL 缩放至 40×：1:1 无锯齿（`BitmapRegionDecoder` 原生像素）
- [ ] 16K JXL 同行为，cacheFile 更小
- [ ] 8K JXL 及以下：不触发预解码，仍走 rev.4 stride 方案
- [ ] >32K JXL：仍走 tooLarge banner，不预解码
- [ ] 预解码失败（OOM/IO）时静默降级，仍用 1024 预览
- [ ] 切回已缓存的图：跳过解码，直接 SubSamplingZoomableImage

## 已知限制

- 32K stride=8 = 4096 输出，非源 1:1（源/8）；但缩放 8× 以内每个屏幕像素对应 ≤1 源像素，视觉等效 1:1
- JPEG 95 有极轻微损失，视觉不可见
- 首次冷启动到 cacheFile 就绪需 5-10s，期间只能用 1024 预览
- 4096 输出 Bitmap 占 48MB 临时 heap，解码完成后释放
- 不做跨 session 持久化（用户决策：cacheDir 自动释放）
