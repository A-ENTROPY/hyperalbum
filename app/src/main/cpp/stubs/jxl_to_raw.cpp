// JXL → raw RGBA 1:1 pixel dump — preserves original pixel data.
//
// Writes a minimal custom header followed by raw pixel data so the Kotlin
// side can build a custom ImageRegionDecoder on top of it. No encoding
// step, no quality loss, alpha channel preserved.
//
// File format:
//   [4B] magic "SVRAW"
//   [4B] width                (uint32_t, LE)
//   [4B] height               (uint32_t, LE)
//   [4B] num_channels         (uint32_t, LE)  — 3 or 4
//   [4B] bytes_per_channel    (uint32_t, LE)  — 1 for now
//   [N]  pixel data row-major, interleaved RGB/RGBA, top-down
//
// Memory: source w×h×4 bytes worst-case (RGBA). For 32768×24576 (32K long
// edge) that is 3.0 GB, beyond the 2.25 GB for RGB — the caller must probe
// first and only call this when the buffer fits. 13332×20000 RGBA → 1.07 GB.
// The large buffer is freed after the file is written.

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
#include <fcntl.h>

#ifdef SMARTVISION_REAL_JXL_DECODER
extern "C" {
#include <jxl/decode.h>
#include <jxl/thread_parallel_runner.h>
}
#endif

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "jxl_to_raw", __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, "jxl_to_raw", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "jxl_to_raw", __VA_ARGS__)

#ifdef SMARTVISION_REAL_JXL_DECODER

namespace {

// Decode JXL into a contiguous RGBA/RGB buffer, preserving original channels.
// Sets *outChannels to 3 (RGB) or 4 (RGBA) depending on source alpha.
std::unique_ptr<std::vector<uint8_t>> decodeJxlPixels(
    const uint8_t* data, size_t len,
    uint32_t* outW, uint32_t* outH, int* outChannels, long* outDecMs) {

    auto t0 = std::chrono::steady_clock::now();
    auto* dec = JxlDecoderCreate(nullptr);
    if (dec == nullptr) { LOGE("JxlDecoderCreate failed"); return nullptr; }

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
    int channels = 3;

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
            channels = info.alpha_bits > 0 ? 4 : 3;
            LOGI("BASIC_INFO %ux%u bits=%d alpha=%d anim=%d orientation=%d ch=%d",
                 w, h, info.bits_per_sample, info.alpha_bits,
                 info.have_animation, info.orientation, channels);
        } else if (s == JXL_DEC_NEED_IMAGE_OUT_BUFFER) {
            JxlPixelFormat fmt = {
                static_cast<uint32_t>(channels),
                JXL_TYPE_UINT8,
                JXL_NATIVE_ENDIAN,
                0
            };
            // Unpremultiply alpha so the raw file stores original pixel values.
            JxlDecoderSetUnpremultiplyAlpha(dec, JXL_TRUE);
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
            break;
        }
    }
    JxlDecoderDestroy(dec);
    if (runner != nullptr) JxlThreadParallelRunnerDestroy(runner);

    auto t1 = std::chrono::steady_clock::now();
    long decMs = std::chrono::duration_cast<std::chrono::milliseconds>(t1 - t0).count();
    if (outDecMs != nullptr) *outDecMs = decMs;
    size_t expected = (size_t)w * h * channels;
    if (pixels && pixels->size() == expected) {
        *outW = w;
        *outH = h;
        *outChannels = channels;
        LOGI("decoded buffer %ux%u ch=%d (%zu bytes) in %ld ms",
             w, h, channels, pixels->size(), decMs);
        return pixels;
    }
    LOGE("buffer size mismatch: %zu vs %zu (after %ld ms)",
         pixels ? pixels->size() : 0, expected, decMs);
    return nullptr;
}

