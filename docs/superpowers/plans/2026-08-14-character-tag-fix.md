# 角色标签提取实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 从 DeepDanbooru 9176 标签中提取角色标签（`c:xxx`），修复 characterTag 永远 null 的 bug

**Architecture:** 在 `DanbooruTagger.parseResult()` 中，扫描 topTags 取第一个含 `_` 且不在黑名单的标签作为角色标签。提取逻辑单独函数化便于单元测试。bump AI_VERSION 触发全量重打标。

**Tech Stack:** Kotlin, JUnit, Truth, Robolectric

---

### Task 1: 提取 characterTag 逻辑 + 单元测试

**Files:**
- Modify: `app/src/main/java/com/smartvision/gallery/data/ai/DanbooruTagger.kt`
- Create: `app/src/test/java/com/smartvision/gallery/data/ai/DanbooruTaggerTest.kt`

- [ ] **Step 1: 创建测试文件，为 extractCharacterTag 写 RED 测试**

```kotlin
package com.smartvision.gallery.data.ai

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DanbooruTaggerTest {

    @Test
    fun `extractCharacterTag returns first underscore tag not in exclude list`() {
        val tags = listOf(
            DanbooruTagger.TaggedTag("1girl", 0.99f),
            DanbooruTagger.TaggedTag("hatsune_miku", 0.95f),
            DanbooruTagger.TaggedTag("solo", 0.90f),
        )
        val result = DanbooruTagger.extractCharacterTag(tags)
        assertThat(result).isEqualTo("hatsune_miku")
    }

    @Test
    fun `extractCharacterTag skips exclude list tags`() {
        val tags = listOf(
            DanbooruTagger.TaggedTag("thick_eyebrows", 0.98f),
            DanbooruTagger.TaggedTag("rem_(re:zero)", 0.85f),
        )
        val result = DanbooruTagger.extractCharacterTag(tags)
        assertThat(result).isEqualTo("rem_(re:zero)")
    }

    @Test
    fun `extractCharacterTag returns null when no underscore tag found`() {
        val tags = listOf(
            DanbooruTagger.TaggedTag("1girl", 0.99f),
            DanbooruTagger.TaggedTag("solo", 0.90f),
            DanbooruTagger.TaggedTag("blush", 0.80f),
        )
        val result = DanbooruTagger.extractCharacterTag(tags)
        assertThat(result).isNull()
    }

    @Test
    fun `extractCharacterTag returns null when only exclude list tags match`() {
        val tags = listOf(
            DanbooruTagger.TaggedTag("thick_eyebrows", 0.95f),
            DanbooruTagger.TaggedTag("animal_ear_fluff", 0.90f),
            DanbooruTagger.TaggedTag("swept_bangs", 0.85f),
        )
        val result = DanbooruTagger.extractCharacterTag(tags)
        assertThat(result).isNull()
    }

    @Test
    fun `extractCharacterTag returns null on empty list`() {
        val result = DanbooruTagger.extractCharacterTag(emptyList())
        assertThat(result).isNull()
    }

    @Test
    fun `extractCharacterTag handles parenthesized character names`() {
        val tags = listOf(
            DanbooruTagger.TaggedTag("rem_(re:zero)", 0.95f),
        )
        val result = DanbooruTagger.extractCharacterTag(tags)
        assertThat(result).isEqualTo("rem_(re:zero)")
    }
}
```

- [ ] **Step 2: 运行测试 — 应该编译失败**

Run: `cd "H:\workspace-minimaxcode\hyperalbum" && ./gradlew :app:testDebugUnitTest --tests "com.smartvision.gallery.data.ai.DanbooruTaggerTest" 2>&1 | tail -30`
Expected: `error: unresolved reference: extractCharacterTag` — 函数尚未定义

- [ ] **Step 3: 在 DanbooruTagger 中添加 `CHARACTER_EXCLUDE` 集合 + `extractCharacterTag` 函数**

在 `DanbooruTagger` companion object 尾部（第 307 行之后）添加：

```kotlin
// 含 `_` 但不是角色标签的通用复合标签 — 用于角色标签提取时的黑名单过滤
val CHARACTER_EXCLUDE: Set<String> = setOf(
    "thick_eyebrows", "animal_ear_fluff", "swept_bangs", "flexible_arms",
    "out_of_frame", "upper_body", "lower_body", "full_body", "profile_picture",
    "digital_media", "polished_media", "letterboxed", "wide_shot", "close_up",
    "greyscale", "monochrome", "from_side", "from_behind", "from_above", "from_below",
    "looking_at_viewer", "looking_away", "looking_down", "looking_up",
    "extra_ears", "extra_limbs", "extra_eyes", "extra_wings", "extra_tail",
    "no_humans", "only_watermark", "multiple_girls", "multiple_boys",
    "wide_image", "tall_image", "bad_image", "bad_anatomy", "bad_hands",
    "facing_viewer", "facing_away", "on_side", "on_back", "on_stomach",
    "on_ground", "on_table", "on_bed", "on_floor", "on_grass", "on_water",
    "on_rock", "on_wood", "on_paper",
)
```

在 `companion object` 之后（第 308 行之后，类体内）添加：

