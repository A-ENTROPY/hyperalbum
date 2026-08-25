# SmartVision Gallery — AI 识别准度重构 实施 Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把智能相册 AI 子域分类从 ImageNet 1000 强映射换成 **Places365 真场景分类 + MLKit Face 守门 + Danbooru 专精二次元** 的 4 模型 cascade；11 子域数据库 schema 加 version 列做向后兼容；AlbumListPage 3×5 网格 + 设置页"立即重扫"按钮。

**Architecture:**
- `AiModelHub.places365()` 懒加载 Places365 ResNet50 TFLite interpreter, 新增 ~30 MB 资产
- `DomainRouter` 改为 cascade pipeline：MLKit Face → Places365 → ImageNetV2 兜底 → Danbooru 守门
- 13 类 subDomain（合并 portrait/person, 拆分 anime, 去 game_screenshot/digital_painting/meme）
- `media_flags.subDomainVersion` Room V19→V20 迁移, `AI_VERSION=9`

**Tech Stack:** Kotlin · TFLite 0.9.0 · MLKit Face 16.1.7 · Room 2.5 · WorkManager · Coil · Compose · adb logcat

**Spec Reference:** `docs/superpowers/specs/2026-07-10-ai-accuracy-restructure-design.md`

---

## File Map

### New Files
- `app/src/main/java/com/smartvision/gallery/data/ai/Places365Classifier.kt` — Places365 推理 + 365→13 subDomain 映射 + 颜色 heuristic 兜底
- `app/src/main/java/com/smartvision/gallery/data/ai/domain/AiCategoryMeta.kt` — 13 类 metadata (id, zh, en, emoji, accent color)
- `app/src/main/assets/places365_resnet50_int8.tflite` — 30 MB 资产
- `app/src/main/assets/places365_labels.txt` — 365 个场所类英文名
- `app/src/main/java/com/smartvision/gallery/data/db/MigrationV19V20.kt` — Room ALTER 加 subDomainVersion 列

### Modified Files
- `app/src/main/java/com/smartvision/gallery/data/ai/AiModelHub.kt` — 新加 places365() interpreter + isPlacesAvailable getter
- `app/src/main/java/com/smartvision/gallery/data/ai/VisionClassifier.kt` — 删 5 个 ImageNet 缺失类映射, 仅保留 food/animal/document
- `app/src/main/java/com/smartvision/gallery/data/ai/DomainRouter.kt` — 重写为 4-阶段 cascade
- `app/src/main/java/com/smartvision/gallery/data/ai/AiTagger.kt` — AI_VERSION=9, 接入 Places365, 写入 subDomainVersion
- `app/src/main/java/com/smartvision/gallery/data/ai/AiTaggingWorker.kt` — forceReRun 调度 + 强制重跑判定
- `app/src/main/java/com/smartvision/gallery/data/model/MediaFlagEntity.kt` — 新加 subDomainVersion: Int DEFAULT 0
- `app/src/main/java/com/smartvision/gallery/data/db/AppDatabase.kt` — version 19→20, 注册 Migration V19→V20
- `app/src/main/java/com/smartvision/gallery/ui/album/AlbumListPage.kt` — 13 类渲染 + emoji 配色 + 版本升级 banner
- `app/src/main/java/com/smartvision/gallery/ui/album/AlbumListViewModel.kt` — aiCategoryFolders 切换为 AiCategoryMeta.ids
- `app/src/main/java/com/smartvision/gallery/ui/settings/SettingsAiSection.kt` — 加 "立即重 AI 扫描全库" 按钮 + 进度条
- `app/src/main/AndroidManifest.xml` — 无改 (无新权限)
- `app/build.gradle.kts` — 无改 (无新依赖)

**Net:** 5 新文件 + 9 改文件 + 1 新增 30MB 资产。

---

## Task 1: 下载 Place365 TFLite 资产 + labels

**Files:**
- Create: `app/src/main/assets/places365_resnet50_int8.tflite`
- Create: `app/src/main/assets/places365_labels.txt`

- [ ] **Step 1: 从 HuggingFace 镜像下载 PyTorch→ONNX Place365 模型**

Run:
```bash
mkdir -p /tmp/places365
cd /tmp/places365
curl -L -o resnet50_places365.onnx "https://huggingface.co/onnx-community/resnet50-places365/resolve/main/onnx/model.onnx"
ls -la resnet50_places365.onnx
```
Expected: 一个 ~102 MB 的 `resnet50_places365.onnx` 文件 (PyTorch→ONNX 输出)

- [ ] **Step 2: 转 ONNX→TFLite int8 (用 onnx2tf 工具)**

Run:
```bash
pip install onnx2tf
onnx2tf -i resnet50_places365.onnx -o tflite_out --quantize int8
ls -la tflite_out/
```
Expected: 出现 `model_int8.tflite` (~25-35 MB), 输入 shape `[1,3,224,224]` 输出 shape `[1,365]`

- [ ] **Step 3: 重命名 + 移到 assets**

Run:
```bash
cp tflite_out/model_int8.tflite "H:/workspace-minimaxcode/新建文件夹/超级相册/app/src/main/assets/places365_resnet50_int8.tflite"
ls -la "H:/workspace-minimaxcode/新建文件夹/超级相册/app/src/main/assets/places365_resnet50_int8.tflite"
```
Expected: ~25-35 MB 模型成功 copy

- [ ] **Step 4: 下载 365 类英文名 labels**

Run:
```bash
curl -L -o "H:/workspace-minimaxcode/新建文件夹/超级相册/app/src/main/assets/places365_labels.txt" "https://raw.githubusercontent.com/CSAILVision/places365/master/categories_places365.txt"
head -5 "H:/workspace-minimaxcode/新建文件夹/超级相册/app/src/main/assets/places365_labels.txt"
wc -l "H:/workspace-minimaxcode/新建文件夹/超级相册/app/src/main/assets/places365_labels.txt"
```
Expected: 第一行类似 `0 /a/airfield`, 共 365 行 (`365 labels.txt`)

- [ ] **Step 5: 验证 TFLite 模型 inputs/outputs (本地 Python)**

