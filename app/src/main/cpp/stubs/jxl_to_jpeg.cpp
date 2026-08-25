// JXL → JPEG 1:1 transcoder — clean reimplementation.
//
// History: the previous streaming implementation fed libjxl scanlines into
// libjpeg via JxlDecoderSetImageOutCallback. The callback fires from libjxl's
// parallel runner threads in a non-deterministic tile order, so row segments
// could arrive out of order; the resulting JPEG was a mosaic (per-MCU block
// reordering) that destroyed the picture. That code is deleted; this file is
// a from-scratch rewrite.
//
// Design: two phases, no streaming callback.
//   Phase 1 — decode the whole JXL into one RGB buffer with
//             JxlDecoderSetImageOutBuffer (JXL_TYPE_UINT8, 3 comps).
//             This is a plain contiguous buffer; libjxl fills it in image
//             order (y ascending) before returning, so the buffer is
//             guaranteed to be a correct raster. No cross-thread state.
//   Phase 2 — single-threaded libjpeg encode from that buffer,
//             4:4:4 chroma (no chroma subsampling → no 16×16 MCU lattice),
//             quality 97 (near-lossless for display purposes).
//
// Memory: source w×h×3 bytes. For a 65536×49152 source that is ~9.2 GB,
// which will fail on any device — the caller probes dimensions first and
// should only call this when the buffer fits. For 32768×24576 (32K long
// edge) it is 2.25 GB, feasible on 12 GB devices. 16384×12288 → 564 MB.
//
// The caller keeps this running on Dispatchers.IO; the Kotlin side has no
// reference to the buffer.

#include <jni.h>
#include <android/log.h>
#include <cstdio>
#include <cstdint>
#include <cstring>
#include <cerrno>
#include <vector>
#include <memory>
#include <chrono>
#include <sys/mman.h>
#include <unistd.h>

#ifdef SMARTVISION_REAL_JXL_DECODER
extern "C" {
#include <jxl/decode.h>
#include <jxl/thread_parallel_runner.h>
}
extern "C" {
#include "jpeglib.h"
}
#endif

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "jxl_to_jpeg", __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, "jxl_to_jpeg", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "jxl_to_jpeg", __VA_ARGS__)

#ifdef SMARTVISION_REAL_JXL_DECODER

