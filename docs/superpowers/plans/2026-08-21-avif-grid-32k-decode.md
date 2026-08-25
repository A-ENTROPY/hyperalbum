# AVIF 16K-32K Grid 解码 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 16K-32K AVIF grid 以有界内存解码：ISOBMFF 解析 grid → 逐 cell 包最小 AVIF 容器 → 系统 BitmapFactory 解码 → 批量写 SVRAW。HDR 10-bit 素材两档 bpc（colr 检测）。

**Architecture:** 新建 `AvifGridDecoder`（纯 Kotlin ISOBMFF 解析，无 Android 依赖）+ `AvifCellDecoder`（cell AV1→最小 AVIF wrapper）。改 `AvifRawPrecacher.decodeToRaw`：bounds probe 后 parse grid，非 grid/小 grid 走原整幅路径，大 grid 逐 cell 串行 decode + 批量写 cell region。改 `RawImageRegionDecoder` 支持 bpc=2（10-bit HDR 降级渲染）。

**Tech Stack:** Kotlin (JVM 17), Android BitmapFactory, RandomAccessFile, JUnit4+Truth+Robolectric, 真机 instrumented test.

**Spec:** `docs/superpowers/specs/2026-08-21-avif-grid-32k-decode-design.md`

---

## File Structure

| File | Status | Responsibility |
|------|--------|----------------|
| `app/src/main/java/com/smartvision/gallery/ui/viewer/AvifGridDecoder.kt` | new | ISOBMFF 解析：FullBox/iloc v0-2/iref v0-1/iinf/ipco/ipma(1-based)/pixi/colr → `GridInfo(rows,cols,cellW,cellH,outW,outH,bpc,cells)` |
| `app/src/main/java/com/smartvision/gallery/ui/viewer/AvifCellDecoder.kt` | new | cell AV1 bytes + av1C/ispe box bytes → minimal single-item AVIF wrapper ByteArray |
| `app/src/main/java/com/smartvision/gallery/ui/viewer/AvifRawPrecacher.kt` | modify | `decodeToRaw` grid 分支 + CellWriter 批量写 + HDR bpc 检测 |
| `app/src/main/java/com/smartvision/gallery/ui/viewer/RawImageRegionDecoder.kt` | modify | bpc==2 分支（2B/通道 LE → 8-bit ARGB_8888）+ HDR hasUltraHdrContent |
| `app/src/test/java/com/smartvision/gallery/ui/viewer/AvifGridDecoderTest.kt` | new | 合成 ISOBMFF bytes 断言 GridInfo |
| `app/src/test/java/com/smartvision/gallery/ui/viewer/AvifCellDecoderTest.kt` | new | wrapper 结构字节断言 |
| `app/src/test/java/com/smartvision/gallery/ui/viewer/AvifRawPrecacherGridTest.kt` | new | CellWriter bpc=1/2 + 边缘 padding（Robolectric BitmapFactory） |
| `app/src/test/java/com/smartvision/gallery/ui/viewer/RawImageRegionDecoderBpc2Test.kt` | new | 合成 10-bit SVRAW decodeRegion 降级 |

**Not to touch:** `jxl_to_raw.cpp` native、`PhotoViewerActivity`、LiquidGlass、`ui/liquidglass/*`。

**Verification gates:** 每 task 后 `./gradlew :app:testDebugUnitTest --tests "...Test"` 过；全量后 `./gradlew :app:compileDebugKotlin` + `assembleDebugAndroidTest` + 真机 instrumented（CellWrapperDecodeTest 已存在）。

---

## Task 1: AvifGridDecoder — ISOBMFF 解析

**Files:**
- Create: `app/src/main/java/com/smartvision/gallery/ui/viewer/AvifGridDecoder.kt`
- Test: `app/src/test/java/com/smartvision/gallery/ui/viewer/AvifGridDecoderTest.kt`

