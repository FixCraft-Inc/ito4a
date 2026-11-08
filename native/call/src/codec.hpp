/* Copyright (C) 2026 FixCraft Inc. AGPL-3.0-or-later. */

// The exact encodings of docs/CALL_SECURITY.md (contact codes, fingerprints,
// invitations, the call context, identity proofs and in-TLS frames) and the
// client side of the relay's control framing in docs/CALL_RELAY.md.
//
// Parsing checks structure only: lengths, versions, fixed values and
// canonical form. Time, trust, signatures and spent state are policy, which
// the store and the call engine apply. No function here performs I/O or
// changes trust.

#pragma once

#include <cstddef>
#include <cstdint>
#include <optional>
#include <string>
#include <string_view>
#include <vector>

#include "bytes.hpp"
#include "crypto.hpp"

namespace ito::call {

enum class CodecStatus { Ok, Malformed, Unsupported };

inline constexpr std::string_view kContactCodePrefix = "ito-contact:";
inline constexpr std::string_view kContactCodeVersion = "1:";
inline constexpr std::size_t kContactCodeChars = 57U;
inline constexpr std::size_t kFingerprintChars = 64U;

[[nodiscard]] std::string format_contact_code(const Key& key);
[[nodiscard]] CodecStatus parse_contact_code(std::string_view code, Key& key);

// SHA-256 of "ito/contact-identity/1", zero, key.
[[nodiscard]] Hash fingerprint(const CryptoContext& crypto, const Key& key);
[[nodiscard]] std::string to_hex(ByteView bytes);
// True when compared is the complete fingerprint, ignoring ASCII case,
// spaces and hyphens. A prefix or any other character is false.
[[nodiscard]] bool fingerprint_matches(std::string_view compared,
                                       const Hash& expected) noexcept;

// YUME's canonical service-name grammar, narrowed to 64 bytes. The relay's
// wire.cpp carries the same rule and vectors.
[[nodiscard]] bool valid_service_name(std::string_view name) noexcept;
// 1..128 bytes of well-formed UTF-8 without C0, DEL or C1 controls.
[[nodiscard]] bool valid_contact_name(std::string_view name) noexcept;

// The relay service an invitation names and a transport is bound to.
struct Authority final {
    Key server_fingerprint{};
    std::string service;

