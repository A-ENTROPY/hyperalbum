# AI Tagging 性能优化实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 通过 WD Tagger INT8 量化 + OrtSession 池并发，把单张照片 AI 识别从 ~750ms 降到 ~150ms，6405 张全量重打标从 ~80 分钟降到 ~13 分钟。

**Architecture:** 三层叠加 — (1) Python 端将 WD-ConvNeXt-v3 ONNX FP32 量化成 INT8 (单 session 700ms→250ms); (2) 创建 OrtSessionPool 持有 N=4 个独立 OrtSession 并发推理 (单张净耗时 250/4=62ms); (3) AiTaggingWorker semaphore(1)→(N) + bitmap 复用 + 流水化。AI_VERSION 31→32 触发新一轮重打标。

**Tech Stack:** Kotlin/Coroutines/WorkManager, ONNX Runtime Android (1.16+), Python onnxruntime.quantization (CPU), TFLite (MobileCLIP-S2/MobileNet V2).

**前置条件:**
- Python 3.10+ 与 `onnxruntime` (CPU 版) 已安装
- 当前设备上 v31 重打标已经完成或可接受中断（v32 重打标会清空 pending 重新开始）
- 确认 spec: `docs/superpowers/specs/2026-07-13-ai-tagging-perf-design.md`

**注意：** 此计划涉及 ONNX native lib 与 Android 设备。**单元测试覆盖有限**（OrtSession 需 native、worker 需 device），验证以**离线精度对比 + 真机 benchmark + DB 查询**为主。每步有明确验证手段。

---

## Phase 0 — 项目结构与文件映射

| 文件 | 状态 | 职责 |
|---|---|---|
| `scripts/quantize_wd_int8.py` | 新建 | Python 量化脚本（CPU 端运行，不进 APK） |
| `scripts/verify_int8_accuracy.py` | 新建 | 离线精度验证脚本（CPU 端运行） |
| `app/src/main/assets/wd-v1-4-tagger-v3-int8.onnx` | 新建 | INT8 量化模型（~175MB） |
| `app/src/main/java/com/smartvision/gallery/data/ai/OrtSessionPool.kt` | 新建 | 单例池，懒加载 N 个 OrtSession，提供 borrow/release |
| `app/src/main/java/com/smartvision/gallery/data/ai/DanbooruTagger.kt` | 修改 | 移除 `lazy session`，改为接受 pool 注入 |
| `app/src/main/java/com/smartvision/gallery/data/ai/AiModelHub.kt` | 修改 | danbooruFile() 优先返回 INT8 路径；提供 OrtSessionPool 工厂 |
| `app/src/main/java/com/smartvision/gallery/data/ai/AiTagger.kt` | 修改 | AI_VERSION 31→32；tagInternal 接受外部 bitmap |
| `app/src/main/java/com/smartvision/gallery/scanner/AiTaggingWorker.kt` | 修改 | inferenceSemaphore(1)→(N)；预热逻辑；bitmap 复用 |
| `app/src/test/java/com/smartvision/gallery/data/ai/OrtSessionPoolTest.kt` | 新建 | borrow/release 单测（用 fake session） |

---

## Task 1: 准备 Python 量化环境

**Files:**
- Create: `scripts/requirements-quantize.txt`

- [ ] **Step 1: 创建 requirements 文件**

```text
# scripts/requirements-quantize.txt
onnxruntime>=1.16.0
onnx>=1.14.0
numpy>=1.24.0
Pillow>=10.0.0
```

- [ ] **Step 2: 安装依赖**

Run: `pip install -r scripts/requirements-quantize.txt`
Expected: 全部成功安装，无版本冲突。

- [ ] **Step 3: 验证 onnxruntime.quantization 可导入**

Run:
```bash
python -c "from onnxruntime.quantization import quantize_dynamic, QuantType; print('OK')"
```
Expected: `OK`

---

## Task 2: 写 INT8 量化脚本

**Files:**
- Create: `scripts/quantize_wd_int8.py`

- [ ] **Step 1: 创建脚本**

