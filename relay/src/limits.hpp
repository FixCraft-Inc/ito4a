/* Copyright (C) 2026 FixCraft Inc. AGPL-3.0-or-later. */

#pragma once

#include <chrono>
#include <cstddef>
#include <cstdint>

namespace ito::relay {

// The version-1 limits of docs/CALL_RELAY.md, "Resource and teardown
// boundaries". The service always runs with these defaults. Tests build
// smaller values to reach time and byte bounds without waiting for them.
struct Limits final {
    std::size_t max_rooms{512U};
    std::size_t max_streams{1024U};
    std::size_t max_rooms_per_principal{2U};
    std::size_t max_streams_per_principal{4U};
    std::size_t max_principals{4096U};
    // Bytes the service holds for one direction of a paired room.
    std::size_t queue_bytes_per_direction{64U * 1024U};
    std::size_t max_total_queued_bytes{64U * 1024U * 1024U};
    // Opaque bytes one direction may carry before its room expires.
    std::uint64_t max_forwarded_bytes_per_direction{65ULL * 1024U * 1024U};
    std::chrono::milliseconds rate_window{60'000};
    // Bounds the module header and each expected control request.
    std::chrono::milliseconds parse_budget{5'000};
    // Applies only while output is queued and cannot be written.
    std::chrono::milliseconds stall_timeout{5'000};
    std::chrono::milliseconds paired_lifetime{30 * 60'000};
    // Time an orderly close may spend flushing accepted bytes and waiting
    // for the peer's end of stream before the descriptor closes.
    std::chrono::milliseconds drain_budget{1'000};
};

// The rolling-window quotas. They size the retained timestamp buckets, so
// they are constants: 10 + 20 = 30 timestamps per principal.
inline constexpr std::size_t kMaxReservesPerWindow = 10U;
inline constexpr std::size_t kMaxFailedJoinsPerWindow = 20U;

}  // namespace ito::relay
