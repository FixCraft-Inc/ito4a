/* Copyright (C) 2026 FixCraft Inc. AGPL-3.0-or-later. */

// Host-only component evaluation. No sockets, application state, invitation
// parser, relay, media endpoint, or Android integration is implemented here.
// The fixed context digest below tests signature binding, not call negotiation.

#include <openssl/crypto.h>
#include <openssl/err.h>
#include <openssl/evp.h>
#include <openssl/provider.h>
#include <openssl/rand.h>
#include <openssl/ssl.h>
#include <openssl/sslerr.h>
#include <openssl/x509.h>

#include <algorithm>
#include <array>
#include <cstddef>
#include <cstdint>
#include <exception>
#include <iostream>
#include <memory>
#include <span>
#include <string_view>
#include <utility>
#include <vector>

namespace {

using Byte = unsigned char;
constexpr std::string_view kGroup = "X25519MLKEM768";
constexpr std::string_view kCipher = "TLS_AES_256_GCM_SHA384";
constexpr std::string_view kProperty = "provider=default";
constexpr std::size_t kWireLimit = 65536U;
constexpr std::size_t kHandshakeSteps = 64U;
constexpr std::string_view kAuthDomain = "ito/call-auth/1";
constexpr std::size_t kAuthBytes = kAuthDomain.size() + 2U + 64U;
using Digest = std::array<Byte, 32>;
using Signature = std::array<Byte, 64>;
using AuthBytes = std::array<Byte, kAuthBytes>;

struct Failure final {};

void require(bool condition) {
    if (!condition) throw Failure{};
}

template <class T, auto Free>
struct Deleter final {
    void operator()(T* value) const noexcept {
        if (value != nullptr) (void)Free(value);
    }
};

template <class T, auto Free>
using Owned = std::unique_ptr<T, Deleter<T, Free>>;

using LibraryContext = Owned<OSSL_LIB_CTX, OSSL_LIB_CTX_free>;
using Provider = Owned<OSSL_PROVIDER, OSSL_PROVIDER_unload>;
using Context = Owned<SSL_CTX, SSL_CTX_free>;
using Connection = Owned<SSL, SSL_free>;
using Session = Owned<SSL_SESSION, SSL_SESSION_free>;
using Bio = Owned<BIO, BIO_free>;
using Key = Owned<EVP_PKEY, EVP_PKEY_free>;
using KeyContext = Owned<EVP_PKEY_CTX, EVP_PKEY_CTX_free>;
using DigestContext = Owned<EVP_MD_CTX, EVP_MD_CTX_free>;
using DigestAlgorithm = Owned<EVP_MD, EVP_MD_free>;
using Certificate = Owned<X509, X509_free>;

template <std::size_t Size>
struct Secret final {
    std::array<Byte, Size> bytes{};
    Secret() = default;
    Secret(const Secret&) = delete;
    Secret& operator=(const Secret&) = delete;
    Secret(Secret&&) = delete;
    Secret& operator=(Secret&&) = delete;
    ~Secret() { OPENSSL_cleanse(bytes.data(), bytes.size()); }
};

class Environment final {
public:
    Environment() : library_(OSSL_LIB_CTX_new()) {
        require(library_ != nullptr);
        provider_.reset(OSSL_PROVIDER_load(library_.get(), "default"));
        require(provider_ != nullptr);
    }

    [[nodiscard]] OSSL_LIB_CTX* library() const noexcept {
        return library_.get();
    }

private:
    // The provider unloads before its private library context is freed.
    LibraryContext library_;
    Provider provider_;
};

struct PskMaterial final {
    Secret<32> key;
    std::array<Byte, 32> identity{};

