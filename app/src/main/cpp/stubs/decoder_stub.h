#pragma once

namespace smartvision::decoder_stub {

struct DecodeResult {
    int width = 0;
    int height = 0;
    int colorDepth = 8;
    bool isHdr = false;
};

// Placeholder routines — replaced once libavif / libjxl are vendored.
inline DecodeResult probe(const char* /*uri*/) { return {}; }
inline DecodeResult decodeAvif(const char* /*uri*/) { return {256, 256, 8, false}; }
inline DecodeResult decodeJxl(const char* /*uri*/) { return {256, 256, 8, false}; }

} // namespace smartvision::decoder_stub