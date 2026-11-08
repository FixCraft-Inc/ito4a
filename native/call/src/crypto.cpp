/* Copyright (C) 2026 FixCraft Inc. AGPL-3.0-or-later. */

#include "crypto.hpp"

#include <openssl/err.h>
#include <openssl/evp.h>
#include <openssl/evperr.h>
#include <openssl/provider.h>
#include <openssl/rand.h>

namespace ito::call {
namespace {

using Pkey = OpenSslOwned<EVP_PKEY, EVP_PKEY_free>;
using PkeyContext = OpenSslOwned<EVP_PKEY_CTX, EVP_PKEY_CTX_free>;
using DigestContext = OpenSslOwned<EVP_MD_CTX, EVP_MD_CTX_free>;
using DigestAlgorithm = OpenSslOwned<EVP_MD, EVP_MD_free>;

void require(bool condition) {
    if (!condition) throw CryptoFailure{};
}

// A signing key exists only for the duration of one signature. OpenSSL wipes
// the private key it copied when the key object is freed.
[[nodiscard]] Pkey private_key(OSSL_LIB_CTX* library, const Seed& seed) {
    Pkey key(EVP_PKEY_new_raw_private_key_ex(
        library, "ED25519", kProviderProperty, seed.data(), seed.size()));
    require(key != nullptr);
    return key;
}

[[nodiscard]] Key raw_public_key(const EVP_PKEY* key) {
    Key result{};
    std::size_t size = result.size();
    require(EVP_PKEY_get_raw_public_key(key, result.data(), &size) == 1 &&
            size == result.size());
    return result;
}

}  // namespace

CryptoContext::CryptoContext() {
    library_ = OSSL_LIB_CTX_new();
    if (library_ == nullptr) throw CryptoFailure{};
    provider_ = OSSL_PROVIDER_load(library_, "default");
    if (provider_ == nullptr) {
        OSSL_LIB_CTX_free(library_);
        throw CryptoFailure{};
    }
}

CryptoContext::~CryptoContext() {
    // The provider unloads before its private library context is freed.
    (void)OSSL_PROVIDER_unload(provider_);
    OSSL_LIB_CTX_free(library_);
}

void CryptoContext::random(std::span<Byte> output) const {
    require(RAND_bytes_ex(library_, output.data(), output.size(), 256U) == 1);
}

Hash CryptoContext::sha256(std::span<const ByteView> parts) const {
    DigestAlgorithm algorithm(
        EVP_MD_fetch(library_, "SHA256", kProviderProperty));
    DigestContext context(EVP_MD_CTX_new());
    require(algorithm != nullptr && context != nullptr);
    require(EVP_DigestInit_ex2(context.get(), algorithm.get(), nullptr) == 1);
    for (const ByteView part : parts) {
        require(EVP_DigestUpdate(context.get(), part.data(), part.size()) == 1);
    }
    Hash result{};
    unsigned int size = 0U;
    require(EVP_DigestFinal_ex(context.get(), result.data(), &size) == 1 &&
            size == result.size());
    return result;
}

void CryptoContext::generate_identity(Seed& seed, Key& public_key) const {
    random(std::span<Byte>(seed.data(), seed.size()));
    public_key = this->public_key(seed);
}

Key CryptoContext::public_key(const Seed& seed) const {
    return raw_public_key(private_key(library_, seed).get());
}

Signature CryptoContext::sign(const Seed& seed, ByteView message) const {
    const Pkey key = private_key(library_, seed);
    DigestContext context(EVP_MD_CTX_new());
    require(context != nullptr);
    require(EVP_DigestSignInit_ex(context.get(), nullptr, nullptr, library_,
                                  kProviderProperty, key.get(), nullptr) == 1);
    Signature result{};
    std::size_t size = result.size();
    require(EVP_DigestSign(context.get(), result.data(), &size, message.data(),
                           message.size()) == 1 &&
            size == result.size());
    return result;
}

Verification CryptoContext::verify(const Key& public_key, ByteView message,
                                   const Signature& signature) const {
    Pkey key(
        EVP_PKEY_new_raw_public_key_ex(library_, "ED25519", kProviderProperty,
                                       public_key.data(), public_key.size()));
    // Any 32 bytes import as an Ed25519 public key, so a failure here is the
    // dependency's, such as an allocation, not the peer's.
    require(key != nullptr);
    DigestContext context(EVP_MD_CTX_new());
    require(context != nullptr);
    require(EVP_DigestVerifyInit_ex(context.get(), nullptr, nullptr, library_,
                                    kProviderProperty, key.get(),
                                    nullptr) == 1);
    ERR_clear_error();
    const int verified =
        EVP_DigestVerify(context.get(), signature.data(), signature.size(),
                         message.data(), message.size());
    bool dependency_error = false;
    while (const unsigned long error = ERR_get_error()) {
        // EVP adds this generic diagnostic even for an ordinary bad signature.
        // Other diagnostics include allocation failures that Ed25519 returns
        // as zero, the same result as a signature mismatch.
        dependency_error |= ERR_GET_LIB(error) != ERR_LIB_EVP ||
                            ERR_GET_REASON(error) != EVP_R_PROVIDER_SIGNATURE_FAILURE;
    }
    require(verified >= 0 && (verified == 1 || !dependency_error));
    return verified == 1 ? Verification::Valid : Verification::Invalid;
}

}  // namespace ito::call
