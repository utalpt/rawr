// Fixed rates above 30 fps need a sensor mode at the RAW stream's own size (camera/SensorModeFpsPolicy.h).
// The tables are the Xiaomi 15's (dada), as its HAL reports them, plus a synthetic Xiaomi 17 Ultra one
// shaped after its profile (the 17U's own table is not on file; the device log prints it, see
// CAMERA_CONTROL_CAPS).
#include <cassert>
#include <cstdint>
#include <iostream>
#include <optional>
#include <string>
#include <vector>

#include "camera/SensorModeFpsPolicy.h"

using rawrcam::camera::describeSensorModeTable;
using rawrcam::camera::parseQuicSensorModeTable;
using rawrcam::camera::sensorModeFpsCeiling;
using rawrcam::camera::sensorModeFpsCeilingAllowed;
using rawrcam::camera::sensorModeFpsDecision;
using rawrcam::camera::SensorModeEntry;
using rawrcam::camera::SensorModeFpsDecision;
using rawrcam::camera::SensorModeFpsReason;

namespace {

int32_t ceilingFor(const std::vector<int32_t>& table, int32_t width, int32_t height, int32_t keptRowsEnd,
                   std::optional<int32_t> forcedMode = std::nullopt) {
    return sensorModeFpsCeiling(parseQuicSensorModeTable(table.data(), static_cast<uint32_t>(table.size())), width,
                                height, keptRowsEnd, forcedMode);
}

SensorModeFpsDecision decisionFor(const std::vector<int32_t>& table, int32_t width, int32_t height,
                                  int32_t keptRowsEnd, std::optional<int32_t> forcedMode = std::nullopt) {
    return sensorModeFpsDecision(parseQuicSensorModeTable(table.data(), static_cast<uint32_t>(table.size())), width,
                                 height, keptRowsEnd, forcedMode);
}

// 14 mm (camera 3): the only 60 fps mode is 3456x2160.
const std::vector<int32_t> kUltraWide = {10,   3,    8160, 6144, 10,  4080, 3072, 30,  4080, 2296, 30,  3456,
                                         2160, 30,   3456, 2160, 60,  3680, 2760, 30,  4080, 3072, 21,  2560,
                                         1920, 30,   1920, 1080, 240, 1440, 1080, 10};
// 23 mm (camera 2): 4096x3072 only at 30 fps; 4096x2560 and 4096x2304 reach 60.
const std::vector<int32_t> kWide = {
    32,   3,    8192, 6144, 30,   4096, 3072, 30,   4096, 3072, 30,   4096, 3072, 30,   4096, 3072, 30,
    4096, 3072, 30,   4096, 3072, 30,   8192, 6144, 30,   4096, 2304, 30,   4096, 2304, 30,   4096, 2304,
    30,   4096, 2304, 30,   4096, 2304, 30,   4096, 2304, 30,   8192, 4608, 30,   4096, 2560, 30,   4096,
    2560, 30,   4096, 2560, 30,   4096, 2560, 30,   4096, 2560, 30,   4096, 2560, 30,   4096, 1840, 30,
    4096, 1840, 30,   4096, 1840, 30,   2560, 1920, 30,   4096, 2560, 60,   7680, 4320, 30,   1920, 1080,
    240,  1280, 720,  240,  1088, 612,  480,  8192, 6144, 12,   4096, 2304, 60};
// 59 mm (camera 4): 4096x3072 only at 30 fps; 4096x2560 reaches 60.
const std::vector<int32_t> kTele = {20,   3,    8192, 6144, 15,  4096, 3072, 30,   4096, 3072, 30,   4096, 3072,
                                    30,   8192, 6144, 15,   4096, 3072, 30,   4096, 3072, 30,   4096, 2560, 30,
                                    4096, 2560, 30,   4096, 2560, 30,   4096, 2560, 60,   4096, 2304, 30,   4096,
                                    2304, 30,   4096, 2304, 30,   8192, 4608, 15,   4096, 2304, 30,   4096, 2304,
                                    30,   1920, 1080, 240,  1280, 720,  240,  4096, 2560, 30};

// Front (camera 1): 3264x2448 only at 30 fps; 3264x1836 reaches 60.
const std::vector<int32_t> kFront = {8,    3,    6528, 4896, 15,   3264, 2448, 30,  3264, 2448, 30,  3264,
                                     1836, 30,   3264, 1836, 30,   3264, 1836, 60,  1632, 918,  120, 1632,
                                     1224, 10};

// Xiaomi 17 Ultra's 23 mm (camera 2), synthetic, shaped after the profile's `1x DCG`: the entry pins
// `current_mode=4`, the full size has no 60 fps mode, a 4096x2560 crop does.
const std::vector<int32_t> kWide17uDcg = {6,   3,    4096, 3072, 30,  4096, 3072, 30, 8192, 6144,
                                          30,  4096, 2560, 30,  4096, 2560, 30, 4096, 2560, 60};

}  // namespace

