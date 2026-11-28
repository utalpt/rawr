#pragma once
// Rawr 17U fork: HDR+ Bracketed merge recorder - Steve's v26 patch, his code. A copy of Ed's HdrPlusRecorder.h with the
// bracket additions, in namespace hdrb (see src/HdrBracketRecorder.cpp). Built only with RAWR_HDRB.
#include <rawr/raw_gpu_pipeline/FrameParameters.h>
#include <rawr/raw_gpu_pipeline/ResourceArena.h>
#include <rawr/raw_gpu_pipeline/VulkanExecutor.h>
#include <rawr/raw_merge_hdrplus_gpu/RawMergeHdrPlusGpu.h>
#include <array>
#include <cstdint>
#include <functional>
#include <utility>
#include <vector>

namespace rawr::raw_gpu_pipeline::hdrb {

// Alignment and padding stages of the HDR+ Quality merge (the pad / pyramid / tile-alignment machinery of upstream's
// spatial path, whose per-frame merge is not used): padded frames, pyramids, tile alignment, finalize.

// The HDR+ Bracketed highlight layer as hdrb_finalize.comp reads it.
struct FinalizeUnder {
    bool on = false;
    std::int32_t originX = 0, originY = 0;  // output pixel + origin = the layer's texel * 2 (pass-0 padding minus the crop)
    float frameScale = 1.0f;                // fp16 storage scale of the layer
    float outScale = 1.0f;                  // output scale (< 1 when the layer extends the range)
    std::array<float, 4> neutral{{0.0f, 0.0f, 0.0f, 0.0f}};  // neutral-light raw ratios per CFA phase, green = 1 (0 = unknown)
};

class HdrPlusRecorder final {
   public:
    HdrPlusRecorder(ResourceArena& arena, VulkanExecutor& executor, rawr::raw_merge_hdrplus_gpu::Config config,
                    rawr::raw_merge_hdrplus_gpu::Geometry geometry);
    void setHotPixels(VkBuffer buffer, std::uint32_t count) noexcept {
        hotPixelBuffer_ = buffer;
        hotPixelCount_ = count;
    }
    // Scale applied to the next companion's black-subtracted signal
    // (reference exposure / companion exposure); 1 when they match.
    void setCompanionGain(float gain) noexcept { companionGain_ = gain; }
    [[nodiscard]] const std::array<float, 4>& referenceBlack() const noexcept { return reference_.blackByPhase; }
    [[nodiscard]] float referenceWhite() const noexcept { return reference_.whiteLevel; }
    // Reference padded frame and pyramid only (frequency merge passes).
    void recordReferencePrepare(VkCommandBuffer command, VkImageView rawU16, const RawNormalization& frame);
    // Per-pass padding offsets inside the fixed padded extent (frequency merge).
    void setPads(std::uint32_t left, std::uint32_t top) noexcept {
        geometry_.padLeft = left;
        geometry_.padTop = top;
    }
    // Companion padded frame only (frequency merge passes reusing alignment).
    void recordCompanionPadded(VkCommandBuffer command, VkImageView rawU16, const RawNormalization& frame);
    // Companion: padded frame and pyramid.
    void recordCompanionPrepare(VkCommandBuffer command, VkImageView rawU16, const RawNormalization& frame);
    // Companion: one coarse-to-fine alignment level against the reference
    // pyramid. Record levels levelCount()-1 down to 0 in order; each may be a
    // separate submission (bounds GPU stalls for interleaved preview work).
    [[nodiscard]] std::uint32_t levelCount() const noexcept { return std::uint32_t(geometry_.levels.size()); }
    // mark(k), when set, is called after upsample+correction (1) and tile cost (2).
    void recordCompanionAlignLevel(VkCommandBuffer command, std::uint32_t level,
                                   const std::function<void(std::uint32_t)>& mark = {});
    void recordFinalize(VkCommandBuffer command);
    // HDR+ Bracketed: the same, smoothing the noisy band along the sensor edge (hdrb_finalize.comp).
    void recordFinalizeBracket(VkCommandBuffer command, std::uint32_t band, float noiseSlope, float noiseOffset,
                               const FinalizeUnder& under);

   private:
    ResourceArena& arena_;
    VulkanExecutor& executor_;
    rawr::raw_merge_hdrplus_gpu::Config config_{};
    rawr::raw_merge_hdrplus_gpu::Geometry geometry_{};
    RawNormalization reference_{};
    VkBuffer hotPixelBuffer_ = VK_NULL_HANDLE;
    std::uint32_t hotPixelCount_ = 0;
    float companionGain_ = 1.0f;

