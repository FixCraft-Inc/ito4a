/* Copyright (C) 2026 FixCraft Inc. AGPL-3.0-or-later. */

// In-memory test fixtures for the call engine: two local identities that
// verified each other, a relay that speaks the version-1 control framing and
// then forwards opaque bytes through an optional tamper hook, a raw TLS
// peer that sends whatever frames a test scripts, and a raw OpenSSL server
// for what the production channel never does. Time is virtual.

#pragma once

#include <openssl/ssl.h>

#include <algorithm>
#include <array>
#include <cstdint>
#include <functional>
#include <memory>
#include <optional>
#include <string>
#include <vector>

#include "check.hpp"
#include "codec.hpp"
#include "engine.hpp"
#include "store.hpp"
#include "tls.hpp"

namespace ito::call::testing {

using Bytes = std::vector<Byte>;

struct Identity final {
    Bytes disk;
    std::unique_ptr<Store> store;
    Key key{};
    bool fail_persist{false};

    Identity(const CryptoContext& crypto, std::uint64_t now) {
        reopen(crypto);
        CHECK(store->create_identity(now) == Status::Ok);
        CHECK(store->identity(key) == Status::Ok);
    }
    void reopen(const CryptoContext& crypto) {
        store.reset();
        Persist persist = [this](ByteView snapshot) {
            if (fail_persist) return PersistResult::NotCommitted;
            disk.assign(snapshot.begin(), snapshot.end());
            return PersistResult::Committed;
        };
        CHECK(Store::open(crypto, disk, std::move(persist), store) ==
              Status::Ok);
    }
};

inline void befriend(const CryptoContext& crypto, Identity& a, Identity& b,
                     std::uint64_t now) {
    CHECK(a.store->add_contact(b.key, "peer", now) == Status::Ok);
    CHECK(a.store->verify_contact(b.key, to_hex(fingerprint(crypto, b.key)),
                                  now) == Status::Ok);
    CHECK(b.store->add_contact(a.key, "peer", now) == Status::Ok);
    CHECK(b.store->verify_contact(a.key, to_hex(fingerprint(crypto, a.key)),
                                  now) == Status::Ok);
}

inline Authority authority(std::string service = "ito-call") {
    Authority result;
    result.server_fingerprint.fill(0xA5);
    result.service = std::move(service);
    return result;
}

inline bool contains(const Bytes& haystack, ByteView needle) {
    if (needle.empty()) return false;
    return std::search(haystack.begin(), haystack.end(), needle.begin(),
                       needle.end()) != haystack.end();
}

// The relay service for two sides. Side 0 is the initiator's stream.
class FakeRelay final {
public:
    std::array<Bytes, 2> pending;  // relay to side
    std::array<bool, 2> closed{false, false};
    std::array<Bytes, 2> tap;  // every byte each side sent
    std::function<void(std::size_t from, Bytes& bytes)> tamper;
    std::optional<std::uint32_t> grant_override;
    std::optional<std::uint8_t> refuse_reserve;
    // RESERVE is read and never answered.
    bool silent_reserve{false};
    bool paired{false};
    Id room{};
    Id tokens[2]{};
    // A side attached as already paired (a raw peer) receives no control.
    std::array<bool, 2> raw{false, false};

    explicit FakeRelay(const CryptoContext& crypto) {
        crypto.random(room);
        crypto.random(tokens[0]);
        crypto.random(tokens[1]);
    }

    // The relay's 64 KiB queue per direction once paired.
    [[nodiscard]] std::size_t space(std::size_t side) const {
        if (!(paired || raw[side])) return SIZE_MAX;
        const std::size_t queued = pending[1U - side].size();
        return queued >= 65536U ? 0U : 65536U - queued;
    }

    void from(std::size_t side, ByteView bytes) {
        tap[side].insert(tap[side].end(), bytes.begin(), bytes.end());
        if (paired || raw[side]) {
            Bytes copy(bytes.begin(), bytes.end());
            if (tamper) tamper(side, copy);
            Bytes& out = pending[1U - side];
            out.insert(out.end(), copy.begin(), copy.end());
            return;
        }
        control_[side].insert(control_[side].end(), bytes.begin(), bytes.end());
        parse(side);
    }

private:
    std::array<Bytes, 2> control_;
    std::array<bool, 2> joined_{false, false};

