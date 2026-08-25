# 精选集 AI 加持 — Design Spec

**日期**: 2026-07-09
**作者**: Claude (brainstorming)
**状态**: Draft — 待用户审阅

## 1. 问题陈述

精选页"精选集"栏目 4 张卡片当前是 **写死的假分类**：

| 卡片名 | 当前 cover | 当前 count | 当前 albumId (点击进去) |
|---|---|---|---|
| 本周精选 | `pool.firstOrNull()` | 永远 4 | `format:HEIC` (错挂) |
| 回忆之旅 | `pool.drop(2).firstOrNull()` | 永远 4 | `timeline` |
| 人像 | `宽高比 0.75 容差过滤` | 凑数 4 | `format:JPEG` (错挂) |
| 格式挑战 | `format.isNextGen` 前 4 | 凑数 4 | `format:AVIF_STATIC` |

→ 卡片封面、count、点进去看到的照片集 **完全不匹配名字**。"人像"卡片里看到的可能是屏截图。"本周精选"点进去可能是视频。

### 用户诉求
> 1. 4 张卡片真实生效（封面/count/albumId 都匹配卡片名）
> 2. 本周精选 / 回忆之旅 / 人像 / 格式挑战都要 AI 加持
> 3. 加动漫/游戏/影视剧人物分类
> 4. APK 体积不是问题，效果优先
> 5. 优先性能好的方案，可接受 Room schema migration 风险

## 2. 目标

1. 4 张卡片封面/count/点进照片集都真匹配卡片名
2. 「人像」真用 MLKit Face Detection（精度 > 95%）
3. 「本周精选」真按"最近 7 天 + AI 评分"排序
4. 「回忆之旅」真按 EXIF 时间+地理聚类出 narrative 集
5. 「格式挑战」沿用现有 `format.isNextGen` filter（最不需 AI 的一张卡）
6. **新增动漫/游戏/影视剧识别**，让精选集能区分"现实照片"与"二次元图"

## 3. 设计方案

### 3.1 三模型组合（端侧离线）

| 模型 | 用途 | 大小 (tflite int8) | 库 |
|---|---|---|---|
| **MobileNet V2 1.0_224 quant** | 真实照片 14 大类（人像/夜景/夕阳/雪景/水面/食物/室内/植物/建筑/文档/天空/宝宝/车/动物）| ~13 MB | TFLite |
| **ConvNeXt V2 Tiny Danbooru tagger** (igidn/DT24-Tiny) | 动漫图 Danbooru tag 10k 词表（角色/版权/IP/性别/风格）| ~60 MB | TFLite |
| **MobileCLIP-S2 int8** | zero-shot 文本 prompt 识别：动漫/游戏截图/影视剧截图/插画/产品截图 | ~40 MB | TFLite |

总 APK 增量 **~113 MB**（用户已确认体积可接受）。完全离线，零隐私泄漏，无网络依赖。

**模型来源**（vendor 到 `app/src/main/assets/`）：
- MobileNetV2 1.0_224 quant：`https://storage.googleapis.com/download.tensorflow.org/models/tflite_v2_2/mobilenet_v2_1.0_224_quant.tflite` (官方 TF Lite 预训练量化版)
- ConvNeXt V2 Tiny Danbooru：基于 `huggingface.co/igidn/DT24-Tiny` safetensors → 自己量化 → ONNX → TFLite (build script 一次性脚本)
- MobileCLIP-S2：基于 Apple MobileCLIP-S2（INT8 variant from Apple ML-MobileCLIP），license Apple ML-MobileCLIP，允许商用

> **可视化零样本识别用例**：用 MobileCLIP 算 N 张照片 × K 个文本 prompt 的 cosine similarity，
> prompts:
> ```
> "a photograph of a real person"
> "an anime illustration"
> "a video game screenshot"
> "a movie or TV show screenshot"
> "a digital painting"
> "a product screenshot"
> "a meme or cartoon"
> ```
> 命中率最高的标签作为 `domain`。
> - domain="anime" → 走 ConvNeXt Danbooru 进一步 → `character` name + 版权 IP
> - domain="game"/"movie" → 直接进游戏 / 影视剧精选集
> - domain="real" → 走 MobileNetV2 → 14 大类
> - domain="screenshot"/"other" → 跳过精选集分类

