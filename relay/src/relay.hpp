/* Copyright (C) 2026 FixCraft Inc. AGPL-3.0-or-later. */

// The relay's event loop. One thread owns every stream, the room table and
// the timers, so room state is serialized without locks. Each accepted
// connection is one stream with its own buffers, deadlines and descriptor.

#pragma once

#include <sys/types.h>

#include <array>
#include <cstddef>
#include <cstdint>
#include <memory>
#include <string>

#include "limits.hpp"
#include "room_table.hpp"
#include "wire.hpp"

namespace ito::relay {

struct RelayOptions final {
    // The exact service name yumed writes in each module header.
    std::string service;
    // The only user, besides root, whose connections carry a trusted header.
    uid_t daemon_uid{0};
    Limits limits{};
};

// Why a stream or room ended, for aggregate counts only.
enum class EndReason : std::size_t {
    PeerClosed,
    Error,
    Stalled,
    ByteLimit,
    LifetimeExpired,
    ServiceFailure,
};
inline constexpr std::size_t kEndReasons = 6U;

// Aggregate counts. They carry no principal, token, address or byte content.
struct RelayCounts final {
    std::size_t streams{0U};
    std::size_t principals{0U};
    std::size_t rooms{0U};
    std::size_t paired_rooms{0U};
    std::size_t queued_bytes{0U};
    std::size_t peak_queued_bytes{0U};
    std::uint64_t accepted{0U};
    std::uint64_t untrusted_peers{0U};
    std::uint64_t over_capacity{0U};
    std::uint64_t rejected_headers{0U};
    std::uint64_t reservations{0U};
    std::uint64_t pairings{0U};
    std::uint64_t closed_rooms{0U};
    // Idle principals kept beyond their window after an allocation failure.
    std::uint64_t unqueued_retentions{0U};
    std::array<std::uint64_t, kRefusalReasons> refusals{};
    std::array<std::uint64_t, kEndReasons> paired_ends{};
};

class Relay final {
public:
    // Takes ownership of listener, a listening UNIX stream socket the caller
    // has checked, and of signals, a signalfd delivering SIGTERM, SIGINT and
    // SIGUSR1, or -1. Null when the limits are inconsistent or the event loop
    // cannot be created.
    [[nodiscard]] static std::unique_ptr<Relay> create(int listener,
                                                       int signals,
                                                       RelayOptions options,
                                                       TokenSource& tokens);
    ~Relay();

    Relay(const Relay&) = delete;
    Relay& operator=(const Relay&) = delete;

    // Serves until SIGTERM, SIGINT or stop(). Every stream is closed on
    // return. 0 after a requested stop, 1 when the event loop failed.
    [[nodiscard]] int run() noexcept;
    // Asks a running loop to stop. Safe from any thread.
    void stop() noexcept;
    // Counts as of the last loop iteration. Call from the loop's thread or
    // after run() returned.
    [[nodiscard]] RelayCounts counts() const noexcept;

    struct State;

private:
    explicit Relay(std::unique_ptr<State> state) noexcept;
    std::unique_ptr<State> state_;
};

// Whether the limits keep the service's total queued bytes within
// max_total_queued_bytes by construction: each stream holds either its
// control input or one direction's queue, never both.
[[nodiscard]] bool consistent_limits(const Limits& limits) noexcept;

// One line of JSON with the counts, newline included.
[[nodiscard]] std::string format_counts(const RelayCounts& counts);

}  // namespace ito::relay
