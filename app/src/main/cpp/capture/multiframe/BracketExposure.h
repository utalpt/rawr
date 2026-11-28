#pragma once
// Rawr 17U fork: HDR+ Bracketed - Steve's v26 patch (rawr-17u16-bracketed-v26), his code.

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <vector>

#include "capture/multiframe/MergeExposure.h"
#include "metadata/FrameMetadataSnapshot.h"

// Exposure bookkeeping for HDR+ bracketed bursts (merge algorithm 3): a ZSL
// burst underexposed below Camera2 AE for highlight headroom plus brighter
// post-shutter long frames (older captures: ZSL frames at the preview
// exposure plus darker post-shutter frames). Only ratios between exposures
// of one capture are used, so the unit is arbitrary.
namespace rawrcam::capture::multiframe {

// Frames within 1/8 EV of the darkest count as the darkest group (sensor
// exposure steps are not exact).
inline std::vector<std::uint32_t> darkestFrames(const std::vector<metadata::FrameMetadataSnapshot>& metadata) {
    double darkest = 0.0;
    for (const auto& m : metadata) {
        const double e = linearExposure(m);
        if (e > 0.0 && (darkest == 0.0 || e < darkest)) darkest = e;
    }
    std::vector<std::uint32_t> out;
    if (darkest <= 0.0) return out;
    for (std::uint32_t i = 0; i < metadata.size(); ++i) {
        const double e = linearExposure(metadata[i]);
        if (e > 0.0 && e <= darkest * 1.0905) out.push_back(i);  // 2^(1/8)
    }
    return out;
}

// Post-shutter frames (the ones after the first zslCount) more than 3/4 EV darker than the ZSL burst's median exposure: the
// under frames, which the merge keeps as a highlight layer instead of merging them.
inline std::uint32_t countUnderFrames(const std::vector<metadata::FrameMetadataSnapshot>& metadata, std::uint32_t zslCount) {
    if (zslCount == 0u || metadata.size() <= zslCount) return 0u;
    std::vector<double> zsl;
    for (std::uint32_t i = 0; i < zslCount; ++i) {
        const double e = linearExposure(metadata[i]);
        if (e > 0.0) zsl.push_back(e);
    }
    if (zsl.empty()) return 0u;
    std::nth_element(zsl.begin(), zsl.begin() + static_cast<std::ptrdiff_t>(zsl.size() / 2), zsl.end());
    const double base = zsl[zsl.size() / 2];
    std::uint32_t n = 0;
    for (std::size_t i = zslCount; i < metadata.size(); ++i) {
        const double e = linearExposure(metadata[i]);
        if (e > 0.0 && e < base / 1.682) ++n;  // 2^(3/4) darker or more (the planned under frames are at least 3/4 EV down)
    }
    return n;
}

// True when the burst holds frames more than 1/8 EV apart.
inline bool isExposureBracketed(const std::vector<metadata::FrameMetadataSnapshot>& metadata) {
    const auto dark = darkestFrames(metadata);
    std::size_t known = 0;
    for (const auto& m : metadata) known += linearExposure(m) > 0.0 ? 1u : 0u;
    return !dark.empty() && dark.size() < known;
}

// The reference of a bracketed burst. Candidates are the pre-shutter frames within kReferenceToleranceEv of the darkest one
// (Rawr AE drifts the exposure by a few tenths of an EV while you aim, which left only old frames inside 1/8 EV and put the
// reference about half a second before the press). The best press-time score wins, discounted by up to 35% for how much
// brighter than the darkest frame it is, because that costs the same amount of highlight margin in the window. If none was
// scored, the newest candidate. Frames after the press (the long frames) never qualify.
inline constexpr double kReferenceToleranceEv = 0.4;

inline std::uint32_t bracketReference(const std::vector<metadata::FrameMetadataSnapshot>& metadata,
                                      const std::vector<float>& scores, std::uint32_t zslCount, std::uint32_t fallback) {
    const std::uint32_t n = std::min<std::uint32_t>(zslCount, static_cast<std::uint32_t>(metadata.size()));
    double darkest = 0.0;
    for (std::uint32_t i = 0; i < n; ++i) {
        const double e = linearExposure(metadata[i]);
        if (e > 0.0 && (darkest == 0.0 || e < darkest)) darkest = e;
    }
    if (darkest <= 0.0) return fallback;
    const double limit = std::exp2(kReferenceToleranceEv);
    const bool scored = scores.size() == metadata.size();
    std::uint32_t best = fallback, newest = fallback;
    bool haveBest = false, haveNewest = false;
    double bestValue = 0.0;
    for (std::uint32_t i = 0; i < n; ++i) {
        const double e = linearExposure(metadata[i]);
        if (!(e > 0.0) || e > darkest * limit) continue;
        newest = i;
        haveNewest = true;
        if (scored && scores[i] > 0.0f) {
            const double excessEv = std::log2(e / darkest);
            const double value = double(scores[i]) * (1.0 - 0.35 * excessEv / kReferenceToleranceEv);
            if (value > bestValue) {
                bestValue = value;
                best = i;
                haveBest = true;
            }
        }
    }
    if (haveBest) return best;
    return haveNewest ? newest : fallback;
}

// EV between the brightest frame (the preview exposure) and the reference,
// i.e. how much the merged result must be lifted to match the viewfinder.
// Older dark-frame captures only; see headroomLiftEv.
inline float bracketLiftEv(const std::vector<metadata::FrameMetadataSnapshot>& metadata,
                           std::uint32_t referenceIndex) noexcept {
    if (referenceIndex >= metadata.size()) return 0.0f;
    const double ref = linearExposure(metadata[referenceIndex]);
    double brightest = 0.0;
    for (const auto& m : metadata) brightest = std::max(brightest, linearExposure(m));
    if (ref <= 0.0 || brightest <= ref) return 0.0f;
    return float(std::log2(brightest / ref));
}

// EV the reference must be lifted by to reach the Camera2 AE exposure the
// underexposed burst was taken below (MultiframeTuning::aeLinearExposure);
// 0 when unknown or not underexposed.
inline float headroomLiftEv(float aeLinearExposure, const metadata::FrameMetadataSnapshot& reference) noexcept {
    const double exposure = linearExposure(reference);
    if (!(aeLinearExposure > 0.0f) || !(exposure > 0.0)) return 0.0f;
    return std::max(0.0f, float(std::log2(double(aeLinearExposure) / exposure)));
}


}  // namespace rawrcam::capture::multiframe
