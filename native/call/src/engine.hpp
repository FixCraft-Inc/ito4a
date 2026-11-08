/* Copyright (C) 2026 FixCraft Inc. AGPL-3.0-or-later. */

// One call attempt as a serialized state machine with no I/O of its own.
// The driver feeds it network bytes and the time, and writes what it
// produces. The relay's control exchange, the TLS channel, frame order,
// identity proofs, consent, deadlines and quotas of docs/CALL_SECURITY.md
// all live here.
//
// The engine is not thread safe. Its owner (driver.hpp) serializes every
// call. Requests from other threads that must not wait for that lock
// (cancel, authority loss, a contact key change) arrive as atomic flags that
// the next tick observes.
//
// A failure never returns to an earlier state. Closing settles exactly once:
// it queues END (or CANCEL before pairing) when it can, flushes for a
// bounded time and then wipes every secret before Closed is published.

#pragma once

#include <atomic>
#include <chrono>
#include <cstddef>
#include <cstdint>
#include <deque>
#include <memory>
#include <optional>
#include <span>

#include "bytes.hpp"
#include "codec.hpp"
#include "invitation.hpp"
#include "status.hpp"
#include "store.hpp"
#include "tls.hpp"

namespace ito::call {

enum class CallState : std::uint32_t {
    Idle = 0,
    Preparing = 1,
    Joined = 2,
    Handshaking = 3,
    Authenticating = 4,
    AwaitingAcceptance = 5,
    MediaAuthorized = 6,
    Closing = 7,
    Closed = 8,
};

enum class EndReason : std::uint8_t {
    Canceled = 1,
    Declined = 2,
    Expired = 3,
    ProtocolFailure = 4,
    AuthorityEnded = 5,
    ResourceLimit = 6,
};

struct CallSnapshot final {
    CallState state{CallState::Idle};
    Status result{Status::Ok};
    bool initiator{false};
    bool invitation_ready{false};
    bool peer_authenticated{false};
    bool consent_pending{false};
    bool remote_end_attributed{false};
    std::uint8_t local_end{0U};
    std::uint8_t remote_end{0U};
    std::uint8_t relay_refusal{0U};
    Key peer{};
    std::uint64_t data_sent{0U};
    std::uint64_t data_received{0U};
    std::uint64_t frames_sent{0U};
    std::uint64_t frames_received{0U};
    std::uint32_t key_updates_sent{0U};
    std::uint32_t key_updates_received{0U};
};

// Requests from any thread. The engine reads them on its next tick.
struct CallSignals final {
    std::atomic<bool> cancel{false};
    std::atomic<bool> authority_lost{false};
    // Raised by the store when the peer's key is blocked or replaced.
    std::atomic<bool> peer_revoked{false};
};

// Explicit application capability and optional test TLS seams.
struct EngineOptions final {
    TlsOptions tls;
    std::uint16_t profile{1U};
};

class CallEngine final {
public:
    // The initiator: peer must be Verified. now_unix and created anchor the
    // wall clock admitted by the store, advanced only with monotonic time.
    [[nodiscard]] static Status create_initiator(
        Store& store, const Key& peer, const Authority& authority,
        std::uint32_t lifetime_seconds, std::uint64_t now_unix,
        TimePoint created, std::unique_ptr<CallEngine>& out,
        const EngineOptions& options = {});
    // The responder. On success the engine owns the invitation. On failure
    // it is left with the caller. Handoff cannot extend its original deadline.
    [[nodiscard]] static Status create_responder(
        Store& store, std::unique_ptr<OpenedInvitation>& invitation,
        const Authority& authority, std::uint64_t now_unix, TimePoint created,
        std::unique_ptr<CallEngine>& out, const EngineOptions& options = {});

    CallEngine(const CallEngine&) = delete;
    CallEngine& operator=(const CallEngine&) = delete;
    ~CallEngine();