### 3.2 媒体 + 字段扩展

`MediaItem` 新增字段：
```kotlin
data class MediaItem(
    // ... 原有字段 ...
    val aiTags: List<String> = emptyList(),           // 已有
    val ocrText: String? = null,                      // 已有
    // 新增：
    val aiDomain: String? = null,                     // "real"|"anime"|"game"|"movie"|"screenshot"|"other"
    val aiSubDomain: String? = null,                  // 中文短词：一律中文大类别，如"人像"|"夕阳"|"夜景"|"动物"|"动漫插画"|"游戏画面"|"影视剧截图"|"产品截图"；Danbooru 角色名放 aiCopyright
    val aiCopyright: String? = null,                  // Danbooru 角色/版权 IP (e.g. "远坂凛"、"fate/grand_order"、"原神"、"艾尔登法环")
    val aiFaceCount: Int = 0,                         // MLKit 检出的人脸数
    val aiFaceArea: Float = 0f,                       // 人脸占画面比例 (0..1)
    val aiScore: Float = 0f,                          // 综合分 (本周精选排序用)
    val aiVersion: Int = 0,                           // 模型版本号
)
```

### 3.3 Room schema 升级

`media_flag` 表加 7 列 + 1 个 migration：

```kotlin
// V18 → V19 migration
db.execSQL("""
  ALTER TABLE media_flag ADD COLUMN ai_domain TEXT
""")
db.execSQL("""
  ALTER TABLE media_flag ADD COLUMN ai_sub_domain TEXT
""")
db.execSQL("""
  ALTER TABLE media_flag ADD COLUMN ai_copyright TEXT
""")
db.execSQL("""
  ALTER TABLE media_flag ADD COLUMN ai_face_count INTEGER NOT NULL DEFAULT 0
""")
db.execSQL("""
  ALTER TABLE media_flag ADD COLUMN ai_face_area REAL NOT NULL DEFAULT 0
""")
db.execSQL("""
  ALTER TABLE media_flag ADD COLUMN ai_score REAL NOT NULL DEFAULT 0
""")
db.execSQL("""
  ALTER TABLE media_flag ADD COLUMN ai_version INTEGER NOT NULL DEFAULT 0
""")
db.execSQL("""
  ALTER TABLE media_flag ADD COLUMN ai_tagged_at INTEGER
""")
```

迁移只在「AI 分析开关」开启、且用户库首次跑 ai 时执行。失败 → fallback 到 HeuristicClassifier + 跳过精选集增强，UI 不崩。

### 3.4 AI 流水线（WorkManager 后台跑）

新增 `app/src/main/java/com/smartvision/gallery/scanner/AiTaggingWorker.kt`：

- 触发：MediaScanCoordinator 完成增量扫描后 enqueue
- 频率：1 次最多跑 200 张，避免一次性吃内存
- 输入：未 ai_tagged 的 media_flag 行（`ai_version < latest` 或 `ai_tagged_at IS NULL`）
- 单张照片处理：
  1. 取 224×224 thumbnail（已有 BitmapPool，直接复用）
  2. 并行跑 MobileNetV2 + MobileCLIP（约 30-100ms each，HIGH device）
  3. 根据 MobileCLIP domain 选择下一步：
     - "real" → 用 MobileNetV2 top1 生成 `ai_sub_domain`（14 类）
     - "anime" → 跑 ConvNeXt Danbooru，取 threshold > 0.6 的 tag，写 `ai_copyright` + `ai_sub_domain` (character name)
     - "game"/"movie" → 直接保留 domain
  4. 跑 MLKit FaceDetection，写 `ai_face_count` + `ai_face_area`
  5. 计算 `ai_score` = (面部比例 * 0.4) + (top1 confidence * 0.3) + (image quality heuristic * 0.3)
  6. 批量 `db.updateMediaFlagAiFields(uri, domain, subDomain, copyright, faceCount, faceArea, score, version, now)`
