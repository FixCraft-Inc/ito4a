/* Copyright (C) 2026 FixCraft Inc. AGPL-3.0-or-later. */

// Invitation policy: what makes a received invitation acceptable, and how an
// initiator builds and signs one. The codec owns the encoding. The store owns
// trust and spent state. Nothing here consumes an invitation.

#pragma once

#include <cstdint>
#include <memory>

#include "codec.hpp"
#include "status.hpp"
#include "store.hpp"

namespace ito::call {

// A received invitation that passed every check of docs/CALL_SECURITY.md
// except spending. It holds the PSK and one of the store's pending slots,
// which it returns when destroyed.
class OpenedInvitation final {
public:
    OpenedInvitation(Store& store, Invitation&& invitation, const Hash& hash,
                     TimePoint deadline);
    OpenedInvitation(const OpenedInvitation&) = delete;
    OpenedInvitation& operator=(const OpenedInvitation&) = delete;
    ~OpenedInvitation();

    [[nodiscard]] const Invitation& invitation() const noexcept {
        return invitation_;
    }
    [[nodiscard]] const Hash& hash() const noexcept { return hash_; }
    [[nodiscard]] TimePoint deadline() const noexcept { return deadline_; }
    // Wipes the PSK, the encoded invitation and the room token once TLS no
    // longer needs them.
    // The non-secret fields stay for the call context.
    void forget_secrets() noexcept;

private:
    Store& store_;
    Invitation invitation_;
    Hash hash_{};
    TimePoint deadline_;
};

// The time rules: 1 <= expires - issued <= 600, issued <= now + 60 and
// now < expires, with arithmetic checked before it is done.
[[nodiscard]] Status check_invitation_time(std::uint64_t issued_at,
                                           std::uint64_t expires_at,
                                           std::uint64_t now) noexcept;

// Cheap checks first (structure, clock, time, keys, authority), then the
// signature and spent state. Admission checkpoints time independently. On
// success out holds the invitation, its original deadline and a pending slot.
[[nodiscard]] Status open_invitation(Store& store, ByteView encoded,
                                     const Authority& expected,
                                     std::uint64_t now, TimePoint observed,
                                     std::unique_ptr<OpenedInvitation>& out);

// Fills id, PSK, issued/expiry and the signature of an initiator's
// invitation whose room, token, keys and authority are already set.
[[nodiscard]] Status sign_new_invitation(Store& store, Invitation& invitation,
                                         const ClockAdmission& admission,
                                         TimePoint observed,
                                         std::uint64_t lifetime,
                                         std::uint16_t profile = 1U);

}  // namespace ito::call