    void reply(std::size_t side, std::uint8_t type, const Bytes& payload) {
        Bytes frame{0, 0, 0, static_cast<Byte>(1U + payload.size()), type};
        frame.insert(frame.end(), payload.begin(), payload.end());
        pending[side].insert(pending[side].end(), frame.begin(), frame.end());
    }

    void refuse_both(std::uint8_t reason) {
        for (std::size_t side = 0; side < 2; ++side) {
            if (!raw[side]) reply(side, 7, {reason});
            closed[side] = true;
        }
    }

    void parse(std::size_t side) {
        Bytes& in = control_[side];
        while (in.size() >= 5U && !paired) {
            const std::uint32_t body = (std::uint32_t{in[0]} << 24U) |
                                       (std::uint32_t{in[1]} << 16U) |
                                       (std::uint32_t{in[2]} << 8U) | in[3];
            if (in.size() < 4U + body) return;
            const std::uint8_t type = in[4];
            const Bytes payload(
                in.begin() + 5,
                in.begin() + 4 + static_cast<std::ptrdiff_t>(body));
            in.erase(in.begin(),
                     in.begin() + 4 + static_cast<std::ptrdiff_t>(body));
            if (type == 1U) {  // RESERVE
                if (refuse_reserve) return refuse_both(*refuse_reserve);
                if (silent_reserve) continue;
                const std::uint32_t requested =
                    (std::uint32_t{payload[2]} << 24U) |
                    (std::uint32_t{payload[3]} << 16U) |
                    (std::uint32_t{payload[4]} << 8U) | payload[5];
                const std::uint32_t granted =
                    grant_override.value_or(requested);
                Bytes body_out(room.begin(), room.end());
                body_out.insert(body_out.end(), tokens[0].begin(),
                                tokens[0].end());
                body_out.insert(body_out.end(), tokens[1].begin(),
                                tokens[1].end());
                for (int shift = 24; shift >= 0; shift -= 8) {
                    body_out.push_back(static_cast<Byte>(granted >> shift));
                }
                reply(side, 2, body_out);
            } else if (type == 3U) {  // JOIN
                const std::size_t role = payload[34];
                const bool valid =
                    std::equal(room.begin(), room.end(), payload.begin() + 2) &&
                    role >= 1U && role <= 2U &&
                    std::equal(tokens[role - 1U].begin(),
                               tokens[role - 1U].end(), payload.begin() + 35);
                if (!valid) return refuse_both(1);
                reply(side, 4, {});
                joined_[side] = true;
                if ((joined_[0] || raw[0]) && (joined_[1] || raw[1])) pair();
            } else if (type == 6U) {  // CANCEL
                return refuse_both(4);
            } else {
                return refuse_both(6);
            }
        }
    }

    void pair() {
        paired = true;
        for (std::size_t side = 0; side < 2; ++side) {
            if (!raw[side]) reply(side, 5, {});
        }
        // Bytes a side sent after its JOIN are its first opaque bytes.
        for (std::size_t side = 0; side < 2; ++side) {
            if (!control_[side].empty()) {
                Bytes rest;
                rest.swap(control_[side]);
                from(side, rest);
            }
        }
    }
};

// What the wire sees of a scripted peer: the bytes it sends and receives.
class RawSide {
public:
    virtual ~RawSide() = default;
    virtual Bytes output() = 0;
    virtual void received(ByteView bytes) = 0;
};

// A TLS peer that holds the invitation PSK and sends scripted frames.
class RawPeer final : public RawSide {
public:
    RawPeer(const CryptoContext& crypto, TlsRole role, const Id& identity,
            const Psk& psk)
        : tls(crypto, role, identity, psk) {}

    TlsChannel tls;
    std::uint64_t sequence{0};
    bool established{false};
    bool failed{false};
    bool closed_by_peer{false};
    struct Owned final {
        FrameType type;
        std::uint64_t sequence;
        Bytes payload;
    };
    std::vector<Owned> frames;

