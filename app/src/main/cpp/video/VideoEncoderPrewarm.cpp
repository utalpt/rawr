#include "video/VideoEncoderPrewarm.h"

#include <android/log.h>
#include <sys/system_properties.h>
#include <time.h>

#include <cstdio>
#include <exception>
#include <utility>

#include "video/HevcEncoderSetup.h"

namespace rawrcam::video {
namespace {
constexpr char kTag[] = "RawrNativeVideo";
constexpr char kPrewarmProperty[] = "debug.rawr.video_encoder_prewarm";
constexpr char kDeviceProperty[] = "ro.product.device";

int64_t bootNs() noexcept {
    timespec ts{};
    clock_gettime(CLOCK_BOOTTIME, &ts);
    return static_cast<int64_t>(ts.tv_sec) * 1'000'000'000LL + ts.tv_nsec;
}

[[nodiscard]] double msBetween(int64_t fromNs, int64_t toNs) noexcept {
    return static_cast<double>(toNs - fromNs) / 1'000'000.0;
}

// Creates and configures the encoder and its input Surface, but does not start it:
// a codec that was never started is freed without a surface drain, and start() is
// the cheapest of the four calls (its cost is logged on take).
bool buildEncoder(const HevcVideoEncoderSpec& spec, AMediaCodec** codec, ANativeWindow** window,
                  std::string* codecName) noexcept {
    try {
        const HevcEncoderOpening opening = prepareHevcEncoder(spec);
        if (!opening.codec) {
            __android_log_print(ANDROID_LOG_WARN, kTag, "VIDEO_ENCODER_PREWARM failed reason=unavailable spec=%s",
                                hevcSpecText(spec).c_str());
            return false;
        }
        if (opening.configureStatus != AMEDIA_OK) {
            __android_log_print(ANDROID_LOG_WARN, kTag,
                                "VIDEO_ENCODER_PREWARM failed reason=configure status=%d codec=%s spec=%s",
                                int(opening.configureStatus), opening.codecName.c_str(), hevcSpecText(spec).c_str());
            (void)AMediaCodec_delete(opening.codec);
            return false;
        }
        ANativeWindow* surface = nullptr;
        const media_status_t surfaceStatus = AMediaCodec_createInputSurface(opening.codec, &surface);
        if (surfaceStatus != AMEDIA_OK || surface == nullptr) {
            __android_log_print(ANDROID_LOG_WARN, kTag,
                                "VIDEO_ENCODER_PREWARM failed reason=surface status=%d codec=%s", int(surfaceStatus),
                                opening.codecName.c_str());
            (void)AMediaCodec_delete(opening.codec);
            return false;
        }
        *codec = opening.codec;
        *codecName = opening.codecName;
        *window = surface;
        return true;
    } catch (const std::exception& error) {
        __android_log_print(ANDROID_LOG_WARN, kTag, "VIDEO_ENCODER_PREWARM failed reason=exception what=%s",
                            error.what());
        return false;
    }
}
}  // namespace

VideoEncoderPrewarm& VideoEncoderPrewarm::instance() {
    static VideoEncoderPrewarm pool;
    return pool;
}

bool VideoEncoderPrewarm::enabled() const {
    char value[PROP_VALUE_MAX] = {};
    char device[PROP_VALUE_MAX] = {};
    __system_property_get(kPrewarmProperty, value);
    __system_property_get(kDeviceProperty, device);
    return prewarmEnabled(value, device);
}

PreparedEncoder VideoEncoderPrewarm::held() const noexcept {
    PreparedEncoder prepared;
    prepared.present = entry_.codec != nullptr;
    prepared.spec = entry_.spec;
    prepared.ageNs = entry_.codec ? bootNs() - entry_.createdAtNs : 0;
    return prepared;
}

void VideoEncoderPrewarm::freeEntry(Entry& entry) noexcept {
    if (entry.codec) {
        // Never fed: stopping before deleting only releases the session, it cannot drain frames.
        (void)AMediaCodec_stop(entry.codec);
        (void)AMediaCodec_delete(entry.codec);
        entry.codec = nullptr;
    }
    if (entry.window) {
        ANativeWindow_release(entry.window);
        entry.window = nullptr;
    }
    entry.codecName.clear();
}

bool VideoEncoderPrewarm::warm(const HevcVideoEncoderSpec& spec) noexcept {
    if (!enabled()) return false;
    Entry displaced;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        const PreparedEncoder prepared = held();
        if (!prewarmNeeded(spec, recordingActive_, prepared)) {
            if (recordingActive_) {
                __android_log_print(ANDROID_LOG_INFO, kTag, "VIDEO_ENCODER_PREWARM skip reason=recording spec=%s",
                                    hevcSpecText(spec).c_str());
            }
            return prepared.present && prewarmTake(spec, prepared) == PrewarmTake::Reused;
        }
        displaced = std::move(entry_);
        entry_ = Entry{};
    }
    // Outside the lock: creating and tearing down a codec takes tens of milliseconds, and
    // a recording start must never wait on it.
    freeEntry(displaced);
    AMediaCodec* codec = nullptr;
    ANativeWindow* window = nullptr;
    std::string codecName;
    const int64_t buildNs = bootNs();
    if (!buildEncoder(spec, &codec, &window, &codecName)) return false;
    Entry fresh;
    fresh.codec = codec;
    fresh.window = window;
    fresh.codecName = codecName;
    fresh.spec = spec;
    fresh.createdAtNs = bootNs();
    const int64_t readyNs = fresh.createdAtNs;
    Entry discarded;
    bool installed = false;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        // A recording may have started while the codec was building: then the pool stays
        // empty, because a second session would compete with the running encoder.
        if (recordingActive_ || entry_.codec != nullptr) {
            discarded = std::move(fresh);
        } else {
            entry_ = std::move(fresh);
            installed = true;
        }
    }
    freeEntry(discarded);
    if (installed) {
        __android_log_print(ANDROID_LOG_INFO, kTag, "VIDEO_ENCODER_PREWARM ready buildMs=%.3f codec=%s spec=%s",
                            msBetween(buildNs, readyNs), codecName.c_str(), hevcSpecText(spec).c_str());
    }
    return installed;
}

