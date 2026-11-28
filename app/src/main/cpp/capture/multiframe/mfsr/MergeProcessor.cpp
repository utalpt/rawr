#include "capture/multiframe/mfsr/MergeProcessor.h"

#include <cmath>
#include <stdexcept>

#include "capture/multiframe/BracketExposure.h"
#include "capture/multiframe/MergeExposure.h"
#include "capture/multiframe/MultiframeQueueHelpers.h"
#include "diagnostics/logging/NativeLog.h"
namespace rawrcam::capture::multiframe {
rawr::raw_gpu_pipeline::BurstRunResult MergeProcessor::run(
    vulkan::VulkanContext& context, rawr::raw_gpu_pipeline::AndroidBurstCoordinator& coordinator, FrozenBurst& burst,
    uint32_t width, uint32_t height, uint32_t cfa, const std::array<float, 4>& wbRggb, const MultiframeTuning& tuning,
    bool dumpRzslRequested,
    const rawr::raw_gpu_pipeline::AndroidBurstCoordinator::Submit& queueSubmit,
    const rawr::raw_gpu_pipeline::AndroidBurstCoordinator::Submit& mergeSubmit) {
    const bool useMfQueue = context.hasMultiframeQueue();
    // The noise model is fitted from the burst; the merges read only the CFA, the burst fit switch and the hot pixels.
    rawr::raw_merge_common::Config mergeConfig{};
    mergeConfig.cfa = static_cast<rawr::raw_merge_common::CfaPattern>(cfa);
    mergeConfig.estimateNoiseFromBurst = true;
    mergeConfig.hotPixels = burst.referenceMetadata.hotPixelMap;
    auto stageSink = rawrcam::capture::multiframe::makeTraceStageSink();
    // One Rexture window holds ~72 bytes of arena per pixel. The job splits larger frames into strips of at most
    // kRextureMaxPixels; a window above it is refused, which takes the existing failure path (base frame out, capture kept).
    if (tuning.mergeAlgorithm == kMergeAlgorithmRexture && std::uint64_t(width) * height > kRextureMaxPixels)
        throw std::runtime_error("Rexture: merge window above kRextureMaxPixels");
    // Captures journaled by older builds with a removed merge algorithm (super-resolution, HDR+, HDR+ Bracketed) are not
    // merged: the failure path outputs the base frame and keeps the capture.
    if (tuning.mergeAlgorithm != kMergeAlgorithmRexture && tuning.mergeAlgorithm != kMergeAlgorithmHdrPlusQuality &&
        tuning.mergeAlgorithm != kMergeAlgorithmHdrPlusBracketed)
        throw std::runtime_error("unsupported merge algorithm " + std::to_string(tuning.mergeAlgorithm));
    const auto algorithm = tuning.mergeAlgorithm == kMergeAlgorithmRexture
                               ? rawr::raw_gpu_pipeline::MergeAlgorithm::Rexture
                               : rawr::raw_gpu_pipeline::MergeAlgorithm::HdrPlusFrequency;
    // HDR+ Bracketed runs as HDR+ Quality: a burst metered darker for highlight headroom has one exposure, so the merge is
    // uniform; the lift back to the metered exposure happens in the DNG and the render.
    if (tuning.mergeAlgorithm == kMergeAlgorithmHdrPlusBracketed && burst.metadata.size() == burst.frames.size())
        LOGI("MULTIFRAME_BRACKET merge frames=%zu reference=%u bracketed=%d lift=%.2fEV", burst.frames.size(), burst.referenceIndex,
             isExposureBracketed(burst.metadata) ? 1 : 0, bracketLiftEv(burst.metadata, burst.referenceIndex));
    // Per-frame exposure from the (journaled) metadata: both merges scale every companion to the reference exposure
    // (AE may still be settling across the ring).
    if (burst.metadata.size() == burst.frames.size())
        for (std::size_t i = 0; i < burst.frames.size(); ++i)
            burst.frames[i].exposure = float(linearExposure(burst.metadata[i]) * 1.0e-6);
    rawr::raw_merge_hdrplus_gpu::Config hdrplusConfig{};
    hdrplusConfig.bracketed = tuning.mergeAlgorithm == kMergeAlgorithmHdrPlusBracketed &&
                              (tuning.bracketFrames > 0u || tuning.underFrames > 0u);
    // The as-shot neutral per CFA phase: a light that is neutral under the white balance has raw channels in the ratio
    // 1 / gain (green = 1). The bracket finalize reconstructs clipped highlights with it. wbRggb is (R, G1, G2, B).
    {
        const bool gainsOk = std::isfinite(wbRggb[0]) && std::isfinite(wbRggb[1]) && std::isfinite(wbRggb[2]) &&
                             std::isfinite(wbRggb[3]) && wbRggb[0] > 0.0f && wbRggb[1] > 0.0f && wbRggb[2] > 0.0f &&
                             wbRggb[3] > 0.0f && cfa <= 3u;
        if (gainsOk) {
            // Colour channel (0 R, 1 G1, 2 G2, 3 B) at each phase of the 2x2 cell, per CFA pattern (cfa_common.glsl).
            static constexpr int kPhaseChannel[4][4] = {{0, 1, 2, 3}, {1, 0, 3, 2}, {2, 3, 0, 1}, {3, 1, 2, 0}};
            const float green = 0.5f * (wbRggb[1] + wbRggb[2]);
            for (int phase = 0; phase < 4; ++phase)
                hdrplusConfig.highlightNeutralByPhase[static_cast<std::size_t>(phase)] =
                    green / wbRggb[static_cast<std::size_t>(kPhaseChannel[cfa][phase])];
        }
    }
    // Strength and tile size are fixed (Config defaults); the journaled tuning slots are reserved.
    coordinator.setRextureDetailRestore(tuning.rextureDetailRestore != 0);
    coordinator.initialize(context.physicalDevice(), context.device(), context.queueFamily(), mergeSubmit, width,
                           height, stageSink, mergeConfig, algorithm, hdrplusConfig);
    ScopedSemaphore mfStartSem(context.device(), useMfQueue);
    if (useMfQueue) {
        // Empty signal ordered after all prior queue-0 work (decodes, base
        // readback); the first merge chunk waits on it. No host wait needed.
        VkSemaphore bridgeSem = mfStartSem.get();
        VkSubmitInfo bridge{};
        bridge.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
        bridge.signalSemaphoreCount = 1;
        bridge.pSignalSemaphores = &bridgeSem;
        queueSubmit(bridge, VK_NULL_HANDLE);
        coordinator.setInitialWaitSemaphore(bridgeSem);
    }
    LOGI("MULTIFRAME_QUEUE mode=%s lowpri=%d", useMfQueue ? "dualq" : "singleq",
         context.multiframeQueueLowPriority() ? 1 : 0);
    auto result = coordinator.run(
        burst.frames, burst.referenceIndex,
        [&burst, dumpRzslRequested](std::uint32_t index) {
            if (!dumpRzslRequested && burst.snapshot && index < burst.frames.size() && index != burst.referenceIndex &&
                (!burst.pinsMayRelease || burst.pinsMayRelease())) {
                burst.snapshot->release(burst.frames[index].raw.ref.frameId);
            }
            // Journal-backed: the companion's image goes once merged.
            if (burst.releaseFrame && index != burst.referenceIndex) burst.releaseFrame(index);
        },
        burst.loadFrame ? rawr::raw_gpu_pipeline::AndroidBurstCoordinator::FrameLoad(
                              [&burst](std::uint32_t index) { burst.loadFrame(index); })
                        : rawr::raw_gpu_pipeline::AndroidBurstCoordinator::FrameLoad{});

    return result;
}
}  // namespace rawrcam::capture::multiframe
