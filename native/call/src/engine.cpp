/* Copyright (C) 2026 FixCraft Inc. AGPL-3.0-or-later. */

#include "engine.hpp"

#include <algorithm>
#include <array>
#include <limits>
#include <vector>

namespace ito::call {
namespace {

// TLS adds a header and an AEAD tag to each record, and a 16397-byte frame
// spans two records. This bounds the ciphertext one DATA frame can add.
constexpr std::size_t kFrameCiphertextOverhead = 2U * (5U + 1U + 16U);
constexpr std::size_t kOutputChunk = 16U * 1024U;

[[nodiscard]] TimePoint earlier(TimePoint a, std::optional<TimePoint> b) {
    return b ? std::min(a, *b) : a;
}

void keep_earliest(std::optional<TimePoint>& result,
                   const std::optional<TimePoint>& candidate) {
    if (candidate && (!result || *candidate < *result)) result = candidate;
}

[[nodiscard]] bool valid_end_reason(std::uint8_t value) noexcept {
    return value >= static_cast<std::uint8_t>(EndReason::Canceled) &&
           value <= static_cast<std::uint8_t>(EndReason::ResourceLimit);
}

}  // namespace

CallEngine::CallEngine(Store& store, Role role, const Authority& authority,
                       const ClockAdmission& admission,
                       const EngineOptions& options)
    : store_(store),
      role_(role),
      authority_(authority),
      options_(options),
      clock_admission_(admission) {
    store_.add_borrower();
}

CallEngine::~CallEngine() {
    wipe_secrets();
    if (attempt_registered_) store_.unregister_attempt();
    if (pending_held_) store_.release_pending();
    tls_.reset();
    for (SecretBytes& payload : received_) payload.clear();
    store_.remove_borrower();
}

Status CallEngine::create_initiator(Store& store, const Key& peer,
                                    const Authority& authority,
                                    std::uint32_t lifetime_seconds,
                                    std::uint64_t now_unix, TimePoint created,
                                    std::unique_ptr<CallEngine>& out,
                                    const EngineOptions& options) {
    if ((options.profile != kProfile && options.profile != kContentProfile) ||
        lifetime_seconds < kMinInvitationLifetime ||
        lifetime_seconds > kMaxInvitationLifetime ||
        now_unix >
            std::numeric_limits<std::uint64_t>::max() - lifetime_seconds ||
        !valid_service_name(authority.service)) {
        return Status::InvalidArgument;
    }
    Key own{};
    if (store.identity(own) != Status::Ok) return Status::WrongState;
    if (peer == own || !store.is_verified(peer)) return Status::Untrusted;
    ClockAdmission admission;
    Status status = store.admit_clock(now_unix, created, admission);
    if (status != Status::Ok) return status;
    std::unique_ptr<CallEngine> engine(
        new CallEngine(store, Role::Initiator, authority, admission, options));
    engine->own_key_ = own;
    engine->peer_key_ = peer;
    engine->lifetime_seconds_ = lifetime_seconds;
    status = store.acquire_pending();
    if (status != Status::Ok) return status;
    engine->pending_held_ = true;
    status = store.register_attempt(peer, &engine->signals_.peer_revoked);
    if (status != Status::Ok) return status;
    engine->attempt_registered_ = true;
    out = std::move(engine);
    return Status::Ok;
}

Status CallEngine::create_responder(
    Store& store, std::unique_ptr<OpenedInvitation>& invitation,
    const Authority& authority, std::uint64_t now_unix, TimePoint created,
    std::unique_ptr<CallEngine>& out, const EngineOptions& options) {
    if (invitation == nullptr) return Status::InvalidArgument;
    const Invitation& opened = invitation->invitation();
    // The transport's authenticated service must be the one invited to.
    if (!(opened.authority == authority)) return Status::AuthorityMismatch;
    Key own{};
    if (store.identity(own) != Status::Ok) return Status::WrongState;
    if (opened.responder != own || !store.is_verified(opened.initiator)) {
        return Status::Untrusted;
    }
    const Status clock = store.check_clock(now_unix, created);
    if (clock != Status::Ok) return clock;
    if (created >= invitation->deadline()) return Status::Expired;
    const Status time =
        check_invitation_time(opened.issued_at, opened.expires_at, now_unix);
    if (time != Status::Ok) return time;
    ClockAdmission admission;
    const Status admitted = store.admit_clock(now_unix, created, admission);
    if (admitted != Status::Ok) return admitted;
    std::unique_ptr<CallEngine> engine(
        new CallEngine(store, Role::Responder, authority, admission, options));
    engine->own_key_ = own;
    engine->peer_key_ = opened.initiator;
    engine->invitation_deadline_ = invitation->deadline();
    const Status status = store.register_attempt(
        opened.initiator, &engine->signals_.peer_revoked);
    if (status != Status::Ok) return status;
    engine->attempt_registered_ = true;
    engine->invitation_hash_ = invitation->hash();
    // The invitation and its pending slot now belong to the engine.
    engine->opened_ = std::move(invitation);
    out = std::move(engine);
    return Status::Ok;
}

Status CallEngine::wall_now(TimePoint now,
                            std::uint64_t& upper) const noexcept {
    std::uint64_t lower = 0U;
    return clock_admission_.project(now, lower, upper);
}

TimePoint CallEngine::establishment_deadline() const noexcept {
    return invitation_deadline_.value_or(TimePoint::max());
}

bool CallEngine::tls_established() const noexcept {
    return tls_ != nullptr && handshake_done_;
}

std::size_t CallEngine::queued_output() const noexcept {
    return (out_.size() - out_offset_) +
           (tls_ != nullptr ? tls_->output_pending() : 0U);
}

void CallEngine::note_output(TimePoint now) noexcept {
    if (!transport_ended_ && state_ != CallState::Closed &&
        !write_waiting_since_ && queued_output() != 0U) {
        write_waiting_since_ = now;
    }
}

void CallEngine::start(TimePoint now) {
    if (state_ != CallState::Idle) return;
    state_ = CallState::Preparing;
    // A cancel, authority loss or block before the run spends nothing.
    if (signal_refused(now)) return;
    if (role_ == Role::Initiator) {
        start_initiator(now);
    } else {
        start_responder(now);
    }
    note_output(now);
}

void CallEngine::start_initiator(TimePoint now) {
    requested_ms_ = lifetime_seconds_ * 1000U;
    out_.append(encode_reserve(requested_ms_));
    reserve_deadline_ = now + kReserveBudget;
    reserve_sent_ = true;
}

void CallEngine::start_responder(TimePoint now) {
    const Invitation& invitation = opened_->invitation();
    std::uint64_t wall = 0U;
    const Status projected = wall_now(now, wall);
    if (projected != Status::Ok) return fail(projected, std::nullopt, now);
    const Status time = check_invitation_time(invitation.issued_at,
                                              invitation.expires_at, wall);
    if (time != Status::Ok) return fail(time, std::nullopt, now);
    if (!store_.is_verified(invitation.initiator)) {
        return fail(Status::Untrusted, std::nullopt, now);
    }
    // Spent durably before the first JOIN. Any later failure keeps it spent.
    const Status spent =
        store_.spend({invitation.invitation_id, invitation_hash_,
                      invitation.expires_at, Role::Responder},
                     clock_admission_, now);
    if (spent != Status::Ok) return fail(spent, std::nullopt, now);
    invitation_deadline_ = std::min(
        *invitation_deadline_, now + Seconds(invitation.expires_at - wall));
    std::vector<Byte> join = encode_join(invitation.room_id, Role::Responder,
                                         invitation.responder_token);
    const WipeOnExit wiped(join);
    out_.append(join);
    join_sent_ = true;
}

void CallEngine::tick(TimePoint now) {
    if (state_ == CallState::Idle || state_ == CallState::Closed) return;
    if (state_ == CallState::Closing) {
        // A second cancellation abandons the flush.
        if (signals_.cancel.load(std::memory_order_acquire) &&
            result_ != Status::Canceled) {
            return finish_closed();
        }
        if (queued_output() == 0U || now >= *close_deadline_) finish_closed();
        return;
    }
    if (write_waiting_since_ &&
        now - *write_waiting_since_ >= kWriteStallTimeout) {
        // Nothing more can be written, so nothing is queued.
        result_ = Status::Timeout;
        state_ = CallState::Closing;
        return finish_closed();
    }
    if (signal_refused(now)) return;
    if (establishment_expired(now)) return;
    if (media_deadline_ && now >= *media_deadline_) {
        return fail(Status::Expired, EndReason::Expired, now);
    }
    if (!transport_ended_ && state_ == CallState::MediaAuthorized) {
        maybe_update_keys(now);
    }
    // Records that backpressure left undecrypted resume once the
    // application has drained the receive queue.
    if (tls_established() && state_ != CallState::Closing &&
        received_bytes_ + kMaxDataBytes <= kMaxReceivedData) {
        read_frames(now);
    }
    note_output(now);
}

std::size_t CallEngine::receive_capacity() const noexcept {
    if (transport_ended_) return 0U;
    switch (state_) {
        case CallState::Idle:
        case CallState::Closing:
        case CallState::Closed:
            return 0U;
        case CallState::Preparing:
        case CallState::Joined:
        case CallState::Handshaking:
        case CallState::Authenticating:
        case CallState::AwaitingAcceptance:
        case CallState::MediaAuthorized:
            break;
    }
    if (!paired_) {
        return 4U + kMaxRelayBody -
               std::min(relay_in_.size(), std::size_t{4U + kMaxRelayBody});
    }
    const std::size_t pending = tls_->inbound_pending();
    std::size_t capacity =
        kMaxNetworkQueue - std::min(pending, kMaxNetworkQueue);
    if (!handshake_done_) {
        // One byte past the budget is enough to refuse it.
        const std::size_t budget =
            kMaxHandshakeInbound + 1U -
            std::min(handshake_inbound_, kMaxHandshakeInbound + 1U);
        capacity = std::min(capacity, budget);
    }
    return capacity;
}

void CallEngine::on_received(ByteView bytes, TimePoint now) {
    if (transport_ended_ || bytes.empty() || state_ == CallState::Idle ||
        state_ == CallState::Closing || state_ == CallState::Closed) {
        return;
    }
    // Relay replies and handshake completion can sign, spend or send just
    // like an application frame. Recheck before acting on this read batch.
    if (state_ != CallState::MediaAuthorized &&
        (signal_refused(now) || establishment_expired(now))) {
        return;
    }
    if (!paired_) {
        on_relay_bytes(bytes, now);
    } else {
        on_tls_bytes(bytes, now);
    }
    note_output(now);
}

void CallEngine::on_transport_end(bool eof, TimePoint now) {
    transport_ended_ = true;
    if (state_ == CallState::Closed || state_ == CallState::Idle) return;
    if (eof && state_ != CallState::Closing && tls_established()) {
        // Native EOF follows the last accepted network byte, not the last
        // decrypted frame. Application backpressure may still hide DATA/END
        // in TLS or the assembler. Keep that bounded input until reads free
        // space, without admitting more output or extending any deadline.
        out_.clear();
        out_offset_ = 0U;
        write_waiting_since_.reset();
        tick(now);
        return;
    }
    if (state_ != CallState::Closing) {
        result_ = Status::TransportFailed;
        state_ = CallState::Closing;
    }
    finish_closed();
}

void CallEngine::on_relay_bytes(ByteView bytes, TimePoint now) {
    relay_in_.append(bytes);
    while (!paired_ && state_ != CallState::Closing &&
           state_ != CallState::Closed) {
        RelayReply reply;
        std::size_t consumed = 0U;
        switch (parse_relay_reply(relay_in_.view(), reply, consumed)) {
            case ParseProgress::Incomplete:
                return;
            case ParseProgress::Malformed:
                return fail(Status::ProtocolFailure, std::nullopt, now);
            case ParseProgress::Complete:
                break;
        }
        SecretBytes rest(relay_in_.view().subspan(consumed));
        relay_in_ = std::move(rest);
        on_relay_reply(reply, now);
    }
    // Bytes after PAIRED are the peer's first TLS records.
    if (paired_ && !relay_in_.empty() && state_ != CallState::Closing &&
        state_ != CallState::Closed) {
        SecretBytes first(relay_in_.view());
        relay_in_.clear();
        on_tls_bytes(first.view(), now);
    }
}

void CallEngine::on_relay_reply(const RelayReply& reply, TimePoint now) {
    if (reply.type == RelayType::Refused) {
        relay_refusal_ = reply.refusal;
        return fail(Status::RelayRefused, std::nullopt, now);
    }
    const bool awaiting_reserved =
        role_ == Role::Initiator && reserve_sent_ && !join_sent_;
    if (reply.type == RelayType::Reserved && awaiting_reserved) {
        return on_reserved(reply, now);
    }
    if (reply.type == RelayType::Waiting && join_sent_ &&
        state_ == CallState::Preparing) {
        state_ = CallState::Joined;
        if (role_ == Role::Initiator) invitation_ready_ = true;
        return;
    }
    if (reply.type == RelayType::Paired && state_ == CallState::Joined) {
        return on_paired(now);
    }
    fail(Status::ProtocolFailure, std::nullopt, now);
}

void CallEngine::on_reserved(const RelayReply& reply, TimePoint now) {
    if (reply.granted_ms < 1000U || reply.granted_ms > requested_ms_) {
        return fail(Status::ProtocolFailure, EndReason::Canceled, now);
    }
    reserve_deadline_.reset();
    std::uint64_t wall = 0U;
    const Status projected = wall_now(now, wall);
    if (projected != Status::Ok) {
        return fail(projected, EndReason::Canceled, now);
    }
    const std::uint64_t lifetime =
        std::min<std::uint64_t>(lifetime_seconds_, reply.granted_ms / 1000U);
    invitation_.room_id = reply.room;
    invitation_.responder_token = reply.responder_token;
    initiator_token_ = reply.initiator_token;
    invitation_.initiator = own_key_;
    invitation_.responder = peer_key_;
    invitation_.authority = authority_;
    if (!store_.is_verified(peer_key_)) {
        return fail(Status::Untrusted, EndReason::Canceled, now);
    }
    Status status = sign_new_invitation(store_, invitation_, clock_admission_,
                                        now, lifetime, options_.profile);
    if (status != Status::Ok) return fail(status, EndReason::Canceled, now);
    const std::array<ByteView, 1> whole{invitation_.encoded.view()};
    invitation_hash_ = store_.crypto().sha256(whole);
    // Spent durably before the first JOIN, like the responder's copy.
    status = store_.spend({invitation_.invitation_id, invitation_hash_,
                           invitation_.expires_at, Role::Initiator},
                          clock_admission_, now);
    if (status != Status::Ok) return fail(status, EndReason::Canceled, now);
    invitation_deadline_ = now + Seconds(invitation_.expires_at - wall);
    std::vector<Byte> join =
        encode_join(invitation_.room_id, Role::Initiator, initiator_token_);
    const WipeOnExit wiped(join);
    out_.append(join);
    join_sent_ = true;
#if defined(ITO_CALL_EVIDENCE)
    evidence_.invitation.assign(invitation_.encoded.view().begin(),
                                invitation_.encoded.view().end());
    evidence_.psk.assign(invitation_.psk.view().begin(),
                         invitation_.psk.view().end());
#endif
}

void CallEngine::on_paired(TimePoint now) {
    paired_ = true;
    // The invitation was for the responder to join. It is no longer shown.
    invitation_ready_ = false;
    state_ = CallState::Handshaking;
    handshake_deadline_ = now + kHandshakeBudget;
    if (invitation_deadline_) {
        handshake_deadline_ =
            std::min(*handshake_deadline_, *invitation_deadline_);
    }
    const Invitation& invitation =
        role_ == Role::Initiator ? invitation_ : opened_->invitation();
#if defined(ITO_CALL_EVIDENCE)
    if (role_ == Role::Responder) {
        evidence_.invitation.assign(invitation.encoded.view().begin(),
                                    invitation.encoded.view().end());
        evidence_.psk.assign(invitation.psk.view().begin(),
                             invitation.psk.view().end());
    }
#endif
    // The PSK identity is the invitation ID, never a key or token.
    tls_ = std::make_unique<TlsChannel>(
        store_.crypto(),
        role_ == Role::Initiator ? TlsRole::Client : TlsRole::Server,
        invitation.invitation_id, invitation.psk, options_.tls);
    continue_handshake(now);
}

void CallEngine::on_tls_bytes(ByteView bytes, TimePoint now) {
    if (!handshake_done_) {
        handshake_inbound_ += bytes.size();
        if (handshake_inbound_ > kMaxHandshakeInbound) {
            return fail(Status::ProtocolFailure, std::nullopt, now);
        }
    }
    tls_->put_inbound(bytes);
    if (!handshake_done_) {
        continue_handshake(now);
    } else {
        read_frames(now);
    }
}

void CallEngine::continue_handshake(TimePoint now) {
    switch (tls_->handshake()) {
        case TlsChannel::Step::Failed:
            // OpenSSL enforces the HRR limit. Only that rejection changes
            // the call's typed result; other auth failures keep their status.
            return fail(tls_->state().hello_retry_repeated
                            ? Status::ProtocolFailure
                            : Status::AuthenticationFailed,
                        std::nullopt, now);
        case TlsChannel::Step::Pending:
            if (tls_->output_pending() > kMaxPendingTlsOutput) {
                fail(Status::ProtocolFailure, std::nullopt, now);
            }
            return;
        case TlsChannel::Step::Complete:
            break;
    }
    if (!tls_->policy_satisfied()) {
        return fail(Status::AuthenticationFailed, std::nullopt, now);
    }
    handshake_done_ = true;
    handshake_deadline_.reset();
    exporter_ = tls_->exporter();
    // The handshake was the PSK's only use. Wipe the core's copies now.
    // OpenSSL's session keeps its own until the channel is freed at Closed.
    tls_->forget_psk();
    if (role_ == Role::Initiator) {
        invitation_.psk = Psk{};
        invitation_.encoded.clear();
    } else {
        opened_->forget_secrets();
    }
    state_ = CallState::Authenticating;
    authentication_deadline_ =
        earlier(now + kAuthenticationBudget, invitation_deadline_);
    last_key_update_ = now;
#if defined(ITO_CALL_EVIDENCE)
    evidence_.exporter.assign(exporter_.begin(), exporter_.end());
#endif
    if (role_ == Role::Initiator) {
        const auto body = profile_body(invitation_.profile);
        send_frame(FrameType::Offer, body);
        offer_done_ = true;
    }
    read_frames(now);
}

void CallEngine::read_frames(TimePoint now) {
    while (state_ != CallState::Closing && state_ != CallState::Closed) {
        if (const std::optional<Frame> frame = assembler_.frame()) {
            on_frame(*frame, now);
            assembler_.take();
            continue;
        }
        // Apply backpressure before decrypting a DATA frame that might not
        // fit in the receive queue.
        if (received_bytes_ + kMaxDataBytes > kMaxReceivedData) break;
        const std::span<Byte> space = assembler_.space();
        if (space.empty()) {
            return fail(Status::ProtocolFailure, EndReason::ProtocolFailure,
                        now);
        }
        std::size_t size = 0U;
        const TlsChannel::ReadResult result = tls_->read(space, size);
        if (result == TlsChannel::ReadResult::Data) {
            assembler_.commit(size);
            if (assembler_.malformed()) {
                return fail(Status::ProtocolFailure, EndReason::ProtocolFailure,
                            now);
            }
            // A ticket or KeyUpdate flood read with this data is refused
            // before any frame that arrived with it is acted on.
            if (tls_->state().ticket_seen) {
                return fail(Status::ProtocolFailure, EndReason::ProtocolFailure,
                            now);
            }
            check_peer_key_updates(now);
            continue;
        }
        if (result == TlsChannel::ReadResult::WantRead) {
            if (transport_ended_) {
                // No future read can complete this input. EOF cannot stand
                // in for the peer's authenticated END.
                return fail(assembler_.partial() ? Status::ProtocolFailure
                                                 : Status::TransportFailed,
                            std::nullopt, now);
            }
            break;
        }
        if (result == TlsChannel::ReadResult::Closed) {
            // close_notify without END: the peer stopped, for no stated
            // reason. A partial frame before it is a protocol failure.
            return fail(assembler_.partial() ? Status::ProtocolFailure
                                             : Status::PeerEnded,
                        std::nullopt, now);
        }
        return fail(Status::ProtocolFailure, std::nullopt, now);
    }
    if (state_ == CallState::Closing || state_ == CallState::Closed) return;
    if (tls_->state().ticket_seen) {
        return fail(Status::ProtocolFailure, EndReason::ProtocolFailure, now);
    }
    check_peer_key_updates(now);
}

void CallEngine::on_frame(const Frame& frame, TimePoint now) {
    ++frames_received_;
    if (state_ != CallState::MediaAuthorized) {
        if (++frames_before_media_ > kMaxFramesBeforeMedia) {
            return fail(Status::ProtocolFailure, EndReason::ProtocolFailure,
                        now);
        }
        // An establishment frame can make this side answer with SELECT, a
        // proof or a confirmation, so the transition rechecks first.
        if (signal_refused(now) || establishment_expired(now)) return;
    }
    // Sequences start at zero, increase by exactly one and never wrap.
    if (frame.sequence != receive_sequence_ ||
        receive_sequence_ == std::numeric_limits<std::uint64_t>::max()) {
        return fail(Status::ProtocolFailure, EndReason::ProtocolFailure, now);
    }
    ++receive_sequence_;
    const bool initiator = role_ == Role::Initiator;
    switch (frame.type) {
        case FrameType::End:
            return on_end(frame, now);
        case FrameType::Offer:
            if (!initiator && frame.sequence == 0U) return on_offer(frame, now);
            break;
        case FrameType::Select:
            if (initiator && frame.sequence == 0U) return on_select(frame, now);
            break;
        case FrameType::Auth:
            if (frame.sequence == 1U) return on_auth(frame, now);
            break;
        case FrameType::Accept:
            if (frame.sequence == 2U) return on_accept(frame, now);
            break;
        case FrameType::Data:
            if (state_ == CallState::MediaAuthorized)
                return on_data(frame, now);
            break;
    }
    fail(Status::ProtocolFailure, EndReason::ProtocolFailure, now);
}

void CallEngine::on_offer(const Frame& frame, TimePoint now) {
    const auto expected = profile_body(opened_->invitation().profile);
    if (!std::equal(frame.payload.begin(), frame.payload.end(),
                    expected.begin(), expected.end())) {
        // The OFFER must match the signed invitation, without downgrade.
        return fail(Status::Unsupported, EndReason::ProtocolFailure, now);
    }
    send_frame(FrameType::Select, expected);
    context_hash_ =
        context_hash(store_.crypto(), invitation_hash_, opened_->invitation(),
                     frame.payload, expected);
    offer_done_ = true;
}

void CallEngine::on_select(const Frame& frame, TimePoint now) {
    const auto expected = profile_body(invitation_.profile);
    if (!offer_done_ || !std::equal(frame.payload.begin(), frame.payload.end(),
                                    expected.begin(), expected.end())) {
        return fail(Status::Unsupported, EndReason::ProtocolFailure, now);
    }
    context_hash_ = context_hash(store_.crypto(), invitation_hash_, invitation_,
                                 expected, frame.payload);
    // The initiator proves its identity first.
    send_own_auth(now);
}

void CallEngine::on_auth(const Frame& frame, TimePoint now) {
    const bool initiator = role_ == Role::Initiator;
    // The initiator's AUTH precedes the responder's, and each arrives once.
    if (peer_auth_verified_ || !offer_done_ || (initiator && !own_auth_sent_) ||
        (!initiator && own_auth_sent_)) {
        return fail(Status::ProtocolFailure, EndReason::ProtocolFailure, now);
    }
    std::copy(frame.payload.begin(), frame.payload.end(),
              peer_signature_.begin());
    const Role signer = initiator ? Role::Responder : Role::Initiator;
    AuthMessage message = auth_message(signer, exporter_, context_hash_);
    // Verified against the pinned key. The PSK never authorizes another one.
    const Verification verified =
        store_.crypto().verify(peer_key_, message, peer_signature_);
    wipe(message.data(), message.size());  // it contains the exporter
    if (verified != Verification::Valid) {
        return fail(Status::AuthenticationFailed, EndReason::ProtocolFailure,
                    now);
    }
    peer_auth_verified_ = true;
#if defined(ITO_CALL_EVIDENCE)
    evidence_.peer_signature.assign(peer_signature_.begin(),
                                    peer_signature_.end());
#endif
    if (initiator) {
        accept_body_ = accept_body(store_.crypto(), context_hash_,
                                   own_signature_, peer_signature_);
        send_frame(FrameType::Accept, accept_body_);
        own_accept_sent_ = true;
        return;
    }
    // The responder asks its user about the now verified contact.
    state_ = CallState::AwaitingAcceptance;
    consent_pending_ = true;
    consent_deadline_ = earlier(now + kConsentBudget, authentication_deadline_);
}

void CallEngine::on_accept(const Frame& frame, TimePoint now) {
    const bool initiator = role_ == Role::Initiator;
    if (!peer_auth_verified_ || !own_auth_sent_ ||
        (initiator && !own_accept_sent_) || (!initiator && own_accept_sent_)) {
        return fail(Status::ProtocolFailure, EndReason::ProtocolFailure, now);
    }
    if (!initiator) {
        accept_body_ = accept_body(store_.crypto(), context_hash_,
                                   peer_signature_, own_signature_);
    }
    if (!constant_time_equal(frame.payload, accept_body_)) {
        return fail(Status::AuthenticationFailed, EndReason::ProtocolFailure,
                    now);
    }
    // on_frame rechecked the signals and deadlines before the responder's
    // ACCEPT goes out and before media is authorized.
    if (!initiator) {
        send_frame(FrameType::Accept, accept_body_);
        own_accept_sent_ = true;
    }
    authorize_media(now);
}

bool CallEngine::signal_refused(TimePoint now) {
    if (signals_.cancel.load(std::memory_order_acquire)) {
        fail(Status::Canceled, EndReason::Canceled, now);
        return true;
    }
    if (signals_.authority_lost.load(std::memory_order_acquire)) {
        fail(Status::AuthorityEnded, EndReason::AuthorityEnded, now);
        return true;
    }
    if (signals_.peer_revoked.load(std::memory_order_acquire)) {
        fail(Status::Untrusted, EndReason::Canceled, now);
        return true;
    }
    std::uint64_t lower = 0U;
    std::uint64_t upper = 0U;
    const Status clock = clock_admission_.project(now, lower, upper);
    if (clock != Status::Ok) {
        fail(clock, EndReason::Canceled, now);
        return true;
    }
    return false;
}

bool CallEngine::establishment_expired(TimePoint now) {
    if (reserve_deadline_ && now >= *reserve_deadline_) {
        fail(Status::Timeout, EndReason::Canceled, now);
        return true;
    }
    if (state_ != CallState::MediaAuthorized && invitation_deadline_ &&
        now >= *invitation_deadline_) {
        fail(Status::Expired, EndReason::Expired, now);
        return true;
    }
    if (consent_deadline_ && now >= *consent_deadline_) {
        fail(Status::Timeout, EndReason::Expired, now);
        return true;
    }
    if (authentication_deadline_ && now >= *authentication_deadline_) {
        fail(Status::Timeout, EndReason::Expired, now);
        return true;
    }
    if (handshake_deadline_ && now >= *handshake_deadline_) {
        fail(Status::Timeout, std::nullopt, now);
        return true;
    }
    return false;
}

void CallEngine::authorize_media(TimePoint now) {
    // on_frame has rechecked the signals and deadlines.
    if (!store_.is_verified(peer_key_)) {
        return fail(Status::Untrusted, EndReason::Canceled, now);
    }
    if (state_ == CallState::Closing || state_ == CallState::Closed) return;
    state_ = CallState::MediaAuthorized;
    // Invitation expiry gated establishment only. The call limits apply now.
    invitation_deadline_.reset();
    authentication_deadline_.reset();
    consent_deadline_.reset();
    media_deadline_ = now + kMaxCallDuration;
    last_key_update_ = now;
}

void CallEngine::on_data(const Frame& frame, TimePoint now) {
    data_received_ += frame.payload.size();
    if (data_received_ > kMaxCallBytesPerDirection) {
        return fail(Status::ResourceLimit, EndReason::ResourceLimit, now);
    }
    received_.emplace_back(frame.payload);
    received_bytes_ += frame.payload.size();
}

void CallEngine::on_end(const Frame& frame, TimePoint now) {
    const std::uint8_t reason = frame.payload[0];
    if (!valid_end_reason(reason)) {
        return fail(Status::ProtocolFailure, std::nullopt, now);
    }
    remote_end_ = reason;
    // Before the peer's proof was verified, END proves only possession of
    // the invitation PSK. It is not attributed to the contact.
    remote_end_attributed_ = peer_auth_verified_;
    fail(Status::PeerEnded, std::nullopt, now);
}

void CallEngine::send_frame(FrameType type, ByteView payload) {
    SecretBytes frame;
    frame.reserve(kFrameHeaderBytes + payload.size());
    encode_frame(frame, type, send_sequence_++, payload);
    if (!tls_->write(frame.view())) throw CryptoFailure{};
    ++frames_sent_;
}

void CallEngine::send_own_auth(TimePoint now) {
    AuthMessage message = auth_message(role_, exporter_, context_hash_);
    const Status status = store_.sign(message, own_signature_);
    wipe(message.data(), message.size());  // it contains the exporter
    if (status != Status::Ok) {
        return fail(status, EndReason::ProtocolFailure, now);
    }
    send_frame(FrameType::Auth, own_signature_);
    own_auth_sent_ = true;
#if defined(ITO_CALL_EVIDENCE)
    evidence_.own_signature.assign(own_signature_.begin(),
                                   own_signature_.end());
#endif
}

void CallEngine::check_peer_key_updates(TimePoint now) {
    const std::uint32_t seen = tls_->state().peer_key_updates;
    while (peer_key_updates_seen_ < seen) {
        peer_key_update_times_.push_back(now);
        ++peer_key_updates_seen_;
    }
    while (!peer_key_update_times_.empty() &&
           now - peer_key_update_times_.front() >= Seconds(60)) {
        peer_key_update_times_.pop_front();
    }
    if (peer_key_update_times_.size() > kMaxPeerKeyUpdatesPerMinute) {
        fail(Status::ProtocolFailure, EndReason::ProtocolFailure, now);
    }
}

void CallEngine::maybe_update_keys(TimePoint now) {
    if (sent_since_key_update_ < kKeyUpdateBytes &&
        now - last_key_update_ < kKeyUpdateInterval) {
        return;
    }
    if (!tls_->update_sending_key()) {
        return fail(Status::ProtocolFailure, std::nullopt, now);
    }
    ++key_updates_sent_;
    sent_since_key_update_ = 0U;
    last_key_update_ = now;
}

void CallEngine::fail(Status result, std::optional<EndReason> end,
                      TimePoint now) {
    if (state_ == CallState::Closing || state_ == CallState::Closed) return;
    result_ = result;
    const bool established = tls_established();
    if (established && end && !transport_ended_) {
        // An authenticated statement inside TLS, then close_notify.
        const std::array<Byte, 1> reason{static_cast<Byte>(*end)};
        try {
            send_frame(FrameType::End, reason);
            local_end_ = static_cast<std::uint8_t>(*end);
            tls_->close();
        } catch (const CryptoFailure&) {
            // The channel is broken. Nothing more can be said on it.
        }
    } else if (!transport_ended_ && !paired_ && end &&
               (reserve_sent_ || join_sent_)) {
        // Before pairing, CANCEL frees the room at the relay.
        out_.append(encode_cancel());
    }
    state_ = CallState::Closing;
    consent_pending_ = false;
    invitation_ready_ = false;
    reserve_deadline_.reset();
    invitation_deadline_.reset();
    handshake_deadline_.reset();
    authentication_deadline_.reset();
    consent_deadline_.reset();
    media_deadline_.reset();
    close_deadline_ = now + kCloseFlushBudget;
    if (transport_ended_ || queued_output() == 0U) finish_closed();
}

void CallEngine::abandon(Status result) noexcept {
    if (state_ == CallState::Closed) return;
    if (state_ != CallState::Closing) result_ = result;
    finish_closed();
}

void CallEngine::finish_closed() noexcept {
    state_ = CallState::Closed;
    wipe_secrets();
    // TLS and signing state go before the transport is closed by the owner.
    tls_.reset();
    out_.clear();
    out_offset_ = 0U;
    write_waiting_since_.reset();
    close_deadline_.reset();
    if (attempt_registered_) {
        store_.unregister_attempt();
        attempt_registered_ = false;
    }
    if (pending_held_) {
        store_.release_pending();
        pending_held_ = false;
    }
}

void CallEngine::wipe_secrets() noexcept {
#if defined(ITO_CALL_EVIDENCE)
    try {
        if (tls_ != nullptr) evidence_.key_log = tls_->state().key_log;
    } catch (...) {
    }
#endif
    invitation_.psk = Psk{};
    invitation_.encoded.clear();
    // Releases the received invitation and its pending slot.
    opened_.reset();
    wipe(initiator_token_.data(), initiator_token_.size());
    wipe(invitation_.responder_token.data(),
         invitation_.responder_token.size());
    wipe(exporter_.data(), exporter_.size());
    wipe(context_hash_.data(), context_hash_.size());
    wipe(own_signature_.data(), own_signature_.size());
    wipe(peer_signature_.data(), peer_signature_.size());
    wipe(accept_body_.data(), accept_body_.size());
    relay_in_.clear();
    assembler_.reset();
}

ByteView CallEngine::output(TimePoint now) {
    if (transport_ended_ || state_ == CallState::Idle ||
        state_ == CallState::Closed)
        return {};
    if (out_offset_ == out_.size() && tls_ != nullptr &&
        tls_->output_pending() != 0U) {
        out_.clear();
        out_offset_ = 0U;
        std::array<Byte, kOutputChunk> chunk{};
        const std::size_t taken = tls_->take_output(chunk);
        out_.append(ByteView(chunk.data(), taken));
        wipe(chunk.data(), taken);
    }
    const ByteView pending = out_.view().subspan(out_offset_);
    if (!pending.empty() && !write_waiting_since_) write_waiting_since_ = now;
    return pending;
}

void CallEngine::on_written(std::size_t count, TimePoint now) {
    out_offset_ += std::min(count, out_.size() - out_offset_);
    if (out_offset_ == out_.size()) {
        out_.clear();
        out_offset_ = 0U;
    }
    if (queued_output() == 0U) {
        write_waiting_since_.reset();
    } else if (count != 0U) {
        write_waiting_since_ = now;
    }
    if (state_ == CallState::Closing && queued_output() == 0U) finish_closed();
}

std::optional<TimePoint> CallEngine::next_deadline() const noexcept {
    std::optional<TimePoint> result;
    keep_earliest(result, reserve_deadline_);
    keep_earliest(result, invitation_deadline_);
    keep_earliest(result, handshake_deadline_);
    keep_earliest(result, authentication_deadline_);
    keep_earliest(result, consent_deadline_);
    keep_earliest(result, media_deadline_);
    keep_earliest(result, close_deadline_);
    if (write_waiting_since_) {
        keep_earliest(result, *write_waiting_since_ + kWriteStallTimeout);
    }
    if (!transport_ended_ && state_ == CallState::MediaAuthorized) {
        keep_earliest(result, last_key_update_ + kKeyUpdateInterval);
    }
    return result;
}

Status CallEngine::decide(bool accept, TimePoint now) {
    if (state_ != CallState::AwaitingAcceptance || !consent_pending_) {
        return Status::WrongState;
    }
    // A decision after the consent budget, or after a cancel, is too late.
    if (signal_refused(now) || establishment_expired(now)) {
        return Status::WrongState;
    }
    consent_pending_ = false;
    consent_deadline_.reset();
    if (!accept) {
        fail(Status::Declined, EndReason::Declined, now);
        return Status::Ok;
    }
    if (!store_.is_verified(peer_key_)) {
        fail(Status::Untrusted, EndReason::Canceled, now);
        return Status::Untrusted;
    }
    send_own_auth(now);
    note_output(now);
    return Status::Ok;
}

Status CallEngine::send(ByteView data, TimePoint now) {
    if (transport_ended_ || state_ != CallState::MediaAuthorized) {
        return Status::WrongState;
    }
    if (data.empty() || data.size() > kMaxDataBytes) {
        return Status::InvalidArgument;
    }
    if (data_sent_ + data.size() > kMaxCallBytesPerDirection) {
        fail(Status::ResourceLimit, EndReason::ResourceLimit, now);
        return Status::ResourceLimit;
    }
    const std::size_t frame = kFrameHeaderBytes + data.size();
    if (queued_output() + frame + kFrameCiphertextOverhead > kMaxNetworkQueue) {
        return Status::WouldBlock;
    }
    send_frame(FrameType::Data, data);
    data_sent_ += data.size();
    sent_since_key_update_ += frame;
    maybe_update_keys(now);
    note_output(now);
    return Status::Ok;
}

Status CallEngine::receive(std::span<Byte> out, std::size_t& size) {
    size = 0U;
    if (received_.empty()) {
        return state_ == CallState::Closed ? Status::WrongState
                                           : Status::WouldBlock;
    }
    SecretBytes& front = received_.front();
    if (out.size() < front.size()) {
        size = front.size();
        return Status::BufferTooSmall;
    }
    std::copy(front.view().begin(), front.view().end(), out.begin());
    size = front.size();
    received_bytes_ -= front.size();
    received_.pop_front();
    return Status::Ok;
}

Status CallEngine::hangup(TimePoint now) {
    if (state_ == CallState::Idle) {
        // Before the run starts, a hangup is a cancel: start() spends nothing.
        signals_.cancel.store(true, std::memory_order_release);
        return Status::Ok;
    }
    if (state_ == CallState::Closing || state_ == CallState::Closed) {
        return Status::WrongState;
    }
    fail(Status::Canceled, EndReason::Canceled, now);
    return Status::Ok;
}

Status CallEngine::copy_invitation(std::span<Byte> out,
                                   std::size_t& size) const {
    size = 0U;
    if (role_ != Role::Initiator || !invitation_ready_) {
        return Status::WrongState;
    }
    const ByteView encoded = invitation_.encoded.view();
    size = encoded.size();
    if (out.size() < encoded.size()) return Status::BufferTooSmall;
    std::copy(encoded.begin(), encoded.end(), out.begin());
    return Status::Ok;
}

CallSnapshot CallEngine::snapshot() const {
    CallSnapshot result;
    result.state = state_;
    result.result = state_ == CallState::Closed ? result_ : Status::Ok;
    result.initiator = role_ == Role::Initiator;
    result.invitation_ready = invitation_ready_;
    result.peer_authenticated = peer_auth_verified_;
    result.consent_pending = consent_pending_;
    result.remote_end_attributed = remote_end_attributed_;
    result.local_end = local_end_;
    result.remote_end = remote_end_;
    result.relay_refusal = relay_refusal_;
    result.peer = peer_key_;
    result.data_sent = data_sent_;
    result.data_received = data_received_;
    result.frames_sent = frames_sent_;
    result.frames_received = frames_received_;
    result.key_updates_sent = key_updates_sent_;
    result.key_updates_received = peer_key_updates_seen_;
    return result;
}

}  // namespace ito::call