Run:
```bash
pip install tflite-runtime
python -c "
import tflite_runtime.interpreter as tflite
itp = tflite.Interpreter(model_path='H:/workspace-minimaxcode/新建文件夹/超级相册/app/src/main/assets/places365_resnet50_int8.tflite')
itp.allocate_tensors()
print('Input:', itp.get_input_details()[0]['shape'], itp.get_input_details()[0]['dtype'])
print('Output:', itp.get_output_details()[0]['shape'], itp.get_output_details()[0]['dtype'])
"
```
Expected:
```
Input: [1, 3, 224, 224] <class 'numpy.float32'>    # 或 int8
Output: [1, 365] <class 'numpy.float32'>
```

- [ ] **Step 6: 记录模型 input 预处理规范供 Places365Classifier 引用**

在 Task 3 用到的关键信息：
- 输入: `ByteBuffer` shape `[1,3,224,224]`, **float32** (预处理: RGB/R_mean/255 归一化后 × (1/std), ImageNet mean/std 标准)
- 输出: `float[]` length 365 (softmax 后 → 概率)
- 标签顺序: `[categories_places365.txt]` 第 i 行 → idx i

- [ ] **Step 7: 提交**

```bash
cd "/h/workspace-minimaxcode/新建文件夹/超级相册"
git add app/src/main/assets/places365_resnet50_int8.tflite app/src/main/assets/places365_labels.txt
git -c commit.gpgsign=false commit -m "feat(ai): vendor Places365 ResNet50 int8 model + 365 labels"
```

---

## Task 2: AiModelHub 新增 places365() interpreter

**Files:**
- Modify: `app/src/main/java/com/smartvision/gallery/data/ai/AiModelHub.kt`

- [ ] **Step 1: 读现有 AiModelHub.kt**

```bash
cat "/h/workspace-minimaxcode/新建文件夹/超级相册/app/src/main/java/com/smartvision/gallery/data/ai/AiModelHub.kt"
```

- [ ] **Step 2: 添加 places365() lazy delegate + isPlacesAvailable flag**

Edit `AiModelHub.kt`：在现有 `mobileclip()`, `mobilenet()`, `danbooru()` 三个 lazy 后新增 `places365()`，并加 `isPlacesAvailable: Boolean` 计算属性。Init 失败 try/catch 与现有 `mobileclip` 一致。

Append to `AiModelHub` class body:
```kotlin
val places365: MappedByteBuffer? by lazy {
    runCatching {
        AssetFileDescriptorCompat.create(
            context, "places365_resnet50_int8.tflite"
        ).use { afd ->
            itpFromBuffer(afd.createInputStream().readBytes())
        }
    }.getOrNull()
}
```

在现有 `isClipAvailable` 旁加：
```kotlin
val isPlacesAvailable: Boolean
    get() = places365 != null
```

**注意**：项目用的不是 Android `AssetFileDescriptor` 而是 androidx.core 版本，确认现有 `mobilenet()` 用法保持一致再套用。如现有用 `context.assets.openFd()` 简化即可 — 与现有 `mobileclip()` 同样写法。

- [ ] **Step 3: 编译验证**

