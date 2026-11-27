#pragma once
#include <tinydng.h>

#include <array>
#include <cstdint>
#include <functional>
#include <mutex>
#include <optional>
#include <string>
#include <vector>

#include "SourceCaptureExif.h"
#include "color/DcpProfile.h"

namespace rawrcam::renderer {
// The CFA the editor reads a DNG with (Renderer RAW tab). Some apps write a
// sensor's block mosaic (Quad Bayer: 2x2 same-colour blocks, or 4x4) while
// tagging it 2x2 Bayer - read as Bayer it comes out magenta. layout: 0 auto
// (Rawr DNGs: Bayer; others: detected from the data), 1 Bayer, 2 quad 2x2,
// 4 block 4x4. order: -1 as tagged, else 0=RGGB 1=GRBG 2=GBRG 3=BGGR (for a
// block mosaic, the order of the blocks).
struct CfaOverride {
    uint32_t layout = 0;
    int32_t order = -1;
    // Cache-name suffix; empty for the defaults, so existing caches stay valid.
    std::string key() const;
};
// Pixel coordinates always refer to the uncropped, unrotated sensor raster.
class DngSource {
   public:
    explicit DngSource(const std::string& path, const CfaOverride& cfaOverride = {});
    ~DngSource();
    DngSource(const DngSource&) = delete;
    DngSource& operator=(const DngSource&) = delete;
    const tinydng_image_info& info() const { return *image_; }
    std::vector<uint16_t> read(uint32_t x, uint32_t y, uint32_t w, uint32_t h);
    std::vector<float> normalized(uint32_t x, uint32_t y, uint32_t w, uint32_t h, bool shading);
    // normalized() for one sensor sample, given its read() value: callers
    // that sample sparse sites skip the full-band arithmetic.
    float normalizedSample(uint16_t raw, uint32_t sx, uint32_t sy, bool shading) const;
    void prepareBinned(const std::string& path, bool shading, const std::function<void()>& check);
    std::vector<float> binnedNormalized(uint32_t x, uint32_t y, uint32_t w, uint32_t h, bool shading);
#ifndef NDEBUG
    // An offline replay may replace the pre-shading CFA while retaining all
    // metadata and the remaining production render stages of the source DNG.
    void setReplayCfa(const std::string& path);
#endif
    std::array<uint32_t, 4> crop{};  // x,y,width,height
    std::array<double, 4> black{};
    // DNG NoiseProfile ([S,O] per CFA channel, row-major 2x2) + white level
    // for profiled denoise. Count 0 = absent (denoise resolves to off).
    std::array<double, 8> noiseProfile{};
    uint16_t noiseProfileCount = 0;
    double whiteLevel = 0.0;
    std::array<float, 3> wb{1, 1, 1};
    std::array<float, 9> cameraToSrgb{};  // row-major, applied after wb
    color::DcpProfilePtr dcpProfile;
    float dcpWeight1 = 1.0f;
    uint32_t cfa = 0;
    // Block side the raw is remosaiced from (1 = Bayer as stored); `cfa` is
    // then the block order and read() returns Bayer in that order.
    uint32_t block = 1;
    // What `block` came from: "tagged", "detected" or "override".
    std::string cfaSource = "tagged";
    // Colour (0 R, 1 G, 2 B) at row-major 2x2 site i, from `cfa`: use this,
    // not info().cfa.pattern, which ignores an override.
    std::array<uint8_t, 4> pattern() const;
    uint32_t orientation = 1;
    std::string provenance;
    bool rawr = false;
    // Capture EXIF carried by the source DNG (image IFD, then IFD0), when
    // present. All optional so tagless files still render (the JPEG writer
    // omits absent tags, as on stills). Aperture prefers FNumber over APEX.
    const SourceCaptureExif& captureExif() const { return captureExif_; }
    std::optional<std::int64_t> exposureTimeNs() const { return captureExif_.exposureTimeNs; }
    std::optional<std::int32_t> sensitivity() const { return captureExif_.sensitivity; }
    std::optional<float> aperture() const { return captureExif_.fNumber; }
    size_t memoryPeak() const { return tinydng_context_memory_peak(context_); }

   private:
    const float* binCache_ = nullptr;
    size_t binCacheBytes_ = 0;
    std::string binCachePath_;
    tinydng_context* context_ = nullptr;
    tinydng_document* document_ = nullptr;
    const tinydng_image_info* image_ = nullptr;
    size_t index_ = 0;
    SourceCaptureExif captureExif_;
    bool rawCacheRequired_ = false;
    // Rawr 17U fork: 16-bit float CFA (LOFIC DNGs). read() returns the half
    // bits; normalizedSample() makes them float x floatScale_ (white 65535).
    bool floatSamples_ = false;
    float floatScale_ = 65535.0f;
    double rawWhite_ = 65535.0;
    static void sanitizeHalves(uint16_t* samples, size_t count);
    std::string rawCachePath_;
    const uint16_t* rawCache_ = nullptr;
    size_t rawCacheBytes_ = 0;
    // read() may run on several binning workers: guards the lazy raw-cache
    // build and tinydng region decodes.
    std::mutex decodeMutex_;
    // Auto layout on a DNG read through the raw cache: the block is found on
    // the decoded raster when the cache is built (ensureRawCache).
    bool detectPending_ = false;
    void ensureRawCache();
    std::vector<uint16_t> decodeRegion(uint32_t x, uint32_t y, uint32_t w, uint32_t h);
    // From the full decoded raster when given, else from a region decode.
    uint32_t detectBlock(const uint16_t* raster = nullptr);
    void checkBlockGeometry() const;
#ifndef NDEBUG
    std::vector<float> replayCfa_;
#endif
};
}  // namespace rawrcam::renderer