- 取消：用户进入隐私敏感开关关 → cancel 当前 worker
- 进度：在 DataStore 存已处理数 + 总数；Settings 页面显示"AI 分析中：N/M"

**性能预算**（HIGH device）：
- MobileNetV2 解码+推理 ~80ms
- MobileCLIP-S2 int8 ~150ms
- ConvNeXt-Tiny Danbooru ~200ms
- MLKit Face 256px ~100ms
- 总单张 ~500ms
- 5000 张 ~ 40 分钟 → 分批跑 + idle 时跑

GPU/NNAPI delegate 自动加速（`AiAccelerator.probe(ctx)` 已实现）。

### 3.5 AlbumListViewModel.curatedCollections 重写

`data/repo/MediaRepository.kt` 加 7 个查询：

```kotlin
fun queryCuratedThisWeekReal(limit: Int = 12): Flow<List<MediaItem>>
  // WHERE dateTakenMs > (now - 7d)
  //   AND aiDomain = 'real' AND aiTaggedAt > 0
  // ORDER BY aiScore DESC LIMIT 12

fun queryCuratedThisWeekAnime(limit: Int = 12): Flow<List<MediaItem>>
  // 上一查询的 anime 版（取 AI 评分高的 IP 同人图）

fun queryPortraits(limit: Int = 12): Flow<List<MediaItem>>
  // aiFaceCount >= 1 AND aiFaceArea >= 0.05 AND aiDomain='real'

fun queryAnimeCharacters(limit: Int = 12): Flow<List<MediaItem>>
  // aiDomain='anime' AND aiCopyright IS NOT NULL
  // GROUP BY aiCopyright ORDER BY COUNT DESC LIMIT

fun queryMemoriesTimeline(): Flow<List<MemoryCluster>>
  // GROUP BY (year-month bucket, geo cell 或 null) WHERE dateTaken > now-90d
  // ORDER BY clusterScore DESC → List<MemoryCluster>(year, month, geo, photoUris, hero, count)

fun queryGameScreens(limit: Int = 12): Flow<List<MediaItem>>
  // aiDomain='game'

fun queryMovieScreens(limit: Int = 12): Flow<List<MediaItem>>
  // aiDomain='movie'

fun queryFormatChallenge(): Flow<List<MediaItem>>
  // 沿用现有 format.isNextGen 列表
```

`AlbumListViewModel.buildCuratedCollections` 重写为：
```kotlin
fun buildCurated(): List<CuratedCollection> {
  return listOf(
    CuratedCollection("本周精选", aiQuery.realThisWeek.firstOrNull(), aiQuery.realThisWeek.count, "ai:thisWeekReal"),
    CuratedCollection("人像",     aiQuery.portraits.firstOrNull(),      aiQuery.portraits.count,       "ai:portraits"),
    CuratedCollection("回忆之旅", aiQuery.memories.hero,                aiQuery.memories.count,        "ai:memories"),
    CuratedCollection("格式挑战", aiQuery.format.firstOrNull(),         aiQuery.format.count,          "format:AVIF_STATIC"),
  )
}
```

第一行只列现实照片，每张卡的 count 真实。

第二行新增（重命名 "精选集" 栏 → "现实精选" + "动漫精选" 双行；不增加行数，把原来 4 张拆开，每行 4 张）：
```kotlin
// "现实精选" 行（人像 / 本周精选 / 游戏画面 / 影视剧截图）
// "动漫精选" 行（动漫人物 / 漫群体 / 二次元插画 / 本周动漫精选）
```

实际 UI 上：精选集 section 标题保持"精选集"一个 SectionHeader，下挂两个 `Row(horizontalScroll)`：