AMediaCodec* VideoEncoderPrewarm::take(const HevcVideoEncoderSpec& spec, ANativeWindow** window,
                                      std::string* codecName) noexcept {
    if (!enabled()) {
        release();
        return nullptr;
    }
    Entry taken;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        const PreparedEncoder prepared = held();
        const PrewarmTake decision = prewarmTake(spec, prepared);
        if (decision != PrewarmTake::Reused) {
            if (entry_.codec) {
                __android_log_print(ANDROID_LOG_INFO, kTag,
                                    "VIDEO_ENCODER_PREWARM discard reason=%s codec=%s pooled=%s",
                                    prewarmTakeText(decision), entry_.codecName.c_str(),
                                    hevcSpecText(entry_.spec).c_str());
                freeEntry(entry_);
            }
            __android_log_print(ANDROID_LOG_INFO, kTag, "VIDEO_ENCODER_PREWARM miss reason=%s spec=%s",
                                prewarmTakeText(decision), hevcSpecText(spec).c_str());
            return nullptr;
        }
        taken = std::move(entry_);
        entry_ = Entry{};
        recordingActive_ = true;
    }
    const int64_t startNs = bootNs();
    const media_status_t started = AMediaCodec_start(taken.codec);
    if (started != AMEDIA_OK) {
        __android_log_print(ANDROID_LOG_WARN, kTag, "VIDEO_ENCODER_PREWARM failed reason=start status=%d codec=%s",
                            int(started), taken.codecName.c_str());
        freeEntry(taken);
        setRecordingActive(false);
        return nullptr;
    }
    // Ownership of both the codec and its Surface window moves to the recorder.
    AMediaCodec* handed = taken.codec;
    *window = taken.window;
    *codecName = taken.codecName;
    __android_log_print(ANDROID_LOG_INFO, kTag,
                        "VIDEO_ENCODER_PREWARM reused ageMs=%.3f startMs=%.3f codec=%s spec=%s",
                        msBetween(taken.createdAtNs, startNs), msBetween(startNs, bootNs()),
                        taken.codecName.c_str(), hevcSpecText(taken.spec).c_str());
    return handed;
}

void VideoEncoderPrewarm::release() noexcept {
    Entry released;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        released = std::move(entry_);
        entry_ = Entry{};
    }
    if (released.codec) {
        __android_log_print(ANDROID_LOG_INFO, kTag, "VIDEO_ENCODER_PREWARM release ageMs=%.3f codec=%s",
                            msBetween(released.createdAtNs, bootNs()), released.codecName.c_str());
    }
    freeEntry(released);
}

void VideoEncoderPrewarm::setRecordingActive(bool active) noexcept {
    std::lock_guard<std::mutex> lock(mutex_);
    recordingActive_ = active;
}

std::string VideoEncoderPrewarm::statsJson() const {
    std::lock_guard<std::mutex> lock(mutex_);
    char buffer[256];
    const PreparedEncoder prepared = held();
    snprintf(buffer, sizeof(buffer), "{\"enabled\":%s,\"state\":\"%s\",\"ageMs\":%.1f,\"spec\":\"%s\"}",
             enabled() ? "true" : "false", prepared.present ? "ready" : "empty",
             prepared.present ? static_cast<double>(prepared.ageNs) / 1'000'000.0 : 0.0,
             prepared.present ? hevcSpecText(prepared.spec).c_str() : "");
    return buffer;
}

}  // namespace rawrcam::video
