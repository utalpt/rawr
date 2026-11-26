#pragma once

#include <media/NdkMediaCodec.h>

#include <string>

#include "video/VideoEncoderPrewarmPolicy.h"

namespace rawrcam::video {

constexpr int kHevcMainProfile = 1;    // HEVCProfileMain.
constexpr int kHevcMain10Profile = 2;  // HEVCProfileMain10.

// How opening the encoder went: the codec to drive and whether it accepted the format.
// [codec] is nullptr when the device has no HEVC encoder at all.
struct HevcEncoderOpening final {
    AMediaCodec* codec = nullptr;
    media_status_t configureStatus = AMEDIA_OK;
    std::string codecName;
};

// Creates the platform HEVC encoder and configures it exactly as a recording does:
// realtime hints first, then — if the codec refuses them — a fresh instance of the same
// encoder without them, because a refused configure leaves no promise about the codec's
// state (the NDK has no reset). Throws for a software implementation, which cannot keep
// up with a 4K surface feed, exactly as the recorder always has.
HevcEncoderOpening prepareHevcEncoder(const HevcVideoEncoderSpec& spec);

}  // namespace rawrcam::video
