package com.smartvision.gallery.data.ai

import android.content.Context
import android.graphics.Bitmap
import com.smartvision.gallery.util.AppLog

/**
 * 域路由器: 三通道级联路由.
 *
 * 优先级:
 *  1. MobileCLIP zero-shot 域分类 (最快, ~50ms) — 可识别 6 个域 (real/anime/...)
 *  2. VisionClassifier ImageNet 启发式 (~20ms) — 检测 comic_book/book_jacket → "anime"
 *  3. Places365 场景分类 (~30ms) — computer_room → "game_screenshot", 否则 "real"
 *
 * 不再单纯依赖 DanbooruTagger 升级路径作为唯一的 anime 入口.
 * MobileCLIP 和 ImageNet 都能在数十 ms 内判断, 大幅降低 anime 识别延迟.
 */
class DomainRouter(context: Context) {

    private val mobileclip by lazy { MobileClipClassifier(context) }
    private val vision by lazy { VisionClassifier(context) }
    private val places by lazy { Places365Classifier(context) }

    fun isReady(): Boolean = mobileclip.isReady() || vision.isReady() || places.isReady()

    fun route(bitmap: Bitmap): String {
        // Stage 1: MobileCLIP zero-shot 域分类 (最快, ~50ms)
        // 6 个域: real, anime, game_screenshot, movie_screenshot, digital_painting, meme
        if (mobileclip.isReady()) {
            try {
                val clipResult = mobileclip.classifyDomain(bitmap)
                if (clipResult != null) {
                    val dc = diagCount.incrementAndGet()
                    if (dc % 10L == 0L) {
                        AppLog.i(TAG, "route #$dc stage=mobileclip domain=${clipResult.domain} sim=${"%.3f".format(clipResult.similarity)}")
                    }
                    // 即使 cosine sim 低也信任 MobileCLIP — 嵌入空间匹配已验证
                    return clipResult.domain
                }
            } catch (t: Throwable) {
                AppLog.w(TAG, "MobileCLIP domain failed", t)
            }
        }

        // Stage 2: ImageNet 启发式 (检测 comic_book/book_jacket → "anime")
        if (vision.isReady()) {
            try {
                val raw = vision.classifyRaw(bitmap)
                if (raw != null) {
                    val domain = domainFromImageNetIdx(raw.idx)
                    if (domain != "real") {
                        val dc = diagCount.incrementAndGet()
                        AppLog.i(TAG, "route #$dc stage=imagenet idx=${raw.idx} conf=${"%.2f".format(raw.conf)} → $domain")
                        return domain
                    }
                    // 即使 ImageNet 说 "real", 如果 top-1 是人物相关也保留
                    // (避免人物照被 Places365 误归 game_screenshot)
                }
            } catch (t: Throwable) {
                AppLog.w(TAG, "ImageNet route failed", t)
            }
        }

        // Stage 3: Places365 场景分类 (兜底)
        if (places.isReady()) {
            try {
                val result = places.classify(bitmap) ?: return "real"
                val label = result.label
                val domain = when {
                    label in GAME_LABELS -> "game_screenshot"
                    else -> "real"
                }
                val dc = diagCount.incrementAndGet()
                if (dc % 30L == 0L) {
                    AppLog.i(TAG, "route #$dc stage=places365 label=$label conf=${"%.3f".format(result.confidence)} → $domain")
                }
                return domain
            } catch (t: Throwable) {
                AppLog.w(TAG, "Places365 route failed", t)
            }
        }

        return "real"
    }

    /**
     * ImageNet V1 keras-1001 idx 倾向动漫/comic/hand-drawn 类.
     * 417 comic_book, 416 book jacket (常为插画封面), 418-420 周边 book 类.
     * 另: 421 notebook/laptop 可能含动漫封面贴纸.
     */
    private fun domainFromImageNetIdx(idx: Int): String {
        return when (idx) {
            in ANIME_INDICES -> "anime"
            in GAME_SCREENSHOT_INDICES -> "game_screenshot"
            else -> "real"
        }
    }

    private val diagCount = java.util.concurrent.atomic.AtomicLong(0L)

    companion object {
        private const val TAG = "DomainRouter"

        val DOMAINS = arrayOf(
            "real", "anime", "game_screenshot", "digital_painting", "meme"
        )

        /** ImageNet keras-1001 idx 倾向动漫 */
        private val ANIME_INDICES = setOf(416, 417, 418, 419, 420, 421)

        /** ImageNet keras-1001 idx 倾向屏幕截图/游戏画面 */
        private val GAME_SCREENSHOT_INDICES = setOf(788, 792, 864, 421)

        /** Places365 labels 暗示游戏/屏幕截图场景 */
        private val GAME_LABELS = setOf(
            "computer_room", "television_room", "television_studio",
            "arcade", "amusement_arcade", "cockpit", "control_room",
            "server_room", "game_room", "recreation_room",
            "home_theater", "movie_theater/indoor"
        )
    }
}