    explicit PskMaterial(const Environment& environment) {
        require(RAND_bytes_ex(environment.library(), key.bytes.data(),
                              key.bytes.size(), 256U) == 1);
        require(RAND_bytes_ex(environment.library(), identity.data(),
                              identity.size(), 256U) == 1);
    }
};

struct PskCallback final {
    const Secret<32>* key = nullptr;
    const std::array<Byte, 32>* identity = nullptr;
    std::array<Byte, 2> cipher{0x13, 0x02};
    bool enabled = true;
    bool supplied = false;
    bool failed = false;
    bool certificate_seen = false;
};

[[nodiscard]] Session external_session(SSL* ssl, const PskCallback& state) {
    Session result(SSL_SESSION_new());
    if (result == nullptr || state.key == nullptr) return {};
    const SSL_CIPHER* cipher = SSL_CIPHER_find(ssl, state.cipher.data());
    if (cipher == nullptr ||
        SSL_SESSION_set_protocol_version(result.get(), TLS1_3_VERSION) != 1 ||
        SSL_SESSION_set_cipher(result.get(), cipher) != 1 ||
        SSL_SESSION_set1_master_key(result.get(), state.key->bytes.data(),
                                    state.key->bytes.size()) != 1) {
        return {};
    }
    return result;
}

int use_psk(SSL* ssl, const EVP_MD* digest, const Byte** identity,
            std::size_t* identity_size, SSL_SESSION** session) noexcept {
    auto* state = static_cast<PskCallback*>(SSL_get_app_data(ssl));
    *session = nullptr;
    *identity = nullptr;
    *identity_size = 0U;
    if (state == nullptr || state->identity == nullptr) return 0;
    try {
        if (!state->enabled) return 1;
        const char* expected_digest =
            state->cipher[1] == 0x02 ? "SHA384" : "SHA256";
        if (digest != nullptr && EVP_MD_is_a(digest, expected_digest) != 1)
            return 1;
        Session candidate = external_session(ssl, *state);
        if (candidate == nullptr) {
            state->failed = true;
            return 0;
        }
        *identity = state->identity->data();
        *identity_size = state->identity->size();
        *session = candidate.release();  // OpenSSL owns this reference.
        state->supplied = true;
        return 1;
    } catch (...) {
        state->failed = true;
        return 0;
    }
}

int find_psk(SSL* ssl, const Byte* identity, std::size_t identity_size,
             SSL_SESSION** session) noexcept {
    auto* state = static_cast<PskCallback*>(SSL_get_app_data(ssl));
    *session = nullptr;
    if (state == nullptr || state->identity == nullptr) return 0;
    try {
        if (!state->enabled || identity_size != state->identity->size() ||
            CRYPTO_memcmp(identity, state->identity->data(), identity_size) !=
                0) {
            return 1;
        }
        Session candidate = external_session(ssl, *state);
        if (candidate == nullptr) {
            state->failed = true;
            return 0;
        }
        *session = candidate.release();  // OpenSSL owns this reference.
        state->supplied = true;
        return 1;
    } catch (...) {
        state->failed = true;
        return 0;
    }
}

int reject_certificate(int, X509_STORE_CTX* store) noexcept {
    SSL* ssl = static_cast<SSL*>(X509_STORE_CTX_get_ex_data(
        store, SSL_get_ex_data_X509_STORE_CTX_idx()));
    if (ssl != nullptr) {
        auto* state = static_cast<PskCallback*>(SSL_get_app_data(ssl));
        if (state != nullptr) state->certificate_seen = true;
    }
    return 0;
}

enum class Variant {
    Normal,
    WrongPsk,
    MissingPsk,
    WrongIdentity,
    WrongHash,
    Tls12,
    IncompatibleGroup,
    ClassicalGroup,
    PskOnly,
    CertificateFallback
};
enum class Handshake { Complete, Rejected, BoundExceeded };
enum class Step { Complete, Pending, Rejected };

[[nodiscard]] Step handshake_step(SSL* ssl) {
    ERR_clear_error();
    const int result = SSL_do_handshake(ssl);
    if (result == 1) return Step::Complete;
    const int error = SSL_get_error(ssl, result);
    if (error == SSL_ERROR_WANT_READ || error == SSL_ERROR_WANT_WRITE)
        return Step::Pending;
    return Step::Rejected;
}

[[nodiscard]] Context tls_context(const Environment& environment,
                                  const char* groups, bool tls12) {
    Context result(
        SSL_CTX_new_ex(environment.library(), kProperty.data(), TLS_method()));
    require(result != nullptr);
    require(SSL_CTX_set_min_proto_version(
                result.get(), tls12 ? TLS1_2_VERSION : TLS1_3_VERSION) == 1);
    require(SSL_CTX_set_max_proto_version(
                result.get(), tls12 ? TLS1_2_VERSION : TLS1_3_VERSION) == 1);
    require(SSL_CTX_set_ciphersuites(result.get(), kCipher.data()) == 1);
    require(SSL_CTX_set1_groups_list(result.get(), groups) == 1);
    require(SSL_CTX_set_num_tickets(result.get(), 0U) == 1);
    require(SSL_CTX_set_max_early_data(result.get(), 0U) == 1);
    (void)SSL_CTX_set_session_cache_mode(result.get(), SSL_SESS_CACHE_OFF);
    (void)SSL_CTX_set_max_cert_list(result.get(), 4096L);
    // No certificate is installed. A missing PSK cannot fall back to mTLS.
    // NO_TICKET alone would not disable all TLS 1.3 ticket issuance.
    (void)SSL_CTX_set_options(result.get(), SSL_OP_NO_TICKET);
    (void)SSL_CTX_clear_options(
        result.get(), SSL_OP_ALLOW_NO_DHE_KEX | SSL_OP_PREFER_NO_DHE_KEX);
    return result;
}

[[nodiscard]] Key new_identity(const Environment& environment);

void install_negative_certificate(const Environment& environment,
                                  SSL_CTX* context) {
    Key key = new_identity(environment);
    Certificate certificate(
        X509_new_ex(environment.library(), kProperty.data()));
    DigestContext signature(EVP_MD_CTX_new());
    require(certificate != nullptr && signature != nullptr);
    require(X509_set_version(certificate.get(), 2L) == 1);
    require(ASN1_INTEGER_set(X509_get_serialNumber(certificate.get()), 1L) ==
            1);
    require(X509_gmtime_adj(X509_getm_notBefore(certificate.get()), -60L) !=
            nullptr);
    require(X509_gmtime_adj(X509_getm_notAfter(certificate.get()), 3600L) !=
            nullptr);
    require(X509_set_pubkey(certificate.get(), key.get()) == 1);
    constexpr std::array<Byte, 10> common_name{'e', 'v', 'a', 'l', 'u',
                                               'a', 't', 'i', 'o', 'n'};
    X509_NAME* subject = X509_get_subject_name(certificate.get());
    require(subject != nullptr &&
            X509_NAME_add_entry_by_txt(
                subject, "CN", MBSTRING_ASC, common_name.data(),
                static_cast<int>(common_name.size()), -1, 0) == 1);
    require(X509_set_issuer_name(certificate.get(), subject) == 1);
    require(EVP_DigestSignInit_ex(signature.get(), nullptr, nullptr,
                                  environment.library(), kProperty.data(),
                                  key.get(), nullptr) == 1);
    require(X509_sign_ctx(certificate.get(), signature.get()) > 0);
    require(SSL_CTX_use_certificate(context, certificate.get()) == 1);
    require(SSL_CTX_use_PrivateKey(context, key.get()) == 1);
}

[[nodiscard]] Connection tls_connection(SSL_CTX* context, PskCallback& callback,
                                        bool client) {
    Connection result(SSL_new(context));
    require(result != nullptr);
    Bio input(BIO_new(BIO_s_mem()));
    Bio output(BIO_new(BIO_s_mem()));
    require(input != nullptr && output != nullptr);
    (void)BIO_set_mem_eof_return(input.get(), -1);
    SSL_set_bio(result.get(), input.release(), output.release());
    require(SSL_set_app_data(result.get(), &callback) == 1);
    if (client)
        SSL_set_connect_state(result.get());
    else
        SSL_set_accept_state(result.get());
    return result;
}

struct Wire final {
    std::array<Byte, kWireLimit> bytes{};
    std::size_t size = 0U;
};

[[nodiscard]] Wire take_wire(SSL* source) {
    Wire result;
    BIO* output = SSL_get_wbio(source);
    const std::size_t pending = BIO_ctrl_pending(output);
    require(pending <= result.bytes.size());
    if (pending == 0U) return result;
    require(BIO_read_ex(output, result.bytes.data(), pending, &result.size) ==
                1 &&
            result.size == pending);
    require(BIO_ctrl_pending(output) == 0U);
    return result;
}

void feed_wire(SSL* destination, const Wire& wire) {
    require(wire.size <= wire.bytes.size());
    if (wire.size == 0U) return;
    BIO* input = SSL_get_rbio(destination);
    const std::size_t pending = BIO_ctrl_pending(input);
    require(pending <= kWireLimit && wire.size <= kWireLimit - pending);
    std::size_t written = 0U;
    require(BIO_write_ex(input, wire.bytes.data(), wire.size, &written) == 1 &&
            written == wire.size);
}

void transfer(SSL* source, SSL* destination) {
    feed_wire(destination, take_wire(source));
}

class Pair final {
public:
    Pair(const Environment& environment, const PskMaterial& material,
         Variant variant = Variant::Normal) {
        std::copy(material.key.bytes.begin(), material.key.bytes.end(),
                  client_key_.bytes.begin());
        std::copy(material.key.bytes.begin(), material.key.bytes.end(),
                  server_key_.bytes.begin());
        client_identity_ = material.identity;
        server_identity_ = material.identity;
        client_callback_.key = &client_key_;
        server_callback_.key = &server_key_;
        client_callback_.identity = &client_identity_;
        server_callback_.identity = &server_identity_;
        const bool classical = variant == Variant::ClassicalGroup;
        const char* client_groups = classical ? "X25519" : kGroup.data();
        const char* server_groups =
            classical || variant == Variant::IncompatibleGroup ? "X25519"
                                                               : kGroup.data();
        switch (variant) {
            case Variant::Normal:
                break;
            case Variant::WrongPsk:
                server_key_.bytes[0] ^= 1U;
                break;
            case Variant::MissingPsk:
                client_callback_.enabled = false;
                break;
            case Variant::WrongIdentity:
                server_identity_[0] ^= 1U;
                break;
            case Variant::WrongHash:
                client_callback_.cipher[1] = 0x01;
                break;
            case Variant::Tls12:
                break;
            case Variant::IncompatibleGroup:
                break;
            case Variant::ClassicalGroup:
                break;
            case Variant::PskOnly:
                break;
            case Variant::CertificateFallback:
                client_callback_.enabled = false;
                break;
        }
        client_context_ =
            tls_context(environment, client_groups, variant == Variant::Tls12);
        server_context_ = tls_context(environment, server_groups, false);
        SSL_CTX_set_psk_use_session_callback(client_context_.get(), use_psk);
        SSL_CTX_set_psk_find_session_callback(server_context_.get(), find_psk);
        SSL_CTX_set_verify(client_context_.get(), SSL_VERIFY_PEER,
                           reject_certificate);
        if (variant == Variant::CertificateFallback)
            install_negative_certificate(environment, server_context_.get());
        if (variant == Variant::PskOnly) {
            // This negative fixture deliberately permits the forbidden mode.
            // The exact post-handshake policy must still refuse its result.
            (void)SSL_CTX_set_options(
                client_context_.get(),
                SSL_OP_ALLOW_NO_DHE_KEX | SSL_OP_PREFER_NO_DHE_KEX);
            (void)SSL_CTX_set_options(
                server_context_.get(),
                SSL_OP_ALLOW_NO_DHE_KEX | SSL_OP_PREFER_NO_DHE_KEX);
        }
        client_ = tls_connection(client_context_.get(), client_callback_, true);
        server_ =
            tls_connection(server_context_.get(), server_callback_, false);
    }

