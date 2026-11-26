#pragma once

#include <android/native_window.h>
#include <media/NdkMediaCodec.h>

#include <cstdint>
#include <mutex>
#include <string>

#include "video/VideoEncoderPrewarmPolicy.h"

namespace rawrcam::video {

// One HEVC encoder prepared ahead of the next recording: created, configured and with
// its input Surface ready, so NativeAvRecorder only has to start it. The rest of video
// startup (Vulkan Surface chain, YUV buffers) is handled by VideoSession's own prewarm.
//
// Single slot on purpose: two 4K HEVC instances fight over the same hardware encoder,
// and the recorder is the only consumer. Everything here is process-wide because the
// recording path and the preview path do not share an object graph.
class VideoEncoderPrewarm final {
public:
    [[nodiscard]] static VideoEncoderPrewarm& instance();

    // On by default where it was measured (dada); the property overrides either way.
    [[nodiscard]] bool enabled() const;

    // Prepares the encoder for [spec], replacing one that no longer matches. Never
    // throws: a failure only leaves the pool empty. Returns true when a usable entry
    // is pooled afterwards.
    bool warm(const HevcVideoEncoderSpec& spec) noexcept;

    // Hands the pooled encoder to a recording and starts it. Returns nullptr on an
    // empty pool, a spec mismatch, a stale entry, or a failed start; the caller then
    // creates the encoder itself, exactly as before.
    AMediaCodec* take(const HevcVideoEncoderSpec& spec, ANativeWindow** window, std::string* codecName) noexcept;

    // Frees the pooled encoder: leaving Video mode, or a recording that ended without
    // consuming it.
    void release() noexcept;

    // While a recording owns an encoder nothing is warmed: a second session would
    // compete with the one producing frames.
    void setRecordingActive(bool active) noexcept;

    [[nodiscard]] std::string statsJson() const;

private:
    struct Entry final {
        AMediaCodec* codec = nullptr;
        ANativeWindow* window = nullptr;
        std::string codecName;
        HevcVideoEncoderSpec spec{};
        int64_t createdAtNs = 0;
    };

    VideoEncoderPrewarm() = default;
    [[nodiscard]] PreparedEncoder held() const noexcept;
    static void freeEntry(Entry& entry) noexcept;

    mutable std::mutex mutex_;
    Entry entry_;
    bool recordingActive_ = false;
};

}  // namespace rawrcam::video
