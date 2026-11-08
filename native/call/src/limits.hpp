/* Copyright (C) 2026 FixCraft Inc. AGPL-3.0-or-later. */

// Every numeric bound of docs/CALL_SECURITY.md and the relay's control
// framing (docs/CALL_RELAY.md), in one place. Code names these constants
// instead of repeating numbers, and the tests exercise each at its edge.

#pragma once

#include <chrono>
#include <cstddef>
#include <cstdint>

namespace ito::call {

using Milliseconds = std::chrono::milliseconds;
using Seconds = std::chrono::seconds;

// Keys, hashes and signatures.
inline constexpr std::size_t kKeyBytes = 32U;
inline constexpr std::size_t kHashBytes = 32U;
inline constexpr std::size_t kSignatureBytes = 64U;
inline constexpr std::size_t kPskBytes = 32U;
inline constexpr std::size_t kIdBytes = 32U;

// Contacts and the local store.
inline constexpr std::size_t kMaxContacts = 256U;
inline constexpr std::size_t kMaxNameBytes = 128U;
inline constexpr std::size_t kMaxSnapshotBytes = 1024U * 1024U;
inline constexpr std::size_t kMaxSpentInvitations = 1024U;
inline constexpr std::size_t kMaxPendingInvitations = 8U;
// A spent ID is kept until its invitation's expiry plus this margin.
inline constexpr std::uint64_t kSpentRetentionSeconds = 60U;

// Invitations.
inline constexpr std::size_t kMaxInvitationBytes = 512U;
inline constexpr std::uint64_t kMinInvitationLifetime = 1U;
inline constexpr std::uint64_t kMaxInvitationLifetime = 600U;
// issued_at may lead the verifier's clock by at most this much.
inline constexpr std::uint64_t kMaxIssuedSkew = 60U;
inline constexpr std::size_t kMaxServiceBytes = 64U;
inline constexpr std::uint16_t kProfile = 1U;
inline constexpr std::uint16_t kContentProfile = 2U;

// Relay control framing (docs/CALL_RELAY.md).
inline constexpr std::uint32_t kMaxRelayBody = 4096U;
inline constexpr std::uint16_t kRelayVersion = 1U;
// RESERVED must arrive within this budget after the stream opens.
inline constexpr Milliseconds kReserveBudget{15'000};

// Application frames inside TLS.
inline constexpr std::size_t kFrameHeaderBytes = 4U + 1U + 8U;
inline constexpr std::uint32_t kMinFrameBody = 9U;
inline constexpr std::size_t kMaxDataBytes = 16384U;
inline constexpr std::uint32_t kMaxFrameBody = 9U + kMaxDataBytes;
inline constexpr std::size_t kMaxFrameBytes = 4U + kMaxFrameBody;
static_assert(kMaxFrameBytes == 16397U);
inline constexpr std::size_t kMaxFramesBeforeMedia = 64U;

// Establishment budgets. All are also capped by invitation lifetime.
inline constexpr Milliseconds kHandshakeBudget{10'000};
inline constexpr std::size_t kMaxHandshakeInbound = 256U * 1024U;
inline constexpr std::size_t kMaxPendingTlsOutput = 64U * 1024U;
inline constexpr Milliseconds kAuthenticationBudget{40'000};
inline constexpr Milliseconds kConsentBudget{30'000};

// Queues and stalls.
inline constexpr std::size_t kMaxNetworkQueue = 64U * 1024U;
inline constexpr std::size_t kMaxReceivedData = 64U * 1024U;
inline constexpr Milliseconds kWriteStallTimeout{5'000};
// Bytes accepted before an end are flushed for at most this long.
inline constexpr Milliseconds kCloseFlushBudget{1'000};
// The END sender retains its transport for at most this long to let accepted
// writes progress. Peer transport end does not acknowledge END consumption.
inline constexpr Milliseconds kCloseLinger{5'000};

// Active call limits and TLS key lifetime.
inline constexpr Milliseconds kMaxCallDuration{30 * 60 * 1000};
inline constexpr std::uint64_t kMaxCallBytesPerDirection =
    64ULL * 1024U * 1024U;
inline constexpr std::uint64_t kKeyUpdateBytes = 16ULL * 1024U * 1024U;
inline constexpr Milliseconds kKeyUpdateInterval{10 * 60 * 1000};
inline constexpr std::size_t kMaxPeerKeyUpdatesPerMinute = 8U;

}  // namespace ito::call
