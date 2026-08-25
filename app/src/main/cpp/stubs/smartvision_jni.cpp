// SmartVision JNI bridge 鈥?V1.x real implementation.
//
// Calls libavif for AVIF images and libjxl for JPEG XL images. The JNI surface,
// signatures, and ABI are stable; the bodies here replace the V1.0 placeholder
// stubs.
//
// Build configuration:
//   - libavif is linked statically (aom-av1-static + libavif-static).
//   - libjxl is linked statically (libjxl-static + libjxl_threads-static).
//   - Both are built with -fvisibility=hidden; the JNI surface itself is exported
//     via the JNIEXPORT macro so the Kotlin `external fun` resolves them.

#include <jni.h>
#include <android/log.h>
#include <android/bitmap.h>
#include <cstring>
#include <cstdint>
#include <cerrno>
#include <vector>
#include <memory>
#include <sys/mman.h>
#include <unistd.h>
#include <cstdio>

// Pull in the upstream decoders when SMARTVISION_REAL_AVIF_DECODER /
// SMARTVISION_REAL_JXL_DECODER are set; otherwise we compile the stub code.
#ifdef SMARTVISION_REAL_AVIF_DECODER
extern "C" {
#include <avif/avif.h>
}
#endif
#ifdef SMARTVISION_REAL_JXL_DECODER
extern "C" {
#include <jxl/decode.h>
#include <jxl/thread_parallel_runner.h>
}
#endif

#ifdef SMARTVISION_REAL_JXL_DECODER
extern "C" {
#include "jpeglib.h"
}
#endif

#include "decoder_stub.h"

#define LOG_TAG "SmartVisionJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Cached JavaVM for attaching worker threads (used when libavif / libjxl are
// off the main thread inside a future decode call).
static JavaVM* g_javaVM = nullptr;

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* /*reserved*/) {
    g_javaVM = vm;
    return JNI_VERSION_1_6;
}

namespace {

// ---- Bitmap helpers --------------------------------------------------------

jclass g_bitmapClass = nullptr;
jmethodID g_bitmapCreateBitmap = nullptr;
jmethodID g_bitmapCopy = nullptr;
jmethodID g_bitmapSetPixels = nullptr;
jmethodID g_bitmapEraseColor = nullptr;

bool ensureBitmapRefs(JNIEnv* env) {
    if (g_bitmapClass != nullptr) return true;
    jclass cls = env->FindClass("android/graphics/Bitmap");
    if (cls == nullptr) return false;
    g_bitmapClass = (jclass)env->NewGlobalRef(cls);
    g_bitmapCreateBitmap = env->GetStaticMethodID(
        g_bitmapClass, "createBitmap",
        "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;"
    );
    g_bitmapCopy = env->GetMethodID(
        g_bitmapClass, "copy",
        "(Landroid/graphics/Bitmap$Config;Z)Landroid/graphics/Bitmap;"
    );
    g_bitmapSetPixels = env->GetMethodID(
        g_bitmapClass, "setPixels",
        "([IIIIIII)V"
    );
    g_bitmapEraseColor = env->GetMethodID(
        g_bitmapClass, "eraseColor", "(I)V"
    );
    if (g_bitmapCreateBitmap == nullptr || g_bitmapCopy == nullptr
        || g_bitmapSetPixels == nullptr || g_bitmapEraseColor == nullptr) {
        env->DeleteGlobalRef(g_bitmapClass);
        g_bitmapClass = nullptr;
        return false;
    }
    return true;
}

jobject makeBitmapArgb(JNIEnv* env, int w, int h) {
    if (!ensureBitmapRefs(env)) return nullptr;
    jclass configClass = env->FindClass("android/graphics/Bitmap$Config");
    jfieldID argb8888 = env->GetStaticFieldID(
        configClass, "ARGB_8888", "Landroid/graphics/Bitmap$Config;"
    );
    jobject config = env->GetStaticObjectField(configClass, argb8888);
    jobject bmp = env->CallStaticObjectMethod(
        g_bitmapClass, g_bitmapCreateBitmap, (jint)w, (jint)h, config
    );
    env->DeleteLocalRef(config);
    env->DeleteLocalRef(configClass);
    return bmp;
}

// Push RGBA8888 bytes into an ARGB_8888 Bitmap by writing the pixels directly
// through AndroidBitmap_lockPixels. This avoids a jintArray round-trip plus a
// Bitmap.setPixels() call — the single biggest cost when the source is a large
// full-resolution JXL frame (e.g. 4096x3072 => 12.6M pixels).
bool fillBitmapArgb(JNIEnv* env, jobject bitmap, int w, int h, const uint8_t* rgba) {
    AndroidBitmapInfo info{};
    void* pixels = nullptr;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS
        || info.width != (uint32_t)w || info.height != (uint32_t)h
        || info.format != ANDROID_BITMAP_FORMAT_RGBA_8888
        || AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
        return false;
    }
    // ARGB_8888 memory layout on Android is actually RGBA (byte order R G B A),
    // which matches our RGBA8888 source byte-for-byte — no per-pixel swizzle
    // needed. write the lot straight through.
    const size_t lineBytes = (size_t)w * 4;
    uint8_t* dst = static_cast<uint8_t*>(pixels);
    const uint8_t* src = rgba;
    for (int y = 0; y < h; ++y) {
        memcpy(dst, src, lineBytes);
        dst += info.stride;
        src += lineBytes;
    }
    AndroidBitmap_unlockPixels(env, bitmap);
    return true;
}