    [[nodiscard]] bool operator==(const Authority&) const = default;
};

// An invitation's fields. psk and the encoded bytes are secrets.
struct Invitation final {
    Id invitation_id{};
    Id room_id{};
    Key initiator{};
    Key responder{};
    Authority authority;
    Id responder_token{};
    std::uint64_t issued_at{0U};
    std::uint64_t expires_at{0U};
    std::uint16_t profile{0U};
    Psk psk;
    Signature signature{};
    // The complete signed encoding: what the initiator hands over and what
    // the invitation hash covers.
    SecretBytes encoded;
};

inline constexpr std::string_view kInvitationDomain{"ito/invitation/1\0", 17U};
inline constexpr std::size_t kInvitationFixedBytes =
    kInvitationDomain.size() + 4U * kKeyBytes + kKeyBytes + 2U + kIdBytes +
    16U + 2U + kPskBytes + kSignatureBytes;
static_assert(kInvitationFixedBytes == 325U);

// Encodes every field before the signature, for signing.
[[nodiscard]] SecretBytes encode_invitation_body(const Invitation& invitation);
// Parses the structure, keeping the encoded bytes. Signature unchecked.
[[nodiscard]] CodecStatus parse_invitation(ByteView input,
                                           Invitation& invitation);
// The signed portion of an encoded invitation: all but its signature.
[[nodiscard]] ByteView invitation_signed_bytes(
    const Invitation& invitation) noexcept;

// The canonical OFFER and SELECT body: the signed u16 profile (1 or 2).
[[nodiscard]] std::array<Byte, 2> profile_body(std::uint16_t profile);

// SHA-256 of "ito/call-context/1", zero, invitation hash, room, both keys,
// relay fingerprint, u16 service length and bytes, OFFER and SELECT bodies.
[[nodiscard]] Hash context_hash(const CryptoContext& crypto,
                                const Hash& invitation_hash,
                                const Invitation& invitation,
                                ByteView offer_body, ByteView select_body);

enum class Role : std::uint8_t { Initiator = 1, Responder = 2 };

inline constexpr std::string_view kAuthDomain{"ito/call-auth/1\0", 16U};
inline constexpr std::size_t kAuthMessageBytes =
    kAuthDomain.size() + 1U + kHashBytes + kHashBytes;
using AuthMessage = std::array<Byte, kAuthMessageBytes>;

// "ito/call-auth/1", zero, role, exporter, context hash.
[[nodiscard]] AuthMessage auth_message(Role role, const Hash& exporter,
                                       const Hash& context_hash);
// SHA-256 of context hash, initiator signature, responder signature.
[[nodiscard]] Hash accept_body(const CryptoContext& crypto,
                               const Hash& context_hash,
                               const Signature& initiator,
                               const Signature& responder);

// Relay control frames (docs/CALL_RELAY.md), client side.
enum class RelayType : std::uint8_t {
    Reserve = 1,
    Reserved = 2,
    Join = 3,
    Waiting = 4,
    Paired = 5,
    Cancel = 6,
    Refused = 7,
};

[[nodiscard]] std::vector<Byte> encode_reserve(std::uint32_t lifetime_ms);
[[nodiscard]] std::vector<Byte> encode_join(const Id& room, Role role,
                                            const Id& token);
[[nodiscard]] std::vector<Byte> encode_cancel();

struct RelayReply final {
    RelayType type{RelayType::Refused};
    // RESERVED
    Id room{};
    Id initiator_token{};
    Id responder_token{};
    std::uint32_t granted_ms{0U};
    // REFUSED
    std::uint8_t refusal{0U};
};

enum class ParseProgress { Incomplete, Complete, Malformed };

// Parses one service-to-client frame from the start of input. On Complete,
// consumed is its size and later bytes belong to the caller.
[[nodiscard]] ParseProgress parse_relay_reply(ByteView input, RelayReply& reply,
                                              std::size_t& consumed) noexcept;

// Application frames inside TLS: u32 body length, u8 type, u64 sequence and
// the payload. The body length counts type and sequence.
enum class FrameType : std::uint8_t {
    Offer = 1,
    Select = 2,
    Auth = 3,
    Accept = 4,
    Data = 5,
    End = 6,
};

// Appends one encoded frame. The caller checks the payload's bound first.
void encode_frame(SecretBytes& out, FrameType type, std::uint64_t sequence,
                  ByteView payload);

struct Frame final {
    FrameType type{FrameType::End};
    std::uint64_t sequence{0U};
    // Borrowed from the assembler until its next feed.
    ByteView payload;
};

// Collects at most one frame at a time from TLS plaintext, with no frame
// boundary assumed at record boundaries. The length is bounded before any
// body byte is kept, and each type's exact payload length is checked.
class FrameAssembler final {
public:
    FrameAssembler();

    // Copies bytes up to the end of the current frame. Returns how many were
    // taken. Malformed latches until reset by destruction.
    [[nodiscard]] std::size_t feed(ByteView input);
    // The same without a copy: space() is where the next wanted bytes go,
    // never more than the current header or body still needs, and commit()
    // records how many were written there.
    [[nodiscard]] std::span<Byte> space() noexcept;
    void commit(std::size_t count) noexcept;
    [[nodiscard]] bool malformed() const noexcept { return malformed_; }
    // A complete frame, valid until the next feed or take.
    [[nodiscard]] std::optional<Frame> frame() const noexcept;
    // Drops the complete frame after the caller processed it.
    void take() noexcept;
    // Bytes of a frame not yet complete, which EOF turns into failure.
    [[nodiscard]] bool partial() const noexcept { return buffered_ != 0U; }
    // Wipes the whole buffer and starts over.
    void reset() noexcept;

private:
    SecretBytes buffer_;
    std::size_t buffered_{0U};
    std::size_t expected_{0U};
    bool malformed_{false};
};

}  // namespace ito::call