    Bytes output() override {
        Bytes result;
        std::array<Byte, 65536> buffer{};
        for (;;) {
            const std::size_t taken = tls.take_output(buffer);
            if (taken == 0U) return result;
            result.insert(result.end(), buffer.begin(),
                          buffer.begin() + static_cast<std::ptrdiff_t>(taken));
        }
    }

    void received(ByteView bytes) override {
        tls.put_inbound(bytes);
        if (!established) {
            const TlsChannel::Step step = tls.handshake();
            if (step == TlsChannel::Step::Failed) failed = true;
            if (step != TlsChannel::Step::Complete) return;
            established = true;
        }
        std::array<Byte, 32768> plain{};
        for (;;) {
            std::size_t size = 0;
            const auto result = tls.read(plain, size);
            if (result == TlsChannel::ReadResult::Closed) closed_by_peer = true;
            if (result != TlsChannel::ReadResult::Data) break;
            std::size_t offset = 0;
            while (offset < size) {
                offset += assembler_.feed(
                    ByteView(plain.data() + offset, size - offset));
                if (const auto frame = assembler_.frame()) {
                    frames.push_back(
                        {frame->type, frame->sequence,
                         Bytes(frame->payload.begin(), frame->payload.end())});
                    assembler_.take();
                }
                if (assembler_.malformed()) return;
            }
        }
    }

    void start() {
        if (tls.handshake() == TlsChannel::Step::Complete) established = true;
    }

    void send(FrameType type, ByteView payload) {
        send_at(type, sequence++, payload);
    }
    void send_at(FrameType type, std::uint64_t at, ByteView payload) {
        SecretBytes frame;
        encode_frame(frame, type, at, payload);
        CHECK(tls.write(frame.view()));
    }
    void send_plain(const Bytes& bytes) { CHECK(tls.write(bytes)); }

    [[nodiscard]] const Owned* last(FrameType type) const {
        for (auto it = frames.rbegin(); it != frames.rend(); ++it) {
            if (it->type == type) return &*it;
        }
        return nullptr;
    }

private:
    FrameAssembler assembler_;
};

struct OpenSslServerOptions final {
    // The supported groups. A client whose first key share is not in this
    // list receives a real HelloRetryRequest from OpenSSL.
    std::string groups{"X25519MLKEM768"};
    // Sends a NewSessionTicket right after the handshake.
    bool issue_ticket{false};
    // Allows and prefers PSK without (EC)DHE.
    bool prefer_no_dhe{false};
    // Test-only peer behavior: replay the first real HRR before the final
    // server flight to prove the client rejects a second one.
    bool replay_hello_retry{false};
};

inline Bytes find_hello_retry_record(ByteView records) {
    constexpr std::array<Byte, 32> hrr_random{
        0xCF, 0x21, 0xAD, 0x74, 0xE5, 0x9A, 0x61, 0x11, 0xBE, 0x1D, 0x8C,
        0x02, 0x1E, 0x65, 0xB8, 0x91, 0xC2, 0xA2, 0x11, 0x16, 0x7A, 0xBB,
        0x8C, 0x5E, 0x07, 0x9E, 0x09, 0xE2, 0xC8, 0xA8, 0x33, 0x9C};
    std::size_t offset = 0U;
    while (offset + 5U <= records.size()) {
        const std::size_t length = (std::size_t{records[offset + 3U]} << 8U) |
                                   std::size_t{records[offset + 4U]};
        if (length > records.size() - offset - 5U) return {};
        if (records[offset] == 22U && length >= 38U &&
            records[offset + 5U] == 2U &&
            std::equal(
                hrr_random.begin(), hrr_random.end(),
                records.begin() + static_cast<std::ptrdiff_t>(offset + 11U))) {
            return Bytes(records.begin() + static_cast<std::ptrdiff_t>(offset),
                         records.begin() +
                             static_cast<std::ptrdiff_t>(offset + 5U + length));
        }
        offset += 5U + length;
    }
    return {};
}

// A TLS server on raw OpenSSL objects that accepts the invitation PSK with
// the production profile, then does what the production channel never does,
// as its options select.
class OpenSslServer final : public RawSide {
public:
    OpenSslServer(const CryptoContext& crypto, const Id& identity,
                  const Psk& psk, OpenSslServerOptions options = {})
        : identity_(identity), options_(options) {
        psk_.assign(psk.view());
        context_.reset(SSL_CTX_new_ex(crypto.library(), kProviderProperty,
                                      TLS_server_method()));
        CHECK(context_ != nullptr);
        CHECK(SSL_CTX_set_min_proto_version(context_.get(), TLS1_3_VERSION) ==
              1);
        CHECK(SSL_CTX_set_max_proto_version(context_.get(), TLS1_3_VERSION) ==
              1);
        CHECK(SSL_CTX_set_ciphersuites(context_.get(),
                                       "TLS_AES_256_GCM_SHA384") == 1);
        CHECK(SSL_CTX_set1_groups_list(context_.get(),
                                       options_.groups.c_str()) == 1);
        CHECK(SSL_CTX_set_num_tickets(context_.get(), 0U) == 1);
        if (options_.prefer_no_dhe) {
            (void)SSL_CTX_set_options(
                context_.get(),
                SSL_OP_ALLOW_NO_DHE_KEX | SSL_OP_PREFER_NO_DHE_KEX);
        }
        SSL_CTX_set_psk_find_session_callback(context_.get(), find_session);
        SSL_CTX_set_msg_callback(context_.get(), observe_message);
        ssl_.reset(SSL_new(context_.get()));
        CHECK(ssl_ != nullptr);
        BIO* input = BIO_new(BIO_s_mem());
        BIO* output = BIO_new(BIO_s_mem());
        CHECK(input != nullptr && output != nullptr);
        (void)BIO_set_mem_eof_return(input, -1);
        SSL_set_bio(ssl_.get(), input, output);
        CHECK(SSL_set_app_data(ssl_.get(), this) == 1);
        SSL_set_accept_state(ssl_.get());
    }