    // Driver side.
    void start(TimePoint now);
    void tick(TimePoint now);
    // How many network bytes the engine accepts now.
    [[nodiscard]] std::size_t receive_capacity() const noexcept;
    void on_received(ByteView bytes, TimePoint now);
    // Orderly input EOF retains buffered TLS/frame input until application
    // reads release backpressure. Errors terminate immediately. Neither
    // outcome supplies an authenticated END.
    void on_transport_end(bool eof, TimePoint now);
    // Whether the platform transport has reported its end.
    [[nodiscard]] bool transport_ended() const noexcept {
        return transport_ended_;
    }
    // Network bytes ready to write, valid until the next engine call.
    [[nodiscard]] ByteView output(TimePoint now);
    void on_written(std::size_t count, TimePoint now);
    [[nodiscard]] std::optional<TimePoint> next_deadline() const noexcept;
    [[nodiscard]] bool closed() const noexcept {
        return state_ == CallState::Closed;
    }
    [[nodiscard]] bool started() const noexcept {
        return state_ != CallState::Idle;
    }
    // Ends the attempt at once after an exception escaped an engine call.
    // Nothing more is written. Secrets are wiped.
    void abandon(Status result) noexcept;
    // Before start: hands a responder's invitation back to the caller, who
    // could not take this engine. The engine is then only destroyed.
    [[nodiscard]] std::unique_ptr<OpenedInvitation>
    release_invitation() noexcept {
        return std::move(opened_);
    }

    // Application side.
    [[nodiscard]] Status decide(bool accept, TimePoint now);
    [[nodiscard]] Status send(ByteView data, TimePoint now);
    [[nodiscard]] Status receive(std::span<Byte> out, std::size_t& size);
    [[nodiscard]] Status hangup(TimePoint now);
    [[nodiscard]] Status copy_invitation(std::span<Byte> out,
                                         std::size_t& size) const;
    [[nodiscard]] CallSnapshot snapshot() const;
    [[nodiscard]] CallSignals& signals() noexcept { return signals_; }

#if defined(ITO_CALL_EVIDENCE)
    // Test evidence only: every secret value this attempt held.
    struct Evidence final {
        std::vector<Byte> psk;
        std::vector<Byte> invitation;
        std::vector<Byte> exporter;
        std::vector<Byte> own_signature;
        std::vector<Byte> peer_signature;
        std::vector<std::string> key_log;
    };
    [[nodiscard]] const Evidence& evidence() const noexcept {
        return evidence_;
    }
#endif

private:
    CallEngine(Store& store, Role role, const Authority& authority,
               const ClockAdmission& admission, const EngineOptions& options);

    [[nodiscard]] Status wall_now(TimePoint now,
                                  std::uint64_t& upper) const noexcept;
    void start_initiator(TimePoint now);
    void start_responder(TimePoint now);
    void on_relay_bytes(ByteView bytes, TimePoint now);
    void on_relay_reply(const RelayReply& reply, TimePoint now);
    void on_reserved(const RelayReply& reply, TimePoint now);
    void on_paired(TimePoint now);
    void on_tls_bytes(ByteView bytes, TimePoint now);
    void continue_handshake(TimePoint now);
    void read_frames(TimePoint now);
    void on_frame(const Frame& frame, TimePoint now);
    void on_offer(const Frame& frame, TimePoint now);
    void on_select(const Frame& frame, TimePoint now);
    void on_auth(const Frame& frame, TimePoint now);
    void on_accept(const Frame& frame, TimePoint now);
    void on_data(const Frame& frame, TimePoint now);
    void on_end(const Frame& frame, TimePoint now);
    void send_frame(FrameType type, ByteView payload);
    void send_own_auth(TimePoint now);
    void authorize_media(TimePoint now);
    // Every establishment transition repeats these checks before it acts:
    // cancellation, authority, peer trust and the establishment deadlines.
    // A refusal fails the attempt and returns true.
    [[nodiscard]] bool signal_refused(TimePoint now);
    [[nodiscard]] bool establishment_expired(TimePoint now);
    void check_peer_key_updates(TimePoint now);
    void maybe_update_keys(TimePoint now);
    // Ends the attempt: result is final, and end (or CANCEL before pairing)
    // goes out when the channel allows.
    void fail(Status result, std::optional<EndReason> end, TimePoint now);
    void finish_closed() noexcept;
    void wipe_secrets() noexcept;
    [[nodiscard]] bool tls_established() const noexcept;
    [[nodiscard]] std::size_t queued_output() const noexcept;
    // Starts the write-stall clock when output becomes pending.
    void note_output(TimePoint now) noexcept;
    [[nodiscard]] TimePoint establishment_deadline() const noexcept;