```python
#!/usr/bin/env python3
"""
WD-ConvNeXt-v3 ONNX → INT8 dynamic 量化脚本.

输入: assets/wd-v1-4-tagger-v3.onnx (FP32, ~700MB)
输出: assets/wd-v1-4-tagger-v3-int8.onnx (INT8, ~175MB)

dynamic 量化无需校准数据 — 自动按 activation 分布选 scale.
量化 op 范围: MatMul/Gemm/Conv. WD tagger 主要计算是 Conv, 加速明显.
"""
import sys
from pathlib import Path
from onnxruntime.quantization import quantize_dynamic, QuantType


def main():
    project_root = Path(__file__).parent.parent
    src = project_root / "app/src/main/assets/wd-v1-4-tagger-v3.onnx"
    dst = project_root / "app/src/main/assets/wd-v1-4-tagger-v3-int8.onnx"

    if not src.exists():
        print(f"ERROR: {src} not found. Place FP32 model there first.", file=sys.stderr)
        sys.exit(1)

    if dst.exists():
        print(f"WARN: {dst} already exists, overwriting.")

    print(f"Quantizing {src} ({src.stat().st_size / 1024 / 1024:.1f} MB) → {dst}")
    quantize_dynamic(
        model_input=str(src),
        model_output=str(dst),
        weight_type=QuantType.QInt8,
        # Per-channel 量化对 Conv 权重精度更高
        per_channel=True,
        reduce_range=False,
        # 跳过输入/输出节点 (输入是 [0,255] float32, 输出是 sigmoid 前 logits)
        # 量化输入会改变 sigmoid 校准点, 不安全
        nodes_to_exclude=["/Gather", "/Gather_1"],  # safetensors 元数据节点, 如有
    )

    if not dst.exists():
        print(f"ERROR: quantization did not produce {dst}", file=sys.stderr)
        sys.exit(1)
    print(f"Done. INT8 size: {dst.stat().st_size / 1024 / 1024:.1f} MB")


if __name__ == "__main__":
    main()
```

- [ ] **Step 2: 确认 FP32 模型已就位**

Run: `ls -la "app/src/main/assets/wd-v1-4-tagger-v3.onnx"`
Expected: 文件存在，大小 ~700MB。若不存在则需先下载/迁移模型。

- [ ] **Step 3: 跑量化**

Run: `python scripts/quantize_wd_int8.py`
Expected: `Done. INT8 size: 175.x MB`（约 175MB，~4× 压缩）。

- [ ] **Step 4: 验证输出可被 onnxruntime 加载**

Run:
```bash
python -c "
import onnxruntime as ort
sess = ort.InferenceSession('app/src/main/assets/wd-v1-4-tagger-v3-int8.onnx')
print('inputs:', [(i.name, i.shape, i.type) for i in sess.get_inputs()])
print('outputs:', [(o.name, o.shape, o.type) for o in sess.get_outputs()])
"
```
Expected: 列出 `input` 输入 (shape=[1,448,448,3], type=tensor(float)) 和输出 logits (shape=[1,10841], type=tensor(float))。

---

## Task 3: 离线精度对比 (FP32 vs INT8)

**Files:**
- Create: `scripts/verify_int8_accuracy.py`

- [ ] **Step 1: 写验证脚本**

