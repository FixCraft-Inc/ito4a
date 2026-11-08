/* Copyright (C) 2026 FixCraft Inc. AGPL-3.0-or-later. */

// The end-to-end channel of docs/CALL_SECURITY.md: TLS 1.3 with one external
// per-invitation PSK, PSK-DHE only, X25519MLKEM768 and
// TLS_AES_256_GCM_SHA384, in the core's private OpenSSL library context.
// No certificate, trust store, hostname, ticket, session cache or early data
// takes part. Network bytes pass through memory BIOs, so the channel does no
// I/O itself and the call engine bounds every queue.
//
// OpenSSL owns traffic keys, nonces, record counters and KeyUpdate. The
// channel exposes only the RFC 9266 exporter and plaintext.

#pragma once

#include <openssl/types.h>

#include <array>
#include <cstddef>
#include <cstdint>
#include <span>
#include <string>
#include <vector>

#include "bytes.hpp"
#include "crypto.hpp"

namespace ito::call {

enum class TlsRole { Client, Server };

// Production uses the defaults. Tests change them to prove that the policy
// check refuses what the configuration alone would otherwise permit.
struct TlsOptions final {
    std::string groups{"X25519MLKEM768"};
    std::string ciphersuites{"TLS_AES_256_GCM_SHA384"};
    bool allow_no_dhe{false};
    bool tls12_only{false};
    // Offer the PSK with this digest instead of SHA-384.
    std::string psk_digest{"SHA384"};
    bool supply_psk{true};
};

// The callbacks' view of one connection, owned by the channel and kept alive
// until after the SSL object is freed, including through HelloRetryRequest.
// It holds its own copy of the PSK so no borrow outlives the invitation.
struct PskState final {
    Id identity{};
    Psk key;
    std::array<Byte, 2> cipher{0x13, 0x02};
    std::string digest{"SHA384"};
    bool supply{true};
    bool supplied{false};
    bool failed{false};
    bool certificate_seen{false};
    // Handshake messages observed after the handshake, by the msg callback.
    std::uint32_t peer_key_updates{0U};
    bool ticket_seen{false};
    bool hello_retry_seen{false};
    bool hello_retry_repeated{false};
#if defined(ITO_CALL_EVIDENCE)
    // Test evidence builds only: NSS key log lines of this connection.
    std::vector<std::string> key_log;
#endif
};

class TlsChannel final {
public:
    enum class Step { Pending, Complete, Failed };
    enum class ReadResult { Data, WantRead, Closed, Failed };

    // Throws CryptoFailure when OpenSSL cannot build the objects.
    TlsChannel(const CryptoContext& crypto, TlsRole role, const Id& identity,
               const Psk& psk, const TlsOptions& options = {});
    TlsChannel(const TlsChannel&) = delete;
    TlsChannel& operator=(const TlsChannel&) = delete;
    ~TlsChannel();

    // Network side. The caller bounds inbound bytes before calling.
    void put_inbound(ByteView bytes);
    [[nodiscard]] std::size_t inbound_pending() const noexcept;
    [[nodiscard]] std::size_t output_pending() const noexcept;
    // Moves up to out.size() bytes of TLS records to out.
    [[nodiscard]] std::size_t take_output(std::span<Byte> out);

    // Advances the handshake as far as the buffered bytes allow.
    [[nodiscard]] Step handshake();
    // The exact negotiated profile, checked once the handshake completed.
    [[nodiscard]] bool policy_satisfied() const noexcept;
    // RFC 9266 tls-exporter: "EXPORTER-Channel-Binding", empty context.
    [[nodiscard]] Hash exporter() const;

    // Decrypts up to out.size() plaintext bytes.
    [[nodiscard]] ReadResult read(std::span<Byte> out, std::size_t& size);
    // Encrypts all of plaintext into the output BIO.
    [[nodiscard]] bool write(ByteView plaintext);
    // Schedules a KeyUpdate of this side's sending keys and emits it.
    [[nodiscard]] bool update_sending_key();
    // Queues close_notify. Further writes fail.
    void close();
    // Wipes the channel's PSK copy once the handshake no longer needs it.
    void forget_psk() noexcept;

    [[nodiscard]] const PskState& state() const noexcept { return psk_; }

private:
    PskState psk_;
    SSL_CTX* context_{nullptr};
    SSL* ssl_{nullptr};
};

}  // namespace ito::call
