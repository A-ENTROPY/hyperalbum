# AVIF 超大图 (16K–32K) grid 解码设计

> 日期：2026-08-21
> 状态：待实现
> 目标格式：仅 AVIF。JXL 已有 32K 支持，不动。

## 1. 背景与目标

用户素材为超分 app 生成的真实 16K–32K AVIF。当前 `AvifRawPrecacher.MAX_PX=64M`（≈8K×8K）之上整幅系统解码直接返回 null → viewer 显示"无法显示该图片"。目标是让 16K–32K AVIF 以有界内存解码并走现有 SVRAW 瓦片管线。

真大图 AVIF **必为 grid 容器**：AV1 coded image 单帧上限（Baseline level 5.1：像素 ≤8,912,896 且宽 ≤8192 且高 ≤4352；Advanced level 6.0：像素 ≤35,651,584 且宽 ≤16384 且高 ≤8704，三条件同时满足、像素 cap 先封顶），超此必拆 cell 拼 grid（HEIF 画布上限 65536×65536）。grid 每个 cell 是**独立、可单独解码的完整 AV1 keyframe OBU bitstream**。

## 2. 决定性事实：逐 cell 解码必须套最小 AVIF 容器

Android (API 36) 系统解码栈（Skia `SkCrabbyAvifCodec`/`SkAvifCodec`）的格式探测**硬检 `ftyp` box**（offset 4 == "ftyp" 且 offset 8 == "avif"/"avis"）。裸 AV1 OBU 流（temporal_delimiter/sequence_header OBU 起始）无容器 → 所有 codec 探测失败 → `BitmapFactory.decodeStream` **静默返回 null**（不抛异常）。

**结论**：不能把 cell 字节直接喂 `BitmapFactory`。每个 cell 须包成**单 item 最小 AVIF 容器**再喂：
- 从原始容器 `ipco` 里**原样拷贝**该 cell 已有的 `av1C` + `ispe` box 字节（含 seq_profile/still_picture，天然满足 MIAF essential 要求）
- 自建 `ftyp`(avif,mif1,miaf,MA1B) + `meta`(hdlr=pict + pitm + iloc + iinf/infe=av01 + iprp/ipco[拷贝来的 av1C,ispe] + ipma) + `mdat`(该 cell 的 AV1 字节)
- 头开销约 150–300B/cell
- `BitmapFactory.decodeStream(ByteArrayInputStream(wrapBytes))` → Bitmap
- 每 cell 串行解码 → 写 SVRAW → `bitmap.recycle()`，峰值内存 = 单 cell bitmap + 一行缓冲

不做 MediaCodec 裸 AV1 路径：需自配 codec/Image 转换/处理 CODEC_AV1 缺失，远比套容器重。

### 2.1 Spike 验证结果（2026-08-21，本轮实行）

真实素材 `Image_1775910716682.avif`（3×4 grid，8160×6050，12 cells，cell=item 6-17）已拆 cell0 验证：

**wrapper 可被系统 BitmapFactory 解码（核心假设成立）**
- 设备 instrumented test（API 36）：`BitmapFactory.decodeFile(cell0_wrap3.avif)` → `2048x2048 config=RGBA_1010102`
- desk 端 ffmpeg/dav1d 交叉印证：`yuv444p10le (pc, smpte170m/bt709/smpte2084) 2048x2048`，RGBA 输出 16MB 完整
- **HDR 识别**：colr=nclx+smpte2084 PQ → 素材是 **10-bit PQ HDR**。系统解码保留 10-bit（RGBA_1010102，非 8-bit 降级）

**grid 整幅解码失败确认**（佐证逐 cell 必要性）
- 同一设备 decodeFile(8160×6050 grid) → **返回 null**（bounds 探测无输出）。>MAX_PX 整幅必须逐 cell。