    void recordPrepare(VkCommandBuffer, bool reference, VkImageView rawU16, const RawNormalization&);
    void recordPyramid(VkCommandBuffer, bool reference);
    // Separable binomial blur of a width x height window (x pass into tmp).
    void recordBlur(VkCommandBuffer, const ArenaImage& src, std::array<std::int32_t, 2> srcOffset,
                    const ArenaImage& tmp, const ArenaImage& dst, std::uint32_t width, std::uint32_t height,
                    std::int32_t kernelSize, std::int32_t stride, bool quantizeHalf);
};

// Scratch for the HDR+ Quality (frequency) merge: the alignment resources plus RGBA tiles, three spectra, per-tile statistics and the
// raw accumulator (~0.85 GB at 12.5 MP).
ScratchLayout makeHdrPlusFrequencyScratchLayout(const rawr::raw_merge_hdrplus_gpu::FrequencyGeometry& geometry,
                                                std::uint32_t width, std::uint32_t height);

// Records the HDR+ frequency-domain merge (upstream "Higher quality"): four
// half-tile-shifted passes; per pass the reference spectrum, then per
// companion alignment (via HdrPlusRecorder) and a per-frequency Wiener merge,
// then deconvolution, inverse transform and accumulation.
class HdrPlusFrequencyRecorder final {
   public:
    HdrPlusFrequencyRecorder(ResourceArena& arena, VulkanExecutor& executor, rawr::raw_merge_hdrplus_gpu::Config config,
                             rawr::raw_merge_hdrplus_gpu::FrequencyGeometry geometry);
    HdrPlusRecorder& alignment() noexcept { return align_; }
    // Uniform merges of frames whose exposure drifted (AE settling in the
    // ZSL ring): gains[i] = reference exposure / frame i exposure scales each
    // companion to the reference before alignment and merge, as upstream's
    // prepare_texture does; the merge itself stays the uniform one.
    void setExposureNormalization(std::vector<float> gains) { normalizationGains_ = std::move(gains); }
    // Exposure-bracketed burst: companions merge with the bracket shaders; weightTotal is the sum over all frames of
    // exposure / reference exposure (the reference counts 1), used instead of the frame count by the finish passes.
    // Most a bracket frame weighs in the merge (its exposure ratio, capped): 6 long frames at 64x would otherwise carry
    // ~95% of the image, so any blur or misalignment in them would show.
    static constexpr float kBracketMaxWeight = 16.0f;
    void setBracketed(bool on, float weightTotal) noexcept {
        bracketed_ = on;
        weightTotal_ = weightTotal;
    }
    // Noise variance of one RGBA channel in the merge's raw units: slope * (signal - black) + offset. Calibrates the
    // displacement statistic that gates a frame out of tiles where it moved, and the shrinkage of the reference there.
    void setNoise(float slope, float offset) noexcept {
        noiseSlope_ = slope;
        noiseOffset_ = offset;
    }
    void beginPass(std::uint32_t pass) noexcept;
    // Align-once mode: companions are aligned (and the reference prepared)
    // only in pass 0; later passes read through a coordinate offset.
    [[nodiscard]] bool alignsThisPass() const noexcept { return !config_.frequencyAlignOnce || pass_ == 0u; }
    void recordReference(VkCommandBuffer command, VkImageView rawU16, const RawNormalization& frame);
    // Companion for this pass: padded frame + pyramid + all alignment levels
    // when alignsThisPass() (storing the shifts in slot), else the padded frame.
    void recordCompanionAlign(VkCommandBuffer command, VkImageView rawU16, const RawNormalization& frame,
                              std::uint32_t slot);
    // The same as two submissions, so preview work can interleave: padded
    // frame, pyramid and the coarse levels; then the finest level (most of
    // the cost) and the shift store. Only when alignsThisPass().
    void recordCompanionAlignCoarse(VkCommandBuffer command, VkImageView rawU16, const RawNormalization& frame,
                                    std::uint32_t slot);
    void recordCompanionAlignFinest(VkCommandBuffer command, std::uint32_t slot);
    // mark(k), when set, is called after warp (1), mismatch+spectrum (2) and
    // mismatch normalization (3) for sub-stage GPU timestamps.
    void recordCompanionMerge(VkCommandBuffer command, std::uint32_t frameCount, std::uint32_t slot,
                              const std::function<void(std::uint32_t)>& mark = {});
    void recordPassFinish(VkCommandBuffer command, std::uint32_t frameCount);
    // After the last companion of a pass: copies the per-tile sum over companions of (1 - gate) (frames that merged)
    // into a host buffer of tileCount().first * second floats.
    void recordEffectiveFramesCopy(VkCommandBuffer command, VkBuffer host);
    [[nodiscard]] std::pair<std::uint32_t, std::uint32_t> tileCount() const noexcept;
    // Highlight layer: companions darker than the reference are not merged. Each is aligned, matched to the reference's
    // brightness and averaged into a layer that recordFinalize blends in where the merge reaches the reference's clip
    // point. outputScale (< 1) scales the whole output to hold the range the layer adds.
    void setUnder(std::uint32_t count, float outputScale) noexcept {
        underCount_ = count;
        outputScale_ = outputScale;
    }
    // Neutral-light raw ratios per CFA phase: lets the finalize make clipped highlights neutral (0 = unknown, no reconstruction).
    void setHighlightNeutral(const std::array<float, 4>& neutral) noexcept {
        neutral_ = neutral;
        neutralOn_ = neutral[0] > 0.0f && neutral[1] > 0.0f && neutral[2] > 0.0f && neutral[3] > 0.0f;
    }
    // Pass 0 only, after the frame's alignment: warp, brightness match and mean into the layer (index = frames before it).
    void recordCompanionUnder(VkCommandBuffer command, std::uint32_t slot, std::uint32_t index);
    void recordFinalize(VkCommandBuffer command) {
        if (bracketed_ || underCount_ > 0u || neutralOn_) {
            FinalizeUnder under{};
            under.on = underCount_ > 0u;
            under.originX = std::int32_t(geometry_.padLeft(0)) - std::int32_t(geometry_.cropX);
            under.originY = std::int32_t(geometry_.padTop(0)) - std::int32_t(geometry_.cropY);
            under.frameScale = frameScale();
            under.outScale = underCount_ > 0u ? outputScale_ : 1.0f;
            if (neutralOn_) under.neutral = neutral_;
            align_.recordFinalizeBracket(command, kBracketEdgeBand, noiseSlope_, noiseOffset_, under);
        } else {
            align_.recordFinalize(command);
        }
    }
    // Width of the sensor-edge band smoothed in a bracket merge (px): the merge there has no long-frame data to lean on.
    static constexpr std::uint32_t kBracketEdgeBand = 32u;
   private:
    ResourceArena& arena_;
    VulkanExecutor& executor_;
    rawr::raw_merge_hdrplus_gpu::Config config_{};
    rawr::raw_merge_hdrplus_gpu::FrequencyGeometry geometry_{};
    HdrPlusRecorder align_;
    std::uint32_t pass_ = 0;
    std::vector<float> normalizationGains_{};
    bool bracketed_ = false;
    std::uint32_t underCount_ = 0u;
    bool neutralOn_ = false;
    std::array<float, 4> neutral_{{0.0f, 0.0f, 0.0f, 0.0f}};
    float outputScale_ = 1.0f;
    float weightTotal_ = 0.0f;
    // Companion exposure / reference exposure (the inverse of its normalization gain).
    float frameExposureFactor(std::uint32_t slot) const noexcept {
        const float g = frameGain(slot);
        return g > 0.0f ? 1.0f / g : 1.0f;
    }
    float noiseSlope_ = 0.0f, noiseOffset_ = 1.0f;
    // Spectra are stored as fp16. A tile's DC bin sums up to 16 * white over the window and the running merge adds one
    // such term per frame, so the stored values are scaled to keep the burst sum far below the fp16 maximum while
    // small bins stay well above its subnormal range.
    // The reference and aligned frames are stored as fp16 (half the traffic of the mismatch and merge passes), scaled so
    // the largest raw value (any sensor range, with exposure gain headroom) stays far below the fp16 maximum; readers
    // multiply by 1 / frameScale() and work in raw units.
    float frameScale() const noexcept { return 2048.0f / std::max(align_.referenceWhite(), 1.0f); }
    float spectrumScale() const noexcept { return 8.0f / std::max(align_.referenceWhite(), 1.0f); }
    // Companion prepare gain: the drift normalization.
    float frameGain(std::uint32_t slot) const noexcept {
        return slot < normalizationGains_.size() ? normalizationGains_[slot] : 1.0f;
    }
    // Raw-pixel offset from this pass's padded coordinates into the prepared frame.
    std::array<std::int32_t, 2> passOffset() const noexcept;
    VkDeviceSize alignSlotBytes() const noexcept;
};

}  // namespace rawr::raw_gpu_pipeline::hdrb
