/* Copyright (C) 2026 FixCraft Inc. AGPL-3.0-or-later. */

#include "room_table.hpp"

#include <sys/random.h>

#include <algorithm>
#include <cerrno>
#include <cstring>
#include <iterator>
#include <new>

namespace ito::relay {
namespace {

// Compared against a presented token when its room or role does not exist,
// so every refused JOIN does the same comparison work.
constexpr Token kAbsentToken{};

template <std::size_t N>
void wipe(std::array<std::uint8_t, N>& bytes) noexcept {
    ::explicit_bzero(bytes.data(), bytes.size());
}

}  // namespace

bool SystemTokenSource::fill(std::span<std::uint8_t> out) noexcept {
    std::size_t filled = 0U;
    while (filled < out.size()) {
        const ssize_t got =
            ::getrandom(out.data() + filled, out.size() - filled, 0);
        if (got < 0 && errno == EINTR) continue;
        if (got <= 0) return false;
        filled += static_cast<std::size_t>(got);
    }
    return true;
}

bool equal_secret(const Token& left, const Token& right) noexcept {
    // The volatile accumulator keeps the compiler from stopping at the
    // first differing byte.
    volatile std::uint8_t difference = 0U;
    for (std::size_t index = 0U; index < left.size(); ++index)
        difference = static_cast<std::uint8_t>(difference |
                                               (left[index] ^ right[index]));
    return difference == 0U;
}

template <std::size_t N>
void RoomTable::Window<N>::prune(TimePoint now,
                                 Clock::duration window) noexcept {
    std::size_t expired = 0U;
    while (expired < size && times[expired] + window <= now) ++expired;
    std::move(times.begin() + static_cast<std::ptrdiff_t>(expired),
              times.begin() + static_cast<std::ptrdiff_t>(size), times.begin());
    size -= expired;
}

template <std::size_t N>
void RoomTable::Window<N>::record(TimePoint now) noexcept {
    // Callers check the quota first, so a full window only drops a
    // timestamp that the quota no longer needs.
    if (size == N) {
        std::move(times.begin() + 1, times.end(), times.begin());
        --size;
    }
    times[size++] = now;
}

template <std::size_t N>
std::optional<TimePoint> RoomTable::Window<N>::newest() const noexcept {
    if (size == 0U) return std::nullopt;
    return times[size - 1U];
}

RoomTable::RoomTable(const Limits& limits, TokenSource& tokens)
    : limits_(limits), tokens_(tokens) {}

bool RoomTable::admit_stream(const Principal& principal, TimePoint now) {
    auto found = principals_.find(principal);
    if (found == principals_.end()) {
        if (principals_.size() >= limits_.max_principals) {
            // Only principals whose retention ended make room. An unexpired
            // bucket is never evicted, or reconnecting would reset a quota.
            forget_expired_principals(now, true);
            if (principals_.size() >= limits_.max_principals) return false;
        }
        found = principals_.try_emplace(principal).first;
    } else if (found->second.streams >= limits_.max_streams_per_principal) {
        return false;
    }
    auto& state = found->second;
    if (state.retained_until) {
        idle_.erase({*state.retained_until, principal});
        state.retained_until.reset();
    }
    ++state.streams;
    return true;
}

void RoomTable::release_stream(const Principal& principal) noexcept {
    const auto found = principals_.find(principal);
    if (found == principals_.end() || found->second.streams == 0U) return;
    --found->second.streams;
    maybe_idle(found);
}

std::variant<Reservation, RefusalReason> RoomTable::reserve(
    const Principal& principal, StreamId stream,
    std::uint32_t lifetime_milliseconds, TimePoint now) {
    const auto found = principals_.find(principal);
    if (found == principals_.end()) return RefusalReason::Capacity;
    auto& state = found->second;
    state.reserves.prune(now, limits_.rate_window);
    if (state.reserves.size >= kMaxReservesPerWindow)
        return RefusalReason::Capacity;
    // Every RESERVE within the quota counts, including one that capacity
    // refuses next, so retrying cannot exceed ten in a window.
    state.reserves.record(now);
    if (rooms_.size() >= limits_.max_rooms ||
        state.rooms >= limits_.max_rooms_per_principal)
        return RefusalReason::Capacity;

    std::array<std::uint8_t, 3U * 32U> material{};
    struct Wipe final {
        std::array<std::uint8_t, 3U * 32U>& bytes;
        ~Wipe() { wipe(bytes); }
    } wipe_material{material};
    if (!tokens_.fill(material)) return RefusalReason::Capacity;

    Reservation reservation;
    std::copy_n(material.begin(), 32, reservation.room.begin());
    std::copy_n(material.begin() + 32, 32, reservation.initiator_token.begin());
    std::copy_n(material.begin() + 64, 32, reservation.responder_token.begin());
    reservation.lifetime_milliseconds = lifetime_milliseconds;
    const TimePoint deadline =
        now + std::chrono::milliseconds(lifetime_milliseconds);

    // Both insertions can allocate. The room is published only after both
    // succeed, and a 256-bit collision refuses rather than replacing a room.
    const auto [room, inserted] = rooms_.try_emplace(reservation.room);
    if (!inserted) return RefusalReason::Capacity;
    try {
        room_deadlines_.emplace(deadline, reservation.room);
    } catch (...) {
        rooms_.erase(room);
        throw;
    }
    auto& created = room->second;
    created.owner = principal;
    created.reserver = stream;
    created.initiator_token = reservation.initiator_token;
    created.responder_token = reservation.responder_token;
    created.deadline = deadline;
    ++state.rooms;
    return reservation;
}

std::variant<Admitted, RefusalReason> RoomTable::join(
    const Principal& principal, StreamId stream,
    const std::optional<RoomId>& reserved, const JoinRequest& request,
    TimePoint now) {
    const auto found_principal = principals_.find(principal);
    if (found_principal == principals_.end()) return RefusalReason::Capacity;
    auto& state = found_principal->second;
    state.failed_joins.prune(now, limits_.rate_window);
    if (state.failed_joins.size >= kMaxFailedJoinsPerWindow)
        return RefusalReason::Capacity;

    const auto found = rooms_.find(request.room);
    const Token* expected = &kAbsentToken;
    bool eligible = false;
    if (found != rooms_.end()) {
        const Room& room = found->second;
        const bool live = now < room.deadline && !room.paired;
        if (request.role == Role::Initiator) {
            expected = &room.initiator_token;
            eligible = live && reserved == request.room &&
                       stream == room.reserver && !room.initiator;
        } else {
            expected = &room.responder_token;
            eligible =
                live && !reserved && stream != room.reserver && !room.responder;
        }
    }
    // Always compared, so a wrong token, an occupied role and an unknown
    // room take the same path to the same refusal.
    const bool matches = equal_secret(*expected, request.token);
    if (!eligible || !matches) {
        state.failed_joins.record(now);
        return RefusalReason::InvalidRoom;
    }

    Room& room = found->second;
    const bool completes = request.role == Role::Initiator
                               ? room.responder.has_value()
                               : room.initiator.has_value();
    const TimePoint paired_deadline = now + limits_.paired_lifetime;
    const bool moves = completes && paired_deadline != room.deadline;
    if (moves) {
        // The paired lifetime replaces the reservation deadline. Insert it
        // first: a failed allocation leaves the room exactly as it was.
        room_deadlines_.emplace(paired_deadline, request.room);
    }
    if (request.role == Role::Initiator) {
        room.initiator = stream;
        wipe(room.initiator_token);
    } else {
        room.responder = stream;
        wipe(room.responder_token);
    }
    Admitted admitted{request.room, false, 0U, 0U};
    if (completes) {
        if (moves) room_deadlines_.erase({room.deadline, request.room});
        room.deadline = paired_deadline;
        room.paired = true;
        ++paired_;
        admitted.paired = true;
        admitted.initiator = *room.initiator;
        admitted.responder = *room.responder;
    }
    return admitted;
}

std::optional<RoomStreams> RoomTable::close_room(const RoomId& room) noexcept {
    const auto found = rooms_.find(room);
    if (found == rooms_.end()) return std::nullopt;
    return remove(found);
}

RoomStreams RoomTable::remove(std::map<RoomId, Room>::iterator room) noexcept {
    Room& closing = room->second;
    RoomStreams streams{closing.reserver, closing.responder, closing.paired};
    room_deadlines_.erase({closing.deadline, room->first});
    if (closing.paired) --paired_;
    wipe(closing.initiator_token);
    wipe(closing.responder_token);
    const auto owner = principals_.find(closing.owner);
    rooms_.erase(room);
    if (owner != principals_.end() && owner->second.rooms > 0U) {
        --owner->second.rooms;
        maybe_idle(owner);
    }
    return streams;
}

std::vector<ExpiredRoom> RoomTable::expire(TimePoint now) {
    // Size the result before removing anything, so an allocation failure
    // leaves every room in place.
    std::size_t due = 0U;
    for (auto entry = room_deadlines_.begin();
         entry != room_deadlines_.end() && entry->first <= now; ++entry)
        ++due;
    std::vector<ExpiredRoom> expired;
    expired.reserve(due);
    while (!room_deadlines_.empty() && room_deadlines_.begin()->first <= now) {
        const RoomId id = room_deadlines_.begin()->second;
        const auto found = rooms_.find(id);
        if (found == rooms_.end()) {
            room_deadlines_.erase(room_deadlines_.begin());
            continue;
        }
        expired.emplace_back();
        expired.back().room = id;
        expired.back().end = found->second.paired ? RoomEnd::LifetimeExpired
                                                  : RoomEnd::ReservationExpired;
        expired.back().streams = remove(found);
    }
    forget_expired_principals(now, false);
    return expired;
}

std::optional<TimePoint> RoomTable::next_deadline() const noexcept {
    std::optional<TimePoint> next;
    if (!room_deadlines_.empty()) next = room_deadlines_.begin()->first;
    if (!idle_.empty() && (!next || idle_.begin()->first < *next))
        next = idle_.begin()->first;
    return next;
}

// A principal with no stream and no room keeps its buckets until its newest
// counted request leaves the rate window. One that never counted a request
// has nothing to retain.
void RoomTable::maybe_idle(Principals::iterator principal) noexcept {
    auto& state = principal->second;
    if (state.streams != 0U || state.rooms != 0U || state.retained_until)
        return;
    auto newest = state.reserves.newest();
    const auto failed = state.failed_joins.newest();
    if (failed && (!newest || *failed > *newest)) newest = failed;
    if (!newest) {
        principals_.erase(principal);
        return;
    }
    state.retained_until = *newest + limits_.rate_window;
    try {
        idle_.emplace(*state.retained_until, principal->first);
    } catch (const std::bad_alloc&) {
        // Without its queue entry the principal stays until a full scan
        // under capacity pressure forgets it: retention can only lengthen.
        ++unqueued_retentions_;
    }
}

void RoomTable::forget_expired_principals(TimePoint now,
                                          bool full_scan) noexcept {
    while (!idle_.empty() && idle_.begin()->first <= now) {
        const auto [until, principal] = *idle_.begin();
        idle_.erase(idle_.begin());
        const auto found = principals_.find(principal);
        if (found != principals_.end() && found->second.retained_until == until)
            principals_.erase(found);
    }
    if (!full_scan) return;
    for (auto entry = principals_.begin(); entry != principals_.end();) {
        const auto& state = entry->second;
        if (state.retained_until && *state.retained_until <= now) {
            idle_.erase({*state.retained_until, entry->first});
            entry = principals_.erase(entry);
        } else {
            ++entry;
        }
    }
}

}  // namespace ito::relay
