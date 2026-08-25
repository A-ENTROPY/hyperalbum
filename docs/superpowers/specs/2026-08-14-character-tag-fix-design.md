# 二次元角色标签提取 + 修复设计

## 现状

### AI 分类管道真实状态

**设置页 AI 分类使用的是真模型，不是启发式。**

| 组件 | 类型 | 状态 |
|------|------|------|
| `AiTagger` | 真实模型级联 (MobileCLIP + Places365 + MLKit + ImageNet) | ✅ 生产运行，写入 `MediaItem.aiDomain/aiSubDomain` |
| `DanbooruTagger` | DeepDanbooru v3 ONNX (9176 标签) | ✅ 生产运行，写入 `MediaItem.aiDanbooruTags` |
| `AiTaggingWorker` | WorkManager 调度 | ✅ 正常运行，设置页进度条已正确连接 |
| `HeuristicClassifier` | 纯启发式规则（颜色/边缘/亮度） | 🟡 注册到 `AiServiceLocator`，但零消费者 |
| `characterTag` | Danbooru 角色标签 | ❌ 永远 `null`（代码中硬编码跳过） |

### 角色分类链（当前断点）

```
DanbooruTagger.detect() → parseResult() → characterTag = null  ← 断点
                                                      ↓
AiTagger.tag() → serializeDanbooruTags() → 无 c:xxx 条目
                                                      ↓
MediaItem.aiDanbooruTags → 无角色信息
                                                      ↓
AlbumListViewModel.extractCharacterTagsFromJson() → 空结果
                                                      ↓
animeCategoryFolders 角色桶 → 永远为空
```

下游 `queryAnimeByCharacter`、`extractCharacterTagsFromJson`、角色桶 UI 均已实现，只需上游补上 `characterTag`。

## 改动

### 1. DanbooruTagger.characterTag 提取

**位置：** `DanbooruTagger.kt:parseResult()`，约 232-236 行

**规则：** DeepDanbooru 标签没有 category 列（9176 标签混合 general/character/rating/meta）。角色标签通过命名模式识别：

- 角色标签含 `_`（如 `hatsune_miku`、`rem_(re:zero)`）
- 通用标签也可能含 `_`（如 `thick_eyebrows`、`animal_ear_fluff`），需排除

**实现：** 扫描 `topTags`（已按 sigmoid 降序），取第一个含 `_` 且不在 `CHARACTER_EXCLUDE` 黑名单中的标签作为 `characterTag`。

**`CHARACTER_EXCLUDE` 黑名单（~50 个通用复合标签）：**

```
thick_eyebrows, animal_ear_fluff, swept_bangs, flexible_arms,
out_of_frame, upper_body, lower_body, full_body, profile_picture,
digital_media, polished_media, letterboxed, wide_shot, close_up,
greyscale, monochrome, from_side, from_behind, from_above, from_below,
looking_at_viewer, looking_away, looking_down, looking_up,
extra_ears, extra_limbs, extra_eyes, extra_wings, extra_tail,
no_humans, only_watermark, multiple_girls, multiple_boys,
wide_image, tall_image, bad_image, bad_anatomy, bad_hands,
facing_viewer, facing_away, on_side, on_back, on_stomach,
on_ground, on_table, on_bed, on_floor, on_grass, on_water,
on_rock, on_wood, on_paper
```

### 2. Bump AI_VERSION 44 → 45

触发全量重打标，让 `AiTaggingWorker` 重新处理所有照片，写入 `c:xxx` 角色标签。

### 3. 标记 HeuristicClassifier 废弃

加 `@Deprecated("AiTagger + AiModelHub 已替代所有功能")` 注释，不删除代码。

## 不涉及改动

- 设置页 UI（已正确工作）
- 二次元栏目 UI（`animeCategoryFolders` 已消费角色桶）
- 角色子页（`queryAnimeByCharacter` 已实现）
- `AlbumDetailPage` 角色路由（已处理 `ai:anime:角色:*` 模式）
- 新模型 / 新依赖（零添加）

## 影响范围

- 修改：`DanbooruTagger.kt`（~20 行）
- 修改：`AiTagger.kt`（1 行，AI_VERSION bump）
- 标记：`HeuristicClassifier.kt`（1 行，@Deprecated）

## 回滚

rollback `AI_VERSION` 44 + 清空 `aiDanbooruTags` 列即可恢复旧状态。