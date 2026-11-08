/* Copyright (C) 2026 FixCraft Inc. AGPL-3.0-or-later. */

// The core's only cryptographic owner outside TLS. One private OpenSSL
// library context with its default provider, explicitly loaded, serves every
// fetch with "provider=default". There is no fallback to OpenSSL's global
// context or another provider. The context outlives every key and operation
// the core creates.

#pragma once

#include <openssl/types.h>

#include <memory>
#include <span>

#include "bytes.hpp"

namespace ito::call {

inline constexpr const char* kProviderProperty = "provider=default";

enum class Verification { Valid, Invalid };

template <class T, auto Free>
struct OpenSslDeleter final {
    void operator()(T* value) const noexcept {
        if (value != nullptr) (void)Free(value);
    }
};

template <class T, auto Free>
using OpenSslOwned = std::unique_ptr<T, OpenSslDeleter<T, Free>>;

class CryptoContext final {
public:
    // Throws CryptoFailure when the context or provider cannot be created.
    CryptoContext();
    CryptoContext(const CryptoContext&) = delete;
    CryptoContext& operator=(const CryptoContext&) = delete;
    ~CryptoContext();

    [[nodiscard]] OSSL_LIB_CTX* library() const noexcept { return library_; }

    // Fills output from the context's DRBG. Throws CryptoFailure.
    void random(std::span<Byte> output) const;
    [[nodiscard]] Hash sha256(std::span<const ByteView> parts) const;

    // A new random Ed25519 seed and its public key.
    void generate_identity(Seed& seed, Key& public_key) const;
    [[nodiscard]] Key public_key(const Seed& seed) const;
    [[nodiscard]] Signature sign(const Seed& seed, ByteView message) const;
    [[nodiscard]] Verification verify(const Key& public_key, ByteView message,
                                      const Signature& signature) const;

private:
    OSSL_LIB_CTX* library_{nullptr};
    OSSL_PROVIDER* provider_{nullptr};
};

// Thrown when OpenSSL or the random source fails. The C interface maps it to
// ITO_STATUS_DEPENDENCY_FAILURE. Peer input never causes it: verification
// failure is a Verification value.
struct CryptoFailure final {};

}  // namespace ito::call
