/* Copyright (C) 2026 FixCraft Inc. AGPL-3.0-or-later. */

// The call engine end to end in memory: two real engines, and scripted raw
// TLS peers that hold the invitation PSK, through a relay that can drop,
// alter and record bytes. Covers the endpoint gates of
// docs/CALL_SECURITY.md that do not need a real network.

#include <chrono>
#include <cstdio>
#include <functional>
#include <string_view>

#include "engine_harness.hpp"
#include "invitation.hpp"

using namespace ito::call;
using namespace ito::call::testing;
using std::chrono::seconds;

namespace {

constexpr std::uint64_t kUnix0 = 1'800'000'000U;
const TimePoint kT0 = TimePoint{} + std::chrono::hours(100);

std::uint64_t unix_at(TimePoint now) {
    return kUnix0 + static_cast<std::uint64_t>(
                        std::chrono::duration_cast<seconds>(now - kT0).count());
}

struct Call final {
    const CryptoContext& crypto;
    Identity alice;
    Identity bob;
    FakeRelay relay;
    Wire wire{relay};
    std::unique_ptr<CallEngine> a;
    std::unique_ptr<CallEngine> b;
    Bytes invitation;
    Invitation parsed;
    Hash invitation_hash{};
    TimePoint now = kT0;
    EngineOptions responder_options;

    explicit Call(const CryptoContext& context, std::uint32_t lifetime = 120,
                  EngineOptions options = {})
        : crypto(context),
          alice(context, kUnix0),
          bob(context, kUnix0),
          relay(context) {
        befriend(crypto, alice, bob, kUnix0);
        CHECK(CallEngine::create_initiator(*alice.store, bob.key, authority(),
                                           lifetime, kUnix0, kT0, a,
                                           options) == Status::Ok);
        wire.engines[0] = a.get();
    }

    void advance(seconds step) {
        now += step;
        wire.pump(now);
    }

    // The initiator reserves, signs, spends and joins. Its invitation is
    // then read the way a UI would show it.
    void invite() {
        a->start(now);
        wire.pump(now);
        CHECK(a->snapshot().state == CallState::Joined);
        CHECK(a->snapshot().invitation_ready);
        std::array<Byte, kMaxInvitationBytes> buffer{};
        std::size_t size = 0;
        CHECK(a->copy_invitation(buffer, size) == Status::Ok);
        invitation.assign(buffer.begin(),
                          buffer.begin() + static_cast<std::ptrdiff_t>(size));
        CHECK(parse_invitation(invitation, parsed) == CodecStatus::Ok);
        const std::array<ByteView, 1> whole{invitation};
        invitation_hash = crypto.sha256(whole);
    }

    Status open(Identity& who, std::unique_ptr<OpenedInvitation>& out,
                const Authority& expected = authority()) {
        return open_invitation(*who.store, invitation, expected, unix_at(now),
                               now, out);
    }

    void join() {
        std::unique_ptr<OpenedInvitation> opened;
        CHECK(open(bob, opened) == Status::Ok);
        CHECK(CallEngine::create_responder(*bob.store, opened, authority(),
                                           unix_at(now), now, b,
                                           responder_options) == Status::Ok);
        wire.engines[1] = b.get();
        b->start(now);
        wire.pump(now);
    }

    void establish() {
        invite();
        join();
        CHECK(b->snapshot().state == CallState::AwaitingAcceptance);
        CHECK(b->snapshot().consent_pending &&
              b->snapshot().peer_authenticated);
        CHECK(a->snapshot().state == CallState::Authenticating);
        CHECK(b->decide(true, now) == Status::Ok);
        wire.pump(now);
        CHECK(a->snapshot().state == CallState::MediaAuthorized);
        CHECK(b->snapshot().state == CallState::MediaAuthorized);
    }