// Write header + raw pixel data to [path]. Overwrites existing file.
bool writeRawFile(const std::vector<uint8_t>& pixels,
                  uint32_t w, uint32_t h, int channels, const char* path,
                  long* outWriteMs) {
    auto t0 = std::chrono::steady_clock::now();

    // Header: magic(4) + w(4) + h(4) + ch(4) + bpc(4) = 20 bytes
    uint8_t header[20];
    std::memcpy(header, "SVRAW", 4);
    // Little-endian int32
    auto store32 = [&](size_t off, uint32_t v) {
        header[off + 0] = static_cast<uint8_t>(v & 0xFF);
        header[off + 1] = static_cast<uint8_t>((v >> 8) & 0xFF);
        header[off + 2] = static_cast<uint8_t>((v >> 16) & 0xFF);
        header[off + 3] = static_cast<uint8_t>((v >> 24) & 0xFF);
    };
    store32(4,  w);
    store32(8,  h);
    store32(12, static_cast<uint32_t>(channels));
    store32(16, 1);  // bytes_per_channel

    FILE* fp = std::fopen(path, "wb");
    if (fp == nullptr) {
        LOGE("fopen(%s) failed errno=%d", path, errno);
        return false;
    }

    if (std::fwrite(header, 1, sizeof(header), fp) != sizeof(header)) {
        LOGE("fwrite header failed");
        std::fclose(fp);
        std::remove(path);
        return false;
    }

    size_t pixelBytes = pixels.size();
    if (std::fwrite(pixels.data(), 1, pixelBytes, fp) != pixelBytes) {
        LOGE("fwrite pixels failed");
        std::fclose(fp);
        std::remove(path);
        return false;
    }

    std::fclose(fp);

    auto t1 = std::chrono::steady_clock::now();
    long writeMs = std::chrono::duration_cast<std::chrono::milliseconds>(t1 - t0).count();
    if (outWriteMs != nullptr) *outWriteMs = writeMs;
    LOGI("wrote %s %ux%u ch=%d (%zu bytes raw) in %ld ms",
         path, w, h, channels, pixelBytes, writeMs);
    return true;
}

} // namespace

extern "C" JNIEXPORT jintArray JNICALL
Java_com_smartvision_gallery_decoder_bridge_NativeBridge_nativeJxlDecodeToRawFile(
    JNIEnv* env, jclass, jint fd, jlong len, jstring rawPath
) {
    if (fd < 0 || len <= 0 || rawPath == nullptr) {
        LOGW("invalid args fd=%d len=%lld", fd, (long long)len);
        return nullptr;
    }
    const char* path = env->GetStringUTFChars(rawPath, nullptr);
    if (path == nullptr) return nullptr;

    // Cap the mmap to 256 MB — a 32K JXL bitstream is well under that.
    size_t mapLen = (size_t)len;
    const size_t MMAP_CAP = 256UL * 1024 * 1024;
    if (mapLen > MMAP_CAP) mapLen = MMAP_CAP;
    void* data = mmap(nullptr, mapLen, PROT_READ, MAP_SHARED, fd, 0);
    if (data == MAP_FAILED) {
        LOGW("mmap failed errno=%d", errno);
        env->ReleaseStringUTFChars(rawPath, path);
        return nullptr;
    }

    uint32_t w = 0, h = 0;
    int channels = 3;
    long decMs = -1, writeMs = -1;
    auto pixels = decodeJxlPixels(
        static_cast<const uint8_t*>(data), mapLen, &w, &h, &channels, &decMs);
    munmap(data, mapLen);

    jintArray result = nullptr;
    if (pixels && writeRawFile(*pixels, w, h, channels, path, &writeMs)) {
        // Timing sidecar — logcat is rate-limited by ColorOS (per-proc quota),
        // so persist decode/write split next to the raw for on-device reads.
        std::string tpath = std::string(path) + ".timing";
        FILE* tf = std::fopen(tpath.c_str(), "w");
        if (tf != nullptr) {
            std::fprintf(tf, "decode_ms=%ld write_ms=%ld total_ms=%ld\n",
                         decMs, writeMs, decMs + writeMs);
            std::fclose(tf);
        }
        result = env->NewIntArray(3);
        if (result != nullptr) {
            jint dims[3] = { (jint)w, (jint)h, (jint)channels };
            env->SetIntArrayRegion(result, 0, 3, dims);
        }
        LOGI("OK %ux%u ch=%d", w, h, channels);
    } else {
        LOGW("decode failed");
    }
    env->ReleaseStringUTFChars(rawPath, path);
    return result;
}

#else // !SMARTVISION_REAL_JXL_DECODER

extern "C" JNIEXPORT jintArray JNICALL
Java_com_smartvision_gallery_decoder_bridge_NativeBridge_nativeJxlDecodeToRawFile(
    JNIEnv*, jclass, jint, jlong, jstring
) {
    return nullptr;
}

#endif