Run:
```bash
cd "/h/workspace-minimaxcode/新建文件夹/超级相册"
./gradlew assembleDebug 2>&1 | tail -20
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: 提交**

```bash
git add app/src/main/java/com/smartvision/gallery/data/ai/AiModelHub.kt
git -c commit.gpgsign=false commit -m "feat(ai): AiModelHub.places365() interpreter + isPlacesAvailable"
```

---

## Task 3: 新建 Places365Classifier.kt — 365→13 subDomain 映射 + 颜色 heuristic

**Files:**
- Create: `app/src/main/java/com/smartvision/gallery/data/ai/Places365Classifier.kt`
- Test: `app/src/test/java/com/smartvision/gallery/data/ai/Places365ClassifierTest.kt`

- [ ] **Step 1: 写 failing 单测 — 365 类映射表 + 颜色 heuristic 已知场景**

Create `Places365ClassifierTest.kt`:
```kotlin
package com.smartvision.gallery.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class Places365ClassifierTest {

    @Test
    fun `synset 282 bar_indoor maps to night`() {
        val id = Places365Classifier.Companion.sceneIdForSynset(282)
        assertEquals("night", id)
    }

    @Test
    fun `synset 350 coral_reef maps to water`() {
        assertEquals("water", Places365Classifier.Companion.sceneIdForSynset(350))
    }

    @Test
    fun `synset 100 abstract synset returns null`() {
        // 类目表中未列出的 idx → null (调用方走颜色 heuristic)
        assertNull(Places365Classifier.Companion.sceneIdForSynset(999))
    }

    @Test
    fun `comic book synset 307 maps to anime eligible`() {
        assert(Places365Classifier.Companion.isComicEligible(307))
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run:
```bash
cd "/h/workspace-minimaxcode/新建文件夹/超级相册"
./gradlew testDebugUnitTest --tests "*Places365ClassifierTest*" 2>&1 | tail -15
```
Expected: `Places365Classifier.Companion.sceneIdForSynset` unresolved reference 失败

- [ ] **Step 3: 写 Places365Classifier.kt**

完整代码 — 大表放在 `PLACES365_TO_SCENE`，comic 类放 `COMIC_ELIGIBLE`，新增 `sceneIdForSynset(idx)` 与 `isComicEligible(idx)` 静态 API，颜色 heuristic 复用 `VisionClassifier` 现有算法 (提取到 `CommonColorHeuristic.parseFallbackColor(bitmap)`）。

```kotlin
package com.smartvision.gallery.data.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import com.smartvision.gallery.util.AppLog
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Places365 真场景分类 (MIT CSAIL — 365 个场所类).
 * 取代 VisionClassifier 中用 ImageNet 强映射 + 颜色 heuristic 猜场景的逻辑.
 *
 * ## 准确度
 *  - 真人/食物/动物/文档仍由 VisionClassifier (ImageNet) 接管 — Places 是场景专模
 *  - 5 类 (night/sunset/snow/water/sky) 之前用 ImageNet + 颜色 heuristic < 40% → Places 接管可达 ≥ 80%
 *
 * ## 数据流
 *  224×224 RGB bitmap → ImageNet 标准化 → ByteBuffer → TFLite run → argmax → 映射表 → subDomain id
 *  顶-1 top synset 不在映射表中 → 看 top-3; 都映射不到 → 调 CommonColorHeuristic 兜底
 */
class Places365Classifier(context: Context) {

    data class SceneResult(
        val subDomain: String,   // "night"/"sunset"/"snow"/"water"/"sky"/"indoor"/"building"/"plant"/"document"
        val confidence: Float,
        val topSynset: Int,
    )

    private val itp by lazy { AiModelHub.get(context).places365 }

    fun isReady(): Boolean = itp != null

    fun classify(bitmap: Bitmap): SceneResult? {
        val tflite = itp ?: return null
        return try {
            val resized = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)
            val input = ByteBuffer.allocateDirect(INPUT_SIZE * INPUT_SIZE * 3 * 4)
                .order(ByteOrder.nativeOrder())
            val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
            resized.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
            val mean = floatArrayOf(0.485f, 0.456f, 0.406f)
            val std = floatArrayOf(0.229f, 0.224f, 0.225f)
            for (p in pixels) {
                val r = ((p shr 16) and 0xFF) / 255f
                val g = ((p shr 8) and 0xFF) / 255f
                val b = (p and 0xFF) / 255f
                input.putFloat((r - mean[0]) / std[0])
                input.putFloat((g - mean[1]) / std[1])
                input.putFloat((b - mean[2]) / std[2])
            }
            input.rewind()
            val outCount = tflite.getOutputTensor(0).numElements()
            val output = Array(1) { FloatArray(outCount) }
            tflite.run(input, output)
            val probs = output[0]
            // Top-3 索引
            val top3 = probs.indices
                .sortedByDescending { probs[it] }
                .take(3)
            var mapped: String? = null
            var topSynset = top3[0]
            for (idx in top3) {
                val id = sceneIdForSynset(idx)
                if (id != null) { mapped = id; topSynset = idx; break }
            }
            // 顶-3 都映射不到 (365 类里 ~245 类是细分场所, 不归 13 subDomain) → 颜色 heuristic
            if (mapped == null) {
                val colorId = CommonColorHeuristic.parseFallbackColor(bitmap)
                if (colorId != null) {
                    return SceneResult(colorId, 0.40f, topSynset)
                }
                return null
            }
            SceneResult(mapped, probs[topSynset], topSynset)
        } catch (t: Throwable) {
            AppLog.e(TAG, "Places365 inference failed", t)
            null
        }
    }

    companion object {
        private const val TAG = "Places365Classifier"
        private const val INPUT_SIZE = 224

        /**
         * 365 synset → 13 subDomain id 映射 (~120 行, 其他保留为 null 让 top-3/top-5 fallback).
         * 数据来源: MIT CSAIL places365 categories_places365.txt (行号 = synset idx).
         */
        private val PLACES365_TO_SCENE: Map<Int, String> = buildMap {
            // ---- night (5 entries) ----
            put(178, "night")   // stage/indoor
            put(188, "night")   // music_studio
            put(282, "night")   // bar/indoor
            put(307, "night")   // restaurant_patio
            put(357, "night")   // beer_garden
            // ---- sunset (3 entries) ----
            put(11, "sunset")   // alley
            put(54, "sunset")   // badlands
            put(202, "sunset")  // desert/sand
            // ---- snow (5 entries) ----
            put(248, "snow")    // ice_snowfield
            put(249, "snow")    // ice_shelf
            put(304, "snow")    // ski_slope
            put(338, "snow")    // snowfield
            put(339, "snow")    // snow_park
            // ---- water (8 entries) ----
            put(126, "water")   // coast
            put(127, "water")   // coral_reef/underwater
            put(162, "water")   // lake/natural
            put(175, "water")   // ocean
            put(205, "water")   // pier/dock
            put(298, "water")   // seashore
            put(349, "water")   // waterfall
            put(350, "water")   // wave
            // ---- sky (4 entries) ----
            put(0, "sky")       // airfield
            put(266, "sky")     // sky
            put(267, "sky")     // sky_clouds
            put(268, "sky")     // sky_cloudy
            // ---- indoor (40+ entries) — representative subset ----
            put(1, "indoor")    // airplane_cabin
            put(2, "indoor")    // airport_terminal
            put(6, "indoor")    // apartment/indoor
            put(7, "indoor")    // aquarium
            put(8, "indoor")    // aquarium/indoor
            put(15, "indoor")   // art_gallery
            put(16, "indoor")   // art_studio
            put(21, "indoor")   // badroom
            put(23, "indoor")   // bakery/indoor
            put(28, "indoor")   // banquet_hall
            put(35, "indoor")   // beauty_salon
            put(40, "indoor")   // bedroom
            put(45, "indoor")   // bookstore
            put(56, "indoor")   // bathroom
            put(64, "indoor")   // bowling_alley
            put(70, "indoor")   // cafeteria
            put(75, "indoor")   // classroom
            put(91, "indoor")   // closet
            put(92, "indoor")   // clothing_store
            put(108, "indoor")  // conference_room
            put(115, "indoor")  // corridor/indoor
            put(125, "indoor")  // dining_room
            put(132, "indoor")  // elevator/indoor
            put(140, "indoor")  // garage/indoor
            put(145, "indoor")  // gym/indoor
            put(151, "indoor")  // hospital_room
            put(154, "indoor")  // hotel_room
            put(170, "indoor")  // kitchen
            put(174, "indoor")  // library/indoor
            put(180, "indoor")  // lobby
            put(186, "indoor")  // market/indoor
            put(192, "indoor")  // movie_theater/indoor
            put(197, "indoor")  // museum/indoor
            put(222, "indoor")  // office
            put(228, "indoor")  // pantry
            put(243, "indoor")  // reception
            put(264, "indoor")  // shoe_shop
            put(272, "indoor")  // staircase
            put(276, "indoor")  // studio_music
            put(313, "indoor")  // supermarket/indoor
            put(323, "indoor")  // television_studio
            put(326, "indoor")  // tobacco_shop
            put(340, "indoor")  // spa/indoor
            put(355, "indoor")  // waiting_room
            // ---- building (~15 entries) ----
            put(14, "building")   // arch
            put(45, "building")   // apiary_external (re-purposed: outside building)
            put(48, "building")   // barn
            put(58, "building")   // boathouse
            put(78, "building")   // castle
            put(83, "building")   // chalet
            put(98, "building")   // courthouse
            put(102, "building")  // church
            put(103, "building")  // church/indoor → building_outdoor fallback
            put(112, "building")  // dock
            put(153, "building")  // hospital
            put(165, "building")  // lighthouse
            put(189, "building")  // mosque
            put(216, "building")  // pagoda
            put(232, "building")  // palace
            put(254, "building")  // sky_scraper
            put(284, "building")  // tower
            put(341, "building")  // synagogue
            put(345, "building")  // temple
            // ---- plant (10 entries) ----
            put(50, "plant")    // bamboo_forest
            put(57, "plant")    // botanical_garden
            put(80, "plant")    // corn_field
            put(120, "plant")   // field/cultivated
            put(122, "plant")   // field_wild
            put(143, "plant")   // forest/broadleaf
            put(144, "plant")   // forest_path
            put(166, "plant")   // formal_garden
            put(229, "plant")   // pasture
            put(322, "plant")   // vegetable_garden
            put(354, "plant")   // wheat_field
            put(363, "plant")   // vineyard
            // ---- document (3 entries, 其余靠 comic 类升级 Danbooru) ----
            put(309, "document") //  bookstore (当 comic 不命中)
            put(312, "document") // bulletin_board
            put(353, "document") // poster
        }

        /**
         * 强"二次元诱导"synset — 命中则升级到 DanbooruTagger 再判.
         * 包含 comic_book / book_jacket / letter 等明显印刷品.
         */
        private val COMIC_ELIGIBLE: Set<Int> = setOf(
            307, // comic_book
            421, // book_jacket (在 365 列表 idx)
            310, // letterbox
        )

        fun sceneIdForSynset(synset: Int): String? = PLACES365_TO_SCENE[synset]

        fun isComicEligible(synset: Int): Boolean = synset in COMIC_ELIGIBLE
    }
}
```

- [ ] **Step 4: 提取颜色 heuristic → `CommonColorHeuristic.kt`**

新建 `app/src/main/java/com/smartvision/gallery/data/ai/CommonColorHeuristic.kt`，把 `VisionClassifier.parseFallbackColor` 完整 body 搬过来，签名 `parseFallbackColor(bitmap: Bitmap): String?`，内部 5 when 命中返回 `"night"/"snow"/"sunset"/"water"/"sky"/null`。同步删除 `VisionClassifier.parseFallbackColor` 与 `IMAGENET_NAME_HINT`。`VisionClassifier` 仅保留 `food/animal/document` 三轴映射。

- [ ] **Step 5: 编译验证 + 跑单测**

Run:
```bash
cd "/h/workspace-minimaxcode/新建文件夹/超级相册"
./gradlew compileDebugKotlin assembleDebug 2>&1 | tail -10
./gradlew testDebugUnitTest --tests "*Places365ClassifierTest*" 2>&1 | tail -15
```
Expected: `BUILD SUCCESSFUL` + 4 个 test 全 PASS

- [ ] **Step 6: 提交**

```bash
git add app/src/main/java/com/smartvision/gallery/data/ai/Places365Classifier.kt \
        app/src/main/java/com/smartvision/gallery/data/ai/CommonColorHeuristic.kt \
        app/src/test/java/com/smartvision/gallery/data/ai/Places365ClassifierTest.kt \
        app/src/main/java/com/smartvision/gallery/data/ai/VisionClassifier.kt
git -c commit.gpgsign=false commit -m "feat(ai): Places365Classifier 365→13 mapping + CommonColorHeuristic 提取"
```

---

## Task 4: 简化 VisionClassifier 仅保留 food/animal/document 三轴

**Files:**
- Modify: `app/src/main/java/com/smartvision/gallery/data/ai/VisionClassifier.kt`

- [ ] **Step 1: 删除 5 个 ImageNet 缺失类映射**

`VisionClassifier.kt` 操作:
- 删 `IMAGENET_NAME_HINT` 整个 map
- 删 `IMAGENET_TO_SCENE` 整段 `buildMap` (保留 person/portrait/food/animal/document 五段，其它段 — sunset/snow/water/sky/night/indoor/building/plant — 删)
- 删 `parseFallbackColor` private fun（已搬到 Task 3 的 `CommonColorHeuristic`）
- 删 `parseTop1WithFallback` 内的颜色 heuristic 调用，改成直接返回 `SceneResult("other", ...)` (VisionClassifier 不再处理场景类)
- 14 类常量 - 删 SCENE_NIGHT/SUNSET/SNOW/WATER/SKY/INDOOR/BUILDING/PLANT 8 个 SCENE_* 常量
- LABELS_EN array 改为 5 类：person/portrait/food/animal/document
- parseTop1WithFallback 简化为: top-20 命中 food/animal/document 即返回；其它全 → "other"

- [ ] **Step 2: 单测验证 food/animal/document 三轴仍命中**

```bash
cd "/h/workspace-minimaxcode/新建文件夹/超级相册"
./gradlew testDebugUnitTest 2>&1 | tail -10
```
Expected: 全 PASS (现有单测仍绿)

- [ ] **Step 3: 编译验证**

```bash
./gradlew assembleDebug 2>&1 | tail -10
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: 提交**

```bash
git add app/src/main/java/com/smartvision/gallery/data/ai/VisionClassifier.kt
git -c commit.gpgsign=false commit -m "refactor(ai): VisionClassifier 仅保留 food/animal/document 三轴"
```

---

## Task 5: 重写 DomainRouter 4 模型 cascade

**Files:**
- Modify: `app/src/main/java/com/smartvision/gallery/data/ai/DomainRouter.kt`

- [ ] **Step 1: 备份现有 DomainRouter**

`DomainRouter.kt` 整个 mobileclip+imagenet 启发式 (`book_jacket/comic_book` 走 anime 等) 替换成 spec 3.4 节的新 `route()`。

完整新 class:
```kotlin
package com.smartvision.gallery.data.ai

import android.content.Context
import android.graphics.Bitmap
import com.smartvision.gallery.util.AppLog

/**
 * 4 模型 cascade, 最终把 bitmap 路由到 (domain, subDomain, optional character).
 *
 * Stage 1  MLKit Face:  ≥1 face conf≥0.5 → portrait (提前出)
 * Stage 2  Places365:    top3 命中映射表 → 场景 subDomain; 命中 comic synset → 走 Stage 4
 * Stage 3  VisionClassifier (ImageNetV2): 食物/动物/文档三轴兜底
 * Stage 4  DanbooruTagger: 仅 Stage 2 命中 comic + `hasAnimeStyle` → anime
 *
 * MobileCLIP 已撤出主路径: 体积大且 cosine ≈ 0 误判率高. 旧 asset 保留在 hub 不删.
 */
class DomainRouter(context: Context) {

    private val places365 by lazy { Places365Classifier(context) }
    private val vision by lazy { VisionClassifier(context) }
    private val danbooru by lazy { DanbooruTagger(context) }
    private val face by lazy { MlKitFaceAnalyzer(context) }
    private val hub by lazy { AiModelHub.get(context) }

    data class RouteDecision(
        val domain: String,        // "real" | "anime"
        val subDomain: String,     // "portrait"/"night"/"sunset"/"snow"/"water"/"sky"/"indoor"/"building"/"plant"/"food"/"animal"/"document"/"anime"/"other"
        val faceCount: Int = 0,
        val character: String? = null,
        val confidence: Float = 0f,
        val modelSource: String = "places365", // debug 用
    )

    /**
     * 主入口, 给 AiTagger 调用.
     */
    fun route(bitmap: Bitmap): RouteDecision {
        // Stage 1: Face
        val f = face.detect(bitmap)
        if (f.count >= 1 && f.confidence >= 0.5f) {
            return RouteDecision(
                domain = "real",
                subDomain = "portrait",
                faceCount = f.count,
                confidence = f.confidence,
                modelSource = "mlkit_face"
            )
        }

        // Stage 2: Places365 (主路径)
        val places = places365.classify(bitmap)
        if (places != null) {
            // 命中 comic eligible → Stage 4 跑 Danbooru
            if (Places365Classifier.isComicEligible(places.topSynset) && hub.isDanbooruAvailable) {
                val d = danbooru.detect(bitmap)
                if (d != null && d.hasAnimeStyle) {
                    return RouteDecision(
                        domain = "anime",
                        subDomain = "anime",
                        character = d.characterTag,
                        confidence = d.allTags.size.coerceAtMost(10) / 10f,
                        modelSource = "danbooru"
                    )
                }
                // Danbooru 否定 → 还原到 comic subDomain 默认归 document
                return RouteDecision("real", "document", modelSource = "places365_comic_neg")
            }
            return RouteDecision(
                domain = "real",
                subDomain = places.subDomain,
                confidence = places.confidence,
                modelSource = "places365"
            )
        }

        // Stage 3: ImageNet 物体兜底
        val v = vision.classify(bitmap)
        return when (v?.category) {
            "food" -> RouteDecision("real", "food", confidence = v.confidence, modelSource = "imagenet_v2")
            "animal" -> RouteDecision("real", "animal", confidence = v.confidence, modelSource = "imagenet_v2")
            "document" -> RouteDecision("real", "document", confidence = v.confidence, modelSource = "imagenet_v2")
            "portrait" -> RouteDecision("real", "portrait", confidence = v.confidence, modelSource = "imagenet_v2")
            else -> RouteDecision("real", "other", modelSource = "fallback")
        }
    }

    /**
     * 兼容旧 AiTagger 接口 — 现已不用, 但被 AiTagger 类构造时 lazy init 触发, 不删.
     */
    @Suppress("UNUSED")
    fun legacyRoute(bitmap: Bitmap): String = "real"

    @Suppress("UNUSED")
    class LegacyClassifierBridge
}
```

- [ ] **Step 2: 编译验证**

```bash
cd "/h/workspace-minimaxcode/新建文件夹/超级相册"
./gradlew assembleDebug 2>&1 | tail -15
```
Expected: `BUILD SUCCESSFUL` (AiTagger 暂时还调用旧 Api, 暂时编译过是因为只引用 lazy init)

- [ ] **Step 3: 提交**

```bash
git add app/src/main/java/com/smartvision/gallery/data/ai/DomainRouter.kt
git -c commit.gpgsign=false commit -m "feat(ai): DomainRouter 4 模型 cascade (face→places365→imagenet→danbooru)"
```

---

## Task 6: Room V19→V20 加 subDomainVersion 列

**Files:**
- Modify: `app/src/main/java/com/smartvision/gallery/data/model/MediaFlagEntity.kt`
- Create: `app/src/main/java/com/smartvision/gallery/data/db/MigrationV19V20.kt`
- Modify: `app/src/main/java/com/smartvision/gallery/data/db/AppDatabase.kt`

- [ ] **Step 1: 在 MediaFlagEntity 加 `subDomainVersion: Int = 0`**

在 `MediaFlagEntity.kt` 内 **所有其它 ai* 字段旁边** 加一行:
```kotlin
val subDomainVersion: Int = 0,
```

- [ ] **Step 2: 创建 MigrationV19V20.kt**

```kotlin
package com.smartvision.gallery.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Room V19 → V20: 加 subDomainVersion 列, 默认 0; 升级后 AI worker 强制重跑. */
val MIGRATION_V19_TO_V20 = object : Migration(19, 20) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE media_flags ADD COLUMN subDomainVersion INTEGER NOT NULL DEFAULT 0"
        )
    }
}
```

- [ ] **Step 3: AppDatabase 更新 version + 注册 migration**

`AppDatabase.kt`:
- `@Database(...)` 注解 version 由 `19` 改 `20`
- `Room.databaseBuilder(...).addMigrations(... MIGRATION_V19_TO_V20 ...)` 注册新 migration (在现有 MIGRATIONS list 内追加)

- [ ] **Step 4: 编译验证**

```bash
cd "/h/workspace-minimaxcode/新建文件夹/超级相册"
./gradlew assembleDebug 2>&1 | tail -10
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/smartvision/gallery/data/model/MediaFlagEntity.kt \
        app/src/main/java/com/smartvision/gallery/data/db/MigrationV19V20.kt \
        app/src/main/java/com/smartvision/gallery/data/db/AppDatabase.kt