namespace {

// Phase 1: decode [data,len] into a contiguous RGB buffer.
// Returns nullptr on failure; sets *outW/*outH.
std::unique_ptr<std::vector<uint8_t>> decodeJxlRgb(
    const uint8_t* data, size_t len, uint32_t* outW, uint32_t* outH) {
    auto t0 = std::chrono::steady_clock::now();
    auto* dec = JxlDecoderCreate(nullptr);
    if (dec == nullptr) { LOGE("JxlDecoderCreate failed"); return nullptr; }
    // Use libjxl's suggested worker count — 0 means "1 worker" (serial),
    // which is why decoding took 33 s. The default suggestion is
    // hardware_concurrency-1, which on this 8-core device is 7.
    size_t nworkers = JxlThreadParallelRunnerDefaultNumWorkerThreads();
    void* runner = JxlThreadParallelRunnerCreate(nullptr, nworkers);
    LOGI("runner workers=%zu", nworkers);
    if (runner != nullptr) {
        JxlDecoderSetParallelRunner(dec, JxlThreadParallelRunner, runner);
    }
    JxlDecoderSetInput(dec, data, len);
    JxlDecoderSubscribeEvents(dec, JXL_DEC_BASIC_INFO | JXL_DEC_FULL_IMAGE);

    std::unique_ptr<std::vector<uint8_t>> pixels;
    uint32_t w = 0, h = 0;

    for (;;) {
        JxlDecoderStatus s = JxlDecoderProcessInput(dec);
        if (s == JXL_DEC_ERROR || s == JXL_DEC_NEED_MORE_INPUT) {
            LOGE("decode failed status=%d", (int)s);
            break;
        }
        if (s == JXL_DEC_SUCCESS) break;
        if (s == JXL_DEC_BASIC_INFO) {
            JxlBasicInfo info{};
            JxlDecoderGetBasicInfo(dec, &info);
            w = info.xsize;
            h = info.ysize;
            LOGI("BASIC_INFO %ux%u bits=%d alpha=%d anim=%d orientation=%d",
                 w, h, info.bits_per_sample, info.alpha_bits,
                 info.have_animation, info.orientation);
        } else if (s == JXL_DEC_NEED_IMAGE_OUT_BUFFER) {
            JxlPixelFormat fmt = { 3, JXL_TYPE_UINT8, JXL_NATIVE_ENDIAN, 0 };
            size_t bsize = 0;
            if (JxlDecoderImageOutBufferSize(dec, &fmt, &bsize) != JXL_DEC_SUCCESS ||
                bsize == 0) {
                LOGE("ImageOutBufferSize failed");
                break;
            }
            pixels = std::make_unique<std::vector<uint8_t>>(bsize);
            if (JxlDecoderSetImageOutBuffer(dec, &fmt, pixels->data(), pixels->size())
                    != JXL_DEC_SUCCESS) {
                LOGE("SetImageOutBuffer failed");
                pixels.reset();
                break;
            }
        } else if (s == JXL_DEC_FULL_IMAGE) {
            // Buffer fully written, in raster order.
            break;
        }
    }
    JxlDecoderDestroy(dec);
    if (runner != nullptr) JxlThreadParallelRunnerDestroy(runner);

    auto t1 = std::chrono::steady_clock::now();
    long decMs = std::chrono::duration_cast<std::chrono::milliseconds>(t1 - t0).count();
    if (pixels && pixels->size() == (size_t)w * h * 3) {
        *outW = w;
        *outH = h;
        LOGI("decoded RGB buffer %ux%u (%zu bytes) in %ld ms",
             w, h, pixels->size(), decMs);
        return pixels;
    }
    LOGE("decoded buffer size mismatch: %zu vs %ux%u*3 (after %ld ms)",
         pixels ? pixels->size() : 0, w, h, decMs);
    return nullptr;
}

// Phase 2: single-threaded libjpeg encode, 4:4:4, quality 97.
bool writeJpeg444(const std::vector<uint8_t>& rgb,
                  uint32_t w, uint32_t h, const char* path, int quality) {
    auto t0 = std::chrono::steady_clock::now();
    FILE* fp = std::fopen(path, "wb");
    if (fp == nullptr) {
        LOGE("fopen(%s) failed errno=%d", path, errno);
        return false;
    }
    struct jpeg_compress_struct cinfo;
    struct jpeg_error_mgr jerr;
    cinfo.err = jpeg_std_error(&jerr);
    jpeg_create_compress(&cinfo);
    jpeg_stdio_dest(&cinfo, fp);

    cinfo.image_width = w;
    cinfo.image_height = h;
    cinfo.input_components = 3;
    cinfo.in_color_space = JCS_RGB;
    jpeg_set_defaults(&cinfo);
    // 4:4:4 — every component full rate; no 16×16 MCU chroma lattice.
    cinfo.comp_info[0].h_samp_factor = 1;
    cinfo.comp_info[0].v_samp_factor = 1;
    cinfo.comp_info[1].h_samp_factor = 1;
    cinfo.comp_info[1].v_samp_factor = 1;
    cinfo.comp_info[2].h_samp_factor = 1;
    cinfo.comp_info[2].v_samp_factor = 1;
    jpeg_set_quality(&cinfo, quality, TRUE);
    // Optimize Huffman for ~5-10% smaller files at a ~20% encode cost
    // — acceptable since this is one-shot per source.
    cinfo.optimize_coding = TRUE;
    jpeg_start_compress(&cinfo, TRUE);

    const uint8_t* row = rgb.data();
    const size_t stride = (size_t)w * 3;
    while (cinfo.next_scanline < cinfo.image_height) {
        JSAMPROW rowPtr = const_cast<JSAMPROW>(row);
        jpeg_write_scanlines(&cinfo, &rowPtr, 1);
        row += stride;
    }
    jpeg_finish_compress(&cinfo);
    jpeg_destroy_compress(&cinfo);
    std::fclose(fp);
    auto t1 = std::chrono::steady_clock::now();
    long encMs = std::chrono::duration_cast<std::chrono::milliseconds>(t1 - t0).count();
    LOGI("wrote %s %ux%u q%d (4:4:4) in %ld ms", path, w, h, quality, encMs);
    return true;
}

} // namespace

extern "C" JNIEXPORT jintArray JNICALL
Java_com_smartvision_gallery_decoder_bridge_NativeBridge_nativeJxlDecodeToJpegFile(
    JNIEnv* env, jclass, jint fd, jlong len, jstring jpegPath, jint quality
) {
    if (fd < 0 || len <= 0 || jpegPath == nullptr) {
        LOGW("invalid args fd=%d len=%lld", fd, (long long)len);
        return nullptr;
    }
    const char* path = env->GetStringUTFChars(jpegPath, nullptr);
    if (path == nullptr) return nullptr;

    // Cap the mmap to 256 MB — a 32K JXL bitstream is well under that.
    size_t mapLen = (size_t)len;
    const size_t MMAP_CAP = 256UL * 1024 * 1024;
    if (mapLen > MMAP_CAP) mapLen = MMAP_CAP;
    void* data = mmap(nullptr, mapLen, PROT_READ, MAP_SHARED, fd, 0);
    if (data == MAP_FAILED) {
        LOGW("mmap failed errno=%d", errno);
        env->ReleaseStringUTFChars(jpegPath, path);
        return nullptr;
    }

    uint32_t w = 0, h = 0;
    auto rgb = decodeJxlRgb(static_cast<const uint8_t*>(data), mapLen, &w, &h);
    munmap(data, mapLen);

    jintArray result = nullptr;
    if (rgb && writeJpeg444(*rgb, w, h, path, (int)quality)) {
        result = env->NewIntArray(2);
        if (result != nullptr) {
            jint dims[2] = { (jint)w, (jint)h };
            env->SetIntArrayRegion(result, 0, 2, dims);
        }
        LOGI("OK %ux%u", w, h);
    } else {
        LOGW("decode failed");
    }
    env->ReleaseStringUTFChars(jpegPath, path);
    return result;
}

#else // !SMARTVISION_REAL_JXL_DECODER

extern "C" JNIEXPORT jintArray JNICALL
Java_com_smartvision_gallery_decoder_bridge_NativeBridge_nativeJxlDecodeToJpegFile(
    JNIEnv*, jclass, jint, jlong, jstring, jint
) {
    return nullptr;
}

#endif
