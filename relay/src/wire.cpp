/* Copyright (C) 2026 FixCraft Inc. AGPL-3.0-or-later. */

#include "wire.hpp"

#include <algorithm>

namespace ito::relay {
namespace {

constexpr std::size_t kFingerprintStart = kModuleHeaderPrefix.size();
constexpr std::size_t kFingerprintEnd = kFingerprintStart + kFingerprintDigits;
constexpr std::size_t kServiceStart = kFingerprintEnd + 1U;

constexpr std::size_t kReservePayloadBytes = 2U + 4U;
constexpr std::size_t kJoinPayloadBytes = 2U + 32U + 1U + 32U;

int hex_value(std::byte byte) noexcept {
    const auto value = std::to_integer<unsigned char>(byte);
    if (value >= '0' && value <= '9') return value - '0';
    if (value >= 'a' && value <= 'f') return value - 'a' + 10;
    return -1;
}

std::uint16_t read_u16(std::span<const std::byte> bytes) noexcept {
    return static_cast<std::uint16_t>(
        (std::to_integer<unsigned>(bytes[0]) << 8U) |
        std::to_integer<unsigned>(bytes[1]));
}

std::uint32_t read_u32(std::span<const std::byte> bytes) noexcept {
    return (std::to_integer<std::uint32_t>(bytes[0]) << 24U) |
           (std::to_integer<std::uint32_t>(bytes[1]) << 16U) |
           (std::to_integer<std::uint32_t>(bytes[2]) << 8U) |
           std::to_integer<std::uint32_t>(bytes[3]);
}

void write_u32(std::byte* out, std::uint32_t value) noexcept {
    out[0] = static_cast<std::byte>(value >> 24U);
    out[1] = static_cast<std::byte>(value >> 16U);
    out[2] = static_cast<std::byte>(value >> 8U);
    out[3] = static_cast<std::byte>(value);
}

template <std::size_t N>
void copy_field(std::span<const std::byte> from,
                std::array<std::uint8_t, N>& to) noexcept {
    std::transform(
        from.begin(), from.begin() + N, to.begin(),
        [](std::byte byte) { return std::to_integer<std::uint8_t>(byte); });
}

template <std::size_t N>
std::byte* put_field(std::byte* out,
                     const std::array<std::uint8_t, N>& field) noexcept {
    return std::transform(field.begin(), field.end(), out,
                          [](std::uint8_t byte) { return std::byte{byte}; });
}

RequestParse invalid() noexcept {
    return {RequestStatus::Invalid, 0U, {}};
}

// The bytes a canonical service name may contain. Segment rules are checked
// once the name is complete.
bool service_byte(unsigned char value) noexcept {
    return (value >= 'a' && value <= 'z') || (value >= '0' && value <= '9') ||
           value == '.' || value == '-' || value == '_';
}

// RESERVE and JOIN start with their version. A version other than 1 is
// unsupported whatever its length, so a later version can grow its payload.
bool unsupported_version(std::span<const std::byte> payload) noexcept {
    return payload.size() >= 2U && read_u16(payload) != kProtocolVersion;
}

}  // namespace

bool valid_service_name(std::string_view name) noexcept {
    if (name.empty() || name.size() > kMaxServiceBytes) return false;
    if (name == "yume" || name.starts_with("yume.")) return false;
    bool at_segment_start = true;
    bool previous_is_alphanumeric = false;
    for (const char character : name) {
        const auto byte = static_cast<unsigned char>(character);
        const bool alphanumeric =
            (byte >= 'a' && byte <= 'z') || (byte >= '0' && byte <= '9');
        if (alphanumeric) {
            at_segment_start = false;
            previous_is_alphanumeric = true;
        } else if (byte == '.') {
            if (at_segment_start || !previous_is_alphanumeric) return false;
            at_segment_start = true;
            previous_is_alphanumeric = false;
        } else if (byte == '-' || byte == '_') {
            if (at_segment_start) return false;
            previous_is_alphanumeric = false;
        } else {
            return false;
        }
    }
    return !at_segment_start && previous_is_alphanumeric;
}

HeaderParse parse_module_header(std::span<const std::byte> input,
                                std::string_view service) noexcept {
    const std::size_t available = std::min(input.size(), kMaxHeaderBytes);
    for (std::size_t index = 0U; index < available; ++index) {
        const auto byte = input[index];
        if (index < kFingerprintStart) {
            if (std::to_integer<char>(byte) != kModuleHeaderPrefix[index])
                return {HeaderStatus::Malformed, 0U, {}};
        } else if (index < kFingerprintEnd) {
            if (hex_value(byte) < 0) return {HeaderStatus::Malformed, 0U, {}};
        } else if (index == kFingerprintEnd) {
            if (std::to_integer<char>(byte) != ' ')
                return {HeaderStatus::Malformed, 0U, {}};
        } else if (std::to_integer<char>(byte) == '\n') {
            const std::string_view named(
                reinterpret_cast<const char*>(input.data()) + kServiceStart,
                index - kServiceStart);
            if (!valid_service_name(named))
                return {HeaderStatus::Malformed, 0U, {}};
            if (named != service) return {HeaderStatus::OtherService, 0U, {}};
            HeaderParse parsed{HeaderStatus::Accepted, index + 1U, {}};
            for (std::size_t digit = 0U; digit < parsed.principal.size();
                 ++digit) {
                const auto high =
                    hex_value(input[kFingerprintStart + 2U * digit]);
                const auto low =
                    hex_value(input[kFingerprintStart + 2U * digit + 1U]);
                parsed.principal[digit] =
                    static_cast<std::uint8_t>((high << 4) | low);
            }
            return parsed;
        } else if (index - kServiceStart >= kMaxServiceBytes ||
                   !service_byte(std::to_integer<unsigned char>(byte))) {
            return {HeaderStatus::Malformed, 0U, {}};
        }
    }
    return {HeaderStatus::Incomplete, 0U, {}};
}

RequestParse parse_request(std::span<const std::byte> input) noexcept {
    if (input.size() < kFrameLengthBytes) return {};
    const std::uint32_t body = read_u32(input);
    if (body == 0U || body > kMaxFrameBody) return invalid();
    if (input.size() - kFrameLengthBytes < body) return {};
    const auto type = std::to_integer<std::uint8_t>(input[kFrameLengthBytes]);
    const auto payload = input.subspan(kFrameLengthBytes + 1U, body - 1U);
    const std::size_t consumed = kFrameLengthBytes + body;
    switch (type) {
        case static_cast<std::uint8_t>(FrameType::Reserve): {
            if (unsupported_version(payload))
                return {RequestStatus::Unsupported, 0U, {}};
            if (payload.size() != kReservePayloadBytes) return invalid();
            const std::uint32_t lifetime = read_u32(payload.subspan(2U));
            if (lifetime == 0U || lifetime > kMaxReservationMilliseconds)
                return invalid();
            return {RequestStatus::Decoded, consumed, ReserveRequest{lifetime}};
        }
        case static_cast<std::uint8_t>(FrameType::Join): {
            if (unsupported_version(payload))
                return {RequestStatus::Unsupported, 0U, {}};
            if (payload.size() != kJoinPayloadBytes) return invalid();
            const auto role = std::to_integer<std::uint8_t>(payload[34]);
            if (role != static_cast<std::uint8_t>(Role::Initiator) &&
                role != static_cast<std::uint8_t>(Role::Responder))
                return invalid();
            JoinRequest join;
            copy_field(payload.subspan(2U, 32U), join.room);
            join.role = static_cast<Role>(role);
            copy_field(payload.subspan(35U, 32U), join.token);
            return {RequestStatus::Decoded, consumed, join};
        }
        case static_cast<std::uint8_t>(FrameType::Cancel):
            if (!payload.empty()) return invalid();
            return {RequestStatus::Decoded, consumed, CancelRequest{}};
        default:
            // Unknown types and the service's own frame types.
            return invalid();
    }
}

ReservedFrame encode_reserved(const RoomId& room, const Token& initiator,
                              const Token& responder,
                              std::uint32_t lifetime_milliseconds) noexcept {
    ReservedFrame frame{};
    write_u32(frame.data(),
              static_cast<std::uint32_t>(frame.size() - kFrameLengthBytes));
    frame[kFrameLengthBytes] =
        std::byte{static_cast<std::uint8_t>(FrameType::Reserved)};
    auto* out = frame.data() + kFrameLengthBytes + 1U;
    out = put_field(out, room);
    out = put_field(out, initiator);
    out = put_field(out, responder);
    write_u32(out, lifetime_milliseconds);
    return frame;
}

SignalFrame encode_signal(FrameType type) noexcept {
    SignalFrame frame{};
    write_u32(frame.data(), 1U);
    frame[kFrameLengthBytes] = std::byte{static_cast<std::uint8_t>(type)};
    return frame;
}

RefusedFrame encode_refused(RefusalReason reason) noexcept {
    RefusedFrame frame{};
    write_u32(frame.data(), 2U);
    frame[kFrameLengthBytes] =
        std::byte{static_cast<std::uint8_t>(FrameType::Refused)};
    frame[kFrameLengthBytes + 1U] =
        std::byte{static_cast<std::uint8_t>(reason)};
    return frame;
}

}  // namespace ito::relay