```kotlin
/**
 * 从 topTags 中提取角色标签.
 * 规则: 取第一个含 `_` 且不在 [CHARACTER_EXCLUDE] 黑名单中的标签.
 * DeepDanbooru 9176 标签无 category 列, 角色标签通过命名模式识别
 * (如 hatsune_miku, rem_(re:zero)), 但通用标签也可能含 `_`.
 */
fun extractCharacterTag(topTags: List<TaggedTag>): String? {
    for (tag in topTags) {
        if (tag.name.contains('_') && tag.name !in CHARACTER_EXCLUDE) {
            return tag.name
        }
    }
    return null
}
```

- [ ] **Step 4: 替换 parseResult 中 characterTag 的硬编码 null**

替换第 231-237 行：

```kotlin
        // 2) Character tag — 从 topTags 中提取
        var characterTag: String? = null
        var bestCharScore = 0f
        // DeepDanbooru 没有 category 字段, character 标签通过命名约定识别
        // 常见: hatsune_miku, rem_(re:zero), 但不易静态列出
        // 简单方案: 跳过 — character 识别留给 UI 层
```

改为：

```kotlin
        // 2) Character tag — 从 topTags 中提取 (需先构建 topTags)
```

在 `topTags` 构建完成之后（第 247 行之后）、`animeScore` 计算之前（第 252 行之前），添加：

```kotlin
        val characterTag = extractCharacterTag(topTags)
```

- [ ] **Step 5: 运行测试 — 应该全部通过**

Run: `cd "H:\workspace-minimaxcode\hyperalbum" && ./gradlew :app:testDebugUnitTest --tests "com.smartvision.gallery.data.ai.DanbooruTaggerTest" 2>&1 | tail -20`
Expected: `BUILD SUCCESSFUL` 且所有 6 个测试 PASS

- [ ] **Step 6: 验证 DanbooruTagger 现有测试仍通过**

Run: `cd "H:\workspace-minimaxcode\hyperalbum" && ./gradlew :app:testDebugUnitTest 2>&1 | tail -20`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 7: Commit**

```bash
cd "H:\workspace-minimaxcode\hyperalbum"
git add app/src/main/java/com/smartvision/gallery/data/ai/DanbooruTagger.kt
git add app/src/test/java/com/smartvision/gallery/data/ai/DanbooruTaggerTest.kt
git commit -m "feat: extract characterTag from DeepDanbooru topTags

角色标签提取规则: 扫描 topTags (已 sigmoid 降序), 取第一个含 '_'
且不在 CHARACTER_EXCLUDE 黑名单中的标签作为 characterTag.
修复 parseResult 中 characterTag 永远 null 的 bug.

CHARACTER_EXCLUDE 包含 ~50 个含 '_' 的通用复合标签
(thick_eyebrows, animal_ear_fluff, 等), 避免误将通用标签
识别为角色名。

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 2: Bump AI_VERSION 44 → 45

**Files:**
- Modify: `app/src/main/java/com/smartvision/gallery/data/ai/AiTagger.kt`

- [ ] **Step 1: 修改 AI_VERSION 常量**

将第 418 行：
```kotlin
        const val AI_VERSION = 44
```
改为：
```kotlin
        const val AI_VERSION = 45
```

- [ ] **Step 2: 验证编译**

Run: `cd "H:\workspace-minimaxcode\hyperalbum" && ./gradlew :app:compileDebugKotlin 2>&1 | tail -10`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
cd "H:\workspace-minimaxcode\hyperalbum"
git add app/src/main/java/com/smartvision/gallery/data/ai/AiTagger.kt
git commit -m "feat: bump AI_VERSION 44->45 for character tag retagging

触发全量重打标, 让 AiTaggingWorker 重新处理所有照片,
写入 c:xxx 角色标签, 填充 MediaItem.aiDanbooruTags 中的角色信息.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 3: 标记 HeuristicClassifier 废弃

**Files:**
- Modify: `app/src/main/java/com/smartvision/gallery/ai/HeuristicClassifier.kt`

- [ ] **Step 1: 添加 @Deprecated 注解**

在 class 声明前（第 32 行）添加：

```kotlin
@Deprecated("AiTagger + AiModelHub 已替代所有功能 — 真实模型 (MobileCLIP + DeepDanbooru ONNX + Places365 + MLKit) 在生产中运行")
```

- [ ] **Step 2: 验证编译**

Run: `cd "H:\workspace-minimaxcode\hyperalbum" && ./gradlew :app:compileDebugKotlin 2>&1 | tail -10`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
cd "H:\workspace-minimaxcode\hyperalbum"
git add app/src/main/java/com/smartvision/gallery/ai/HeuristicClassifier.kt
git commit -m "chore: mark HeuristicClassifier as deprecated

AiTagger + AiModelHub (MobileCLIP, DeepDanbooru ONNX, Places365,
MLKit) 已完全替代启发式分类器。HeuristicClassifier 注册到
AiServiceLocator 但零消费者，保留代码不删除。

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### 验证

- [ ] **全量单元测试**

```bash
cd "H:\workspace-minimaxcode\hyperalbum"
./gradlew :app:testDebugUnitTest
```

Expected: `BUILD SUCCESSFUL`，所有现有测试 + 6 个新测试通过

- [ ] **连接设备验证（可选）**

```bash
# 安装 debug APK 到连接设备
cd "H:\workspace-minimaxcode\hyperalbum"
./gradlew :app:installDebug

# 查看日志确认 characterTag 出现
adb logcat -c && adb logcat | grep DanbooruTagger
```

Expected: 日志中出现 `char=hatsune_miku` 等角色名（而非 `char=null`）