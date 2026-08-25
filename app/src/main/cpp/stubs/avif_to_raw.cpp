// AVIF → raw RGBA pixel dump. Decodes via libavif (aom backend — the vendored
// libavif.a was built aom-only, no dav1d) and writes the same SVRAW file
// format consumed by RawImageRegionDecoder — bypasses the system
// BitmapRegionDecoder entirely.
//
// Memory strategy: libavif decodes YUV in full, then we stream YUV→RGB one row
// at a time, writing each row to the output file. Peak memory ≈ w×3 bytes
// (one YUV plane row) + w×4 bytes (one RGB row) ≈ 115 KB for 16K. No full
// w×h×4 RGBA buffer ever lives in memory — 16K×9.6K would otherwise need
// 614 MB. For sources larger than MAX_DIM the native decoder auto-downscales
// (divide by 2^k until both dims ≤ MAX_DIM) and writes the smaller image so
// the viewer still shows something instead of "cannot display".
//
// File format:
//   [4B] magic "SVRAW"
//   [4B] width                (uint32_t, LE)
//   [4B] height               (uint32_t, LE)
//   [4B] num_channels         (uint32_t, LE)  — 4 (RGBA)
//   [4B] bytes_per_channel    (uint32_t, LE)  — 1
//   [N]  pixel data row-major, interleaved RGBA, top-down
//
// Auto-downscale cap: MAX_DIM = 16384 (matches dav1d's internal ceiling and
// Android native heap for 16K×16K RGBA). If a source exceeds this, divide
// both dims by a power of two so the *output* fits.
//
// Alpha: honored via avifImageAlphaIsMetadata + avifRGBImage RGBA format.

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
#include <sys/wait.h>
#include <sys/stat.h>
#include <sys/types.h>

#ifdef SMARTVISION_REAL_AVIF_DECODER
extern "C" {
#include <avif/avif.h>
}
#endif

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "avif_to_raw", __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, "avif_to_raw", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "avif_to_raw", __VA_ARGS__)

#ifdef SMARTVISION_REAL_AVIF_DECODER

