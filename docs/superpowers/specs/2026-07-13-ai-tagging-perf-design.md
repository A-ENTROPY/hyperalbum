# AI 识别性能优化设计 (WD Tagger INT8 量化 + 多 OrtSession 并发)

**日期**：2026-07-13
**目标**：单张照片识别从 ~750ms 降到 ~150ms，6405 张全量重打标从 ~80 分钟降到 ~13 分钟
**约束**：保证 v30.2 动漫分类守门 (AnimeBuckets.isAnimeStyle) 不退化

---

## 1. 背景与现状

### 1.1 性能瓶颈分析

基于 v31 AiTaggingWorker 日志 (`adb logcat -s AiTaggingWorker:I DanbooruTagger:I`)：

| 阶段 | 当前耗时 | 占比 | 备注 |
|---|---|---|---|
| Bitmap decode (224 short edge) | ~200ms | 27% | 8-way 并行，理想情况下应被推理完全隐藏 |
| **WD ConvNeXt-v3 ONNX FP32** | **~700ms** | **93%** | **单线程 OrtSession** |
| MobileCLIP-S2 TFLite FP16 | ~30ms | 4% | 单 interpreter |
| Places365 / MobileNet / MLKit | ~20ms | 3% | 部分与上面并行 |
| **合计（pipeline 后净）** | **~750ms** | | |

单线程 OrtSession 是核心瓶颈：WD Tagger 一个模型占总时间 93%。

### 1.2 历史优化空间

当前 [AiTaggingWorker.kt:82] `inferenceSemaphore(1)` 串行推理（OrtSession 非线程安全）。注释明确："TFLite Interpreter.run() & ONNX OrtSession.run() are NOT thread-safe." 因此多模型并发需要 per-model 隔离或 per-session 隔离。

### 1.3 守门修复保留

v30.2 二次元分类守门 (AnimeBuckets.isAnimeStyle + MediaRepository.queryAnimeByBucket 入口同步 + AlbumListViewModel.animeCategoryFolders 单点判别门) 已经完成并部署。本次优化**不能破坏**这一守门。

---

## 2. 设计目标

| 指标 | 当前 | 目标 |
|---|---|---|
| 单张照片识别 | ~750ms | **~150ms** |
| 6405 张全量重打标 | ~80 分钟 | **~13 分钟** |
| 守门通过率（真人照误入率） | 0/234 | **保持 0** |
| 内存峰值 (native) | ~175MB | **<800MB** (4-way 并发) |
| INT8 量化动漫判别 tag 召回偏差 | n/a | **<5%** |

---

## 3. 方案：三层叠加优化

### Layer 1 — WD Tagger INT8 量化

**改动**：
- 用 ONNX Runtime `quantize_dynamic` Python 工具量化现有 WD-ConvNeXt-v3 ONNX 模型
- 输出 `wd-v1-4-tagger-v3-int8.onnx` 替换 `wd-v1-4-tagger-v3.onnx`
- AiModelHub.danbooruFile() 增加 int8 路径优先级

**预期收益**：
- 模型体积：~700MB → **~175MB**（4× 压缩）
- 加载时间：~3s → **~0.8s**
- 单 OrtSession 推理：~700ms → **~250ms**（2.8× 加速）

**精度影响**：
- INT8 dynamic 量化对激活分布主要影响 sigmoid 输出的小数位精度
- top-1 acc 误差 <0.5%（Danbooru WD Tagger 实测）
- 动漫判别 tag (comic/manga/sketch/lineart/chibi/furry) sigmoid 召回率偏差 <5%

**回退方案**：
- 若量化后 isAnimeStyle 守门通过率偏差 ≥5%，回退 FP32 + 改用 2-way 并发（净 ~350ms，仍优于当前 750ms）

### Layer 2 — OrtSession 池 + 并发限流

**改动**：
- 新建 `OrtSessionPool` (singleton)，持有 N 个 OrtSession 实例 + 独立 thread
- N 由设备 CPU 核数决定，默认 4，最低 2，最高 6
- AiTagger.danbooru() 改为从池借/还 session（类似数据库连接池）
- AiTaggingWorker `inferenceSemaphore(1)` → 改成 `inflightSemaphore(N)`，让 N 张图同时推理

**预期收益**：
- WD net time per photo = 250ms / 4 = **~62ms**
- 内存峰值：4 × 175MB = **~700MB native**