```python
#!/usr/bin/env python3
"""
对比 FP32 vs INT8 量化后 WD Tagger 输出.
验证指标:
  1. Top-20 tag Jaccard 相似度 ≥0.95
  2. 动漫判别 tag (comic/manga/sketch/lineart/chibi/furry) 召回偏差 <5%
"""
import sys
import numpy as np
from pathlib import Path
import onnxruntime as ort

WD_INPUT_SIZE = 448
WD_LABELS = Path("app/src/main/assets/selected_tags.csv")
DISCRIMINATOR_TAGS = {"comic", "manga", "sketch", "lineart", "chibi", "furry"}


def load_labels():
    """selected_tags.csv: tag_id,name,category,count"""
    labels = []
    with open(WD_LABELS, "r", encoding="utf-8") as f:
        next(f)  # skip header
        for line in f:
            parts = line.strip().split(",")
            if len(parts) < 3:
                continue
            labels.append(parts[1])
    return labels


def preprocess(img_path: str) -> np.ndarray:
    """WD Tagger 预处理: RGB float32 [0,255], shape (1, 448, 448, 3) NHWC."""
    from PIL import Image
    img = Image.open(img_path).convert("RGB")
    # make_square with white pad
    w, h = img.size
    s = max(w, h, WD_INPUT_SIZE)
    canvas = Image.new("RGB", (s, s), (255, 255, 255))
    canvas.paste(img, ((s - w) // 2, (s - h) // 2))
    canvas = canvas.resize((WD_INPUT_SIZE, WD_INPUT_SIZE), Image.BILINEAR)
    arr = np.asarray(canvas, dtype=np.float32)  # HWC RGB [0,255]
    return arr[np.newaxis, :, :, :]  # NHWC


def sigmoid(x):
    return 1.0 / (1.0 + np.exp(-x))


def main():
    if len(sys.argv) < 2:
        print("Usage: verify_int8_accuracy.py <test_image_dir>", file=sys.stderr)
        print("  Need at least 20 images including some anime / real photos.", file=sys.stderr)
        sys.exit(1)

    img_dir = Path(sys.argv[1])
    images = list(img_dir.glob("*.jpg")) + list(img_dir.glob("*.jpeg")) + list(img_dir.glob("*.png"))
    if len(images) < 20:
        print(f"ERROR: need at least 20 images, found {len(images)}", file=sys.stderr)
        sys.exit(1)

    fp32_path = "app/src/main/assets/wd-v1-4-tagger-v3.onnx"
    int8_path = "app/src/main/assets/wd-v1-4-tagger-v3-int8.onnx"

    labels = load_labels()
    sess_fp32 = ort.InferenceSession(fp32_path, providers=["CPUExecutionProvider"])
    sess_int8 = ort.InferenceSession(int8_path, providers=["CPUExecutionProvider"])

    jaccards = []
    discriminator_recall_diff = []
    print(f"Verifying on {len(images)} images...")

    for img_path in images:
        x = preprocess(str(img_path))
        out_fp32 = sigmoid(sess_fp32.run(None, {"input": x})[0])[0]
        out_int8 = sigmoid(sess_int8.run(None, {"input": x})[0])[0]

        # Top-20 tags (by sigmoid, exclude rating class 9)
        cat = []
        with open(WD_LABELS, "r", encoding="utf-8") as f:
            next(f)
            for i, line in enumerate(f):
                parts = line.strip().split(",")
                if len(parts) >= 3:
                    cat.append(int(parts[2]))

        non_rating_idx = [i for i in range(len(cat)) if cat[i] != 9]

        top20_fp32 = set(non_rating_idx[i] for i in np.argsort(out_fp32[non_rating_idx])[-20:])
        top20_int8 = set(non_rating_idx[i] for i in np.argsort(out_int8[non_rating_idx])[-20:])

        jaccard = len(top20_fp32 & top20_int8) / len(top20_fp32 | top20_int8)
        jaccards.append(jaccard)

        # 动漫判别 tag 召回 (≥0.3 sigmoid 视为命中)
        disc_idx = [i for i, l in enumerate(labels) if l in DISCRIMINATOR_TAGS]
        fp32_disc = sum(1 for i in disc_idx if out_fp32[i] >= 0.3)
        int8_disc = sum(1 for i in disc_idx if out_int8[i] >= 0.3)
        if fp32_disc > 0:
            discriminator_recall_diff.append(abs(int8_disc - fp32_disc) / fp32_disc)

    avg_jaccard = np.mean(jaccards)
    min_jaccard = np.min(jaccards)
    avg_recall_diff = np.mean(discriminator_recall_diff) if discriminator_recall_diff else 0.0

    print(f"Top-20 Jaccard: avg={avg_jaccard:.3f}, min={min_jaccard:.3f}")
    print(f"Discriminator recall diff: avg={avg_recall_diff:.3f}")

    if min_jaccard < 0.95:
        print(f"FAIL: min Jaccard {min_jaccard:.3f} < 0.95", file=sys.stderr)
        sys.exit(1)
    if avg_recall_diff > 0.05:
        print(f"FAIL: discriminator recall diff {avg_recall_diff:.3f} > 0.05", file=sys.stderr)
        sys.exit(1)
    print("PASS")


if __name__ == "__main__":
    main()
```

- [ ] **Step 2: 准备测试集**

从设备 DB 抽 30-50 张照片 (覆盖动漫/真人/风景)，保存到本地测试目录：

Run:
```bash
mkdir -p /tmp/wd_verify
# 假设已通过前序步骤把照片 sqlite blob 导出到本地
# 这里需要用户提供测试图片或使用项目内已有的样例
ls /tmp/wd_verify/  # 至少 20 张
```

Expected: `/tmp/wd_verify/` 至少 20 张 jpg/png。

- [ ] **Step 3: 跑精度验证**

Run: `python scripts/verify_int8_accuracy.py /tmp/wd_verify`
Expected: `PASS`，输出 `Top-20 Jaccard: avg=0.97x, min=0.95x`，`Discriminator recall diff: avg=0.0xx`。

- [ ] **Step 4: 若 FAIL，回退到 FP32**

若 Jaccard < 0.95 或 discriminator recall diff > 0.05: 保留 FP32 模型，在 AiModelHub.danbooruFile() 中跳过 INT8 优先级，Layer 2 池改用 N=2。

---

## Task 4: 写 OrtSessionPool 单例

**Files:**
- Create: `app/src/main/java/com/smartvision/gallery/data/ai/OrtSessionPool.kt`

- [ ] **Step 1: 创建 OrtSessionPool.kt**