// ---- NativeDecodeResult factory -------------------------------------------

jclass g_decodeResultClass = nullptr;
jmethodID g_decodeResultCtor = nullptr;

bool ensureDecodeResultRefs(JNIEnv* env) {
    if (g_decodeResultClass != nullptr) return true;
    jclass cls = env->FindClass("com/smartvision/gallery/decoder/bridge/NativeDecodeResult");
    if (cls == nullptr) return false;
    g_decodeResultClass = (jclass)env->NewGlobalRef(cls);
    g_decodeResultCtor = env->GetMethodID(
        g_decodeResultClass, "<init>",
        "(Landroid/graphics/Bitmap;IIIZ)V"
    );
    return g_decodeResultCtor != nullptr;
}

jobject makeDecodeResult(JNIEnv* env, jobject bitmap, int w, int h, int colorDepth, bool isHdr) {
    if (!ensureDecodeResultRefs(env)) return nullptr;
    return env->NewObject(
        g_decodeResultClass, g_decodeResultCtor, bitmap, w, h, colorDepth, (jboolean)isHdr
    );
}

// ---- Stub fallback (when real decoders aren't linked) ---------------------

jobject makePlaceholderBitmap(JNIEnv* env, int w, int h) {
    if (!ensureBitmapRefs(env)) return nullptr;
    jobject bmp = makeBitmapArgb(env, w, h);
    if (bmp == nullptr) return nullptr;
    // Fill with a gradient so it visually tells the user "this is a stub".
    AndroidBitmapInfo info{};
    void* pixels = nullptr;
    if (AndroidBitmap_getInfo(env, bmp, &info) != ANDROID_BITMAP_RESULT_SUCCESS
        || AndroidBitmap_lockPixels(env, bmp, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
        return bmp;
    }
    uint32_t* px = static_cast<uint32_t*>(pixels);
    for (int y = 0; y < (int)info.height; ++y) {
        for (int x = 0; x < (int)info.width; ++x) {
            uint8_t r = (uint8_t)((x * 255) / (info.width > 1 ? info.width - 1 : 1));
            uint8_t g = (uint8_t)((y * 255) / (info.height > 1 ? info.height - 1 : 1));
            uint8_t b = (uint8_t)(((x + y) * 128) / (info.width + info.height));
            px[y * info.stride / 4 + x] = 0xFF000000u | (r << 16) | (g << 8) | b;
        }
    }
    AndroidBitmap_unlockPixels(env, bmp);
    return bmp;
}

#ifdef SMARTVISION_REAL_AVIF_DECODER

// ---- Real AVIF decode ------------------------------------------------------

bool decodeAvifReal(const uint8_t* data, size_t len, int targetW, int targetH,
                    jobject* outBmp, int* outW, int* outH, int* outDepth, bool* outHdr) {
    avifDecoder* dec = avifDecoderCreate();
    if (dec == nullptr) return false;
    // Multi-threaded AV1 decode: libavif defaults maxThreads=1 (single-threaded
    // aom), making a 16K software decode 6+ seconds — the single biggest
    // thumbnail/full-decode bottleneck. sysconf gives online cores; cap at 8
    // to avoid thread thrash. This path runs in-process (no fork), so the
    // threads are owned directly by the calling Dispatchers.IO coroutine.
    long cpus = sysconf(_SC_NPROCESSORS_ONLN);
    if (cpus < 1) cpus = 1;
    else if (cpus > 8) cpus = 8;
    dec->maxThreads = (int)cpus;
    // Force dav1d (≈2-3× faster than aom) — both codecs are statically linked
    // into libavif and libavif's AUTO order prefers aom.
    dec->codecChoice = AVIF_CODEC_CHOICE_DAV1D;
    avifResult r = avifDecoderSetIOMemory(dec, data, len);
    if (r == AVIF_RESULT_OK) r = avifDecoderParse(dec);
    if (r == AVIF_RESULT_OK) r = avifDecoderNextImage(dec);
    if (r != AVIF_RESULT_OK) {
        LOGE("libavif: parse/next image failed: %s", avifResultToString(r));
        avifDecoderDestroy(dec);
        return false;
    }
    avifImage* img = dec->image;
    if (img == nullptr || img->width == 0 || img->height == 0) {
        avifDecoderDestroy(dec);
        return false;
    }

    avifRGBImage rgb;
    avifRGBImageSetDefaults(&rgb, img);
    // Honor thumbnail request: pick a downscale factor that roughly matches
    // the requested pixel size while preserving aspect ratio.
    int reqW = targetW > 0 ? targetW : img->width;
    int reqH = targetH > 0 ? targetH : img->height;
    int downscale = 1;
    while ((img->width / (downscale * 2)) >= reqW && (img->height / (downscale * 2)) >= reqH) {
        downscale *= 2;
    }
    rgb.width = img->width / downscale;
    rgb.height = img->height / downscale;
    rgb.depth = 8;
    rgb.format = AVIF_RGB_FORMAT_RGBA;
    rgb.ignoreAlpha = false;
    if (avifRGBImageAllocatePixels(&rgb) != AVIF_RESULT_OK) {
        avifDecoderDestroy(dec);
        return false;
    }
    if (downscale > 1) {
        // avifImageScale resizes the YUV planes in place; a subsequent
        // avifRGBImageSetDefaults/avifImageYUVToRGB picks up the scaled dims.
        // Must pass a real avifDiagnostics — libavif clears it via
        // avifDiagnosticsClearError on entry, and passing nullptr dereferences
        // it (avifImageScale+36 → avifDiagnosticsClearError SIGSEGV, the crash
        // that took down the viewer process on every 16K AVIF thumbnail).
        avifDiagnostics diag;
        r = avifImageScale(img, (uint32_t)rgb.width, (uint32_t)rgb.height, &diag);
        if (r == AVIF_RESULT_OK) r = avifImageYUVToRGB(img, &rgb);
    } else {
        r = avifImageYUVToRGB(img, &rgb);
    }
    if (r != AVIF_RESULT_OK) {
        LOGE("libavif: convert/scale failed: %s", avifResultToString(r));
        avifRGBImageFreePixels(&rgb);
        avifDecoderDestroy(dec);
        return false;
    }

    // Build a Bitmap and copy the rgba buffer into it.
    JNIEnv* env = nullptr;
    if (g_javaVM != nullptr) {
        g_javaVM->AttachCurrentThread(&env, nullptr);
    }
    bool ok = false;
    if (env != nullptr && ensureBitmapRefs(env)) {
        jobject bmp = makeBitmapArgb(env, rgb.width, rgb.height);
        if (bmp != nullptr) {
            ok = fillBitmapArgb(env, bmp, rgb.width, rgb.height, rgb.pixels);
            if (ok) {
                *outBmp = bmp;
                *outW = rgb.width;
                *outH = rgb.height;
                *outDepth = img->depth;
                *outHdr = (img->depth >= 10) || (img->yuvFormat == AVIF_PIXEL_FORMAT_YUV420);
            }
        }
    }

    avifRGBImageFreePixels(&rgb);
    avifDecoderDestroy(dec);
    return ok;
}

#endif // SMARTVISION_REAL_AVIF_DECODER

#ifdef SMARTVISION_REAL_JXL_DECODER

// ---- Box downsample (RGBA8888) ---------------------------------------------
// Repeatedly halve a full-resolution RGBA buffer by 2x2 box averaging until
// it fits the requested target size. libjxl has no "output small buffer"
// API for one-shot input, so the thumbnail path decodes full-res then shrinks
// HERE, before building the Bitmap — keeping the subsequent pixels write and
// the Java-side Bitmap small. In-place: each pass writes to the front half.
void boxDownsample(uint8_t* pix, size_t* w, size_t* h, int targetW, int targetH) {
    while (*w > (size_t)targetW * 2 && *h > (size_t)targetH * 2 && (*w >= 2) && (*h >= 2)) {
        size_t sw = *w, sh = *h, dw = sw / 2, dh = sh / 2;
        uint8_t* dst = pix;
        for (size_t y = 0; y < dh; ++y) {
            const uint8_t* r0 = pix + (2 * y) * sw * 4;
            const uint8_t* r1 = r0 + sw * 4;
            uint8_t* out = dst + y * dw * 4;
            for (size_t x = 0; x < dw; ++x) {
                const uint8_t* p0 = r0 + (2 * x) * 4;
                const uint8_t* p1 = p0 + 4;
                const uint8_t* p2 = r1 + (2 * x) * 4;
                const uint8_t* p3 = p2 + 4;
                out[x * 4 + 0] = (uint8_t)(((unsigned)p0[0] + p1[0] + p2[0] + p3[0] + 2) >> 2);
                out[x * 4 + 1] = (uint8_t)(((unsigned)p0[1] + p1[1] + p2[1] + p3[1] + 2) >> 2);
                out[x * 4 + 2] = (uint8_t)(((unsigned)p0[2] + p1[2] + p2[2] + p3[2] + 2) >> 2);
                out[x * 4 + 3] = (uint8_t)(((unsigned)p0[3] + p1[3] + p2[3] + p3[3] + 2) >> 2);
            }
        }
        *w = dw;
        *h = dh;
    }
}

// ---- Direct bitmap write callback ----------------------------------------
// libjxl invokes this per scanline (libjxl 0.10.3 JxlThreadParallelRunner is
// pinned to 1 worker here, so scanlines arrive strictly in ascending y —
// critical for the box-accumulator below). We write straight into the
// pre-allocated ARGB_8888 bitmap's locked pixels. lockPixels is acquired
// once before the ProcessInput loop and released once after; the callback
// is 0-JNI pure C++.
//
// Box-averaging stride s>1: a rolling window of stride source rows × dstW
// cells accumulates R/G/B/A sums + counts. When the last source row of a
// destination row arrives (y % s == s-1 or y == srcH-1) the window is
// averaged into the bitmap's dstY row and the window is zeroed. Window
// footprint is stride×dstW×sizeof(AccCell) = stride×dstW×10 B ≤ 328 KB
// (stride=8, dstW=4096) — a 750× reduction vs. the full dstH×dstW
// accumulator that would overshoot the app's native heap.
// s==1 is the stride=1 (true 1:1) path: straight pixel copy, no window.
struct AccCell { uint16_t r = 0, g = 0, b = 0, a = 0; uint16_t cnt = 0; };

struct BitmapWriteState {
    AndroidBitmapInfo info;
    void* rawPtr = nullptr;
    uint32_t dstW = 0, dstH = 0;
    uint32_t srcW = 0, srcH = 0;
    uint32_t stride = 1;
    std::vector<AccCell> window;  // size = stride * dstW; rolling s-row buffer
};

static void boxFlushRow(BitmapWriteState* st, uint32_t dstY) {
    if (st->stride == 1) return;
    uint8_t* rowPtr = static_cast<uint8_t*>(st->rawPtr)
                      + (size_t)(dstY * st->info.stride);
    for (uint32_t dstX = 0; dstX < st->dstW; ++dstX) {
        uint32_t R = 0, G = 0, B = 0, A = 0, C = 0;
        for (uint32_t k = 0; k < st->stride; ++k) {
            const AccCell& c = st->window[(size_t)k * st->dstW + dstX];
            R += c.r; G += c.g; B += c.b; A += c.a; C += c.cnt;
        }
        if (C == 0) continue;
        uint8_t* dst = rowPtr + (size_t)dstX * 4;
        dst[0] = (uint8_t)((R + C / 2) / C);
        dst[1] = (uint8_t)((G + C / 2) / C);
        dst[2] = (uint8_t)((B + C / 2) / C);
        dst[3] = (uint8_t)((A + C / 2) / C);
    }
    std::memset(st->window.data(), 0, st->window.size() * sizeof(AccCell));
}

void bitmapWriteCallback(void* opaque, size_t x, size_t y,
                         size_t num_pixels, const void* pixels) {
    auto* st = static_cast<BitmapWriteState*>(opaque);
    const uint8_t* src = static_cast<const uint8_t*>(pixels);
    const uint32_t s = st->stride;

    if (s == 1) {
        for (size_t i = 0; i < num_pixels; ++i, src += 4) {
            size_t gx = x + i;
            if (gx >= st->dstW || y >= st->dstH) continue;
            uint8_t* dst = static_cast<uint8_t*>(st->rawPtr)
                           + (size_t)(y * st->info.stride + gx * 4);
            dst[0] = src[0]; dst[1] = src[1];
            dst[2] = src[2]; dst[3] = src[3];
        }
        return;
    }

    const uint32_t k = (uint32_t)(y % s);
    AccCell* row = st->window.data() + (size_t)k * st->dstW;
    for (size_t i = 0; i < num_pixels; ++i, src += 4) {
        size_t gx = x + i;
        if (gx >= st->srcW) continue;
        uint32_t dstX = (uint32_t)(gx / s);
        if (dstX >= st->dstW) continue;
        AccCell& c = row[dstX];
        c.r += src[0];
        c.g += src[1];
        c.b += src[2];
        c.a += src[3];
        c.cnt += 1;
    }
    const bool lastInBox = (k == s - 1) || (y == st->srcH - 1);
    if (lastInBox) {
        boxFlushRow(st, (uint32_t)(y / s));
    }
}

// ---- Real JPEG XL decode ---------------------------------------------------

// DC-only decode: subscribes FRAME_PROGRESSION at kDC detail, flushes the
// decoder when the DC layer (source/8) is ready — ~1/64 of the full decode
// work. Used for thumbnails where full-res + box-downsample was 50-100x
// slower. The flushed image is upscaled DC at FULL resolution (libjxl 0.10.3
// no longer offers a native 1/8 output buffer), so we guard source size: over
// the ceiling we bail and the caller falls back to stride-decoded thumbnail.
// Returns false on failure (including codec doesn't expose DC).
bool decodeJxlDc(JNIEnv* env, const uint8_t* data, size_t len,
                 int* outW, int* outH, jobject* outBmp) {
    auto* dec = JxlDecoderCreate(nullptr);
    if (dec == nullptr) return false;
    void* runner = JxlThreadParallelRunnerCreate(nullptr, 1);
    if (runner != nullptr) {
        JxlDecoderSetParallelRunner(dec, JxlThreadParallelRunner, runner);
    }
    JxlDecoderStatus subSt = JxlDecoderSubscribeEvents(
        dec, JXL_DEC_BASIC_INFO | JXL_DEC_FRAME_PROGRESSION
    );
    if (subSt != JXL_DEC_SUCCESS) {
        LOGW("decodeJxlDc: subscribe failed status=%d", (int)subSt);
        JxlDecoderDestroy(dec);
        if (runner != nullptr) JxlThreadParallelRunnerDestroy(runner);
        return false;
    }
    // Request DC-level progressive detail (1:8). Not guaranteed to trigger.
    JxlDecoderSetProgressiveDetail(dec, kDC);
    JxlDecoderSetInput(dec, data, len);
    JxlDecoderCloseInput(dec);

    JxlPixelFormat format = {4, JXL_TYPE_UINT8, JXL_NATIVE_ENDIAN, 0};
    int decW = 0, decH = 0;
    bool gotDc = false;
    jobject bmp = nullptr;
    void* lockedPixels = nullptr;

    while (true) {
        JxlDecoderStatus s = JxlDecoderProcessInput(dec);
        if (s == JXL_DEC_SUCCESS) break;
        if (s == JXL_DEC_ERROR || s == JXL_DEC_NEED_MORE_INPUT) {
            LOGW("decodeJxlDc: process status=%d", (int)s);
            break;
        }
        if (s == JXL_DEC_BASIC_INFO) {
            JxlBasicInfo info{};
            JxlDecoderGetBasicInfo(dec, &info);
            decW = (int)info.xsize;
            decH = (int)info.ysize;
            // Full-res flush buffer ceiling: 64M px → 256MB ARGB. Over this the
            // DC path's memory cost beats its CPU savings; fall back instead.
            if ((uint64_t)decW * decH > (64UL * 1024 * 1024)) {
                LOGW("decodeJxlDc: source %dx%d too large for DC path", decW, decH);
                break;
            }
        } else if (s == JXL_DEC_NEED_IMAGE_OUT_BUFFER) {
            // libjxl 0.10.3 flushes upscaled DC into a FULL-resolution buffer.
            bmp = makeBitmapArgb(env, decW, decH);
            if (bmp == nullptr) break;
            if (AndroidBitmap_lockPixels(env, bmp, &lockedPixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
                bmp = nullptr;
                break;
            }
            size_t outSize = (size_t)decW * decH * 4;
            JxlDecoderStatus ok = JxlDecoderSetImageOutBuffer(dec, &format, lockedPixels, outSize);
            if (ok != JXL_DEC_SUCCESS) {
                LOGE("decodeJxlDc: setImageOutBuffer failed status=%d", (int)ok);
                AndroidBitmap_unlockPixels(env, bmp);
                bmp = nullptr;
                break;
            }
        } else if (s == JXL_DEC_FRAME_PROGRESSION) {
            // DC layer ready — flush it into the out buffer, then stop.
            if (JxlDecoderFlushImage(dec) == JXL_DEC_SUCCESS) {
                gotDc = true;
            }
            break;
        }
    }

    if (gotDc && bmp != nullptr) {
        AndroidBitmap_unlockPixels(env, bmp);
        *outBmp = bmp;
        *outW = decW;
        *outH = decH;
        JxlDecoderDestroy(dec);
        if (runner != nullptr) JxlThreadParallelRunnerDestroy(runner);
        LOGI("decodeJxlDc: OK %dx%d src→DC flushed", decW, decH);
        return true;
    }
    if (bmp != nullptr && lockedPixels != nullptr) {
        AndroidBitmap_unlockPixels(env, bmp);
        env->DeleteLocalRef(bmp);
    }
    JxlDecoderDestroy(dec);
    if (runner != nullptr) JxlThreadParallelRunnerDestroy(runner);
    return false;
}

bool decodeJxlReal(JNIEnv* env, const uint8_t* data, size_t len,
                   int targetW, int targetH,
                   jobject* outBmp, int* outW, int* outH, int* outDepth, bool* outHdr) {
    auto* dec = JxlDecoderCreate(nullptr);
    if (dec == nullptr) return false;
    // Pin libjxl to 1 worker: the box-accumulator in bitmapWriteCallback
    // relies on scanlines arriving in strictly ascending y order to keep
    // its rolling window coherent. JxlThreadParallelRunnerDefaultNumWorkerThreads
    // would deliver scanlines out of order and corrupt colors. The one-time
    // decode cost (~3 s on a 16 K source) is paid once per cache file.
    void* runner = JxlThreadParallelRunnerCreate(nullptr, 1);
    if (runner != nullptr) {
        JxlDecoderSetParallelRunner(dec, JxlThreadParallelRunner, runner);
    }
    // NOTE: JXL_DEC_NEED_IMAGE_OUT_BUFFER (value 5) must NOT be subscribed —
    // libjxl rejects events whose bits fall in 0..63 ("Can only subscribe to
    // informative events") and returns JXL_DEC_ERROR. NEED_IMAGE_OUT_BUFFER
    // is instead returned by JxlDecoderProcessInput automatically when the
    // decoder needs our output buffer. Subscribing only the informative
    // events (BASIC_INFO / FRAME / FULL_IMAGE) is the correct protocol.
    JxlDecoderStatus subSt = JxlDecoderSubscribeEvents(
        dec, JXL_DEC_BASIC_INFO | JXL_DEC_FRAME | JXL_DEC_FULL_IMAGE
    );
    LOGI("libjxl: SubscribeEvents status=%d mask=%d", (int)subSt,
         (int)(JXL_DEC_BASIC_INFO | JXL_DEC_FRAME | JXL_DEC_FULL_IMAGE));
    LOGI("libjxl: decodeJxlReal input len=%zu magic=%02x%02x%02x%02x",
         len, data[0], data[1], data[2], data[3]);
    JxlDecoderStatus setSt = JxlDecoderSetInput(dec, data, len);
    LOGI("libjxl: SetInput status=%d", (int)setSt);
    JxlDecoderCloseInput(dec);
    LOGI("libjxl: CloseInput done");

    JxlPixelFormat format = {4, JXL_TYPE_UINT8, JXL_NATIVE_ENDIAN, 0};
    int decW = 0, decH = 0, depth = 8;
    bool hdr = false;
    bool gotImage = false;
    std::unique_ptr<BitmapWriteState> bwsOwned;  // lifetime must cover the callback below

    while (true) {
        JxlDecoderStatus s = JxlDecoderProcessInput(dec);
        LOGI("libjxl: ProcessInput status=%d", (int)s);
        if (s == JXL_DEC_SUCCESS) { LOGI("libjxl: SUCCESS"); break; }
        if (s == JXL_DEC_ERROR) { LOGE("libjxl: decoder error"); goto done; }
        if (s == JXL_DEC_NEED_MORE_INPUT) { LOGE("libjxl: truncated input (len=%zu)", len); goto done; }

        switch (s) {
            case JXL_DEC_BASIC_INFO: {
                JxlBasicInfo info{};
                JxlDecoderGetBasicInfo(dec, &info);
                decW = (int)info.xsize;
                decH = (int)info.ysize;
                depth = info.bits_per_sample;
                hdr = (depth > 8) || (info.exponent_bits_per_sample > 0);
                LOGI("libjxl: BASIC_INFO %dx%d depth=%d", decW, decH, depth);
                break;
            }
            case JXL_DEC_FRAME: {
                JxlFrameHeader fh{};
                JxlDecoderGetFrameHeader(dec, &fh);
                LOGI("libjxl: FRAME event");
                break;
            }
            case JXL_DEC_NEED_IMAGE_OUT_BUFFER: {
                JxlBasicInfo cur{};
                JxlDecoderGetBasicInfo(dec, &cur);
                size_t bw = cur.xsize, bh = cur.ysize;
                LOGI("libjxl: NEED_IMAGE_OUT_BUFFER %zux%zu target=%dx%d",
                     bw, bh, targetW, targetH);
                size_t longEdge = bw > bh ? bw : bh;
                uint32_t t = (uint32_t)targetW;
                // Cap at 8192 long edge. 8192×6144 (4:3) = 50.3 MP — slightly
                // above Android's 48 MP Canvas ceiling, but workable on Pixel
                // 6/8 (6 GB+) for a single transient bitmap. A 16 K JXL with
                // stride=2 produces 8192×6144 (~200 MB transient heap). Above
                // 8192 → stride doubles to keep output ≤ 8 K.
                if (t > 8192) t = 8192;
                if (t < 256) t = 256;  // guard against t=0 infinite loop
                // Pick the smallest power-of-two stride that brings the output at or
                // below the requested target. output = source / s ≤ t.
                // Without this invariant the output can exceed the Canvas
                // 48MP cap and the viewer crashes (e.g. 20K source with the
                // old hysteresis-keeping stride yielded 10000×7500 = 75MP).
                uint32_t s = 1;
                while (longEdge / s > t) s *= 2;
                *outW = (int)(bw / s);
                *outH = (int)(bh / s);
                *outBmp = makeBitmapArgb(env, *outW, *outH);
                if (*outBmp == nullptr) {
                    LOGE("libjxl: makeBitmapArgb failed for %dx%d", *outW, *outH);
                    goto done;
                }
                AndroidBitmapInfo info{};
                void* raw = nullptr;
                if (AndroidBitmap_getInfo(env, *outBmp, &info) != ANDROID_BITMAP_RESULT_SUCCESS
                    || AndroidBitmap_lockPixels(env, *outBmp, &raw) != ANDROID_BITMAP_RESULT_SUCCESS) {
                    LOGE("libjxl: lockPixels failed for %dx%d", *outW, *outH);
                    goto done;
                }
                bwsOwned = std::make_unique<BitmapWriteState>();
                auto* bws = bwsOwned.get();
                bws->info = info;
                bws->rawPtr = raw;
                bws->dstW = (uint32_t)*outW;
                bws->dstH = (uint32_t)*outH;
                bws->srcW = (uint32_t)bw;
                bws->srcH = (uint32_t)bh;
                bws->stride = s;
                if (s > 1) {
                    bws->window.assign((size_t)s * bws->dstW, AccCell{});
                }
                JxlDecoderStatus ok = JxlDecoderSetImageOutCallback(
                    dec, &format, bitmapWriteCallback, bws);
                if (ok != JXL_DEC_SUCCESS) {
                    LOGE("libjxl: setImageOutCallback failed (status=%d)", (int)ok);
                    AndroidBitmap_unlockPixels(env, *outBmp);
                    goto done;
                }
                LOGI("libjxl: bitmap-write callback stride=%u dst=%ux%u",
                     s, bws->dstW, bws->dstH);
                break;
            }
            case JXL_DEC_FULL_IMAGE: {
                LOGI("libjxl: FULL_IMAGE");
                gotImage = true;
                break;
            }
            default: {
                LOGI("libjxl: status=%d", (int)s);
                break;
            }
        }
    }

done:
    if (gotImage && *outBmp != nullptr) {
        // Bitmap pixels are already filled by the callback. Just unlock and
        // return success.
        AndroidBitmap_unlockPixels(env, *outBmp);
        *outDepth = depth;
        *outHdr = hdr;
        JxlDecoderDestroy(dec);
        if (runner != nullptr) JxlThreadParallelRunnerDestroy(runner);
        return true;
    }
    JxlDecoderDestroy(dec);
    if (runner != nullptr) JxlThreadParallelRunnerDestroy(runner);
    return false;
}

#endif // SMARTVISION_REAL_JXL_DECODER

} // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_smartvision_gallery_decoder_bridge_NativeBridge_nativeInit(JNIEnv*, jclass) {
#ifdef SMARTVISION_REAL_AVIF_DECODER
    LOGI("nativeInit: libavif + libjxl real decoder bridge ready");
#else
    LOGI("nativeInit: stub decoder bridge ready (rebuild with ENABLE_REAL_DECODER=ON to enable real decoders)");
#endif
    return JNI_TRUE;
}