**cell wrapper 构造要点（已验证可解的最小结构）**
- ftyp major=avif minor=0 compat=avif,mif1,miaf,MA1B
- meta: hdlr(pict) + pitm(item 1) + iinf(infe ver2: item_ID u16 + protection u16 + 'av01' + name\0) + iloc(v1, sizes 4/4/0/0, extent=mdat offset+len) + iprp(ipco[av1C,ispe] + ipma)
- mdat 直接接 cell AV1 OBU 字节；iloc 的 extent_offset = **mdat 数据起始**（= 文件偏移，`ftyp+meta+8`，勿多加）
- ipma v0: item→[(essential=1) av1C idx1, ispe idx2]（av1C essential 必须，否则 decoders 拒 decode）
- wrapper 头部全量 244B（含 iloc 30B）；`BitmapFactory.decodeFile` 直接可用

**iloc v0 分支确认**：现有素材是 iloc v1；avifenc/其他 encoder 可产 v0。v0 无 construction_method 字段（布局不同），解析器必须按 version 分支（见 §3.3）。

## 3. ISOBMFF 解析细节（写入 Kotlin 解析器，字节级精确）

全部 BIG-ENDIAN。box 头 = 4B size + 4B type；size==1 → 后跟 8B largesize；size==0 → 至 EOF。

### 3.1 顶层结构
`ftyp → meta → mdat`（avifenc 典型顺序；meta 也可在 mdat 后）。`meta` 是 FullBox：解析子 box 前先跳过其 1B version + 3B flags。`mdat` 内连续堆放各 cell 的 AV1 OBU 字节。

### 3.2 ImageGrid payload（grid item 的 item data，非 box，无 size/type 头）
```
offset 0: uint8 version (=0, 必须为 0)
offset 1: uint8 flags   (仅 bit0 有定义; FieldLength = ((flags & 1) + 1) * 16 → 16 或 32 bit)
offset 2: uint8 rows_minus_one    (rows = 值+1, 范围 [1,256])
offset 3: uint8 columns_minus_one (cols = 值+1, 范围 [1,256])
offset 4: output_width  (flags bit0==0 → uint16 BE; ==1 → uint32 BE)
offset 4+FL/8: output_height (同上)
```
总长 8B（16-bit）或 12B（32-bit）。**16K(16384)/32K(32768) 均 ≤65535，用 16-bit**；仅 65536+ 才 32-bit。读端先读 flags 再定宽。

### 3.3 iloc（ItemLocationBox）
FullBox（头含 version+flags）。version ∈ {0,1,2}。
- packed size（flags 后 2 字节）：
  - byte A: `offset_size`(高4位) | `length_size`(低4位)，取值 {0,4,8,16} = 字段字节数
  - byte B: `base_offset_size`(高4位) | `index_size`(低4位，仅 version≥1 有效)
- item_count: v0/v1 → uint16；v2 → uint32
- per entry：
  - item_ID: v0/v1 → uint16；v2 → uint32
  - construction_method: **仅 version≥1**，uint16（低 4 位；0=文件绝对偏移、1=idat、2=item-relative）
  - data_reference_index: uint16（0=本文件）
  - base_offset: base_offset_size 字节（0 则无此字段，视为 0）
  - extent_count: uint16
  - per extent: [index_size>0 且 v≥1 → extent_index(index_size 字节)] + extent_offset(offset_size 字节) + extent_length(length_size 字节)

cell 数据绝对文件位置 = `base_offset + extent_offset`（construction_method=0 时**直接是文件绝对偏移**，勿再减 mdat 头）。多 extent 拼接即完整 item。**必须按 version 分支，勿假设 v0 布局**。grid item 自身也有 iloc extent（指向 8–12B ImageGrid payload，不是像素数据）。

### 3.4 iref / dimg（SingleItemTypeReferenceBox）
`iref` 是 FullBox（version ∈ {0,1}）。子 box：reference_type 4CC = `dimg` (0x64696D67)。
- v0：`from_item_ID` uint16、`reference_count` uint16、`to_item_ID[count]` 各 uint16
- v1：`from_item_ID` uint32、`reference_count` **仍 uint16**、`to_item_ID[count]` 各 uint32
- `to_item_ID[]` 顺序 = cell **行优先**（顶行左→右，再下一行），reference_count == rows×cols
- from = grid item ID，to = 各 cell (av01) item ID