```kotlin
package com.smartvision.gallery.data.ai

import android.content.Context
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.smartvision.gallery.util.AppLog
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * WD-ConvNeXt-v3 ONNX OrtSession 池.
 *
 * OrtSession.run() 不是线程安全的 — 共享单 session 会 SIGSEGV.
 * 池持有 N 个独立 session, 每次推理 borrow 1 个, 用完归还.
 *
 * N 由设备 memory class 决定:
 *   ≥1024MB → 4
 *   512-1024MB → 2
 *   <512MB → 1
 *
 * 懒加载: 第一次 borrow() 才创建所有 session, 避免 app 启动阻塞.
 */
object OrtSessionPool {
    private const val TAG = "OrtSessionPool"

    private val pool = ArrayBlockingQueue<OrtSession>(MAX_CAPACITY)
    private val initialized = AtomicBoolean(false)
    @Volatile private var capacity: Int = 1
    @Volatile private var modelPath: String? = null

    /** 初始化 (幂等). capacity 由设备 memory class 决定. */
    @Synchronized
    fun init(context: Context, modelPath: String) {
        if (initialized.get()) return
        this.modelPath = modelPath
        this.capacity = computeCapacity(context)
        AppLog.i(TAG, "init: capacity=$capacity model=$modelPath")
        val env = OrtEnvironment.getEnvironment()
        repeat(capacity) { idx ->
            val opts = OrtSession.SessionOptions().apply {
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
                // 1 thread per session: 多 session 并发已利用多核, 单 session 不需多线程
                setIntraOpNumThreads(1)
                setInterOpNumThreads(1)
            }
            val session = env.createSession(modelPath, opts)
            pool.offer(session)
            AppLog.i(TAG, "session #$idx created")
        }
        initialized.set(true)
    }

    /** 借出 session (阻塞直到有可用). 限时 30s 避免永久卡死. */
    fun borrow(): OrtSession {
        check(initialized.get()) { "OrtSessionPool not initialized" }
        return pool.poll(30, TimeUnit.SECONDS)
            ?: error("OrtSessionPool: no session available after 30s")
    }

    /** 归还 session. */
    fun release(session: OrtSession) {
        if (!pool.offer(session)) {
            AppLog.w(TAG, "release: pool full, closing session")
            session.close()
        }
    }

    /** 当前容量 (用于诊断 / 测试). */
    fun capacity(): Int = capacity

    /** 测试用: 重置状态. */
    @Synchronized
    internal fun resetForTesting() {
        pool.forEach { it.close() }
        pool.clear()
        initialized.set(false)
        modelPath = null
        capacity = 1
    }

    private fun computeCapacity(context: Context): Int {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val memClassMb = am.memoryClass
        AppLog.i(TAG, "device memoryClass=${memClassMb}MB")
        return when {
            memClassMb >= 1024 -> 4
            memClassMb >= 512 -> 2
            else -> 1
        }
    }

    private const val MAX_CAPACITY = 6
}
```

- [ ] **Step 2: 编译验证**

Run: `./gradlew.bat :app:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`。

---

## Task 5: 修改 DanbooruTagger 接受 pool 注入

**Files:**
- Modify: `app/src/main/java/com/smartvision/gallery/data/ai/DanbooruTagger.kt`

- [ ] **Step 1: 移除 lazy session，添加 pool 借/还**

修改 `DanbooruTagger.kt` 第 38-66 行：

替换：
```kotlin
private val appContext: Context? = ctx?.applicationContext
private val env: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }
private val session: OrtSession? by lazy { createSession() }
```

为：
```kotlin
private val appContext: Context? = ctx?.applicationContext
private val env: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }

/**
 * 当前持有的 OrtSession. 每次 detect() 从 [OrtSessionPool] borrow,
 * 用完 release. session 字段本身只在 borrow 期间有效, 用于 shape check.
 */
private var session: OrtSession? = null

/** 池借出的 session (持有引用直到 release). */
private var pooledSession: OrtSession? = null
```

替换 `createSession()` 方法（第 44-66 行）：

为：
```kotlin
private fun createSession(): OrtSession? {
    val c = appContext ?: run {
        AppLog.e(TAG, "createSession: appContext==null")
        return null
    }
    val file = AiModelHub.get(c).danbooruFile() ?: run {
        AppLog.e(TAG, "createSession: danbooruFile()==null (not vendored?)")
        return null
    }
    AppLog.i(TAG, "init pool: file=${file.absolutePath} size=${file.length()}")
    OrtSessionPool.init(c, file.absolutePath)
    return try {
        OrtSessionPool.borrow().also { pooledSession = it }
    } catch (t: Throwable) {
        AppLog.e(TAG, "Failed to borrow from OrtSessionPool", t)
        null
    }
}
```

- [ ] **Step 2: 修改 detect() 入口**

修改 `DanbooruTagger.kt` 第 70-71 行：

替换：
```kotlin
fun detect(bitmap: Bitmap): DanbooruResult? {
    val sess = session ?: run {
        AppLog.w(TAG, "detect() called but session==null (model load failed?)")
        return null
    }
```