JNIEXPORT jobject JNICALL
Java_com_smartvision_gallery_decoder_bridge_NativeBridge_nativeDecodeAvifThumb(
    JNIEnv* env, jclass, jbyteArray dataArr, jint w, jint h
) {
    if (dataArr == nullptr) return nullptr;
    jsize len = env->GetArrayLength(dataArr);
    jbyte* data = env->GetByteArrayElements(dataArr, nullptr);
    jobject bmp = nullptr;
#ifdef SMARTVISION_REAL_AVIF_DECODER
    int outW = 0, outH = 0, depth = 0;
    bool isHdr = false;
    if (decodeAvifReal(reinterpret_cast<uint8_t*>(data), (size_t)len, (int)w, (int)h,
                       &bmp, &outW, &outH, &depth, &isHdr)) {
        env->ReleaseByteArrayElements(dataArr, data, JNI_ABORT);
        return bmp;
    }
#endif
    env->ReleaseByteArrayElements(dataArr, data, JNI_ABORT);
    LOGW("nativeDecodeAvifThumb: failed for %dx%d, returning null", (int)w, (int)h);
    return nullptr;
}

JNIEXPORT jobject JNICALL
Java_com_smartvision_gallery_decoder_bridge_NativeBridge_nativeDecodeAvifFull(
    JNIEnv* env, jclass, jbyteArray dataArr
) {
    if (dataArr == nullptr) return nullptr;
    jsize len = env->GetArrayLength(dataArr);
    jbyte* data = env->GetByteArrayElements(dataArr, nullptr);
    jobject result = nullptr;
#ifdef SMARTVISION_REAL_AVIF_DECODER
    jobject bmp = nullptr;
    int outW = 0, outH = 0, depth = 0;
    bool isHdr = false;
    if (decodeAvifReal(reinterpret_cast<uint8_t*>(data), (size_t)len, 0, 0,
                       &bmp, &outW, &outH, &depth, &isHdr)) {
        result = makeDecodeResult(env, bmp, outW, outH, depth, isHdr);
        env->ReleaseByteArrayElements(dataArr, data, JNI_ABORT);
        return result;
    }
#endif
    env->ReleaseByteArrayElements(dataArr, data, JNI_ABORT);
    LOGW("nativeDecodeAvifFull: failed, returning null");
    return nullptr;
}