    bool established{false};
    bool ticket_sent{false};
    bool hello_retry_sent{false};
    bool hello_retry_replayed{false};
    std::size_t client_hellos{0U};

    // The key exchange group, or null when no (EC)DHE took place.
    [[nodiscard]] const char* group() const {
        return SSL_get0_group_name(ssl_.get());
    }

    Bytes output() override {
        Bytes result;
        BIO* output = SSL_get_wbio(ssl_.get());
        std::array<Byte, 65536> buffer{};
        for (;;) {
            const int read = BIO_read(output, buffer.data(),
                                      static_cast<int>(buffer.size()));
            if (read <= 0) break;
            result.insert(result.end(), buffer.begin(), buffer.begin() + read);
        }
        if (!hello_retry_sent) {
            replay_hello_retry_ = find_hello_retry_record(result);
            hello_retry_sent = !replay_hello_retry_.empty();
            // Deliver HRR alone. Its replay waits for the real second
            // ClientHello and the server's next flight.
            return result;
        }
        if (!result.empty() && options_.replay_hello_retry &&
            !hello_retry_replayed) {
            CHECK(client_hellos == 2U);
            Bytes replay;
            replay.swap(replay_hello_retry_);
            replay.insert(replay.end(), result.begin(), result.end());
            hello_retry_replayed = true;
            return replay;
        }
        return result;
    }

    void received(ByteView bytes) override {
        CHECK(BIO_write(SSL_get_rbio(ssl_.get()), bytes.data(),
                        static_cast<int>(bytes.size())) ==
              static_cast<int>(bytes.size()));
        if (established) return;
        if (SSL_do_handshake(ssl_.get()) != 1) return;
        established = true;
        if (!options_.issue_ticket) return;
        // A ticket the client never asked for, right after the handshake.
        CHECK(SSL_new_session_ticket(ssl_.get()) == 1);
        CHECK(SSL_do_handshake(ssl_.get()) == 1);
        ticket_sent = true;
    }

private:
    static void observe_message(int write, int, int content_type,
                                const void* buffer, std::size_t length,
                                SSL* ssl, void*) noexcept {
        if (write != 0 || content_type != SSL3_RT_HANDSHAKE || length < 1U)
            return;
        auto* self = static_cast<OpenSslServer*>(SSL_get_app_data(ssl));
        const auto* bytes = static_cast<const unsigned char*>(buffer);
        if (self != nullptr && bytes[0] == SSL3_MT_CLIENT_HELLO)
            ++self->client_hellos;
    }