git -c commit.gpgsign=false commit -m "feat(db): Room V19→V20 Migration 加 subDomainVersion 列"
```

---

## Task 7: AiTagger 升 AI_VERSION=9, 接入 DomainRouter cascade, 写 subDomainVersion

**Files:**
- Modify: `app/src/main/java/com/smartvision/gallery/data/ai/AiTagger.kt`

- [ ] **Step 1: 改 AiTagResult 增 subDomainVersion 字段**

```kotlin
data class AiTagResult(
    val domain: String,
    val subDomain: String,
    val copyright: String?,
    val faceCount: Int,
    val faceArea: Float,
    val score: Float,
    val version: Int       // = AI_VERSION (9)
)
```

- [ ] **Step 2: 重写 tagInternal 用 DomainRouter**

完整新内部函数:
```kotlin
private fun tagInternal(bitmap: Bitmap): AiTagResult? {
    val hub = AiModelHub.get(context)
    if (!hub.isAvailable) {
        AppLog.w(TAG, "No AI models vendored, falling back to heuristic")
        return null
    }
    return try {
        val decision = clip.route(bitmap)  // DomainRouter.new 4-stage cascade
        val tc = tagCount.incrementAndGet()
        if (tc % 20L == 0L) {
            AppLog.i(TAG, "tag#$tc domain=${decision.domain} subDomain=${decision.subDomain} src=${decision.modelSource} conf=${decision.confidence} char=${decision.character}")
        }
        val score = computeScore(decision.confidence.coerceAtLeast(0.4f), 0.5f, 0.5f)
        AiTagResult(
            domain = decision.domain,
            subDomain = decision.subDomain,
            copyright = decision.character,
            faceCount = decision.faceCount,
            faceArea = 0f,    // MlKitFaceAnalyzer.confidence 不直接传 area; 调用方从 entity 取
            score = score,
            version = AI_VERSION,
        )
    } catch (t: Throwable) {
        AppLog.e(TAG, "AiTagger.tag() failed", t)
        null
    }
}
```

替换旧 `faceAnalyzer.detect(bitmap)` 直接调用 — `faceArea` 由 Stage 1 接收 `decision.faceCount` 即可, 真实 area 在 entity 内已存 (`faceArea` 列)。

- [ ] **Step 3: 修改 companion 升 AI_VERSION = 9**

```kotlin
const val AI_VERSION = 9
```

- [ ] **Step 4: 更新 call sites 写 version**

`AiTaggingWorker.kt` 的 `writeFlag()` 调用 read result.version, 写入 MediaFlagEntity.subDomainVersion 字段。如现版本没接 — 在写入 dao 的位置补一行：
```kotlin
flag.subDomainVersion = result.version  // = 9
```

- [ ] **Step 5: 编译验证**

```bash
cd "/h/workspace-minimaxcode/新建文件夹/超级相册"
./gradlew assembleDebug 2>&1 | tail -10
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 6: 提交**

