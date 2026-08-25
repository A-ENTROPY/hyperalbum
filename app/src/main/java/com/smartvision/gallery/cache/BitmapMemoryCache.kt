package com.smartvision.gallery.cache

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import com.smartvision.gallery.util.AppLog
import java.util.concurrent.atomic.AtomicLong

/**
 * Single in-memory LRU for decoded thumbnails and full bitmaps. Backed by [LruCache] with
 * a size budget derived from the device's heap.
 *
 *  * Key = (uri, targetW, targetH) — different rendering sizes coexist without thrash.
 *  * Value = [Bitmap]; on eviction the bitmap is recycled via [BitmapPool] if compatible.
 */
class BitmapMemoryCache {

    private val keyer = Keyer()

    private val cache: LruCache<Key, Bitmap> = object : LruCache<Key, Bitmap>(DEFAULT_CACHE_BYTES) {
        override fun sizeOf(key: Key, value: Bitmap): Int = value.allocationByteCount
        override fun entryRemoved(evicted: Boolean, key: Key, oldValue: Bitmap, newValue: Bitmap?) {
            if (evicted && oldValue != null && !oldValue.isRecycled) {
                BitmapPool.recycleIfCompatible(oldValue)
            }
        }
    }

    fun get(uri: android.net.Uri, w: Int, h: Int): Bitmap? {
        return cache[keyer.of(uri, w, h)]
    }

    fun put(uri: android.net.Uri, w: Int, h: Int, bitmap: Bitmap) {
        cache.put(keyer.of(uri, w, h), bitmap)
    }

    fun clear() {
        cache.evictAll()
    }

    fun trimToSize(targetBytes: Int) {
        cache.resize(targetBytes.coerceAtLeast(1024 * 1024))
    }

    /** Composite key — toString so we can leverage LruCache's hashCode/equals on strings. */
    private class Key(val s: String)
    private class Keyer {
        fun of(uri: android.net.Uri, w: Int, h: Int) = Key("${uri}@${w}x$h")
    }

    companion object {
        // Default to ~25% of the app's heap budget for the bitmap cache. Generous on
        // low-RAM devices thanks to BitmapPool reuse.
        private val DEFAULT_CACHE_BYTES = run {
            val maxHeap = Runtime.getRuntime().maxMemory()
            (maxHeap / 4).coerceAtLeast(8L * 1024 * 1024).toInt()
        }
    }
}

/**
 * Bitmap reuse pool. Avoids the cost of allocating a fresh [Bitmap] for every decode
 * when the requested size and config match an existing recycled bitmap.
 */
object BitmapPool {

    private val pool = ArrayDeque<Bitmap>()
    private val maxPoolBytes = AtomicLong(32L * 1024 * 1024)
    private var currentPoolBytes = AtomicLong(0L)

    @Synchronized
    fun obtain(width: Int, height: Int, config: Bitmap.Config): Bitmap {
        val it = pool.iterator()
        while (it.hasNext()) {
            val bmp = it.next()
            if (!bmp.isRecycled && bmp.width == width && bmp.height == height && bmp.config == config) {
                it.remove()
                currentPoolBytes.addAndGet(-bmp.allocationByteCount.toLong())
                return bmp
            }
        }
        return Bitmap.createBitmap(width, height, config)
    }

    @Synchronized
    fun recycleIfCompatible(bitmap: Bitmap) {
        if (bitmap.isRecycled) return
        val bytes = bitmap.allocationByteCount.toLong()
        if (currentPoolBytes.get() + bytes > maxPoolBytes.get()) {
            bitmap.recycle()
            return
        }
        pool.addLast(bitmap)
        currentPoolBytes.addAndGet(bytes)
    }

    @Synchronized
    fun clear() {
        while (pool.isNotEmpty()) {
            pool.removeFirst().recycle()
        }
        currentPoolBytes.set(0L)
    }
}