JNIEXPORT jobject JNICALL
Java_com_smartvision_gallery_decoder_bridge_NativeBridge_nativeDecodeJxlFd(
    JNIEnv* env, jclass, jint fd, jlong len, jint targetLongEdgePx
) {
    if (fd < 0 || len <= 0) {
        LOGW("nativeDecodeJxlFd: invalid args fd=%d len=%lld", fd, (long long)len);
        return nullptr;
    }
    // mmap the fd for zero-copy read. len is lseek'd file size; clamp at 256MB.
    size_t mapLen = (size_t)len;
    const size_t MMAP_CAP = 256UL * 1024 * 1024;
    if (mapLen > MMAP_CAP) mapLen = MMAP_CAP;
    void* data = mmap(nullptr, mapLen, PROT_READ, MAP_SHARED, fd, 0);
    if (data == MAP_FAILED) {
        LOGW("nativeDecodeJxlFd: mmap failed errno=%d, falling back to readBytes caller", errno);
        return nullptr;
    }
    jobject result = nullptr;
#ifdef SMARTVISION_REAL_JXL_DECODER
    jobject bmp = nullptr;
    int outW = 0, outH = 0, depth = 0;
    bool isHdr = false;
    if (decodeJxlReal(env, static_cast<uint8_t*>(data), mapLen,
                      (int)targetLongEdgePx, (int)targetLongEdgePx,
                      &bmp, &outW, &outH, &depth, &isHdr)) {
        result = makeDecodeResult(env, bmp, outW, outH, depth, isHdr);
    }
#endif
    munmap(data, mapLen);
    LOGI("nativeDecodeJxlFd: fd=%d len=%zu target=%d → %s",
         fd, mapLen, (int)targetLongEdgePx,
         result == nullptr ? "FAIL" : "OK");
    return result;
}