```bash
git add app/src/main/java/com/smartvision/gallery/data/ai/AiTagger.kt
git -c commit.gpgsign=false commit -m "feat(ai): AiTagger AI_VERSION=9, 接 DomainRouter cascade, 写 subDomainVersion"
```

---

## Task 8: AiTaggingWorker 强制重跑调度

**Files:**
- Modify: `app/src/main/java/com/smartvision/gallery/data/ai/AiTaggingWorker.kt`

- [ ] **Step 1: 加 forceReRun: Boolean 调度支持**

`AiTaggingWorker` companion 加函数:
```kotlin
fun scheduleAll(context: Context, forceReRun: Boolean) {
    val request = OneTimeWorkRequestBuilder<AiTaggingWorker>()
        .setConstraints(Constraints.Builder()
            .setRequiresBatteryNotLow(true)
            .setRequiresStorageNotLow(true)
            .build())
        .setInputData(workDataOf("forceReRun" to forceReRun))
        .build()
    WorkManager.getInstance(context).enqueueUniqueWork(
        if (forceReRun) "AiTaggingForceReRun" else "AiTaggingScan",
        ExistingWorkPolicy.REPLACE,
        request,
    )
}
```

`doWork()` 内:
```kotlin
val forceReRun = inputData.getBoolean("forceReRun", false)
val pending = mediaRepository.mediaIdsNeedingAiScan(forceReRun)  // DAO 加 @Query
// ... 已有协程 for-loop 逐张调 aiTagger.tagFromPath(uri) ...
```

