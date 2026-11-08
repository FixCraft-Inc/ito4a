/* Copyright (C) 2026 FixCraft Inc. AGPL-3.0-or-later. */

// The local communication identity, contact trust and spent invitations of
// docs/CALL_SECURITY.md, and the exact plaintext snapshot that holds them.
// The platform seals and publishes each snapshot. This class decides what
// it contains and applies every policy.
//
// Each mutation builds a candidate state, encodes it, asks the platform to
// persist it, and only then makes it current. A refused persist leaves the
// previous state current. An ambiguous one latches the store: it refuses
// mutation until the platform reopens it from what storage actually holds.
//
// One mutex serializes all access. Persistence runs with it held, so the
// platform callback must not call back into the store.

#pragma once

#include <atomic>
#include <chrono>
#include <cstdint>
#include <functional>
#include <limits>
#include <memory>
#include <mutex>
#include <optional>
#include <string>
#include <string_view>
#include <vector>

#include "bytes.hpp"
#include "codec.hpp"
#include "crypto.hpp"
#include "status.hpp"

namespace ito::call {

using Clock = std::chrono::steady_clock;
using TimePoint = Clock::time_point;

// No finite-lifetime invitation can use this wall second. In the existing
// snapshot clock field it records detected uncertainty across store reopen.
inline constexpr std::uint64_t kUncertainClock =
    std::numeric_limits<std::uint64_t>::max();

class Store;

// A wall-clock observation admitted by one store. Its monotonic projection
// remains valid for that attempt, without treating a rounded deadline as a
// new wall-clock observation. It cannot be restored from a snapshot.
class ClockAdmission final {
public:
    ClockAdmission() = default;
    [[nodiscard]] Status project(TimePoint now, std::uint64_t& lower,
                                 std::uint64_t& upper) const noexcept;

private:
    friend class Store;
    const Store* owner_{nullptr};
    std::uint64_t wall_{0U};
    TimePoint observed_{};
};

enum class Trust : std::uint8_t { Unverified = 1, Verified = 2, Blocked = 3 };

struct Contact final {
    Key key{};
    Trust trust{Trust::Unverified};
    std::optional<Key> replaced_by;
    std::optional<Key> replaces;
    std::uint64_t added_at{0U};
    std::uint64_t verified_at{0U};
    std::string name;

    [[nodiscard]] bool operator==(const Contact&) const = default;
};

struct SpentInvitation final {
    Id invitation_id{};
    // SHA-256 of the complete signed invitation. Not secret.
    Hash invitation_hash{};
    std::uint64_t expires_at{0U};
    Role role{Role::Initiator};

    [[nodiscard]] bool operator==(const SpentInvitation&) const = default;
};

enum class PersistResult { Committed, NotCommitted, Ambiguous };

// The platform's sealing and atomic publication of one snapshot.
using Persist = std::function<PersistResult(ByteView snapshot)>;

// The contents of one snapshot.
struct StoreState final {
    bool has_identity{false};
    Seed seed;
    Key public_key{};
    std::uint64_t identity_created{0U};
    // A durable lower bound from admitted wall observations and their
    // monotonic projections, or kUncertainClock after detected rollback.
    // It refuses rollback, never proves current time or safe recovery.
    std::uint64_t clock_high_water{0U};
    // The latest expiry of any pruned received invitation. A received
    // invitation expiring no later than this may be a replay of one whose
    // record is gone, so it is refused.
    std::uint64_t pruned_through{0U};
    std::vector<Contact> contacts;       // ascending by key
    std::vector<SpentInvitation> spent;  // ascending by invitation ID

    StoreState() = default;
    StoreState(const StoreState&) = delete;
    StoreState& operator=(const StoreState&) = delete;
    void copy_from(const StoreState& other);
    // Publishes a committed candidate. It cannot fail.
    void take_from(StoreState&& other) noexcept;
};

inline constexpr std::string_view kSnapshotDomain{"ito/identity-snapshot/1\0",
                                                  24U};

[[nodiscard]] SecretBytes encode_snapshot(const StoreState& state);
// Parses and checks every invariant, including that the seed derives the
// stored public key. Malformed or inconsistent input is CorruptState.
[[nodiscard]] Status decode_snapshot(const CryptoContext& crypto,
                                     ByteView snapshot, StoreState& state);

class Store final {
public:
    // Opens a snapshot (empty for a new installation). Throws only on
    // allocation or dependency failure.
    [[nodiscard]] static Status open(const CryptoContext& crypto,
                                     ByteView snapshot, Persist persist,
                                     std::unique_ptr<Store>& out);
    Store(const Store&) = delete;
    Store& operator=(const Store&) = delete;