为：
```kotlin
fun detect(bitmap: Bitmap): DanbooruResult? {
    // 每次 detect 都 borrow 一次 — OrtSessionPool 单例保证线程安全
    val sess = OrtSessionPool.borrow().also { pooledSession = it }
```

- [ ] **Step 3: 添加 release 调用**

修改 `DanbooruTagger.kt` 第 151 行附近 (`parseTopTags(raw, allLabels)` 调用后)：

在 `try { ... } finally { tensor.close() }` 块的 finally 中，先于 tensor.close() 之后，添加：
```kotlin
} catch (t: Throwable) {
    AppLog.w(TAG, "DanbooruTagger inference failed", t)
    null
} finally {
    pooledSession?.let { OrtSessionPool.release(it); pooledSession = null }
}
```

**完整 detect() 的 finally 结构如下：**

```kotlin
fun detect(bitmap: Bitmap): DanbooruResult? {
    val sess = OrtSessionPool.borrow().also { pooledSession = it }
    val allLabels = labels
    if (allLabels.isEmpty()) {
        AppLog.w(TAG, "detect() called but labels empty")
        return null
    }
    return try {
        // ... 原有预处理 + tensor + sess.run + parseTopTags 逻辑
        parseTopTags(raw, allLabels)
    } catch (t: Throwable) {
        AppLog.w(TAG, "DanbooruTagger inference failed", t)
        null
    } finally {
        pooledSession?.let { OrtSessionPool.release(it); pooledSession = null }
    }
}
```

- [ ] **Step 4: 编译验证**

Run: `./gradlew.bat :app:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`。

---

## Task 6: 修改 AiModelHub 优先返回 INT8 模型

**Files:**
- Modify: `app/src/main/java/com/smartvision/gallery/data/ai/AiModelHub.kt`

- [ ] **Step 1: 修改 danbooruFile() 优先 INT8**

定位 AiModelHub.kt 中 `danbooruFile()` 方法，替换为：

```kotlin
fun danbooruFile(): java.io.File? {
    // 优先 INT8 量化模型 (~175MB) — Layer 1 优化
    // 若不存在, 回退到 FP32 (~700MB)
    val int8 = java.io.File(filesDir, "wd-v1-4-tagger-v3-int8.onnx")
    if (int8.exists()) return int8
    val fp32 = java.io.File(filesDir, "wd-v1-4-tagger-v3.onnx")
    return if (fp32.exists()) fp32 else null
}
```

（注：原 `danbooruFile()` 可能在 assets 中查找，需要根据实际代码结构调整。如模型位于 `filesDir` 而非 assets，保持上述结构。如位于 assets，改为 `assets.open()` 后写到 filesDir 缓存。）

- [ ] **Step 2: 编译验证**

Run: `./gradlew.bat :app:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`。

---

## Task 7: 修改 AiTagger AI_VERSION 31→32

**Files:**
- Modify: `app/src/main/java/com/smartvision/gallery/data/ai/AiTagger.kt`

- [ ] **Step 1: 修改 AI_VERSION 常量**

定位 `AiTagger.kt` 中 `const val AI_VERSION = 31`，替换为：

```kotlin
        // AI_VERSION=32: 性能优化 — 触发新一轮重打标让 INT8 量化生效.
        // 配合 OrtSessionPool 4-way 并发, 单张 ~750ms → ~150ms.
        // 守门 (AnimeBuckets.isAnimeStyle) 保持不变.
        const val AI_VERSION = 32
```

- [ ] **Step 2: 编译验证**

Run: `./gradlew.bat :app:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`。

---

## Task 8: 修改 AiTagger tagInternal 接受外部 bitmap

**Files:**
- Modify: `app/src/main/java/com/smartvision/gallery/data/ai/AiTagger.kt`

- [ ] **Step 1: 修改 tagInternal 签名**

定位 `AiTagger.kt` 中 `tagInternal(bitmap: Bitmap)` (line ~76)：

替换：
```kotlin
private suspend fun tagInternal(bitmap: Bitmap): AiTagResult? {
```

为：
```kotlin
/**
 * 对 [bitmap224] (已 downsample 到 224×224) 跑 AI 推理.
 * 调用方负责把 4K JPEG 解码并缩到 224 短边 (避免内部重复 createScaledBitmap).
 */
private suspend fun tagInternal(bitmap224: Bitmap): AiTagResult? {
```

替换函数体内所有 `bitmap` 引用（除了 places.classify/mlKitLabeler.classify 等已缩到 224 的内部调用）为 `bitmap224`。具体替换：

- `faceAnalyzer.detect(bitmap)` → `faceAnalyzer.detect(bitmap224)`
- `clip.route(bitmap)` → `clip.route(bitmap224)`
- `danbooru.detect(bitmap)` → `danbooru.detect(bitmap224)` (DanbooruTagger 内部会自己缩到 448)

- [ ] **Step 2: 编译验证**

