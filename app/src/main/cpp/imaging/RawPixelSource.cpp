#include "imaging/RawPixelSource.h"

#include <algorithm>
#include <array>
#include <cstring>
#include <iomanip>
#include <sstream>
#include <thread>
#include <utility>
#include <vector>

namespace rawrcam::imaging {

size_t minimumRowBytes(RawPixelFormat format, uint32_t width) noexcept {
    if (format == RawPixelFormat::Raw10) return static_cast<size_t>(width) / 4u * 5u;
    if (format == RawPixelFormat::Raw14Packed) return static_cast<size_t>(width) / 4u * 7u;
    return static_cast<size_t>(width) * sizeof(uint16_t);
}

uint16_t readRawPixel(const RawPixelSource& source, uint32_t x, uint32_t y) noexcept {
    const uint8_t* row = source.base + static_cast<size_t>(y) * source.rowStrideBytes;
    if (source.format == RawPixelFormat::Raw10) {
        const uint8_t* group = row + static_cast<size_t>(x / 4u) * 5u;
        const uint32_t lane = x % 4u;
        return static_cast<uint16_t>((static_cast<uint16_t>(group[lane]) << 2) | ((group[4] >> (lane * 2u)) & 0x3u));
    }
    if (source.format == RawPixelFormat::Raw14Packed) {
        const uint8_t* group = row + static_cast<size_t>(x / 4u) * 7u;
        const uint32_t lsb = group[4] | (uint32_t(group[5]) << 8) | (uint32_t(group[6]) << 16);
        const uint32_t lane = x % 4u;
        return static_cast<uint16_t>((uint32_t(group[lane]) << 6) | ((lsb >> (6u * lane)) & 0x3fu));
    }
    uint16_t value = 0;
    std::memcpy(&value, row + static_cast<size_t>(x) * sizeof(uint16_t), sizeof(value));
    return value;
}

void unpackRaw10Row(const uint8_t* packed, uint32_t width, uint16_t* destination) noexcept {
    for (uint32_t x = 0; x + 3u < width; x += 4u, packed += 5) {
        const uint8_t lsb = packed[4];
        destination[x + 0] = static_cast<uint16_t>((packed[0] << 2) | (lsb & 0x3u));
        destination[x + 1] = static_cast<uint16_t>((packed[1] << 2) | ((lsb >> 2) & 0x3u));
        destination[x + 2] = static_cast<uint16_t>((packed[2] << 2) | ((lsb >> 4) & 0x3u));
        destination[x + 3] = static_cast<uint16_t>((packed[3] << 2) | ((lsb >> 6) & 0x3u));
    }
}

uint32_t filledRows(const uint8_t* base, size_t rowBytes, uint32_t maxRows) noexcept {
    if (!base || !rowBytes) return 0;
    for (uint32_t row = maxRows; row > 0; --row) {
        const uint8_t* p = base + static_cast<size_t>(row - 1u) * rowBytes;
        for (size_t i = 0; i < rowBytes; i += 16)
            if (p[i]) return row;
    }
    return 0;
}

// Xiaomi 17 Ultra LOFIC Long frame (layout from Anton Tretiakov's X17U LOFIC RAW v1.0 spec, measured on the 17U):
// 4 MSB bytes then 3 bytes holding four 6-bit LSBs, little-endian; value = msb << 6 | lsb.
void unpackRaw14Row(const uint8_t* packed, uint32_t width, uint16_t* destination) noexcept {
    for (uint32_t x = 0; x + 3u < width; x += 4u, packed += 7) {
        const uint32_t lsb = packed[4] | (uint32_t(packed[5]) << 8) | (uint32_t(packed[6]) << 16);
        destination[x + 0] = static_cast<uint16_t>((uint32_t(packed[0]) << 6) | (lsb & 0x3fu));
        destination[x + 1] = static_cast<uint16_t>((uint32_t(packed[1]) << 6) | ((lsb >> 6) & 0x3fu));
        destination[x + 2] = static_cast<uint16_t>((uint32_t(packed[2]) << 6) | ((lsb >> 12) & 0x3fu));
        destination[x + 3] = static_cast<uint16_t>((uint32_t(packed[3]) << 6) | ((lsb >> 18) & 0x3fu));
    }
}

bool copyRawToPackedRaw16(const RawPixelSource& source, uint16_t* destination) noexcept {
    if (!source.base || !destination || source.width == 0 || source.height == 0) return false;
    if (source.rowStrideBytes < minimumRowBytes(source.format, source.width)) return false;
    if (source.format != RawPixelFormat::Raw16 && source.width % 4u != 0) return false;
    const auto rows = [&](uint32_t begin, uint32_t end) noexcept {
        for (uint32_t y = begin; y < end; ++y) {
            const uint8_t* row = source.base + static_cast<size_t>(y) * source.rowStrideBytes;
            uint16_t* out = destination + static_cast<size_t>(y) * source.width;
            if (source.format == RawPixelFormat::Raw10)
                unpackRaw10Row(row, source.width, out);
            else if (source.format == RawPixelFormat::Raw14Packed)
                unpackRaw14Row(row, source.width, out);
            else
                std::memcpy(out, row, static_cast<size_t>(source.width) * sizeof(uint16_t));
        }
    };
    // Full-size stills (50 / 200 MP) split the rows across cores: one thread took ~180 ms per 200 MP RAW10 frame.
    // Smaller frames stay on the calling thread. A thread that cannot be started leaves its rows to this one.
    const uint64_t pixels = static_cast<uint64_t>(source.width) * source.height;
    const uint32_t threads =
        pixels >= 24'000'000ull || source.format == RawPixelFormat::Raw14Packed ? std::clamp(std::thread::hardware_concurrency(), 1u, 8u) : 1u;
    if (threads <= 1u || source.height < threads) {
        rows(0, source.height);
        return true;
    }
    const uint32_t chunk = (source.height + threads - 1u) / threads;
    const auto range = [&](uint32_t i) {
        const uint32_t begin = std::min(source.height, i * chunk);
        return std::pair<uint32_t, uint32_t>{begin, std::min(source.height, begin + chunk)};
    };
    std::vector<std::thread> pool;
    uint32_t started = 0;
    try {
        pool.reserve(threads - 1u);
        for (uint32_t i = 1; i < threads; ++i) {
            const auto [begin, end] = range(i);
            pool.emplace_back(rows, begin, end);
            ++started;
        }
    } catch (...) {
    }
    for (uint32_t i = started + 1u; i < threads; ++i) {
        const auto [begin, end] = range(i);
        rows(begin, end);
    }
    const auto [begin, end] = range(0);
    rows(begin, end);
    for (auto& thread : pool) thread.join();
    return true;
}

std::optional<std::array<float, 4>> rawBlackFloor(const RawPixelSource& source, int cfa) {
    if (!source.base || source.width < 16 || source.height < 16 || cfa < 0 || cfa > 3 ||
        source.rowStrideBytes < minimumRowBytes(source.format, source.width)) return std::nullopt;
    static constexpr int channels[4][4] = {{0, 1, 2, 3}, {1, 0, 3, 2}, {2, 3, 0, 1}, {3, 2, 1, 0}};
    std::array<std::vector<uint16_t>, 4> values;
    const uint32_t dx = std::max(2u, source.width / 32 / 2 * 2);
    const uint32_t dy = std::max(2u, source.height / 32 / 2 * 2);
    for (uint32_t y = 0; y + 1 < source.height; y += dy)
        for (uint32_t x = 0; x + 1 < source.width; x += dx)
            for (uint32_t c = 0; c < 4; ++c)
                values[channels[cfa][c]].push_back(readRawPixel(source, x + (c & 1), y + (c >> 1)));
    std::array<float, 4> floor{};
    for (size_t c = 0; c < 4; ++c) {
        auto& v = values[c];
        if (v.size() < 16) return std::nullopt;
        const auto lower = v.begin() + v.size() / 100;  // Ignore isolated dead pixels.
        std::nth_element(v.begin(), lower, v.end());
        floor[c] = *lower;
    }
    return floor;
}

RawBlockUniformity rawBlockUniformity(const RawPixelSource& source, const RawContentStatsInput& input) {
    RawBlockUniformity out;
    if (!source.base || source.rowStrideBytes < minimumRowBytes(source.format, source.width)) return out;
    // A centred window: real sensor noise makes equal blocks rare, while a binned
    // frame upscaled by pixel repetition makes every aligned block equal. Blocks
    // at black or white are flat either way and are skipped.
    const auto uniform = [&](uint32_t block, uint64_t* sampled) {
        const uint32_t span = std::min<uint32_t>(1024u, std::min(source.width, source.height)) / 4u * 4u;
        const uint32_t x0 = (source.width - span) / 2u / 4u * 4u, y0 = (source.height - span) / 2u / 4u * 4u;
        uint64_t count = 0, equal = 0;
        for (uint32_t y = y0; y + block <= y0 + span; y += block) {
            for (uint32_t x = x0; x + block <= x0 + span; x += block) {
                const uint16_t first = readRawPixel(source, x, y);
                if (float(first) <= input.blackLevel || float(first) >= input.whiteLevel) continue;
                bool same = true;
                for (uint32_t dy = 0; dy < block && same; ++dy)
                    for (uint32_t dx = 0; dx < block && same; ++dx) same = readRawPixel(source, x + dx, y + dy) == first;
                ++count;
                if (same) ++equal;
            }
        }
        if (sampled) *sampled = count;
        return count ? double(equal) / double(count) : 0.0;
    };
    out.uniform2 = uniform(2u, &out.sampled);
    out.uniform4 = uniform(4u, nullptr);
    out.verdict = out.sampled < 64u              ? "flat"
                  : out.uniform4 > 0.9           ? "upscaled4x"
                  : out.uniform2 > 0.9           ? "upscaled2x"
                                                 : "real";
    return out;
}

std::string rawContentStatsLine(const RawPixelSource& source, const RawContentStatsInput& input) {
    std::ostringstream out;
    out << "RAW_CONTENT_STATS format=" << rawPixelFormatName(source.format) << " dims=" << source.width << 'x'
        << source.height << " rowStrideBytes=" << source.rowStrideBytes << " cfa=" << input.cfa;
    if (!source.base || source.width < 2 || source.height < 2 ||
        source.rowStrideBytes < minimumRowBytes(source.format, source.width)) {
        out << " error=invalid_source";
        return out.str();
    }

    // Channel slot per position inside the 2x2 quad, in R,Gr,Gb,B order.
    // Camera2 CFA names the top-left, top-right, bottom-left, bottom-right colors.
    static constexpr std::array<std::array<int, 4>, 4> kQuadChannel = {{
        {0, 1, 2, 3},  // RGGB
        {1, 0, 3, 2},  // GRBG
        {2, 3, 0, 1},  // GBRG
        {3, 2, 1, 0},  // BGGR
    }};
    static constexpr const char* kChannelName[4] = {"R", "Gr", "Gb", "B"};
    const auto& quad = kQuadChannel[static_cast<size_t>(std::clamp(input.cfa, 0, 3))];

    const uint32_t step = std::max(1u, input.quadStep);
    std::array<std::vector<uint16_t>, 4> samples;
    uint64_t total = 0;
    uint64_t atOrBelowBlack = 0;
    uint64_t atOrAboveWhite = 0;
    for (uint32_t qy = 0; qy + 1u < source.height; qy += 2u * step) {
        for (uint32_t qx = 0; qx + 1u < source.width; qx += 2u * step) {
            for (uint32_t i = 0; i < 4u; ++i) {
                const uint16_t v = readRawPixel(source, qx + (i & 1u), qy + (i >> 1u));
                samples[static_cast<size_t>(quad[i])].push_back(v);
                ++total;
                if (static_cast<float>(v) <= input.blackLevel) ++atOrBelowBlack;
                if (static_cast<float>(v) >= input.whiteLevel) ++atOrAboveWhite;
            }
        }
    }

    out << std::fixed << std::setprecision(1) << " black=" << input.blackLevel << " white=" << input.whiteLevel
        << " samples=" << total;
    for (size_t c = 0; c < samples.size(); ++c) {
        auto& values = samples[c];
        if (values.empty()) continue;
        uint64_t sum = 0;
        for (uint16_t v : values) sum += v;
        std::sort(values.begin(), values.end());
        const auto at = [&](double q) {
            return values[static_cast<size_t>(q * static_cast<double>(values.size() - 1))];
        };
        out << ' ' << kChannelName[c] << "=[min=" << values.front()
            << " mean=" << static_cast<double>(sum) / static_cast<double>(values.size()) << " p50=" << at(0.5)
            << " p99=" << at(0.99) << " max=" << values.back() << ']';
    }
    const double denom = total ? static_cast<double>(total) : 1.0;
    out << std::setprecision(4) << " fracAtOrBelowBlack=" << static_cast<double>(atOrBelowBlack) / denom
        << " fracAtOrAboveWhite=" << static_cast<double>(atOrAboveWhite) / denom;
    const auto blocks = rawBlockUniformity(source, input);
    out << " block2Uniform=" << blocks.uniform2 << " block4Uniform=" << blocks.uniform4
        << " blocksSampled=" << blocks.sampled << " detail=" << blocks.verdict;
    return out.str();
}

}  // namespace rawrcam::imaging