int main() {
    // Full-size RAW streams stop at 30 fps.
    assert(ceilingFor(kUltraWide, 4080, 3072, 3072) == 30);
    assert(ceilingFor(kWide, 4096, 3072, 3072) == 30);
    assert(ceilingFor(kTele, 4096, 3072, 3072) == 30);
    assert(ceilingFor(kFront, 3264, 2448, 2448) == 30);
    // Cropping the rows a same-width 60 fps mode leaves stale opens 60 fps (23 mm, 59 mm, front).
    assert(ceilingFor(kWide, 4096, 3072, 2560) == 60);
    assert(ceilingFor(kTele, 4096, 3072, 2560) == 60);
    assert(ceilingFor(kFront, 3264, 2448, 2448 - 612) == 60);
    // A crop that keeps more rows than the 60 fps mode writes does not.
    assert(ceilingFor(kWide, 4096, 3072, 2561) == 30);
    assert(ceilingFor(kFront, 3264, 2448, 1837) == 30);
    // 14 mm: the 60 fps mode is narrower, so no crop helps.
    assert(ceilingFor(kUltraWide, 4080, 3072, 2160) == 30);
    assert(ceilingFor(kUltraWide, 4080, 3072, 0) == 30);
    // A request key holding the sensor in a 30 fps mode keeps the stream at 30 (mode 1 is the
    // 23 mm's 4096x3072; 20 is the DCG lens's 4096x2560), whatever the crop.
    assert(ceilingFor(kWide, 4096, 3072, 2560, 1) == 30);
    assert(ceilingFor(kWide, 4096, 3072, 2560, 20) == 30);
    assert(ceilingFor(kWide, 4096, 3072, 2560, 25) == 60);
    // An index past the table is ignored.
    assert(ceilingFor(kWide, 4096, 3072, 2560, 99) == 60);
    assert(ceilingFor(kWide, 4096, 3072, 2560, -1) == 60);

    // Xiaomi 17 Ultra `1x DCG`: the profile's request key holds the sensor in mode 4, a 4096x2560
    // readout at 30 fps, so the ceiling is 30 even though mode 5 reaches 60 on the same width. This is
    // the case the 17U's 1x DCG lens reports, and the one the capabilities log has to explain.
    const auto dcg = decisionFor(kWide17uDcg, 4096, 3072, 2560, 4);
    assert(dcg.ceiling == 30);
    assert(dcg.modeIndex == 4);
    assert(dcg.reason == SensorModeFpsReason::kForcedMode);
    assert(ceilingFor(kWide17uDcg, 4096, 3072, 2560, 4) == 30);
    // Without the key the same crop opens 60 fps: it is the entry, not the size, that caps the stream.
    const auto cropped17u = decisionFor(kWide17uDcg, 4096, 3072, 2560);
    assert(cropped17u.ceiling == 60);
    assert(cropped17u.modeIndex == 5);
    assert(cropped17u.reason == SensorModeFpsReason::kCropSearch);
    // Full size: the 30 fps comes from the first 4096x3072 entry.
    const auto full17u = decisionFor(kWide17uDcg, 4096, 3072, 3072);
    assert(full17u.ceiling == 30);
    assert(full17u.modeIndex == 0);
    assert(full17u.reason == SensorModeFpsReason::kCropSearch);
    // An index the table does not list is ignored, and so is a size it does not have.
    const auto past17u = decisionFor(kWide17uDcg, 4096, 3072, 2560, 99);
    assert(past17u.ceiling == 60 && past17u.modeIndex == 5);
    const auto otherSize = decisionFor(kWide17uDcg, 3840, 2160, 2160);
    assert(otherSize.ceiling == 0);
    assert(otherSize.modeIndex == -1);
    assert(otherSize.reason == SensorModeFpsReason::kNoExactSize);
    // Kept rows past every same-width mode leave the floor in place, naming no entry.
    const auto floorOnly = decisionFor(kWide17uDcg, 4096, 3072, 3073);
    assert(floorOnly.ceiling == 30);
    assert(floorOnly.modeIndex == -1);
    assert(floorOnly.reason == SensorModeFpsReason::kFloor);
    // Slower full-size modes never pull the ceiling under 30 fps.
    assert(ceilingFor(kUltraWide, 8160, 6144, 6144) == 30);
    assert(ceilingFor(kWide, 8192, 6144, 6144, 32) == 30);
    // A size the table does not list is not limited.
    assert(ceilingFor(kUltraWide, 4000, 3000, 3000) == 0);
    assert(ceilingFor(kWide, 4096, 2560, 2560) == 60);

    // Malformed tables parse as empty, so nothing is limited.
    assert(parseQuicSensorModeTable(nullptr, 0).empty());
    assert(parseQuicSensorModeTable(kUltraWide.data(), 1).empty());
    assert(parseQuicSensorModeTable(kUltraWide.data(), static_cast<uint32_t>(kUltraWide.size() - 1)).empty());
    const std::vector<int32_t> twoFields = {1, 2, 4080, 3072};
    assert(parseQuicSensorModeTable(twoFields.data(), static_cast<uint32_t>(twoFields.size())).empty());
    const std::vector<int32_t> noModes = {0, 3};
    assert(parseQuicSensorModeTable(noModes.data(), static_cast<uint32_t>(noModes.size())).empty());
    // Entries keep the table's order, invalid ones included, so current_mode indexes them.
    const std::vector<int32_t> holes = {2, 3, 0, 0, 0, 4080, 3072, 30};
    const auto kept = parseQuicSensorModeTable(holes.data(), static_cast<uint32_t>(holes.size()));
    assert(kept.size() == 2 && kept[1].width == 4080);
    // Extra fields per mode are skipped.
    const std::vector<int32_t> fourFields = {2, 4, 4080, 3072, 30, 7, 3456, 2160, 60, 7};
    const auto wide = parseQuicSensorModeTable(fourFields.data(), static_cast<uint32_t>(fourFields.size()));
    assert(wide.size() == 2 && wide[1].width == 3456 && wide[1].height == 2160 && wide[1].fps == 60);
    assert(parseQuicSensorModeTable(kUltraWide.data(), static_cast<uint32_t>(kUltraWide.size())).size() == 10);
    assert(parseQuicSensorModeTable(kWide.data(), static_cast<uint32_t>(kWide.size())).size() == 32);

    // The log prints the parsed table with its indices, so a lens's `current_mode` reads back against it.
    const std::string described = describeSensorModeTable(
        parseQuicSensorModeTable(kWide17uDcg.data(), static_cast<uint32_t>(kWide17uDcg.size())));
    assert(described == "0:4096x3072@30,1:4096x3072@30,2:8192x6144@30,3:4096x2560@30,4:4096x2560@30,5:4096x2560@60");
    assert(describeSensorModeTable({}).empty());
    // A long table is cut, with the count of what was left out, so one log line stays readable.
    const std::vector<SensorModeEntry> many(70, SensorModeEntry{4080, 3072, 30});
    const std::string cut = describeSensorModeTable(many);
    assert(cut.find("63:4080x3072@30") != std::string::npos);
    assert(cut.find("64:") == std::string::npos);
    assert(cut.compare(cut.size() - 6, 6, ",...+" + std::to_string(6)) == 0);

    // Xiaomi 15: validated, on by default, and the property can still turn it off.
    assert(sensorModeFpsCeilingAllowed("dada", '\0'));
    assert(sensorModeFpsCeilingAllowed("dada", '1'));
    assert(!sensorModeFpsCeilingAllowed("dada", '0'));
    // Any other device: off unless opted in by hand. Only the exact codename matches.
    for (const char* device : {"", "other", "dada_eea", "dad", "DADA"}) {
        assert(!sensorModeFpsCeilingAllowed(device, '\0'));
        assert(sensorModeFpsCeilingAllowed(device, '1'));
        assert(!sensorModeFpsCeilingAllowed(device, '0'));
        assert(!sensorModeFpsCeilingAllowed(device, 'x'));
    }
    std::cout << "sensor_mode_fps_policy_test: ok\n";
    return 0;
}
