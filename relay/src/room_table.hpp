/* Copyright (C) 2026 FixCraft Inc. AGPL-3.0-or-later. */

// Admission policy and room state: principals with their stream counts and
// retained rate buckets, reservations, one-use role tokens and pairing. The
// table performs no I/O and reads no clock. Every call receives the current
// time, so its deadlines and rolling windows are deterministic under test.
// The relay's single event loop is its only caller.

#pragma once

#include <array>
#include <chrono>
#include <cstddef>
#include <cstdint>
#include <map>
#include <optional>
#include <set>
#include <span>
#include <utility>
#include <variant>
#include <vector>

#include "limits.hpp"
#include "wire.hpp"

namespace ito::relay {

using Clock = std::chrono::steady_clock;
using TimePoint = Clock::time_point;
using StreamId = std::uint64_t;

// The cryptographic source of room IDs and role tokens.
class TokenSource {
public:
    virtual ~TokenSource() = default;
    // Fills every byte of out, or returns false. A false return leaves the
    // contents unspecified, and the caller publishes nothing from them.
    [[nodiscard]] virtual bool fill(std::span<std::uint8_t> out) noexcept = 0;
};

// The kernel's random source through getrandom(2), which blocks only until
// the pool is first initialized.
class SystemTokenSource final : public TokenSource {
public:
    [[nodiscard]] bool fill(std::span<std::uint8_t> out) noexcept override;
};

// Compares two secrets in time independent of where they differ.
[[nodiscard]] bool equal_secret(const Token& left, const Token& right) noexcept;

struct Reservation final {
    RoomId room{};
    Token initiator_token{};
    Token responder_token{};
    std::uint32_t lifetime_milliseconds{0U};
};

struct Admitted final {
    RoomId room{};
    // Set once both roles are admitted. Both streams then receive PAIRED,
    // and initiator and responder name them.
    bool paired{false};
    StreamId initiator{0U};
    StreamId responder{0U};
};

// The streams a closed room held. A reserving stream holds its room from
// RESERVE, before it is admitted as the initiator.
struct RoomStreams final {
    StreamId reserver{0U};
    std::optional<StreamId> responder;
    bool paired{false};
};

enum class RoomEnd {
    // The reservation's lifetime passed before both roles joined.
    ReservationExpired,
    // A paired room reached its 30-minute lifetime.
    LifetimeExpired,
};

struct ExpiredRoom final {
    RoomId room{};
    RoomStreams streams;
    RoomEnd end{RoomEnd::ReservationExpired};
};

class RoomTable final {
public:
    RoomTable(const Limits& limits, TokenSource& tokens);

    RoomTable(const RoomTable&) = delete;
    RoomTable& operator=(const RoomTable&) = delete;

    // Counts one more stream for principal, creating its entry when absent.
    // False refuses it: the principal holds its stream quota, or the table is
    // full of principals whose retention has not ended.
    [[nodiscard]] bool admit_stream(const Principal& principal, TimePoint now);
    // Releases a stream that admit_stream counted.
    void release_stream(const Principal& principal) noexcept;

    // Reserves a room for stream, which becomes its only possible initiator.
    // Counts the request against the principal's RESERVE window first.
    [[nodiscard]] std::variant<Reservation, RefusalReason> reserve(
        const Principal& principal, StreamId stream,
        std::uint32_t lifetime_milliseconds, TimePoint now);

    // Admits stream to one role and consumes that role's token. reserved is
    // the room this stream reserved, if any: only it may join, and only as
    // the initiator of that room. Wrong tokens, occupied roles and unknown
    // rooms all return InvalidRoom after the same work, and each counts as
    // a failed JOIN. A principal at its failed-JOIN quota gets Capacity
    // before its token is examined.
    [[nodiscard]] std::variant<Admitted, RefusalReason> join(
        const Principal& principal, StreamId stream,
        const std::optional<RoomId>& reserved, const JoinRequest& request,
        TimePoint now);

    // Removes a room and releases its reservation. Its tokens can never be
    // presented again. Empty when the room no longer exists.
    std::optional<RoomStreams> close_room(const RoomId& room) noexcept;

    // Removes and returns rooms whose deadline is at or before now, and
    // forgets principals whose retention ended.
    [[nodiscard]] std::vector<ExpiredRoom> expire(TimePoint now);

    // The earliest room deadline or principal retention end.
    [[nodiscard]] std::optional<TimePoint> next_deadline() const noexcept;

    [[nodiscard]] std::size_t rooms() const noexcept { return rooms_.size(); }
    [[nodiscard]] std::size_t paired_rooms() const noexcept { return paired_; }
    [[nodiscard]] std::size_t principals() const noexcept {
        return principals_.size();
    }
    // Idle principals whose expiry entry could not be allocated. A full scan
    // still forgets them, so retention only lengthens.
    [[nodiscard]] std::uint64_t unqueued_retentions() const noexcept {
        return unqueued_retentions_;
    }

private:
    // The newest-last timestamps of one rolling window, at most N of them.
    template <std::size_t N>
    struct Window final {
        std::array<TimePoint, N> times{};
        std::size_t size{0U};

        void prune(TimePoint now, Clock::duration window) noexcept;
        void record(TimePoint now) noexcept;
        [[nodiscard]] std::optional<TimePoint> newest() const noexcept;
    };

    struct PrincipalState final {
        std::size_t streams{0U};
        std::size_t rooms{0U};
        Window<kMaxReservesPerWindow> reserves;
        Window<kMaxFailedJoinsPerWindow> failed_joins;
        // When this idle principal's retention ends, while it is queued in
        // idle_.
        std::optional<TimePoint> retained_until;
    };

    struct Room final {
        Principal owner{};
        StreamId reserver{0U};
        Token initiator_token{};
        Token responder_token{};
        std::optional<StreamId> initiator;
        std::optional<StreamId> responder;
        TimePoint deadline{};
        bool paired{false};
    };
    // docs/CALL_RELAY.md bounds room metadata independently of its queues.
    static_assert(sizeof(Room) <= 2048U);

    using Principals = std::map<Principal, PrincipalState>;

    void maybe_idle(Principals::iterator principal) noexcept;
    // Forgets idle principals whose retention ended. A full scan also finds
    // those whose queue entry could not be allocated.
    void forget_expired_principals(TimePoint now, bool full_scan) noexcept;
    RoomStreams remove(std::map<RoomId, Room>::iterator room) noexcept;

    const Limits limits_;
    TokenSource& tokens_;
    Principals principals_;
    std::map<RoomId, Room> rooms_;
    std::set<std::pair<TimePoint, RoomId>> room_deadlines_;
    std::set<std::pair<TimePoint, Principal>> idle_;
    std::size_t paired_{0U};
    std::uint64_t unqueued_retentions_{0U};
};

}  // namespace ito::relay