- [ ] **Step 1: 写失败测试** — 合成真实 spike 素材同构 bytes（3×4 grid, out 8160×6050, 12 cells, ispe 2048×2048, pixi 3×10bit, colr nclx PQ, iloc v1 4/4/0/0, ipma 1-based），断言 GridInfo.rows=3 cols=4 cellW=2048 cellH=2048 outW=8160 outH=6050 bpc=2 cells.size=12 cells[0].fileOffset==mdat+0
- [ ] **Step 2: 运行确认失败**（`./gradlew :app:testDebugUnitTest` RED）
- [ ] **Step 3: 实现 AvifGridDecoder** — box 遍历 + GridInfo 数据类；iloc v0/v1/v2 分支、iref v0/v1、ipma 1-based、pixi/colr bpc 判定
- [ ] **Step 4: 运行测试 GREEN**
- [ ] **Step 5: commit**

## Task 2: AvifCellDecoder — 最小 AVIF wrapper

**Files:**
- Create: `app/src/main/java/com/smartvision/gallery/ui/viewer/AvifCellDecoder.kt`
- Test: `app/src/test/java/com/smartvision/gallery/ui/viewer/AvifCellDecoderTest.kt`

- [ ] **Step 1: 写失败测试** — 输入 `av1Bytes`(任意) + `av1CBytes` + `ispeBytes`，断言输出头部 ftyp/meta/iloc extent_offset==实际 mdat 数据偏移、mdat 尾接 av1Bytes、ipma av1C essential
- [ ] **Step 2: RED**
- [ ] **Step 3: 实现** — 布局见 spec §2.1（ftyp avif/mif1/miaf/MA1B + meta{hdlr,pitm,iinf,iprp{ipco,ipma},iloc v1} + mdat），注意 `mdat_off = len(ftyp)+len(meta)+8`（勿多加）
- [ ] **Step 4: GREEN**
- [ ] **Step 5: commit**

## Task 3: RawImageRegionDecoder bpc=2 支持

**Files:**
- Modify: `app/src/main/java/com/smartvision/gallery/ui/viewer/RawImageRegionDecoder.kt`
- Test: `app/src/test/java/com/smartvision/gallery/ui/viewer/RawImageRegionDecoderBpc2Test.kt`

- [ ] **Step 1: 写失败测试** — 手工写 20B 头(bpc=2) + 2×2 像素 16-bit 值（0x03ff→255），decodeRegion 断言 ARGB 降级正确
- [ ] **Step 2: RED**
- [ ] **Step 3: 实现** — readHeader 放宽 `bpc in 1..2`；bpc==2 分支 2B LE→>>2→ARGB_8888；返回 hasUltraHdrContent 由构造参数/头部标记决定
- [ ] **Step 4: GREEN**
- [ ] **Step 5: commit**

## Task 4: AvifRawPrecacher grid 分支 + CellWriter

**Files:**
- Modify: `app/src/main/java/com/smartvision/gallery/ui/viewer/AvifRawPrecacher.kt`
- Test: `app/src/test/java/com/smartvision/gallery/ui/viewer/AvifRawPrecacherGridTest.kt`

- [ ] **Step 1: 写失败测试** — CellWriter（或独立方法）bpc=1 全格 + 右/底边缘 padding 裁切，读回 SVRAW 断言
- [ ] **Step 2: RED**
- [ ] **Step 3: 实现** — decodeToRaw 入口分流（grid parse → 大 grid 逐 cell）；CellWriter 批量写（seek 一次写整 cell 区域）；MAX_CELL_PX=35_651_584L
- [ ] **Step 4: GREEN**
- [ ] **Step 5: commit**

## Task 5: 全量验证 + 真机回归

- [ ] **Step 1:** `./gradlew :app:testDebugUnitTest` 全过
- [ ] **Step 2:** `./gradlew :app:assembleDebugAndroidTest` + 真机 instrumented（CellWrapperDecodeTest + 新 grid 端到端）
- [ ] **Step 3:** 现有 8160×6050 grid AVIF 改走逐 cell 路径真机显示正常
- [ ] **Step 4:** commit
