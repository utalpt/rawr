#pragma once

#include <array>
#include <chrono>
#include <cstdint>
#include <optional>
#include <vector>

namespace rawrcam::imaging {

// A LOFIC photo from the "1x LOFIC" lens (Xiaomi 17 Ultra, Anton Tretiakov's X17U LOFIC RAW v1.0 spec): the
// Long (packed RAW14) and Short (RAW10) of one frame, with that frame's capture result. The shutter asks for one
// (request), the RAW ingress and the drained Short reader offer frames, the result processor offers results, and
// the caller waits for the pair.
struct LoficPair {
    std::vector<uint8_t> longBytes;   // rows * width*7/4
    std::vector<uint8_t> shortBytes;  // rows * width*5/4
    uint32_t width = 0;
    uint32_t rows = 0;
    int64_t timestampNs = 0;
    int32_t iso = 0;
    int64_t exposureNs = 0;
    std::array<float, 3> neutral{0, 0, 0};  // AsShotNeutral; zeros when the result had none
    bool resultMatched = false;
};

class LoficGrab {
   public:
    static LoficGrab& instance();
    // Starts a new grab; any frame offered after this can complete it.
    void request();
    // A LOFIC session is running (set by the camera session).
    void setActive(bool active);
    bool active() const;
    bool wanted() const;
    void offerLong(int64_t timestampNs, const uint8_t* data, uint32_t width, uint32_t rows);
    void offerShort(int64_t timestampNs, const uint8_t* data, uint32_t width, uint32_t rows);
    void offerResult(int64_t timestampNs, int32_t iso, int64_t exposureNs, const std::array<float, 3>& neutral);
    // The pair, once both RAWs of one frame are in; waits up to timeout for the frame's result too.
    std::optional<LoficPair> wait(std::chrono::milliseconds timeout);

   private:
    LoficGrab() = default;
};

}  // namespace rawrcam::imaging
