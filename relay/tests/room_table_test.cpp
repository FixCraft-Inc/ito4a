/* Copyright (C) 2026 FixCraft Inc. AGPL-3.0-or-later. */

// Room and admission policy under virtual time, with the version-1 limits.

#include "room_table.hpp"

#include <cstdio>
#include <variant>

#include "check.hpp"
#include "sequence_tokens.hpp"

namespace {

using namespace ito::relay;
using ito::relay::testing::SequenceTokens;
using std::chrono::milliseconds;
using std::chrono::minutes;
using std::chrono::seconds;

Principal principal(std::uint16_t number) {
    Principal bytes{};
    bytes[0] = static_cast<std::uint8_t>(number >> 8U);
    bytes[1] = static_cast<std::uint8_t>(number);
    return bytes;
}

const TimePoint kStart = TimePoint{} + minutes(10);

Reservation reserved(RoomTable& table, const Principal& owner, StreamId stream,
                     TimePoint now, std::uint32_t lifetime = 600'000U) {
    auto result = table.reserve(owner, stream, lifetime, now);
    CHECK(std::holds_alternative<Reservation>(result));
    return std::get<Reservation>(result);
}

JoinRequest request(const RoomId& room, Role role, const Token& token) {
    return JoinRequest{room, role, token};
}

RefusalReason refusal(const std::variant<Admitted, RefusalReason>& result) {
    CHECK(std::holds_alternative<RefusalReason>(result));
    return std::get<RefusalReason>(result);
}

void test_pairing_and_one_use_tokens() {
    SequenceTokens tokens;
    RoomTable table(Limits{}, tokens);
    const auto alice = principal(1);
    const auto bob = principal(2);
    const auto mallory = principal(3);
    CHECK(table.admit_stream(alice, kStart));
    CHECK(table.admit_stream(bob, kStart));
    CHECK(table.admit_stream(bob, kStart));
    CHECK(table.admit_stream(mallory, kStart));
    const auto room = reserved(table, alice, 10U, kStart);
    CHECK(room.initiator_token != room.responder_token &&
          room.room != room.initiator_token);

    // Only the reserving stream joins as initiator, with its own room.
    CHECK(refusal(table.join(
              bob, 20U, std::nullopt,
              request(room.room, Role::Initiator, room.initiator_token),
              kStart)) == RefusalReason::InvalidRoom);
    CHECK(refusal(table.join(
              alice, 10U, room.room,
              request(room.room, Role::Responder, room.responder_token),
              kStart)) == RefusalReason::InvalidRoom);
    // A wrong responder token.
    auto wrong = room.responder_token;
    wrong[31] ^= 1U;
    CHECK(refusal(table.join(bob, 20U, std::nullopt,
                             request(room.room, Role::Responder, wrong),
                             kStart)) == RefusalReason::InvalidRoom);
    // The initiator token does not open the responder role.
    CHECK(refusal(table.join(
              bob, 20U, std::nullopt,
              request(room.room, Role::Responder, room.initiator_token),
              kStart)) == RefusalReason::InvalidRoom);

    auto first = table.join(
        bob, 20U, std::nullopt,
        request(room.room, Role::Responder, room.responder_token), kStart);
    CHECK(std::holds_alternative<Admitted>(first) &&
          !std::get<Admitted>(first).paired);
    // The admitted token is spent: a replay finds the role occupied.
    CHECK(refusal(table.join(
              bob, 21U, std::nullopt,
              request(room.room, Role::Responder, room.responder_token),
              kStart)) == RefusalReason::InvalidRoom);
    CHECK(refusal(table.join(
              mallory, 30U, std::nullopt,
              request(room.room, Role::Responder, room.responder_token),
              kStart)) == RefusalReason::InvalidRoom);

    auto second = table.join(
        alice, 10U, room.room,
        request(room.room, Role::Initiator, room.initiator_token), kStart);
    CHECK(std::holds_alternative<Admitted>(second));
    const auto& admitted = std::get<Admitted>(second);
    CHECK(admitted.paired && admitted.initiator == 10U &&
          admitted.responder == 20U);
    CHECK(table.rooms() == 1U && table.paired_rooms() == 1U);
    // Nothing joins a paired room, even with an unused-looking token.
    CHECK(refusal(table.join(
              mallory, 30U, std::nullopt,
              request(room.room, Role::Initiator, room.initiator_token),
              kStart)) == RefusalReason::InvalidRoom);

    const auto closed = table.close_room(room.room);
    CHECK(closed && closed->reserver == 10U && closed->responder == 20U &&
          closed->paired);
    CHECK(table.rooms() == 0U && table.paired_rooms() == 0U);
    CHECK(!table.close_room(room.room));
    // Unknown and closed rooms refuse the same way.
    CHECK(refusal(table.join(
              mallory, 30U, std::nullopt,
              request(room.room, Role::Responder, room.responder_token),
              kStart)) == RefusalReason::InvalidRoom);
}

void test_reservation_and_paired_deadlines() {
    SequenceTokens tokens;
    RoomTable table(Limits{}, tokens);
    const auto alice = principal(1);
    const auto bob = principal(2);
    CHECK(table.admit_stream(alice, kStart));
    CHECK(table.admit_stream(bob, kStart));
    const auto room = reserved(table, alice, 10U, kStart, 2'000U);
    CHECK(table.next_deadline() == kStart + milliseconds(2'000));
    CHECK(table.expire(kStart + milliseconds(1'999)).empty());
    // The deadline itself is expired, and a JOIN at it is refused.
    CHECK(refusal(table.join(
              bob, 20U, std::nullopt,
              request(room.room, Role::Responder, room.responder_token),
              kStart + milliseconds(2'000))) == RefusalReason::InvalidRoom);
    const auto expired = table.expire(kStart + milliseconds(2'000));
    CHECK(expired.size() == 1U &&
          expired[0].end == RoomEnd::ReservationExpired);
    CHECK(expired[0].streams.reserver == 10U && !expired[0].streams.responder);
    CHECK(table.rooms() == 0U);

    const auto call = reserved(table, alice, 11U, kStart);
    CHECK(std::holds_alternative<Admitted>(table.join(
        alice, 11U, call.room,
        request(call.room, Role::Initiator, call.initiator_token), kStart)));
    const TimePoint paired_at = kStart + seconds(100);
    CHECK(std::get<Admitted>(table.join(bob, 21U, std::nullopt,
                                        request(call.room, Role::Responder,
                                                call.responder_token),
                                        paired_at))
              .paired);
    // Pairing replaces the 600-second reservation with the 30-minute lifetime.
    CHECK(table.next_deadline() == paired_at + minutes(30));
    CHECK(table.expire(paired_at + minutes(30) - milliseconds(1)).empty());
    const auto ended = table.expire(paired_at + minutes(30));
    CHECK(ended.size() == 1U && ended[0].end == RoomEnd::LifetimeExpired &&
          ended[0].streams.paired);
    CHECK(table.paired_rooms() == 0U);
}

void test_paired_deadline_equal_to_reservation() {
    SequenceTokens tokens;
    Limits limits;
    limits.paired_lifetime = std::chrono::seconds(100);
    RoomTable table(limits, tokens);
    const auto alice = principal(1);
    const auto bob = principal(2);
    CHECK(table.admit_stream(alice, kStart));
    CHECK(table.admit_stream(bob, kStart));
    const auto room = reserved(table, alice, 10U, kStart, 200'000U);
    CHECK(std::holds_alternative<Admitted>(table.join(
        alice, 10U, room.room,
        request(room.room, Role::Initiator, room.initiator_token), kStart)));
    // Paired exactly 100 s before the reservation ends: both deadlines match.
    CHECK(std::get<Admitted>(table.join(bob, 20U, std::nullopt,
                                        request(room.room, Role::Responder,
                                                room.responder_token),
                                        kStart + seconds(100)))
              .paired);
    CHECK(table.next_deadline() == kStart + seconds(200));
    const auto ended = table.expire(kStart + seconds(200));
    CHECK(ended.size() == 1U && ended[0].end == RoomEnd::LifetimeExpired);
    CHECK(table.rooms() == 0U);
}

void test_reserve_window_survives_reconnect() {
    SequenceTokens tokens;
    RoomTable table(Limits{}, tokens);
    const auto alice = principal(1);
    CHECK(table.admit_stream(alice, kStart));
    for (std::size_t index = 0U; index < kMaxReservesPerWindow; ++index) {
        const TimePoint at = kStart + seconds(static_cast<long>(index));
        auto room = reserved(table, alice, 10U + index, at);
        CHECK(table.close_room(room.room).has_value());
    }
    // The stream closes and a new one arrives inside the window.
    table.release_stream(alice);
    CHECK(table.principals() == 1U);
    CHECK(table.admit_stream(alice, kStart + seconds(20)));
    CHECK(std::get<RefusalReason>(
              table.reserve(alice, 30U, 1'000U, kStart + seconds(20))) ==
          RefusalReason::Capacity);
    // The window rolls: the first RESERVE leaves it at 60 seconds.
    CHECK(std::get<RefusalReason>(table.reserve(
              alice, 31U, 1'000U, kStart + seconds(60) - milliseconds(1))) ==
          RefusalReason::Capacity);
    auto again = table.reserve(alice, 32U, 1'000U, kStart + seconds(60));
    CHECK(std::holds_alternative<Reservation>(again));
}

void test_failed_join_quota_precedes_token_check() {
    SequenceTokens tokens;
    RoomTable table(Limits{}, tokens);
    const auto alice = principal(1);
    const auto bob = principal(2);
    CHECK(table.admit_stream(alice, kStart));
    CHECK(table.admit_stream(bob, kStart));
    const auto room = reserved(table, alice, 10U, kStart);
    Token wrong{};
    for (std::size_t index = 0U; index < kMaxFailedJoinsPerWindow; ++index) {
        CHECK(refusal(table.join(
                  bob, 20U, std::nullopt,
                  request(room.room, Role::Responder, wrong),
                  kStart + milliseconds(static_cast<long>(index)))) ==
              RefusalReason::InvalidRoom);
    }
    // At the quota even the correct token is not examined.
    CHECK(refusal(table.join(
              bob, 20U, std::nullopt,
              request(room.room, Role::Responder, room.responder_token),
              kStart + seconds(1))) == RefusalReason::Capacity);
    // The quota survives the stream and its reconnect.
    table.release_stream(bob);
    CHECK(table.principals() == 2U);
    CHECK(table.admit_stream(bob, kStart + seconds(2)));
    CHECK(refusal(table.join(
              bob, 21U, std::nullopt,
              request(room.room, Role::Responder, room.responder_token),
              kStart + seconds(2))) == RefusalReason::Capacity);
    // Rate refusals are not counted, so the window ends 60 s after the
    // twentieth failure.
    CHECK(std::holds_alternative<Admitted>(
        table.join(bob, 21U, std::nullopt,
                   request(room.room, Role::Responder, room.responder_token),
                   kStart + seconds(60) + milliseconds(19))));
}

void test_retention_and_principal_capacity() {
    SequenceTokens tokens;
    RoomTable table(Limits{}, tokens);
    // A principal that counted nothing is forgotten when its stream ends.
    CHECK(table.admit_stream(principal(9000), kStart));
    table.release_stream(principal(9000));
    CHECK(table.principals() == 0U);

    const Limits limits;
    for (std::uint16_t number = 0U; number < limits.max_principals; ++number) {
        const auto owner = principal(number);
        CHECK(table.admit_stream(owner, kStart));
        auto room = reserved(table, owner, 100U + number, kStart, 1'000U);
        CHECK(table.close_room(room.room).has_value());
        table.release_stream(owner);
    }
    CHECK(table.principals() == limits.max_principals);
    // Full of retained buckets: a new principal is refused, never evicting.
    CHECK(!table.admit_stream(principal(9001), kStart + seconds(59)));
    CHECK(table.principals() == limits.max_principals);
    // A retained principal reconnects within its window.
    CHECK(table.admit_stream(principal(5), kStart + seconds(59)));
    table.release_stream(principal(5));
    CHECK(table.next_deadline() == kStart + seconds(60));
    CHECK(table.expire(kStart + seconds(60)).empty());
    CHECK(table.principals() == 0U);
    CHECK(table.admit_stream(principal(9001), kStart + seconds(60)));
}

void test_stream_and_room_quotas() {
    SequenceTokens tokens;
    RoomTable table(Limits{}, tokens);
    const Limits limits;
    const auto alice = principal(1);
    for (std::size_t index = 0U; index < limits.max_streams_per_principal;
         ++index)
        CHECK(table.admit_stream(alice, kStart));
    CHECK(!table.admit_stream(alice, kStart));
    table.release_stream(alice);
    CHECK(table.admit_stream(alice, kStart));

    reserved(table, alice, 1U, kStart);
    reserved(table, alice, 2U, kStart);
    CHECK(std::get<RefusalReason>(table.reserve(alice, 3U, 1'000U, kStart)) ==
          RefusalReason::Capacity);

    // 512 rooms across principals, then the service is full.
    RoomTable full(Limits{}, tokens);
    for (std::uint16_t number = 0U; number < limits.max_rooms / 2U; ++number) {
        const auto owner = principal(number);
        CHECK(full.admit_stream(owner, kStart));
        reserved(full, owner, 2U * number, kStart);
        reserved(full, owner, 2U * number + 1U, kStart);
    }
    CHECK(full.rooms() == limits.max_rooms);
    CHECK(full.admit_stream(principal(4000), kStart));
    CHECK(std::get<RefusalReason>(
              full.reserve(principal(4000), 9999U, 1'000U, kStart)) ==
          RefusalReason::Capacity);
}

void test_random_failure_publishes_nothing() {
    SequenceTokens tokens;
    RoomTable table(Limits{}, tokens);
    const auto alice = principal(1);
    CHECK(table.admit_stream(alice, kStart));
    tokens.fail = true;
    CHECK(std::get<RefusalReason>(table.reserve(alice, 1U, 1'000U, kStart)) ==
          RefusalReason::Capacity);
    CHECK(table.rooms() == 0U && !table.next_deadline());
    tokens.fail = false;
    reserved(table, alice, 2U, kStart);
    CHECK(table.rooms() == 1U);
}

void test_equal_secret() {
    Token left{};
    Token right{};
    CHECK(equal_secret(left, right));
    for (std::size_t index = 0U; index < right.size(); ++index) {
        right = {};
        right[index] = 0x80U;
        CHECK(!equal_secret(left, right));
    }
}

}  // namespace

int main() {
    test_pairing_and_one_use_tokens();
    test_reservation_and_paired_deadlines();
    test_paired_deadline_equal_to_reservation();
    test_reserve_window_survives_reconnect();
    test_failed_join_quota_precedes_token_check();
    test_retention_and_principal_capacity();
    test_stream_and_room_quotas();
    test_random_failure_publishes_nothing();
    test_equal_secret();
    std::puts("room table: all checks passed");
    return 0;
}