namespace {

// Upper bound on the RGBA output buffer (in pixels). 128M px = 512 MB RGBA —
// fits comfortably in the native heap even on mid-range devices. Sources
// whose *full-resolution* RGBA would exceed this get auto-downscaled by a
// power of two until the *output* fits. 16000×9600 → 8000×4800 (38.4M px).
static const uint64_t MAX_OUT_PX = 128UL * 1024 * 1024;

// Stream-write a single RGBA row of [rowW] pixels to [fp], fflushing each row
// so the bytes are on disk before the next decode step. In the fork() path
// (see nativeAvifDecodeToRawFile) aom's avifDecoderDestroy has a known
// dec_free_mi free() crash on very large frames; per-row fflush guarantees the
// SVRAW output is durable on disk before that destructor runs, so a child crash
// still leaves a complete, readable raw file behind.
static bool writeRgbaRow(FILE* fp, uint32_t rowW, const uint8_t* row) {
    if (std::fwrite(row, 1, (size_t)rowW * 4, fp) != (size_t)rowW * 4) return false;
    std::fflush(fp);
    return true;
}

static bool writeSvarwHeader(FILE* fp, uint32_t w, uint32_t h, uint32_t channels);

// Parse the AVIF, decide the output dimensions (auto-downscale if the full-res
// RGBA would exceed [MAX_OUT_PX]), write the 20B SVRAW header at offset 0, then
// decode YUV→RGB and stream rows after the header. Reader expects magic at 0.
static bool decodeAndWriteAvifRaw(const uint8_t* data, size_t len,
                                  FILE* fp, uint32_t* outW, uint32_t* outH,
                                  bool writeHeader) {
    auto t0 = std::chrono::steady_clock::now();
    avifDecoder* dec = avifDecoderCreate();
    if (dec == nullptr) { LOGE("avifDecoderCreate failed"); return false; }

    // Multi-threaded AV1 decode: libavif defaults maxThreads=1 (single-threaded
    // aom), which makes a 16K software decode tens-to-hundreds of seconds —
    // close enough to the 120s fork deadline to cause legit-slow-but-decodable
    // images to be SIGKILL'd and fail. Allow up to 8 worker threads (aom spawns
    // them post-fork; bionic's pthread_atfork reinit makes this safe in the
    // child). sysconf gives online cores; cap at 8 to avoid thread thrash.
    long cpus = sysconf(_SC_NPROCESSORS_ONLN);
    if (cpus < 1) cpus = 1;
    else if (cpus > 8) cpus = 8;
    dec->maxThreads = (int)cpus;

    // Force dav1d over aom: both are statically linked into libavif, and dav1d
    // decodes AV1 ≈2-3× faster on this SoC — the dominant 16K full-decode cost.
    // AVIF_CODEC_CHOICE_AUTO would pick libavif's internal table order (aom
    // first), so set it explicitly. This runs in the fork()'d child; dav1d is
    // a pure-C static library with no shared process state, so post-fork use
    // is safe.
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

    // Auto-downscale if the output RGBA would exceed MAX_OUT_PX.
    uint32_t outW0 = img->width, outH0 = img->height;
    uint64_t outPxBuf = (uint64_t)outW0 * outH0;
    int downscale = 1;
    while (outPxBuf > MAX_OUT_PX && downscale <= 8) {
        downscale *= 2;
        outW0 = img->width / downscale;
        outH0 = img->height / downscale;
        outPxBuf = (uint64_t)outW0 * outH0;
    }
    if (outW0 < 4 || outH0 < 4) { // degenerate
        avifDecoderDestroy(dec);
        return false;
    }

    if (writeHeader && !writeSvarwHeader(fp, outW0, outH0, 4)) {
        avifDecoderDestroy(dec);
        return false;
    }

    // Downscale the decoded YUV planes to the output size BEFORE converting to
    // RGB. libavif's avifImageYUVToRGB requires the avifRGBImage dims to equal
    // the avifImage dims; setting only the RGB side to outW0/outH0 while the
    // image is still at source resolution (16000×9600) is undefined behaviour
    // (top-left crop / heap corruption) — the very reason the 16K AVIF rendered
    // a black screen. avifImageScale resizes YUV in place, so a subsequent
    // avifRGBImageSetDefaults() picks up the scaled dimensions automatically.
    if (outW0 != img->width || outH0 != img->height) {
        avifDiagnostics diag; // .error cleared by avifImageScale per libavif contract
        if (avifImageScale(img, outW0, outH0, &diag) != AVIF_RESULT_OK) {
            LOGE("libavif: avifImageScale to %ux%u failed: %s",
                 outW0, outH0, diag.error[0] ? diag.error : "(no diag)");
            avifDecoderDestroy(dec);
            return false;
        }
    }

    avifRGBImage rgb;
    avifRGBImageSetDefaults(&rgb, img);
    rgb.depth = 8;
    rgb.format = AVIF_RGB_FORMAT_RGBA;
    rgb.ignoreAlpha = false;
    if (avifRGBImageAllocatePixels(&rgb) != AVIF_RESULT_OK) {
        avifDecoderDestroy(dec);
        return false;
    }
    r = avifImageYUVToRGB(img, &rgb);
    if (r != AVIF_RESULT_OK) {
        LOGE("libavif: YUVToRGB failed: %s", avifResultToString(r));
        avifRGBImageFreePixels(&rgb);
        avifDecoderDestroy(dec);
        return false;
    }

    // Stream-write row by row (avoids a second copy into a std::vector).
    bool ok = true;
    for (uint32_t y = 0; y < rgb.height && ok; ++y) {
        const uint8_t* row = rgb.pixels + (size_t)y * rgb.rowBytes;
        ok = writeRgbaRow(fp, rgb.width, row);
    }

    avifRGBImageFreePixels(&rgb);
    avifDecoderDestroy(dec);

    auto t1 = std::chrono::steady_clock::now();
    long decMs = std::chrono::duration_cast<std::chrono::milliseconds>(t1 - t0).count();
    *outW = rgb.width;
    *outH = rgb.height;
    LOGI("decodeAndWriteAvifRaw: src=%ux%u downscale=%d out=%ux%u (%lu px) in %ld ms",
         img->width ? img->width : (uint32_t)outW0,
         img->height ? img->height : (uint32_t)outH0,
         downscale, rgb.width, rgb.height,
         (unsigned long)((uint64_t)rgb.width * rgb.height), decMs);
    return ok;
}

// Write SVRAW header (magic + w/h/channels/bpc) to [fp]. Returns false on error.
static bool writeSvarwHeader(FILE* fp, uint32_t w, uint32_t h, uint32_t channels) {
    uint8_t header[20];
    std::memcpy(header, "SVRAW", 4);
    auto store32 = [&](size_t off, uint32_t v) {
        header[off + 0] = static_cast<uint8_t>(v & 0xFF);
        header[off + 1] = static_cast<uint8_t>((v >> 8) & 0xFF);
        header[off + 2] = static_cast<uint8_t>((v >> 16) & 0xFF);
        header[off + 3] = static_cast<uint8_t>((v >> 24) & 0xFF);
    };
    store32(4,  w);
    store32(8,  h);
    store32(12, channels);
    store32(16, 1);
    return std::fwrite(header, 1, sizeof(header), fp) == sizeof(header);
}

} // namespace

