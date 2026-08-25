# JXL 渐进全分辨率兼容 Spec (rev.4)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task.

**Goal:** JXL 大图 viewer 打开不 OOM、放大时**尽可能高**的原生像素，峰值内存 < 256MB。

**Architecture:** native 端 2^n stride 降采样（复用已验证 `thumbDownsampleCallback` 机制，改直写 bitmap 消除中间缓冲，**callback 0-JNI 纯 C++**）。viewer **新增** zoom 感知渐进加载（**档位用 target long edge 表达，跨档 reload 前先查实际输出是否变化，无变化则短路**）。文件读取 mmap 主路径 + readBytes fallback（**上限 200MBbitmap + heap 余量**）。最高档输出 = **不超过 4096 的最大 2^n 缩放**。

**Tech Stack:** Kotlin (Compose + Telephoto zoomable), libjxl C++ (JNI `JxlDecoder` + `AndroidBitmap_lockPixels`), Android Bitmap (ARGB_8888), mmap.

---

## 约束（rev.4 修正）

- JXL only；AVIF 仍走 `ImageDecoder.setTargetSampleSize` 旧路径
- 256MB 峰值硬限
- **Canvas 硬限 ~100MB**：bitmap byteCount ≤ 91MB 安全（Compromise readBuffer 也安全）
- 切换无缝（fade 200ms），不闪切

## 决策（rev.4 修正）

| 决策 | 值 | 状态 |
|---|---|---|
| 分档 | 动态自适应（zoom 触发，双向响应） | ✅ |
| AVIF | 不纳入 | ✅ |
| OOM 兜底 | 256MB 阈值 | ✅ |
| 最高档 | **方案 A**：2^n stride 取整，8K→4096 (48MB)，16K→4096 (48MB) | **rev.4：采纳方案 A，放弃分数映射** |
| 文件读取 | mmap 主路径 + readBytes fallback | **rev.4：补 fallback** |
| callback 锁 | decode 循环前 lock 一次，结束 unlock 一次 + JNIEnv attach | **rev.4 修正** |
| 接口 | 统一 `decodeJxlScaled(uri, targetLongEdgePx)`，一律 > 0，默认 5500 | **rev.4 修正** |
| 降级 | 缩小 zoom 降档 + 释放 | ✅ |

## 为什么方案 A（rev.4 论证）

- 2^n stride 是已验证机制（thumb 路径生产稳定，无新算法风险）
- 方案 B 的分数映射累积器 = 新算法 + 多线程竞态 + 行缓存复杂度，收益仅是 8K 多 ~1400px 原生像素（4x zoom 才感知），性价比极差
- 8K JXL 真实场景占比极低，4096px 已覆盖 99% 显示需求

## stride 映射 + 最高档取整（rev.4 核心）

**最高档输出规则**（解决 rev.3 的 5500 与 2^n 矛盾）：

```
MAX_SAFE_LONG_EDGE = 5500          // Canvas 硬限 5500px 以下绝对安全
targets = [1/8, 1/2, 1/1]
for each 档:
    raw = sourceLongEdge / 档
    candidate = min(raw, MAX_SAFE_LONG_EDGE)   // Canvas 保护
    actual = 2^floor(log2(candidate))           // 取 ≤ candidate 的最大 2^n
    // 即 stride = 2^ceil(log2(sourceLongEdge / MAX_SAFE_LONG_EDGE))
```

实际分档表（8K 源 8192px 长边）：

| Zoom | 档 | stride | 实际输出 | ByteCount | 可绘制 |
|---|---|---|---|---|---|
| 0.5–1.5x | 1/8 | 8 | 1024×768 | 3.1MB | ✅ |
| 1.5–3.5x | 1/2 | 2 | 4096×3072 | 48MB | ✅ |
| 3.5x+ | 1/1 (cap) | **4** | 2048×1536 → 提升为 4096×3072 | 48MB | ✅ |

