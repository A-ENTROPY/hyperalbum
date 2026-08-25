package com.smartvision.gallery.ui.components

import android.content.Context
import coil.ImageLoader
import coil.decode.GifDecoder
import coil.decode.VideoFrameDecoder
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.CachePolicy
import com.smartvision.gallery.data.model.MediaItem
import com.smartvision.gallery.lan.smb.SmbFetcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.math.min

/**
 * Centralised [ImageLoader] builder. The image loader is application-scoped; Compose's
 * `AsyncImage` reads it from the LocalImageLoader provided at the root.
 *
 * Decoders registered here apply to ANY `AsyncImage(model = …)`, including
 * the URI-based call sites in [com.smartvision.gallery.ui.pages.TimelinePage]
 * and the curated-card section of [com.smartvision.gallery.ui.album.AlbumListPage].
 * That is why registering [VideoFrameDecoder.Factory] here unblocks video and
 * Live Photo (`.mov`) thumbnails in the photo grid and folder covers — those
 * call sites pass `Uri`, not `MediaItem`, so they fall through to Coil's
 * decoder pipeline rather than [MediaFetcherFactory] (which is typed
 * `Fetcher.Factory<MediaItem>` and only fires for `MediaItem` models).
 */
object AppImageLoaderFactory {

    /**
     * Snapped to the high-end mobile envelope (Snapdragon 8 Gen 1/2/3 = 8 cores
     * total, big.LITTLE). Coil's default decoder dispatcher is
     * `Dispatchers.Default` (parallelism = `max(2, cores - 1)`) which on a
     * 6-core phone caps at 5 parallel decodes — fine for one frame, but a
     * 3-column scrolling grid can fire 6-9 cells simultaneously, and the
     * queued tail shows up as the "thumbnail pops in 200ms late" jank.
     *
     * 8 threads = every P-core busy decoding, no thread-pool wait when 9 cells
     * hit Coil in the same frame. Capped at 16 — past that you saturate the
     * memory bandwidth on a phone SoC and decode actually slows down.
     */
    private val DECODER_POOL_SIZE: Int = min(16, max(8, Runtime.getRuntime().availableProcessors()))

    /**
     * I/O is mostly waiting on binder + MediaStore CursorWindow reads, so a
     * larger pool helps. 16 is the sweet spot — past it the Binder thread pool
     * becomes the bottleneck (the system has a hard cap around 15-31 Binder
     * threads depending on device), and adding more just queues.
     */
    private val FETCHER_POOL_SIZE: Int = 16

    fun create(context: Context): ImageLoader {
        val app = context.applicationContext

        // Persistent executor threads — recreated only when the loader is
        // rebuilt. Using `Executors.newFixedThreadPool` (not coroutine
        // Dispatchers.IO) keeps decode work off the shared IO pool that
        // Room / OkHttp / DataStore also contend for.
        val decoderDispatcher = Executors.newFixedThreadPool(
            DECODER_POOL_SIZE,
            NamedThreadFactory("coil-decode")
        ).asCoroutineDispatcher()
        val fetcherDispatcher = Executors.newFixedThreadPool(
            FETCHER_POOL_SIZE,
            NamedThreadFactory("coil-fetch")
        ).asCoroutineDispatcher()

        return ImageLoader.Builder(app)
            .components {
                // Register BEFORE MediaFetcherFactory so Uri models hit the
                // system thumbnail cache first (see SystemThumbnailFetcherFactory
                // kdoc). MediaFetcherFactory only matches MediaItem models, so
                // the order here is more about clarity than actual precedence —
                // Coil routes by model type, not by registration order. But keeping
                // system-thumb first mirrors the data-flow intent: Uri → system
                // thumb → fallback to Coil's default UriFetcher.
                add(SystemThumbnailFetcherFactory(app))
                add(MediaFetcherFactory(app))
                // Without this, `model = videoUri` resolves via the default
                // `UriFetcher` → `ImageDecoderDecoder`, which fails on
                // `.mp4` / `.mov` (video isn't an image bitmap) — the
                // AsyncImage sits at its placeholder forever. Registering
                // the video decoder makes Coil use MediaMetadataRetriever
                // to extract a keyframe and serves it as the thumbnail.
                add(VideoFrameDecoder.Factory())
                // Without this, GIFs decode via the default `ImageDecoderDecoder`
                // (or fail silently on API < 28), and AsyncImage only ever paints
                // the first frame — the user sees a frozen card. `coil-gif` ships
                // its own GifDecoder that loops animated GIFs frame-by-frame.
                add(GifDecoder.Factory())
                // SMB/CIFS Fetcher for LAN shared folder thumbnails.
                // Model type: SmbResource (jcifs-ng). Registered after system
                // fetchers so local content always takes priority.
                add(SmbFetcher.Factory(app))
            }
            // Custom decoder + fetcher dispatchers — see DECODER_POOL_SIZE /
//            // FETCHER_POOL_SIZE above for sizing rationale. Default Coil
//            // dispatchers undersize for parallel grid-cell decode.
//            // (Coil 2.6.0: `decoderDispatcher()` / `fetcherDispatcher()`
//            // take a `CoroutineDispatcher` — `decoderCoroutineContext`
//            // was the 2.7.0+ name. We override both to dedicated pools.)
            .decoderDispatcher(decoderDispatcher)
            .fetcherDispatcher(fetcherDispatcher)
            .memoryCache {
                // Up to 20% of heap. Coil defaults to 25%, but on a 6-8GB phone
                // that already trades against the decoder pixel-pool budget and
                // the Jetpack Compose render cache. 20% keeps the grid fast
                // without cutting into render memory so aggressively.
                MemoryCache.Builder(app)
                    .maxSizePercent(0.20)
                    .build()
            }
            .diskCache {
                // 250MB at cacheDir/image_cache. Default is 2% of free disk,
                // which on a 128GB phone is ~2.5GB and almost never churns —
                // a gallery with 8k photos feeds more than that during the
                // first few sweeps, and unused disk cache is dead weight.
                // Capping at 250MB keeps cache hot for the active subset
                // (most-recently-viewed) and trims elder entries via LRU.
                DiskCache.Builder()
                    .directory(app.cacheDir.resolve("image_cache"))
                    .maxSizeBytes(250L * 1024 * 1024)
                    .build()
            }
            // Enable both layers for every fetcher — without these flags,
            // Coil reads `NetworkFetcher` only and silently drops the disk
            // step. Both are read+write so the next session starts warm.
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .crossfade(true)
            .respectCacheHeaders(false)
            // NOTE: Coil 2.6.0 has no `respectRepeatCount` knob — the
            // `GifDecoder` registered above always re-frames on each
            // visibility pass when the source is a `.gif`. The 2.7.0
            // builder added that flag to short-circuit animation, but
            // we're on 2.6.0 and don't need the knob.
            .build()
    }
}

/**
 * Names worker threads so they're easy to spot in `adb shell ps -T` and
 * Studio's profiler. Naming has zero perf cost and saves debugging time
 * when the decode pool is busy and we need to know who's holding the
 * frame open.
 */
private class NamedThreadFactory(private val prefix: String) : ThreadFactory {
    private val counter = AtomicInteger(0)
    override fun newThread(r: Runnable): Thread {
        val t = Thread(r, "$prefix-${counter.incrementAndGet()}")
        t.priority = Thread.NORM_PRIORITY - 1  // below UI; decode shouldn't starve input
        t.isDaemon = true                     // don't block JVM exit if pool hangs
        return t
    }
}