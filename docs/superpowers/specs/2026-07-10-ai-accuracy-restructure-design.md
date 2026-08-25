# SmartVision Gallery — AI 识别准度全面重构

| | |
|---|---|
| **Date** | 2026-07-10 |
| **Status** | Draft (待用户审批) |
| **Scope** | `data/ai/*` + `AiModelHub` 资产 + `AlbumListPage` 入口 |
| **Goal** | 把"识别准确率堪称灾难"的根本错配（ImageNet 1000 当场景分类）换成 **Places365 真场景分类 + Danbooru 专精二次元 + MLKit Face 专精人像** 的多模型分工 |
| **Author** | brainstorming skill output |

---

## 1. 问题陈述

当前 14 sub-domain 分类由 **MobileNet V2 1.0_224 quant (ImageNet 1001)** 扛大头，配上 384 个手工 synset 映射表 + 颜色 heuristic fallback。从日志与用户反馈：

- **根本错配**：ImageNet 是物体分类（"dog/spider/card"），不是场景分类。Places365 才是场景分类（"forest/jungle/beach/aquarium"）。
- 14 个里 5 类（夕阳/雪景/天空/水面/夜景）ImageNet 完全没有对应 synset，全部靠 32×32 downsample 的颜色 heuristic 兜底，**误判率超 60%**。
- MobileCLIP-S2 已经在 `DomainRouter` 内被注释废弃（"实测 cosine ≈ 0，分类退化为 all-real"）—— 留着只会拖慢首跑、不出价值。
- `DomainRouter.route()` 仍用 `416/417 book jacket/comic book` 启发式定位"anime"，覆盖率极低，二次元的精准度事实上由 `DanbooruTagger` 决定。

UI 侧已经有 `AlbumListPage` 的"AI 智能分类" 3×5 网格（15 个 `aiCategoryFolders`），用户选 **方案A 网格+emoji+数量** —— UI 已经是这个形态，**核心动的是模型与 cascade**。

## 2. 目标

| 指标 | 当前 | 目标 |
|---|---|---|
| 5 个 ImageNet 缺失类（夕阳/雪景/天空/水面/夜景）的 top-1 命中率 | < 40% (依赖颜色 heuristic) | **≥ 80%**（Places365 接管） |
| 食物 / 动物 / 物体的 top-1 命中率 | ~65%（错位映射） | **≥ 85%**（继续用 ImageNet-1k 截取） |
| 二次元 subDomain 假阳性 | 已修复（`hasAnimeStyle` 守卫） | 保持 ≤ 5% |
| 真人/人像 subDomain 命中率 | ~50%（ImageNet 仅 12 个 face synset） | **≥ 85%**（`MLKit Face` 接管） |
| 单张推理平均延迟（224×224 短边） | ~2.4s（MobileCLIP 在白跑 + Danbooru 全开） | **≤ 1.5s**（前置门控） |

## 3. 架构

### 3.1 模型分工

```
输入 224×224 Bitmap
   │
   ▼
[1] MLKit Face (PortA, 同步,~30ms)
   │  → faceCount, faceArea
   │  → faceCount ≥ 1 + conf ≥ 0.5 即: subDomain = "人像", 进入 [5]
   │
   ▼ 否则
[2] Places365 ResNet50 (TFLite ~30MB, ~250ms)
   │  → top1 synset ∈ {0..364}
   │  → PLACES365_TO_SCENE 映射表
   │  → 进入 [5]
   │
   ▼ 若 top1 conf < 0.30 (极不确信)
[3] ImageNetV2 MobileNet (现有, ~80ms)
   │  → 14 类常规物体兜底 (food/animal 等)
   │  → 进入 [5]
   │
   ▼ 若 top1 idx ∈ COMIC_BOOK_IDXS
[4] DanbooruTagger (WD ConvNeXt V3, ~2s)
   │  → 已有的二次元升级路径
   │  → 进入 [5]
   │
[5] Score 聚合 → 入 media_flags
```