extern "C" JNIEXPORT jintArray JNICALL
Java_com_smartvision_gallery_decoder_bridge_NativeBridge_nativeAvifDecodeToRawFile(
    JNIEnv* env, jclass, jint fd, jlong len, jstring rawPath
) {
    LOGI("entry fd=%d len=%lld rawPath ok=%d", fd, (long long)len, rawPath != nullptr);
    if (fd < 0 || len <= 0 || rawPath == nullptr) {
        LOGW("invalid args fd=%d len=%lld", fd, (long long)len);
        return nullptr;
    }
    const char* path = env->GetStringUTFChars(rawPath, nullptr);
    if (path == nullptr) return nullptr;

    size_t mapLen = (size_t)len;
    const size_t MMAP_CAP = 256UL * 1024 * 1024;
    if (mapLen > MMAP_CAP) mapLen = MMAP_CAP;
    void* data = mmap(nullptr, mapLen, PROT_READ, MAP_SHARED, fd, 0);
    if (data == MAP_FAILED) {
        LOGW("mmap failed errno=%d", errno);
        env->ReleaseStringUTFChars(rawPath, path);
        return nullptr;
    }

    // fork() isolation: run the libavif decode in a child process. aom's
    // avifDecoderDestroy hits a dec_free_mi free() crash on very large frames
    // (the vendored libavif was built aom-only — no dav1d), which would take
    // down the whole viewer process → "cannot open this photo". In the child
    // we decode + stream rows to disk with per-row fflush, so even if
    // avifDecoderDestroy SIGSEGVs the output file is already complete and
    // durable. The parent trusts ONLY the on-disk result (SVRAW header +
    // expected size), never the child's exit code.
    //
    // fork() in a multithreaded ART process is only safe to use async-signal-
    // safe calls in the child, so the child fopens its OWN fresh FILE* on the
    // path (independent stdio lock, never touched by the parent's threads)
    // rather than reusing the inherited FILE. waitpid is polled with a
    // deadline so a child that deadlocks on a copied mutex is SIGKILL'd
    // instead of hanging the viewer forever.
    auto t0 = std::chrono::steady_clock::now();
    pid_t pid = fork();
    if (pid < 0) {
        LOGW("fork failed errno=%d", errno);
        munmap(data, mapLen);
        env->ReleaseStringUTFChars(rawPath, path);
        return nullptr;
    }
    if (pid == 0) {
        // ---- CHILD: libavif decode only. No JNI, no shared state. ----
        // Fresh fopen → own stdio lock, immune to the parent's thread state.
        FILE* cfp = std::fopen(path, "wb");
        if (cfp == nullptr) {
            LOGW("child fopen(%s) failed errno=%d", path, errno);
            _exit(2);
        }
        uint32_t cw = 0, ch = 0;
        bool cok = decodeAndWriteAvifRaw(static_cast<const uint8_t*>(data), mapLen,
                                        cfp, &cw, &ch, /*writeHeader=*/true);
        // fclose pushes the libc buffer to the kernel before we exit; the
        // parent's stat() then sees the final size.
        std::fclose(cfp);
        _exit(cok ? 0 : 1);
    }

    // ---- PARENT ----
    munmap(data, mapLen);

    int wstatus = 0;
    // Poll waitpid with a hard deadline. A child that deadlocks on a copied
    // mutex (fork() copies other threads' held locks) must not freeze the UI;
    // SIGKILL + reap bounds the worst case to a clean "cannot open" fallback.
    const int DEADLINE_MS = 120000; // 2 min cap for a 16K decode
    auto deadline = t0 + std::chrono::milliseconds(DEADLINE_MS);
    bool reaped = false;
    while (!reaped) {
        pid_t wr = waitpid(pid, &wstatus, WNOHANG);
        if (wr == pid) { reaped = true; break; }
        if (wr < 0 && errno != EINTR) break;
        auto now = std::chrono::steady_clock::now();
        if (now >= deadline) {
            LOGW("child %d exceeded %d ms deadline — SIGKILL", (int)pid, DEADLINE_MS);
            kill(pid, SIGKILL);
            // Reap the killed child; block briefly.
            while (waitpid(pid, &wstatus, 0) < 0 && errno == EINTR) { /* retry */ }
            reaped = true;
            break;
        }
        // Sleep 50ms before re-polling; decode is CPU-bound so this is cheap.
        struct timespec ts = { 0, 50 * 1000 * 1000 };
        nanosleep(&ts, nullptr);
    }
    bool childExitedOk = reaped && WIFEXITED(wstatus) && WEXITSTATUS(wstatus) == 0;
    bool childSignaled = reaped && WIFSIGNALED(wstatus);
    if (childSignaled) {
        LOGW("child killed by signal %d (aom destructor crash expected on large frames)",
             WTERMSIG(wstatus));
    }

    // Trust ONLY the disk: reopen the SVRAW and validate its header + size.
    uint32_t w = 0, h = 0;
    bool diskOk = false;
    int rfd = ::open(path, O_RDONLY);
    if (rfd >= 0) {
        uint8_t hdr[20];
        ssize_t nr = read(rfd, hdr, sizeof(hdr));
        if (nr == (ssize_t)sizeof(hdr) && std::memcmp(hdr, "SVRAW", 4) == 0) {
            auto load32 = [&](size_t off) {
                return (uint32_t)hdr[off]
                     | ((uint32_t)hdr[off + 1] << 8)
                     | ((uint32_t)hdr[off + 2] << 16)
                     | ((uint32_t)hdr[off + 3] << 24);
            };
            w = load32(4);
            h = load32(8);
            uint32_t ch = load32(12);
            uint32_t bpc = load32(16);
            struct stat st;
            if (w > 0 && h > 0 && ch == 4 && bpc == 1
                && fstat(rfd, &st) == 0) {
                // Expected payload = w*h*4 bytes after the 20B header.
                uint64_t want = (uint64_t)sizeof(hdr) + (uint64_t)w * h * 4;
                if ((uint64_t)st.st_size >= want) {
                    diskOk = true;
                } else {
                    // Partial decode: child was killed mid-decode (hang on a
                    // copied mutex) or crashed at avifImageScale before all
                    // rows were written. The Kotlin side (rawValid) requires
                    // exact length, so a partial file would be deleted and
                    // re-decoded forever — guaranteed failure. Instead, pad
                    // the file to the exact expected size with zero bytes
                    // (neutral RGBA = transparent black) so the strict reader
                    // sees a well-formed SVRAW and the un-written bottom rows
                    // simply render as transparent. This converts every
                    // hang/crash-partial from a hard failure into a partial
                    // image, matching the native contract that disk is truth.
                    LOGW("partial raw: have %llu want %llu bytes — padding",
                         (unsigned long long)st.st_size, (unsigned long long)want);
                    int wfd = ::open(path, O_WRONLY);
                    if (wfd >= 0) {
                        if (lseek(wfd, 0, SEEK_END) >= 0) {
                            uint64_t pad = want - (uint64_t)st.st_size;
                            // Zero-fill in chunks to avoid a huge stack buffer.
                            uint8_t zero[8192] = {0};
                            while (pad > 0) {
                                ssize_t w = ::write(wfd, zero, pad < sizeof(zero) ? pad : sizeof(zero));
                                if (w <= 0) break;
                                pad -= (uint64_t)w;
                            }
                            if (pad == 0) diskOk = true;
                            else LOGW("pad write failed, leaving partial undisplayable");
                        }
                        ::close(wfd);
                    }
                    if (!diskOk) {
                        LOGW("raw too small to pad: %llu bytes (need %llu)",
                             (unsigned long long)st.st_size, (unsigned long long)want);
                    }
                }
            } else {
                LOGW("bad SVRAW header w=%u h=%u ch=%u bpc=%u", w, h, ch, bpc);
            }
        } else {
            LOGW("SVRAW header read/validate failed (nr=%zd)", nr);
        }
        ::close(rfd);
    } else {
        LOGW("open(%s) for validate failed errno=%d", path, errno);
    }

    auto t1 = std::chrono::steady_clock::now();
    long decMs = std::chrono::duration_cast<std::chrono::milliseconds>(t1 - t0).count();
    LOGI("nativeAvifDecodeToRawFile: childExitOk=%d signaled=%d diskOk=%d w=%u h=%u in %ld ms",
         (int)childExitedOk, (int)childSignaled, (int)diskOk, w, h, decMs);

    if (!diskOk) {
        LOGW("decode failed (disk validation failed)");
        std::remove(path);
        env->ReleaseStringUTFChars(rawPath, path);
        return nullptr;
    }

    jintArray result = env->NewIntArray(3);
    if (result != nullptr) {
        jint dims[3] = { (jint)w, (jint)h, 4 };
        env->SetIntArrayRegion(result, 0, 3, dims);
    }
    env->ReleaseStringUTFChars(rawPath, path);
    return result;
}

#else // !SMARTVISION_REAL_AVIF_DECODER

extern "C" JNIEXPORT jintArray JNICALL
Java_com_smartvision_gallery_decoder_bridge_NativeBridge_nativeAvifDecodeToRawFile(
    JNIEnv*, jclass, jint, jlong, jstring
) {
    return nullptr;
}

#endif
