#include "imaging/LoficGrab.h"

#include <condition_variable>
#include <cstring>
#include <deque>
#include <map>
#include <mutex>

namespace rawrcam::imaging {
namespace {

struct Frame {
    int64_t timestampNs = 0;
    std::vector<uint8_t> bytes;
    uint32_t width = 0, rows = 0;
};
struct Result {
    int64_t timestampNs = 0;
    int32_t iso = 0;
    int64_t exposureNs = 0;
    std::array<float, 3> neutral{0, 0, 0};
};

std::mutex mutex;
std::condition_variable changed;
bool wanting = false;
bool sessionActive = false;
uint32_t viewfinderRows = 0;
std::deque<Frame> longs, shorts;  // a few recent frames of each while a grab is wanted
std::deque<Result> results;       // recent results, always (they arrive about a frame after the RAWs)

constexpr size_t kFrames = 3;
constexpr size_t kResults = 16;

void keep(std::deque<Frame>& list, int64_t timestampNs, const uint8_t* data, size_t bytes, uint32_t width,
          uint32_t rows) {
    Frame f;
    f.timestampNs = timestampNs;
    f.width = width;
    f.rows = rows;
    f.bytes.assign(data, data + bytes);
    list.push_back(std::move(f));
    while (list.size() > kFrames) list.pop_front();
}

}  // namespace

LoficGrab& LoficGrab::instance() {
    static LoficGrab grab;
    return grab;
}

void LoficGrab::request() {
    std::lock_guard<std::mutex> lock(mutex);
    longs.clear();
    shorts.clear();
    wanting = true;
}

void LoficGrab::setActive(bool active) {
    std::lock_guard<std::mutex> lock(mutex);
    sessionActive = active;
    viewfinderRows = 0;
    if (!active) wanting = false;
}

void LoficGrab::noteRows(uint32_t rows) {
    std::lock_guard<std::mutex> lock(mutex);
    if (sessionActive) viewfinderRows = rows;
}

uint32_t LoficGrab::lastRows() const {
    std::lock_guard<std::mutex> lock(mutex);
    return viewfinderRows;
}

bool LoficGrab::active() const {
    std::lock_guard<std::mutex> lock(mutex);
    return sessionActive;
}

bool LoficGrab::wanted() const {
    std::lock_guard<std::mutex> lock(mutex);
    return wanting;
}

void LoficGrab::offerLong(int64_t timestampNs, const uint8_t* data, uint32_t width, uint32_t rows) {
    std::lock_guard<std::mutex> lock(mutex);
    if (!wanting || !data) return;
    keep(longs, timestampNs, data, size_t(rows) * width / 4u * 7u, width, rows);
    changed.notify_all();
}

void LoficGrab::offerShort(int64_t timestampNs, const uint8_t* data, uint32_t width, uint32_t rows) {
    std::lock_guard<std::mutex> lock(mutex);
    if (!wanting || !data) return;
    keep(shorts, timestampNs, data, size_t(rows) * width / 4u * 5u, width, rows);
    changed.notify_all();
}

void LoficGrab::offerResult(int64_t timestampNs, int32_t iso, int64_t exposureNs, const std::array<float, 3>& neutral) {
    std::lock_guard<std::mutex> lock(mutex);
    results.push_back({timestampNs, iso, exposureNs, neutral});
    while (results.size() > kResults) results.pop_front();
    changed.notify_all();
}

std::optional<LoficPair> LoficGrab::wait(std::chrono::milliseconds timeout) {
    const auto deadline = std::chrono::steady_clock::now() + timeout;
    std::unique_lock<std::mutex> lock(mutex);
    const auto findPair = [&]() -> std::optional<std::pair<size_t, size_t>> {
        for (size_t l = longs.size(); l-- > 0;)
            for (size_t s = 0; s < shorts.size(); ++s)
                if (shorts[s].timestampNs == longs[l].timestampNs) return std::make_pair(l, s);
        return std::nullopt;
    };
    std::optional<std::pair<size_t, size_t>> found;
    changed.wait_until(lock, deadline, [&] { return (found = findPair()).has_value(); });
    if (!found) {
        wanting = false;
        longs.clear();
        shorts.clear();
        return std::nullopt;
    }
    LoficPair pair;
    Frame& l = longs[found->first];
    Frame& s = shorts[found->second];
    pair.timestampNs = l.timestampNs;
    pair.width = l.width;
    pair.rows = std::min(l.rows, s.rows);
    pair.longBytes = std::move(l.bytes);
    pair.shortBytes = std::move(s.bytes);
    wanting = false;
    longs.clear();
    shorts.clear();
    // The frame's result follows its RAWs by about a frame: wait for it, up to 500 ms more.
    const auto resultDeadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(500);
    const auto findResult = [&]() -> const Result* {
        for (const auto& r : results)
            if (r.timestampNs == pair.timestampNs) return &r;
        return nullptr;
    };
    const Result* result = nullptr;
    changed.wait_until(lock, resultDeadline, [&] { return (result = findResult()) != nullptr; });
    if (!result && !results.empty()) result = &results.back();  // nearest in time: the latest
    if (result) {
        pair.iso = result->iso;
        pair.exposureNs = result->exposureNs;
        pair.neutral = result->neutral;
        pair.resultMatched = result->timestampNs == pair.timestampNs;
    }
    return pair;
}

}  // namespace rawrcam::imaging