**风险缓解**：
- 启动时检查 `ActivityManager.getMemoryClass()`（单位 MB）：
  - ≥1024MB → N=4
  - 512-1024MB → N=2
  - <512MB → N=1（无并发）
- 预热：App 启动后第一次 inference 前先 warmup 1 张图，避免冷启动抖动

### Layer 3 — Bitmap 复用 + 流水化

**改动**：
- AiTagger.tagInternal(bitmap) 接收外部传入的 224×224 bitmap，不再内部多次 createScaledBitmap
- MobileCLIP / MobileNet / Places365 / MLKit Face 复用同一张 224×224 bitmap
- 真正并行：Bitmap decode 与上一张照片的 WD 推理完全流水（已经在 8-way decode 中实现）
- WD inference 与 MobileCLIP/MobileNet 流水（不等 WD 完成即开始）

**预期收益**：
- 去掉 3 次 createScaledBitmap 重复调用 → 节省 ~30ms
- 整体 latency 从"decode + 全部模型串行"变成"max(decode, WD/N, 其他模型)"

### 预期最终性能

单张照片 wall time（一张照片从进入到写完 DB 的端到端延迟）：

| 阶段 | 优化后耗时 | 备注 |
|---|---|---|
| Bitmap decode | ~80ms | 8-way 并行均摊 |
| WD INT8 (4-way 并发) | ~62ms | 250ms / 4 sessions |
| 其他模型 (流水化) | ~30ms | MobileCLIP / Places365 / MobileNet |
| **单张 wall time** | **~150ms** | decode 与 inference 部分重叠 |

整体吞吐（6405 张全量重打标总时长）：

- Decode 吞吐：8 路 × 80ms = **100 张/秒**
- WD 推理吞吐：4 路 × 250ms = **16 张/秒**
- **瓶颈在 WD：~6405 / 16 ≈ 400 秒 ≈ 6.7 分钟**

保守估算（包含 WM 调度 overhead、IO 阻塞）：**~10-13 分钟**。

---

## 4. 数据流

```
[Main] MediaScanner 写入 media row
  ↓
[AiTaggingWorker.doWork]
  ↓ flagDao.findPendingAi(AI_VERSION=32, limit=800)
[Worker] pending photos
  ↓ 8-way parallel decode (BitmapFactory + inSampleSize)
[Worker] Bitmap 224×224
  ↓ inflightSemaphore(N=4).withPermit { ... }
[AiTaggerPool] danbooru.detect(bitmap)  ← OrtSessionPool 借出
  ↓ top-20 tag JSON
[AiTaggerPool] mobileclip.classifySubDomain(bitmap)  ← 复用 bitmap
[AiTaggerPool] places.classify(bitmap) / mlKit / vision  ← 复用 bitmap
  ↓
[DB] flagDao.updateAiFields(... ai_version=32)
```

---

## 5. 文件改动

| 文件 | 改动 | LOC 预估 |
|---|---|---|
| `data/ai/OrtSessionPool.kt` (新建) | 单例池，懒加载 N 个 OrtSession，提供 borrow/release 接口 | ~120 |
| `data/ai/DanbooruTagger.kt` | 移除 `lazy session`，改为接受 pool 注入；session 通过 pool.borrow() 获取 | ~30 |
| `data/ai/AiModelHub.kt` | danbooruFile() 优先返回 INT8 模型路径；提供 OrtSessionPool 工厂 | ~15 |
| `data/ai/AiTagger.kt` | tagInternal() 接受外部传入 224×224 bitmap，不再内部 downsample | ~20 |
| `data/ai/AiTagger.kt` | `AI_VERSION = 31` → `AI_VERSION = 32`（触发新一轮重打标） | ~3 |
| `scanner/AiTaggingWorker.kt` | `inferenceSemaphore(1)` → `inflightSemaphore(N)` (N 由设备决定) | ~10 |
| `assets/wd-v1-4-tagger-v3-int8.onnx` (新文件) | Python 端 `quantize_dynamic` 输出 | (~175MB) |
| `app/build.gradle.kts` | 增加 `model_v3_int8` asset 引用 | ~5 |

**总 LOC 改动**：~200（不含量化脚本和模型文件本身）

---

## 6. 验证策略

### 6.1 离线精度验证（量化前 vs 量化后）