### 3.5 iinf / infe
version 分支（0/1: uint16 item_ID；2/3: 位置不同），item_type 为 4CC（"grid"/"av01"）。用于确认 grid item 与各 cell 的 type，并取每个 av01 的 iloc。

### 3.6 iprp / ipco / ipma
- ipco 是属性 box 容器（av1C、ispe、colr、pixi 等原始 box 字节）
- ipma 关联 item↔property index（**1-based** index into ipco list）
- **ispe**：cell 解码尺寸（image_width=UpscaledWidth, image_height=FrameHeight），**各 cell 相同** → tile_width/tile_height。用 ispe 判 cell bitmap 尺寸，勿手搓 AV1 OBU 解析（变长 f(n) 编码易错）。
- **pixi**：pixel_information，payload = `version/flags(4B) + count(1B) + count×bits_per_channel(1B)`。count==3 → YUV，bits==8 → 8-bit，bits==10/12 → HDR。spike 素材 pixi=`03 0a 0a 0a` = 3 通道 × 10-bit → 10-bit HDR。
- **colr**：colour_information。method(4B)：`nclx` / `icc ` / `prof`。nclx payload 含 colour_primaries/transfer_characteristics(transfer) / matrix。transfer==16(PQ SMPTE 2084) 或 18(HLG) → HDR。spike 素材 colr=nclx，transfer=16(PQ)。
- cell wrapper 直接拷贝该 cell 的 av1C/ispe 原始 box 字节；**pixi/colr 用于决定 bpc 分档**（见 §4 HDR 策略），不必须拷入 wrapper（av1C 已含 seq_profile/bps 信息）。

### 3.7 cell 对称与 padding
- 所有 cell 等尺寸：tile_width×tile_height（= ispe）
- tile_width×cols ≥ output_width，tile_height×rows ≥ output_height（画布可大于输出）
- **output 尺寸读 grid payload 的 output_width/output_height（权威），不用 cellW×cols 推算**（边缘 cell 有 padding）
- 边缘 cell（右列/底行）AV1 帧内含 padding 像素；写 SVRAW 时只 copy 落在 output 范围内的像素（右列 cell 写 `outW - gc*cellW` 列，底行 cell 写 `outH - gr*cellH` 行）

### 3.8 output 字段边界
16-bit 字段最大 65535。HEIF 画布上限 65536×65536。`outW/outH == 65536` 时 16-bit 装不下 → avifenc 用 **32-bit 字段**（flags bit0==1）。解析器按 flags 定宽，不假设 16-bit。32K(32768) ≤ 65535 → 16-bit；仅 65536 边界素材用 32-bit。spike 素材 flags=0 → 16-bit，out=8160×6050。

## 4. 架构与数据流

```
AVIF uri
  → decodeToRaw(uri):
      cache hit → return rawFile
      bounds probe (inJustDecodeBounds)            // 系统读 ispe/grid output
      grid = AvifGridDecoder.parse(stream)          // 全量 ISOBMFF 遍历一次
      if grid == null:                              // 非 grid
          if outW*outH ≤ MAX_PX:  return decodeSystemWhole(uri)  // 原整幅路径
          else:                   return null                   // 非 grid 超限
      // grid 路径：
      if outW*outH ≤ MAX_PX:     return decodeSystemWhole(uri)   // 小 grid 一次解更快
      // 大 grid 逐 cell:
      bpc = detectBpc(grid.cellPixi, grid.cellColr)   // 8 或 2(10/12-bit)
      writeSvarwHeader(outW, outH, bpc)
      for (gr, gc) in row-major:
          av1 = seek+read(cell.fileOffset, cell.length)
          wrap = AvifCellDecoder.wrap(av1, cell.av1C, cell.ispe)
          bmp = BitmapFactory.decodeStream(wrap, inPreferredConfig = configFor(bpc))
          if bmp == null → delete cache → return null
          writeCellRegion(bmp, gr, gc, bpc, outW, cellW, cellH)
          bmp.recycle()
      return rawFile
  → RawImageRegionDecoder 瓦片读 SVRAW
```

