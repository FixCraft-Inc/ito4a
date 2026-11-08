/* Copyright (C) 2026 FixCraft Inc. AGPL-3.0-or-later. */

// The codec against tests/vectors.txt, which an independent Python writer
// produced from the contract's tables, and against refusal cases for every
// field the contract constrains.

#include <algorithm>
#include <array>
#include <string>
#include <vector>

#include "check.hpp"
#include "codec.hpp"
#include "crypto.hpp"
#include "store.hpp"

using namespace ito::call;

namespace {

using Bytes = std::vector<unsigned char>;
std::map<std::string, Bytes> v;

template <std::size_t N>
std::array<Byte, N> fixed(const std::string& name) {
    const Bytes& bytes = v.at(name);
    CHECK(bytes.size() == N);
    std::array<Byte, N> result{};
    std::copy(bytes.begin(), bytes.end(), result.begin());
    return result;
}

std::string text(const std::string& name) {
    const Bytes& bytes = v.at(name);
    return std::string(bytes.begin(), bytes.end());
}

bool same(ByteView a, const Bytes& b) {
    return std::equal(a.begin(), a.end(), b.begin(), b.end());
}

void test_contact_codes(const CryptoContext& crypto) {
    const Key key = fixed<32>("public_1");
    const std::string code = format_contact_code(key);
    CHECK(code == text("contact_code_1"));
    CHECK(code.size() == kContactCodeChars);
    Key parsed{};
    CHECK(parse_contact_code(code, parsed) == CodecStatus::Ok && parsed == key);
    // Wrong prefix, other versions, lengths, alphabet and padding bits.
    CHECK(parse_contact_code("ito-contact:2:" + code.substr(14), parsed) ==
          CodecStatus::Unsupported);
    CHECK(parse_contact_code("ito-contact:x:" + code.substr(14), parsed) ==
          CodecStatus::Malformed);
    CHECK(parse_contact_code("ito-Contact:1:" + code.substr(14), parsed) ==
          CodecStatus::Malformed);
    CHECK(parse_contact_code(code.substr(0, code.size() - 1), parsed) ==
          CodecStatus::Malformed);
    CHECK(parse_contact_code(code + "A", parsed) == CodecStatus::Malformed);
    CHECK(parse_contact_code(code + "=", parsed) == CodecStatus::Malformed);
    std::string plus = code;
    plus[20] = '+';
    CHECK(parse_contact_code(plus, parsed) == CodecStatus::Malformed);
    // The last character carries two padding bits that must be zero. 'o'
    // encodes 40 = 101000: replacing it with 'p' (41) sets a padding bit.
    std::string noncanonical = code;
    CHECK(noncanonical.back() == 'o');
    noncanonical.back() = 'p';
    CHECK(parse_contact_code(noncanonical, parsed) == CodecStatus::Malformed);
    CHECK(parse_contact_code("", parsed) == CodecStatus::Malformed);

    const Hash expected = fixed<32>("fingerprint_1");
    CHECK(fingerprint(crypto, key) == expected);
    const std::string hex = to_hex(expected);
    CHECK(hex.size() == kFingerprintChars);
    CHECK(fingerprint_matches(hex, expected));
    std::string grouped;
    for (std::size_t index = 0; index < hex.size(); ++index) {
        if (index != 0 && index % 4 == 0)
            grouped += (index % 8 == 0 ? " " : "-");
        grouped += static_cast<char>(std::toupper(hex[index]));
    }
    CHECK(fingerprint_matches(grouped, expected));
    CHECK(!fingerprint_matches(hex.substr(0, 63), expected));
    CHECK(!fingerprint_matches(hex + "0", expected));
    std::string changed = hex;
    changed[63] = changed[63] == '0' ? '1' : '0';
    CHECK(!fingerprint_matches(changed, expected));
    CHECK(!fingerprint_matches(hex.substr(0, 60) + "x" + hex.substr(61),
                               expected));
}

void test_names() {
    CHECK(valid_service_name("ito-call"));
    CHECK(valid_service_name(std::string(64, 'a')));
    CHECK(!valid_service_name(std::string(65, 'a')));
    for (const char* name : {"", "yume", "yume.x", "-a", "a-", "a..b", ".a",
                             "a.", "A", "a b", "a/b"}) {
        CHECK(!valid_service_name(name));
    }
    CHECK(valid_contact_name("Alice"));
    CHECK(valid_contact_name("\xe5\xa4\xa2"));  // U+5922
    CHECK(valid_contact_name(std::string(128, 'n')));
    CHECK(!valid_contact_name(std::string(129, 'n')));
    CHECK(!valid_contact_name(""));
    CHECK(!valid_contact_name("a\nb"));
    CHECK(!valid_contact_name("a\x7f"));
    CHECK(!valid_contact_name("\xc2\x85"));          // C1 control
    CHECK(!valid_contact_name("\xc0\xaf"));          // overlong
    CHECK(!valid_contact_name("\xed\xa0\x80"));      // surrogate
    CHECK(!valid_contact_name("\xf4\x90\x80\x80"));  // above U+10FFFF
    CHECK(!valid_contact_name("\xe5\xa4"));          // truncated
}

Invitation vector_invitation() {
    Invitation invitation;
    invitation.invitation_id = fixed<32>("invitation_id");
    invitation.room_id = fixed<32>("room");
    invitation.initiator = fixed<32>("public_1");
    invitation.responder = fixed<32>("public_2");
    invitation.authority.server_fingerprint = fixed<32>("relay");
    invitation.authority.service = text("service");
    invitation.responder_token = fixed<32>("token");
    const Bytes& issued = v.at("issued");
    const Bytes& expires = v.at("expires");
    for (const unsigned char byte : issued) {
        invitation.issued_at = (invitation.issued_at << 8U) | byte;
    }
    for (const unsigned char byte : expires) {
        invitation.expires_at = (invitation.expires_at << 8U) | byte;
    }
    invitation.profile = 1U;
    invitation.psk.assign(fixed<32>("psk"));
    return invitation;
}

void test_invitation(const CryptoContext& crypto) {
    Seed seed;
    seed.assign(fixed<32>("seed_1"));
    Invitation built = vector_invitation();
    SecretBytes body = encode_invitation_body(built);
    const Signature signature = crypto.sign(seed, body.view());
    body.append(signature);
    CHECK(same(body.view(), v.at("invitation_1")));

    Invitation parsed;
    CHECK(parse_invitation(v.at("invitation_1"), parsed) == CodecStatus::Ok);
    CHECK(parsed.invitation_id == built.invitation_id &&
          parsed.room_id == built.room_id &&
          parsed.initiator == built.initiator &&
          parsed.responder == built.responder &&
          parsed.authority == built.authority &&
          parsed.responder_token == built.responder_token &&
          parsed.issued_at == built.issued_at &&
          parsed.expires_at == built.expires_at && parsed.profile == 1U &&
          std::equal(parsed.psk.view().begin(), parsed.psk.view().end(),
                     built.psk.view().begin()) &&
          parsed.signature == signature);
    CHECK(crypto.verify(parsed.initiator, invitation_signed_bytes(parsed),
                        parsed.signature) == Verification::Valid);
    const std::array<ByteView, 1> whole{parsed.encoded.view()};
    CHECK(crypto.sha256(whole) == fixed<32>("invitation_hash_1"));

    // Every truncation, an extension byte, other domains and profiles.
    const Bytes& encoded = v.at("invitation_1");
    for (std::size_t size = 0; size < encoded.size(); ++size) {
        Invitation partial;
        CHECK(parse_invitation(ByteView(encoded.data(), size), partial) ==
              CodecStatus::Malformed);
    }
    Bytes longer = encoded;
    longer.push_back(0);
    Invitation refused;
    CHECK(parse_invitation(longer, refused) == CodecStatus::Malformed);
    Bytes domain = encoded;
    domain[15] = '2';
    CHECK(parse_invitation(domain, refused) == CodecStatus::Malformed);
    // u16 profile sits before the PSK and signature.
    Bytes profile = encoded;
    profile[encoded.size() - 64 - 32 - 1] = 2;
    Invitation content;
    CHECK(parse_invitation(profile, content) == CodecStatus::Ok);
    CHECK(content.profile == 2U);
    CHECK(crypto.verify(content.initiator, invitation_signed_bytes(content),
                        content.signature) == Verification::Invalid);
    profile[encoded.size() - 64 - 32 - 1] = 3;
    CHECK(parse_invitation(profile, refused) == CodecStatus::Unsupported);
    // A service length that overruns, and a service outside the grammar.
    const std::size_t service_length_at = 17 + 5 * 32;
    Bytes overrun = encoded;
    overrun[service_length_at + 1] = 0xFF;
    CHECK(parse_invitation(overrun, refused) == CodecStatus::Malformed);
    Bytes upper = encoded;
    upper[service_length_at + 2] = 'I';
    CHECK(parse_invitation(upper, refused) == CodecStatus::Malformed);
    CHECK(parse_invitation(Bytes(513, 0), refused) == CodecStatus::Malformed);
}

void test_context_and_proofs(const CryptoContext& crypto) {
    Invitation invitation = vector_invitation();
    const auto offer = profile_body(1);
    const Hash context = context_hash(crypto, fixed<32>("invitation_hash_1"),
                                      invitation, offer, offer);
    CHECK(context == fixed<32>("context_hash_1"));
    const Hash exporter = fixed<32>("exporter");
    const AuthMessage first = auth_message(Role::Initiator, exporter, context);
    const AuthMessage second = auth_message(Role::Responder, exporter, context);
    CHECK(same(first, v.at("auth_message_1")));
    CHECK(same(second, v.at("auth_message_2")));
    Seed seed_1;
    Seed seed_2;
    seed_1.assign(fixed<32>("seed_1"));
    seed_2.assign(fixed<32>("seed_2"));
    const Signature signature_1 = crypto.sign(seed_1, first);
    const Signature signature_2 = crypto.sign(seed_2, second);
    CHECK(signature_1 == fixed<64>("auth_signature_1"));
    CHECK(signature_2 == fixed<64>("auth_signature_2"));
    CHECK(accept_body(crypto, context, signature_1, signature_2) ==
          fixed<32>("accept_1"));
    // A proof does not verify for the other role or another context.
    CHECK(crypto.verify(fixed<32>("public_1"), second, signature_1) ==
          Verification::Invalid);
    Invitation other = vector_invitation();
    other.authority.service = "ito-calm";
    const Hash moved = context_hash(crypto, fixed<32>("invitation_hash_1"),
                                    other, offer, offer);
    CHECK(moved != context);
}

void test_frames() {
    SecretBytes out;
    encode_frame(out, FrameType::Offer, 0, profile_body(1));
    CHECK(same(out.view(), v.at("frame_offer")));
    SecretBytes data;
    const std::array<Byte, 3> abc{'a', 'b', 'c'};
    encode_frame(data, FrameType::Data, 5, abc);
    CHECK(same(data.view(), v.at("frame_data")));
    SecretBytes end;
    const std::array<Byte, 1> declined{2};
    encode_frame(end, FrameType::End, 7, declined);
    CHECK(same(end.view(), v.at("frame_end")));

    // One byte at a time, with no frame boundary at any read boundary.
    Bytes stream = v.at("frame_offer");
    const Bytes& second = v.at("frame_data");
    stream.insert(stream.end(), second.begin(), second.end());
    FrameAssembler assembler;
    std::vector<Frame> seen;
    std::vector<Bytes> payloads;
    for (const unsigned char byte : stream) {
        CHECK(assembler.feed(ByteView(&byte, 1)) == 1U);
        if (const auto frame = assembler.frame()) {
            seen.push_back(*frame);
            payloads.emplace_back(frame->payload.begin(), frame->payload.end());
            assembler.take();
        }
    }
    CHECK(seen.size() == 2 && seen[0].type == FrameType::Offer &&
          seen[1].type == FrameType::Data && seen[1].sequence == 5U &&
          payloads[1] == Bytes({'a', 'b', 'c'}) && !assembler.partial());

    // Lengths are bounded before the body, and each type's payload exactly.
    const auto refused = [](Bytes frame) {
        FrameAssembler parser;
        (void)parser.feed(frame);
        return parser.malformed();
    };
    CHECK(refused({0, 0, 0, 8}));
    CHECK(refused({0, 0, 0x40, 0x0A}));  // 16394 > 16393
    CHECK(refused({0xFF, 0xFF, 0xFF, 0xFF}));
    CHECK(refused({0, 0, 0, 9, 7, 0, 0, 0, 0, 0, 0, 0, 0}));  // unknown type
    CHECK(refused({0, 0, 0, 9, 5, 0, 0, 0, 0, 0, 0, 0, 0}));  // empty DATA
    CHECK(
        refused({0, 0, 0, 10, 1, 0, 0, 0, 0, 0, 0, 0, 0, 1}));  // OFFER 1 byte
    CHECK(refused({0, 0, 0, 11, 6, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1}));  // END 2
    // The largest DATA frame is accepted. Its header alone is not a frame.
    Bytes largest = {0, 0, 0x40, 0x09, 5, 0, 0, 0, 0, 0, 0, 0, 0};
    FrameAssembler big;
    CHECK(big.feed(largest) == largest.size() && big.partial());
    largest.assign(16384, 0x5A);
    CHECK(big.feed(largest) == largest.size() && big.frame().has_value() &&
          big.frame()->payload.size() == 16384U);
}

void test_relay_frames() {
    CHECK(encode_reserve(300'000) == v.at("relay_reserve"));
    CHECK(encode_join(fixed<32>("room"), Role::Responder, fixed<32>("token")) ==
          v.at("relay_join"));
    CHECK(encode_cancel() == v.at("relay_cancel"));
    RelayReply reply;
    std::size_t consumed = 0;
    CHECK(parse_relay_reply(v.at("relay_reserved"), reply, consumed) ==
              ParseProgress::Complete &&
          reply.type == RelayType::Reserved &&
          reply.room == fixed<32>("room") &&
          reply.responder_token == fixed<32>("token") &&
          reply.granted_ms == 299'000U &&
          consumed == v.at("relay_reserved").size());
    CHECK(parse_relay_reply(v.at("relay_paired"), reply, consumed) ==
              ParseProgress::Complete &&
          reply.type == RelayType::Paired);
    CHECK(parse_relay_reply(v.at("relay_refused"), reply, consumed) ==
              ParseProgress::Complete &&
          reply.refusal == 3U);
    // Coalesced: PAIRED followed by TLS bytes leaves them unconsumed.
    Bytes coalesced = v.at("relay_waiting");
    coalesced.push_back(0x16);
    CHECK(parse_relay_reply(coalesced, reply, consumed) ==
              ParseProgress::Complete &&
          consumed == 5U);
    const Bytes& reserved = v.at("relay_reserved");
    for (std::size_t size = 0; size < reserved.size(); ++size) {
        CHECK(parse_relay_reply(ByteView(reserved.data(), size), reply,
                                consumed) == ParseProgress::Incomplete);
    }
    CHECK(parse_relay_reply(Bytes{0, 0, 0, 0}, reply, consumed) ==
          ParseProgress::Malformed);
    CHECK(parse_relay_reply(Bytes{0, 0, 0x10, 1}, reply, consumed) ==
          ParseProgress::Malformed);
    CHECK(parse_relay_reply(Bytes{0, 0, 0, 1, 1}, reply, consumed) ==
          ParseProgress::Malformed);  // RESERVE is a client frame
    CHECK(parse_relay_reply(Bytes{0, 0, 0, 2, 4, 0}, reply, consumed) ==
          ParseProgress::Malformed);  // WAITING with a payload
    CHECK(parse_relay_reply(Bytes{0, 0, 0, 2, 7, 7}, reply, consumed) ==
          ParseProgress::Malformed);  // unknown reason
}

void test_snapshot(const CryptoContext& crypto) {
    StoreState state;
    CHECK(decode_snapshot(crypto, v.at("snapshot_1"), state) == Status::Ok);
    CHECK(state.has_identity && state.public_key == fixed<32>("public_2") &&
          state.contacts.size() == 1 && state.contacts[0].name == "Alice" &&
          state.contacts[0].trust == Trust::Verified &&
          state.spent.size() == 1 && state.spent[0].role == Role::Responder);
    CHECK(same(encode_snapshot(state).view(), v.at("snapshot_1")));
    const Bytes& snapshot = v.at("snapshot_1");
    for (std::size_t size = 0; size < snapshot.size(); ++size) {
        StoreState partial;
        CHECK(decode_snapshot(crypto, ByteView(snapshot.data(), size),
                              partial) == Status::CorruptState);
    }
    // A public key the seed does not derive is corrupt state.
    Bytes mismatch = snapshot;
    mismatch[24 + 1 + 32] ^= 1U;
    StoreState refused;
    CHECK(decode_snapshot(crypto, mismatch, refused) == Status::CorruptState);
}

}  // namespace

int main() {
    v = testing::load_vectors(ITO_CALL_VECTORS);
    const CryptoContext crypto;
    test_contact_codes(crypto);
    test_names();
    test_invitation(crypto);
    test_context_and_proofs(crypto);
    test_frames();
    test_relay_frames();
    test_snapshot(crypto);
    return 0;
}
