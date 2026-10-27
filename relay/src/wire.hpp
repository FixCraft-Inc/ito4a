/* Copyright (C) 2026 FixCraft Inc. AGPL-3.0-or-later. */

// The relay's two input grammars and its replies: the module header yumed
// writes before each stream, and the version-1 control frames of
// docs/CALL_RELAY.md. Parsing is pure, bounded and allocation free. A parse
// reports how many bytes it consumed and never looks past them, so bytes that
// follow a header or a frame in the same read stay with the caller.

#pragma once

#include <array>
#include <cstddef>
#include <cstdint>
#include <span>
#include <string_view>
#include <variant>

namespace ito::relay {

using Principal = std::array<std::uint8_t, 32>;
using RoomId = std::array<std::uint8_t, 32>;
using Token = std::array<std::uint8_t, 32>;

inline constexpr std::string_view kModuleHeaderPrefix = "yume-module 1 ";
inline constexpr std::size_t kFingerprintDigits = 64U;
inline constexpr std::size_t kMaxServiceBytes = 64U;
inline constexpr std::size_t kMaxHeaderBytes = kModuleHeaderPrefix.size() +
                                               kFingerprintDigits + 1U +
                                               kMaxServiceBytes + 1U;
static_assert(kMaxHeaderBytes == 144U);

inline constexpr std::size_t kFrameLengthBytes = 4U;
inline constexpr std::uint32_t kMaxFrameBody = 4096U;
inline constexpr std::size_t kMaxFrameBytes = kFrameLengthBytes + kMaxFrameBody;
inline constexpr std::uint16_t kProtocolVersion = 1U;
inline constexpr std::uint32_t kMaxReservationMilliseconds = 600'000U;

enum class FrameType : std::uint8_t {
    Reserve = 1,
    Reserved = 2,
    Join = 3,
    Waiting = 4,
    Paired = 5,
    Cancel = 6,
    Refused = 7,
};

enum class Role : std::uint8_t {
    Initiator = 1,
    Responder = 2,
};

enum class RefusalReason : std::uint8_t {
    InvalidRoom = 1,
    UnsupportedProtocol = 2,
    Capacity = 3,
    Canceled = 4,
    Timeout = 5,
    InvalidRequest = 6,
};
inline constexpr std::size_t kRefusalReasons = 6U;

// YUME's canonical service-name grammar, narrowed to this application's
// 64 bytes, without the names YUME reserves for itself. YUME's
// src/common/service_name.hpp owns the grammar. A module cannot include that
// private header, so wire_test.cpp carries vectors for this copy.
[[nodiscard]] bool valid_service_name(std::string_view name) noexcept;

enum class HeaderStatus {
    Incomplete,
    Accepted,
    Malformed,
    OtherService,
};

struct HeaderParse final {
    HeaderStatus status{HeaderStatus::Incomplete};
    // Bytes of the header line, newline included, when Accepted.
    std::size_t consumed{0U};
    // The fingerprint's 32 bytes, when Accepted.
    Principal principal{};
};

// Parses "yume-module 1 <64 lowercase hex> <service>\n" from the start of
// input. Rejects the first byte no canonical header can contain at that
// position. The service name's segment rules are checked at its newline,
// which comes within 144 bytes. OtherService is a well-formed header naming
// a service other than `service`.
[[nodiscard]] HeaderParse parse_module_header(
    std::span<const std::byte> input, std::string_view service) noexcept;

struct ReserveRequest final {
    std::uint32_t lifetime_milliseconds{0U};
};

struct JoinRequest final {
    RoomId room{};
    Role role{Role::Initiator};
    Token token{};
};

struct CancelRequest final {};

using Request = std::variant<ReserveRequest, JoinRequest, CancelRequest>;

enum class RequestStatus {
    Incomplete,
    Decoded,
    // A length outside 1..4096, an unknown or service-only type, a wrong
    // payload size or an out-of-range field.
    Invalid,
    // A RESERVE or JOIN whose version field is not 1.
    Unsupported,
};

struct RequestParse final {
    RequestStatus status{RequestStatus::Incomplete};
    std::size_t consumed{0U};
    Request request{};
};

// Decodes one client request from the start of input. A length outside the
// version-1 bounds is Invalid as soon as its four bytes are present, before
// any body is buffered.
[[nodiscard]] RequestParse parse_request(
    std::span<const std::byte> input) noexcept;

inline constexpr std::size_t kReservedFrameBytes =
    kFrameLengthBytes + 1U + 3U * 32U + 4U;
inline constexpr std::size_t kSignalFrameBytes = kFrameLengthBytes + 1U;
inline constexpr std::size_t kRefusedFrameBytes = kFrameLengthBytes + 2U;

using ReservedFrame = std::array<std::byte, kReservedFrameBytes>;
using SignalFrame = std::array<std::byte, kSignalFrameBytes>;
using RefusedFrame = std::array<std::byte, kRefusedFrameBytes>;

[[nodiscard]] ReservedFrame encode_reserved(
    const RoomId& room, const Token& initiator, const Token& responder,
    std::uint32_t lifetime_milliseconds) noexcept;
// WAITING or PAIRED, which carry no payload.
[[nodiscard]] SignalFrame encode_signal(FrameType type) noexcept;
[[nodiscard]] RefusedFrame encode_refused(RefusalReason reason) noexcept;

}  // namespace ito::relay