### HDR 两档 bpc 策略（用户批准：检测 colr 决定）
- **8-bit 素材**（pixi bits==8 或无 pixi）：`inPreferredConfig=ARGB_8888`，bpc=1。写 ARGB_8888 像素（现有路径，消费端零改动）。
- **10/12-bit HDR 素材**（pixi bits≥10 且 colr transfer∈{16,18}）：`inPreferredConfig=RGBA_1010102`，bpc=2。每通道 2B LE（低 16 位存 10-bit 值，高位 0）。消费端 `RawImageRegionDecoder` 增 bpc==2 分支：读 2B/通道 → 右移 2 位降到 8-bit → ARGB_8888 渲染（屏幕终是 8-bit 输出，HDR 高光在 SVRAW 层保留 10-bit 供未来 HDR 屏，渲染时降级）。
- **colr 非 nclx / transfer 未识别**：保守降级 8-bit（不丢数据，bpc=1）。
- **非 PQ/HLG 10-bit**（如 10-bit SDR）：仍 bpc=2 保留，渲染降级。

### SVRAW 写入（CellWriter，与现有 writeSvarw 字节兼容 + bpc 扩展）
SVRAW = 20B 头（magic "SVRA" 4B + totalW int32 LE + totalH int32 LE + channels=4 int32 LE + bpc int32 LE）+ 平铺 RGBA 行优先，stride=totalW×4×bpc 无 padding。

**批量写策略（回应 review #1：勿逐行 seek million 次）**：逐 cell 对 cell (gr,gc) **一次 seek 写完整 cell 区域**——cell 全部像素行连续。bpc=1: 一个 `cellW×cellH×4` ByteArray；bpc=2: `cellW×cellH×8`。seek 到 `20 + (gr*cellH) * (totalW*4*bpc) + (gc*cellW*4*bpc)` 一次 write。32K grid（如 1024 cells）= 1024 次 seek，非 million。

边缘 cell：右列 cell 行内只写 `outW-gc*cellW` 像素宽（非 cellW 全宽），底行 cell 只写 `outH-gr*cellH` 行——避免 padding 像素写入 SVRAW 的 output 区域之外（稀疏 hole 读为 0=透明黑）。

### 现有消费端契约（需扩展 bpc=2，否则不破坏 bpc=1 路径）
`RawImageRegionDecoder` 现 bpc==1 硬校验（line 193）。扩展：bpc==1 走原 RGBA→ARGB_8888（零改动）；bpc==2 走新分支——2B/通道 LE 读 → `val >> (bits-8)` 降到 8-bit → ARGB_8888。`hasUltraHdrContent` 在 bpc==2 且 colr 是 PQ/HLG 时置 true（支持 HDR 屏幕增益）。

## 5. 组件

| 文件 | 职责 |
|---|---|
| 新建 `AvifGridDecoder.kt` | ISOBMFF 遍历 + grid 解析，输出 `GridInfo(rows,cols,cellW,cellH,outW,outH,bpc,cells:List<CellMeta(itemId,fileOffset,length,av1CBytes,ispeBytes)>)`；box 解析器（FullBox/iloc v0-2/iref v0-1/iinf/ipco/ipma 1-based） |
| 新建 `AvifCellDecoder.kt` | 把单 cell AV1 字节包成最小 AVIF 容器（拷贝 av1C/ispe 原始 box 字节 + 自建 ftyp/meta/iloc/ipma/mdat）；静态工具。wrapper 布局见 §2.1 |
| 改 `AvifRawPrecacher.kt` | `decodeToRaw`：cache→bounds probe→grid parse→分流（非 grid/小 grid 走原 `decodeSystemFull`；大 grid 逐 cell）。`MAX_PX` 保持整幅 cap 语义；新增 per-cell cap `MAX_CELL_PX=35_651_584L`（Advanced level 6.0）。HDR bpc 检测 + CellWriter 批量写 |
| 改 `RawImageRegionDecoder.kt` | 放宽 `bpc==1` 校验为 `bpc∈{1,2}`；bpc==2 分支：2B/通道 LE→8-bit ARGB_8888 渲染；HDR 时 `hasUltraHdrContent=true` |

## 6. 边界与错误处理