**关键变化**：
- **DLBENCH：撤掉 MobileCLIP** —— 完全不参与主路径，资产可保留但新代码不再 init，省 68MB 资产解包
- **新增 Places365**：覆盖 5 类真正场景
- **MLKit 前置**：人像判定直接用 Face 计数，不再依赖 ImageNet 错配（人脸 = 人像）
- **Danbooru 守门**：只有漫画/插画这种 synset（comic_book 417、book_jacket 416、envelope 549 弱） 才路由到 DanbooruTagger

### 3.2 subDomain 终态（13 类 + 1 fallback）

| idx | subDomain (中文) | subDomain (db key) | 触发顺序 |
|---|---|---|---|
| 0 | 人像 | `portrait` | MLKit Face count ≥ 1 |
| 1 | 夜景 | `night` | Places365: candle/lampshade/spotlight/bar/restaurant |
| 2 | 夕阳 | `sunset` | Places365: canyon/rainbow/desert/sun/horizon |
| 3 | 雪景 | `snow` | Places365: ski_slope/snowfield/iceberg/glacier |
| 4 | 水面 | `water` | Places365: lakeside/seashore/coral_reef/ocean |
| 5 | 天空 | `sky` | Places365: sky/cloud_rainy/aerial_view |
| 6 | 室内 | `indoor` | Places365: living_room/bedroom/kitchen/library |
| 7 | 建筑 | `building` | Places365: castle/church/palace/monastery/bridge |
| 8 | 植物 | `plant` | Places365: forest/jungle/garden/rapeseed/grass |
| 9 | 食物 | `food` | ImageNetV2 924-969 (直接复用现有映射) |
| 10 | 动物 | `animal` | ImageNetV2 1-100, 151-400 |
| 11 | 文档 | `document` | Places365: book_jacket/menu/comic_book/letter/computer |
| 12 | 宝宝 | `baby` | MLKit Face + age heuristic + Places365: nursery |
| 13 | 动漫插画 | `anime` | Places365: comic_book + DanbooruTagger `hasAnimeStyle \|\| characterTag` |
| -1 | 其他 | `other` | cascade 失败 fallback |

（与现有 14 类的差异：合并 person+portrait→`portrait`，拆 anime→`anime`，去 movie_screenshot，去 digital_painting/meme/game_screenshot。把 `subDomain` 字串对齐 Db 列值。）

### 3.3 数据层向后兼容

- **新增 column**：`media_flags.sub_domain_version INTEGER NOT NULL DEFAULT 0`
- Room V19 → V20 migration：加列 + 默认 0
- 旧（version=0）row 在 UI 仍显示但在子页头加黄条："AI 算法已升级，建议重新扫描以获得更好识别"
- `AiTagger.AI_VERSION = 9`（接续 8），写入 row 时同步 version=9
- 设置页面 "立即全库重新扫描 AI" 按钮 → enqueue `AiTaggingWorker` 强制重跑

## 4. 组件

### 4.1 资产 (assets/)

| 文件 | 大小 | 用途 |
|---|---|---|
| `places365_resnet50_int8.tflite` | ~30 MB | 场景分类主模型，新增 |
| `places365_labels.txt` | ~6 KB | 365 个场所类名 + 中文映射 |
| `mobilenet_v2_1.0_224_quant.tflite` | 4.2 MB | 复用（食物/动物物体） |
| `wd_convnext_tagger_v3.onnx` | 376.7 MB | 复用（二次元） |
| `mobileclip_s2_int8.tflite` | 68 MB | **保留** 不动（暂不剔除以便快速回滚） |

下载源（从优到次）：
1. `huggingface.co/onnx-community/resnet50-places365`（ONNX，最稳）
2. `huggingface.co/1aurent/resnet50-places365`（PyTorch 原始权重）→ `onnx2tf` 转 TFLite int8
3. `csail.mit.edu/places365` 原版 PyTorch → 自己转

