#include "video/HevcEncoderSetup.h"

#include <android/log.h>
#include <media/NdkMediaFormat.h>

#include <stdexcept>

namespace rawrcam::video {
namespace {
constexpr char kTag[] = "RawrNativeVideo";
constexpr int kSurfaceColorFormat = 2130708361;  // MediaCodecInfo.COLOR_FormatSurface.

void requireMedia(media_status_t status, const char* operation) {
    if (status != AMEDIA_OK) throw std::runtime_error(std::string(operation) + " status=" + std::to_string(status));
}

// Null when the device has no HEVC encoder; a software encoder is refused outright.
AMediaCodec* createHevcEncoder(std::string* codecName) {
    AMediaCodec* codec = AMediaCodec_createEncoderByType("video/hevc");
    if (!codec) return nullptr;
    char* name = nullptr;
    requireMedia(AMediaCodec_getName(codec, &name), "query HEVC encoder");
    *codecName = name ? name : "";
    if (name) AMediaCodec_releaseName(codec, name);
    if (codecName->rfind("c2.android.", 0) == 0 || codecName->rfind("OMX.google.", 0) == 0) {
        (void)AMediaCodec_delete(codec);
        throw std::runtime_error("software HEVC encoder selected: " + *codecName);
    }
    return codec;
}

media_status_t configureWith(AMediaCodec* codec, const HevcVideoEncoderSpec& spec, bool realtime) {
    AMediaFormat* format = AMediaFormat_new();
    if (!format) throw std::runtime_error("video format allocation failed");
    AMediaFormat_setString(format, AMEDIAFORMAT_KEY_MIME, "video/hevc");
    AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_WIDTH, spec.width);
    AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_HEIGHT, spec.height);
    AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_COLOR_FORMAT, kSurfaceColorFormat);
    AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_PROFILE,
                          spec.bitDepth == 8 ? kHevcMainProfile : kHevcMain10Profile);
    AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_BIT_RATE, spec.bitrate);
    AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_FRAME_RATE, spec.fps);
    // i-frame-interval is in seconds and accepts a float; 0 asks for every frame to be a sync frame.
    AMediaFormat_setFloat(format, AMEDIAFORMAT_KEY_I_FRAME_INTERVAL,
                          spec.intraFrames <= 1 ? 0.0f
                                                : static_cast<float>(spec.intraFrames) / static_cast<float>(spec.fps));
    if (spec.bitrateMode >= 0) AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_BITRATE_MODE, spec.bitrateMode);
    // Optional codec key. A codec may reject the request during configure;
    // omit it when left at the automatic default.
    const int maxBFrames = spec.intraFrames <= 1 ? 0 : spec.maxBFrames;
    if (maxBFrames >= 0) AMediaFormat_setInt32(format, "max-bframes", maxBFrames);
    // Preserve the encoder's BT.709 RGB-to-YCbCr conversion. LOG signalling
    // is rewritten after encoding, without changing the encoded samples.
    AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_COLOR_STANDARD, 1);
    AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_COLOR_TRANSFER, 3);
    AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_COLOR_RANGE, 2);
    // Realtime hints: operating-rate tells the codec driver the rate to clock for, and
    // priority 0 marks the session realtime. Without them a Qualcomm HEVC encoder can stay
    // in a lower clock state and fall behind at 4K60 (encoder-side drops, latency piling up
    // past 500 ms).
    if (realtime) {
        AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_OPERATING_RATE, spec.fps);
        AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_PRIORITY, 0);
    }
    const media_status_t status =
        AMediaCodec_configure(codec, format, nullptr, nullptr, AMEDIACODEC_CONFIGURE_FLAG_ENCODE);
    AMediaFormat_delete(format);
    return status;
}
}  // namespace

HevcEncoderOpening prepareHevcEncoder(const HevcVideoEncoderSpec& spec) {
    HevcEncoderOpening opening;
    opening.codec = createHevcEncoder(&opening.codecName);
    if (!opening.codec) return opening;  // The caller reports "HEVC encoder unavailable".
    opening.configureStatus = configureWith(opening.codec, spec, true);
    if (opening.configureStatus == AMEDIA_OK) {
        __android_log_print(ANDROID_LOG_INFO, kTag,
                            "VIDEO_ENCODER_REALTIME operatingRate=%d priority=0 codec=%s %dx%d", spec.fps,
                            opening.codecName.c_str(), spec.width, spec.height);
        return opening;
    }
    __android_log_print(ANDROID_LOG_WARN, kTag, "VIDEO_ENCODER_REALTIME_REFUSED status=%d codec=%s",
                        int(opening.configureStatus), opening.codecName.c_str());
    // A refused configure leaves no promise about the codec's state (the NDK has no reset):
    // the fallback runs on a fresh instance of the same encoder.
    (void)AMediaCodec_delete(opening.codec);
    opening.codec = createHevcEncoder(&opening.codecName);
    if (!opening.codec) return opening;  // The caller reports "HEVC encoder unavailable".
    opening.configureStatus = configureWith(opening.codec, spec, false);
    return opening;
}

}  // namespace rawrcam::video
