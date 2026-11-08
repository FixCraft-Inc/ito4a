/* Copyright (C) 2026 FixCraft Inc. AGPL-3.0-or-later. */

// The production TLS channel against I3's component cases: the exact
// external-PSK profile, every refused variant, HelloRetryRequest, a
// certificate-only server, KeyUpdate and close.

#include <openssl/evp.h>
#include <openssl/ssl.h>
#include <openssl/x509.h>

#include <array>
#include <memory>
#include <vector>

#include "check.hpp"
#include "tls.hpp"

using namespace ito::call;

namespace {

enum class Outcome { Complete, Rejected, Stuck };

void pump(TlsChannel& from, TlsChannel& to) {
    std::array<Byte, 65536> buffer{};
    for (;;) {
        const std::size_t taken = from.take_output(buffer);
        if (taken == 0U) return;
        to.put_inbound(ByteView(buffer.data(), taken));
    }
}

Outcome handshake(TlsChannel& client, TlsChannel& server) {
    TlsChannel::Step client_step = TlsChannel::Step::Pending;
    TlsChannel::Step server_step = TlsChannel::Step::Pending;
    for (int round = 0; round < 32; ++round) {
        if (client_step != TlsChannel::Step::Complete) {
            client_step = client.handshake();
        }
        if (client_step == TlsChannel::Step::Failed) return Outcome::Rejected;
        pump(client, server);
        if (server_step != TlsChannel::Step::Complete) {
            server_step = server.handshake();
        }
        if (server_step == TlsChannel::Step::Failed) return Outcome::Rejected;
        pump(server, client);
        if (client_step == TlsChannel::Step::Complete &&
            server_step == TlsChannel::Step::Complete) {
            return Outcome::Complete;
        }
    }
    return Outcome::Stuck;
}

struct Material final {
    Id identity{};
    Psk psk;
    explicit Material(const CryptoContext& crypto) {
        crypto.random(identity);
        crypto.random(std::span<Byte>(psk.data(), psk.size()));
    }
};

struct Pair final {
    std::unique_ptr<TlsChannel> client;
    std::unique_ptr<TlsChannel> server;
    Pair(const CryptoContext& crypto, const Material& material,
         const TlsOptions& client_options = {},
         const TlsOptions& server_options = {},
         const Id* server_identity = nullptr, const Psk* server_psk = nullptr) {
        client = std::make_unique<TlsChannel>(crypto, TlsRole::Client,
                                              material.identity, material.psk,
                                              client_options);
        server = std::make_unique<TlsChannel>(
            crypto, TlsRole::Server,
            server_identity ? *server_identity : material.identity,
            server_psk ? *server_psk : material.psk, server_options);
    }
};

// Both sides finish and pass the exact policy check.
bool permitted(const Pair& pair) {
    return pair.client->policy_satisfied() && pair.server->policy_satisfied();
}

void test_exact_profile(const CryptoContext& crypto) {
    const Material material(crypto);
    Pair pair(crypto, material);
    CHECK(handshake(*pair.client, *pair.server) == Outcome::Complete);
    CHECK(permitted(pair));
    CHECK(pair.client->exporter() == pair.server->exporter());
    CHECK(!pair.client->state().hello_retry_seen);

    // A fresh handshake with the same PSK has a different exporter.
    Pair again(crypto, material);
    CHECK(handshake(*again.client, *again.server) == Outcome::Complete);
    CHECK(again.client->exporter() != pair.client->exporter());

    // Application data, KeyUpdate in both directions, then close_notify.
    const std::array<Byte, 5> hello{'h', 'e', 'l', 'l', 'o'};
    CHECK(pair.client->write(hello));
    CHECK(pair.client->update_sending_key());
    CHECK(pair.client->write(hello));
    pump(*pair.client, *pair.server);
    std::array<Byte, 16> received{};
    std::size_t size = 0;
    CHECK(pair.server->read(received, size) == TlsChannel::ReadResult::Data &&
          size == 5U);
    CHECK(pair.server->read(received, size) == TlsChannel::ReadResult::Data &&
          size == 5U);
    CHECK(pair.server->state().peer_key_updates == 1U);
    CHECK(pair.server->read(received, size) ==
          TlsChannel::ReadResult::WantRead);
    CHECK(pair.server->exporter() == pair.client->exporter());
    pair.client->close();
    pump(*pair.client, *pair.server);
    CHECK(pair.server->read(received, size) == TlsChannel::ReadResult::Closed);
}

void test_refusals(const CryptoContext& crypto) {
    const Material material(crypto);
    {
        Psk other;
        crypto.random(std::span<Byte>(other.data(), other.size()));
        Pair pair(crypto, material, {}, {}, nullptr, &other);
        CHECK(handshake(*pair.client, *pair.server) == Outcome::Rejected);
    }
    {
        TlsOptions missing;
        missing.supply_psk = false;
        Pair pair(crypto, material, missing);
        CHECK(handshake(*pair.client, *pair.server) == Outcome::Rejected);
    }
    {
        Id other = material.identity;
        other[0] ^= 1U;
        Pair pair(crypto, material, {}, {}, &other);
        CHECK(handshake(*pair.client, *pair.server) == Outcome::Rejected);
    }
    {
        TlsOptions wrong_hash;
        wrong_hash.psk_digest = "SHA256";
        Pair pair(crypto, material, wrong_hash);
        CHECK(handshake(*pair.client, *pair.server) == Outcome::Rejected);
    }
    {
        TlsOptions tls12;
        tls12.tls12_only = true;
        Pair pair(crypto, material, tls12);
        CHECK(handshake(*pair.client, *pair.server) == Outcome::Rejected);
    }
    {
        TlsOptions classical;
        classical.groups = "X25519";
        Pair incompatible(crypto, material, {}, classical);
        CHECK(handshake(*incompatible.client, *incompatible.server) ==
              Outcome::Rejected);
        // Both configured for a classical group completes, and the exact
        // policy check refuses it.
        Pair both(crypto, material, classical, classical);
        CHECK(handshake(*both.client, *both.server) == Outcome::Complete);
        CHECK(!both.client->policy_satisfied() &&
              !both.server->policy_satisfied());
    }
    {
        // PSK without (EC)DHE: the forbidden mode must not pass the policy.
        TlsOptions psk_only;
        psk_only.allow_no_dhe = true;
        Pair pair(crypto, material, psk_only, psk_only);
        const Outcome outcome = handshake(*pair.client, *pair.server);
        CHECK(outcome == Outcome::Rejected ||
              (!pair.client->policy_satisfied() &&
               !pair.server->policy_satisfied()));
    }
}

void test_no_dhe_client(const CryptoContext& crypto) {
    // A client that also offers PSK without (EC)DHE. The production server
    // never selects that mode, so the handshake completes with the hybrid
    // group, which the server's policy check observes directly. The engine
    // test covers the other direction against a raw server that prefers it.
    const Material material(crypto);
    TlsOptions no_dhe;
    no_dhe.allow_no_dhe = true;
    Pair pair(crypto, material, no_dhe, {});
    CHECK(handshake(*pair.client, *pair.server) == Outcome::Complete);
    CHECK(pair.server->policy_satisfied());
    CHECK(pair.client->exporter() == pair.server->exporter());
}

void test_hello_retry(const CryptoContext& crypto) {
    // The client's first key share is X25519, which the server does not
    // accept. One HelloRetryRequest moves it to the hybrid group, and the
    // PSK callback supplies a fresh session the second time.
    const Material material(crypto);
    TlsOptions retry;
    retry.groups = "X25519:X25519MLKEM768";
    Pair pair(crypto, material, retry);
    CHECK(handshake(*pair.client, *pair.server) == Outcome::Complete);
    CHECK(pair.client->state().hello_retry_seen);
    CHECK(permitted(pair));
    CHECK(pair.client->exporter() == pair.server->exporter());
}

using Context = OpenSslOwned<SSL_CTX, SSL_CTX_free>;
using Connection = OpenSslOwned<SSL, SSL_free>;
using PrivateKey = OpenSslOwned<EVP_PKEY, EVP_PKEY_free>;
using Certificate = OpenSslOwned<X509, X509_free>;

void test_certificate_server(const CryptoContext& crypto) {
    // A server that ignores the PSK and authenticates with a certificate,
    // as an active relay without the invitation would. The client refuses.
    PrivateKey key(
        EVP_PKEY_Q_keygen(crypto.library(), kProviderProperty, "ED25519"));
    Certificate certificate(X509_new_ex(crypto.library(), kProviderProperty));
    CHECK(key != nullptr && certificate != nullptr);
    CHECK(X509_set_version(certificate.get(), 2L) == 1);
    CHECK(ASN1_INTEGER_set(X509_get_serialNumber(certificate.get()), 1L) == 1);
    CHECK(X509_gmtime_adj(X509_getm_notBefore(certificate.get()), -60L));
    CHECK(X509_gmtime_adj(X509_getm_notAfter(certificate.get()), 3600L));
    CHECK(X509_set_pubkey(certificate.get(), key.get()) == 1);
    X509_NAME* subject = X509_get_subject_name(certificate.get());
    const unsigned char name[] = "relay";
    CHECK(X509_NAME_add_entry_by_txt(subject, "CN", MBSTRING_ASC, name, 5, -1,
                                     0) == 1);
    CHECK(X509_set_issuer_name(certificate.get(), subject) == 1);
    CHECK(X509_sign(certificate.get(), key.get(), nullptr) > 0);

    Context context(SSL_CTX_new_ex(crypto.library(), kProviderProperty,
                                   TLS_server_method()));
    CHECK(context != nullptr);
    CHECK(SSL_CTX_set_min_proto_version(context.get(), TLS1_3_VERSION) == 1);
    CHECK(SSL_CTX_set1_groups_list(context.get(), "X25519MLKEM768") == 1);
    CHECK(SSL_CTX_use_certificate(context.get(), certificate.get()) == 1);
    CHECK(SSL_CTX_use_PrivateKey(context.get(), key.get()) == 1);
    Connection server(SSL_new(context.get()));
    BIO* input = BIO_new(BIO_s_mem());
    BIO* output = BIO_new(BIO_s_mem());
    BIO_set_mem_eof_return(input, -1);
    SSL_set_bio(server.get(), input, output);
    SSL_set_accept_state(server.get());

    const Material material(crypto);
    TlsChannel client(crypto, TlsRole::Client, material.identity, material.psk);
    bool client_failed = false;
    for (int round = 0; round < 16 && !client_failed; ++round) {
        client_failed = client.handshake() == TlsChannel::Step::Failed;
        std::array<Byte, 65536> buffer{};
        const std::size_t taken = client.take_output(buffer);
        int written = 0;
        if (taken != 0U) {
            written = BIO_write(input, buffer.data(), static_cast<int>(taken));
        }
        (void)written;
        (void)SSL_do_handshake(server.get());
        const int pending = static_cast<int>(BIO_ctrl_pending(output));
        if (pending > 0) {
            std::vector<Byte> reply(static_cast<std::size_t>(pending));
            CHECK(BIO_read(output, reply.data(), pending) == pending);
            client.put_inbound(reply);
        }
    }
    CHECK(client_failed);
    CHECK(!client.policy_satisfied());
}

}  // namespace

int main() {
    const CryptoContext crypto;
    // First, so a control that lets production negotiate PSK without DHE
    // fails here rather than in a later test.
    test_no_dhe_client(crypto);
    test_exact_profile(crypto);
    test_refusals(crypto);
    test_hello_retry(crypto);
    test_certificate_server(crypto);
    return 0;
}