- **上行"现实精选"**：本周精选 / 人像 / 游戏画面 / 影视剧截图 — 4 张（现实类照片才出现）
- **下行"二次元精选"**：本周动漫 / 动漫人物 / 漫群体 / 二次元插画 — 4 张（动漫类照片才出现）

两行 Row 间留 8dp 间距。每行卡片动态出现（≥ 1 张才显示对应卡片，否则对应位置显示"暂无 X"提示卡，4 张位置变 0~4 张动态）。

> 注：每张精选集中的"回忆之旅"对应 ≥ 4.1 节描述的方式（时空聚类），属于"现实精选"行的虚拟卡片；当库中 ≥ 1 个聚类簇时显示，否则显示"暂无回忆"提示卡。所以现实精选行的标题为「本周精选 / 人像 / 游戏画面 / 影视剧截图」四张；时间聚类走专门"回忆之旅" Top appbar entry。

### 3.6 AlbumDetailPage 虚拟 albumId dispatch

加 dispatch handler：

```kotlin
when (albumId) {
  "ai:thisWeekReal"  -> AiAlbumDetail(title="本周精选", query=repo::queryCuratedThisWeekReal)
  "ai:thisWeekAnime" -> AiAlbumDetail(title="本周动漫", query=repo::queryCuratedThisWeekAnime)
  "ai:portraits"     -> AiAlbumDetail(title="人像",     query=repo::queryPortraits)
  "ai:animeChars"    -> AiAlbumDetail(title="动漫人物", query=repo::queryAnimeCharacters)
  "ai:memories"      -> MemoryAlbumDetail(clusters=repo::queryMemoriesTimeline)
  "ai:games"         -> AiAlbumDetail(title="游戏画面", query=repo::queryGameScreens)
  "ai:movies"        -> AiAlbumDetail(title="影视剧截图", query=repo::queryMovieScreens)
  else               -> /* 原有逻辑 */
}
```

`AiAlbumDetail` 复用 AlbumDetailPage 现有渲染（grid 风格相同），只是数据源换 query。

### 3.7 隐私 / 隐私开关

- 新增 Settings 开关："本地 AI 分析"（默认 on）
- off 时：跳过 AiTaggingWorker，curatedCollections 走简单 fallback（前 8 张按时间倒序），4 张卡片照旧但内容相对真实（不再是 fake cover，但全是时间倒序前 8 张）
- 即便 on 也完全离线：模型文件 bundling，所有推理在手机 GPU/CPU 上
- 隐私文案：精选页 + Settings 都展示"所有 AI 计算均在本地完成，照片不会被上传"

### 3.8 模型初始化的原子性

首次启动流程：
1. 检查 `assets/mobilenet_v2_1.0_224_quant.tflite` / `assets/convnextv2_tiny_danbooru_int8.tflite` / `assets/mobileclip_s2_int8.tflite` / `assets/labels_*.txt` 是否就绪（vendor 时就 check-in）
2. TFLite Interpreter 懒加载：第一次 `MediaItem` 需打分时 `Interpreter(Buffer)` 一次，三类模型分别
3. `AiAccelerator.probe()` 决定 delegate：NNAPI → `GpuDelegate` 或 `NnApiDelegate`；GPU → `GpuDelegate`；其他 → `Interpreter`（CPU）
4. 已加载的 Interpreter 在所有 AiTaggingWorker 实例之间共享（`AiModelHub` 单例）

### 3.9 错误处理

| 失败 | 回退 |
|---|---|
| 模型文件损坏 / missing | 走 `HeuristicClassifier` + 4 张卡片 fallback（与现状相同），log 警告 |
| TFLite decode 失败（损坏 JPEG） | 跳过此 uri，下次扫描重试，记 counter |
| 推理超时（> 2s/张） | 跳过此 uri（嫌疑模型在低端设备跑不动），下次扫描重试 |
| Room migration 失败 | 报警日志，4 张卡片 fallback，**不崩** |
| MLKit Play Services 缺失 | 走 Android 内置 `FaceDetector`（已实现） |