// Probe-only: run the decoder up to BASIC_INFO, return long edge (max of
// width/height). No pixel decode — cheap (parses JXL headers, ~1ms for 8K).
// Returns -1 on failure. Caller uses this in place of the probe×8 heuristic
// so the source long edge is known exactly → computeZoomTarget no longer
// underestimates and the controller requests the 4096 tier when zoomed.
JNIEXPORT jint JNICALL
Java_com_smartvision_gallery_decoder_bridge_NativeBridge_nativeJxlProbeSize(
    JNIEnv* env, jclass, jint fd, jlong len, jboolean wantWidth
) {
    if (fd < 0 || len <= 0) return -1;
    size_t mapLen = (size_t)len;
    const size_t MMAP_CAP = 256UL * 1024 * 1024;
    if (mapLen > MMAP_CAP) mapLen = MMAP_CAP;
    void* data = mmap(nullptr, mapLen, PROT_READ, MAP_SHARED, fd, 0);
    if (data == MAP_FAILED) {
        LOGW("nativeJxlProbeSize: mmap failed errno=%d", errno);
        return -1;
    }
    jint longEdge = -1;
#ifdef SMARTVISION_REAL_JXL_DECODER
    auto* dec = JxlDecoderCreate(nullptr);
    if (dec != nullptr) {
        JxlDecoderSetInput(dec, static_cast<uint8_t*>(data), mapLen);
        JxlDecoderCloseInput(dec);
        JxlDecoderSubscribeEvents(dec, JXL_DEC_BASIC_INFO);
        JxlDecoderStatus s = JxlDecoderProcessInput(dec);
        if (s == JXL_DEC_BASIC_INFO) {
            JxlBasicInfo info{};
            JxlDecoderGetBasicInfo(dec, &info);
            longEdge = wantWidth == JNI_TRUE
                ? (jint)info.xsize : (jint)info.ysize;
        }
        JxlDecoderDestroy(dec);
    }
#endif
    munmap(data, mapLen);
    LOGI("nativeJxlProbeSize: len=%zu → longEdge=%d", mapLen, longEdge);
    return longEdge;
}