**关键修正**：8K 的 1/1 档实际 stride=4 → 输出 2048×1536（10MB）？**否** — `actual = 2^floor(log2(min(8192, 5500))) = 2^floor(log2(5500)) = 2^12 = 4096`，即 stride=2 → 输出 4096×3072 (48MB)。5500 只用来**算 stride 上限**，不产生小数尺寸。

**统一规则**：

```
stride(目标长边 T) = 2^ceil(log2(sourceLongEdge / T))
                     但 stride ≥ 1，且 stride 结果向下 clamp 到已有档位集合
                     1/1 档: stride = 2^max(0, ceil(log2(sourceLongEdge / 4096)))
最终输出长边 = sourceLongEdge / stride
```

即：任何源，最高档输出 = **不超过 4096 的最大 2^n 缩放**。8K → 4096；16K → 4096；4K → 4096（stride=1 全分辨率）；2K → 2048（stride=1 全分辨率，源≤4096 时输出=源）。

**这是"尽可能高"**：源 ≤ 4096 全分辨率；源 > 4096 时降采样到 4096（48MB，Canvas 安全），不产生非法小数尺寸。

## 分档表（统一，rev.4）

| 源长边 | 档 | stride | 输出 | ByteCount | 峰值（callback 直写 + 旧档 recycle） |
|---|---|---|---|---|---|
| 4096 | 1/8 | 8 | 512×384 | 786KB | ≤1MB |
| 4096 | 1/2 | 2 | 2048×1536 | 12MB | 12MB + 旧 1MB = 13MB |
| 4096 | 1/1 | 1 | **4096×3072** | 48MB | 48MB + 旧 12MB = 60MB |
| 8192 | 1/8 | 8 | 1024×768 | 3.1MB | 3.1MB |
| 8192 | 1/2 | 2 | 4096×3072 | 48MB | 48MB + 3.1MB = 51MB |
| 8192 | 1/1 | 2 | **4096×3072** | 48MB | 48MB + 48MB = 96MB |
| 16384 | 1/8 | 8 | 2048×1536 | 12MB | 12MB |
| 16384 | 1/2 | 2 | 8192×6144 | 192MB | **超 Canvas ❌** → 改 stride=4 → 4096×3072 (48MB) |
| 16384 | 1/1 | 4 | **4096×3072** | 48MB | 48MB + 12MB = 60MB |

**16K 修正**：16K 的 1/2 档若 stride=2 → 8192×6144 (192MB) 超 Canvas。加入 **档位链二次检查**：任何档输出 byteCount > 91MB → 自动升 stride 直到 ≤91MB。

## Native 机制（rev.4 修正）

### 新 callback：`bitmapWriteCallback`

```cpp
struct BitmapWriteState {
    AndroidBitmapInfo info;
    jobject bitmap;       // 预分配 ARGB_8888, lock 一次
    void* rawPtr;         // lockPixels 返回的 raw void*，callback 直接写
    uint32_t dstW, dstH;
    uint32_t stride;      // 1/2/4/8
};

void bitmapWriteCallback(void* opaque, size_t x, size_t y,
                         size_t num_pixels, const void* pixels) {
    // 运行于 libjxl runner 工作线程
    // **0-JNI**：只写 st->rawPtr 像素区间，不接触 JNIEnv
    // 按 stride 采样写对应 scanline
}
```

- **lock 一次**（decode 控制线程在循环前 `AndroidBitmap_lockPixels` 获取 `rawPtr`），**unlock 一次**（循环结束）— callback 只写 `rawPtr` 不同行，**0-JNI 纯 C++**
- **JNIEnv 完全不进入 callback**：避免 AttachCurrentThread 系统调用 + Detach 配对泄漏问题
- 并发安全：libjxl runner 工作线程调用 callback 写**不同扫描线**（libjxl 内部按行分派），无写冲突
- 无 `thumb.pixels` 中间缓冲（rev.3 核心修正保留）

### 文件读取：mmap + readBytes fallback

