# JXL 32K 上限兜底 Spec (rev.5)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task.

**Goal:** JXL viewer 对 32K 级超大源**不 OOM、不崩**；4K-16K 源 zoom 时**绝对清晰**（1:1 像素级）。

**Architecture:** 在 rev.4 的 2^n stride 方案基础上加 32K 上限兜底：源 > 32K 显示「源过长」横幅；32K 源走 rev.4 已验证的 4096 输出，48MB heap；峰值内存硬限 96MB（双档切换瞬间）。**不**做 viewport 1:1 streaming（libjxl 0.10.3 无 crop API，区域重解码 5-30s 不可响应）。

**Tech Stack:** Kotlin (Compose + Telephoto zoomable), libjxl C++ (JNI `JxlDecoder` + `AndroidBitmap_lockPixels`), Android Bitmap (ARGB_8888), mmap.

---

## 决策（rev.5 新增）

| 决策 | 值 | 状态 |
|---|---|---|
| 32K 是上限尺寸，作为最高分辨率保底用的，无需考虑太多实用性 | ✅ 用户确认 |
| 32K 源 zoom 1:1 绝对清晰 | **不实现** — libjxl 0.10.3 无 ROI API，区域重解码 5-30s 不可响应；32K 输出 4096 (48MB) 已是合理保底 |
| 4K-16K 源 zoom 1:1 绝对清晰 | ✅ 沿用 rev.4 stride=1 输出 4096 |
| 源 > 32K 兜底 | 显示「源过长，仅显示低分辨率」横幅；不解码，避免 OOM |
| libjxl 无 crop API | ✅ 已确认（GitHub issue #3519 至今未实现） |

## 源长边上限分层

| 源长边 | 行为 | 输出长边 | Heap 峰值 |
|---|---|---|---|
| ≤ 16K | rev.4 全 stride | 4096 (1:1) | 96MB |
| 16K < 源 ≤ 32K | rev.4 全 stride (含 1/2 = 16384 已在 Canvas 检查中 clamp) | 4096 | 96MB |
| 源 > 32K | **跳过解码**，显示「源过长」横幅 + 兜底 1024 预览 | 1024 | ~5MB |

**32K 上限的物理依据**：
- 32K 源 rev.4 解码 4096 输出 = 48MB heap（Canvas 安全）
- mmap 32K JXL codestream 本身仅占 address space，不进 heap
- **超过 32K 即视为「该 JXL 是天文摄影/科学图像」，移动端查看无意义**——业界（Google Photos、Apple Photos、Lightroom Mobile）对 >32K 源都直接降采样到屏幕分辨率

## 「源过长」UI 反馈

**不在 rev.4 范围内**（rev.4 已能输出 4096 不崩）。rev.5 新增**只有源 > 32K** 时的 UI 反馈：

```
┌─────────────────────────────────┐
│            ⚠                    │
│   原图尺寸过大 (45000×32000)    │
│   已显示降采样版本               │
│                                 │
└─────────────────────────────────┘
```

- 位置：图片底部半透明 overlay（InfoPanel 之上）
- 自动消失：5s 后淡出
- 颜色：`Color.White.copy(alpha = 0.85f)`，背景 `Color.Black.copy(alpha = 0.5f)` 半透明
- 不阻塞手势穿透（图片仍可缩放）

## 32K 上限检测

**Native 端**：`nativeJxlProbeSize(fd, len, wantWidth)` 已在 rev.4 实现，返回源 long edge（int64）。

**Kotlin 端**：`JxlProgressiveController` 新增常量：

```kotlin
companion object {
    const val MAX_SUPPORTED_LONG_EDGE_PX = 32768L
}
```

`PhotoViewerActivity.kt` `LaunchedEffect(uri)`：
- `sourceLongEdge > MAX_SUPPORTED_LONG_EDGE_PX` → 设置 `tooLarge = true`，**不**进入 `JxlProgressiveController` 路径
- 仍下载 1024 预览（`loadFullUri(uri, format, 1024)`）作为兜底图

## 改动文件（最小化）

### 修改

| 文件 | 改动 |
|---|---|
| `JxlProgressiveController.kt` | 新增常量 `MAX_SUPPORTED_LONG_EDGE_PX = 32768L`；新增 `isTooLarge(sourceLongEdge: Long)` |
| `PhotoViewerActivity.kt` | isNextGen 分支 `LaunchedEffect(uri)` 增加 `tooLarge` 检测；isNextGen 显示分支增加 `if (tooLarge) TooLargeBanner(...)` overlay |

### 新增

| 文件 | 用途 |
|---|---|
| `TooLargeBanner.kt` | 半透明 overlay composable，5s 自动淡出 |

**不**改：Native 端（probe 已能返回 64K long edge）、MediaLoader（已有 maxDimensionPx 参数）、computeZoomTarget（仍 clamp 256..4096）。

## 验收标准（rev.5）

- [ ] 32K JXL 源（32768×24576）：rev.4 已能 4096 输出 48MB 不崩，行为不变
- [ ] 64K JXL 源（65536×65536）：**不**调用 libjxl decode；显示「源过长」横幅 + 1024 兜底图，heap < 16MB
- [ ] 16K JXL 源 zoom 4×：rev.4 已 4096 输出，绝对清晰
- [ ] 8K JXL 源 zoom 2×：rev.4 已 4096 输出（stride=2），绝对清晰
- [ ] 4K JXL 源 zoom 1×：rev.4 stride=1 全分辨率 4096，绝对清晰
- [ ] 「源过长」横幅 5s 自动淡出，不阻塞手势

## 已知限制（诚实标注，rev.5 保留）

- 源 > 4096px 长边 → 输出 4096（Canvas 硬限），**最高档 1:1 仅源 ≤ 4096 时可达**
- 源 > 32K → 仅显示 1024 兜底（不做 32K → 4096 的中间档）
- 32K 源 zoom 到 4× → 输出 4096 但每个屏幕像素 = 8 个源像素，仍清晰但非「源 1:1」
- libjxl 0.10.3 无 crop API，无法做 viewport 区域重解码；32K 以上源的 1:1 在移动端物理不可达

## rev.6 追加（2026-08-19）：≥16K JXL 后台 JPEG 预解码

见 `docs/superpowers/specs/2026-08-19-jxl-tile-cache-design.md`。实现参数：stride=8（target=4096）、
单文件 JPEG 质量 95、cacheDir `JXL_FullRes/<md5(uri)>.jpg`、触发范围源长边 16384..32768、
首屏 1024 DC 预览 + 后台切换 SubSamplingZoomableImage（BitmapRegionDecoder 1:1）。