- **非 grid 单图 ≤MAX_PX** → 原整幅路径不变
- **非 grid 但 >MAX_PX** → null（无法显示）
- **grid 但 outW*outH ≤MAX_PX** → 原整幅路径（一次解码更快）
- grid 解析失败 / dimg reference_count≠rows×cols / iloc 越界 / cell 解码 null → 中断、删缓存、null
- **per-cell 像素 cap** `MAX_CELL_PX=35_651_584`：cell ispe 超 Advanced level 6.0 → 整体 null（HW decoder 会拒）。spike 素材 cell 2048×2048=4.19M px ≪ cap ✓。真 16K-32K 素材 cell 尺寸由超分 app 决定，真机验证时确认 cell ≤ cap
- **HDR 检测缺失保护**：pixi/colr 解析失败 → 保守 bpc=1（不崩，可能丢 HDR 高光但不崩）
- **alpha（auxl）grid 不实现**（YAGNI）：检测到 color item 挂 auxl alpha → 返回 null。超分 app 大图基本无 alpha
- **iloc construction_method≠0**：grid cell 若用 idat(1)/item-relative(2) → 不支持 → null（avifenc 直出 grid 用 cm=0，罕见 cm≠0）
- 写失败/空间不足 → delete cache → null

## 7. 内存与性能

- 峰值内存 = 单 cell bitmap（8-bit: cellW×cellH×4；10-bit: ×4 同 ARGB_8888 或 RGBA_1010102 同字节）+ cell 写缓冲（cellW×cellH×4×bpc，一次 cell 量级）+ wrapper 小数组（~300B）。逐 cell 串行 + recycle
- 2048×2048 cell ≈ 16MB bitmap，安全。16K grid（8×4=32 cells, cell 2K×2K）= 32 次 16MB 解码，串行峰值 16MB + 16MB 缓冲
- 一次 decode 开销：每 cell 1 次系统解码 + ~250B wrapper 构造。cache 一次，后续 `rawExists` 短路
- 结果缓存 `AVIF_Raw/{md5}.raw` 复用，grid 解析成本每 uri 只付一次

## 8. 测试计划

- **AvifGridDecoder 单测**：合成 ISOBMFF bytes（ftyp+meta+iloc v0/v1/v2+dimg+grid payload 16/32-bit+ispe+pixi+colr），断言 GridInfo 正确（rows/cols/output/cellW/cellH/bpc/各 cell offset+length）；覆盖 iloc v0 无 construction_method、v1 有、16/32-bit output、pixi 8/10-bit、colr PQ/SDR
- **AvifCellDecoder 单测**：wrapper 字节可被 BitmapFactory 解（用 spike 素材 cell0 AV1 bytes，已验证 2048×2048 RGBA_1010102）
- **CellWriter 单测**：合成 cell bitmap 写入 → 读回 SVRAW 字节，断言 bpc=1/bpc=2 布局 + 边缘 cell 裁 padding 正确
- **RawImageRegionDecoder bpc=2 单测**：合成 10-bit SVRAW → decodeRegion → ARGB_8888 bitmap，断言降级像素值合理（10-bit 1023 → 255）
- **instrumented（已建 `CellWrapperDecodeTest`）**：保留作为 wrapper 可解性的真机回归门
- **端到端**：用户超分 app 生成 16K–32K grid AVIF 后真机验证：打开显示 1:1 清晰、缩放瓦片正常、内存无 OOM、SVRAW 尺寸=outW×outH×4×bpc+20、HDR 素材 hasUltraHdrContent=true
- 回归：现有 8160×6050 grid（>MAX_PX 但 cell 小）改走逐 cell 路径正常显示；非 grid 单图仍整幅

## 9. YAGNI 排除项

- MediaCodec 裸 AV1 路径（不采用，见 §2）
- alpha/auxl grid 合成（无素材，见 §6）
- libavif native 重编（先前根因未变：`AVIF_CODEC_AOM=ON` 非法值 + 缺 aom_config.h，需联网 FetchContent）
- JXL 改动（用户明确仅 AVIF）
- iloc construction_method=1/2 支持（avifenc 直出 grid 用 cm=0，罕见非 0，遇到再补）