**模型加载失败策略**：hub init try/catch，`isPlacesAvailable=false` 时降级方案三继续工作（不破坏现有体验）。

### 4.2 新 / 改 / 删文件

| 文件 | 动作 | 说明 |
|---|---|---|
| `data/ai/Places365Classifier.kt` | **新增** | 365→13 subDomain 映射；top-1 解析；fallback |
| `data/ai/VisionClassifier.kt` | **简化为物体兜底** | 仅 ImageNet-1k → 食物/动物/文档三轴；移走 5 类场景 |
| `data/ai/AiModelHub.kt` | **加** `places365()` interpreter + 加 `isPlacesAvailable` | 旧 API 保留签名 |
| `data/ai/DomainRouter.kt` | **重写** | 4 模型 cascade，按 3.1 节顺序；撤 MobileCLIP |
| `data/ai/AiTagger.kt` | **改** | pipeline 接 Places365；`subDomain_version` 写入；`AI_VERSION = 9` |
| `data/ai/AiTaggingWorker.kt` | **小改** | 跑完每张写 version=9，读旧 version=0 时强制重跑 |
| `data/model/MediaFlagEntity.kt` | **加列** `subDomainVersion: Int = 0` | |
| `data/db/MigrationV19V20.kt` | **新增** | Room V19→V20 ALTER TABLE |
| `data/AppDatabase.kt` | **改 version** + 注册 Migration | |
| `domain/AiSmartCategory.kt` | **新增** | 13 类元数据 (id, zh, en, emoji, color) |
| `ui/settings/SettingsAiSection.kt` | **加按钮** "立即重新 AI 扫描全库" | |
| `ui/album/AlbumDetailPage.kt` | **派发新 subDomain 别名** | 把 anime 路由到现有 anime 页 |

**净改动**：8 文件改 + 3 文件新增 + 1 资产新增 + UI 局部不动
**预估代码量**：~600 行（含注释），多数集中在 `Places365Classifier` 与改写 `DomainRouter`

### 4.3 Places365 分类流水线

```kotlin
class Places365Classifier(context: Context) {
    private val itp = AiModelHub.get(context).places365()  // Lazy
    private val isReady = itp != null

    fun classify(bitmap: Bitmap): SceneResult? {
        // 1. 缩放到 224×224 (与 MobileNet 同)
        // 2. ARGB → 浮点, (R-mean)/std, ImageNet 标准化
        // 3. input: ByteBuffer shape [1,3,224,224] float32
        // 4. run: 输出 shape [1,365] float32 (softmax 后)
        // 5. argmax → idx ∈ [0, 364]
        // 6. 查 PLACES365_TO_SCENE 表 → subDomain 或 null
        // 7. 若 null → 看 top-3, top-3 全 null → "other"
    }

    data class SceneResult(val subDomain: String, val conf: Float, val topSynset: Int)
}
```

`PLACES365_TO_SCENE` 是手工映射表（基于 MIT 提供的 365 类语义清单）：

```kotlin
// 真实映射见 Places365Classifier.kt 内部表 — 这里列出格式示例
private val PLACES365_TO_SCENE = mapOf(
    178 to "night",          // "stage/indoor" → night (灯光强场景)
    188 to "night",          // "music_studio"
    282 to "night",          // "bar/indoor"
    11 to "sunset",          // "alley"
    // ... 365 → 13 类 一对一映射, ~120 类映射到具体 subDomain,
    //     其余 245 类 → null (留作 top-3 加权判定)
)
```

颜色 heuristic 仅在 Places365 conf<0.3 *且* top-3 都映射到 null 时启用，等同现有 `parseFallbackColor`，搬到 `Places365Classifier` 内复用。

### 4.4 DomainRouter 重写后的核心