### 3.10 文件改动清单

**新增：**
- `app/src/main/assets/mobilenet_v2_1.0_224_quant.tflite`
- `app/src/main/assets/convnextv2_tiny_danbooru_int8.tflite`
- `app/src/main/assets/mobileclip_s2_int8.tflite`
- `app/src/main/assets/labels_mobilenet.txt` (14 class Chinese names)
- `app/src/main/assets/labels_danbooru.txt` (top ~300 tag → Chinese)
- `app/src/main/assets/labels_mobileclip_prompts.txt`
- `app/src/main/assets/labels_characters_sample.txt` (高频 Danbooru 角色 Chinese mapping)
- `app/src/main/java/com/smartvision/gallery/data/ai/VisionClassifier.kt`
- `app/src/main/java/com/smartvision/gallery/data/ai/AiModelHub.kt`
- `app/src/main/java/com/smartvision/gallery/data/ai/DomainRouter.kt` (MobileCLIP 域判别)
- `app/src/main/java/com/smartvision/gallery/data/ai/DanbooruTagger.kt`
- `app/src/main/java/com/smartvision/gallery/scanner/AiTaggingWorker.kt`
- `docs/scripts/convert_models.sh` (一次性转换脚本，把 safetensors → tflite int8)

**修改：**
- `gradle/libs.versions.toml`: + `mlkit-face-detection = "16.3.1"`，`tflite` 升 v2.14.0 → 并 `implementation`
- `app/build.gradle.kts`: `implementation(libs.mlkit.face.detection)` + `implementation(libs.tflite)` + `packagingOptions { excludes += "META-INF/*.kotlin_module" }`
- `data/model/MediaItem.kt`: 加 7 个 ai 字段
- `data/db/MediaFlagEntity.kt` + `MediaFlagDao.kt`: 加 7 列
- `data/db/AppDatabase.kt`: V18 → V19 migration
- `data/repo/MediaRepository.kt`: 加 7 个 query
- `data/ai/HeuristicClassifier.kt`: 保留作 fallback（已实现）
- `scanner/MediaScanCoordinator.kt`: 扫完后 enqueue AiTaggingWorker
- `ui/album/AlbumListViewModel.kt`: buildCuratedCollections 重写 + "精选·现实"/"精选·二次元" 双行
- `ui/album/AlbumListPage.kt`: CuratedCollectionsRow → CuratedSectionRow × 2
- `ui/album/AlbumDetailPage.kt`: 加 7 个虚拟 albumId dispatch
- `ui/activity/SettingsActivity.kt`: 加 "本地 AI 分析" 开关 + "AI 分析 N/M 进度" 显示
- `smartvision/gallery/SmartVisionApp.kt`: 注 AiModelHub 初始化顺序

**完全不动：**
- 液态玻璃（约束）
- 现有 LiquidGlassCard / LiquidGlassSurface / LiquidGlassBackdrop / LiquidGlassTheme
- 现有 GlassConfig 参数
- 现有 viewer / 编辑器 / 隐私保险柜

## 4. 验收标准

### 4.1 功能验收
- 4 张卡片封面真实（人像卡封面 = 真有人脸的图，回忆之旅卡片封面 = 真有时空聚类的图）
- 点进每张卡片看到的照片集与卡片名匹配（不再是 `format:HEIC` 错挂）
- 至少 80% 已扫描照片会跑过 ai pipeline（4800/5000+），< 5% 跳过
- 动漫图确实识别出 Danbooru IP（label 命中 ≥ 60%）
- 游戏截图 / 影视剧截图识别 precision ≥ 70%

### 4.2 性能验收
- 单张照片全 pipeline 耗时 (HIGH device) ≤ 500ms
- 选页 LCP ≤ 1.5s（4 张卡片封面命中缓存，first paint 立即出来）
- 不阻塞相机导入 / 滚动 / 切 tab
- AI Worker 在 Doze / Standby 不执行（按 WorkManager 默认）

