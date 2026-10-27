/* Copyright (C) 2026 FixCraft Inc. AGPL-3.0-or-later. */

// A deterministic token source for tests: distinct splitmix64 output, never
// a cryptographic source, with an optional failure.

#pragma once

#include <cstdint>
#include <span>

#include "room_table.hpp"

namespace ito::relay::testing {

class SequenceTokens final : public TokenSource {
public:
    bool fill(std::span<std::uint8_t> out) noexcept override {
        if (fail) return false;
        for (std::size_t index = 0U; index < out.size(); index += 8U) {
            std::uint64_t value = (state_ += 0x9e3779b97f4a7c15ULL);
            value = (value ^ (value >> 30U)) * 0xbf58476d1ce4e5b9ULL;
            value = (value ^ (value >> 27U)) * 0x94d049bb133111ebULL;
            value ^= value >> 31U;
            for (std::size_t byte = 0U; byte < 8U && index + byte < out.size();
                 ++byte)
                out[index + byte] =
                    static_cast<std::uint8_t>(value >> (8U * byte));
        }
        return true;
    }

    bool fail{false};

private:
    std::uint64_t state_{0U};
};

}  // namespace ito::relay::testing