```kotlin
class DomainRouter(context: Context) {
    private val places365 by lazy { Places365Classifier(context) }
    private val vision by lazy { VisionClassifier(context) }
    private val danbooru by lazy { DanbooruTagger(context) }
    private val face by lazy { MlKitFaceAnalyzer(context) }

    fun route(bitmap: Bitmap): RouteDecision {
        // Stage 1: Face
        val f = face.detect(bitmap)
        if (f.count >= 1 && f.confidence >= 0.5) {
            return RouteDecision(domain = "real", subDomain = "portrait", faceCount = f.count)
        }

        // Stage 2: Places365
        val places = places365.classify(bitmap)
        if (places != null) {
            // 油画/插画/comic 类 → 升级 anime 跑 Danbooru
            if (places.topSynset in COMIC_SYNSET) {
                val d = danbooru.detect(bitmap)
                if (d != null && d.hasAnimeStyle) {
                    return RouteDecision("anime", "anime", character = d.characterTag)
                }
                // Danbooru 否定 → fallback 到 book_jacket/animation 类的 "其他"
                return RouteDecision("real", "other")
            }
            return RouteDecision("real", places.subDomain)
        }

        // Stage 3: ImageNet 物体兜底
        val v = vision.classify(bitmap)
        return when (v?.category) {
            "food" -> RouteDecision("real", "food")
            "animal" -> RouteDecision("real", "animal")
            "document" -> RouteDecision("real", "document")
            else -> RouteDecision("real", "other")
        }
    }

    data class RouteDecision(
        val domain: String,        // "real" | "anime" | ...
        val subDomain: String,     // 13 类 + "other"
        val faceCount: Int = 0,
        val character: String? = null,
        val confidence: Float = 0f,
    )
}
```

### 4.5 Worker 调度

- `AiTaggingWorker` 调度不变（Semaphore=4 并行已上）
- 新加：每张图处理完后 `flagRepo.updateSubDomainVersion(mediaId, 9)`；worker 启动时查 `subDomainVersion<9` 全表 enqueue

## 5. UI 重组

### 5.1 AlbumListPage 现状 — 已符合方案 A
现状 `AlbumListPage.kt:300-339` 的 `AI 智能分类` section 已经把 15 个 `aiCategoryFolders` 渲染成 3×5 网格，每格 1:1 缩略图 + 标题 + 张数。**视觉上已经是选项 A**，本文档保留并精修：

- 把 `aiCategoryFolders` 改为 13 类（合并 person+portrait，删冗余项）
- `AiCategoryTile` 配色：每类一 emoji + 强调色（绿=plant, 蓝=water, 橙=sunset...）
- `subtitle` 从 `${count} 张` 改为 `${count} 张 · 子模块"二次元"`
- 已识别旧标签 (`subDomainVersion<9`) 在该 section 顶部加一行黄色的"算法升级提示" banner，引导去设置重扫

### 5.2 Settings 新增
- 卡片 "AI 智能分类"
- 开关：AI 自动扫描 (默认开，保留现有)
- **按钮** "立即重 AI 扫描全库" → 调 `AiTaggingWorker.scheduleAll(forceReRun=true)`
- 进度条：根据 `(已扫数 / 总数)` 比例

## 6. 实施步骤（high-level — 实施时再细分）

| # | 步骤 | 估时 | 验收 |
|---|---|---|---|
| 1 | 下载/转换 Places365 ResNet50 TFLite int8 | 15min | bin 在 assets/，可被 AiModelHub 加载 |
| 2 | 新建 `Places365Classifier.kt` | 30min | `places365_classify_test`: top1 推理 < 250ms |
| 3 | 重写 `DomainRouter` 4 模型 cascade | 45min | `domain_router_cascade_test`: 各阶段跳转 |
| 4 | 简化 `VisionClassifier` 至物体兜底 | 20min | 单测 food/animal/document 命中 ≥ 80% |
| 5 | 加列 subDomainVersion + Migration V19→V20 | 20min | 设备升级不会 crash, 老数据可见 |
| 6 | `AiTagger` 升 AI_VERSION=9, 串接 Places365 | 30min | 手动跑 30 张真实照片, log 子域分布正确 |
| 7 | Room + Worker + 强制重跑调度 | 30min | 全库 100 张重 AI 5 分钟内 |
| 8 | AlbumListPage 改 13 类 + emoji 色 + banner | 30min | 网格呈现 |
| 9 | Settings "立即重扫" 按钮 + 进度条 | 20min | 点击 → 真重跑 |
| 10 | 编译 + adb install 5ddfea15 + 30 张实测 | 60min | 5 类改善指标 ≥ 80% |