```cpp
// 主路径：mmap
jobject decodeJxlScaledFd(jint fd, jlong len, jint targetLongEdgePx) {
    void* data = mmap(nullptr, len, PROT_READ, MAP_SHARED, fd, 0);
    ... decodeJxlReal(data, len, ...) ...
    munmap(data, len);
}
```

- **fallback**：mmap 失败（非 file-backed provider / pipe）→ 回退旧 `readBytes` 路径（**上限 200MB**，保留 56MB 余量给 bitmap + Java heap overhead；256MB ByteArray + 48MB bitmap = 304MB 会超 budget）

## 接口（rev.4 统一）

### Kotlin（`NativeBridge`）

```kotlin
// 统一接口。targetLongEdgePx 一律 > 0。
// 默认 4096 = Canvas 安全目标。传 5500 与传 4096 在 native 实际效果相同
// (5500 仍 clamp 到 2^n 的 4096) — 5500 仅作为上限阈值意义，不再是有效输出尺寸。
suspend fun decodeJxlScaled(uri: Uri, targetLongEdgePx: Int = 4096): NativeDecodeResult?
```

内部：`openFileDescriptor(uri, "r")?.use { nativeDecodeJxlScaledFd(it.fd, len, targetLongEdgePx) }`，mmap 失败 → `readBytes` fallback。

**删除** `decodeJxlFull`、`decodeJxlThumbnail`（统一入口）。

### Kotlin（`JxlNativeDecoder`）

`decodeThumbnail(uri, w, h)` → `decodeJxlScaled(uri, targetLongEdgePx=max(w,h))`；`decodeFull(uri, maxW, maxH)` → `decodeJxlScaled(uri, targetLongEdgePx = maxW ?: 5500)`。

### Kotlin（`MediaLoader`）

- **删除** `capBitmapPayload` + `MAX_BITMAP_LONG_EDGE_PX=4096`
- 删除 `decodeAtScale`（与 decodeFull 合并）
- `loadFullUri` / `loadFull` 转调 `decodeJxlScaled`

### Kotlin（viewer `isNextGen` 分支）— **新增**

- 状态用 **target long edge**（非抽象档位语义）：`currentTargetPx`, 初始 1024 (1/8 of 8K, 实际按源归一)
- `LaunchedEffect(uri)`：加载首屏（源 longEdge / 8, floor 到 256 起步）
- 监听 `contentTransformation.scale` → `derivedStateOf` 算 newTargetPx → `LaunchedEffect` 异步加载
- **跨档短路（fast path）**：算 `newStride = ceil(log2(sourceLongEdge / newTargetPx))` vs `currentStride`，相等则不 reload（避免 8K 1/2→1/1 因 stride 同为 2 而重复解码白做）
- 双向：放大跨档 → 高档；缩小跨档 → 低档 + recycle 高档
- 防抖 `isDecoding`：加载中只记最终 target，完成后再处理
- `maxDecodePx` 4096 移除

## 验收标准（rev.4）

- [ ] 4K JXL (4096×3072)：1/1 档全分辨率 48MB 显示
- [ ] 8K JXL (8192×6144)：1/1 档 4096×3072 (48MB) 显示，峰值 96MB
- [ ] 16K JXL (16384×12288)：1/1 档 4096×3072 (48MB)，峰值 60MB，无 Canvas 崩
- [ ] 无 `Canvas: trying to draw too large`
- [ ] 快速缩放不闪切、不卡死（防抖 + 双向）
- [ ] >64MB JXL 文件 mmap 解码成功；mmap 失败时 readBytes fallback 不崩
- [ ] 缩小 zoom 释放高档内存

## 已知限制（诚实标注）

- 源 > 4096px 长边的 JXL 无法显示原生全分辨率（Canvas 硬限），最高档降采样到 4096 (48MB)。要突破需自定义 SoftwareCanvas 绘制，不在本期范围。
- callback 仍 nearest-neighbour（box-average 为后续优化项）
- 16K 源 512MB 原生像素在 mmap 下也只是 address space（非 heap）占用，无 OOM 风险