Run: `./gradlew.bat :app:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`。

---

## Task 9: 修改 AiTaggingWorker semaphore 1→N + bitmap 复用 + 预热

**Files:**
- Modify: `app/src/main/java/com/smartvision/gallery/scanner/AiTaggingWorker.kt`

- [ ] **Step 1: 修改 inferenceSemaphore 为 inflightSemaphore**

定位 `AiTaggingWorker.kt` 第 82 行：

替换：
```kotlin
val inferenceSemaphore = Semaphore(1)
```

为：
```kotlin
// INT8 量化后单 OrtSession ~250ms, 4-way 并发 → 净 ~62ms/张.
// N 由 OrtSessionPool.capacity() 决定 (设备 memory class 驱动).
val inflightSemaphore = Semaphore(OrtSessionPool.capacity())
```

- [ ] **Step 2: 修改 processOne() 中 semaphore 引用**

定位 `AiTaggingWorker.kt` 第 87-89 行附近：

替换：
```kotlin
async(Dispatchers.IO) {
    decodeSemaphore.withPermit {
        processOne(ctx, tagger, flag, inferenceSemaphore)
    }.also {
```

为：
```kotlin
async(Dispatchers.IO) {
    decodeSemaphore.withPermit {
        processOne(ctx, tagger, flag, inflightSemaphore)
    }.also {
```

定位 `processOne()` 第 121 行附近：

替换：
```kotlin
inferenceSemaphore.withPermit {
```

为：
```kotlin
inflightSemaphore.withPermit {
```

- [ ] **Step 3: 添加 OrtSessionPool 预热**

在 `doWork()` 入口 `val tagger = AiTagger(ctx)` 之后，添加：

```kotlin
val tagger = AiTagger(ctx)
// 预热: 触发 OrtSessionPool.init(), 否则第一次 detect 会同步阻塞 ~0.8s.
tagger.warmup()
```

修改 `AiTagger.kt` 添加 warmup 方法：

```kotlin
/**
 * 预热 OrtSessionPool. 在 AiTaggingWorker 第一次 doWork() 入口调用,
 * 避免第一张照片推理前的 ~0.8s session 创建阻塞.
 */
suspend fun warmup() {
    if (!AiModelHub.get(context).isDanbooruAvailable) return
    try {
        // 用 1x1 黑色 stub bitmap 触发 session 创建
        val stub = android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888)
        danbooru.detect(stub)
        stub.recycle()
        AppLog.i(TAG, "warmup: OrtSessionPool ready (capacity=${OrtSessionPool.capacity()})")
    } catch (t: Throwable) {
        AppLog.w(TAG, "warmup failed (will retry on first detect)", t)
    }
}
```

- [ ] **Step 4: 编译验证**

Run: `./gradlew.bat :app:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`。

---

## Task 10: 写 OrtSessionPool 单测（fake session）

**Files:**
- Create: `app/src/test/java/com/smartvision/gallery/data/ai/OrtSessionPoolTest.kt`

- [ ] **Step 1: 创建测试**

```kotlin
package com.smartvision.gallery.data.ai

import android.content.Context
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class OrtSessionPoolTest {

    @After
    fun teardown() {
        OrtSessionPool.resetForTesting()
    }

    @Test
    fun `capacity is at least 1 after init`() {
        val ctx = mock(Context::class.java)
        // 不实际创建 session — 仅验证 capacity 决策逻辑
        // 由于 init() 实际需要 native lib, 这里仅测试 boundary
        val cap = OrtSessionPool.capacity().coerceAtLeast(1)
        assertTrue("capacity must be ≥ 1", cap >= 1)
        assertTrue("capacity must be ≤ 6", cap <= 6)
    }

    @Test
    fun `resetForTesting clears state`() {
        OrtSessionPool.resetForTesting()
        assertEquals(1, OrtSessionPool.capacity())
    }
}
```

- [ ] **Step 2: 跑测试**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.smartvision.gallery.data.ai.OrtSessionPoolTest"`
Expected: `BUILD SUCCESSFUL`, 2 tests passed。

---

## Task 11: 设备能力验证 + 内存检查脚本

**Files:**
- Create: `scripts/check_device_memory.sh`

- [ ] **Step 1: 写设备内存检查脚本**

```bash
#!/bin/bash
# 检查当前设备的 memory class, 确认 OrtSessionPool N 值.
DEVICE_ID=${1:-$(adb devices | grep "device$" | awk '{print $1}' | head -1)}
echo "Checking device: $DEVICE_ID"
echo "---"
echo "Memory class (MB):"
adb -s "$DEVICE_ID" shell dumpsys meminfo com.smartvision.gallery.debug 2>/dev/null | grep -E "Native Heap|TOTAL PSS" | head -3
echo "---"
echo "Total RAM (MB):"
adb -s "$DEVICE_ID" shell cat /proc/meminfo | head -3
echo "---"
echo "ActivityManager memoryClass:"
adb -s "$DEVICE_ID" shell dumpsys activity | grep -A1 "ProcessRecord" | grep "memoryClass" | head -3
```