### 4.3 稳定性验收
- 模型缺失 → fallback 到 HeuristicClassifier + 4 张 fake 卡片，UI 不崩
- Room migration 失败 → 同上 fallback + 报错 log
- MLKit 不可用 → 走 Android 内置 FaceDetector
- AiTaggingWorker 异常 → 退避 + retry 三次，再失败则放弃该 uri

### 4.4 隐私验收
- 无网络 IO（除 WorkManager / 用户自己 sync 外）
- 模型预测过程不写任何日志到云
- Settings 加 "本地 AI 分析" 关闭入口，关闭后 5 秒内停 worker

## 5. 实施计划

按 writing-plans skill 出详细步骤。粗略拆分：

1. **基础**: 加 tflite + mlkit 依赖；vendor 三个 .tflite 文件
2. **数据**: MediaItem 加 7 字段；Room migration V18 → V19；repository 加 7 查询
3. **AI 引擎**: AiModelHub 单例 + VisionClassifier + DanbooruTagger + DomainRouter
4. **MLKit face**: MlKitFaceDetector 替代 HeuristicClassifier.facesFor()，保留 fallback
5. **调度**: AiTaggingWorker + MediaScanCoordinator enqueue
6. **UI**: buildCuratedCollections 重写为真查询；UI 双行 (现实/二次元)
7. **Dispatch**: AlbumDetailPage 加 7 个虚拟 albumId
8. **Settings**: 隐私开关 + 进度展示
9. **测试**: 单元 + 集成 + E2E
10. **验收**: 装到机上手测所有 4 卡片

## 6. 待用户决策点（spec review 时确认）

1. **卡片排序**: 4 张卡片顺序是否保持「本周精选 / 人像 / 回忆之旅 / 格式挑战」或重排
2. **新增卡的封面**: 现实精选第二行要有 4 张，但只保留了"格式挑战"可能与其他两张关联度低 — 是否需要 "朋友圈/合影/3 月精选"等本地化名称？
3. **模型供应商**: 三个 .tflite 模型我可以 vendor 为 git-lfs 二进制（~113MB），或下载到 `ai-models/` 通过首次启动下载（APK 体积小）
4. **进度展示文案**: "AI 分析中：3,248 / 5,000" 是否合适
5. **失败回退**: 模型缺失 / migration 失败时，是否想看一个底部 banner 提示 "AI 不可用"，还是安静退化

## 7. 风险

| 风险 | 概率 | 影响 | 回退 |
|---|---|---|---|
| ConvNeXt V2 Tiny 转 TFLite int8 不稳定，accuracy drop 严重 | 中 | 大 | 选 v1.0 量化版本 / 切回 ConvNeXt V1 Tiny |
| MobileCLIP-S2 INT8 在 Android TFLite 上跑动 | 低 | 中 | 用 MobileCLIP-BLIP-Base 替代 / 用 CLIP-ViT-B-16 quant |
| 113MB APK 增量把部分用户拒之门外 | 中 | 中 | 把 3 个模型从中剔除，按需 lazy download (留 S3 URL + hash 校验) |
| 实际"动漫"识别 precision 不达标 (~50%) | 中 | 大 | 改阈值 + 重 prompt；如仍不佳，回退到方案 B（只用 13MB MobileNet + Heuristic）|
| Room migration V18 → V19 在某些厂商 ROM 失败 | 低 | 中 | 捕获 Migration 异常 + 提示"重建数据库"按钮 |

## 8. 结论

按 HARD-GATE 要求：此 spec 必须经用户**逐节审阅确认**后，才能进入 writing-plans。等待用户在以下选项给出反馈：

1. ✅ 章节 1-4 全文 OK 否
2. 章节 6 待决策点请逐条回复
3. 章节 7 风险是否需要 mitigation

若全部 OK，下一步 invoke **writing-plans** skill 写详细实施步骤。
