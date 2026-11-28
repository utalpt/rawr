// Rawr 17U fork: HDR+ Bracketed merge recorder - Steve's v26 patch (rawr-17u16-bracketed-v26), his code.
// A copy of Ed's HdrPlusRecorder.cpp (HDR+ Quality alignment + frequency merge) with Steve's bracket changes, in its own
// namespace (hdrb), so Ed's HdrPlusRecorder.cpp and the HDR+ Quality merge stay exactly as upstream. Changes Ed makes to
// HdrPlusRecorder.cpp are not picked up here: port them by hand when the bracket merge should have them.
// Built only with RAWR_HDRB (app/build.gradle.kts, -Prawr.hdrb). Left out of Steve's version: the HDR+ (spatial) merge.
#include <rawr/raw_gpu_pipeline/HdrBracketRecorder.h>

#include <algorithm>
#include <cmath>
#include <stdexcept>
#include <string>
#include <utility>

namespace rawr::raw_gpu_pipeline::hdrb {
namespace {
namespace hp = rawr::raw_merge_hdrplus_gpu;

constexpr std::uint32_t divUp(std::uint32_t x, std::uint32_t y) { return (x + y - 1u) / y; }
ImageBinding ib(std::uint32_t b, const ArenaImage& i) { return {b, i.view, VK_IMAGE_LAYOUT_GENERAL}; }
BufferBinding bb(std::uint32_t b, const ArenaBuffer& buf) { return {b, buf.buffer, 0, buf.bytes}; }
std::string levelName(bool reference, std::size_t level) {
    return std::string(reference ? "hdrp_ref_l" : "hdrp_comp_l") + std::to_string(level);
}

struct PreparePc {
    float blackDelta[4];
    float refBlack[4];
    std::int32_t padX, padY, width, height, paddedWidth, paddedHeight;
    float gain;
};
// A companion this much darker than the reference (gain = reference exposure / its exposure) is an under frame: aligned against a
// reference that is clipped where the frame is not, its alignment image is clipped like the reference first (hdrp_avg_pool.comp:
// the level 0 of its pyramid only, the warp keeps the full range) and it keeps the inherited displacement where the alignment has
// nothing to decide by (hdrp_best_tile.comp, hdrp_correct_upsampling.comp).
constexpr float kUnderAlignGain = 1.25f, kUnderAlignClip = 0.985f;
// Push-constant structs mirror the GLSL blocks (ivec2 members are 8-byte aligned).
struct HotPixelPc {
    std::int32_t count, alignPad, padX, padY, width, height;
};
struct AvgPoolPc {
    std::int32_t width, height;
    float clip;
};
struct BlurPc {
    std::int32_t width, height, srcX, srcY, dstX, dstY, kernelSize, stride, direction, quantizeHalf;
};
struct UpsamplePc {
    std::int32_t srcX, srcY, dstX, dstY;
    float scaleX, scaleY;
};
struct TileCostPc {
    std::int32_t levelWidth, levelHeight, tilesX, tilesY, downscale, tileSize, useSsd, exposureRatio, preferPrior;
};
struct BestTilePc {
    std::int32_t tilesX, tilesY, downscale, preferPrior;
};
struct FinalizePc {
    float black[4];
    float white;
    std::int32_t alignPad, width, height;
};
struct FinalizeBracketPc {
    FinalizePc base;
    std::int32_t band;
    float lift, noiseSlope, noiseOffset, rangeScale;
    std::int32_t underOn, originX, originY;
    float underScale, outScale, hlLo, hlHi;
    float neutral[4];
    float underDetail;
};
struct UnderStorePc {
    std::int32_t width, height;
    float count;
};
// Raw levels (at the 14-bit range, 15359) the bracket merge's output sits below its reference; added back in hdrb_finalize.
// Storing the aligned frames black-subtracted (hdrb_warp_rgba.comp) removed the per-channel part of the merge's shortfall
// (R, G, B within 0.06 of each other) but left a uniform ~0.3 raw levels in the deep shadows, falling to ~0.1 by 30 levels.
// hdrb_finalize.comp adds it back, fading out with brightness.
constexpr float kBracketBlackLift = 0.3f;
// Highlight layer. It is blended in where the merged level is between these fractions of the range (the reference clips at 1).
constexpr float kUnderBlendLo = 0.80f, kUnderBlendHi = 0.96f;
// How far the under layer's own per-channel values may replace the neutral reconstruction in a clipped cell (hdrb_finalize.comp):
// 1 = wherever the layer agrees with the merged result around the cell, 0 = never (the earlier smoothed neutral level only).
constexpr float kUnderLayerDetail = 1.0f;
// Brightness match of an under frame to the reference, over the texels where both are clean (the frame, scaled to the
// reference, between these fractions of the range): its exposure ratio comes from shutter x ISO as reported, off by a percent
// or two, and a black offset is multiplied by the exposure gain.
constexpr float kUnderPhotLo = 0.05f, kUnderPhotHi = 0.65f, kUnderPhotMinSamples = 50000.0f, kUnderPhotAMin = 0.85f,
                kUnderPhotAMax = 1.15f, kUnderPhotBLimit = 24.0f;
}  // namespace


HdrPlusRecorder::HdrPlusRecorder(ResourceArena& arena, VulkanExecutor& executor, hp::Config config,
                                 hp::Geometry geometry)
    : arena_(arena), executor_(executor), config_(config), geometry_(std::move(geometry)) {
    if (geometry_.levels.empty()) throw std::invalid_argument("hdrplus recorder: empty geometry");
}

void HdrPlusRecorder::recordPrepare(VkCommandBuffer c, bool reference, VkImageView rawU16,
                                    const RawNormalization& frame) {
    const auto& dst = arena_.image(reference ? "hdrp_ref_padded" : "hdrp_comp_padded");
    PreparePc pc{};
    for (int i = 0; i < 4; ++i) {
        pc.blackDelta[i] = reference_.blackByPhase[i] - frame.blackByPhase[i];
        pc.refBlack[i] = reference_.blackByPhase[i];
    }
    pc.gain = reference ? 1.0f : companionGain_;
    pc.padX = std::int32_t(geometry_.padLeft);
    pc.padY = std::int32_t(geometry_.padTop);
    pc.width = std::int32_t(geometry_.width);
    pc.height = std::int32_t(geometry_.height);
    pc.paddedWidth = std::int32_t(geometry_.paddedWidth);
    pc.paddedHeight = std::int32_t(geometry_.paddedHeight);
    executor_.record(c, ShaderId::HdrpPrepare, {{{0u, rawU16, VK_IMAGE_LAYOUT_GENERAL}, ib(1, dst)}, {}}, &pc,
                     sizeof(pc), divUp(geometry_.paddedWidth, 16), divUp(geometry_.paddedHeight, 16));
    computeWriteBarrier(c);
    if (hotPixelBuffer_ && hotPixelCount_) {
        HotPixelPc hpc{std::int32_t(hotPixelCount_), 0, pc.padX, pc.padY, pc.width, pc.height};
        executor_.record(c, ShaderId::HdrpHotPixel, {{ib(0, dst)}, {BufferBinding{1u, hotPixelBuffer_}}}, &hpc,
                         sizeof(hpc), divUp(hotPixelCount_, 64), 1);
        computeWriteBarrier(c);
    }
}

void HdrPlusRecorder::recordBlur(VkCommandBuffer c, const ArenaImage& src, std::array<std::int32_t, 2> srcOffset,
                                 const ArenaImage& tmp, const ArenaImage& dst, std::uint32_t w, std::uint32_t h,
                                 std::int32_t kernelSize, std::int32_t stride, bool quantizeHalf) {
    BlurPc x{std::int32_t(w), std::int32_t(h), srcOffset[0], srcOffset[1], 0, 0, kernelSize, stride, 0,
             quantizeHalf ? 1 : 0};
    executor_.record(c, ShaderId::HdrpBlur, {{ib(0, src), ib(1, tmp)}, {}}, &x, sizeof(x), divUp(w, 16),
                     divUp(h, 16));
    computeWriteBarrier(c);
    BlurPc y = x;
    y.srcX = y.srcY = 0;
    y.direction = 1;
    executor_.record(c, ShaderId::HdrpBlur, {{ib(0, tmp), ib(1, dst)}, {}}, &y, sizeof(y), divUp(w, 16),
                     divUp(h, 16));
    computeWriteBarrier(c);
}

void HdrPlusRecorder::recordPyramid(VkCommandBuffer c, bool reference) {
    const auto& padded = arena_.image(reference ? "hdrp_ref_padded" : "hdrp_comp_padded");
    const auto& tmpA = arena_.image("hdrp_pyr_tmp_a");
    const auto& tmpB = arena_.image("hdrp_pyr_tmp_b");
    for (std::size_t i = 0; i < geometry_.levels.size(); ++i) {
        const auto& level = geometry_.levels[i];
        const auto& dst = arena_.image(levelName(reference, i));
        const ArenaImage* src = &padded;
        if (i > 0) {
            // Upstream build_pyramid: blur(previous level, pattern 1, kernel 2), then pool.
            const auto& prev = geometry_.levels[i - 1];
            recordBlur(c, arena_.image(levelName(reference, i - 1)), {0, 0}, tmpA, tmpB, prev.width, prev.height, 2,
                       1, true);
            src = &tmpB;
        }
        float clip = 0.0f;
        if (!reference && i == 0 && companionGain_ > kUnderAlignGain) {
            const float black = 0.25f * (reference_.blackByPhase[0] + reference_.blackByPhase[1] + reference_.blackByPhase[2] +
                                          reference_.blackByPhase[3]);
            clip = black + kUnderAlignClip * (reference_.whiteLevel - black);
        }
        AvgPoolPc pc{std::int32_t(level.width), std::int32_t(level.height), clip};
        executor_.record(c, ShaderId::HdrbAvgPool, {{ib(0, *src), ib(1, dst)}, {}}, &pc, sizeof(pc),
                         divUp(level.width, 16), divUp(level.height, 16));
        computeWriteBarrier(c);
    }
}

void HdrPlusRecorder::recordCompanionPadded(VkCommandBuffer c, VkImageView rawU16, const RawNormalization& frame) {
    recordPrepare(c, false, rawU16, frame);
}

void HdrPlusRecorder::recordReferencePrepare(VkCommandBuffer c, VkImageView rawU16, const RawNormalization& frame) {
    reference_ = frame;
    recordPrepare(c, true, rawU16, frame);
    recordPyramid(c, true);
}

void HdrPlusRecorder::recordCompanionPrepare(VkCommandBuffer c, VkImageView rawU16, const RawNormalization& frame) {
    recordPrepare(c, false, rawU16, frame);
    recordPyramid(c, false);
}

void HdrPlusRecorder::recordCompanionAlignLevel(VkCommandBuffer c, std::uint32_t n,
                                                const std::function<void(std::uint32_t)>& mark) {
    if (n >= geometry_.levels.size()) throw std::invalid_argument("hdrplus recorder: invalid alignment level");
    const auto& cur = arena_.buffer("hdrp_align_a");
    const auto& prev = arena_.buffer("hdrp_align_b");
    const auto& corrected = arena_.buffer("hdrp_align_c");
    const auto& cost = arena_.buffer("hdrp_tile_cost");
    const auto& level = geometry_.levels[n];
    const bool coarsest = n + 1u == geometry_.levels.size();
    // Alignment from the next-coarser level, in its (2x smaller) pixel units.
    const std::int32_t srcX = coarsest ? 1 : std::int32_t(geometry_.levels[n + 1u].tilesX);
    const std::int32_t srcY = coarsest ? 1 : std::int32_t(geometry_.levels[n + 1u].tilesY);
    const std::int32_t downscale = coarsest ? 0 : 2;
    UpsamplePc up{srcX, srcY, std::int32_t(level.tilesX), std::int32_t(level.tilesY),
                  float(double(level.tilesX) / double(srcX)), float(double(level.tilesY) / double(srcY))};
    executor_.record(c, ShaderId::HdrpUpsampleAlign, {{}, {bb(0, cur), bb(1, prev)}}, &up, sizeof(up),
                     divUp(level.tilesX, 16), divUp(level.tilesY, 16));
    computeWriteBarrier(c);
    const auto& refLevel = arena_.image(levelName(true, n));
    const auto& compLevel = arena_.image(levelName(false, n));
    TileCostPc tc{std::int32_t(level.width), std::int32_t(level.height), std::int32_t(level.tilesX),
                  std::int32_t(level.tilesY), downscale, std::int32_t(level.tileSize), n != 0u ? 1 : 0,
                  std::fabs(companionGain_ - 1.0f) > 1.0e-3f ? 1 : 0, companionGain_ > kUnderAlignGain ? 1 : 0};
    executor_.record(c, ShaderId::HdrbCorrectUpsampling,
                     {{ib(0, refLevel), ib(1, compLevel)}, {bb(2, prev), bb(3, corrected)}}, &tc, sizeof(tc),
                     level.tilesX, level.tilesY);
    computeWriteBarrier(c);
    if (mark) mark(1u);
    executor_.record(c, ShaderId::HdrpTileDiff, {{ib(0, refLevel), ib(1, compLevel)}, {bb(2, corrected), bb(3, cost)}},
                     &tc, sizeof(tc), level.tilesX, level.tilesY);
    computeWriteBarrier(c);
    if (mark) mark(2u);
    BestTilePc bt{std::int32_t(level.tilesX), std::int32_t(level.tilesY), downscale, companionGain_ > kUnderAlignGain ? 1 : 0};
    executor_.record(c, ShaderId::HdrbBestTile, {{}, {bb(0, cost), bb(1, corrected), bb(2, cur)}}, &bt, sizeof(bt),
                     divUp(level.tilesX, 16), divUp(level.tilesY, 16));
    computeWriteBarrier(c);
}



void HdrPlusRecorder::recordFinalize(VkCommandBuffer c) {
    FinalizePc fp{};
    for (int i = 0; i < 4; ++i) fp.black[i] = reference_.blackByPhase[i];
    fp.white = reference_.whiteLevel;
    fp.width = std::int32_t(geometry_.width);
    fp.height = std::int32_t(geometry_.height);
    executor_.record(c, ShaderId::HdrpFinalize,
                     {{ib(0, arena_.image("hdrp_accum")), ib(1, arena_.image("hdrp_output")),
                       ib(2, arena_.image("hdrp_cfa"))},
                      {}},
                     &fp, sizeof(fp),
                     divUp(geometry_.width, 16), divUp(geometry_.height, 16));
    computeWriteBarrier(c);
}

void HdrPlusRecorder::recordFinalizeBracket(VkCommandBuffer c, std::uint32_t band, float noiseSlope, float noiseOffset,
                                            const FinalizeUnder& under) {
    FinalizeBracketPc fp{};
    for (int i = 0; i < 4; ++i) fp.base.black[i] = reference_.blackByPhase[i];
    fp.base.white = reference_.whiteLevel;
    fp.base.width = std::int32_t(geometry_.width);
    fp.base.height = std::int32_t(geometry_.height);
    fp.band = std::int32_t(band);
    {
        const float meanBlack = .25f * (reference_.blackByPhase[0] + reference_.blackByPhase[1] + reference_.blackByPhase[2] +
                                        reference_.blackByPhase[3]);
        fp.rangeScale = std::max(reference_.whiteLevel - meanBlack, 1.0f) / 15359.0f;
        fp.lift = kBracketBlackLift * fp.rangeScale;
        fp.noiseSlope = noiseSlope;
        fp.noiseOffset = noiseOffset;
        fp.underOn = under.on ? 1 : 0;
        fp.originX = under.originX;
        fp.originY = under.originY;
        fp.underScale = 1.0f / std::max(under.frameScale, 1.0e-6f);
        fp.outScale = under.outScale;
        fp.hlLo = kUnderBlendLo;
        fp.hlHi = kUnderBlendHi;
        for (int i = 0; i < 4; ++i) fp.neutral[i] = under.neutral[std::size_t(i)];
        fp.underDetail = kUnderLayerDetail;
    }
    executor_.record(c, ShaderId::HdrbFinalize,
                     {{ib(0, arena_.image("hdrp_accum")), ib(1, arena_.image("hdrp_output")), ib(2, arena_.image("hdrp_cfa")),
                       ib(3, arena_.image("hdrb_under_rgba"))},
                      {}},
                     &fp, sizeof(fp), divUp(geometry_.width, 16), divUp(geometry_.height, 16));
    computeWriteBarrier(c);
}

namespace {
struct TilesPc {
    std::int32_t tilesX, tilesY;
};
struct ToRgbaPc {
    std::int32_t width, height, cropX, cropY, offsetX, offsetY, paddedWidth, paddedHeight;
    float scale;
};
struct RmsPc {
    std::int32_t tilesX, tilesY;
    float frameInv;
};
struct WarpRgbaPc {
    std::int32_t width, height, cropX, cropY, paddedWidth, paddedHeight, tilesX, tilesY, halfTileSize;
    std::int32_t alignPad;  // ivec2 offset is 8-byte aligned in the GLSL block
    std::int32_t offsetX, offsetY;
    std::int32_t continuous;
    float scale;
};
VkDeviceSize alignUp256(VkDeviceSize v) { return (v + 255u) / 256u * 256u; }
struct MismatchPc {
    std::int32_t tilesX, tilesY, rgbaWidth, rgbaHeight;
    float noiseSlope, noiseOffset, black, frameInv;
    float energyGain, energyDead, energyScale;
};
// HDR+ Bracketed: the uniform push constants plus the bracket extras (see hdrb_*.comp).
struct BracketWarpPc {
    WarpRgbaPc base;
    float exposureFactor, whiteLevel, blackLevelMean;
    std::int32_t sensorX0, sensorY0, sensorX1, sensorY1;
};
struct BracketMismatchPc {
    MismatchPc base;
    float exposureFactor, readFloor, meanSlack;
};
// Displacement-test allowances for a frame much brighter than the reference (hdrb_mismatch.comp), measured on a 14-bit raw
// burst (range 15359): the reference's near-black noise there is 5.1 raw-level^2, mostly coarse quantization and static
// pattern that the temporal fit cannot see, and the merged output sits 0.5 raw levels from the reference everywhere. Both
// scale with the sensor range, so another bit depth keeps the same proportions.
constexpr float kBracketReadFloor = 5.2f, kBracketMeanSlack = 0.75f, kBracketRefRange = 15359.0f;
struct WnormPc {
    std::int32_t tilesX, tilesY;
    float total;
};
struct RegionPc {
    std::int32_t originX, originY, width, height;
};
struct MismatchNormPc {
    std::int32_t tilesX, tilesY;
    float invFrames;
    float gateLo, gateHi;
};
struct FreqMergePc {
    std::int32_t tilesX, tilesY;
    float wienerNoise, maxMotionNorm, gateLo, gateHi, noiseSlope, noiseOffset, black, frameInv, spectrumScale, binNoise;
};
struct BracketFreqMergePc {
    FreqMergePc base;
    float frameWeight, exposureFactor;
};
// Wiener shrinkage of each frequency bin: weight = d^2 / (d^2 + kWienerNoise x the bin's noise power of the difference, 18 sigma^2
// per channel from the burst noise model at the tile's level). It replaced upstream's (tile RMS + read noise) x 64 x robustness
// norm, whose noise grew with the signal at a fixed rate whatever the gain. On synthetic bursts of known noise (12 frames,
// tmp/noisesyn) bright areas kept 4x less noise variance at high ISO and 13x at low ISO; at 16 both get ~16x, which is the old
// low-ISO behaviour the gates were tuned with: daylight bursts are unchanged (noise within 6%), a high-ISO indoor burst loses
// 26-49% of its noise, a moving square (also one 6 sigma above its background) stays out, gratings keep their contrast. 8 gave
// ~14x and +5-15% noise in daylight.
constexpr float kWienerNoise = 16.0f;
// A frame is gated out of a tile where the displacement statistic (1 = noise only) rises from kGateLow to kGateHigh. In a gated
// tile a frequency bin still merges while its difference stays within kGateBinNoise times its noise power (hdrq_merge.comp):
// moving areas are denoised like their surroundings without the moved content showing through. Tuned on a dusk street (scooter
// rider in front of a taxi) and a high-ISO hand sweeping a wall: 1 to 2 keep the rider solid and the area smooth (2 a little
// smoother on the wall), 4 lets the taxi start to show through the rider. It replaces shrinking the lone reference's tile
// spectra, which left a mottled, blotchy grain.
constexpr float kGateLow = 1.6f, kGateHigh = 2.6f, kGateBinNoise = 2.0f;
// The bracket merge's own tolerance for a gated bin (0 = hard gate, nothing merges in a gated tile): the unit of its tail-free
// test (hdrb_merge.comp), accepted below 0.4 and rejected above 2 times this many noise powers of the difference. Starts at HDR+
// Quality's.
constexpr float kBracketGateBinNoise = kGateBinNoise;
// Second displacement test, on the energy of the difference: the mean-based test cannot see a displaced fine pattern (small ripples,
// foliage) because its block mean cancels. The block's mean square difference over what noise alone gives (q, ~1 for noise) raises the
// statistic to 1 + kEnergyGain * (q - 1 - kEnergyDead) / kEnergyScale once q passes the dead zone; below it noise tiles are untouched.
// Tuned on a 30-frame bay with moving water against pixel4a, 33TJ and the street: far-water ripple detail kept 38% -> 43% at the
// finest band and rocks 82% -> 91%, for ~6% more noise in flat areas of long bursts (a more sensitive 0.4 / 0.2 keeps 58% but costs 18%).
constexpr float kEnergyGain = 1.0f, kEnergyDead = 1.0f, kEnergyScale = 0.5f;
struct BackwardPc {
    std::int32_t tilesX, tilesY;
    float frames, scale;
};
struct DeconvPc {
    std::int32_t tilesX, tilesY;
    float frames;
};
struct SeedPc {
    std::int32_t tilesX, tilesY;
    float frameInv, scale;
};
struct PhotPartialPc {
    std::int32_t width, height, groupsX;
    float scale, black, lo, hi;
};
struct PhotReducePc {
    std::int32_t count, slot;
    float minCount, aMin, aMax, bLimit;
};
struct PhotApplyPc {
    std::int32_t width, height, slot;
    float scale, black, limit;
};
// Photometric matching of a long frame to the reference (hdrb_phot_*.comp). The window and limits are in reference raw
// units at the 14-bit range (15359) and scale with the sensor range. A frame must be at least this much brighter than the
// reference to be matched (the clean frame is the regressor), and the fit is only trusted with enough samples.
constexpr float kBracketPhotMinFactor = 1.5f, kBracketPhotLo = 6.0f, kBracketPhotHi = 200.0f, kBracketPhotHiClipFrac = 0.6f,
                kBracketPhotApplyClipFrac = 0.88f, kBracketPhotMinSamples = 50000.0f, kBracketPhotAMin = 0.90f,
                kBracketPhotAMax = 1.10f, kBracketPhotBLimit = 4.0f;
struct BorderPc {
    std::int32_t width, height;
    float minValue, frameInv;
};
struct FreqAccumulatePc {
    std::int32_t width, height, offsetX, offsetY, mode;
};
}  // namespace

ScratchLayout makeHdrPlusFrequencyScratchLayout(const hp::FrequencyGeometry& f, std::uint32_t width,
                                                std::uint32_t height) {
    ScratchLayout l{};
    const auto& g = f.align;
    const Extent2D raw{width, height}, padded{g.paddedWidth, g.paddedHeight};
    const Extent2D rgba{f.rgbaWidth, f.rgbaHeight}, spectrum{2u * f.rgbaWidth, f.rgbaHeight}, tiles{f.tilesX, f.tilesY};
    const auto add = [&](const std::string& name, Extent2D e, PixelStorage s, Lifetime life, const char* placeAt = nullptr) {
        if (placeAt) l.placements.push_back({name, "hdrq_spectra", placeAt});
        l.images.push_back({name, e, s, life});
        l.logicalImageBytes += std::uint64_t(e.width) * e.height * bytesPerPixel(s);
    };
    add("hdrp_ref_padded", padded, PixelStorage::R32Float, Lifetime::Burst);
    add("hdrp_comp_padded", padded, PixelStorage::R32Float, Lifetime::Companion);
    add("hdrp_pyr_tmp_a", {g.levels.front().width, g.levels.front().height}, PixelStorage::R32Float, Lifetime::PyramidTemp);
    add("hdrp_pyr_tmp_b", {g.levels.front().width, g.levels.front().height}, PixelStorage::R32Float, Lifetime::PyramidTemp);
    std::uint64_t maxTiles = 1u;
    for (std::size_t i = 0; i < g.levels.size(); ++i) {
        const Extent2D e{g.levels[i].width, g.levels[i].height};
        add(levelName(true, i), e, PixelStorage::R32Float, Lifetime::Burst);
        add(levelName(false, i), e, PixelStorage::R32Float, Lifetime::Companion);
        maxTiles = std::max<std::uint64_t>(maxTiles, std::uint64_t(g.levels[i].tilesX) * g.levels[i].tilesY);
    }
    add("hdrq_ref_rgba", rgba, PixelStorage::RGBA16Float, Lifetime::Burst);
    add("hdrq_aligned_rgba", rgba, PixelStorage::RGBA16Float, Lifetime::Companion);
    add("hdrb_under_rgba", rgba, PixelStorage::RGBA16Float, Lifetime::Burst);  // HDR+ Bracketed highlight layer
    add("hdrq_out_rgba", rgba, PixelStorage::RGBA32Float, Lifetime::Output);
    add("hdrq_ref_ft", spectrum, PixelStorage::RGBA16Float, Lifetime::Burst, "");
    add("hdrq_final_ft", spectrum, PixelStorage::RGBA16Float, Lifetime::Output, "end:hdrq_ref_ft");
    add("hdrq_rms", tiles, PixelStorage::RGBA32Float, Lifetime::Burst);
    add("hdrq_mismatch", tiles, PixelStorage::R32Float, Lifetime::Companion);
    add("hdrq_zmap", tiles, PixelStorage::R32Float, Lifetime::Companion);
    add("hdrq_neff", tiles, PixelStorage::R32Float, Lifetime::Output);
    add("hdrq_total_mismatch", tiles, PixelStorage::R32Float, Lifetime::Output);
    add("hdrb_wbin", rgba, PixelStorage::R32Float, Lifetime::Output);  // HDR+ Bracketed per-bin weight (one texel per spectrum bin)
    add("hdrp_accum", raw, PixelStorage::R32Float, Lifetime::Output);
    // Written only by finalize, after every pass: the reference and running spectra are dead by then and share their memory.
    add("hdrp_output", raw, PixelStorage::RGBA16Float, Lifetime::Output, "start:hdrq_ref_ft");
    add("hdrp_cfa", raw, PixelStorage::R32Float, Lifetime::Output, "end:hdrp_output");
    const auto addBuffer = [&](const char* name, std::uint64_t bytes) {
        l.buffers.push_back({name, bytes, Lifetime::Companion});
        l.logicalBufferBytes += bytes;
    };
    addBuffer("hdrp_align_a", maxTiles * 8u);
    addBuffer("hdrp_align_b", maxTiles * 8u);
    addBuffer("hdrp_align_c", maxTiles * 8u);
    addBuffer("hdrp_tile_cost", maxTiles * 25u * 4u);
    addBuffer("hdrq_mean", 16u);
    // Photometric matching of the long frames: per-group fit sums, and one (gain, offset) per frame slot.
    addBuffer("hdrb_phot_parts", std::uint64_t(divUp(f.rgbaWidth, 16u)) * divUp(f.rgbaHeight, 16u) * 5u * 4u);
    addBuffer("hdrb_phot_coef", 64u * 8u);
    addBuffer("hdrq_shift_table", 49u * 64u * 8u);
    const auto& level0 = g.levels.front();
    addBuffer("hdrq_align_store",
              hp::kMaxFrequencyFrames * alignUp256(std::uint64_t(level0.tilesX) * level0.tilesY * 8u));
    return l;
}

HdrPlusFrequencyRecorder::HdrPlusFrequencyRecorder(ResourceArena& arena, VulkanExecutor& executor, hp::Config config,
                                                   hp::FrequencyGeometry geometry)
    : arena_(arena), executor_(executor), config_(config), geometry_(std::move(geometry)),
      align_(arena, executor, config, geometry_.align) {}

void HdrPlusFrequencyRecorder::beginPass(std::uint32_t pass) noexcept {
    pass_ = pass;
    // Align-once keeps every frame in the pass-0 padding; passOffset() maps.
    const std::uint32_t prepared = config_.frequencyAlignOnce ? 0u : pass;
    align_.setPads(geometry_.padLeft(prepared), geometry_.padTop(prepared));
}

std::array<std::int32_t, 2> HdrPlusFrequencyRecorder::passOffset() const noexcept {
    if (!config_.frequencyAlignOnce) return {0, 0};
    return {std::int32_t(geometry_.padLeft(0)) - std::int32_t(geometry_.padLeft(pass_)),
            std::int32_t(geometry_.padTop(0)) - std::int32_t(geometry_.padTop(pass_))};
}

VkDeviceSize HdrPlusFrequencyRecorder::alignSlotBytes() const noexcept {
    const auto& level0 = geometry_.align.levels.front();
    return alignUp256(VkDeviceSize(level0.tilesX) * level0.tilesY * 8u);
}

void HdrPlusFrequencyRecorder::recordCompanionAlign(VkCommandBuffer c, VkImageView rawU16, const RawNormalization& frame,
                                                    std::uint32_t slot) {
    if (!alignsThisPass()) {
        align_.setCompanionGain(frameGain(slot));
        align_.recordCompanionPadded(c, rawU16, frame);
        return;
    }
    recordCompanionAlignCoarse(c, rawU16, frame, slot);
    recordCompanionAlignFinest(c, slot);
}

void HdrPlusFrequencyRecorder::recordCompanionAlignCoarse(VkCommandBuffer c, VkImageView rawU16,
                                                          const RawNormalization& frame, std::uint32_t slot) {
    if (!alignsThisPass()) throw std::logic_error("hdrplus frequency: coarse alignment outside an aligning pass");
    align_.setCompanionGain(frameGain(slot));
    align_.recordCompanionPrepare(c, rawU16, frame);
    for (std::uint32_t level = align_.levelCount(); --level > 0u;) align_.recordCompanionAlignLevel(c, level);
}

void HdrPlusFrequencyRecorder::recordCompanionAlignFinest(VkCommandBuffer c, std::uint32_t slot) {
    align_.recordCompanionAlignLevel(c, 0u);
    if (!config_.frequencyAlignOnce) return;
    if (slot >= hp::kMaxFrequencyFrames) throw std::invalid_argument("hdrplus frequency: too many frames");
    const auto& level0 = geometry_.align.levels.front();
    VkMemoryBarrier toTransfer{VK_STRUCTURE_TYPE_MEMORY_BARRIER, nullptr, VK_ACCESS_SHADER_WRITE_BIT,
                               VK_ACCESS_TRANSFER_READ_BIT};
    vkCmdPipelineBarrier(c, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 1, &toTransfer, 0,
                         nullptr, 0, nullptr);
    const VkBufferCopy copy{0, slot * alignSlotBytes(), VkDeviceSize(level0.tilesX) * level0.tilesY * 8u};
    vkCmdCopyBuffer(c, arena_.buffer("hdrp_align_a").buffer, arena_.buffer("hdrq_align_store").buffer, 1, &copy);
    VkMemoryBarrier toCompute{VK_STRUCTURE_TYPE_MEMORY_BARRIER, nullptr, VK_ACCESS_TRANSFER_WRITE_BIT,
                              VK_ACCESS_SHADER_READ_BIT};
    vkCmdPipelineBarrier(c, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 1, &toCompute, 0,
                         nullptr, 0, nullptr);
}

void HdrPlusFrequencyRecorder::recordReference(VkCommandBuffer c, VkImageView rawU16, const RawNormalization& frame) {
    if (pass_ == 0u) {
        executor_.record(c, ShaderId::HdrqShiftTable, {{}, {bb(0, arena_.buffer("hdrq_shift_table"))}}, nullptr, 0u,
                         divUp(49u * 64u, 64u), 1);
    }
    if (alignsThisPass()) align_.recordReferencePrepare(c, rawU16, frame);
    const auto& rgba = arena_.image("hdrq_ref_rgba");
    const std::uint32_t tx = geometry_.tilesX, ty = geometry_.tilesY;
    const auto offset = passOffset();
    ToRgbaPc tr{std::int32_t(geometry_.rgbaWidth),         std::int32_t(geometry_.rgbaHeight),
                std::int32_t(geometry_.cropX),             std::int32_t(geometry_.cropY),
                offset[0],                                 offset[1],
                std::int32_t(geometry_.align.paddedWidth), std::int32_t(geometry_.align.paddedHeight), frameScale()};
    executor_.record(c, ShaderId::HdrqToRgba, {{ib(0, arena_.image("hdrp_ref_padded")), ib(1, rgba)}, {}}, &tr,
                     sizeof(tr), divUp(geometry_.rgbaWidth, 16), divUp(geometry_.rgbaHeight, 16));
    computeWriteBarrier(c);
    RmsPc rmsPc{std::int32_t(tx), std::int32_t(ty), 1.0f / frameScale()};
    executor_.record(c, ShaderId::HdrqRms, {{ib(0, rgba), ib(1, arena_.image("hdrq_rms"))}, {}}, &rmsPc, sizeof(rmsPc),
                     divUp(tx, 16), divUp(ty, 16));
    SeedPc sp{std::int32_t(tx), std::int32_t(ty), 1.0f / frameScale(), spectrumScale()};
    // The reference spectrum seeds both the reference and the running merge.
    // Each shifted pass has its own input/window; only the two identical
    // transforms within this pass are shared.
    executor_.record(c, ShaderId::HdrqSeedDft,
                     {{ib(0, rgba), ib(1, arena_.image("hdrq_ref_ft")), ib(2, arena_.image("hdrq_final_ft"))}, {}},
                     &sp, sizeof(sp), tx, ty);
    // Per-pass total mismatch starts at zero.
    VkMemoryBarrier toTransfer{VK_STRUCTURE_TYPE_MEMORY_BARRIER, nullptr, VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT,
                               VK_ACCESS_TRANSFER_WRITE_BIT};
    vkCmdPipelineBarrier(c, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 1, &toTransfer, 0,
                         nullptr, 0, nullptr);
    const VkClearColorValue zero{};
    const VkImageSubresourceRange range{VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    vkCmdClearColorImage(c, arena_.image("hdrq_total_mismatch").image, VK_IMAGE_LAYOUT_GENERAL, &zero, 1, &range);
    vkCmdClearColorImage(c, arena_.image("hdrq_neff").image, VK_IMAGE_LAYOUT_GENERAL, &zero, 1, &range);
    if (bracketed_) {
        // HDR+ Bracketed: each bin's weight starts at the reference's own (1).
        VkClearColorValue one{};
        one.float32[0] = 1.0f;
        vkCmdClearColorImage(c, arena_.image("hdrb_wbin").image, VK_IMAGE_LAYOUT_GENERAL, &one, 1, &range);
    }
    VkMemoryBarrier toCompute{VK_STRUCTURE_TYPE_MEMORY_BARRIER, nullptr, VK_ACCESS_TRANSFER_WRITE_BIT,
                              VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT};
    vkCmdPipelineBarrier(c, VK_PIPELINE_STAGE_TRANSFER_BIT | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                         VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 1, &toCompute, 0, nullptr, 0, nullptr);
}

void HdrPlusFrequencyRecorder::recordCompanionMerge(VkCommandBuffer c, std::uint32_t frameCount, std::uint32_t slot,
                                                    const std::function<void(std::uint32_t)>& mark) {
    const auto& a = geometry_.align;
    const auto& level0 = a.levels.front();
    const std::uint32_t tx = geometry_.tilesX, ty = geometry_.tilesY;
    const auto& refRgba = arena_.image("hdrq_ref_rgba");
    const auto& alignedRgba = arena_.image("hdrq_aligned_rgba");
    const auto& mismatch = arena_.image("hdrq_mismatch");
    const auto offset = passOffset();
    WarpRgbaPc wp{std::int32_t(geometry_.rgbaWidth), std::int32_t(geometry_.rgbaHeight), std::int32_t(geometry_.cropX),
                  std::int32_t(geometry_.cropY), std::int32_t(a.paddedWidth), std::int32_t(a.paddedHeight),
                  std::int32_t(level0.tilesX), std::int32_t(level0.tilesY), std::int32_t(level0.tileSize), 0,
                  offset[0], offset[1], config_.frequencyAlignOnce ? 1 : 0, frameScale()};
    // Align-once: this companion's pass-0 shifts from its store slot.
    const BufferBinding shifts =
        config_.frequencyAlignOnce
            ? BufferBinding{2u, arena_.buffer("hdrq_align_store").buffer, slot * alignSlotBytes(),
                            VkDeviceSize(level0.tilesX) * level0.tilesY * 8u}
            : bb(2, arena_.buffer("hdrp_align_a"));
    const float exposureFactor = bracketed_ ? frameExposureFactor(slot) : 1.0f;
    if (bracketed_) {
        const auto& blackRef = align_.referenceBlack();
        // The companion's real sensor area in the padded frame it is sampled from: align-once keeps every frame in the
        // pass-0 padding, otherwise the frame is padded for this pass.
        const std::uint32_t padPass = config_.frequencyAlignOnce ? 0u : pass_;
        const std::int32_t sx0 = std::int32_t(geometry_.padLeft(padPass)), sy0 = std::int32_t(geometry_.padTop(padPass));
        BracketWarpPc bwp{wp, exposureFactor, std::max(align_.referenceWhite(), 1.0f),
                          .25f * (blackRef[0] + blackRef[1] + blackRef[2] + blackRef[3]), sx0, sy0,
                          sx0 + std::int32_t(a.width), sy0 + std::int32_t(a.height)};
        executor_.record(c, ShaderId::HdrbWarpRgba,
                         {{ib(0, arena_.image("hdrp_comp_padded")), ib(1, alignedRgba), ib(3, refRgba)}, {shifts}}, &bwp,
                         sizeof(bwp), divUp(geometry_.rgbaWidth, 16), divUp(geometry_.rgbaHeight, 16));
    } else {
        executor_.record(c, ShaderId::HdrqWarpRgba,
                         {{ib(0, arena_.image("hdrp_comp_padded")), ib(1, alignedRgba)}, {shifts}}, &wp, sizeof(wp),
                         divUp(geometry_.rgbaWidth, 16), divUp(geometry_.rgbaHeight, 16));
    }
    computeWriteBarrier(c);
    if (mark) mark(1u);
    const auto& black = align_.referenceBlack();
    const float meanBlack = .25f * (black[0] + black[1] + black[2] + black[3]);
    if (bracketed_ && exposureFactor > kBracketPhotMinFactor) {
        // Match this long frame's brightness to the reference's. The exposure ratio comes from shutter x ISO as reported, and
        // the frame also differs by the sensor's real gain steps and by lamp flicker (the short reference sees one phase, the
        // 1/15 s frame the average): a percent or two, and a different one each capture. Tiles that lean on the long frames
        // and tiles that lean on the reference would otherwise differ by that much, as blocks in a smooth wall.
        const float photRange = std::max(align_.referenceWhite() - meanBlack, 1.0f);
        const float photScale = photRange / kBracketRefRange;
        const std::uint32_t pgx = divUp(geometry_.rgbaWidth, 16u), pgy = divUp(geometry_.rgbaHeight, 16u);
        if (pass_ == 0u) {  // measured once per frame (pass 0); the other passes reuse it
            const float hi = std::min(kBracketPhotHi * photScale, kBracketPhotHiClipFrac * photRange / exposureFactor);
            PhotPartialPc pp{std::int32_t(geometry_.rgbaWidth), std::int32_t(geometry_.rgbaHeight), std::int32_t(pgx),
                             frameScale(), meanBlack, kBracketPhotLo * photScale, hi};
            executor_.record(c, ShaderId::HdrbPhotPartial,
                             {{ib(0, alignedRgba), ib(1, refRgba)}, {bb(2, arena_.buffer("hdrb_phot_parts"))}}, &pp, sizeof(pp),
                             pgx, pgy);
            computeWriteBarrier(c);
            PhotReducePc rp{std::int32_t(pgx * pgy), std::int32_t(slot), kBracketPhotMinSamples, kBracketPhotAMin,
                            kBracketPhotAMax, kBracketPhotBLimit * photScale};
            executor_.record(c, ShaderId::HdrbPhotReduce,
                             {{}, {bb(0, arena_.buffer("hdrb_phot_parts")), bb(1, arena_.buffer("hdrb_phot_coef"))}}, &rp,
                             sizeof(rp), 1u, 1u);
            computeWriteBarrier(c);
        }
        PhotApplyPc ap{std::int32_t(geometry_.rgbaWidth), std::int32_t(geometry_.rgbaHeight), std::int32_t(slot), frameScale(),
                       meanBlack, kBracketPhotApplyClipFrac * photRange / exposureFactor};
        executor_.record(c, ShaderId::HdrbPhotApply, {{ib(0, alignedRgba)}, {bb(1, arena_.buffer("hdrb_phot_coef"))}}, &ap,
                         sizeof(ap), pgx, pgy);
        computeWriteBarrier(c);
    }
    MismatchPc mp{std::int32_t(tx), std::int32_t(ty), std::int32_t(geometry_.rgbaWidth), std::int32_t(geometry_.rgbaHeight),
                  noiseSlope_, noiseOffset_, meanBlack, 1.0f / frameScale(), kEnergyGain, kEnergyDead, kEnergyScale};
    {
        const DispatchBindings mismatchImages{{ib(0, refRgba), ib(1, alignedRgba), ib(2, arena_.image("hdrq_rms")), ib(3, mismatch),
                                             ib(4, arena_.image("hdrq_zmap"))},
                                            {}};
        if (bracketed_) {
            const float rangeScale = std::max(align_.referenceWhite() - meanBlack, 1.0f) / kBracketRefRange;
            BracketMismatchPc bmp{mp, exposureFactor, kBracketReadFloor * rangeScale * rangeScale,
                                  kBracketMeanSlack * rangeScale};
            executor_.record(c, ShaderId::HdrbMismatch, mismatchImages, &bmp, sizeof(bmp), tx, ty);
        } else {
            executor_.record(c, ShaderId::HdrqMismatch, mismatchImages, &mp, sizeof(mp), tx, ty);
        }
    }
    if (mark) {
        computeWriteBarrier(c);
        mark(5u);
    }
    // The aligned frame's spectrum is computed inside the merge pass (no stored aligned spectrum).
    computeWriteBarrier(c);
    if (mark) mark(2u);
    // Mean mismatch excluding the tile row/column introduced by this pass's shift.
    const std::uint32_t left = hp::FrequencyGeometry::shiftLeft(pass_) ? 1u : 0u;
    const std::uint32_t right = hp::FrequencyGeometry::shiftLeft(pass_) ? 0u : 1u;
    const std::uint32_t top = hp::FrequencyGeometry::shiftTop(pass_) ? 1u : 0u;
    const std::uint32_t bottom = hp::FrequencyGeometry::shiftTop(pass_) ? 0u : 1u;
    RegionPc rp{std::int32_t(left), std::int32_t(top), std::int32_t(tx - left - right), std::int32_t(ty - top - bottom)};
    executor_.record(c, ShaderId::HdrqRegionMean, {{ib(0, mismatch)}, {bb(1, arena_.buffer("hdrq_mean"))}}, &rp,
                     sizeof(rp), 1, 1);
    computeWriteBarrier(c);
    MismatchNormPc np{std::int32_t(tx), std::int32_t(ty), 1.0f / float(frameCount), kGateLow, kGateHigh};
    executor_.record(c, ShaderId::HdrqMismatchNorm,
                     {{ib(0, mismatch), ib(1, arena_.image("hdrq_total_mismatch")), ib(3, arena_.image("hdrq_zmap")),
                       ib(4, arena_.image("hdrq_neff"))},
                      {bb(2, arena_.buffer("hdrq_mean"))}},
                     &np, sizeof(np), divUp(tx, 16), divUp(ty, 16));
    computeWriteBarrier(c);
    if (mark) mark(3u);
    const auto norms = hp::frequencyNorms(config_.strength);
    FreqMergePc fm{std::int32_t(tx), std::int32_t(ty), kWienerNoise, norms.maxMotionNorm,
                   kGateLow, kGateHigh, noiseSlope_, noiseOffset_, meanBlack, 1.0f / frameScale(), spectrumScale(), kGateBinNoise};
    {
        const DispatchBindings mergeBindings{
            {ib(0, arena_.image("hdrq_ref_ft")), ib(1, alignedRgba), ib(2, arena_.image("hdrq_final_ft")),
             ib(3, arena_.image("hdrq_rms")), ib(4, mismatch), ib(6, arena_.image("hdrq_zmap"))},
            {bb(5, arena_.buffer("hdrq_shift_table"))}};
        if (bracketed_) {
            BracketFreqMergePc bfm{fm, std::min(exposureFactor, kBracketMaxWeight), exposureFactor};
            // The bracket merge is its own copy (hdrb_merge.comp). A frame rejected in a bin must add exactly nothing: the
            // per-bin weight (hdrb_wbin) leaves it out of the divisor, which is what lets a gated frame still merge the bins
            // where it agrees with the reference (binNoise, u.12) without over-scaling the others. The shrink of the reference
            // inside gated tiles (the old gated-tile handling) is gone, as in HDR+ Quality since u.12.
            bfm.base.binNoise = kBracketGateBinNoise;
            DispatchBindings bracketBindings = mergeBindings;
            bracketBindings.images.push_back(ib(7, arena_.image("hdrb_wbin")));
            executor_.record(c, ShaderId::HdrbMerge, bracketBindings, &bfm, sizeof(bfm), tx, ty);
        } else {
            executor_.record(c, ShaderId::HdrqMerge, mergeBindings, &fm, sizeof(fm), tx, ty);
        }
    }
    computeWriteBarrier(c);
}

void HdrPlusFrequencyRecorder::recordCompanionUnder(VkCommandBuffer c, std::uint32_t slot, std::uint32_t index) {
    if (pass_ != 0u) throw std::logic_error("hdrplus frequency: an under frame is taken in pass 0");
    const auto& a = geometry_.align;
    const auto& level0 = a.levels.front();
    const auto& refRgba = arena_.image("hdrq_ref_rgba");
    const auto& alignedRgba = arena_.image("hdrq_aligned_rgba");
    const auto& underRgba = arena_.image("hdrb_under_rgba");
    const auto offset = passOffset();
    WarpRgbaPc wp{std::int32_t(geometry_.rgbaWidth), std::int32_t(geometry_.rgbaHeight), std::int32_t(geometry_.cropX),
                  std::int32_t(geometry_.cropY), std::int32_t(a.paddedWidth), std::int32_t(a.paddedHeight),
                  std::int32_t(level0.tilesX), std::int32_t(level0.tilesY), std::int32_t(level0.tileSize), 0,
                  offset[0], offset[1], config_.frequencyAlignOnce ? 1 : 0, frameScale()};
    const BufferBinding shifts =
        config_.frequencyAlignOnce
            ? BufferBinding{2u, arena_.buffer("hdrq_align_store").buffer, slot * alignSlotBytes(),
                            VkDeviceSize(level0.tilesX) * level0.tilesY * 8u}
            : bb(2, arena_.buffer("hdrp_align_a"));
    const auto& blackRef = align_.referenceBlack();
    const float meanBlack = .25f * (blackRef[0] + blackRef[1] + blackRef[2] + blackRef[3]);
    const std::int32_t sx0 = std::int32_t(geometry_.padLeft(0)), sy0 = std::int32_t(geometry_.padTop(0));
    BracketWarpPc bwp{wp, frameExposureFactor(slot), std::max(align_.referenceWhite(), 1.0f), meanBlack, sx0, sy0,
                      sx0 + std::int32_t(a.width), sy0 + std::int32_t(a.height)};
    executor_.record(c, ShaderId::HdrbWarpRgba,
                     {{ib(0, arena_.image("hdrp_comp_padded")), ib(1, alignedRgba), ib(3, refRgba)}, {shifts}}, &bwp,
                     sizeof(bwp), divUp(geometry_.rgbaWidth, 16), divUp(geometry_.rgbaHeight, 16));
    computeWriteBarrier(c);
    // Match the frame's brightness to the reference over the texels where both are clean.
    const float range = std::max(align_.referenceWhite() - meanBlack, 1.0f);
    const float photScale = range / kBracketRefRange;
    const std::uint32_t pgx = divUp(geometry_.rgbaWidth, 16u), pgy = divUp(geometry_.rgbaHeight, 16u);
    PhotPartialPc pp{std::int32_t(geometry_.rgbaWidth), std::int32_t(geometry_.rgbaHeight), std::int32_t(pgx), frameScale(),
                     meanBlack, kUnderPhotLo * range, kUnderPhotHi * range};
    executor_.record(c, ShaderId::HdrbPhotPartial,
                     {{ib(0, alignedRgba), ib(1, refRgba)}, {bb(2, arena_.buffer("hdrb_phot_parts"))}}, &pp, sizeof(pp), pgx,
                     pgy);
    computeWriteBarrier(c);
    PhotReducePc rp{std::int32_t(pgx * pgy), std::int32_t(slot), kUnderPhotMinSamples, kUnderPhotAMin, kUnderPhotAMax,
                    kUnderPhotBLimit * photScale};
    executor_.record(c, ShaderId::HdrbPhotReduce,
                     {{}, {bb(0, arena_.buffer("hdrb_phot_parts")), bb(1, arena_.buffer("hdrb_phot_coef"))}}, &rp, sizeof(rp),
                     1u, 1u);
    computeWriteBarrier(c);
    PhotApplyPc ap{std::int32_t(geometry_.rgbaWidth), std::int32_t(geometry_.rgbaHeight), std::int32_t(slot), frameScale(),
                   meanBlack, 1.0e9f};  // the whole frame: it is bright where it counts
    executor_.record(c, ShaderId::HdrbPhotApply, {{ib(0, alignedRgba)}, {bb(1, arena_.buffer("hdrb_phot_coef"))}}, &ap,
                     sizeof(ap), pgx, pgy);
    computeWriteBarrier(c);
    // Running mean of the under frames.
    UnderStorePc sp{std::int32_t(geometry_.rgbaWidth), std::int32_t(geometry_.rgbaHeight), float(index)};
    executor_.record(c, ShaderId::HdrbUnderStore, {{ib(0, alignedRgba), ib(1, underRgba)}, {}}, &sp, sizeof(sp),
                     divUp(geometry_.rgbaWidth, 16), divUp(geometry_.rgbaHeight, 16));
    computeWriteBarrier(c);
}

void HdrPlusFrequencyRecorder::recordEffectiveFramesCopy(VkCommandBuffer c, VkBuffer host) {
    const auto& neff = arena_.image("hdrq_neff");
    VkMemoryBarrier mb{VK_STRUCTURE_TYPE_MEMORY_BARRIER, nullptr, VK_ACCESS_SHADER_WRITE_BIT, VK_ACCESS_TRANSFER_READ_BIT};
    vkCmdPipelineBarrier(c, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 1, &mb, 0, nullptr, 0,
                         nullptr);
    VkBufferImageCopy region{};
    region.imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
    region.imageExtent = {neff.extent.width, neff.extent.height, 1};
    vkCmdCopyImageToBuffer(c, neff.image, VK_IMAGE_LAYOUT_GENERAL, host, 1, &region);
    VkMemoryBarrier toHost{VK_STRUCTURE_TYPE_MEMORY_BARRIER, nullptr, VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_HOST_READ_BIT};
    vkCmdPipelineBarrier(c, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_HOST_BIT, 0, 1, &toHost, 0, nullptr, 0, nullptr);
}

std::pair<std::uint32_t, std::uint32_t> HdrPlusFrequencyRecorder::tileCount() const noexcept {
    return {geometry_.tilesX, geometry_.tilesY};
}

void HdrPlusFrequencyRecorder::recordPassFinish(VkCommandBuffer c, std::uint32_t frameCount) {
    const std::uint32_t tx = geometry_.tilesX, ty = geometry_.tilesY;
    const auto& finalFt = arena_.image("hdrq_final_ft");
    const auto& out = arena_.image("hdrq_out_rgba");
    // The running merge stores the burst's difference from frameCount x the reference spectrum (see hdrq_merge.comp), so the
    // reference spectrum is an input of both the deconvolution and the inverse transform.
    const auto& refFt = arena_.image("hdrq_ref_ft");
    if (bracketed_) {
        // Per-bin weight normalization (hdrb_wnorm.comp): a companion rejected in a bin, or with nothing there, drops out of
        // that bin's weight instead of counting as a copy of the reference at its full weight.
        computeWriteBarrier(c);
        WnormPc wn{std::int32_t(tx), std::int32_t(ty), weightTotal_};
        executor_.record(c, ShaderId::HdrbWnorm, {{ib(0, finalFt), ib(1, arena_.image("hdrb_wbin"))}, {}}, &wn, sizeof(wn),
                         tx, ty);
        computeWriteBarrier(c);
    }
    DeconvPc dp{std::int32_t(tx), std::int32_t(ty), bracketed_ ? weightTotal_ : float(frameCount)};
    executor_.record(c, ShaderId::HdrqDeconvolute,
                     {{ib(0, finalFt), ib(1, arena_.image("hdrq_total_mismatch")), ib(2, refFt)}, {}}, &dp, sizeof(dp), tx, ty);
    computeWriteBarrier(c);
    BackwardPc bp{std::int32_t(tx), std::int32_t(ty), bracketed_ ? weightTotal_ : float(frameCount), spectrumScale()};
    executor_.record(c, ShaderId::HdrqBackwardDft, {{ib(0, finalFt), ib(1, out), ib(2, refFt)}, {}}, &bp, sizeof(bp), tx, ty);
    computeWriteBarrier(c);
    // Exposure control off: upstream passes black level -1, so the clamp floor is -2 * window.
    BorderPc bd{std::int32_t(geometry_.rgbaWidth), std::int32_t(geometry_.rgbaHeight), -2.0f, 1.0f / frameScale()};
    executor_.record(c, ShaderId::HdrqBorder, {{ib(0, out), ib(1, arena_.image("hdrq_ref_rgba"))}, {}}, &bd, sizeof(bd),
                     divUp(geometry_.rgbaWidth, 16), divUp(geometry_.rgbaHeight, 16));
    computeWriteBarrier(c);
    const auto& accum = arena_.image("hdrp_accum");
    FreqAccumulatePc ap{std::int32_t(accum.extent.width), std::int32_t(accum.extent.height),
                        std::int32_t(geometry_.padLeft(pass_) - geometry_.cropX),
                        std::int32_t(geometry_.padTop(pass_) - geometry_.cropY), pass_ == 0u ? 0 : 1};
    executor_.record(c, ShaderId::HdrqAccumulate, {{ib(0, out), ib(1, accum)}, {}}, &ap, sizeof(ap),
                     divUp(accum.extent.width, 16), divUp(accum.extent.height, 16));
    computeWriteBarrier(c);
}

}  // namespace rawr::raw_gpu_pipeline::hdrb
