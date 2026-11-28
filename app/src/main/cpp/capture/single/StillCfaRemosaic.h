#pragma once
#include <android/log.h>
#if defined(__ANDROID__)
#include <sys/system_properties.h>
#endif

#include <algorithm>
#include <array>
#include <chrono>
#include <optional>
#include <thread>

#include "capture/BlockRemosaicGpu.h"
#include "capture/ReducedRawMetadata.h"
#include "imaging/CfaRemosaic.h"
#include "imaging/CfaRemosaicFast.h"
#include "imaging/QuadConvert.h"
#include "imaging/QuadDisparity.h"
#include "imaging/QuadGainField.h"
#include "imaging/RawSnapshot.h"
#include "capture/single/StillBinning.h"

namespace rawrcam::capture {
// The photo remosaic quality a lens asked for. Detail is quad only.
inline imaging::CfaRemosaicQuality photoRemosaicQuality(const metadata::CameraContextMetadata& context) {
    using Q = imaging::CfaRemosaicQuality;
    const Q quality = context.cfaPhotoQuality == 3u ? Q::Detail : context.cfaPhotoQuality == 2u ? Q::High : Q::Standard;
    return quality == Q::Detail && context.cfaBlock != 2u ? Q::High : quality;
}
inline const char* remosaicQualityName(imaging::CfaRemosaicQuality quality) {
    return quality == imaging::CfaRemosaicQuality::Detail ? "detail"
           : quality == imaging::CfaRemosaicQuality::High ? "high"
                                                          : "standard";
}
// A quad lens with "Correct per-position response" (photoPhaseFit): the
// sensor's per-position gain field, fitted from this frame (QuadGainField.h)
// unless `cache` already holds one; a burst passes the same cache for every
// frame, so it fits once (the field belongs to the sensor, not the scene).
inline std::optional<imaging::QuadGainField> quadGainFieldFor(const std::vector<uint8_t>& raw16, uint32_t width,
                                                              uint32_t height,
                                                              const metadata::CameraContextMetadata& context,
                                                              float black, float white,
                                                              std::optional<imaging::QuadGainField>* cache) {
    if (!context.photoPhaseFit || context.cfaBlock != 2u) return std::nullopt;
    if (cache && *cache) return *cache;
    if (!(white > black)) white = context.baselineWhiteLevel;
    const auto start = std::chrono::steady_clock::now();
    const uint32_t threads = std::clamp(std::thread::hardware_concurrency(), 1u, 8u);
    auto field = imaging::fitQuadGainField(reinterpret_cast<const uint16_t*>(raw16.data()), width, height, black,
                                           white, threads);
    [[maybe_unused]] const long long us =
        std::chrono::duration_cast<std::chrono::microseconds>(std::chrono::steady_clock::now() - start).count();
    __android_log_print(ANDROID_LOG_INFO, "RawrCamNative",
                        "QUAD_GAIN_FIELD active=%d deviation=%.3f%% noise=%.3f%% pixel_noise=%.3f*level+%.2f fit_us=%lld",
                        field.active ? 1 : 0, 100.0f * field.deviation, 100.0f * field.noise, field.noiseSlope,
                        field.noiseFloor, us);
    if (cache) *cache = field;
    return field;
}
// A quad lens with "Correct per-position response" also gets the phase-detect
// disparity correction (QuadDisparity.h): the same sensors put one lens over
// each 2x2 block, and on a soft edge the halves of a block disagree.
inline bool quadDisparityFor(const metadata::CameraContextMetadata& context) {
    return context.photoPhaseFit && context.cfaBlock == 2u;
}
// Remosaics one block-CFA RAW16 buffer to Bayer in place: on the GPU when the
// caller holds a BlockRemosaicGpu that supports the lens, otherwise (or when
// the GPU run throws) on the CPU. Returns true when the GPU did it. `white` is
// the raw white level (for the gain field; 0 = the lens baseline).
inline bool remosaicBlockCfaBuffer(std::vector<uint8_t>& raw16, uint32_t width, uint32_t height,
                                   const metadata::CameraContextMetadata& context, const std::array<float, 4>& black,
                                   BlockRemosaicGpu* gpu, float white = 0.0f,
                                   std::optional<imaging::QuadGainField>* fieldCache = nullptr) {
    const auto quality = photoRemosaicQuality(context);
    const float blackMean = 0.25f * (black[0] + black[1] + black[2] + black[3]);
    const auto field = quadGainFieldFor(raw16, width, height, context, blackMean, white, fieldCache);
    const imaging::QuadGainField* activeField = field && field->active ? &*field : nullptr;
    const bool disparity = quadDisparityFor(context);
    // The same on-chip-lens sensors need the thin-feature stage after Detail (imaging/QuadConvert.h).
    const bool thinStage = disparity && quality == imaging::CfaRemosaicQuality::Detail && width % 4 == 0 && height % 4 == 0 &&
                           width >= 32 && height >= 32;
    if (gpu && BlockRemosaicGpu::supports(context.cfaBlock, quality)) {
        try {
            gpu->run(raw16, context.cfaBlock, context.rawPreviewCfa, quality, blackMean, field ? &*field : nullptr,
                     disparity, thinStage);
            return true;
        } catch (const BlockRemosaicGpu::PartialFailure&) {
            throw;  // some rows are already Bayer: the CPU path must not run over them
        } catch (const std::exception& e) {
            __android_log_print(ANDROID_LOG_WARN, "RawrCamNative", "BLOCK_REMOSAIC_GPU_FALLBACK error=%s", e.what());
        }
    }
    // All cores: 12 MP takes ~115 ms (Standard) / ~140-175 ms (High) at 8 threads on V2562.
    const uint32_t threads = std::clamp(std::thread::hardware_concurrency(), 1u, 8u);
    if (activeField)
        imaging::applyQuadGainField(reinterpret_cast<uint16_t*>(raw16.data()), width, height, blackMean, *activeField,
                                    threads);
    if (disparity)
        imaging::correctQuadDisparity(reinterpret_cast<uint16_t*>(raw16.data()), width, height, context.rawPreviewCfa,
                                      blackMean, field ? field->noiseSlope : 0.0f, field ? field->noiseFloor : 0.0f,
                                      threads);
    std::vector<uint8_t> quad;
    if (thinStage) quad = raw16;  // the corrected quad the stage reads
    imaging::remosaicBlockCfa(raw16, width, height, context.cfaBlock, context.rawPreviewCfa, threads, quality, 1.0f,
                              blackMean);
    if (thinStage) {
        std::vector<uint8_t> out(raw16.size());
        imaging::quadConvert(reinterpret_cast<const uint16_t*>(quad.data()), reinterpret_cast<const uint16_t*>(raw16.data()),
                             reinterpret_cast<uint16_t*>(out.data()), width, height, context.rawPreviewCfa, blackMean, threads);
        raw16.swap(out);
    }
    return false;
}
// A debug system property whose first character is `value` (always false in host tests).
inline bool stillDebugPropIs(const char* name, char value) {
#if defined(__ANDROID__)
    char buffer[PROP_VALUE_MAX] = {};
    return __system_property_get(name, buffer) > 0 && buffer[0] == value;
#else
    (void)name;
    (void)value;
    return false;
#endif
}

// A block-CFA lens (CameraContextMetadata::cfaBlock > 1) hands stills over as
// the block mosaic; remosaic the copy to Bayer before anything (journal, DNG,
// develop) reads it, like ingress does for the viewfinder. `gpu` (optional)
// does it on the GPU.
inline void remosaicBlockCfaStill(imaging::RawSnapshot& frame, BlockRemosaicGpu* gpu = nullptr) {
    const auto& context = frame.metadata.cameraContext;
    if (!context || context->cfaBlock <= 1u || reducedRawRequested(*context)) return;
    // Debug: `adb shell setprop debug.rawr.keep_block_cfa 1` keeps the raw block mosaic in the DNG
    // (looks pink in editors) so remosaic methods can be compared offline on real scenes.
    if (stillDebugPropIs("debug.rawr.keep_block_cfa", '1')) {
        __android_log_print(ANDROID_LOG_WARN, "RawrCamNative", "STILL_CFA_REMOSAIC_SKIPPED requestId=%llu (debug.rawr.keep_block_cfa)",
                            static_cast<unsigned long long>(frame.requestId));
        return;
    }
    const auto start = std::chrono::steady_clock::now();
    const auto quality = photoRemosaicQuality(*context);
    // 4x4 blocks at Standard (Xiaomi 200 MP): the generic remosaic takes ~16 s there, the fast path well under 1 s.
    const bool fast = context->cfaBlock == 4u && quality == imaging::CfaRemosaicQuality::Standard &&
                      frame.raw16.size() >= size_t(frame.width) * frame.height * 2u;
    [[maybe_unused]] long long jpegMs = -1;
    [[maybe_unused]] bool onGpu = false;
    if (fast) {
        const uint32_t threads = std::clamp(std::thread::hardware_concurrency(), 1u, 8u);
        const auto& b = context->baselineBlackLevelPhysicalRggb;
        auto* raw = reinterpret_cast<uint16_t*>(frame.raw16.data());
        const auto model = imaging::buildBlockCfaModel(raw, frame.width, frame.height, context->cfaBlock,
                                                       context->rawPreviewCfa, 0.25f * (b[0] + b[1] + b[2] + b[3]),
                                                       context->baselineWhiteLevel, threads);
        // Too large for a full-size JPEG (200 MP): its 50 MP Bayer comes straight from the blocks, before the
        // remosaic below rewrites them for the DNG. On real scenes this removes the stair-stepped edges and
        // checker texture of binning the remosaicked frame (+2.5 dB on synthetic scenes). A TV screen's
        // sub-pixels can show as moire. `adb shell setprop debug.rawr.jpeg_from_blocks 0` bins the remosaicked
        // frame instead, for comparison.
        const bool binRemosaicked = stillDebugPropIs("debug.rawr.jpeg_from_blocks", '0');
        if (jpegNeedsBinning(frame) && !binRemosaicked) {
            const auto jpegStart = std::chrono::steady_clock::now();
            auto half = halfSizeBayerSnapshot(frame);
            imaging::binBlockCfaToBayer(raw, model, reinterpret_cast<uint16_t*>(half->raw16.data()), threads);
            frame.jpegBayer = std::move(half);
            jpegMs = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - jpegStart)
                         .count();
        }
        imaging::remosaicBlockCfaWithModel(raw, model, threads);
    } else {
        // Ed's #164: the white level, so a 2x2 quad photo's per-position response can be fitted and divided out.
        onGpu = remosaicBlockCfaBuffer(frame.raw16, frame.width, frame.height, *context,
                                       frame.metadata.blackLevelPhysicalRggb, gpu, frame.metadata.effectiveWhiteLevel);
    }
    [[maybe_unused]] const long long ms =
        std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - start).count();
    __android_log_print(ANDROID_LOG_INFO, "RawrCamNative",
                        "STILL_CFA_REMOSAIC requestId=%llu raw=%ux%u block=%u quality=%s disparity=%d gpu=%d ms=%lld jpegBayerMs=%lld",
                        static_cast<unsigned long long>(frame.requestId), frame.width, frame.height, context->cfaBlock,
                        fast ? "fast" : remosaicQualityName(quality), quadDisparityFor(*context) ? 1 : 0, onGpu ? 1 : 0, ms,
                        jpegMs);
}
}  // namespace rawrcam::capture