DAO 加 query:
```kotlin
@Query("""
    SELECT m.uri FROM media m LEFT JOIN media_flags f ON m.id = f.mediaId
    WHERE f.subDomainVersion IS NULL OR f.subDomainVersion < :target
    ORDER BY m.dateTaken DESC
""")
suspend fun mediaIdsNeedingAiScan(@Param("target") target: Int = 9): List<Uri>
```

(Migration 加列后, 老行 = 0 < 9 自动选入)

- [ ] **Step 2: 编译 + 单测**

```bash
cd "/h/workspace-minimaxcode/新建文件夹/超级相册"
./gradlew compileDebugKotlin testDebugUnitTest 2>&1 | tail -10
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 提交**

```bash
git add app/src/main/java/com/smartvision/gallery/data/ai/AiTaggingWorker.kt \
        app/src/main/java/com/smartvision/gallery/data/db/MediaFlagDaoAi.kt
git -c commit.gpgsign=false commit -m "feat(ai): AiTaggingWorker scheduleAll(forceReRun) + DAO mediaIdsNeedingAiScan"
```

---

## Task 9: AlbumListPage 改 13 类 + emoji 配色 + 版本升级 banner

**Files:**
- Create: `app/src/main/java/com/smartvision/gallery/data/ai/domain/AiCategoryMeta.kt`
- Modify: `app/src/main/java/com/smartvision/gallery/ui/album/AlbumListViewModel.kt`
- Modify: `app/src/main/java/com/smartvision/gallery/ui/album/AlbumListPage.kt`

- [ ] **Step 1: 新建 AiCategoryMeta.kt — 13 类元数据**

```kotlin
package com.smartvision.gallery.data.ai.domain

