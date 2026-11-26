#pragma once

#include <algorithm>
#include <cstdint>
#include <optional>
#include <string>
#include <string_view>
#include <vector>

namespace rawrcam::camera {

// A camera can advertise a fixed 60 fps AE range while none of its sensor modes delivers the RAW
// stream at that rate. Asked for 60 fps, the Xiaomi 15 HAL switches to the table's 60 fps mode and
// writes that mode's frame, tightly packed, into the head of the RAW buffer; the rest of the buffer
// keeps whatever it held before:
// - A mode of the stream's width lands on the stream's rows: 23 mm 4096x2560 in 4096x3072, front
//   3264x1836 in 3264x2448. Cropping the rows past it (image.cropEdgesHighFps) leaves a clean frame.
// - A narrower mode does not: the 14 mm's 3456x2160 frame read as 4080x3072 is stripes.
// - A request key that holds the sensor in a 30 fps mode (sensor_meta_data.current_mode) clashes
//   with the 60 fps mode: every request fails until the camera provider aborts.
// The vendor sensor-mode table says which rates each size really runs at.
inline constexpr const char* kQuicSensorModeTableKey = "org.quic.camera2.sensormode.info.SensorModeTable";
inline constexpr const char* kQuicCurrentModeKey = "org.codeaurora.qcamera3.sensor_meta_data.current_mode";

struct SensorModeEntry {
    int32_t width = 0;
    int32_t height = 0;
    int32_t fps = 0;
};

// org.quic.camera2.sensormode.info.SensorModeTable: modeCount, fieldsPerMode, then per mode
// width, height, maxFps (and any further fields). Entries keep the table's order: current_mode
// values index it. A malformed table parses as empty.
[[nodiscard]] inline std::vector<SensorModeEntry> parseQuicSensorModeTable(const int32_t* values, uint32_t count) {
    std::vector<SensorModeEntry> modes;
    if (!values || count < 2) return modes;
    const int64_t modeCount = values[0];
    const int64_t fields = values[1];
    if (modeCount <= 0 || fields < 3 || 2 + modeCount * fields > int64_t{count}) return modes;
    modes.reserve(static_cast<size_t>(modeCount));
    for (int64_t i = 0; i < modeCount; ++i) {
        const int32_t* mode = values + 2 + i * fields;
        modes.push_back({mode[0], mode[1], mode[2]});
    }
    return modes;
}

// Why the ceiling came out where it did, so the capabilities log can name the cause instead of
// leaving a bare "30 fps" to be guessed from the vendor table.
enum class SensorModeFpsReason : uint8_t {
    kNoExactSize,  // the table lists no mode of the stream's own size: 0, no limit
    kForcedMode,   // a request key holds the sensor in `modeIndex`
    kCropSearch,   // the fastest same-width mode covering the kept rows is `modeIndex`
    kFloor,        // modes of the size exist but none covers the kept rows: the 30 fps floor stands
};

struct SensorModeFpsDecision {
    int32_t ceiling = 0;
    int32_t modeIndex = -1;  // table entry that set the ceiling; -1 when none did
    SensorModeFpsReason reason = SensorModeFpsReason::kNoExactSize;
};

[[nodiscard]] inline const char* sensorModeFpsReasonName(SensorModeFpsReason reason) noexcept {
    switch (reason) {
        case SensorModeFpsReason::kForcedMode:
            return "forcedMode";
        case SensorModeFpsReason::kCropSearch:
            return "cropSearch";
        case SensorModeFpsReason::kFloor:
            return "floor";
        case SensorModeFpsReason::kNoExactSize:
            break;
    }
    return "noExactSize";
}

// The ceiling and the table entry behind it. Highest fixed rate above 30 fps a RAW stream may
// request; 0 = no limit, because the table lists no mode of exactly the stream's size and so
// cannot speak for it. Rates up to 30 fps are never limited here (full-size vendor modes have
// their own profile frame rate).
// - forcedMode: the table index a request key holds the sensor in; its own rate is the ceiling.
// - keptRowsEnd: the row past the last one the pipeline keeps above 30 fps (stream height minus
//   the high-fps bottom crop). A mode serves the stream when it has the stream's width, so its rows
//   land on the stream's stride, and covers every kept row.
[[nodiscard]] inline SensorModeFpsDecision sensorModeFpsDecision(const std::vector<SensorModeEntry>& modes,
                                                                 int32_t rawWidth, int32_t rawHeight,
                                                                 int32_t keptRowsEnd,
                                                                 std::optional<int32_t> forcedMode = std::nullopt) {
    SensorModeFpsDecision out;
    const auto exact = std::find_if(modes.begin(), modes.end(), [&](const SensorModeEntry& mode) {
        return mode.width == rawWidth && mode.height == rawHeight && mode.fps > 0;
    });
    if (exact == modes.end()) return out;
    if (forcedMode && *forcedMode >= 0 && static_cast<size_t>(*forcedMode) < modes.size()) {
        out.modeIndex = *forcedMode;
        out.ceiling = std::max(modes[static_cast<size_t>(*forcedMode)].fps, int32_t{30});
        out.reason = SensorModeFpsReason::kForcedMode;
        return out;
    }
    int32_t fastest = 0;
    for (size_t i = 0; i < modes.size(); ++i) {
        const SensorModeEntry& mode = modes[i];
        if (mode.width == rawWidth && mode.height <= rawHeight && mode.height >= keptRowsEnd && mode.fps > fastest) {
            fastest = mode.fps;
            out.modeIndex = static_cast<int32_t>(i);
        }
    }
    out.ceiling = std::max(fastest, int32_t{30});
    out.reason = out.modeIndex >= 0 ? SensorModeFpsReason::kCropSearch : SensorModeFpsReason::kFloor;
    return out;
}

[[nodiscard]] inline int32_t sensorModeFpsCeiling(const std::vector<SensorModeEntry>& modes, int32_t rawWidth,
                                                  int32_t rawHeight, int32_t keptRowsEnd,
                                                  std::optional<int32_t> forcedMode = std::nullopt) {
    return sensorModeFpsDecision(modes, rawWidth, rawHeight, keptRowsEnd, forcedMode).ceiling;
}

// The parsed table as the capabilities log prints it: `index:widthxheight@fps`, comma separated, so
// a lens's `current_mode` can be read back against the entries it indexes. Bounded, so a vendor
// table with hundreds of modes cannot flood the log line.
inline constexpr size_t kSensorModeTableLogEntries = 64;

[[nodiscard]] inline std::string describeSensorModeTable(const std::vector<SensorModeEntry>& modes) {
    std::string out;
    const size_t limit = std::min(modes.size(), kSensorModeTableLogEntries);
    for (size_t i = 0; i < limit; ++i) {
        if (i) out += ',';
        out += std::to_string(i);
        out += ':';
        out += std::to_string(modes[i].width);
        out += 'x';
        out += std::to_string(modes[i].height);
        out += '@';
        out += std::to_string(modes[i].fps);
    }
    if (modes.size() > limit) out += ",...+" + std::to_string(modes.size() - limit);
    return out;
}

// Where the ceiling applies. It is on only on the devices it was validated on, matched by
// ro.product.device; everywhere else the fps ranges are exactly what the camera advertises.
//
// `debug.rawr.sensor_mode_fps` overrides this on any device: "1" turns the ceiling on, "0" turns it
// off. `setting` is that property's first character, or '\0' when it is unset.
//
// Validated:
// - Xiaomi 15 ("dada"): every lens advertises [60,60]; no sensor mode runs a full RAW size above
//   30 fps; 23 mm and front run 60 fps through a same-width mode with the high-fps crop.
[[nodiscard]] inline bool sensorModeFpsCeilingAllowed(std::string_view productDevice, char setting) noexcept {
    if (setting == '1') return true;
    if (setting == '0') return false;
    return productDevice == "dada";
}

}  // namespace rawrcam::camera