    static int find_session(SSL* ssl, const unsigned char* identity,
                            std::size_t identity_size,
                            SSL_SESSION** session) noexcept {
        auto* self = static_cast<OpenSslServer*>(SSL_get_app_data(ssl));
        *session = nullptr;
        if (identity_size != self->identity_.size() ||
            !std::equal(self->identity_.begin(), self->identity_.end(),
                        identity)) {
            return 1;
        }
        SSL_SESSION* result = SSL_SESSION_new();
        const std::array<unsigned char, 2> cipher{0x13, 0x02};
        if (result == nullptr ||
            SSL_SESSION_set_protocol_version(result, TLS1_3_VERSION) != 1 ||
            SSL_SESSION_set_cipher(result,
                                   SSL_CIPHER_find(ssl, cipher.data())) != 1 ||
            SSL_SESSION_set1_master_key(result, self->psk_.data(),
                                        self->psk_.size()) != 1) {
            SSL_SESSION_free(result);
            return 0;
        }
        *session = result;
        return 1;
    }

    Id identity_{};
    Psk psk_;
    OpenSslServerOptions options_;
    OpenSslOwned<SSL_CTX, SSL_CTX_free> context_;
    OpenSslOwned<SSL, SSL_free> ssl_;
    Bytes replay_hello_retry_;
};

// Pumps engines, raw peers and the relay until nothing moves.
struct Wire final {
    FakeRelay& relay;
    std::array<CallEngine*, 2> engines{nullptr, nullptr};
    std::array<RawSide*, 2> raws{nullptr, nullptr};
    std::array<bool, 2> write_blocked{false, false};
    std::array<bool, 2> eof_delivered{false, false};

    void pump(TimePoint now, int rounds = 400) {
        for (int round = 0; round < rounds; ++round) {
            bool moved = false;
            for (std::size_t side = 0; side < 2; ++side) {
                if (CallEngine* engine = engines[side]) {
                    engine->tick(now);
                    const ByteView out = engine->output(now);
                    const std::size_t count =
                        std::min(out.size(), relay.space(side));
                    if (count != 0U && !write_blocked[side]) {
                        const Bytes copy(
                            out.begin(),
                            out.begin() + static_cast<std::ptrdiff_t>(count));
                        relay.from(side, copy);
                        engine->on_written(copy.size(), now);
                        moved = true;
                    }
                } else if (RawSide* raw = raws[side]) {
                    const Bytes out = raw->output();
                    if (!out.empty()) {
                        relay.from(side, out);
                        moved = true;
                    }
                }
            }
            for (std::size_t side = 0; side < 2; ++side) {
                Bytes& in = relay.pending[side];
                if (CallEngine* engine = engines[side]) {
                    const std::size_t count =
                        std::min(in.size(), engine->receive_capacity());
                    if (count != 0U) {
                        const auto end =
                            in.begin() + static_cast<std::ptrdiff_t>(count);
                        const Bytes chunk(in.begin(), end);
                        in.erase(in.begin(), end);
                        engine->on_received(chunk, now);
                        moved = true;
                    }
                    if (relay.closed[side] && in.empty() &&
                        !eof_delivered[side] && !engine->closed()) {
                        eof_delivered[side] = true;
                        engine->on_transport_end(true, now);
                        moved = true;
                    }
                } else if (RawSide* raw = raws[side]) {
                    if (!in.empty()) {
                        Bytes all;
                        all.swap(in);
                        raw->received(all);
                        moved = true;
                    }
                }
            }
            if (!moved) return;
        }
    }
};

}  // namespace ito::call::testing
