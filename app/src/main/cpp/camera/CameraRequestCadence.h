#pragma once

#include <algorithm>
#include <array>
#include <optional>

#include "camera/CameraControlTypes.h"

namespace rawrcam::camera {

// Bracket requests keep the recipe's cadence too; legacy lenses retain 30 fps.
inline int64_t cameraBracketFrameDuration(const CameraControlState& state, int64_t exposureNs) {
    const int64_t period = state.capabilities.frameRate > 0
                              ? 1'000'000'000LL / state.capabilities.frameRate : 33'333'333LL;
    const int64_t duration = std::max(period, exposureNs);
    return state.capabilities.maxFrameDurationNs > 0
               ? std::min(duration, state.capabilities.maxFrameDurationNs) : duration;
}

inline bool cameraSupportsRecordingFps(const CameraControlCapabilities& caps, int fps) {
    return fps > 0 && (caps.frameRate <= 0 || caps.frameRate == fps) &&
           (caps.sensorModeFpsCeiling <= 0 || fps <= caps.sensorModeFpsCeiling) &&
           caps.exposureTimeMinNs <= 1'000'000'000LL / fps &&
           std::any_of(caps.aeTargetFpsRanges.begin(), caps.aeTargetFpsRanges.end(), [fps](const auto& range) {
               return range[0] == fps && range[1] == fps;
           });
}

// The idle video cadence the camera is asked for: the chosen rate, held under the RAW stream's
// sensor-mode ceiling (SensorModeFpsPolicy.h). The chosen rate itself stays in the state.
inline int32_t cameraVideoPreviewFps(const CameraControlState& state) {
    const auto ceiling = state.capabilities.sensorModeFpsCeiling;
    return ceiling > 0 ? std::min(state.videoPreviewFps, ceiling) : state.videoPreviewFps;
}

// Shared by session parameters, repeating requests and frozen provenance.
inline std::optional<std::array<int32_t, 2>> cameraTargetFpsRange(const CameraControlState& state,
                                                                  bool priorityRequested) {
    // A vendor sensor mode that runs at one rate (Xiaomi 75 mm 50 MP: 24 fps) keeps it on every request.
    if (state.capabilities.frameRate > 0)
        return std::array<int32_t, 2>{state.capabilities.frameRate, state.capabilities.frameRate};
    if (state.recordingFps > 0) return std::array<int32_t, 2>{state.recordingFps, state.recordingFps};
    const auto& caps = state.capabilities;
    // Idle video mode above 30 fps runs the viewfinder at the recording rate:
    // the sensor then stays in the same readout mode when recording starts
    // (no reframe, no restart drops). 30 fps and below keep the AE range.
    const auto previewFps = cameraVideoPreviewFps(state);
    if (state.videoMode && previewFps > 30 &&
        std::any_of(caps.aeTargetFpsRanges.begin(), caps.aeTargetFpsRanges.end(), [&](const auto& range) {
            return range[0] == previewFps && range[1] == previewFps;
        }))
        return std::array<int32_t, 2>{previewFps, previewFps};
    if (priorityRequested && caps.priorityAeTargetFpsMin > 0 &&
        caps.priorityAeTargetFpsMax >= caps.priorityAeTargetFpsMin) {
        int32_t floor = caps.priorityAeTargetFpsMin;
        if (state.autoMinFps >= 5 && state.autoMinFps < 30) floor = std::max(floor, state.autoMinFps);
        return std::array<int32_t, 2>{std::min(floor, caps.priorityAeTargetFpsMax), caps.priorityAeTargetFpsMax};
    }
    if ((state.exposureMode == ExposureControlMode::Auto || caps.targetFpsRangeRequired) && state.autoMinFps >= 5 &&
        state.autoMinFps < 30) {
        const auto maximum = caps.priorityAeTargetFpsMax > 0 ? caps.priorityAeTargetFpsMax : 30;
        return std::array<int32_t, 2>{std::min(state.autoMinFps, maximum), maximum};
    }
    if (caps.targetFpsRangeRequired) {
        const auto maximum = caps.priorityAeTargetFpsMax > 0 ? caps.priorityAeTargetFpsMax : 30;
        return std::array<int32_t, 2>{maximum, maximum};
    }
    return std::nullopt;
}

}  // namespace rawrcam::camera
