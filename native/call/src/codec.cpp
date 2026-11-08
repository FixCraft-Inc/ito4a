/* Copyright (C) 2026 FixCraft Inc. AGPL-3.0-or-later. */

#include "codec.hpp"

#include <algorithm>
#include <array>

namespace ito::call {
namespace {

constexpr std::string_view kBase64Url =
    "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
constexpr std::string_view kFingerprintDomain{"ito/contact-identity/1\0", 23U};
constexpr std::string_view kContextDomain{"ito/call-context/1\0", 19U};
// 32 bytes are 256 bits. 43 base64 characters carry 258, so the final
// character's two low bits are padding and must be zero.
constexpr std::size_t kKeyBase64Chars = 43U;

[[nodiscard]] int base64url_value(char character) noexcept {
    const std::size_t index = kBase64Url.find(character);
    return index == std::string_view::npos ? -1 : static_cast<int>(index);
}

[[nodiscard]] bool body_length_valid(std::uint32_t body) noexcept {
    return body >= kMinFrameBody && body <= kMaxFrameBody;
}

// The exact payload length each frame type carries.
[[nodiscard]] bool payload_length_valid(FrameType type,
                                        std::size_t size) noexcept {
    switch (type) {
        case FrameType::Offer:
        case FrameType::Select:
            return size == 2U;
        case FrameType::Auth:
            return size == kSignatureBytes;
        case FrameType::Accept:
            return size == kHashBytes;
        case FrameType::Data:
            return size >= 1U && size <= kMaxDataBytes;
        case FrameType::End:
            return size == 1U;
    }
    return false;
}

[[nodiscard]] bool known_frame_type(std::uint8_t value) noexcept {
    return value >= static_cast<std::uint8_t>(FrameType::Offer) &&
           value <= static_cast<std::uint8_t>(FrameType::End);
}

}  // namespace

std::string format_contact_code(const Key& key) {
    std::string result;
    result.reserve(kContactCodeChars);
    result.append(kContactCodePrefix).append(kContactCodeVersion);
    std::uint32_t accumulator = 0U;
    int bits = 0;
    for (const Byte byte : key) {
        accumulator = (accumulator << 8U) | byte;
        bits += 8;
        while (bits >= 6) {
            bits -= 6;
            result.push_back(kBase64Url[(accumulator >> bits) & 0x3FU]);
        }
    }
    if (bits > 0) {
        result.push_back(kBase64Url[(accumulator << (6 - bits)) & 0x3FU]);
    }
    return result;
}

CodecStatus parse_contact_code(std::string_view code, Key& key) {
    if (!code.starts_with(kContactCodePrefix)) return CodecStatus::Malformed;
    code.remove_prefix(kContactCodePrefix.size());
    if (!code.starts_with(kContactCodeVersion)) {
        // A different decimal version is well formed but unsupported.
        const std::size_t colon = code.find(':');
        const bool numeric =
            colon != std::string_view::npos && colon > 0U &&
            std::all_of(code.begin(), code.begin() + colon,
                        [](char c) { return c >= '0' && c <= '9'; });
        return numeric ? CodecStatus::Unsupported : CodecStatus::Malformed;
    }
    code.remove_prefix(kContactCodeVersion.size());
    if (code.size() != kKeyBase64Chars) return CodecStatus::Malformed;
    Key decoded{};
    std::uint32_t accumulator = 0U;
    int bits = 0;
    std::size_t written = 0U;
    for (const char character : code) {
        const int value = base64url_value(character);
        if (value < 0) return CodecStatus::Malformed;
        accumulator = (accumulator << 6U) | static_cast<std::uint32_t>(value);
        bits += 6;
        if (bits >= 8) {
            bits -= 8;
            if (written == decoded.size()) return CodecStatus::Malformed;
            decoded[written++] = static_cast<Byte>(accumulator >> bits);
        }
    }
    // Two leftover bits: they must be zero for the one canonical encoding.
    if (written != decoded.size() || bits != 2 || (accumulator & 0x3U) != 0U) {
        return CodecStatus::Malformed;
    }
    key = decoded;
    return CodecStatus::Ok;
}

Hash fingerprint(const CryptoContext& crypto, const Key& key) {
    const std::array<ByteView, 2> parts{as_bytes(kFingerprintDomain), key};
    return crypto.sha256(parts);
}

std::string to_hex(ByteView bytes) {
    constexpr std::string_view digits = "0123456789abcdef";
    std::string result;
    result.reserve(2U * bytes.size());
    for (const Byte byte : bytes) {
        result.push_back(digits[byte >> 4U]);
        result.push_back(digits[byte & 0xFU]);
    }
    return result;
}

bool fingerprint_matches(std::string_view compared,
                         const Hash& expected) noexcept {
    std::array<char, kFingerprintChars> normalized{};
    std::size_t count = 0U;
    for (char character : compared) {
        if (character == ' ' || character == '-') continue;
        if (character >= 'A' && character <= 'F') {
            character = static_cast<char>(character - 'A' + 'a');
        }
        const bool hex = (character >= '0' && character <= '9') ||
                         (character >= 'a' && character <= 'f');
        if (!hex || count == normalized.size()) return false;
        normalized[count++] = character;
    }
    if (count != normalized.size()) return false;
    constexpr std::string_view digits = "0123456789abcdef";
    unsigned difference = 0U;
    for (std::size_t index = 0U; index < expected.size(); ++index) {
        difference |= static_cast<unsigned>(normalized[2U * index] ^
                                            digits[expected[index] >> 4U]);
        difference |= static_cast<unsigned>(normalized[2U * index + 1U] ^
                                            digits[expected[index] & 0xFU]);
    }
    return difference == 0U;
}

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

bool valid_contact_name(std::string_view name) noexcept {
    if (name.empty() || name.size() > kMaxNameBytes) return false;
    std::size_t index = 0U;
    while (index < name.size()) {
        const auto lead = static_cast<unsigned char>(name[index]);
        std::size_t length = 0U;
        std::uint32_t code_point = 0U;
        if (lead < 0x80U) {
            length = 1U;
            code_point = lead;
        } else if (lead >= 0xC2U && lead <= 0xDFU) {
            length = 2U;
            code_point = lead & 0x1FU;
        } else if (lead >= 0xE0U && lead <= 0xEFU) {
            length = 3U;
            code_point = lead & 0x0FU;
        } else if (lead >= 0xF0U && lead <= 0xF4U) {
            length = 4U;
            code_point = lead & 0x07U;
        } else {
            return false;
        }
        if (length > name.size() - index) return false;
        for (std::size_t next = 1U; next < length; ++next) {
            const auto byte = static_cast<unsigned char>(name[index + next]);
            if ((byte & 0xC0U) != 0x80U) return false;
            code_point = (code_point << 6U) | (byte & 0x3FU);
        }
        // Overlong forms, surrogates and values above U+10FFFF.
        constexpr std::array<std::uint32_t, 5> minimum{0U, 0U, 0x80U, 0x800U,
                                                       0x10000U};
        if (code_point < minimum[length] || code_point > 0x10FFFFU ||
            (code_point >= 0xD800U && code_point <= 0xDFFFU)) {
            return false;
        }
        if (code_point < 0x20U ||
            (code_point >= 0x7FU && code_point <= 0x9FU)) {
            return false;
        }
        index += length;
    }
    return true;
}

SecretBytes encode_invitation_body(const Invitation& invitation) {
    std::vector<Byte> body;
    const WipeOnExit wiped(body);
    body.reserve(kMaxInvitationBytes);
    Writer out(body);
    out.text(kInvitationDomain);
    out.bytes(invitation.invitation_id);
    out.bytes(invitation.room_id);
    out.bytes(invitation.initiator);
    out.bytes(invitation.responder);
    out.bytes(invitation.authority.server_fingerprint);
    out.u16(static_cast<std::uint16_t>(invitation.authority.service.size()));
    out.text(invitation.authority.service);
    out.bytes(invitation.responder_token);
    out.u64(invitation.issued_at);
    out.u64(invitation.expires_at);
    out.u16(invitation.profile);
    out.bytes(invitation.psk.view());
    return SecretBytes(body);
}

CodecStatus parse_invitation(ByteView input, Invitation& invitation) {
    if (input.size() > kMaxInvitationBytes) return CodecStatus::Malformed;
    Reader in(input);
    if (!in.literal(as_bytes(kInvitationDomain))) {
        return CodecStatus::Malformed;
    }
    std::uint16_t service_size = 0U;
    ByteView service;
    ByteView psk;
    const bool fields =
        in.fixed(invitation.invitation_id) && in.fixed(invitation.room_id) &&
        in.fixed(invitation.initiator) && in.fixed(invitation.responder) &&
        in.fixed(invitation.authority.server_fingerprint) &&
        in.u16(service_size) && in.take(service_size, service) &&
        in.fixed(invitation.responder_token) && in.u64(invitation.issued_at) &&
        in.u64(invitation.expires_at) && in.u16(invitation.profile) &&
        in.take(kPskBytes, psk) && in.fixed(invitation.signature);
    if (!fields || !in.at_end()) return CodecStatus::Malformed;
    const std::string_view name(reinterpret_cast<const char*>(service.data()),
                                service.size());
    if (!valid_service_name(name)) return CodecStatus::Malformed;
    if (invitation.profile != kProfile && invitation.profile != kContentProfile)
        return CodecStatus::Unsupported;
    invitation.authority.service.assign(name);
    invitation.psk.assign(psk.first<kPskBytes>());
    invitation.encoded = SecretBytes(input);
    return CodecStatus::Ok;
}

ByteView invitation_signed_bytes(const Invitation& invitation) noexcept {
    const ByteView all = invitation.encoded.view();
    return all.first(all.size() - kSignatureBytes);
}

std::array<Byte, 2> profile_body(std::uint16_t profile) {
    return {static_cast<Byte>(profile >> 8U), static_cast<Byte>(profile)};
}

Hash context_hash(const CryptoContext& crypto, const Hash& invitation_hash,
                  const Invitation& invitation, ByteView offer_body,
                  ByteView select_body) {
    const std::string& service = invitation.authority.service;
    const std::array<Byte, 2> service_size{
        static_cast<Byte>(service.size() >> 8U),
        static_cast<Byte>(service.size())};
    const std::array<ByteView, 10> parts{
        as_bytes(kContextDomain),
        invitation_hash,
        invitation.room_id,
        invitation.initiator,
        invitation.responder,
        invitation.authority.server_fingerprint,
        service_size,
        as_bytes(service),
        offer_body,
        select_body};
    return crypto.sha256(parts);
}

AuthMessage auth_message(Role role, const Hash& exporter,
                         const Hash& context_hash) {
    AuthMessage result{};
    auto output =
        std::copy(kAuthDomain.begin(), kAuthDomain.end(), result.begin());
    *output++ = static_cast<Byte>(role);
    output = std::copy(exporter.begin(), exporter.end(), output);
    (void)std::copy(context_hash.begin(), context_hash.end(), output);
    return result;
}

Hash accept_body(const CryptoContext& crypto, const Hash& context_hash,
                 const Signature& initiator, const Signature& responder) {
    const std::array<ByteView, 3> parts{context_hash, initiator, responder};
    return crypto.sha256(parts);
}

namespace {

std::vector<Byte> relay_frame(RelayType type, ByteView payload) {
    std::vector<Byte> frame;
    frame.reserve(5U + payload.size());
    Writer out(frame);
    out.u32(static_cast<std::uint32_t>(1U + payload.size()));
    out.u8(static_cast<std::uint8_t>(type));
    out.bytes(payload);
    return frame;
}

}  // namespace

std::vector<Byte> encode_reserve(std::uint32_t lifetime_ms) {
    std::vector<Byte> payload;
    Writer out(payload);
    out.u16(kRelayVersion);
    out.u32(lifetime_ms);
    return relay_frame(RelayType::Reserve, payload);
}

std::vector<Byte> encode_join(const Id& room, Role role, const Id& token) {
    std::vector<Byte> payload;
    const WipeOnExit wiped(payload);
    payload.reserve(2U + 2U * kIdBytes + 1U);
    Writer out(payload);
    out.u16(kRelayVersion);
    out.bytes(room);
    out.u8(static_cast<std::uint8_t>(role));
    out.bytes(token);
    return relay_frame(RelayType::Join, payload);
}

std::vector<Byte> encode_cancel() {
    return relay_frame(RelayType::Cancel, {});
}

ParseProgress parse_relay_reply(ByteView input, RelayReply& reply,
                                std::size_t& consumed) noexcept {
    Reader header(input);
    std::uint32_t body = 0U;
    if (!header.u32(body)) return ParseProgress::Incomplete;
    if (body < 1U || body > kMaxRelayBody) return ParseProgress::Malformed;
    if (input.size() - 4U < body) return ParseProgress::Incomplete;
    Reader in(input.subspan(4U, body));
    std::uint8_t type = 0U;
    (void)in.u8(type);
    bool valid = false;
    switch (static_cast<RelayType>(type)) {
        case RelayType::Reserved:
            valid = in.fixed(reply.room) && in.fixed(reply.initiator_token) &&
                    in.fixed(reply.responder_token) &&
                    in.u32(reply.granted_ms) && in.at_end();
            break;
        case RelayType::Waiting:
        case RelayType::Paired:
            valid = in.at_end();
            break;
        case RelayType::Refused:
            valid = in.u8(reply.refusal) && in.at_end() &&
                    reply.refusal >= 1U && reply.refusal <= 6U;
            break;
        case RelayType::Reserve:
        case RelayType::Join:
        case RelayType::Cancel:
            break;
    }
    if (!valid) return ParseProgress::Malformed;
    reply.type = static_cast<RelayType>(type);
    consumed = 4U + body;
    return ParseProgress::Complete;
}

void encode_frame(SecretBytes& out, FrameType type, std::uint64_t sequence,
                  ByteView payload) {
    std::array<Byte, kFrameHeaderBytes> header{};
    const auto body = static_cast<std::uint32_t>(9U + payload.size());
    for (std::size_t index = 0U; index < 4U; ++index) {
        header[index] = static_cast<Byte>(body >> (24U - 8U * index));
    }
    header[4] = static_cast<Byte>(type);
    for (std::size_t index = 0U; index < 8U; ++index) {
        header[5U + index] = static_cast<Byte>(sequence >> (56U - 8U * index));
    }
    out.append(header);
    out.append(payload);
}

FrameAssembler::FrameAssembler() {
    buffer_.reserve(kMaxFrameBytes);
    buffer_.append(std::array<Byte, kMaxFrameBytes>{});
}

std::span<Byte> FrameAssembler::space() noexcept {
    if (malformed_ || frame().has_value()) return {};
    const std::size_t wanted = expected_ == 0U ? 4U : expected_;
    return {buffer_.data() + buffered_, wanted - buffered_};
}

void FrameAssembler::commit(std::size_t count) noexcept {
    const std::span<Byte> available = space();
    if (count > available.size()) {
        malformed_ = true;
        return;
    }
    buffered_ += count;
    if (expected_ == 0U && buffered_ == 4U) {
        const Byte* length = buffer_.data();
        const std::uint32_t body = (std::uint32_t{length[0]} << 24U) |
                                   (std::uint32_t{length[1]} << 16U) |
                                   (std::uint32_t{length[2]} << 8U) |
                                   std::uint32_t{length[3]};
        // The length is bounded before any body byte is kept.
        if (!body_length_valid(body)) {
            malformed_ = true;
            return;
        }
        expected_ = 4U + body;
    }
    if (expected_ != 0U && buffered_ == expected_) {
        const std::uint8_t type = buffer_.data()[4];
        if (!known_frame_type(type) ||
            !payload_length_valid(static_cast<FrameType>(type),
                                  expected_ - kFrameHeaderBytes)) {
            malformed_ = true;
        }
    }
}

std::size_t FrameAssembler::feed(ByteView input) {
    std::size_t taken = 0U;
    while (taken < input.size()) {
        const std::span<Byte> target = space();
        if (target.empty()) break;
        const std::size_t count = std::min(target.size(), input.size() - taken);
        std::copy_n(input.begin() + static_cast<std::ptrdiff_t>(taken), count,
                    target.begin());
        commit(count);
        taken += count;
    }
    return taken;
}

std::optional<Frame> FrameAssembler::frame() const noexcept {
    if (malformed_ || expected_ == 0U || buffered_ != expected_) {
        return std::nullopt;
    }
    const Byte* bytes = buffer_.data();
    std::uint64_t sequence = 0U;
    for (std::size_t index = 0U; index < 8U; ++index) {
        sequence = (sequence << 8U) | bytes[5U + index];
    }
    return Frame{
        static_cast<FrameType>(bytes[4]), sequence,
        ByteView(bytes + kFrameHeaderBytes, expected_ - kFrameHeaderBytes)};
}

void FrameAssembler::reset() noexcept {
    wipe(buffer_.data(), buffer_.size());
    buffered_ = 0U;
    expected_ = 0U;
    malformed_ = false;
}

void FrameAssembler::take() noexcept {
    // Wipe the consumed plaintext before the next frame reuses the buffer.
    wipe(buffer_.data(), buffered_);
    buffered_ = 0U;
    expected_ = 0U;
}

}  // namespace ito::call