总 ~5小时（含 build/install 验证）。每步保留 commit。

## 7. 风险 + 缓解

| 风险 | 影响 | 缓解 |
|---|---|---|
| Places365 转换 TFLite 后精度掉 | 命中指标未达 80% | 备：直接用 PyTorch→ONNX→TFLite 再转一次；最差 fallback 5 类继续用 ImageNet+heuristic |
| 365 类场所类多人脸/插画误判 | portrait/anime 混淆 | Stage 1 MLKit Face > Stage 2 Places365 > Stage 3 imageNet |
| 30 MB 资产 APK 体积 +10% | 下载/安装体积变大 | 同时考虑删除 68 MB MobileCLIP (净 + -38 MB) |
| Room V19→V20 加列迁移失败 | 老用户 app crash | Migration ALTER 默认值 `DEFAULT 0`，失败时 try/catch + `fallbackToDestructiveMigration` 仅 debug 启用 |
| Worker 强制重跑堵塞 UI 扫描 | 用户感受卡顿 | Semaphore=4 限并发 + ProgressFlow 上 UI |
| 老 photo 重新 AI 触发 CursorWindow NO_MEMORY (历史问题) | worker OOM | `queryAll` 已移 inline EXIF GPS, 此风险已低 |

## 8. 验收测试

不依赖截图，按用户持久指令走 log 验证：

1. **`adb logcat -s VisionClassifier,DomainRouter,Places365Classifier,AiTagger`**
2. 上传 30 张图（人/食物/风景/二次元各 7-8 张）→ 等 3 分钟 worker 跑完
3. 验证：
   - 真人照 ≥ 90% 进 `portrait`
   - 夕阳照 ≥ 80% 进 `sunset`
   - 雪景照 ≥ 80% 进 `snow`
   - 二次元照 ≥ 90% 进 `anime` 且 `anime_confidence ≥ 0.3`
   - 食物照 ≥ 80% 进 `food`
4. SQL `SELECT sub_domain, COUNT(*) FROM media_flags WHERE subDomainVersion=9 GROUP BY sub_domain` 验证分布

## 9. 不在本次范围 (Out of Scope)

- 简化类 `subDomain` 命名合并 (`portrait`/`person`)
- 删除 `mobileclip_s2_int8.tflite` 68 MB 资产
- 加更细分 subDomain（如 split `indoor` 为 `living/bedroom/kitchen`）
- 跨子域标签搜索
- AI 模型热替换/下载升级机制

---

## 附录 A — Places365 365 类清单（节选）

[36MB 列表留作实施时填充到 `places365_labels.txt`]

| idx | EN | subDomain |
|---|---|---|
| 0 | airfield | sky |
| 11 | alley | sunset 候选 |
| 178 | stage/indoor | night |
| 282 | bar/indoor | night |
| 350 | coral_reef/underwater | water |
| ... | (365 行) | ... |

## 附录 B — `PLACES365_TO_SCENE` 映射规范

- 5 个 ImageNet 缺类（`sunset/snow/water/sky/night`）有 ~25 个 Places 类可触发，多数为 sky(19x), water(8x), snow(5x)
- 室内类统一 → `indoor`
- 室外建筑 → `building`
- 自然森林植被 → `plant`
- 商业场所 (restaurant/cafe) 默认 → `indoor`，但是有 spotlights 也可升级 `night`

【End】
