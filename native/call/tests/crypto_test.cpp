/* Copyright (C) 2026 FixCraft Inc. AGPL-3.0-or-later. */

// Known answers for the primitives the core fetches from its private
// library context, as I3's component evaluation checked them.

#include <array>
#include <string_view>

#include <openssl/err.h>

#include "check.hpp"
#include "crypto.hpp"

using namespace ito::call;

int main() {
    const CryptoContext crypto;

    constexpr std::string_view abc = "abc";
    const std::array<ByteView, 1> message{as_bytes(abc)};
    const Hash expected_abc{0xba, 0x78, 0x16, 0xbf, 0x8f, 0x01, 0xcf, 0xea,
                            0x41, 0x41, 0x40, 0xde, 0x5d, 0xae, 0x22, 0x23,
                            0xb0, 0x03, 0x61, 0xa3, 0x96, 0x17, 0x7a, 0x9c,
                            0xb4, 0x10, 0xff, 0x61, 0xf2, 0x00, 0x15, 0xad};
    CHECK(crypto.sha256(message) == expected_abc);
    // Split input hashes the same.
    const std::array<ByteView, 3> parts{as_bytes("a"), as_bytes(""),
                                        as_bytes("bc")};
    CHECK(crypto.sha256(parts) == expected_abc);

    // RFC 8032 section 7.1, TEST 1 (empty message).
    Seed seed;
    seed.assign(std::array<Byte, 32>{
        0x9d, 0x61, 0xb1, 0x9d, 0xef, 0xfd, 0x5a, 0x60, 0xba, 0x84, 0x4a,
        0xf4, 0x92, 0xec, 0x2c, 0xc4, 0x44, 0x49, 0xc5, 0x69, 0x7b, 0x32,
        0x69, 0x19, 0x70, 0x3b, 0xac, 0x03, 0x1c, 0xae, 0x7f, 0x60});
    const Key expected_public{0xd7, 0x5a, 0x98, 0x01, 0x82, 0xb1, 0x0a, 0xb7,
                              0xd5, 0x4b, 0xfe, 0xd3, 0xc9, 0x64, 0x07, 0x3a,
                              0x0e, 0xe1, 0x72, 0xf3, 0xda, 0xa6, 0x23, 0x25,
                              0xaf, 0x02, 0x1a, 0x68, 0xf7, 0x07, 0x51, 0x1a};
    const Signature expected_signature{
        0xe5, 0x56, 0x43, 0x00, 0xc3, 0x60, 0xac, 0x72, 0x90, 0x86, 0xe2,
        0xcc, 0x80, 0x6e, 0x82, 0x8a, 0x84, 0x87, 0x7f, 0x1e, 0xb8, 0xe5,
        0xd9, 0x74, 0xd8, 0x73, 0xe0, 0x65, 0x22, 0x49, 0x01, 0x55, 0x5f,
        0xb8, 0x82, 0x15, 0x90, 0xa3, 0x3b, 0xac, 0xc6, 0x1e, 0x39, 0x70,
        0x1c, 0xf9, 0xb4, 0x6b, 0xd2, 0x5b, 0xf5, 0xf0, 0x59, 0x5b, 0xbe,
        0x24, 0x65, 0x51, 0x41, 0x43, 0x8e, 0x7a, 0x10, 0x0b};
    CHECK(crypto.public_key(seed) == expected_public);
    CHECK(crypto.sign(seed, {}) == expected_signature);
    CHECK(crypto.verify(expected_public, {}, expected_signature) ==
          Verification::Valid);
    Signature changed = expected_signature;
    changed[10] ^= 1U;
    CHECK(crypto.verify(expected_public, {}, changed) == Verification::Invalid);
    CHECK(crypto.verify(expected_public, as_bytes("x"), expected_signature) ==
          Verification::Invalid);
    // A public key that is not a curve point is refused, not an error.
    Key invalid{};
    invalid.fill(0xFF);
    CHECK(crypto.verify(invalid, {}, expected_signature) ==
          Verification::Invalid);

    // A different operation's diagnostics cannot change peer verification.
    ERR_raise(ERR_LIB_EVP, ERR_R_MALLOC_FAILURE);
    CHECK(crypto.verify(expected_public, {}, expected_signature) == Verification::Valid);
    CHECK(ERR_peek_error() == 0);
    ERR_raise(ERR_LIB_EVP, ERR_R_MALLOC_FAILURE);
    CHECK(crypto.verify(expected_public, {}, changed) == Verification::Invalid);
    CHECK(ERR_peek_error() == 0);
    ERR_raise(ERR_LIB_EVP, ERR_R_MALLOC_FAILURE);
    CHECK(crypto.verify(expected_public, as_bytes("x"), expected_signature) == Verification::Invalid);
    CHECK(ERR_peek_error() == 0);
    ERR_raise(ERR_LIB_EVP, ERR_R_MALLOC_FAILURE);
    CHECK(crypto.verify(invalid, {}, expected_signature) == Verification::Invalid);
    CHECK(ERR_peek_error() == 0);

    // Fresh identities differ and sign verifiably.
    Seed first;
    Seed second;
    Key first_public{};
    Key second_public{};
    crypto.generate_identity(first, first_public);
    crypto.generate_identity(second, second_public);
    CHECK(first_public != second_public);
    const Signature signed_first = crypto.sign(first, as_bytes("ito"));
    CHECK(crypto.verify(first_public, as_bytes("ito"), signed_first) ==
          Verification::Valid);
    CHECK(crypto.verify(second_public, as_bytes("ito"), signed_first) ==
          Verification::Invalid);
    return 0;
}