    Store& store_;
    const Role role_;
    Authority authority_;
    Key own_key_{};
    Key peer_key_{};
    EngineOptions options_;
    ClockAdmission clock_admission_;
    CallSignals signals_;
    bool attempt_registered_{false};
    bool pending_held_{false};

    CallState state_{CallState::Idle};
    Status result_{Status::Ok};
    std::uint32_t lifetime_seconds_{0U};

    // Invitation and its derived values. Secrets are wiped when Closed.
    Invitation invitation_;
    Hash invitation_hash_{};
    bool invitation_ready_{false};
    std::unique_ptr<OpenedInvitation> opened_;
    Id initiator_token_{};
    Hash context_hash_{};
    Hash exporter_{};
    Signature own_signature_{};
    Signature peer_signature_{};
    Hash accept_body_{};

    // Relay control phase.
    SecretBytes relay_in_;
    std::uint32_t requested_ms_{0U};
    bool reserve_sent_{false};
    bool join_sent_{false};
    bool transport_ended_{false};
    bool paired_{false};

    // TLS and frames.
    std::unique_ptr<TlsChannel> tls_;
    bool handshake_done_{false};
    std::size_t handshake_inbound_{0U};
    FrameAssembler assembler_;
    std::uint64_t send_sequence_{0U};
    std::uint64_t receive_sequence_{0U};
    std::size_t frames_before_media_{0U};
    bool offer_done_{false};
    bool own_auth_sent_{false};
    bool peer_auth_verified_{false};
    bool consent_pending_{false};
    bool own_accept_sent_{false};

    // Network output not yet written: relay frames or TLS records.
    SecretBytes out_;
    std::size_t out_offset_{0U};
    std::optional<TimePoint> write_waiting_since_;

    // Received DATA payloads, each a separate secret buffer.
    std::deque<SecretBytes> received_;
    std::size_t received_bytes_{0U};

    // Deadlines.
    std::optional<TimePoint> reserve_deadline_;
    std::optional<TimePoint> invitation_deadline_;
    std::optional<TimePoint> handshake_deadline_;
    std::optional<TimePoint> authentication_deadline_;
    std::optional<TimePoint> consent_deadline_;
    std::optional<TimePoint> media_deadline_;
    std::optional<TimePoint> close_deadline_;

    // Media accounting and key lifetime.
    std::uint64_t data_sent_{0U};
    std::uint64_t data_received_{0U};
    std::uint64_t frames_sent_{0U};
    std::uint64_t frames_received_{0U};
    std::uint64_t sent_since_key_update_{0U};
    TimePoint last_key_update_{};
    std::uint32_t key_updates_sent_{0U};
    std::uint32_t peer_key_updates_seen_{0U};
    std::deque<TimePoint> peer_key_update_times_;

    std::uint8_t local_end_{0U};
    std::uint8_t remote_end_{0U};
    bool remote_end_attributed_{false};
    std::uint8_t relay_refusal_{0U};

#if defined(ITO_CALL_EVIDENCE)
    Evidence evidence_;
#endif
};

}  // namespace ito::call
