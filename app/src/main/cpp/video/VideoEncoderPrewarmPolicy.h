#pragma once

#include <cstdint>
#include <sstream>
#include <string>

namespace rawrcam::video {

// The exact HEVC input a recording configures. Two recordings with equal specs can
// share a prepared codec; anything else has to reconfigure, so it must not reuse one.
struct HevcVideoEncoderSpec final {
    int width = 0;
    int height = 0;
    int fps = 0;
    int bitrate = 0;
    int intraFrames = 0;
    int bitrateMode = 0;
    int maxBFrames = 0;
    int bitDepth = 0;

    [[nodiscard]] bool operator==(const HevcVideoEncoderSpec& other) const noexcept {
        return width == other.width && height == other.height && fps == other.fps && bitrate == other.bitrate &&
               intraFrames == other.intraFrames && bitrateMode == other.bitrateMode &&
               maxBFrames == other.maxBFrames && bitDepth == other.bitDepth;
    }
    [[nodiscard]] bool operator!=(const HevcVideoEncoderSpec& other) const noexcept { return !(*this == other); }
};

// Refuses what NativeAvRecorder's own constructor would refuse, so a prewarm never
// prepares a codec the recorder is going to reject.
[[nodiscard]] inline bool hevcSpecUsable(const HevcVideoEncoderSpec& spec) noexcept {
    return spec.width > 0 && spec.height > 0 && (spec.width & 1) == 0 && (spec.height & 1) == 0 &&
           spec.fps >= 1 && spec.fps <= 240 && spec.bitrate >= 1'000'000 && spec.bitrate <= 400'000'000 &&
           spec.intraFrames >= 1 && spec.intraFrames <= 480 && spec.bitrateMode >= -1 &&
           spec.bitrateMode <= 2 && spec.maxBFrames >= -1 && spec.maxBFrames <= 4 &&
           (spec.bitDepth == 8 || spec.bitDepth == 10);
}

[[nodiscard]] inline std::string hevcSpecText(const HevcVideoEncoderSpec& spec) {
    std::ostringstream out;
    out << spec.width << 'x' << spec.height << '@' << spec.fps << "f " << spec.bitrate / 1'000'000 << "Mbps"
        << " depth" << spec.bitDepth << " gop" << spec.intraFrames << " rc" << spec.bitrateMode << " b"
        << spec.maxBFrames;
    return out.str();
}

// What the pool currently holds: nothing, or a configured codec of this spec and age.
struct PreparedEncoder {
    bool present = false;
    HevcVideoEncoderSpec spec{};
    int64_t ageNs = 0;
};

// A configured encoder keeps a hardware session open. It is useful for the recording
// about to start, not for one that may never happen: past this age the pool discards
// it rather than handing a stale instance to the recorder.
inline constexpr int64_t kHevcPrewarmTtlNs = 90'000'000'000LL;  // 90 s

// A prepared encoder holds a hardware session, so one entry is all the pool keeps
// and never while a recording is running.
enum class PrewarmTake : int { Reused, Empty, Mismatch, Expired };

[[nodiscard]] inline PrewarmTake prewarmTake(const HevcVideoEncoderSpec& requested,
                                             const PreparedEncoder& prepared) noexcept {
    if (!prepared.present) return PrewarmTake::Empty;
    if (prepared.spec != requested) return PrewarmTake::Mismatch;
    if (prepared.ageNs >= kHevcPrewarmTtlNs) return PrewarmTake::Expired;
    return PrewarmTake::Reused;
}

[[nodiscard]] inline const char* prewarmTakeText(PrewarmTake take) noexcept {
    switch (take) {
        case PrewarmTake::Reused: return "reused";
        case PrewarmTake::Empty: return "empty";
        case PrewarmTake::Mismatch: return "mismatch";
        case PrewarmTake::Expired: return "expired";
    }
    return "empty";
}

// Whether warm() has to build: only when the pool cannot serve the next recording, and
// never during a recording — a second 4K HEVC instance would compete for the same
// hardware encoder and slow the one that is producing frames.
[[nodiscard]] inline bool prewarmNeeded(const HevcVideoEncoderSpec& requested, bool recordingActive,
                                        const PreparedEncoder& prepared) noexcept {
    if (!hevcSpecUsable(requested) || recordingActive) return false;
    if (!prepared.present) return true;
    return prewarmTake(requested, prepared) != PrewarmTake::Reused;
}

// The pool was measured on one device, so there it is on by default and everywhere else
// it stays off until the same A/B is repeated. The property overrides the default both
// ways: it is the kill switch when the pool misbehaves in the field, and the switch to
// trial it on the next device.
inline constexpr char kPrewarmDefaultOnDevice[] = "dada";

[[nodiscard]] inline bool prewarmEnabled(const std::string& property, const std::string& device) noexcept {
    if (property == "1" || property == "true" || property == "on") return true;
    if (property == "0" || property == "false" || property == "off") return false;
    return device == kPrewarmDefaultOnDevice;
}

}  // namespace rawrcam::video