JNIEXPORT jobject JNICALL
Java_com_smartvision_gallery_decoder_bridge_NativeBridge_nativeDecodeJxlScaledBytes(
    JNIEnv* env, jclass, jbyteArray dataArr, jint targetLongEdgePx
) {
    if (dataArr == nullptr) return nullptr;
    jsize len = env->GetArrayLength(dataArr);
    jbyte* data = env->GetByteArrayElements(dataArr, nullptr);
    jobject result = nullptr;
#ifdef SMARTVISION_REAL_JXL_DECODER
    jobject bmp = nullptr;
    int outW = 0, outH = 0, depth = 0;
    bool isHdr = false;
    if (decodeJxlReal(env, reinterpret_cast<uint8_t*>(data), (size_t)len,
                      (int)targetLongEdgePx, (int)targetLongEdgePx,
                      &bmp, &outW, &outH, &depth, &isHdr)) {
        result = makeDecodeResult(env, bmp, outW, outH, depth, isHdr);
    }
#endif
    env->ReleaseByteArrayElements(dataArr, data, JNI_ABORT);
    return result;
}

// DC-only thumbnail decode via fd. ~1/64 the work of full decode.
// Returns a small DC bitmap (source/8 × source/8) suitable for thumbnails.
// Caller should fall back to nativeDecodeJxlFd when this returns null.
JNIEXPORT jobject JNICALL
Java_com_smartvision_gallery_decoder_bridge_NativeBridge_nativeDecodeJxlDcFd(
    JNIEnv* env, jclass, jint fd, jlong len
) {
    if (fd < 0 || len <= 0) return nullptr;
    size_t mapLen = (size_t)len;
    const size_t MMAP_CAP = 256UL * 1024 * 1024;
    if (mapLen > MMAP_CAP) mapLen = MMAP_CAP;
    void* data = mmap(nullptr, mapLen, PROT_READ, MAP_SHARED, fd, 0);
    if (data == MAP_FAILED) {
        LOGW("nativeDecodeJxlDcFd: mmap failed errno=%d", errno);
        return nullptr;
    }
    jobject bmp = nullptr;
    int outW = 0, outH = 0;
#ifdef SMARTVISION_REAL_JXL_DECODER
    decodeJxlDc(env, static_cast<uint8_t*>(data), mapLen, &outW, &outH, &bmp);
#endif
    munmap(data, mapLen);
    LOGI("nativeDecodeJxlDcFd: fd=%d len=%zu → %s",
         fd, mapLen, bmp == nullptr ? "FAIL/fallback" : "OK");
    return bmp;
}

