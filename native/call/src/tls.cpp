/* Copyright (C) 2026 FixCraft Inc. AGPL-3.0-or-later. */

#include "tls.hpp"

#include <openssl/err.h>
#include <openssl/evp.h>
#include <openssl/ssl.h>
#include <openssl/x509.h>

#include <algorithm>
#include <string_view>

namespace ito::call {
namespace {

using Session = OpenSslOwned<SSL_SESSION, SSL_SESSION_free>;

constexpr std::string_view kExporterLabel = "EXPORTER-Channel-Binding";
// TLS 1.3 HelloRetryRequest is a ServerHello carrying this fixed random
// (RFC 8446, section 4.1.3).
constexpr std::array<unsigned char, 32> kHelloRetryRandom{
    0xCF, 0x21, 0xAD, 0x74, 0xE5, 0x9A, 0x61, 0x11, 0xBE, 0x1D, 0x8C,
    0x02, 0x1E, 0x65, 0xB8, 0x91, 0xC2, 0xA2, 0x11, 0x16, 0x7A, 0xBB,
    0x8C, 0x5E, 0x07, 0x9E, 0x09, 0xE2, 0xC8, 0xA8, 0x33, 0x9C};

void require(bool condition) {
    if (!condition) throw CryptoFailure{};
}

[[nodiscard]] PskState* state_of(SSL* ssl) noexcept {
    return static_cast<PskState*>(SSL_get_app_data(ssl));
}

// A fresh, correctly typed TLS 1.3 SHA-384 session for every callback,
// including the second ClientHello after a HelloRetryRequest.
[[nodiscard]] Session external_session(SSL* ssl, const PskState& state) {
    Session result(SSL_SESSION_new());
    if (result == nullptr) return {};
    const SSL_CIPHER* cipher = SSL_CIPHER_find(ssl, state.cipher.data());
    if (cipher == nullptr ||
        SSL_SESSION_set_protocol_version(result.get(), TLS1_3_VERSION) != 1 ||
        SSL_SESSION_set_cipher(result.get(), cipher) != 1 ||
        SSL_SESSION_set1_master_key(result.get(), state.key.data(),
                                    state.key.size()) != 1) {
        return {};
    }
    return result;
}

int use_psk(SSL* ssl, const EVP_MD* digest, const unsigned char** identity,
            std::size_t* identity_size, SSL_SESSION** session) noexcept {
    PskState* state = state_of(ssl);
    *session = nullptr;
    *identity = nullptr;
    *identity_size = 0U;
    if (state == nullptr) return 0;
    try {
        if (!state->supply) return 1;
        // After a HelloRetryRequest OpenSSL passes the negotiated digest. A
        // PSK for another hash is not offered at all.
        if (digest != nullptr &&
            EVP_MD_is_a(digest, state->digest.c_str()) != 1)
            return 1;
        Session candidate = external_session(ssl, *state);
        if (candidate == nullptr) {
            state->failed = true;
            return 0;
        }
        *identity = state->identity.data();
        *identity_size = state->identity.size();
        *session = candidate.release();  // OpenSSL owns this reference.
        state->supplied = true;
        return 1;
    } catch (...) {
        state->failed = true;
        return 0;
    }
}

int find_psk(SSL* ssl, const unsigned char* identity, std::size_t identity_size,
             SSL_SESSION** session) noexcept {
    PskState* state = state_of(ssl);
    *session = nullptr;
    if (state == nullptr) return 0;
    try {
        if (!state->supply ||
            !constant_time_equal(ByteView(identity, identity_size),
                                 state->identity)) {
            // An unknown identity selects no PSK. Without a certificate the
            // handshake then fails, with no fallback.
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
    auto* ssl = static_cast<SSL*>(X509_STORE_CTX_get_ex_data(
        store, SSL_get_ex_data_X509_STORE_CTX_idx()));
    if (ssl != nullptr) {
        PskState* state = state_of(ssl);
        if (state != nullptr) state->certificate_seen = true;
    }
    return 0;
}

// Counts the peer's post-handshake messages that the engine limits or
// refuses, and observes HRRs for the engine's failure classification.
void observe_message(int write, int, int content_type, const void* buffer,
                     std::size_t length, SSL* ssl, void*) noexcept {
    PskState* state = state_of(ssl);
    if (state == nullptr || write != 0 || content_type != SSL3_RT_HANDSHAKE ||
        length < 1U) {
        return;
    }
    const auto* bytes = static_cast<const unsigned char*>(buffer);
    switch (bytes[0]) {
        case SSL3_MT_KEY_UPDATE:
            ++state->peer_key_updates;
            break;
        case SSL3_MT_NEWSESSION_TICKET:
            state->ticket_seen = true;
            break;
        case SSL3_MT_SERVER_HELLO:
            // type(1) length(3) legacy_version(2) random(32)
            if (length >= 38U &&
                std::equal(kHelloRetryRandom.begin(), kHelloRetryRandom.end(),
                           bytes + 6)) {
                state->hello_retry_repeated |= state->hello_retry_seen;
                state->hello_retry_seen = true;
            }
            break;
        default:
            break;
    }
}

#if defined(ITO_CALL_EVIDENCE)
// Test evidence builds only. The production library has no key log.
void key_log_callback(const SSL* ssl, const char* line) noexcept {
    auto* state = static_cast<PskState*>(SSL_get_app_data(ssl));
    try {
        if (state != nullptr) state->key_log.emplace_back(line);
    } catch (...) {
    }
}
#endif

}  // namespace

TlsChannel::TlsChannel(const CryptoContext& crypto, TlsRole role,
                       const Id& identity, const Psk& psk,
                       const TlsOptions& options) {
    psk_.identity = identity;
    psk_.key.assign(psk.view());
    psk_.digest = options.psk_digest;
    psk_.supply = options.supply_psk;
    if (options.psk_digest == "SHA256") psk_.cipher = {0x13, 0x01};
    ERR_clear_error();
    context_ =
        SSL_CTX_new_ex(crypto.library(), kProviderProperty, TLS_method());
    try {
        require(context_ != nullptr);
        const int version =
            options.tls12_only ? TLS1_2_VERSION : TLS1_3_VERSION;
        require(SSL_CTX_set_min_proto_version(context_, version) == 1);
        require(SSL_CTX_set_max_proto_version(context_, version) == 1);
        require(SSL_CTX_set_ciphersuites(context_,
                                         options.ciphersuites.c_str()) == 1);
        require(SSL_CTX_set1_groups_list(context_, options.groups.c_str()) ==
                1);
        // No tickets, cache or early data. NO_TICKET alone would not disable
        // all TLS 1.3 ticket issuance, so the ticket count is zero as well.
        require(SSL_CTX_set_num_tickets(context_, 0U) == 1);
        require(SSL_CTX_set_max_early_data(context_, 0U) == 1);
        require(SSL_CTX_set_recv_max_early_data(context_, 0U) == 1);
        (void)SSL_CTX_set_session_cache_mode(context_, SSL_SESS_CACHE_OFF);
        (void)SSL_CTX_set_max_cert_list(context_, 4096L);
        (void)SSL_CTX_set_options(context_, SSL_OP_NO_TICKET);
        if (options.allow_no_dhe) {
            // Negative fixtures only: the policy check must still refuse it.
            (void)SSL_CTX_set_options(
                context_, SSL_OP_ALLOW_NO_DHE_KEX | SSL_OP_PREFER_NO_DHE_KEX);
        } else {
            (void)SSL_CTX_clear_options(
                context_, SSL_OP_ALLOW_NO_DHE_KEX | SSL_OP_PREFER_NO_DHE_KEX);
        }
        // No certificate is installed and any one a server presents is
        // refused, so a missing PSK cannot fall back to certificate
        // authentication. A server requests no client certificate at all.
        if (role == TlsRole::Client) {
            SSL_CTX_set_verify(context_, SSL_VERIFY_PEER, reject_certificate);
        } else {
            SSL_CTX_set_verify(context_, SSL_VERIFY_NONE, nullptr);
        }
        SSL_CTX_set_psk_use_session_callback(context_, use_psk);
        SSL_CTX_set_psk_find_session_callback(context_, find_psk);
        SSL_CTX_set_msg_callback(context_, observe_message);
#if defined(ITO_CALL_EVIDENCE)
        SSL_CTX_set_keylog_callback(context_, key_log_callback);
#endif
        ssl_ = SSL_new(context_);
        require(ssl_ != nullptr);
        BIO* input = BIO_new(BIO_s_mem());
        BIO* output = BIO_new(BIO_s_mem());
        if (input == nullptr || output == nullptr) {
            BIO_free(input);
            BIO_free(output);
            throw CryptoFailure{};
        }
        // An empty input BIO means "wait for more", never end of stream.
        (void)BIO_set_mem_eof_return(input, -1);
        SSL_set_bio(ssl_, input, output);
        require(SSL_set_app_data(ssl_, &psk_) == 1);
        if (role == TlsRole::Client) {
            SSL_set_connect_state(ssl_);
        } else {
            SSL_set_accept_state(ssl_);
        }
    } catch (...) {
        SSL_free(ssl_);
        SSL_CTX_free(context_);
        ERR_clear_error();
        throw;
    }
}

TlsChannel::~TlsChannel() {
    // The SSL object goes first. Its callbacks borrow psk_, which outlives it.
    SSL_free(ssl_);
    SSL_CTX_free(context_);
}

void TlsChannel::put_inbound(ByteView bytes) {
    if (bytes.empty()) return;
    std::size_t written = 0U;
    require(BIO_write_ex(SSL_get_rbio(ssl_), bytes.data(), bytes.size(),
                         &written) == 1 &&
            written == bytes.size());
}

std::size_t TlsChannel::inbound_pending() const noexcept {
    return BIO_ctrl_pending(SSL_get_rbio(ssl_));
}

std::size_t TlsChannel::output_pending() const noexcept {
    return BIO_ctrl_pending(SSL_get_wbio(ssl_));
}

std::size_t TlsChannel::take_output(std::span<Byte> out) {
    const std::size_t pending = output_pending();
    const std::size_t wanted = std::min(pending, out.size());
    if (wanted == 0U) return 0U;
    std::size_t read = 0U;
    require(BIO_read_ex(SSL_get_wbio(ssl_), out.data(), wanted, &read) == 1 &&
            read == wanted);
    return read;
}

TlsChannel::Step TlsChannel::handshake() {
    ERR_clear_error();
    const int result = SSL_do_handshake(ssl_);
    if (result == 1) return Step::Complete;
    const int error = SSL_get_error(ssl_, result);
    ERR_clear_error();
    if (error == SSL_ERROR_WANT_READ || error == SSL_ERROR_WANT_WRITE)
        return Step::Pending;
    return Step::Failed;
}

bool TlsChannel::policy_satisfied() const noexcept {
    const char* group = SSL_get0_group_name(ssl_);
    const SSL_CIPHER* cipher = SSL_get_current_cipher(ssl_);
    // With no prior session supplied, caching off and tickets disabled,
    // reused==1 together with the PSK callbacks confirms external-PSK
    // selection. The flag alone cannot tell it from ticket resumption.
    return SSL_is_init_finished(ssl_) == 1 &&
           SSL_version(ssl_) == TLS1_3_VERSION && group != nullptr &&
           std::string_view(group) == "X25519MLKEM768" && cipher != nullptr &&
           std::string_view(SSL_CIPHER_get_name(cipher)) ==
               "TLS_AES_256_GCM_SHA384" &&
           SSL_get_certificate(ssl_) == nullptr &&
           SSL_get0_peer_certificate(ssl_) == nullptr &&
           SSL_get_max_early_data(ssl_) == 0U &&
           SSL_session_reused(ssl_) == 1 &&
           (SSL_get_options(ssl_) &
            (SSL_OP_ALLOW_NO_DHE_KEX | SSL_OP_PREFER_NO_DHE_KEX)) == 0U &&
           psk_.supplied && !psk_.failed && !psk_.certificate_seen &&
           !psk_.ticket_seen;
}

Hash TlsChannel::exporter() const {
    Hash result{};
    require(SSL_export_keying_material(
                ssl_, result.data(), result.size(), kExporterLabel.data(),
                kExporterLabel.size(), nullptr, 0U, 1) == 1);
    return result;
}

TlsChannel::ReadResult TlsChannel::read(std::span<Byte> out,
                                        std::size_t& size) {
    size = 0U;
    ERR_clear_error();
    const int result = SSL_read_ex(ssl_, out.data(), out.size(), &size);
    if (result == 1) return ReadResult::Data;
    const int error = SSL_get_error(ssl_, result);
    ERR_clear_error();
    switch (error) {
        case SSL_ERROR_WANT_READ:
        case SSL_ERROR_WANT_WRITE:
            return ReadResult::WantRead;
        case SSL_ERROR_ZERO_RETURN:
            return ReadResult::Closed;
        default:
            return ReadResult::Failed;
    }
}

bool TlsChannel::write(ByteView plaintext) {
    if (plaintext.empty()) return true;
    ERR_clear_error();
    std::size_t written = 0U;
    // A memory BIO accepts every record, so a complete write either happens
    // at once or the connection failed.
    const int result =
        SSL_write_ex(ssl_, plaintext.data(), plaintext.size(), &written);
    ERR_clear_error();
    return result == 1 && written == plaintext.size();
}

bool TlsChannel::update_sending_key() {
    ERR_clear_error();
    if (SSL_key_update(ssl_, SSL_KEY_UPDATE_NOT_REQUESTED) != 1) {
        ERR_clear_error();
        return false;
    }
    // Emits the scheduled KeyUpdate now rather than with the next record.
    const bool sent = SSL_do_handshake(ssl_) == 1;
    ERR_clear_error();
    return sent;
}

void TlsChannel::forget_psk() noexcept {
    psk_.key = Psk{};
}

void TlsChannel::close() {
    ERR_clear_error();
    (void)SSL_shutdown(ssl_);
    ERR_clear_error();
}

}  // namespace ito::call