    Hash context() const {
        const auto profile = profile_body(1);
        return context_hash(crypto, invitation_hash, parsed, profile, profile);
    }
};

Bytes pattern(std::size_t size, Byte seed) {
    Bytes bytes(size);
    for (std::size_t index = 0; index < size; ++index) {
        bytes[index] = static_cast<Byte>(seed + index * 31U);
    }
    return bytes;
}

Bytes drain(CallEngine& engine) {
    Bytes all;
    std::array<Byte, kMaxDataBytes> buffer{};
    std::size_t size = 0;
    while (engine.receive(buffer, size) == Status::Ok) {
        all.insert(all.end(), buffer.begin(),
                   buffer.begin() + static_cast<std::ptrdiff_t>(size));
    }
    return all;
}


Bytes fragmented_handshake(std::uint8_t type, std::size_t body_size) {
    CHECK(body_size <= 0xFFFFFFU);
    Bytes result;
    const std::size_t message_size = 4U + body_size;
    result.reserve(message_size * 6U);
    const std::array<Byte, 4> header{
        type,
        static_cast<Byte>((body_size >> 16U) & 0xFFU),
        static_cast<Byte>((body_size >> 8U) & 0xFFU),
        static_cast<Byte>(body_size & 0xFFU),
    };
    for (std::size_t offset = 0; offset < message_size; ++offset) {
        result.insert(result.end(), {0x16U, 0x03U, 0x03U, 0x00U, 0x01U,
                                     offset < header.size() ? header[offset]
                                                            : Byte{0U}});
    }
    return result;
}

void feed_handshake_budget(CallEngine& engine, const Bytes& records,
                           TimePoint now) {
    const std::size_t threshold = kMaxHandshakeInbound;
    CHECK(records.size() > threshold);
    std::size_t offset = 0U;
    while (offset < threshold) {
        const std::size_t capacity = engine.receive_capacity();
        CHECK(capacity != 0U);
        const std::size_t count = std::min(capacity, threshold - offset);
        engine.on_received(ByteView(records).subspan(offset, count), now);
        offset += count;
        CHECK(!engine.closed());
        CHECK(engine.snapshot().state == CallState::Handshaking);
    }
    // The legal fragmented handshake is still incomplete, so the exact
    // threshold remains pending while the next admitted byte is the only
    // remaining capacity.
    CHECK(engine.receive_capacity() == 1U);
    CHECK(engine.snapshot().result == Status::Ok);
    engine.on_received(ByteView(records).subspan(threshold, 1U), now);
    CHECK(engine.snapshot().result == Status::ProtocolFailure);
    CHECK(engine.snapshot().state == CallState::Closing || engine.closed());
}

void test_handshake_byte_budget(const CryptoContext& crypto) {
    // An initiator receives a fragmented, incomplete ServerHello. Each
    // one-byte TLS record is legal; OpenSSL remains pending until the complete
    // message, while the engine counts every admitted network byte.
    {
        Call call(crypto);
        call.invite();
        call.relay.raw[1] = true;
        call.relay.paired = true;
        call.relay.pending[0] = {0U, 0U, 0U, 1U, 5U};
        call.wire.pump(call.now);
        CHECK(call.a->snapshot().state == CallState::Handshaking);
        feed_handshake_budget(*call.a,
                              fragmented_handshake(2U, 65'000U), call.now);
    }
    // Repeat for the responder owner with a fragmented, incomplete
    // ClientHello. The two roles share no counter or TLS object.
    {
        Call call(crypto);
        call.invite();
        std::unique_ptr<OpenedInvitation> opened;
        CHECK(call.open(call.bob, opened) == Status::Ok);
        CHECK(CallEngine::create_responder(*call.bob.store, opened, authority(),
                                           unix_at(call.now), call.now,
                                           call.b) == Status::Ok);
        call.wire.engines[0] = nullptr;
        call.wire.engines[1] = call.b.get();
        call.relay.raw[0] = true;
        call.relay.paired = true;
        call.relay.pending[1] = {0U, 0U, 0U, 1U, 4U, 0U,
                                     0U, 0U, 1U, 5U};
        call.b->start(call.now);
        call.wire.pump(call.now);
        CHECK(call.b->snapshot().state == CallState::Handshaking);
        feed_handshake_budget(*call.b,
                              fragmented_handshake(1U, 131'000U), call.now);
    }
}

void test_full_call(const CryptoContext& crypto) {
    Call call(crypto);
    call.establish();
    const Bytes to_bob = pattern(40'000, 1);
    const Bytes to_alice = pattern(5'000, 9);
    for (std::size_t offset = 0; offset < to_bob.size(); offset += 10'000) {
        CHECK(call.a->send(ByteView(to_bob).subspan(offset, 10'000),
                           call.now) == Status::Ok);
        call.wire.pump(call.now);
    }
    CHECK(call.b->send(to_alice, call.now) == Status::Ok);
    call.wire.pump(call.now);
    CHECK(drain(*call.b) == to_bob);
    CHECK(drain(*call.a) == to_alice);
    CHECK(call.a->hangup(call.now) == Status::Ok);
    call.wire.pump(call.now);
    const CallSnapshot a = call.a->snapshot();
    const CallSnapshot b = call.b->snapshot();
    CHECK(a.state == CallState::Closed && a.result == Status::Canceled &&
          a.local_end == 1U);
    CHECK(b.state == CallState::Closed && b.result == Status::PeerEnded &&
          b.remote_end == 1U && b.remote_end_attributed);
    CHECK(a.data_sent == to_bob.size() && b.data_received == to_bob.size());
    // Both spent the invitation durably, and the attempt slot is free again.
    CHECK(call.bob.store->check_unspent(call.parsed.invitation_id,
                                        call.parsed.expires_at) ==
          Status::AlreadySpent);
    CHECK(call.alice.store->check_unspent(call.parsed.invitation_id,
                                          call.parsed.expires_at) ==
          Status::AlreadySpent);
    std::unique_ptr<CallEngine> next;
    CHECK(CallEngine::create_initiator(*call.alice.store, call.bob.key,
                                       authority(), 60, kUnix0, call.now,
                                       next) == Status::Ok);
    // The relay saw no PSK, invitation or plaintext in either direction.
    for (const Bytes& seen : call.relay.tap) {
        CHECK(!contains(seen, call.parsed.psk.view()));
        CHECK(!contains(seen, call.invitation));
        CHECK(!contains(seen, ByteView(to_bob).first(64)));
        CHECK(!contains(seen, ByteView(to_alice).first(64)));
        CHECK(!contains(seen, call.parsed.signature));
    }
    // Received data stays readable after Closed until destruction.
    CHECK(call.b->send(to_alice, call.now) == Status::WrongState);
}

void test_decline_and_consent_timeout(const CryptoContext& crypto) {
    {
        Call call(crypto);
        call.invite();
        call.join();
        CHECK(call.b->decide(false, call.now) == Status::Ok);
        call.wire.pump(call.now);
        CHECK(call.b->snapshot().result == Status::Declined);
        const CallSnapshot a = call.a->snapshot();
        // The responder never proved its identity, so its END is not
        // attributed to the contact.
        CHECK(a.result == Status::PeerEnded && a.remote_end == 2U &&
              !a.remote_end_attributed && !a.peer_authenticated);
        CHECK(call.b->decide(true, call.now) == Status::WrongState);
    }
    {
        Call call(crypto);
        call.invite();
        call.join();
        call.advance(seconds(29));
        CHECK(call.b->snapshot().state == CallState::AwaitingAcceptance);
        call.advance(seconds(1));
        CHECK(call.b->snapshot().result == Status::Timeout);
        CHECK(call.b->snapshot().local_end == 3U);
        CHECK(call.a->snapshot().result == Status::PeerEnded);
    }
}

void test_budgets(const CryptoContext& crypto) {
    {
        // Nothing passes after pairing: both handshakes time out at 10 s.
        Call call(crypto);
        call.invite();
        call.relay.tamper = [](std::size_t, Bytes& bytes) { bytes.clear(); };
        call.join();
        call.advance(seconds(9));
        CHECK(call.a->snapshot().state == CallState::Handshaking);
        call.advance(seconds(1));
        CHECK(call.a->snapshot().result == Status::Timeout);
        CHECK(call.b->snapshot().result == Status::Timeout);
    }
    {
        // The responder never joins: the invitation expires the attempt.
        Call call(crypto, 30);
        call.invite();
        call.advance(seconds(29));
        CHECK(call.a->snapshot().state == CallState::Joined);
        call.advance(seconds(1));
        CHECK(call.a->snapshot().result == Status::Expired);
        CHECK(call.relay.closed[0]);  // CANCEL reached the relay
    }
    {
        // The initiator's 40-second authentication budget: the responder's
        // proof never arrives because its bytes stop after the handshake.
        Call call(crypto);
        call.invite();
        call.join();
        call.relay.tamper = [](std::size_t from, Bytes& bytes) {
            if (from == 1) bytes.clear();
        };
        CHECK(call.b->decide(true, call.now) == Status::Ok);
        call.advance(seconds(39));
        CHECK(call.a->snapshot().state == CallState::Authenticating);
        call.advance(seconds(1));
        CHECK(call.a->snapshot().result == Status::Timeout);
    }
}

void test_invitation_refusals(const CryptoContext& crypto) {
    Call call(crypto);
    call.invite();
    std::unique_ptr<OpenedInvitation> opened;
    // Another service, or another relay.
    CHECK(call.open(call.bob, opened, authority("ito-other")) ==
          Status::AuthorityMismatch);
    Authority other = authority();
    other.server_fingerprint[0] ^= 1U;
    CHECK(call.open(call.bob, opened, other) == Status::AuthorityMismatch);
    // A third identity is not the invited responder.
    Identity carol(crypto, kUnix0);
    befriend(crypto, carol, call.alice, kUnix0);
    CHECK(call.open(carol, opened) == Status::Untrusted);
    // Each altered byte of the signed invitation is refused.
    const Bytes original = call.invitation;
    for (std::size_t index = 17; index < original.size(); index += 7) {
        call.invitation = original;
        call.invitation[index] ^= 0x01U;
        const Status status = call.open(call.bob, opened);
        CHECK(status != Status::Ok);
    }
    call.invitation = original;
    // Time: expired at expiry, with no grace.
    const TimePoint saved = call.now;
    call.now = kT0 + seconds(120);
    CHECK(call.open(call.bob, opened) == Status::Expired);
    call.now = saved;
    // Unverified and blocked initiators.
    CHECK(call.bob.store->block_contact(call.alice.key) == Status::Ok);
    CHECK(call.open(call.bob, opened) == Status::Untrusted);

    // Spent once, refused again, including after a restart.
    Call fresh(crypto);
    fresh.invite();
    fresh.join();
    std::unique_ptr<OpenedInvitation> again;
    CHECK(fresh.open(fresh.bob, again) == Status::AlreadySpent);
    fresh.b.reset();
    fresh.wire.engines[1] = nullptr;
    fresh.bob.reopen(crypto);
    CHECK(fresh.open(fresh.bob, again) == Status::AlreadySpent);

    // Eight pending invitations at most.
    Call many(crypto);
    many.invite();
    std::vector<std::unique_ptr<OpenedInvitation>> held(8);
    std::size_t opened_count = 0;
    for (auto& slot : held) {
        if (many.open(many.bob, slot) == Status::Ok) ++opened_count;
    }
    CHECK(opened_count == 8U);
    std::unique_ptr<OpenedInvitation> ninth;
    CHECK(many.open(many.bob, ninth) == Status::Capacity);
}

void test_invitation_time_rules() {
    CHECK(check_invitation_time(100, 101, 100) == Status::Ok);
    CHECK(check_invitation_time(100, 700, 100) == Status::Ok);
    CHECK(check_invitation_time(100, 701, 100) == Status::Malformed);
    CHECK(check_invitation_time(100, 100, 100) == Status::Malformed);
    CHECK(check_invitation_time(101, 100, 100) == Status::Malformed);
    CHECK(check_invitation_time(160, 200, 100) == Status::Ok);
    CHECK(check_invitation_time(161, 200, 100) == Status::Expired);
    CHECK(check_invitation_time(100, 200, 200) == Status::Expired);
    CHECK(check_invitation_time(UINT64_MAX - 10, UINT64_MAX, 0) ==
          Status::Expired);
}

// Pairs a raw TLS server, holding the invitation PSK, with the initiator.
struct RawResponder final {
    Call call;
    std::unique_ptr<RawPeer> raw;
    explicit RawResponder(const CryptoContext& crypto,
                          const EngineOptions& options = {})
        : call(crypto, 120, options) {
        call.invite();
        raw = std::make_unique<RawPeer>(crypto, TlsRole::Server,
                                        call.parsed.invitation_id,
                                        call.parsed.psk);
        call.wire.raws[1] = raw.get();
        call.relay.raw[1] = true;
        call.relay.paired = true;
        call.relay.pending[0].insert(call.relay.pending[0].end(),
                                     {0, 0, 0, 1, 5});  // PAIRED
        call.wire.pump(call.now);
        CHECK(raw->established);
        CHECK(raw->last(FrameType::Offer) != nullptr);
    }
    // A proof from Bob's real key over chosen inputs.
    Signature bob_signs(Role role, const Hash& exporter, const Hash& context) {
        Signature signature{};
        CHECK(call.bob.store->sign(auth_message(role, exporter, context),
                                   signature) == Status::Ok);
        return signature;
    }
    CallSnapshot after(const std::function<void(RawPeer&)>& script) {
        script(*raw);
        call.wire.pump(call.now);
        return call.a->snapshot();
    }
    // An honest exchange from the raw side, up to MediaAuthorized.
    void authorize() {
        const auto select = profile_body(1);
        Signature bob_signature{};
        after([&](RawPeer& peer) {
            peer.send(FrameType::Select, select);
            bob_signature =
                bob_signs(Role::Responder, peer.tls.exporter(), call.context());
            peer.send(FrameType::Auth, bob_signature);
        });
        const RawPeer::Owned* alice_auth = raw->last(FrameType::Auth);
        CHECK(alice_auth != nullptr);
        Signature alice_signature{};
        std::copy(alice_auth->payload.begin(), alice_auth->payload.end(),
                  alice_signature.begin());
        const Hash confirm = accept_body(call.crypto, call.context(),
                                         alice_signature, bob_signature);
        CHECK(raw->last(FrameType::Accept) != nullptr &&
              Bytes(confirm.begin(), confirm.end()) ==
                  raw->last(FrameType::Accept)->payload);
        CHECK(after([&](RawPeer& peer) {
                  peer.send(FrameType::Accept, confirm);
              }).state == CallState::MediaAuthorized);
    }
};

// Pairs the initiator with a raw OpenSSL server holding the invitation PSK.
struct OpenSslResponder final {
    Call call;
    std::unique_ptr<OpenSslServer> server;
    OpenSslResponder(const CryptoContext& crypto, OpenSslServerOptions options,
                     EngineOptions client_options = {}, bool pump = true)
        : call(crypto, 120, client_options) {
        call.invite();
        server = std::make_unique<OpenSslServer>(
            crypto, call.parsed.invitation_id, call.parsed.psk, options);
        call.wire.raws[1] = server.get();
        call.relay.raw[1] = true;
        call.relay.paired = true;
        call.relay.pending[0].insert(call.relay.pending[0].end(),
                                     {0, 0, 0, 1, 5});  // PAIRED
        if (pump) call.wire.pump(call.now);
    }
};

// Stop after the first real HRR has reached the engine, before delivering
// ClientHello2 to the server. Time stays fixed throughout the exchange.
void first_hello_retry(OpenSslResponder& peer) {
    for (int round = 0; round < 8 && !peer.server->hello_retry_sent; ++round)
        peer.call.wire.pump(peer.call.now, 1);
    CHECK(peer.server->hello_retry_sent);
    CHECK(peer.server->client_hellos == 1U);
    CHECK(peer.call.a->snapshot().state == CallState::Handshaking);
    CHECK(peer.call.a->snapshot().result == Status::Ok);
    CHECK(!peer.call.a->output(peer.call.now).empty());  // ClientHello2
}

void test_hello_retry_limit(const CryptoContext& crypto) {
    EngineOptions client_options;
    client_options.tls.groups = "X25519:X25519MLKEM768";

    OpenSslResponder valid(crypto, {}, client_options, false);
    first_hello_retry(valid);
    valid.call.wire.pump(valid.call.now);
    CHECK(valid.server->client_hellos == 2U);
    CHECK(valid.server->established);
    CHECK(!valid.server->hello_retry_replayed);
    CHECK(valid.call.a->snapshot().state == CallState::Authenticating);
    std::puts(
        "HRR positive: first accepted, ClientHello2 observed, TLS complete");

    // Authentication failures after one valid HRR keep their own outcome.
    OpenSslResponder damaged(crypto, {}, client_options, false);
    first_hello_retry(damaged);
    damaged.call.relay.tamper = [](std::size_t from, Bytes& bytes) {
        if (from == 1U && !bytes.empty()) bytes.back() ^= 1U;
    };
    damaged.call.wire.pump(damaged.call.now);
    CHECK(damaged.server->client_hellos == 2U);
    CHECK(!damaged.server->hello_retry_replayed);
    CHECK(damaged.call.a->snapshot().result == Status::AuthenticationFailed);

    // Replay the real server's HRR after it processes ClientHello2, before
    // its final flight. No TLS state or retry count is injected into the core.
    for (const bool fragmented : {false, true}) {
        OpenSslServerOptions second;
        second.replay_hello_retry = true;
        OpenSslResponder repeated(crypto, second, client_options, false);
        first_hello_retry(repeated);
        if (fragmented) {
            const ByteView pending = repeated.call.a->output(repeated.call.now);
            const Bytes hello2(pending.begin(), pending.end());
            repeated.call.a->on_written(hello2.size(), repeated.call.now);
            repeated.server->received(hello2);
            const Bytes flight = repeated.server->output();
            CHECK(!flight.empty());
            for (const Byte& byte : flight) {
                const CallState state = repeated.call.a->snapshot().state;
                if (state == CallState::Closing || state == CallState::Closed)
                    break;
                CHECK(repeated.call.a->receive_capacity() != 0U);
                repeated.call.a->on_received(ByteView(&byte, 1U),
                                             repeated.call.now);
            }
            // Flush the fatal alert so the terminal snapshot publishes its
            // result, just as the normal wire pump does. Time stays fixed.
            repeated.call.wire.pump(repeated.call.now);
        } else {
            repeated.call.wire.pump(repeated.call.now);
        }
        CHECK(repeated.server->client_hellos == 2U);
        CHECK(repeated.server->hello_retry_replayed);
        const auto result = repeated.call.a->snapshot();
        std::printf("HRR repeated: fragmented=%u, result=%u, state=%u\n",
                    static_cast<unsigned int>(fragmented),
                    static_cast<unsigned int>(result.result),
                    static_cast<unsigned int>(result.state));
        CHECK(result.result == Status::ProtocolFailure);
        CHECK(result.state == CallState::Closing ||
              result.state == CallState::Closed);
        repeated.call.a->tick(repeated.call.now);
        CHECK(repeated.call.a->snapshot().result == Status::ProtocolFailure);
    }
}

// Application drain, rather than a transport read, must make room for
// already accepted encrypted DATA and END after an orderly EOF.
Bytes drain_after_eof(CallEngine& engine, TimePoint now) {
    Bytes delivered;
    std::array<Byte, kMaxDataBytes> buffer{};
    for (std::size_t round = 0; round < 16U; ++round) {
        const CallSnapshot before = engine.snapshot();
        CHECK(before.data_received >= delivered.size());
        CHECK(before.data_received - delivered.size() <= kMaxReceivedData);
        std::size_t size = 0U;
        const Status status = engine.receive(buffer, size);
        if (status == Status::Ok) {
            CHECK(size != 0U);
            delivered.insert(
                delivered.end(), buffer.begin(),
                buffer.begin() + static_cast<std::ptrdiff_t>(size));
        } else {
            CHECK(size == 0U);
            CHECK(status == Status::WouldBlock || status == Status::WrongState);
        }
        engine.tick(now);
        if (engine.closed() && status == Status::WrongState) return delivered;
    }
    CHECK(false);  // Five DATA frames and one terminal event cannot need more.
    return {};
}

void check_closed_stable(CallEngine& engine, TimePoint now) {
    CHECK(engine.closed());
    const CallSnapshot closed = engine.snapshot();
    for (int repetition = 0; repetition < 3; ++repetition) {
        engine.on_transport_end(true, now);
        engine.on_transport_end(false, now);
        engine.tick(now + seconds(1));
        engine.abandon(Status::TransportFailed);
        const CallSnapshot after = engine.snapshot();
        CHECK(after.state == CallState::Closed &&
              after.result == closed.result);
        CHECK(after.data_received == closed.data_received &&
              after.frames_received == closed.frames_received &&
              after.frames_sent == closed.frames_sent);
        CHECK(after.remote_end == closed.remote_end &&
              after.local_end == closed.local_end &&
              after.remote_end_attributed == closed.remote_end_attributed);
        CHECK(engine.receive_capacity() == 0U && engine.output(now).empty());
    }
}

void check_attempt_released_once(CallEngine& ended, Store& store,
                                 const Key& peer, TimePoint now) {
    // Every pending slot is available while the completed engine still lives.
    for (std::size_t slot = 0; slot < kMaxPendingInvitations; ++slot) {
        CHECK(store.acquire_pending() == Status::Ok);
    }
    CHECK(store.acquire_pending() == Status::Capacity);
    for (std::size_t slot = 0; slot < kMaxPendingInvitations; ++slot) {
        store.release_pending();
    }
    std::unique_ptr<CallEngine> next;
    CHECK(CallEngine::create_initiator(store, peer, authority(), 60U,
                                       unix_at(now), now, next) == Status::Ok);
    // Repeated completion of the old owner must not unregister the new one.
    check_closed_stable(ended, now);
    std::unique_ptr<CallEngine> blocked;
    CHECK(CallEngine::create_initiator(store, peer, authority(), 60U,
                                       unix_at(now), now,
                                       blocked) == Status::Busy);
    CHECK(blocked == nullptr);
    next.reset();
    CHECK(CallEngine::create_initiator(store, peer, authority(), 60U,
                                       unix_at(now), now,
                                       blocked) == Status::Ok);
}

void feed_records(CallEngine& engine, const Bytes& records, TimePoint now,
                  std::size_t fragment) {
    CHECK(fragment != 0U);
    for (std::size_t offset = 0; offset < records.size();) {
        const std::size_t count = std::min(
            {fragment, records.size() - offset, engine.receive_capacity()});
        CHECK(count != 0U);
        engine.on_received(ByteView(records).subspan(offset, count), now);
        offset += count;
    }
}

Bytes queue_raw_data(RawResponder& peer, std::size_t frames,
                     std::size_t fragment) {
    const Bytes payload = pattern(frames * kMaxDataBytes, 0x35U);
    for (std::size_t offset = 0; offset < payload.size();
         offset += kMaxDataBytes) {
        peer.raw->send(FrameType::Data,
                       ByteView(payload).subspan(offset, kMaxDataBytes));
        feed_records(*peer.call.a, peer.raw->output(), peer.call.now, fragment);
    }
    CHECK(peer.call.a->snapshot().data_received ==
          std::min(payload.size(), kMaxReceivedData));
    return payload;
}

void queue_raw_end(RawResponder& peer, std::size_t fragment) {
    const std::array<Byte, 1> canceled{static_cast<Byte>(EndReason::Canceled)};
    peer.raw->send(FrameType::End, canceled);
    feed_records(*peer.call.a, peer.raw->output(), peer.call.now, fragment);
}

void check_held_eof(CallEngine& engine, TimePoint now) {
    engine.on_transport_end(true, now);
    CHECK(!engine.closed() && engine.transport_ended());
    CHECK(engine.snapshot().state == CallState::MediaAuthorized);
    CHECK(engine.receive_capacity() == 0U && engine.output(now).empty());
    const std::array<Byte, 1> extra{0x5AU};
    CHECK(engine.send(extra, now) == Status::WrongState);
    // Input reported after the terminal transport event cannot extend its tail.
    engine.on_received(extra, now);
    CHECK(engine.snapshot().remote_end == 0U);
}

void test_buffered_data_end_eof(const CryptoContext& crypto) {
    // Exercise both endpoint roles and EOF on either side of application drain.
    for (std::size_t receiver : {0U, 1U}) {
        for (bool eof_first : {false, true}) {
            Call call(crypto);
            call.establish();
            CallEngine& from = receiver == 0U ? *call.b : *call.a;
            CallEngine& to = receiver == 0U ? *call.a : *call.b;
            Identity& owner = receiver == 0U ? call.alice : call.bob;
            const Key& contact = receiver == 0U ? call.bob.key : call.alice.key;
            const CallSnapshot authorized = to.snapshot();
            const Bytes payload = pattern(5U * kMaxDataBytes, 0x21U);
            for (std::size_t offset = 0; offset < payload.size();
                 offset += kMaxDataBytes) {
                CHECK(
                    from.send(ByteView(payload).subspan(offset, kMaxDataBytes),
                              call.now) == Status::Ok);
                call.wire.pump(call.now);
            }
            CHECK(from.hangup(call.now) == Status::Ok);
            call.wire.pump(call.now);
            CHECK(from.closed() && from.snapshot().data_sent == payload.size());
            CHECK(call.relay.pending[receiver].empty());
            CHECK(to.snapshot().data_received == kMaxReceivedData);
            CHECK(to.snapshot().frames_received ==
                  authorized.frames_received + 4U);
            if (eof_first) check_held_eof(to, call.now);
            CHECK(drain_after_eof(to, call.now) == payload);
            if (!eof_first) to.on_transport_end(true, call.now);
            const CallSnapshot finished = to.snapshot();
            CHECK(finished.result == Status::PeerEnded &&
                  finished.remote_end ==
                      static_cast<Byte>(EndReason::Canceled) &&
                  finished.remote_end_attributed);
            CHECK(finished.data_received == payload.size() &&
                  finished.frames_received == authorized.frames_received + 6U);
            check_attempt_released_once(to, *owner.store, contact, call.now);
        }
    }
    // A complete TLS record and a record split at every byte have the same
    // outcome. The fifth DATA frame and END are still encrypted at EOF.
    for (std::size_t fragment : {std::size_t{1U}, kMaxNetworkQueue}) {
        RawResponder peer(crypto);
        peer.authorize();
        const std::uint64_t frames = peer.call.a->snapshot().frames_received;
        const Bytes payload = queue_raw_data(peer, 5U, fragment);
        queue_raw_end(peer, fragment);
        check_held_eof(*peer.call.a, peer.call.now);
        CHECK(drain_after_eof(*peer.call.a, peer.call.now) == payload);
        const CallSnapshot done = peer.call.a->snapshot();
        CHECK(done.result == Status::PeerEnded && done.remote_end == 1U &&
              done.remote_end_attributed &&
              done.data_received == payload.size());
        CHECK(done.frames_received == frames + 6U);
        check_closed_stable(*peer.call.a, peer.call.now);
    }
}

void test_buffered_coalesced_end(const CryptoContext& crypto) {
    RawResponder peer(crypto);
    peer.authorize();
    const std::uint64_t frames = peer.call.a->snapshot().frames_received;
    Bytes payload = queue_raw_data(peer, 3U, kMaxNetworkQueue);
    const std::array<Byte, 1> one{0x7BU};
    const std::array<Byte, 1> canceled{1U};
    SecretBytes data_frame;
    SecretBytes end_frame;
    encode_frame(data_frame, FrameType::Data, peer.raw->sequence++, one);
    encode_frame(end_frame, FrameType::End, peer.raw->sequence++, canceled);
    Bytes combined(data_frame.view().begin(), data_frame.view().end());
    combined.insert(combined.end(), end_frame.view().begin(),
                    end_frame.view().end());
    // One TLS write coalesces both frames. After the one-byte DATA, the
    // queue has 49153 bytes and END can remain in SSL's plaintext buffer.
    peer.raw->send_plain(combined);
    feed_records(*peer.call.a, peer.raw->output(), peer.call.now,
                 kMaxNetworkQueue);
    payload.push_back(one[0]);
    CHECK(payload.size() == 49153U);
    CHECK(peer.call.a->snapshot().data_received == payload.size());
    CHECK(peer.call.a->snapshot().frames_received == frames + 4U);
    check_held_eof(*peer.call.a, peer.call.now);
    CHECK(drain_after_eof(*peer.call.a, peer.call.now) == payload);
    const CallSnapshot done = peer.call.a->snapshot();
    CHECK(done.result == Status::PeerEnded && done.remote_end == 1U &&
          done.remote_end_attributed && done.frames_received == frames + 5U);
    check_closed_stable(*peer.call.a, peer.call.now);
}

void test_buffered_incomplete_ends(const CryptoContext& crypto) {
    // EOF cannot supply an END or authenticate an incomplete TLS record.
    for (int variant = 0; variant < 5; ++variant) {
        RawResponder peer(crypto);
        peer.authorize();
        const Bytes payload = queue_raw_data(peer, 5U, kMaxNetworkQueue);
        if (variant == 1 || variant == 4) {
            peer.raw->send_plain({0U, 0U, 0U, 11U, 2U});
        }
        if (variant == 2) {
            const std::array<Byte, 1> canceled{1U};
            peer.raw->send(FrameType::End, canceled);
            Bytes truncated = peer.raw->output();
            CHECK(!truncated.empty());
            truncated.pop_back();
            feed_records(*peer.call.a, truncated, peer.call.now,
                         kMaxNetworkQueue);
        } else {
            if (variant == 3 || variant == 4) peer.raw->tls.close();
            feed_records(*peer.call.a, peer.raw->output(), peer.call.now,
                         kMaxNetworkQueue);
        }
        check_held_eof(*peer.call.a, peer.call.now);
        CHECK(drain_after_eof(*peer.call.a, peer.call.now) == payload);
        const std::array<Status, 5> expected{
            Status::TransportFailed, Status::ProtocolFailure,
            Status::TransportFailed, Status::PeerEnded,
            Status::ProtocolFailure};
        const CallSnapshot done = peer.call.a->snapshot();
        CHECK(done.result == expected[static_cast<std::size_t>(variant)]);
        // Existing close_notify semantics are an unattributed PeerEnded;
        // only an authenticated END frame supplies a remote reason.
        CHECK(done.remote_end == 0U && !done.remote_end_attributed);
        CHECK(done.data_received == payload.size());
        check_closed_stable(*peer.call.a, peer.call.now);
    }
}

void test_buffered_eof_overrides(const CryptoContext& crypto) {
    for (int variant = 0; variant < 7; ++variant) {
        RawResponder peer(crypto);
        peer.authorize();
        (void)queue_raw_data(peer, 5U, kMaxNetworkQueue);
        queue_raw_end(peer, kMaxNetworkQueue);
        check_held_eof(*peer.call.a, peer.call.now);
        switch (variant) {
            case 0:
                peer.call.a->on_transport_end(false, peer.call.now);
                break;
            case 1:
                peer.call.a->signals().cancel.store(true);
                break;
            case 2:
                CHECK(peer.call.a->hangup(peer.call.now) == Status::Ok);
                break;
            case 3:
                peer.call.a->signals().authority_lost.store(true);
                break;
            case 4:
                CHECK(peer.call.alice.store->block_contact(peer.call.bob.key) ==
                      Status::Ok);
                break;
            case 5:
                peer.call.now += kMaxCallDuration;
                break;
            case 6:
                peer.call.now -= seconds(1);
                break;
        }
        peer.call.a->tick(peer.call.now);
        const std::array<Status, 7> expected{
            Status::TransportFailed, Status::Canceled,  Status::Canceled,
            Status::AuthorityEnded,  Status::Untrusted, Status::Expired,
            Status::ClockRegressed};
        const CallSnapshot done = peer.call.a->snapshot();
        CHECK(done.state == CallState::Closed &&
              done.result == expected[static_cast<std::size_t>(variant)]);
        CHECK(done.data_received == kMaxReceivedData && done.remote_end == 0U &&
              !done.remote_end_attributed);
        CHECK(drain(*peer.call.a).size() == kMaxReceivedData);
        check_closed_stable(*peer.call.a, peer.call.now);
    }
}

void test_buffered_eof_scheduling(const CryptoContext& crypto) {
    RawResponder peer(crypto);
    peer.authorize();
    const Bytes payload = queue_raw_data(peer, 5U, kMaxNetworkQueue);
    queue_raw_end(peer, kMaxNetworkQueue);
    check_held_eof(*peer.call.a, peer.call.now);
    const auto before = peer.call.a->snapshot();
    const auto deadline = peer.call.a->next_deadline();
    CHECK(deadline && *deadline == peer.call.now + kMaxCallDuration);
    peer.call.now += kKeyUpdateInterval;
    peer.call.a->tick(peer.call.now);
    const auto held = peer.call.a->snapshot();
    CHECK(held.state == CallState::MediaAuthorized);
    CHECK(held.key_updates_sent == before.key_updates_sent &&
          held.frames_sent == before.frames_sent);
    CHECK(peer.call.a->output(peer.call.now).empty());
    CHECK(peer.call.a->next_deadline() == deadline &&
          *deadline > peer.call.now);
    CHECK(drain_after_eof(*peer.call.a, peer.call.now) == payload);
    CHECK(peer.call.a->snapshot().remote_end_attributed);
    check_closed_stable(*peer.call.a, peer.call.now);
}

void test_buffered_eof(const CryptoContext& crypto) {
    test_buffered_data_end_eof(crypto);
    test_buffered_coalesced_end(crypto);
    test_buffered_incomplete_ends(crypto);
    test_buffered_eof_overrides(crypto);
    test_buffered_eof_scheduling(crypto);
}

void test_raw_responder(const CryptoContext& crypto) {
    const auto select = profile_body(1);
    // A relay that learned the PSK cannot substitute its own key.
    {
        RawResponder peer(crypto);
        Seed impostor;
        Key impostor_key{};
        crypto.generate_identity(impostor, impostor_key);
        const CallSnapshot a = peer.after([&](RawPeer& raw) {
            raw.send(FrameType::Select, select);
            const Signature fake = crypto.sign(
                impostor, auth_message(Role::Responder, raw.tls.exporter(),
                                       peer.call.context()));
            raw.send(FrameType::Auth, fake);
        });
        CHECK(a.result == Status::AuthenticationFailed && a.local_end == 4U);
        CHECK(!a.peer_authenticated);
    }
    // SELECT must match the signed invitation profile.
    {
        RawResponder peer(crypto);
        const std::array<Byte, 2> two{0, 2};
        CHECK(
            peer.after([&](RawPeer& raw) { raw.send(FrameType::Select, two); })
                .result == Status::Unsupported);
    }
    {
        EngineOptions content;
        content.profile = 2U;
        RawResponder peer(crypto, content);
        CHECK(peer.call.parsed.profile == 2U);
        const CallSnapshot downgraded = peer.after(
            [&](RawPeer& raw) { raw.send(FrameType::Select, select); });
        CHECK(downgraded.result == Status::Unsupported);
        CHECK(!downgraded.peer_authenticated && !downgraded.consent_pending);
        CHECK(peer.raw->last(FrameType::Auth) == nullptr);
    }
    // Wrong role, sequence gap, duplicate, DATA before media, unknown type,
    // oversized length.
    const std::vector<std::function<void(RawPeer&)>> malformed{
        [&](RawPeer& raw) { raw.send(FrameType::Offer, select); },
        [&](RawPeer& raw) { raw.send_at(FrameType::Select, 1, select); },
        [&](RawPeer& raw) {
            raw.send_at(FrameType::Select, 0, select);
            raw.send_at(FrameType::Select, 0, select);
        },
        [&](RawPeer& raw) {
            raw.send(FrameType::Select, select);
            raw.send(FrameType::Data, select);
        },
        [&](RawPeer& raw) {
            raw.send_plain({0, 0, 0, 11, 9, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1});
        },
        [&](RawPeer& raw) { raw.send_plain({0, 1, 0, 0}); },
        [&](RawPeer& raw) {
            const std::array<Byte, 1> unknown{7};
            raw.send(FrameType::End, unknown);
        },
    };
    for (const auto& script : malformed) {
        RawResponder peer(crypto);
        CHECK(peer.after(script).result == Status::ProtocolFailure);
    }
    // Bob's real key over a tampered exporter, context (room, service) or
    // role is refused.
    for (int variant = 0; variant < 4; ++variant) {
        RawResponder peer(crypto);
        const CallSnapshot a = peer.after([&](RawPeer& raw) {
            raw.send(FrameType::Select, select);
            Hash exporter = raw.tls.exporter();
            Hash context = peer.call.context();
            Role role = Role::Responder;
            if (variant == 0) exporter[0] ^= 1U;
            if (variant == 1) {
                Invitation moved;
                CHECK(parse_invitation(peer.call.invitation, moved) ==
                      CodecStatus::Ok);
                moved.room_id[0] ^= 1U;
                context = context_hash(crypto, peer.call.invitation_hash, moved,
                                       select, select);
            }
            if (variant == 2) {
                Invitation moved;
                CHECK(parse_invitation(peer.call.invitation, moved) ==
                      CodecStatus::Ok);
                moved.authority.service = "ito-calm";
                context = context_hash(crypto, peer.call.invitation_hash, moved,
                                       select, select);
            }
            if (variant == 3) role = Role::Initiator;
            raw.send(FrameType::Auth, peer.bob_signs(role, exporter, context));
        });
        CHECK(a.result == Status::AuthenticationFailed);
    }
    // A correct proof but a wrong confirmation.
    {
        RawResponder peer(crypto);
        const CallSnapshot a = peer.after([&](RawPeer& raw) {
            raw.send(FrameType::Select, select);
            raw.send(FrameType::Auth,
                     peer.bob_signs(Role::Responder, raw.tls.exporter(),
                                    peer.call.context()));
        });
        CHECK(a.peer_authenticated && a.state == CallState::Authenticating);
        Hash wrong{};
        wrong.fill(0x42);
        CHECK(peer.after(
                      [&](RawPeer& raw) { raw.send(FrameType::Accept, wrong); })
                  .result == Status::AuthenticationFailed);
    }
    // END before any proof proves only PSK possession.
    {
        RawResponder peer(crypto);
        const std::array<Byte, 1> declined{2};
        const CallSnapshot a = peer.after(
            [&](RawPeer& raw) { raw.send(FrameType::End, declined); });
        CHECK(a.result == Status::PeerEnded && a.remote_end == 2U &&
              !a.remote_end_attributed);
    }
    // A partial frame and then close_notify is a protocol failure.
    {
        RawResponder peer(crypto);
        CHECK(peer.after([&](RawPeer& raw) {
                      raw.send_plain({0, 0, 0, 11, 2});
                      raw.tls.close();
                  })
                  .result == Status::ProtocolFailure);
    }
    // A full honest exchange from the raw side, then a KeyUpdate flood.
    {
        RawResponder peer(crypto);
        peer.authorize();
        for (int update = 0; update < 8; ++update) {
            CHECK(peer.after([&](RawPeer& raw) {
                          CHECK(raw.tls.update_sending_key());
                      })
                      .state == CallState::MediaAuthorized);
        }
        const CallSnapshot a = peer.after(
            [&](RawPeer& raw) { CHECK(raw.tls.update_sending_key()); });
        CHECK(a.result == Status::ProtocolFailure && a.local_end == 4U &&
              a.key_updates_received == 9U);
    }
}

// Pairs a raw TLS client, holding the PSK, with the responder.
void test_raw_initiator(const CryptoContext& crypto) {
    const auto offer = profile_body(1);
    const auto run = [&](const std::function<void(RawPeer&, Call&)>& script,
                         const EngineOptions& options = {}) {
        auto call = std::make_unique<Call>(crypto, 120, options);
        call->invite();
        call->wire.engines[0] = nullptr;
        call->relay.raw[0] = true;
        call->relay.pending[0].clear();
        auto raw = std::make_unique<RawPeer>(crypto, TlsRole::Client,
                                             call->parsed.invitation_id,
                                             call->parsed.psk);
        call->wire.raws[0] = raw.get();
        call->join();
        raw->start();
        call->wire.pump(call->now);
        CHECK(raw->established);
        script(*raw, *call);
        call->wire.pump(call->now);
        const CallSnapshot b = call->b->snapshot();
        return b;
    };
    const std::array<Byte, 2> two{0, 2};
    CHECK(run([&](RawPeer& raw, Call&) {
              raw.send(FrameType::Offer, two);
          }).result == Status::Unsupported);
    EngineOptions content;
    content.profile = 2U;
    const CallSnapshot downgraded = run(
        [&](RawPeer& raw, Call& call) {
            CHECK(call.parsed.profile == 2U);
            raw.send(FrameType::Offer, offer);
        },
        content);
    CHECK(downgraded.result == Status::Unsupported);
    CHECK(!downgraded.peer_authenticated && !downgraded.consent_pending);
    // An initiator proof from a key other than the invited contact's.
    const CallSnapshot impostor = run([&](RawPeer& raw, Call& call) {
        raw.send(FrameType::Offer, offer);
        Seed seed;
        Key key{};
        crypto.generate_identity(seed, key);
        raw.send(
            FrameType::Auth,
            crypto.sign(seed, auth_message(Role::Initiator, raw.tls.exporter(),
                                           call.context())));
    });
    CHECK(impostor.result == Status::AuthenticationFailed &&
          !impostor.consent_pending);
    // ACCEPT before the responder's consent and proof.
    CHECK(run([&](RawPeer& raw, Call& call) {
              raw.send(FrameType::Offer, offer);
              Signature signature{};
              CHECK(call.alice.store->sign(
                        auth_message(Role::Initiator, raw.tls.exporter(),
                                     call.context()),
                        signature) == Status::Ok);
              raw.send(FrameType::Auth, signature);
              Hash early{};
              raw.send(FrameType::Accept, early);
          }).result == Status::ProtocolFailure);
}

void test_policy_in_engine(const CryptoContext& crypto) {
    // Both peers configured for a classical group complete TLS, and the
    // engine's exact policy check ends the attempt before any proof.
    EngineOptions classical;
    classical.tls.groups = "X25519";
    Call call(crypto, 120, classical);
    call.responder_options = classical;
    call.invite();
    call.join();
    CHECK(call.a->snapshot().result == Status::AuthenticationFailed &&
          !call.a->snapshot().peer_authenticated);
    CHECK(call.b->snapshot().result == Status::AuthenticationFailed);
}

void test_cancel_each_state(const CryptoContext& crypto) {
    // Before RESERVED, while Joined, while Handshaking, while consent is
    // pending, while the initiator authenticates, and during media.
    for (int stage = 0; stage < 7; ++stage) {
        Call call(crypto);
        CallEngine* target = call.a.get();
        if (stage == 0) {
            call.a->start(call.now);
        } else {
            call.invite();
        }
        if (stage == 2) {
            call.relay.tamper = [](std::size_t, Bytes& bytes) {
                bytes.clear();
            };
            call.join();
            CHECK(call.a->snapshot().state == CallState::Handshaking);
        }
        if (stage == 3 || stage == 4) {
            call.join();
            if (stage == 3) target = call.b.get();
        }
        if (stage >= 5) {
            call.join();
            CHECK(call.b->decide(true, call.now) == Status::Ok);
            call.wire.pump(call.now);
            if (stage == 6) target = call.b.get();
        }
        target->signals().cancel.store(true);
        call.advance(seconds(1));
        CHECK(target->snapshot().state == CallState::Closed);
        CHECK(target->snapshot().result == Status::Canceled);
        if (stage >= 3) {
            CallEngine& other = target == call.a.get() ? *call.b : *call.a;
            CHECK(other.snapshot().result == Status::PeerEnded &&
                  other.snapshot().remote_end == 1U);
        }
        if (stage == 1) CHECK(call.relay.closed[0]);
    }
}

void test_transition_rechecks(const CryptoContext& crypto) {
    {
        // A hangup before the run starts cancels it. The responder spends
        // nothing and sends nothing.
        Call call(crypto);
        call.invite();
        std::unique_ptr<OpenedInvitation> opened;
        CHECK(call.open(call.bob, opened) == Status::Ok);
        CHECK(CallEngine::create_responder(
                  *call.bob.store, opened, authority(), unix_at(call.now),
                  call.now, call.b, call.responder_options) == Status::Ok);
        call.wire.engines[1] = call.b.get();
        CHECK(call.b->hangup(call.now) == Status::Ok);
        call.b->start(call.now);
        call.wire.pump(call.now);
        CHECK(call.b->snapshot().result == Status::Canceled);
        CHECK(call.bob.store->check_unspent(call.parsed.invitation_id,
                                            call.parsed.expires_at) ==
              Status::Ok);
    }
    {
        // A decision after the consent budget is refused even before the
        // run's next tick has noticed the deadline.
        Call call(crypto);
        call.invite();
        call.join();
        call.now += seconds(30);
        CHECK(call.b->decide(true, call.now) == Status::WrongState);
        call.wire.pump(call.now);
        CHECK(call.b->snapshot().result == Status::Timeout);
        CHECK(call.a->snapshot().result == Status::PeerEnded);
    }
}

void test_authority_and_key_changes(const CryptoContext& crypto) {
    {
        Call call(crypto);
        call.establish();
        call.a->signals().authority_lost.store(true);
        call.advance(seconds(0));
        CHECK(call.a->snapshot().result == Status::AuthorityEnded);
        const CallSnapshot b = call.b->snapshot();
        CHECK(b.remote_end == 5U && b.remote_end_attributed);
    }
    {
        Call call(crypto);
        call.establish();
        Seed seed;
        Key replacement{};
        crypto.generate_identity(seed, replacement);
        CHECK(call.bob.store->replace_contact(call.alice.key, replacement,
                                              unix_at(call.now)) == Status::Ok);
        call.advance(seconds(0));
        CHECK(call.b->snapshot().result == Status::Untrusted);
        CHECK(call.a->snapshot().result == Status::PeerEnded);
    }
}

void test_transport_failures(const CryptoContext& crypto) {
    {
        Call call(crypto);
        call.establish();
        CHECK(call.a->send(pattern(100, 3), call.now) == Status::Ok);
        call.wire.write_blocked[0] = true;
        call.advance(seconds(4));
        CHECK(call.a->snapshot().state == CallState::MediaAuthorized);
        call.advance(seconds(1));
        CHECK(call.a->snapshot().result == Status::Timeout);
    }
    {
        Call call(crypto);
        call.establish();
        call.relay.closed[0] = true;
        call.advance(seconds(0));
        CHECK(call.a->snapshot().result == Status::TransportFailed);
    }
    {
        Call call(crypto);
        call.relay.refuse_reserve = 3;
        call.a->start(call.now);
        call.wire.pump(call.now);
        CHECK(call.a->snapshot().result == Status::RelayRefused &&
              call.a->snapshot().relay_refusal == 3U);
    }
    for (const std::uint32_t grant : {999U, 120'001U}) {
        Call call(crypto);
        call.relay.grant_override = grant;
        call.a->start(call.now);
        call.wire.pump(call.now);
        CHECK(call.a->snapshot().result == Status::ProtocolFailure);
    }
    {
        // The responder's spend fails: no JOIN is sent.
        Call call(crypto);
        call.invite();
        std::unique_ptr<OpenedInvitation> opened;
        CHECK(call.open(call.bob, opened) == Status::Ok);
        CHECK(CallEngine::create_responder(*call.bob.store, opened, authority(),
                                           unix_at(call.now), call.now,
                                           call.b) == Status::Ok);
        call.wire.engines[1] = call.b.get();
        // Admission has its own checkpoint; fail only the later spend.
        call.bob.fail_persist = true;
        call.b->start(call.now);
        call.wire.pump(call.now);
        CHECK(call.b->snapshot().result == Status::StorageFailed);
        CHECK(call.relay.tap[1].empty());
    }
    {
        // One attempt per installation.
        Call call(crypto);
        std::unique_ptr<CallEngine> second;
        CHECK(CallEngine::create_initiator(*call.alice.store, call.bob.key,
                                           authority(), 60, kUnix0, kT0,
                                           second) == Status::Busy);
    }
}

void test_media_limits(const CryptoContext& crypto) {
    {
        Call call(crypto);
        call.establish();
        call.advance(seconds(10 * 60));
        CHECK(call.a->snapshot().key_updates_sent == 1U);
        CHECK(call.b->snapshot().key_updates_received >= 1U);
        call.advance(seconds(20 * 60));
        CHECK(call.a->snapshot().result == Status::Expired);
        // Both reached the limit at the same virtual instant.
        const CallSnapshot b = call.b->snapshot();
        CHECK(b.result == Status::Expired || b.remote_end == 3U);
    }
    {
        Call call(crypto);
        call.establish();
        const Bytes block = pattern(kMaxDataBytes, 7);
        std::uint64_t sent = 0;
        Status status = Status::Ok;
        while (status == Status::Ok || status == Status::WouldBlock) {
            status = call.a->send(block, call.now);
            if (status == Status::Ok) sent += block.size();
            if (status == Status::WouldBlock) {
                call.wire.pump(call.now);
                (void)drain(*call.b);
            }
        }
        CHECK(status == Status::ResourceLimit);
        CHECK(sent == kMaxCallBytesPerDirection);
        CHECK(call.a->snapshot().key_updates_sent >= 3U);
        call.wire.pump(call.now);
        CHECK(call.b->snapshot().remote_end == 6U);
    }
    {
        // Backpressure: the receiver does not drain, so the sender blocks.
        Call call(crypto);
        call.establish();
        const Bytes block = pattern(kMaxDataBytes, 5);
        int accepted = 0;
        for (int attempt = 0; attempt < 64; ++attempt) {
            if (call.a->send(block, call.now) == Status::Ok) ++accepted;
            call.wire.pump(call.now);
        }
        CHECK(accepted < 64);
        CHECK(call.a->send(block, call.now) == Status::WouldBlock);
        // Draining releases the queues hop by hop until everything arrives.
        std::size_t received = 0;
        for (int round = 0; round < 64; ++round) {
            received += drain(*call.b).size();
            call.wire.pump(call.now);
        }
        CHECK(received == static_cast<std::size_t>(accepted) * kMaxDataBytes);
        CHECK(call.a->send(block, call.now) == Status::Ok);
    }
}

void test_receive_byte_cap(const CryptoContext& crypto) {
    // A peer that ignores its own sending cap. The receiver accepts exactly
    // 64 MiB of DATA in one direction and ends the call at the next byte.
    RawResponder peer(crypto);
    peer.authorize();
    const Bytes block = pattern(kMaxDataBytes, 3);
    const std::uint64_t frames = kMaxCallBytesPerDirection / kMaxDataBytes;
    std::uint64_t delivered = 0;
    for (std::uint64_t sent = 1; sent <= frames; ++sent) {
        peer.raw->send(FrameType::Data, block);
        if (sent % 4U == 0U) {
            peer.call.wire.pump(peer.call.now);
            delivered += drain(*peer.call.a).size();
        }
    }
    for (int round = 0; round < 8 && delivered < kMaxCallBytesPerDirection;
         ++round) {
        peer.call.wire.pump(peer.call.now);
        delivered += drain(*peer.call.a).size();
    }
    CHECK(delivered == kMaxCallBytesPerDirection);
    CHECK(peer.call.a->snapshot().state == CallState::MediaAuthorized);
    const std::array<Byte, 1> one{0x5A};
    const CallSnapshot a =
        peer.after([&](RawPeer& raw) { raw.send(FrameType::Data, one); });
    CHECK(a.result == Status::ResourceLimit && a.local_end == 6U);
    CHECK(a.data_received == kMaxCallBytesPerDirection + 1U);
    CHECK(drain(*peer.call.a).empty());
}

void test_peer_ticket(const CryptoContext& crypto) {
    // The contract forbids tickets. A server that issues one right after
    // the handshake ends the attempt before any proof is exchanged.
    OpenSslServerOptions options;
    options.issue_ticket = true;
    OpenSslResponder peer(crypto, options);
    CHECK(peer.server->established && peer.server->ticket_sent);
    const CallSnapshot a = peer.call.a->snapshot();
    CHECK(a.result == Status::ProtocolFailure && a.local_end == 4U &&
          !a.peer_authenticated);
}

void test_no_dhe_server(const CryptoContext& crypto) {
    // A server that allows and prefers PSK without (EC)DHE. The initiator
    // offers only PSK with DHE, so the handshake uses the hybrid group and
    // the engine continues to authentication.
    OpenSslServerOptions options;
    options.prefer_no_dhe = true;
    OpenSslResponder peer(crypto, options);
    CHECK(peer.server->established && peer.server->group() != nullptr &&
          std::string_view(peer.server->group()) == "X25519MLKEM768");
    CHECK(peer.call.a->snapshot().state == CallState::Authenticating);
}

void test_reserve_timeout(const CryptoContext& crypto) {
    // The relay reads RESERVE and never answers. The attempt ends at the
    // 15-second budget and frees the request with CANCEL, with nothing signed.
    Call call(crypto);
    call.relay.silent_reserve = true;
    call.a->start(call.now);
    call.wire.pump(call.now);
    call.advance(seconds(14));
    CHECK(call.a->snapshot().state == CallState::Preparing);
    call.advance(seconds(1));
    const CallSnapshot a = call.a->snapshot();
    CHECK(a.state == CallState::Closed && a.result == Status::Timeout);
    CHECK(!a.invitation_ready);
    CHECK(call.relay.closed[0]);  // CANCEL reached the relay
}

void test_establishment_input_rechecks(const CryptoContext& crypto) {
    // A RESERVED reply must not sign or spend after cancel, authority loss,
    // a block, or the reserve deadline, even when no tick has run yet.
    for (int variant = 0; variant < 4; ++variant) {
        Call call(crypto);
        call.a->start(call.now);
        const ByteView reserve = call.a->output(call.now);
        call.relay.from(0, reserve);
        call.a->on_written(reserve.size(), call.now);
        CHECK(!call.relay.pending[0].empty());
        if (variant == 0) call.a->signals().cancel.store(true);
        if (variant == 1) call.a->signals().authority_lost.store(true);
        if (variant == 2) {
            CHECK(call.alice.store->block_contact(call.bob.key) == Status::Ok);
        }
        if (variant == 3) call.now += seconds(15);
        const Bytes disk_before_reply = call.alice.disk;
        call.a->on_received(call.relay.pending[0], call.now);
        call.relay.pending[0].clear();
        CHECK(call.alice.disk == disk_before_reply);
        const std::array<Status, 4> results{Status::Canceled,
                                            Status::AuthorityEnded,
                                            Status::Untrusted, Status::Timeout};
        CHECK(call.a->snapshot().state == CallState::Closing);
        CHECK(!call.a->snapshot().invitation_ready);
        const ByteView pending = call.a->output(call.now);
        const Bytes cancellation(pending.begin(), pending.end());
        CHECK(cancellation == encode_cancel());
        call.wire.pump(call.now);
        CHECK(call.a->closed() &&
              call.a->snapshot().result ==
                  results[static_cast<std::size_t>(variant)]);
        // The only bytes sent are RESERVE and CANCEL, never JOIN.
        Bytes expected = encode_reserve(120'000U);
        const Bytes cancel = encode_cancel();
        expected.insert(expected.end(), cancel.begin(), cancel.end());
        CHECK(call.relay.tap[0] == expected);
    }
    {
        // PAIRED must not start TLS after cancellation.
        Call call(crypto);
        call.invite();
        call.a->signals().cancel.store(true);
        const Bytes paired{0, 0, 0, 1, 5};
        call.a->on_received(paired, call.now);
        CHECK(call.a->snapshot().state == CallState::Closing);
        const ByteView pending = call.a->output(call.now);
        CHECK(Bytes(pending.begin(), pending.end()) == encode_cancel());
        CHECK(call.a->snapshot().frames_sent == 0U);
        call.wire.pump(call.now);
        CHECK(call.a->closed() &&
              call.a->snapshot().result == Status::Canceled);
    }
    for (int variant = 0; variant < 2; ++variant) {
        // Hold the server's final flight. Cancel or the handshake deadline
        // must prevent the initiator's OFFER, without a preceding tick.
        Call call(crypto);
        call.invite();
        RawPeer raw(crypto, TlsRole::Server, call.parsed.invitation_id,
                    call.parsed.psk);
        const Bytes paired{0, 0, 0, 1, 5};
        call.a->on_received(paired, call.now);
        const ByteView hello = call.a->output(call.now);
        CHECK(!hello.empty());
        raw.received(hello);
        call.a->on_written(hello.size(), call.now);
        const Bytes flight = raw.output();
        CHECK(!flight.empty());
        if (variant == 0) call.a->signals().cancel.store(true);
        if (variant == 1) call.now += seconds(10);
        call.a->on_received(flight, call.now);
        const CallSnapshot a = call.a->snapshot();
        CHECK(a.state == CallState::Closed &&
              a.result == (variant == 0 ? Status::Canceled : Status::Timeout));
        CHECK(a.frames_sent == 0U && call.a->output(call.now).empty());
    }
}

void test_accept_after_cancel(const CryptoContext& crypto) {
    {
        // The initiator already sent ACCEPT. A canceled attempt must not
        // authorize media when the responder's valid ACCEPT arrives.
        RawResponder peer(crypto);
        const auto select = profile_body(1);
        peer.after([&](RawPeer& raw) {
            raw.send(FrameType::Select, select);
            raw.send(FrameType::Auth,
                     peer.bob_signs(Role::Responder, raw.tls.exporter(),
                                    peer.call.context()));
        });
        CHECK(peer.call.a->snapshot().peer_authenticated);
        CHECK(peer.call.a->snapshot().state == CallState::Authenticating);
        const RawPeer::Owned* accept = peer.raw->last(FrameType::Accept);
        CHECK(accept != nullptr);
        peer.raw->send(FrameType::Accept, accept->payload);
        const Bytes records = peer.raw->output();
        peer.call.a->signals().cancel.store(true);
        peer.call.a->on_received(records, peer.call.now);
        CHECK(peer.call.a->snapshot().state == CallState::Closing);
        peer.call.wire.pump(peer.call.now);
        CHECK(peer.call.a->closed() &&
              peer.call.a->snapshot().result == Status::Canceled);
    }
    {
        // The responder consented and sent its proof. Cancellation before
        // the initiator's ACCEPT must prevent both its own ACCEPT and media.
        Call call(crypto);
        call.invite();
        call.wire.engines[0] = nullptr;
        call.relay.raw[0] = true;
        call.relay.pending[0].clear();
        RawPeer raw(crypto, TlsRole::Client, call.parsed.invitation_id,
                    call.parsed.psk);
        call.wire.raws[0] = &raw;
        call.join();
        raw.start();
        call.wire.pump(call.now);
        CHECK(raw.established);
        raw.send(FrameType::Offer, profile_body(1));
        Signature alice_signature{};
        CHECK(call.alice.store->sign(
                  auth_message(Role::Initiator, raw.tls.exporter(),
                               call.context()),
                  alice_signature) == Status::Ok);
        raw.send(FrameType::Auth, alice_signature);
        call.wire.pump(call.now);
        CHECK(call.b->snapshot().state == CallState::AwaitingAcceptance);
        CHECK(call.b->decide(true, call.now) == Status::Ok);
        call.wire.pump(call.now);
        const RawPeer::Owned* proof = raw.last(FrameType::Auth);
        CHECK(proof != nullptr && raw.last(FrameType::Accept) == nullptr);
        Signature bob_signature{};
        std::copy(proof->payload.begin(), proof->payload.end(),
                  bob_signature.begin());
        const Hash confirm =
            accept_body(crypto, call.context(), alice_signature, bob_signature);
        raw.send(FrameType::Accept, confirm);
        const Bytes records = raw.output();
        call.b->signals().cancel.store(true);
        call.b->on_received(records, call.now);
        CHECK(call.b->snapshot().state == CallState::Closing);
        call.wire.pump(call.now);
        CHECK(call.b->closed() &&
              call.b->snapshot().result == Status::Canceled);
        CHECK(raw.last(FrameType::Accept) == nullptr);
    }
}

void clock_checkpoint_preserves(const CryptoContext& crypto,
                                const Bytes& before, const Bytes& after) {
    StoreState expected;
    CHECK(decode_snapshot(crypto, before, expected) == Status::Ok);
    expected.clock_high_water = kUncertainClock;
    const SecretBytes encoded = encode_snapshot(expected);
    CHECK(std::equal(encoded.view().begin(), encoded.view().end(),
                     after.begin(), after.end()));
}

void test_clock_admission_and_restoration(const CryptoContext& crypto) {
    for (bool reload : {false, true}) {
        Call call(crypto);
        call.invite();
        ClockAdmission observed;
        CHECK(call.bob.store->admit_clock(kUnix0 + 121, kT0 + seconds(121),
                                          observed) == Status::Ok);
        const auto saved = call.bob.disk;
        const auto contacts = call.bob.store->contacts();
        if (reload) call.bob.reopen(crypto);
        std::unique_ptr<OpenedInvitation> opened;
        // This invitation really expired before the observed clock rolled
        // back. Neither a fresh nor reloaded store may revive its unspent ID.
        CHECK(open_invitation(*call.bob.store, call.invitation, authority(),
                              kUnix0 + 1, kT0 + seconds(122),
                              opened) == Status::ClockRegressed);
        CHECK(opened == nullptr);
        clock_checkpoint_preserves(crypto, saved, call.bob.disk);
        CHECK(call.bob.store->contacts() == contacts);
        Key own{};
        CHECK(call.bob.store->identity(own) == Status::Ok &&
              own == call.bob.key);
        CHECK(call.bob.store->check_unspent(call.parsed.invitation_id,
                                            call.parsed.expires_at) ==
              Status::Ok);
        CHECK(call.relay.tap[1].empty());
    }
    {
        Call call(crypto);
        call.a.reset();
        call.wire.engines[0] = nullptr;
        const auto saved = call.alice.disk;
        CHECK(CallEngine::create_initiator(*call.alice.store, call.bob.key,
                                           authority(), 60, kUnix0 - 1, kT0,
                                           call.a) == Status::ClockRegressed);
        CHECK(call.a == nullptr);
        clock_checkpoint_preserves(crypto, saved, call.alice.disk);
        CHECK(call.relay.tap[0].empty());
        call.alice.reopen(crypto);
        CHECK(CallEngine::create_initiator(*call.alice.store, call.bob.key,
                                           authority(), 60, kUnix0 + 100, kT0,
                                           call.a) == Status::ClockRegressed);
    }
    for (int variant = 0; variant < 3; ++variant) {
        Call call(crypto);
        call.invite();
        std::unique_ptr<OpenedInvitation> opened;
        CHECK(call.open(call.bob, opened) == Status::Ok);
        const auto saved = call.bob.disk;
        const TimePoint observed = variant == 0   ? kT0 + seconds(1)
                                   : variant == 1 ? kT0 - seconds(1)
                                                  : kT0 + seconds(120);
        const std::uint64_t wall = variant == 0   ? kUnix0 - 1
                                   : variant == 1 ? kUnix0
                                                  : kUnix0 + 120;
        const Status expected =
            variant == 2 ? Status::Expired : Status::ClockRegressed;
        CHECK(CallEngine::create_responder(*call.bob.store, opened, authority(),
                                           wall, observed, call.b) == expected);
        CHECK(opened != nullptr && call.b == nullptr);
        if (variant == 2)
            CHECK(call.bob.disk == saved);
        else
            clock_checkpoint_preserves(crypto, saved, call.bob.disk);
        CHECK(call.relay.tap[1].empty());
    }
    {
        Call call(crypto);
        call.invite();
        std::unique_ptr<OpenedInvitation> opened;
        CHECK(call.open(call.bob, opened) == Status::Ok);
        // A platform's next integer-second sample can advance within one
        // monotonic second. Handoff must still keep the original deadline.
        CHECK(CallEngine::create_responder(
                  *call.bob.store, opened, authority(), kUnix0 + 1,
                  kT0 + std::chrono::milliseconds(900), call.b) == Status::Ok);
        call.b->start(kT0 + seconds(120));
        CHECK(call.b->snapshot().result == Status::Expired);
        CHECK(call.bob.store->check_unspent(call.parsed.invitation_id,
                                            call.parsed.expires_at) ==
              Status::Ok);
        CHECK(call.b->output(kT0 + seconds(120)).empty());
    }
    {
        Call call(crypto, 1);
        call.now += std::chrono::milliseconds(500);
        call.invite();
        // Rounded-up issue time is not a witnessed wall time. An immediate
        // ordinary next call at the same raw second must remain possible.
        call.a.reset();
        call.wire.engines[0] = nullptr;
        CHECK(CallEngine::create_initiator(*call.alice.store, call.bob.key,
                                           authority(), 1, kUnix0, call.now,
                                           call.a) == Status::Ok);
        StoreState state;
        CHECK(decode_snapshot(crypto, call.alice.disk, state) == Status::Ok);
        CHECK(state.clock_high_water == kUnix0);
    }
    {
        Call call(crypto);
        const auto saved = call.alice.disk;
        call.a->start(kT0 - seconds(1));
        CHECK(call.a->snapshot().result == Status::ClockRegressed);
        CHECK(call.alice.disk == saved && call.a->output(kT0).empty());
    }
}

void test_establishment_frame_rechecks(const CryptoContext& crypto) {
    // A cancel, or the authentication deadline, that no tick has observed yet
    // still stops the initiator before it answers SELECT with its proof.
    const auto select = profile_body(1);
    for (int variant = 0; variant < 2; ++variant) {
        RawResponder peer(crypto);
        peer.raw->send(FrameType::Select, select);
        const Bytes records = peer.raw->output();
        if (variant == 0) peer.call.a->signals().cancel.store(true);
        if (variant == 1) peer.call.now += seconds(40);
        peer.call.a->on_received(records, peer.call.now);
        peer.call.wire.pump(peer.call.now);
        const CallSnapshot a = peer.call.a->snapshot();
        CHECK(a.state == CallState::Closed &&
              a.result == (variant == 0 ? Status::Canceled : Status::Timeout));
        CHECK(peer.raw->last(FrameType::Auth) == nullptr);
    }
}

}  // namespace

int main(int argc, char** argv) {
    const CryptoContext crypto;
    if (argc > 1) {
        CHECK(argc == 2);
        if (std::string_view(argv[1]) == "--buffered-eof") {
            test_buffered_eof(crypto);
            std::puts("buffered EOF engine tests passed");
            return 0;
        }
        if (std::string_view(argv[1]) == "--handshake-budget") {
            test_handshake_byte_budget(crypto);
            std::puts("handshake byte budget tests passed");
            return 0;
        }
        if (std::string_view(argv[1]) == "--hello-retry") {
            test_hello_retry_limit(crypto);
            std::puts("hello retry tests passed");
            return 0;
        }
        CHECK(false);
    }
    // First, so a control that lets production negotiate PSK without DHE
    // fails here rather than in a later test.
    test_no_dhe_server(crypto);
    test_full_call(crypto);
    test_decline_and_consent_timeout(crypto);
    test_budgets(crypto);
    test_invitation_refusals(crypto);
    test_invitation_time_rules();
    test_raw_responder(crypto);
    test_raw_initiator(crypto);
    test_policy_in_engine(crypto);
    test_cancel_each_state(crypto);
    test_transition_rechecks(crypto);
    test_authority_and_key_changes(crypto);
    test_transport_failures(crypto);
    test_media_limits(crypto);
    test_receive_byte_cap(crypto);
    test_peer_ticket(crypto);
    test_hello_retry_limit(crypto);
    test_reserve_timeout(crypto);
    test_establishment_input_rechecks(crypto);
    test_accept_after_cancel(crypto);
    test_establishment_frame_rechecks(crypto);
    test_clock_admission_and_restoration(crypto);
    test_buffered_eof(crypto);
    std::puts("engine tests passed");
    return 0;
}
