// Rawr 17U fork: HDR+ Bracketed burst run - Steve's v26 patch (rawr-17u16-bracketed-v26), his code. A copy of Ed's
// AndroidBurstCoordinator::runHdrPlusFrequency with Steve's bracket changes, running the bracket recorder
// (HdrBracketRecorder.cpp), so Ed's HDR+ Quality run stays exactly as upstream. AndroidBurstCoordinator::run calls it only
// for a bracketed burst (hdrplus_.bracketed). Built only with RAWR_HDRB (app/build.gradle.kts, -Prawr.hdrb).
#include <rawr/raw_gpu_pipeline/AndroidBurstCoordinator.h>
#include <rawr/raw_gpu_pipeline/BurstNoiseEstimate.h>
#include <rawr/raw_gpu_pipeline/HdrBracketRecorder.h>

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <stdexcept>
#include <vector>

namespace rawr::raw_gpu_pipeline {
namespace {
void check(VkResult r, const char* what) {
    if (r != VK_SUCCESS) throw std::runtime_error(what);
}
// Same as Ed's exposureGain in AndroidBurstCoordinator.cpp: gain that scales frame i to the reference exposure.
float exposureGain(const std::vector<BurstFrame>& frames, std::uint32_t ref, std::uint32_t i) {
    const float r = frames[ref].exposure, e = frames[i].exposure;
    if (!(r > 0.0f) || !(e > 0.0f)) return 1.0f;
    const float gain = r / e;
    return std::fabs(gain - 1.0f) > 1.0e-3f ? gain : 1.0f;
}
}  // namespace

// The burst noise fit's neighbour for a bracketed burst: the nearest frame at the reference's exposure (the burst also holds
// darker and brighter ones); with none, Ed's choice (fallback).
std::uint32_t AndroidBurstCoordinator::bracketNoiseNeighbour(const std::vector<BurstFrame>& frames, std::uint32_t ref,
                                                             std::uint32_t fallback) {
    for (std::uint32_t d = 1u; d < frames.size(); ++d) {
        const std::uint32_t up = ref + d, down = ref >= d ? ref - d : ref;
        if (up < frames.size() && std::fabs(exposureGain(frames, ref, up) - 1.0f) < 0.02f) return up;
        if (down != ref && std::fabs(exposureGain(frames, ref, down) - 1.0f) < 0.02f) return down;
    }
    return fallback;
}

ScratchLayout AndroidBurstCoordinator::bracketScratchLayout(std::uint32_t width, std::uint32_t height,
                                                            const rawr::raw_merge_hdrplus_gpu::Config& hdrplus) {
    return hdrb::makeHdrPlusFrequencyScratchLayout(rawr::raw_merge_hdrplus_gpu::makeFrequencyGeometry(width, height, hdrplus),
                                                   width, height);
}

BurstRunResult AndroidBurstCoordinator::runHdrPlusBracketed(const std::vector<BurstFrame>& frames, std::uint32_t ref,
                                                            const FrameConsumed& frameConsumed,
                                                            const FrameLoad& frameLoad) {
    // Four passes revisit every companion: all frames stay resident.
    if (frameLoad)
        for (std::uint32_t i = 0; i < frames.size(); ++i) frameLoad(i);
    // Four half-tile-shifted passes. Companions are aligned in pass 0 only
    // (align-once, default) or re-aligned on each pass's padding (upstream).
    // Chunk codes: 1=pass reference, 11=companion prepare/alignment,
    // 14=frequency merge, 15=pass finish, 20=finalize. Ring frames are
    // consumed only after the last pass.
    using Clock = std::chrono::steady_clock;
    const auto runBegin = Clock::now();
    BurstStageTimings timings{};
    timings.initializeMs = pendingInitializeMs_;
    const auto gpuOrWall = [](const ChunkTiming& t) { return t.second.empty() ? t.first : t.second.front(); };
    const auto geometry = rawr::raw_merge_hdrplus_gpu::makeFrequencyGeometry(width_, height_, hdrplus_);
    hdrb::HdrPlusFrequencyRecorder recorder(arena_, executor_, hdrplus_, geometry);
    // Noise model for the displacement statistic: the burst-fitted profile when it fits (as the Rexture merge does),
    // else the configured one; converted from normalized units to the merge's raw units. The fit also goes to the result,
    // for the JPEG chroma denoise.
    bool noiseFitted = false;
    rawr::raw_merge_common::CfaNoiseProfile fittedNoise{};
    {
        struct RestoreMerge {
            rawr::raw_merge_common::Config& target;
            rawr::raw_merge_common::Config saved;
            ~RestoreMerge() { target = saved; }
        } restoreMerge{merge_, merge_};
        noiseFitted = estimateNoiseFromBurst(frames, ref, frameLoad, timings);
        if (noiseFitted && merge_.sensorNoiseProfile) fittedNoise = *merge_.sensorNoiseProfile;
        float alpha = merge_.noiseAlpha, beta = merge_.noiseBeta;
        if (merge_.sensorNoiseProfile) {
            const auto& n = *merge_.sensorNoiseProfile;
            alpha = .25f * (n.slopeBySite[0] + n.slopeBySite[1] + n.slopeBySite[2] + n.slopeBySite[3]);
            beta = .25f * (n.offsetBySite[0] + n.offsetBySite[1] + n.offsetBySite[2] + n.offsetBySite[3]);
        }
        const auto& norm = frames[ref].parameters.normalization;
        const float black = .25f * (norm.blackByPhase[0] + norm.blackByPhase[1] + norm.blackByPhase[2] + norm.blackByPhase[3]);
        const float range = std::max(1.0f, norm.whiteLevel - black);
        recorder.setNoise(alpha * range, beta * range * range);
    }
    // HDR+ Bracketed: frames more than 1/8 EV darker than the reference (the under frames) are not merged; they become the
    // highlight layer the finalize blends in. isUnder marks them, underMinEf is the exposure ratio of the darkest.
    std::vector<char> isUnder(frames.size(), 0);
    std::uint32_t underCount = 0;
    float underMinEf = 1.0f;
    {
        std::vector<float> gains(frames.size(), 1.0f);
        for (std::uint32_t i = 0; i < frames.size(); ++i) gains[i] = exposureGain(frames, ref, i);
        // HDR+ Bracketed: a burst with frames more than 1/8 EV brighter than the reference merges with the bracket
        // shaders, each frame weighted by its exposure ratio (the reference counts 1). Any other burst is uniform.
        if (hdrplus_.bracketed) {
            bool brighter = false;
            float weightTotal = 0.0f;
            for (std::uint32_t i = 0; i < gains.size(); ++i) {
                const float g = gains[i];
                if (i != ref && g > 1.682f) {  // darker by more than 3/4 EV (the planned under frames): the highlight layer, not merged
                    isUnder[i] = 1;
                    ++underCount;
                    underMinEf = std::min(underMinEf, 1.0f / g);
                    continue;
                }
                if (g > 0.0f && g < 0.917f) brighter = true;
                weightTotal += g > 0.0f ? std::min(1.0f / g, hdrb::HdrPlusFrequencyRecorder::kBracketMaxWeight) : 1.0f;
            }
            if (brighter) recorder.setBracketed(true, weightTotal);
            if (underCount > 0u) recorder.setUnder(underCount, underMinEf);
            recorder.setHighlightNeutral(hdrplus_.highlightNeutralByPhase);
        }
        recorder.setExposureNormalization(std::move(gains));
    }
    const std::size_t pairs = std::min<std::size_t>(merge_.hotPixels.size() / 2u, 1u << 20);
    if (pairs > 0u) {
        ensureHostBuffer(hotPixels_, VkDeviceSize(pairs) * 2u * sizeof(std::int32_t));
        std::memcpy(hotPixels_.mapped, merge_.hotPixels.data(), pairs * 2u * sizeof(std::int32_t));
        recorder.alignment().setHotPixels(hotPixels_.buffer, std::uint32_t(pairs));
    }
    const auto frameCount = std::uint32_t(frames.size());
    for (std::uint32_t i = 0; i < frameCount; ++i)
        if (frames[i].raw.ref.width != width_ || frames[i].raw.ref.height != height_)
            throw std::invalid_argument("multiframe burst: RAW geometry mismatch");
    std::vector<std::uint32_t> order, underOrder;
    for (std::uint32_t i = 0; i < frameCount; ++i) {
        if (i == ref) continue;
        (isUnder[i] ? underOrder : order).push_back(i);
    }
    const std::uint32_t mergedCount = frameCount - underCount;  // frames in the spectral merge (the reference included)
    std::uint32_t companions = 0;
    float effectiveFrames = 0.0f;
    for (std::uint32_t pass = 0; pass < 4u; ++pass) {
        recorder.beginPass(pass);
        const bool initializeLayouts = !layoutsInitialized_;
        timings.referencePrepareMs += gpuOrWall(executeChunk(1u, ref, 2u, [&] {
            recordTimestamp(0u);
            if (initializeLayouts) arena_.recordInitializeLayouts(command_);
            recorder.recordReference(command_, frames[ref].raw.view, frames[ref].parameters.normalization);
            recordTimestamp(1u);
        }));
        if (initializeLayouts) layoutsInitialized_ = true;
        if (pass == 0u) {
            // Highlight layer: each under frame is aligned to the reference, brightness-matched and averaged in; they are
            // not needed again, so the later passes skip them.
            std::uint32_t underIndex = 0;
            for (const std::uint32_t i : underOrder) {
                executeChunk(11u, i, 2u, [&] {
                    recordTimestamp(0u);
                    recorder.recordCompanionAlignCoarse(command_, frames[i].raw.view, frames[i].parameters.normalization, i);
                    recordTimestamp(1u);
                });
                executeChunk(11u, i, 2u, [&] {
                    recordTimestamp(0u);
                    recorder.recordCompanionAlignFinest(command_, i);
                    recordTimestamp(1u);
                });
                executeChunk(14u, i, 2u, [&] {
                    recordTimestamp(0u);
                    recorder.recordCompanionUnder(command_, i, underIndex);
                    recordTimestamp(1u);
                });
                ++underIndex;
                ++companions;
                if (frameConsumed) frameConsumed(i);
            }
        }
        for (const std::uint32_t i : order) {
            // Aligning passes: prepare + coarse levels, then the finest level
            // (+ shift store) as its own submission. One submission held the
            // GPU ~70 ms per companion on Adreno and dropped preview frames.
            // Other passes: only the padded frame.
            const bool aligns = recorder.alignsThisPass();
            double alignMs = 0.0;
            if (aligns) {
                alignMs += gpuOrWall(executeChunk(11u, i, 2u, [&] {
                    recordTimestamp(0u);
                    recorder.recordCompanionAlignCoarse(command_, frames[i].raw.view,
                                                        frames[i].parameters.normalization, i);
                    recordTimestamp(1u);
                }));
                alignMs += gpuOrWall(executeChunk(11u, i, 2u, [&] {
                    recordTimestamp(0u);
                    recorder.recordCompanionAlignFinest(command_, i);
                    recordTimestamp(1u);
                }));
            } else {
                alignMs = gpuOrWall(executeChunk(11u, i, 2u, [&] {
                    recordTimestamp(0u);
                    recorder.recordCompanionAlign(command_, frames[i].raw.view, frames[i].parameters.normalization, i);
                    recordTimestamp(1u);
                }));
            }
            (aligns ? timings.alignmentMs : timings.companionPrepareMs) += alignMs;
            const bool profile = std::getenv("RAWR_HDRP_PROFILE") != nullptr;
            // Query order: 0 start, 1 warp, [2 mismatch when profiling], then fft, norm, merge.
            const auto mergeTiming = executeChunk(14u, i, profile ? 6u : 5u, [&] {
                recordTimestamp(0u);
                recorder.recordCompanionMerge(command_, mergedCount, i, [&](std::uint32_t k) {
                    if (k == 5u) {
                        if (profile) recordTimestamp(2u);  // mismatch boundary, profiling only
                    } else {
                        recordTimestamp(profile && k >= 2u ? k + 1u : k);
                    }
                });
                recordTimestamp(profile ? 5u : 4u);
            });
            double mergeMs = mergeTiming.first;
            if (!mergeTiming.second.empty()) {
                mergeMs = 0.0;
                for (const double ms : mergeTiming.second) mergeMs += ms;
            }
            timings.accumulationMs += mergeMs;
            if (profile && mergeTiming.second.size() == 5u)
                std::fprintf(stderr, "hdrq_profile pass=%u frame=%u warp=%.3f mismatch=%.3f fft=%.3f norm=%.3f merge=%.3f\n",
                             pass, i, mergeTiming.second[0], mergeTiming.second[1], mergeTiming.second[2],
                             mergeTiming.second[3], mergeTiming.second[4]);
            if (pass == 3u) {
                ++companions;
                if (frameConsumed) frameConsumed(i);
            }
        }
        if (pass == 3u) {
            const auto tiles = recorder.tileCount();
            const VkDeviceSize bytes = VkDeviceSize(tiles.first) * tiles.second * sizeof(float);
            ensureHostBuffer(effectiveFrames_, bytes);
            executeChunk(32u, ref, 2u, [&] {
                recordTimestamp(0u);
                recorder.recordEffectiveFramesCopy(command_, effectiveFrames_.buffer);
                recordTimestamp(1u);
            });
            check(vkWaitForFences(device_, 1, &fence_, VK_TRUE, UINT64_MAX), "multiframe effective frames fence");
            submissionInFlight_ = false;
            const auto* acc = static_cast<const float*>(effectiveFrames_.mapped);
            double inverse = 0.0;
            const std::size_t count = std::size_t(tiles.first) * tiles.second;
            for (std::size_t t = 0; t < count; ++t) inverse += 1.0 / (1.0 + double(std::max(acc[t], 0.0f)));
            effectiveFrames = count ? float(double(count) / inverse) : 0.0f;
        }
        timings.finalizeMs += gpuOrWall(executeChunk(15u, ref, 2u, [&] {
            recordTimestamp(0u);
            recorder.recordPassFinish(command_, mergedCount);
            recordTimestamp(1u);
        }));
    }
    timings.finalizeMs += gpuOrWall(executeChunk(20u, ref, 2u, [&] {
        recordTimestamp(0u);
        recorder.recordFinalize(command_);
        recordTimestamp(1u);
    }));
    timings.totalMs = std::chrono::duration<double, std::milli>(Clock::now() - runBegin).count();
    timings.chunkCount = chunkStats_.chunkCount;
    timings.chunkWallMs = chunkStats_.chunkWallMs;
    timings.chunkRecordMs = chunkStats_.chunkRecordMs;
    timings.chunkSubmitMs = chunkStats_.chunkSubmitMs;
    timings.chunkWaitMs = chunkStats_.chunkWaitMs;
    timings.chunkGpuMs = chunkStats_.chunkGpuMs;
    const auto& out = arena_.image("hdrp_output");
    const auto& cfa = arena_.image("hdrp_cfa");
    BurstRunResult result{ref,
                          frameCount,
                          out.image,
                          out.view,
                          out.format,
                          out.extent,
                          arena_.physicalImageBytes(),
                          arena_.physicalBufferBytes(),
                          4u * (2u + companions * 2u) + 1u,
                          resourcesReused_,
                          pipelineCacheReused_,
                          timings};
    result.cfaImage = cfa.image;
    result.cfaView = cfa.view;
    result.noiseEstimated = noiseFitted;
    if (noiseFitted) result.estimatedNoise = fittedNoise;
    result.effectiveFrames = effectiveFrames;
    result.outputScaleEv = underCount > 0u ? -std::log2(std::max(underMinEf, 1.0e-3f)) : 0.0f;
    return result;
}
}  // namespace rawr::raw_gpu_pipeline