    Pair(const Pair&) = delete;
    Pair& operator=(const Pair&) = delete;

    [[nodiscard]] SSL* client() const noexcept { return client_.get(); }
    [[nodiscard]] SSL* server() const noexcept { return server_.get(); }
    [[nodiscard]] bool certificate_seen() const noexcept {
        return client_callback_.certificate_seen;
    }

    [[nodiscard]] Handshake handshake() {
        Step client_step = Step::Pending;
        Step server_step = Step::Pending;
        for (std::size_t index = 0U; index < kHandshakeSteps; ++index) {
            if (client_step != Step::Complete)
                client_step = handshake_step(client());
            if (client_step == Step::Rejected) return Handshake::Rejected;
            transfer(client(), server());
            if (server_step != Step::Complete)
                server_step = handshake_step(server());
            if (server_step == Step::Rejected) return Handshake::Rejected;
            transfer(server(), client());
            if (client_step == Step::Complete && server_step == Step::Complete)
                return Handshake::Complete;
        }
        return Handshake::BoundExceeded;
    }

    [[nodiscard]] bool permitted() const noexcept {
        const auto shape = [](SSL* ssl) {
            const char* group = SSL_get0_group_name(ssl);
            const SSL_CIPHER* cipher = SSL_get_current_cipher(ssl);
            return SSL_is_init_finished(ssl) == 1 &&
                   SSL_version(ssl) == TLS1_3_VERSION && group != nullptr &&
                   std::string_view(group) == kGroup && cipher != nullptr &&
                   std::string_view(SSL_CIPHER_get_name(cipher)) == kCipher &&
                   SSL_get_certificate(ssl) == nullptr &&
                   SSL_get0_peer_certificate(ssl) == nullptr &&
                   SSL_get_max_early_data(ssl) == 0U &&
                   SSL_session_reused(ssl) == 1 &&
                   (SSL_get_options(ssl) &
                    (SSL_OP_ALLOW_NO_DHE_KEX | SSL_OP_PREFER_NO_DHE_KEX)) == 0U;
        };
        // Here reused==1 positively confirms PSK selection, together with the
        // exact callbacks and absence of certificates. No prior session is
        // supplied, caching is off, and tickets are disabled. The flag alone
        // cannot distinguish an external PSK from forbidden ticket resumption.
        return shape(client()) && shape(server()) &&
               client_callback_.supplied && server_callback_.supplied &&
               !client_callback_.failed && !server_callback_.failed;
    }

private:
    // SSL objects are destroyed before callbacks, keys, and their contexts.
    Secret<32> client_key_;
    Secret<32> server_key_;
    std::array<Byte, 32> client_identity_{};
    std::array<Byte, 32> server_identity_{};
    PskCallback client_callback_;
    PskCallback server_callback_;
    Context client_context_;
    Context server_context_;
    Connection client_;
    Connection server_;
};

[[nodiscard]] Digest exporter(SSL* ssl) {
    constexpr std::string_view label = "EXPORTER-Channel-Binding";
    Digest result{};
    require(SSL_export_keying_material(ssl, result.data(), result.size(),
                                       label.data(), label.size(), nullptr, 0U,
                                       1) == 1);
    return result;
}

[[nodiscard]] Key new_identity(const Environment& environment) {
    KeyContext context(EVP_PKEY_CTX_new_from_name(environment.library(),
                                                  "ED25519", kProperty.data()));
    require(context != nullptr && EVP_PKEY_keygen_init(context.get()) == 1);
    EVP_PKEY* raw = nullptr;
    const int generated = EVP_PKEY_generate(context.get(), &raw);
    Key result(raw);
    require(generated == 1 && result != nullptr);
    return result;
}

[[nodiscard]] Key public_identity(const Environment& environment,
                                  EVP_PKEY* private_key) {
    Digest bytes{};
    std::size_t size = bytes.size();
    require(EVP_PKEY_get_raw_public_key(private_key, bytes.data(), &size) ==
                1 &&
            size == bytes.size());
    Key result(EVP_PKEY_new_raw_public_key_ex(environment.library(), "ED25519",
                                              kProperty.data(), bytes.data(),
                                              bytes.size()));
    require(result != nullptr);
    return result;
}

[[nodiscard]] Signature sign(const Environment& environment, EVP_PKEY* key,
                             std::span<const Byte> message) {
    DigestContext context(EVP_MD_CTX_new());
    require(context != nullptr);
    require(EVP_DigestSignInit_ex(context.get(), nullptr, nullptr,
                                  environment.library(), kProperty.data(), key,
                                  nullptr) == 1);
    Signature result{};
    std::size_t size = result.size();
    require(EVP_DigestSign(context.get(), result.data(), &size, message.data(),
                           message.size()) == 1 &&
            size == result.size());
    return result;
}

enum class Verification { Valid, Invalid, Failed };

[[nodiscard]] Verification verify(const Environment& environment, EVP_PKEY* key,
                                  std::span<const Byte> message,
                                  const Signature& signature) {
    DigestContext context(EVP_MD_CTX_new());
    if (context == nullptr ||
        EVP_DigestVerifyInit_ex(context.get(), nullptr, nullptr,
                                environment.library(), kProperty.data(), key,
                                nullptr) != 1) {
        return Verification::Failed;
    }
    const int result =
        EVP_DigestVerify(context.get(), signature.data(), signature.size(),
                         message.data(), message.size());
    if (result == 1) return Verification::Valid;
    if (result == 0) return Verification::Invalid;
    return Verification::Failed;
}

[[nodiscard]] AuthBytes auth_bytes(Byte role, const Digest& binding,
                                   const Digest& context_hash) {
    AuthBytes result{};
    auto output =
        std::copy(kAuthDomain.begin(), kAuthDomain.end(), result.begin());
    *output++ = 0U;
    *output++ = role;
    output = std::copy(binding.begin(), binding.end(), output);
    (void)std::copy(context_hash.begin(), context_hash.end(), output);
    return result;
}

constexpr Digest kSha256Abc{0xba, 0x78, 0x16, 0xbf, 0x8f, 0x01, 0xcf, 0xea,
                            0x41, 0x41, 0x40, 0xde, 0x5d, 0xae, 0x22, 0x23,
                            0xb0, 0x03, 0x61, 0xa3, 0x96, 0x17, 0x7a, 0x9c,
                            0xb4, 0x10, 0xff, 0x61, 0xf2, 0x00, 0x15, 0xad};

[[nodiscard]] bool sha256_known_answer(const Environment& environment) {
    DigestAlgorithm algorithm(
        EVP_MD_fetch(environment.library(), "SHA256", kProperty.data()));
    DigestContext context(EVP_MD_CTX_new());
    require(algorithm != nullptr && context != nullptr);
    constexpr std::array<Byte, 3> message{'a', 'b', 'c'};
    Digest result{};
    unsigned int size = 0U;
    require(EVP_DigestInit_ex2(context.get(), algorithm.get(), nullptr) == 1);
    require(EVP_DigestUpdate(context.get(), message.data(), message.size()) ==
            1);
    require(EVP_DigestFinal_ex(context.get(), result.data(), &size) == 1 &&
            size == result.size());
    return result == kSha256Abc;
}

[[nodiscard]] bool ed25519_known_answer(const Environment& environment) {
    // RFC 8032 section 7.1, TEST 1. This published seed is test data.
    Secret<32> seed;
    seed.bytes = {0x9d, 0x61, 0xb1, 0x9d, 0xef, 0xfd, 0x5a, 0x60,
                  0xba, 0x84, 0x4a, 0xf4, 0x92, 0xec, 0x2c, 0xc4,
                  0x44, 0x49, 0xc5, 0x69, 0x7b, 0x32, 0x69, 0x19,
                  0x70, 0x3b, 0xac, 0x03, 0x1c, 0xae, 0x7f, 0x60};
    constexpr Digest expected_public{
        0xd7, 0x5a, 0x98, 0x01, 0x82, 0xb1, 0x0a, 0xb7, 0xd5, 0x4b, 0xfe,
        0xd3, 0xc9, 0x64, 0x07, 0x3a, 0x0e, 0xe1, 0x72, 0xf3, 0xda, 0xa6,
        0x23, 0x25, 0xaf, 0x02, 0x1a, 0x68, 0xf7, 0x07, 0x51, 0x1a};
    constexpr Signature expected_signature{
        0xe5, 0x56, 0x43, 0x00, 0xc3, 0x60, 0xac, 0x72, 0x90, 0x86, 0xe2,
        0xcc, 0x80, 0x6e, 0x82, 0x8a, 0x84, 0x87, 0x7f, 0x1e, 0xb8, 0xe5,
        0xd9, 0x74, 0xd8, 0x73, 0xe0, 0x65, 0x22, 0x49, 0x01, 0x55, 0x5f,
        0xb8, 0x82, 0x15, 0x90, 0xa3, 0x3b, 0xac, 0xc6, 0x1e, 0x39, 0x70,
        0x1c, 0xf9, 0xb4, 0x6b, 0xd2, 0x5b, 0xf5, 0xf0, 0x59, 0x5b, 0xbe,
        0x24, 0x65, 0x51, 0x41, 0x43, 0x8e, 0x7a, 0x10, 0x0b};
    Key key(EVP_PKEY_new_raw_private_key_ex(environment.library(), "ED25519",
                                            kProperty.data(), seed.bytes.data(),
                                            seed.bytes.size()));
    require(key != nullptr);
    Digest actual_public{};
    std::size_t size = actual_public.size();
    require(EVP_PKEY_get_raw_public_key(key.get(), actual_public.data(),
                                        &size) == 1 &&
            size == actual_public.size());
    const Signature signature = sign(environment, key.get(), {});
    return actual_public == expected_public &&
           signature == expected_signature &&
           verify(environment, key.get(), {}, expected_signature) ==
               Verification::Valid;
}

constexpr std::array<Byte, 16> kPayload{'c', 'o', 'm', 'p', 'o', 'n', 'e', 'n',
                                        't', '-', 't', 'e', 's', 't', '-', '1'};

[[nodiscard]] Wire write_payload(SSL* sender) {
    std::size_t written = 0U;
    ERR_clear_error();
    require(SSL_write_ex(sender, kPayload.data(), kPayload.size(), &written) ==
                1 &&
            written == kPayload.size());
    Wire result = take_wire(sender);
    require(result.size > kPayload.size());
    return result;
}

[[nodiscard]] bool read_payload(SSL* receiver) {
    Secret<256> output;
    std::size_t received = 0U;
    ERR_clear_error();
    const int result = SSL_read_ex(receiver, output.bytes.data(),
                                   output.bytes.size(), &received);
    return result == 1 && received == kPayload.size() &&
           std::equal(kPayload.begin(), kPayload.end(), output.bytes.begin());
}

[[nodiscard]] bool rejects_record(SSL* receiver, const Wire& wire) {
    feed_wire(receiver, wire);
    Secret<256> output;
    output.bytes.fill(0xa5);
    std::size_t received = 0U;
    ERR_clear_error();
    const int result = SSL_read_ex(receiver, output.bytes.data(),
                                   output.bytes.size(), &received);
    const int error =
        result == 1 ? SSL_ERROR_NONE : SSL_get_error(receiver, result);
    // The first entry identifies the MAC failure. Later entries can wrap it
    // with the generic record-layer failure reason.
    const unsigned long reason = ERR_peek_error();
    return result != 1 && error == SSL_ERROR_SSL && received == 0U &&
           ERR_GET_LIB(reason) == ERR_LIB_SSL &&
           ERR_GET_REASON(reason) ==
               SSL_R_DECRYPTION_FAILED_OR_BAD_RECORD_MAC &&
           std::all_of(output.bytes.begin(), output.bytes.end(),
                       [](Byte value) { return value == 0xa5; });
}

struct Case final {
    std::string_view name;
    bool passed;
};

class Results final {
public:
    template <class Function>
    void run(std::string_view name, Function&& function) {
        bool passed = false;
        try {
            passed = std::forward<Function>(function)();
        } catch (const Failure&) {
            passed = false;
        } catch (const std::exception&) {
            passed = false;
        } catch (...) {
            passed = false;
        }
        cases_.push_back({name, passed});
    }