    [[nodiscard]] const CryptoContext& crypto() const noexcept {
        return crypto_;
    }

    [[nodiscard]] Status create_identity(std::uint64_t now);
    [[nodiscard]] Status identity(Key& key) const;

    [[nodiscard]] Status add_contact(const Key& key, std::string_view name,
                                     std::uint64_t now);
    [[nodiscard]] Status verify_contact(const Key& key,
                                        std::string_view compared,
                                        std::uint64_t now);
    [[nodiscard]] Status block_contact(const Key& key);
    [[nodiscard]] Status rename_contact(const Key& key, std::string_view name);
    [[nodiscard]] Status replace_contact(const Key& old_key, const Key& new_key,
                                         std::uint64_t now);
    [[nodiscard]] std::vector<Contact> contacts() const;
    [[nodiscard]] std::optional<Contact> find_contact(const Key& key) const;
    [[nodiscard]] bool is_verified(const Key& key) const;

    // Checks a fresh platform wall observation against the durable floor and
    // the live monotonic anchor. Refusal checkpoints detected uncertainty.
    // Admission checkpoints the clock separately from identity/trust/replay
    // mutations, before invitation secrets are used.
    [[nodiscard]] Status check_clock(std::uint64_t now, TimePoint observed);
    [[nodiscard]] Status admit_clock(std::uint64_t now, TimePoint observed,
                                     ClockAdmission& out);
    // Advances an already admitted attempt's lower bound. Its upper rounding
    // is for expiry only and must never become a durable clock observation.
    [[nodiscard]] Status advance_clock(const ClockAdmission& admission,
                                       TimePoint now);

    // Checks a received invitation's freshness against the spent records:
    // AlreadySpent for a recorded ID, Expired when its record may have been
    // pruned. Does not mutate.
    [[nodiscard]] Status check_unspent(const Id& invitation_id,
                                       std::uint64_t expires_at) const;
    // Records the invitation as spent before its first JOIN, using a clock
    // admitted by this store. Capacity refuses rather than evicting a record.
    [[nodiscard]] Status spend(const SpentInvitation& record,
                               const ClockAdmission& admission, TimePoint now);

    // Signs with the identity seed, which never leaves the store.
    [[nodiscard]] Status sign(ByteView message, Signature& signature) const;

    // Pending invitations and attempts share one bound of eight.
    [[nodiscard]] Status acquire_pending();
    void release_pending() noexcept;

    // One active attempt per installation. stop is raised when the attempt's
    // peer key is blocked or replaced while it runs.
    [[nodiscard]] Status register_attempt(const Key& peer,
                                          std::atomic<bool>* stop);
    void unregister_attempt() noexcept;

    // Handles that borrow this store. Destruction is refused while any do.
    void add_borrower() noexcept;
    void remove_borrower() noexcept;
    [[nodiscard]] bool borrowed() const noexcept;

    [[nodiscard]] bool latched() const noexcept;

private:
    Store(const CryptoContext& crypto, Persist persist);

    // Encodes candidate, persists it and makes it current. Called with lock_
    // held.
    [[nodiscard]] Status commit(StoreState& candidate);
    [[nodiscard]] Status check_clock_locked(std::uint64_t now,
                                            TimePoint observed);
    [[nodiscard]] Status refuse_clock_locked();
    [[nodiscard]] Status project_clock(const ClockAdmission& admission,
                                       TimePoint now,
                                       std::uint64_t& lower) const;
    void stop_attempt_for(const Key& key) noexcept;
    [[nodiscard]] Contact* find(StoreState& state, const Key& key) noexcept;

    const CryptoContext& crypto_;
    Persist persist_;
    mutable std::mutex lock_;
    StoreState state_;
    bool latched_{false};
    // Set before persisting uncertainty can allocate or fail. A refused
    // publication cannot make this live store resume invitation use.
    bool clock_uncertain_{false};
    std::optional<ClockAdmission> clock_anchor_;
    std::optional<TimePoint> last_clock_observed_;
    std::size_t pending_{0U};
    std::size_t borrowers_{0U};
    std::optional<Key> attempt_peer_;
    std::atomic<bool>* attempt_stop_{nullptr};
};

}  // namespace ito::call