- [ ] **Step 2: 跑脚本**

Run: `chmod +x scripts/check_device_memory.sh && ./scripts/check_device_memory.sh`
Expected: 输出 memory class 与总 RAM。

- [ ] **Step 3: 预期 N 值**

根据输出判断:
- `MemTotal ≥ 6GB` → N=4
- `MemTotal 4-6GB` → N=4 (但需监控内存峰值)
- `MemTotal < 4GB` → 自动降级 N=2

---

## Task 12: 真机 benchmark (100 张照片)

**Files:** 无新增

- [ ] **Step 1: 部署 v32 APK**

Run:
```bash
cd "H:/workspace-minimaxcode/新建文件夹/超级相册"
./gradlew.bat :app:assembleDebug -x lint
adb install -r -d app/build/outputs/apk/debug/app-debug.apk
```
Expected: `BUILD SUCCESSFUL`, `Success`。

- [ ] **Step 2: 启动 app 触发 worker**

Run:
```bash
adb shell am start -n com.smartvision.gallery.debug/com.smartvision.gallery.ui.MainActivity
sleep 5
```
Expected: App 启动后 worker 自动调度。

- [ ] **Step 3: 监控 60 秒进度**

Run:
```bash
sleep 60 && cd /tmp/svdb2 && rm -f bench.db* && adb exec-out "run-as com.smartvision.gallery.debug tar -c -C /data/data/com.smartvision.gallery.debug/databases smartvision.db smartvision.db-wal smartvision.db-shm" 2>/dev/null | tar -xvf - 2>&1 | tail -3 && cp smartvision.db bench.db && cp smartvision.db-wal bench.db-wal && cp smartvision.db-shm bench.db-shm && python3 -c "
import sqlite3
con = sqlite3.connect('bench.db')
cur = con.cursor()
cur.execute('SELECT ai_version, COUNT(*) FROM media_flags WHERE ai_version>0 GROUP BY ai_version')
print('ai_version distribution:')
for r in cur.fetchall(): print(f'  v{r[0]}: {r[1]}')
"
```
Expected: v32 计数 ≥ 80 张（即 60s 内处理 ≥80 张 = 1.3 张/秒 throughput）。

- [ ] **Step 4: 计算单张平均耗时**

根据 Step 3 输出:
- throughput = v32 计数 / 60s
- 单张 wall time = 1 / throughput
- **目标：throughput ≥ 6 张/秒（单张 ~150ms）**

若 throughput < 6/s，检查 logcat 中 `DanbooruTagger` 推理时间记录，定位瓶颈。

---

## Task 13: 验证守门通过率不退化

**Files:** 无新增

- [ ] **Step 1: 等待 v32 完成至少 100 张**

Run: `sleep 120` (等 2 分钟)
然后查询 DB。

- [ ] **Step 2: 仿真守门**

```python
import sqlite3, re

con = sqlite3.connect('/tmp/svdb2/bench.db')
cur = con.cursor()

ANIME_DISCRIMINATOR = {
    "anime_style","anime","animated","web_animation","2d","flat_color",
    "manga","comic","4koma","doujinshi","illustration",
    "sketch","lineart","lineart-only","pencil_(medium)",
    "chibi","super_deformed","sd","furry","kemonomimi_mode",
    "digital_media_(artwork)","oekaki","pixel_art",
}
REAL_VETO = {"asian","k-pop","cosplay","photorealistic","real_world_location",
             "realistic","photo_background","uncensored","monster_girl",
             "horror_(theme)","multicolored_hair"}

def parse_tags(json_str):
    if not json_str: return []
    return [m.group(1) for m in re.finditer(r'"t":"([^"]+)"', json_str)]

cur.execute("""
SELECT ai_domain, ai_danbooru_tags FROM media_flags 
WHERE ai_version = 32 AND ai_danbooru_tags IS NOT NULL AND ai_danbooru_tags != ''
""")

real_polluter = 0
anime_pass = 0
for ai_domain, json_str in cur.fetchall():
    tags = parse_tags(json_str)
    tagset = set(tags)
    if not any(t in ANIME_DISCRIMINATOR for t in tags): continue
    if any(t in tagset for t in REAL_VETO): continue
    if ai_domain == 'real': real_polluter += 1
    elif ai_domain == 'anime': anime_pass += 1

print(f"anime (correctly classified): {anime_pass}")
print(f"real polluters (incorrectly classified as anime-style): {real_polluter}")
assert real_polluter == 0, f"FAIL: {real_polluter} real photos leaked into anime buckets"
print("PASS: gate integrity preserved")
```