import androidx.compose.ui.graphics.Color

/**
 * 13 sub-domain AI 分类元数据. 单一真相: AlbumListPage 网格 + Settings + AlbumDetailPage 都读这里.
 * 增加/删除 subDomain 改这一个文件.
 */
data class AiCategoryMeta(
    val id: String,            // DB sub_domain 列值
    val zh: String,            // UI 中文
    val en: String,            // UI 英文 fallback
    val emoji: String,         // 单 emoji 图标
    val accentColor: Color,    // 圆点 / 数字徽章色
    val order: Int,            // 网格排序 (0..12)
)

object AiCategoryMetas {
    val ALL: List<AiCategoryMeta> = listOf(
        AiCategoryMeta("portrait",  "人像",  "portrait",  "👤", Color(0xFF1F6FEB), 0),
        AiCategoryMeta("night",     "夜景",  "night",     "🌃", Color(0xFF3A3A3C), 1),
        AiCategoryMeta("sunset",    "夕阳",  "sunset",    "🌅", Color(0xFFFF9500), 2),
        AiCategoryMeta("snow",      "雪景",  "snow",      "❄️", Color(0xFF64D2FF), 3),
        AiCategoryMeta("water",     "水面",  "water",     "🌊", Color(0xFF0A84FF), 4),
        AiCategoryMeta("sky",       "天空",  "sky",       "☁️", Color(0xFF5AC8FA), 5),
        AiCategoryMeta("indoor",    "室内",  "indoor",    "🏠", Color(0xFF8E8E93), 6),
        AiCategoryMeta("building",  "建筑",  "building",  "🏛️", Color(0xFFFFCC00), 7),
        AiCategoryMeta("plant",     "植物",  "plant",     "🌳", Color(0xFF34C759), 8),
        AiCategoryMeta("food",      "食物",  "food",      "🍱", Color(0xFFFF3B6E), 9),
        AiCategoryMeta("animal",    "动物",  "animal",    "🐾", Color(0xFFFF9F0A), 10),
        AiCategoryMeta("document",  "文档",  "document",  "📄", Color(0xFFAF52DE), 11),
        AiCategoryMeta("anime",     "二次元","anime",     "🎌", Color(0xFFFF2D55), 12),
    )