```python
# scripts/verify_int8_accuracy.py
import onnxruntime as ort
import numpy as np
from PIL import Image

sess_fp32 = ort.InferenceSession("wd-v1-4-tagger-v3.onnx")
sess_int8 = ort.InferenceSession("wd-v1-4-tagger-v3-int8.onnx")

for img_path in test_image_paths[:100]:
    x = preprocess(img_path)  # 448×448 RGB float32 [0,255]
    out_fp32 = sigmoid(sess_fp32.run(None, {"input": x})[0])
    out_int8 = sigmoid(sess_int8.run(None, {"input": x})[0])
    
    # Top-20 tag Jaccard 相似度
    tags_fp32 = set(topk(out_fp32, 20))
    tags_int8 = set(topk(out_int8, 20))
    jaccard = len(tags_fp32 & tags_int8) / len(tags_fp32 | tags_int8)
    assert jaccard >= 0.95, f"Jaccard {jaccard} too low for {img_path}"

    # 动漫判别 tag 召回率
    discriminator_tags = {"comic","manga","sketch","lineart","chibi","furry"}
    fp32_hits = discriminator_tags & tags_fp32
    int8_hits = discriminator_tags & tags_int8
    recall_diff = abs(len(int8_hits) - len(fp32_hits)) / max(len(fp32_hits), 1)
    assert recall_diff < 0.05, f"recall diff {recall_diff} too high"
```

### 6.2 真机 benchmark

```bash
# 100 张混合照片（动漫 / 真人 / 风景）
adb shell am start -n com.smartvision.gallery.debug/...MainActivity
# 让 worker 跑 100 张
# 通过 AiPreferences 进度 + logcat 时间戳计算平均耗时
```

### 6.3 内存验证

```bash
# peak RSS 监控
adb shell dumpsys meminfo com.smartvision.gallery.debug | grep -E "(Native Heap|TOTAL)"
```

---

## 7. 回退策略

| 失败场景 | 回退动作 |
|---|---|
| INT8 量化 Jaccard <0.95 | 保留 FP32 + 改 2-way 并发（净 ~350ms，仍优于 750ms） |
| 内存峰值 >1.2GB | 降低 N=2；若仍 >1.2GB，回退 N=1 |
| 真机 benchmark >250ms | 检查 OrtSession 是否预热；检查 thread 亲和性 |
| WD 推理并发导致 SIGSEGV | 每个 OrtSession 固定一个 worker thread（`setIntraOpNumThreads(1)` 已有） |

---

## 8. 实施步骤

1. **离线量化**（Python 端，不影响 app）
   - `pip install onnxruntime`（CPU 版即可）
   - `python -m onnxruntime.quantization.quantize_dynamic --input wd-v1-4-tagger-v3.onnx --output wd-v1-4-tagger-v3-int8.onnx`
   - 跑 `scripts/verify_int8_accuracy.py` 验证精度

2. **建 OrtSessionPool.kt** + 修改 DanbooruTagger 接受 pool 注入

3. **修改 AiModelHub.kt** 优先返回 INT8 模型路径

4. **修改 AiTagger.kt** AI_VERSION 31 → 32 + bitmap 复用

5. **修改 AiTaggingWorker.kt** semaphore 1 → N（动态 N）

6. **adb install -r -d** 部署

7. **真机 benchmark** 100 张照片，验证 ~150ms

8. **bump AI_VERSION=32 触发全量重打标**，观察 batch 进度

---

## 9. 不在本设计范围内

- AnimeBuckets 守门逻辑（已 v30.2 部署，不动）
- AiTagger cascade 流程（不动）
- AiTaggingWorker 调度策略（不动）
- Places365 / MobileNet 模型本身的精度（不动）

---

## 10. 风险与依赖

| 风险 | 影响 | 缓解 |
|---|---|---|
| INT8 量化在 ONNX Runtime Android 上不支持某些 op | 高 | 先用 CPU 验证；切换到 `quantize_static` 用校准数据 |
| 4-way 并发 + 4 个 OrtSession 导致 Dalvik OOM | 中 | 启动时按 memory class 降级 N |
| 重打标需数十分钟阻塞用户测试 | 低 | UI 进度条已有；bump 32 后让 worker 后台跑 |
| 量化脚本在用户机器没装 onnxruntime | 低 | 提供 Dockerfile / requirements.txt |