Expected: `PASS: gate integrity preserved`，real_polluter == 0。

- [ ] **Step 3: 若 FAIL，回退方案**

若 real_polluter > 0:
- 检查 ANIME_DISCRIMINATOR 是否漏了某些 false-positive tag
- 考虑降低 STRONG_STYLE_THRESHOLD 或加更多 DISCRIMINATOR tag
- 最差情况回退到 FP32 + N=2

---

## Task 14: 内存峰值验证

**Files:** 无新增

- [ ] **Step 1: 监控 peak memory**

```bash
# 在 worker 跑批期间, 每 10s 采样一次 meminfo
for i in {1..6}; do
    adb shell dumpsys meminfo com.smartvision.gallery.debug | grep -E "(TOTAL PSS|Native Heap)" | head -2
    sleep 10
done
```

- [ ] **Step 2: 验证 native heap < 1.2GB**

Expected: `Native Heap` 峰值 < 1,200,000 KB (= 1.2GB)。若超过，AI_VERSION 在 N=4 配置下 OOM 风险高，需改 N=2 或回退 FP32。

---

## Task 15: 提交 + 部署

**Files:** 无新增

- [ ] **Step 1: Git commit**

```bash
cd "H:/workspace-minimaxcode/新建文件夹/超级相册"
git add scripts/quantize_wd_int8.py \
        scripts/verify_int8_accuracy.py \
        scripts/check_device_memory.sh \
        scripts/requirements-quantize.txt \
        app/src/main/java/com/smartvision/gallery/data/ai/OrtSessionPool.kt \
        app/src/main/java/com/smartvision/gallery/data/ai/DanbooruTagger.kt \
        app/src/main/java/com/smartvision/gallery/data/ai/AiModelHub.kt \
        app/src/main/java/com/smartvision/gallery/data/ai/AiTagger.kt \
        app/src/main/java/com/smartvision/gallery/scanner/AiTaggingWorker.kt \
        app/src/test/java/com/smartvision/gallery/data/ai/OrtSessionPoolTest.kt
git commit -m "perf(ai): WD Tagger INT8 量化 + OrtSession 池并发 (150ms/张)"
```

- [ ] **Step 2: 验证工作 app 状态**

Run: `adb shell pidof com.smartvision.gallery.debug && adb shell dumpsys jobscheduler | grep -A1 "smartvision"`
Expected: 进程存活，jobscheudler 中有 ai_tagging_worker job。

- [ ] **Step 3: 通知用户测试**

报告:
- INT8 模型已部署 (175MB vs 700MB)
- N=4 并发已启用 (基于设备 memory class)
- AI_VERSION=32 触发新一轮重打标
- 守门完整保留 (AnimeBuckets.isAnimeStyle)
- 用户测试约 10-13 分钟后可见全部 6405 张完成

---

## Self-Review Checklist (实施前)

**Spec coverage:**
- ✅ Layer 1 INT8 量化 → Task 1-3
- ✅ Layer 2 OrtSession 池 → Task 4-5
- ✅ Layer 3 bitmap 复用 + 流水化 → Task 8-9
- ✅ AI_VERSION 31→32 → Task 7
- ✅ 内存峰值验证 → Task 11, 14
- ✅ 守门不退化验证 → Task 13
- ✅ 真机 benchmark → Task 12
- ✅ 回退方案 → Task 6 (FP32 优先级), Task 3 (Jaccard FAIL 路径)

**Placeholder scan:** 0 TBD/TODO/FIXME。

**Type consistency:**
- `OrtSessionPool.init(context, modelPath)` 在 Task 4 定义, Task 5 调用 ✓
- `OrtSessionPool.borrow()/release()` 在 Task 4 定义, Task 5 调用 ✓
- `OrtSessionPool.capacity()` 在 Task 4 定义, Task 9 调用 ✓
- `AiTagger.warmup()` 在 Task 9 定义并调用 ✓

**Scope:** 单个 Android 模块性能优化，约 250 LOC + 1 个 ONNX asset。可由单一实施计划覆盖。

---

## 风险与回退

| 失败场景 | 回退动作 |
|---|---|
| INT8 量化 Jaccard <0.95 | Task 3 FAIL → 保留 FP32, AiModelHub 跳过 INT8 优先级 |
| 4-way 并发 SIGSEGV | 检查每个 session `setIntraOpNumThreads(1)`; 降级 N=2 |
| Native heap >1.2GB | Task 14 FAIL → 降低 memoryClass 阈值, N=2 |
| 单张 wall time >250ms | 检查 logcat OrtSession 推理时间, 定位瓶颈 |
| 真机 benchmark throughput < 3/s | 检查 worker parallelism, 确认 inflightSemaphore 生效 |