JNIEXPORT jstring JNICALL
Java_com_smartvision_gallery_decoder_bridge_NativeBridge_nativeProbeFormat(
    JNIEnv* env, jclass, jbyteArray dataArr
) {
    if (dataArr == nullptr) return env->NewStringUTF("UNKNOWN");
    jsize len = env->GetArrayLength(dataArr);
    jbyte* data = env->GetByteArrayElements(dataArr, nullptr);
    const char* out = "UNKNOWN";
#ifdef SMARTVISION_REAL_AVIF_DECODER
    avifDecoder* dec = avifDecoderCreate();
    if (dec != nullptr) {
        if (avifDecoderSetIOMemory(dec, reinterpret_cast<const uint8_t*>(data), (size_t)len) == AVIF_RESULT_OK
            && avifDecoderParse(dec) == AVIF_RESULT_OK) {
            out = "AVIF";
        }
        avifDecoderDestroy(dec);
    }
    if (std::strcmp(out, "UNKNOWN") == 0) {
#ifdef SMARTVISION_REAL_JXL_DECODER
        auto* jx = JxlDecoderCreate(nullptr);
        if (jx != nullptr) {
            JxlDecoderSetInput(jx, reinterpret_cast<const uint8_t*>(data), (size_t)len);
            JxlDecoderSubscribeEvents(jx, JXL_DEC_BASIC_INFO);
            JxlDecoderStatus s = JxlDecoderProcessInput(jx);
            if (s == JXL_DEC_BASIC_INFO) out = "JXL";
            JxlDecoderDestroy(jx);
        }
#endif
    }
#endif
    env->ReleaseByteArrayElements(dataArr, data, JNI_ABORT);
    return env->NewStringUTF(out);
}

} // extern "C"
