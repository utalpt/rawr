#pragma once

#include <algorithm>
#include <cstdint>
#include <string_view>

namespace rawrcam::video {

// The short acquire wait is an experimental diagnostic option for Xiaomi 15
// (ro.product.device=dada). Other devices keep the original non-blocking path.
[[nodiscard]] inline std::uint32_t videoAcquireWaitMs(std::string_view productDevice,
                                                       std::string_view propertyValue) noexcept {
    if (productDevice != "dada" || propertyValue.empty()) return 0;
    std::uint32_t value = 0;
    for (const char ch : propertyValue) {
        if (ch < '0' || ch > '9') return 0;
        const auto digit = static_cast<std::uint32_t>(ch - '0');
        // Saturate without returning early: every trailing character must be valid.
        value = std::min(5u, value * 10u + digit);
    }
    return value;
}

// Keep the existing four-buffer policy unless explicitly testing on dada.
[[nodiscard]] inline std::uint32_t videoReadyBufferCount(std::string_view device,
                                                        std::string_view setting) noexcept {
    return device == "dada" && setting == "6" ? 6u : 4u;
}

}  // namespace rawrcam::video
