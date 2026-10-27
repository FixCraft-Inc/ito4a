/* Copyright (C) 2026 FixCraft Inc. AGPL-3.0-or-later. */

// Grammar vectors for the module header, the control frames and the replies.
// Expected bytes are written out from docs/CALL_RELAY.md, not derived from
// the encoder under test.

#include "wire.hpp"

#include <cstdio>
#include <string>
#include <string_view>
#include <vector>

#include "check.hpp"

namespace {

using namespace ito::relay;

std::vector<std::byte> bytes(std::string_view text) {
    std::vector<std::byte> out;
    for (const char character : text)
        out.push_back(static_cast<std::byte>(character));
    return out;
}

std::vector<std::byte> concat(std::vector<std::byte> left,
                              const std::vector<std::byte>& right) {
    left.insert(left.end(), right.begin(), right.end());
    return left;
}

std::vector<std::byte> frame(std::uint32_t length,
                             std::vector<std::uint8_t> body) {
    std::vector<std::byte> out{static_cast<std::byte>(length >> 24U),
                               static_cast<std::byte>(length >> 16U),
                               static_cast<std::byte>(length >> 8U),
                               static_cast<std::byte>(length)};
    for (const auto value : body) out.push_back(std::byte{value});
    return out;
}

std::vector<std::uint8_t> repeated(std::size_t count, std::uint8_t value) {
    return std::vector<std::uint8_t>(count, value);
}

std::vector<std::uint8_t> join_body(std::uint16_t version, std::uint8_t role) {
    std::vector<std::uint8_t> body{3U, static_cast<std::uint8_t>(version >> 8U),
                                   static_cast<std::uint8_t>(version)};
    for (std::uint8_t index = 0U; index < 32U; ++index) body.push_back(index);
    body.push_back(role);
    for (std::uint8_t index = 0U; index < 32U; ++index)
        body.push_back(static_cast<std::uint8_t>(0xa0U + index));
    return body;
}

const std::string kFingerprint =
    "00112233445566778899aabbccddeeff0123456789abcdef0f1e2d3c4b5a6978";

void test_service_names() {
    for (const std::string_view name : {"ito-call", "a", "a.b", "a-b", "a_b",
                                        "a1.b2-c_d", "yumex", "x.yume", "0"}) {
        CHECK(valid_service_name(name));
    }
    const std::string longest(64U, 'a');
    CHECK(valid_service_name(longest));
    CHECK(!valid_service_name(longest + "a"));
    for (const std::string_view name :
         {"", "A", "Ito", "-a", "a-", "_a", "a_", "a.", ".a", "a..b", "a.-b",
          "a-.b", "a b", "a/b", "yume", "yume.call", "caf\xc3\xa9"}) {
        CHECK(!valid_service_name(name));
    }
    CHECK(!valid_service_name(std::string_view("a\0b", 3U)));
}

void test_header_accepts_exact_line() {
    const auto input =
        bytes("yume-module 1 " + kFingerprint + " ito-call\nREST");
    const auto parsed = parse_module_header(input, "ito-call");
    CHECK(parsed.status == HeaderStatus::Accepted);
    CHECK(parsed.consumed == 14U + 64U + 1U + 8U + 1U);
    const Principal expected{0x00, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77,
                             0x88, 0x99, 0xaa, 0xbb, 0xcc, 0xdd, 0xee, 0xff,
                             0x01, 0x23, 0x45, 0x67, 0x89, 0xab, 0xcd, 0xef,
                             0x0f, 0x1e, 0x2d, 0x3c, 0x4b, 0x5a, 0x69, 0x78};
    CHECK(parsed.principal == expected);
    // The bytes after the newline are not part of the header.
    CHECK(std::string_view(
              reinterpret_cast<const char*>(input.data()) + parsed.consumed,
              4U) == "REST");
}

void test_header_is_incomplete_at_every_prefix() {
    const auto input = bytes("yume-module 1 " + kFingerprint + " ito-call\n");
    for (std::size_t length = 0U; length < input.size(); ++length) {
        const auto parsed = parse_module_header(
            std::span<const std::byte>(input.data(), length), "ito-call");
        CHECK(parsed.status == HeaderStatus::Incomplete);
    }
}

void test_header_maximum_length() {
    const std::string service(64U, 's');
    const auto input =
        bytes("yume-module 1 " + kFingerprint + " " + service + "\n");
    CHECK(input.size() == kMaxHeaderBytes);
    CHECK(parse_module_header(input, service).status == HeaderStatus::Accepted);
    // A 65th service byte is refused before any newline arrives.
    const auto longer =
        bytes("yume-module 1 " + kFingerprint + " " + service + "s");
    CHECK(parse_module_header(longer, service).status ==
          HeaderStatus::Malformed);
}

void test_header_refusals() {
    const auto check_status = [](const std::string& text,
                                 HeaderStatus expected) {
        CHECK(parse_module_header(bytes(text), "ito-call").status == expected);
    };
    std::string upper = kFingerprint;
    upper[10] = 'A';
    check_status("yume-module 2 " + kFingerprint + " ito-call\n",
                 HeaderStatus::Malformed);
    check_status("yume-module 1  " + kFingerprint + " ito-call\n",
                 HeaderStatus::Malformed);
    check_status("yume-module 1 " + upper + " ito-call\n",
                 HeaderStatus::Malformed);
    check_status("yume-module 1 " + kFingerprint.substr(0, 63) + " ito-call\n",
                 HeaderStatus::Malformed);
    check_status("yume-module 1 " + kFingerprint + "0 ito-call\n",
                 HeaderStatus::Malformed);
    check_status("yume-module 1 " + kFingerprint + " \n",
                 HeaderStatus::Malformed);
    check_status("yume-module 1 " + kFingerprint + " Ito-call\n",
                 HeaderStatus::Malformed);
    check_status("yume-module 1 " + kFingerprint + " ito-call \n",
                 HeaderStatus::Malformed);
    check_status("yume-module 1 " + kFingerprint + " ito-call\r\n",
                 HeaderStatus::Malformed);
    check_status("yume-module 1 " + kFingerprint + " echo\n",
                 HeaderStatus::OtherService);
    check_status("yume-module 1 " + kFingerprint + " ito-calls\n",
                 HeaderStatus::OtherService);
    // The first wrong byte refuses at once, in the service name as well.
    check_status("YUME", HeaderStatus::Malformed);
    check_status("yume-module 1 0g", HeaderStatus::Malformed);
    check_status("yume-module 1 " + kFingerprint + " ito-C",
                 HeaderStatus::Malformed);
    check_status("yume-module 1 " + kFingerprint + " ito call",
                 HeaderStatus::Malformed);
    check_status("yume-module 1 " + kFingerprint + " ito-c",
                 HeaderStatus::Incomplete);
}

void test_reserve() {
    const auto valid = frame(7U, {1U, 0U, 1U, 0x00U, 0x09U, 0x27U, 0xc0U});
    auto parsed = parse_request(valid);
    CHECK(parsed.status == RequestStatus::Decoded);
    CHECK(parsed.consumed == 11U);
    CHECK(std::get<ReserveRequest>(parsed.request).lifetime_milliseconds ==
          600'000U);

    CHECK(std::get<ReserveRequest>(
              parse_request(frame(7U, {1U, 0U, 1U, 0U, 0U, 0U, 1U})).request)
              .lifetime_milliseconds == 1U);
    CHECK(parse_request(frame(7U, {1U, 0U, 1U, 0U, 0U, 0U, 0U})).status ==
          RequestStatus::Invalid);
    CHECK(parse_request(frame(7U, {1U, 0U, 1U, 0x00U, 0x09U, 0x27U, 0xc1U}))
              .status == RequestStatus::Invalid);
    CHECK(parse_request(frame(7U, {1U, 0U, 2U, 0U, 0U, 0U, 1U})).status ==
          RequestStatus::Unsupported);
    // A later version may carry another payload size.
    CHECK(parse_request(frame(4U, {1U, 0U, 2U, 9U})).status ==
          RequestStatus::Unsupported);
    // Trailing and missing payload bytes.
    CHECK(parse_request(frame(8U, {1U, 0U, 1U, 0U, 0U, 0U, 1U, 0U})).status ==
          RequestStatus::Invalid);
    CHECK(parse_request(frame(6U, {1U, 0U, 1U, 0U, 0U, 1U})).status ==
          RequestStatus::Invalid);
    CHECK(parse_request(frame(2U, {1U, 0U})).status == RequestStatus::Invalid);
}

void test_join_and_cancel() {
    auto parsed = parse_request(frame(68U, join_body(1U, 2U)));
    CHECK(parsed.status == RequestStatus::Decoded && parsed.consumed == 72U);
    const auto& join = std::get<JoinRequest>(parsed.request);
    CHECK(join.role == Role::Responder);
    CHECK(join.room[0] == 0U && join.room[31] == 31U);
    CHECK(join.token[0] == 0xa0U && join.token[31] == 0xbfU);
    CHECK(std::get<JoinRequest>(
              parse_request(frame(68U, join_body(1U, 1U))).request)
              .role == Role::Initiator);
    CHECK(parse_request(frame(68U, join_body(1U, 0U))).status ==
          RequestStatus::Invalid);
    CHECK(parse_request(frame(68U, join_body(1U, 3U))).status ==
          RequestStatus::Invalid);
    CHECK(parse_request(frame(68U, join_body(2U, 1U))).status ==
          RequestStatus::Unsupported);
    auto shorter = join_body(1U, 1U);
    shorter.pop_back();
    CHECK(parse_request(frame(67U, shorter)).status == RequestStatus::Invalid);

    parsed = parse_request(frame(1U, {6U}));
    CHECK(parsed.status == RequestStatus::Decoded && parsed.consumed == 5U);
    CHECK(std::holds_alternative<CancelRequest>(parsed.request));
    CHECK(parse_request(frame(2U, {6U, 0U})).status == RequestStatus::Invalid);
}

void test_frame_bounds_and_types() {
    // Lengths outside 1..4096 refuse as soon as the length is present.
    CHECK(parse_request(frame(0U, {})).status == RequestStatus::Invalid);
    CHECK(parse_request(frame(4097U, {})).status == RequestStatus::Invalid);
    CHECK(parse_request(frame(0xffffffffU, {})).status ==
          RequestStatus::Invalid);
    // A maximal frame waits for all of its bytes, then refuses its type.
    auto body = repeated(4096U, 0U);
    // RESERVE version 1 with a payload far longer than six bytes.
    body[0] = 1U;
    body[2] = 1U;
    auto maximal = frame(4096U, body);
    CHECK(parse_request(
              std::span<const std::byte>(maximal.data(), maximal.size() - 1U))
              .status == RequestStatus::Incomplete);
    CHECK(parse_request(maximal).status == RequestStatus::Invalid);
    for (const int type : {0, 2, 4, 5, 7, 8, 255})
        CHECK(parse_request(frame(1U, {static_cast<std::uint8_t>(type)}))
                  .status == RequestStatus::Invalid);
}

void test_partial_and_coalesced_frames() {
    const auto reserve = frame(7U, {1U, 0U, 1U, 0U, 0U, 0x03U, 0xe8U});
    const auto cancel = frame(1U, {6U});
    const auto both = concat(reserve, cancel);
    for (std::size_t length = 0U; length < reserve.size(); ++length) {
        CHECK(parse_request(std::span<const std::byte>(both.data(), length))
                  .status == RequestStatus::Incomplete);
    }
    const auto first = parse_request(both);
    CHECK(first.status == RequestStatus::Decoded &&
          first.consumed == reserve.size());
    const auto second =
        parse_request(std::span<const std::byte>(both).subspan(first.consumed));
    CHECK(second.status == RequestStatus::Decoded &&
          second.consumed == cancel.size());
}

void test_replies() {
    RoomId room{};
    Token initiator{};
    Token responder{};
    for (std::uint8_t index = 0U; index < 32U; ++index) {
        room[index] = index;
        initiator[index] = static_cast<std::uint8_t>(0x40U + index);
        responder[index] = static_cast<std::uint8_t>(0x80U + index);
    }
    const auto reserved =
        encode_reserved(room, initiator, responder, 0x01020304U);
    CHECK(reserved.size() == 105U);
    CHECK(reserved[0] == std::byte{0} && reserved[1] == std::byte{0} &&
          reserved[2] == std::byte{0} && reserved[3] == std::byte{101});
    CHECK(reserved[4] == std::byte{2});
    CHECK(reserved[5] == std::byte{0} && reserved[36] == std::byte{31});
    CHECK(reserved[37] == std::byte{0x40} && reserved[69] == std::byte{0x80});
    CHECK(reserved[100] == std::byte{0x9f});
    CHECK(reserved[101] == std::byte{1} && reserved[104] == std::byte{4});

    const auto waiting = encode_signal(FrameType::Waiting);
    CHECK((waiting == SignalFrame{std::byte{0}, std::byte{0}, std::byte{0},
                                  std::byte{1}, std::byte{4}}));
    const auto paired = encode_signal(FrameType::Paired);
    CHECK(paired[4] == std::byte{5});
    const auto refused = encode_refused(RefusalReason::Timeout);
    CHECK((refused == RefusedFrame{std::byte{0}, std::byte{0}, std::byte{0},
                                   std::byte{2}, std::byte{7}, std::byte{5}}));
}

}  // namespace

int main() {
    test_service_names();
    test_header_accepts_exact_line();
    test_header_is_incomplete_at_every_prefix();
    test_header_maximum_length();
    test_header_refusals();
    test_reserve();
    test_join_and_cancel();
    test_frame_bounds_and_types();
    test_partial_and_coalesced_frames();
    test_replies();
    std::puts("wire: all checks passed");
    return 0;
}