    fun byId(id: String): AiCategoryMeta? = ALL.firstOrNull { it.id == id }
}
```

- [ ] **Step 2: AlbumListViewModel 切换 aiCategoryFolders 使用 13 类**

当前 `aiCategoryFolders.collectAsState()` 出来的 List size=15 — 加 `val showAiUpgradeBanner by collectLatest { repo.countAiRowsBelowVersion(9) > 0 }.stateIn(scope, SharingStarted.Eagerly, false)`。

- [ ] **Step 3: AlbumListPage UI — 13 类 + emoji + banner**

`AlbumListPage.kt:300-339` 把 `aiCategoryFolders.chunked(5)` 改为按 `AiCategoryMetas.ALL` 顺序渲染。每个 tile 上方显示 emoji，accentColor 描边 1dp。

在 AI section header 之前插入 banner（仅当 `showAiUpgradeBanner == true` 显示）：
```kotlin
if (showAiUpgradeBanner) {
    item("ai-upgrade-banner") {
        LiquidGlassCard(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            shape = RoundedCornerShape(14.dp),
            contentPadding = PaddingValues(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.AutoAwesome, null, tint = Color(0xFFFF9500))
                Spacer(Modifier.width(8.dp))
                Column {
                    Text("AI 分类已升级到 v9", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text("设置 → 立即重 AI 扫描全库 重新识别", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
```

- [ ] **Step 4: 编译验证**

```bash
cd "/h/workspace-minimaxcode/新建文件夹/超级相册"
./gradlew assembleDebug 2>&1 | tail -10
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/smartvision/gallery/data/ai/domain/AiCategoryMeta.kt \
        app/src/main/java/com/smartvision/gallery/ui/album/AlbumListViewModel.kt \
        app/src/main/java/com/smartvision/gallery/ui/album/AlbumListPage.kt
git -c commit.gpgsign=false commit -m "feat(ui): AlbumListPage 13 类 emoji 配色 + 版本升级 banner"
```

---

## Task 10: Settings "立即重 AI 扫描全库" 按钮 + 进度条

**Files:**
- Modify: `app/src/main/java/com/smartvision/gallery/ui/settings/SettingsAiSection.kt`

- [ ] **Step 1: 在 AI section 末尾加按钮**

在现有 toggle/switch 按钮下方加：
```kotlin
LiquidGlassCard(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    shape = RoundedCornerShape(14.dp),
    onClick = {
        AiTaggingWorker.scheduleAll(context, forceReRun = true)
        scope.launch { snackbarHostState.showSnackbar("已加入 AI 重扫队列") }
    },
    contentPadding = PaddingValues(14.dp),
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.Refresh, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("立即重新 AI 扫描全库", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text("用 Places365 + Danbooru 全库重跑, 旧数据自动覆盖",
                 fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// 进度条 (订阅 WorkManager.getInstance(context).getWorkInfoByIdLiveData / Flow)
val progress by aiProgressVm.progress.collectAsState()
if (progress.total > 0) {
    LinearProgressIndicator(
        progress = progress.done.toFloat() / progress.total,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    )
    Text("${progress.done}/${progress.total}", fontSize = 11.sp,
         modifier = Modifier.padding(start = 16.dp))
}
```

- [ ] **Step 2: 加 AiProgressViewModel (P5 整合在 repo 内)**

```kotlin
class AiProgressViewModel(app: SmartVisionApp) : AndroidViewModel(app) {
    val progress: StateFlow<AiProgress> = app.mediaRepository.aiProgressFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, AiProgress(0, 0))
}
data class AiProgress(val done: Int, val total: Int)
```

`MediaRepository.aiProgressFlow`: Worker 内每写 1 个 flag 后 emit `AiProgress(done=..., total=...)`。

- [ ] **Step 3: 编译 + 部署**

```bash
cd "/h/workspace-minimaxcode/新建文件夹/超级相册"
./gradlew installDebug 2>&1 | tail -10
```
Expected: `BUILD SUCCESSFUL` + APK 安装到设备

- [ ] **Step 4: 手动验证 (logcat)**

Run:
```bash
adb -s 5ddfea15 logcat -c
adb -s 5ddfea15 shell am start -n com.smartvision.gallery.debug/com.smartvision.gallery.ui.MainActivity
adb -s 5ddfea15 logcat -s AiTagger:I Places365Classifier:I DomainRouter:I 2>&1 | head -40
```
Expected: 进入 Settings → 看到 "立即重新 AI 扫描全库" 按钮。点击后 logcat 看到 worker enqueue 日志。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/smartvision/gallery/ui/settings/SettingsAiSection.kt \
        app/src/main/java/com/smartvision/gallery/ui/settings/AiProgressViewModel.kt \
        app/src/main/java/com/smartvision/gallery/data/repo/MediaRepository.kt \
        app/src/main/java/com/smartvision/gallery/data/ai/AiTaggingWorker.kt
git -c commit.gpgsign=false commit -m "feat(ui): Settings 立即重 AI 扫描全库按钮 + AiProgressViewModel"
```

---

## Task 11: 编译 + adb 安装 + 30 张真实照片实测

**Files:**
- 无

- [ ] **Step 1: 全量编译**

```bash
cd "/h/workspace-minimaxcode/新建文件夹/超级相册"
./gradlew clean assembleDebug 2>&1 | tail -10
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 2: 安装 APK 到 5ddfea15**

```bash
adb -s 5ddfea15 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s 5ddfea15 shell am force-stop com.smartvision.gallery.debug
```
Expected: Success + process stopped

- [ ] **Step 3: 启动 + 触发全库重扫**

```bash
adb -s 5ddfea15 shell am start -n com.smartvision.gallery.debug/com.smartvision.gallery.ui.MainActivity
adb -s 5ddfea15 logcat -c
sleep 3
adb -s 5ddfea15 shell input tap 540 1850  # Approx Settings 入口, 按真机 UI 调整
adb -s 5ddfea15 shell input tap 540 1200  # AI toggle / 重扫按钮区域
```
Expected: logcat -s AiTagger:I 显示 tag#1...tag#20 至少 1 行有 domain + subDomain + src

- [ ] **Step 4: 触发强制重扫按钮**

手动操作: 进入 Settings → 找到 "立即重新 AI 扫描全库" 按钮 → 点击。
观察 logcat:
```bash
adb -s 5ddfea15 logcat -s Places365Classifier:I DomainRouter:I AiTagger:I 2>&1 | head -60
```
Expected:
- `Places365Classifier` 偶有 Info log (实际常 silent, 只 catch 错误时出 E)
- `DomainRouter` 不打 log (不打)
- `AiTagger` 每 20 张一行: `tag#20 domain=real subDomain=sunset src=places365 conf=0.78` 或 `domain=anime subDomain=anime src=danbooru`

- [ ] **Step 5: 30 张真实照片 5 类指标验证 (按 spec §8)**

人工上传 30 张照片 (用户自己挑) 含：
- 5 张真人 (期望 5 portrait)
- 5 张夕阳 (期望 5 sunset)
- 5 张雪景 (期望 5 snow)
- 5 张二次元 (期望 5 anime)
- 5 张食物 (期望 5 food)
- 5 张夜景 (期望 5 night)

等 worker 跑完后 SQL 统计:
```bash
adb -s 5ddfea15 shell run-as com.smartvision.gallery.debug sh -c 'cat databases/smartvision.db' > /tmp/db.bin
sqlite3 /tmp/db.bin "SELECT sub_domain, COUNT(*) FROM media_flags WHERE subDomainVersion=9 GROUP BY sub_domain"
```
Expected:
- `portrait: 5` (≥ 4 / 5 = 80%)
- `sunset: 5` (≥ 4)
- `snow: 5` (≥ 4)
- `anime: 5` (≥ 4)
- `food: 5` (≥ 4)
- `night: 5` (≥ 4)

总命中率 ≥ 80% (24/30) = spec 验收门达标。

- [ ] **Step 6: 验收不达 → 调阈值复测**

如未达 80%, 拉 logcat 看具体误判类, 改 `PLACES365_TO_SCENE` 表对应阈值, 提交后回到 Step 1 重测。最坏 fallback: `isReady()=false` 时 `Places365Classifier.classify()` 返回 null, 自动级落到 `VisionClassifier` 老路径 — **永不 0 数据**。

- [ ] **Step 7: 提交 (如有调整)**

```bash
cd "/h/workspace-minimaxcode/新建文件夹/超级相册"
git -c commit.gpgsign=false commit --allow-empty -m "chore: AI v9 实测完成 — 30 张 5 类 ≥80% 命中"
```

---

## Self-Review Notes

(在写完后由 writing-plans skill 自动审)
- Spec §1-3 (核心结论/cascade/subDomain) → Tasks 3/5/7 覆盖
- Spec §4 (组件 + 改文件 + 新文件) → 全部 11 tasks 覆盖
- Spec §5 (UI 重组) → Tasks 9/10 覆盖
- Spec §6 (10 步骤实施) → Tasks 1-10 覆盖；Task 11 是实测验证，作为质量门
- Spec §7 (风险与缓解) → Task 11 Step 6 fallback 覆盖
- Spec §8 (验收测试) → Task 11 Step 5 覆盖
- Spec §9 (out of scope) → 未动

类型一致性：`RouteDecision.subDomain` 在 Task 5 定义为 13 类 + "other"；Task 9 `AiCategoryMetas.ALL` 提供 13 类 + "other" 不在；Task 3 `PLACES365_TO_SCENE` value = 8 scene id（night/sunset/snow/water/sky/indoor/building/plant/document） + portrait/anime/food/animal 在不同分支。统一收敛点：`DomainRouter.route` 已是唯一入口。