    [[nodiscard]] bool emit() const {
        const std::size_t passed = static_cast<std::size_t>(
            std::count_if(cases_.begin(), cases_.end(),
                          [](const Case& test) { return test.passed; }));
        const bool ok = !cases_.empty() && passed == cases_.size();
        std::cout << "{\"component\":\"OpenSSL\",\"version\":\""
                  << OpenSSL_version(OPENSSL_VERSION) << "\",\"group\":\""
                  << kGroup << "\",\"cipher\":\"" << kCipher
                  << "\",\"cases\":[";
        for (std::size_t index = 0U; index < cases_.size(); ++index) {
            if (index != 0U) std::cout << ',';
            std::cout << "{\"name\":\"" << cases_[index].name
                      << "\",\"passed\":"
                      << (cases_[index].passed ? "true" : "false") << '}';
        }
        std::cout << "],\"count\":" << cases_.size() << ",\"passed\":" << passed
                  << ",\"ok\":" << (ok ? "true" : "false") << "}\n";
        return ok && static_cast<bool>(std::cout);
    }

private:
    std::vector<Case> cases_;
};

void evaluate(const Environment& environment, Results& results) {
    results.run("exact_component_version", [] {
        return OPENSSL_VERSION_MAJOR == 3 && OPENSSL_VERSION_MINOR == 5 &&
               OPENSSL_VERSION_PATCH == 9 && OPENSSL_version_major() == 3U &&
               OPENSSL_version_minor() == 5U && OPENSSL_version_patch() == 9U;
    });
    results.run("sha256_abc_known_answer",
                [&] { return sha256_known_answer(environment); });
    results.run("rfc8032_ed25519_test1_known_answer",
                [&] { return ed25519_known_answer(environment); });

    PskMaterial material(environment);
    results.run("exact_psk_dhe_handshake_and_matching_exporters", [&] {
        Pair pair(environment, material);
        return pair.handshake() == Handshake::Complete && pair.permitted() &&
               exporter(pair.client()) == exporter(pair.server());
    });
    results.run("fresh_handshakes_have_distinct_exporters", [&] {
        // Reusing this in-memory fixture PSK tests handshake independence;
        // it does not implement or permit production invitation reuse.
        Pair first(environment, material);
        Pair second(environment, material);
        require(first.handshake() == Handshake::Complete && first.permitted());
        require(second.handshake() == Handshake::Complete &&
                second.permitted());
        return exporter(first.client()) != exporter(second.client());
    });
    results.run("bidirectional_encrypted_payload", [&] {
        Pair pair(environment, material);
        require(pair.handshake() == Handshake::Complete && pair.permitted());
        const Wire outbound = write_payload(pair.client());
        feed_wire(pair.server(), outbound);
        require(read_payload(pair.server()));
        const Wire reply = write_payload(pair.server());
        feed_wire(pair.client(), reply);
        return read_payload(pair.client()) &&
               std::search(outbound.bytes.begin(),
                           outbound.bytes.begin() +
                               static_cast<std::ptrdiff_t>(outbound.size),
                           kPayload.begin(), kPayload.end()) ==
                   outbound.bytes.begin() +
                       static_cast<std::ptrdiff_t>(outbound.size);
    });
    results.run("keyupdate_preserves_payload_and_channel_binding", [&] {
        Pair pair(environment, material);
        require(pair.handshake() == Handshake::Complete && pair.permitted());
        const Digest before = exporter(pair.client());
        require(SSL_key_update(pair.client(), SSL_KEY_UPDATE_NOT_REQUESTED) ==
                1);
        require(handshake_step(pair.client()) == Step::Complete);
        transfer(pair.client(), pair.server());
        feed_wire(pair.server(), write_payload(pair.client()));
        require(read_payload(pair.server()));
        return exporter(pair.client()) == before &&
               exporter(pair.server()) == before;
    });
    constexpr std::array<std::pair<std::string_view, Variant>, 6>
        rejection_cases{{
            {"wrong_psk_rejected", Variant::WrongPsk},
            {"missing_psk_rejected", Variant::MissingPsk},
            {"wrong_psk_identity_rejected", Variant::WrongIdentity},
            {"incompatible_psk_cipher_hash_rejected", Variant::WrongHash},
            {"tls12_rejected", Variant::Tls12},
            {"incompatible_dhe_group_rejected", Variant::IncompatibleGroup},
        }};
    for (const auto& [name, variant] : rejection_cases) {
        results.run(name, [&] {
            Pair pair(environment, material, variant);
            return pair.handshake() == Handshake::Rejected;
        });
    }
    results.run("classical_only_dhe_rejected_by_exact_policy", [&] {
        Pair pair(environment, material, Variant::ClassicalGroup);
        return pair.handshake() == Handshake::Complete && !pair.permitted();
    });
    results.run("psk_only_key_exchange_rejected_by_exact_policy", [&] {
        Pair pair(environment, material, Variant::PskOnly);
        return pair.handshake() == Handshake::Complete &&
               SSL_get_negotiated_group(pair.client()) == 0 &&
               SSL_get_negotiated_group(pair.server()) == 0 &&
               !pair.permitted();
    });
    results.run("certificate_fallback_rejected", [&] {
        Pair pair(environment, material, Variant::CertificateFallback);
        return pair.handshake() == Handshake::Rejected &&
               pair.certificate_seen();
    });
    results.run("corrupted_ciphertext_decrypt_fails_without_plaintext", [&] {
        Pair pair(environment, material);
        require(pair.handshake() == Handshake::Complete && pair.permitted());
        Wire wire = write_payload(pair.client());
        wire.bytes[wire.size - 1U] ^= 1U;
        return rejects_record(pair.server(), wire);
    });
    results.run("replayed_ciphertext_decrypt_fails_without_plaintext", [&] {
        Pair pair(environment, material);
        require(pair.handshake() == Handshake::Complete && pair.permitted());
        const Wire wire = write_payload(pair.client());
        feed_wire(pair.server(), wire);
        require(read_payload(pair.server()));
        return rejects_record(pair.server(), wire);
    });
    results.run("reordered_ciphertext_decrypt_fails_without_plaintext", [&] {
        Pair pair(environment, material);
        require(pair.handshake() == Handshake::Complete && pair.permitted());
        const Wire first = write_payload(pair.client());
        const Wire second = write_payload(pair.client());
        require(first.size == second.size);
        return rejects_record(pair.server(), second);
    });

    Pair pair(environment, material);
    require(pair.handshake() == Handshake::Complete && pair.permitted());
    Key caller = new_identity(environment);
    Key callee = new_identity(environment);
    Key unexpected = new_identity(environment);
    Key caller_public = public_identity(environment, caller.get());
    Key callee_public = public_identity(environment, callee.get());
    const Digest binding = exporter(pair.client());
    // A published digest fixture stands in for the future canonical context.
    // Mutations below prove signed-byte binding, not semantic field parsing.
    const AuthBytes caller_bytes = auth_bytes(1U, binding, kSha256Abc);
    const AuthBytes callee_bytes = auth_bytes(2U, binding, kSha256Abc);
    const Signature caller_signature =
        sign(environment, caller.get(), caller_bytes);
    const Signature callee_signature =
        sign(environment, callee.get(), callee_bytes);
    results.run("bilateral_application_identity_signatures", [&] {
        return verify(environment, caller_public.get(), caller_bytes,
                      caller_signature) == Verification::Valid &&
               verify(environment, callee_public.get(), callee_bytes,
                      callee_signature) == Verification::Valid;
    });
    results.run("wrong_peer_signing_key_rejected", [&] {
        return verify(environment, unexpected.get(), caller_bytes,
                      caller_signature) == Verification::Invalid;
    });
    constexpr std::array<std::pair<std::string_view, std::size_t>, 4>
        signed_mutations{{
            {"changed_authentication_domain_rejected", 0U},
            {"changed_role_rejected", kAuthDomain.size() + 1U},
            {"changed_exporter_rejected", kAuthDomain.size() + 2U},
            {"changed_context_digest_rejected", kAuthDomain.size() + 34U},
        }};
    for (const auto& [name, offset] : signed_mutations) {
        results.run(name, [&] {
            AuthBytes changed = caller_bytes;
            changed[offset] ^= 1U;
            return verify(environment, caller_public.get(), changed,
                          caller_signature) == Verification::Invalid;
        });
    }
    results.run("changed_signature_rejected", [&] {
        Signature changed = caller_signature;
        changed[0] ^= 1U;
        return verify(environment, caller_public.get(), caller_bytes,
                      changed) == Verification::Invalid;
    });
    results.run("signature_from_another_tls_channel_rejected", [&] {
        Pair other(environment, material);
        require(other.handshake() == Handshake::Complete && other.permitted());
        const AuthBytes other_bytes =
            auth_bytes(1U, exporter(other.client()), kSha256Abc);
        return verify(environment, caller_public.get(), other_bytes,
                      caller_signature) == Verification::Invalid;
    });
}

}  // namespace

int main() {
    try {
        Results results;
        try {
            Environment environment;
            evaluate(environment, results);
        } catch (...) {
            // Setup failure is recorded and cannot masquerade as a skipped
            // pass.
            results.run("harness_setup_or_evaluation_failed",
                        [] { return false; });
        }
        return results.emit() ? 0 : 1;
    } catch (...) {
        // This final boundary also contains allocation/output failures. No
        // library diagnostics, peer bytes, or secret material are printed.
        std::cout << "{\"component\":\"OpenSSL\",\"cases\":[{\"name\":"
                     "\"harness_boundary_failed\",\"passed\":false}],\"count\":"
                     "1,\"passed\":0,\"ok\":false}\n";
        return 1;
    }
}
