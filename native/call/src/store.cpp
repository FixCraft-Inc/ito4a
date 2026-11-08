/* Copyright (C) 2026 FixCraft Inc. AGPL-3.0-or-later. */

#include "store.hpp"

#include <algorithm>
#include <limits>

namespace ito::call {
namespace {

constexpr std::uint8_t kHasReplacedBy = 1U;
constexpr std::uint8_t kHasReplaces = 2U;

[[nodiscard]] bool key_less(const Key& a, const Key& b) noexcept {
    return std::lexicographical_compare(a.begin(), a.end(), b.begin(), b.end());
}

[[nodiscard]] bool valid_trust(std::uint8_t value) noexcept {
    return value >= static_cast<std::uint8_t>(Trust::Unverified) &&
           value <= static_cast<std::uint8_t>(Trust::Blocked);
}

// Cross-record invariants that a sequence of valid mutations preserves.
[[nodiscard]] bool consistent(const StoreState& state) {
    const auto lookup = [&](const Key& key) -> const Contact* {
        const auto found =
            std::lower_bound(state.contacts.begin(), state.contacts.end(), key,
                             [](const Contact& contact, const Key& wanted) {
                                 return key_less(contact.key, wanted);
                             });
        return found != state.contacts.end() && found->key == key ? &*found
                                                                  : nullptr;
    };
    for (const Contact& contact : state.contacts) {
        if (state.has_identity && contact.key == state.public_key) return false;
        if ((contact.trust == Trust::Verified) != (contact.verified_at != 0U) &&
            contact.trust != Trust::Blocked) {
            return false;
        }
        if (contact.replaced_by) {
            const Contact* successor = lookup(*contact.replaced_by);
            if (contact.trust != Trust::Blocked || successor == nullptr ||
                successor->replaces != contact.key) {
                return false;
            }
        }
        if (contact.replaces) {
            const Contact* retired = lookup(*contact.replaces);
            if (retired == nullptr || retired->replaced_by != contact.key) {
                return false;
            }
        }
    }
    return true;
}

}  // namespace

void StoreState::copy_from(const StoreState& other) {
    has_identity = other.has_identity;
    seed.assign(other.seed.view());
    public_key = other.public_key;
    identity_created = other.identity_created;
    clock_high_water = other.clock_high_water;
    pruned_through = other.pruned_through;
    contacts = other.contacts;
    spent = other.spent;
}

void StoreState::take_from(StoreState&& other) noexcept {
    has_identity = other.has_identity;
    seed = std::move(other.seed);
    public_key = other.public_key;
    identity_created = other.identity_created;
    clock_high_water = other.clock_high_water;
    pruned_through = other.pruned_through;
    contacts = std::move(other.contacts);
    spent = std::move(other.spent);
}

SecretBytes encode_snapshot(const StoreState& state) {
    // The reservation covers the largest encoding, so the vector holding the
    // seed never reallocates and leaves an unwiped copy behind. Fixed part:
    // domain, identity flag, seed, key, three u64 and two u16 counts.
    constexpr std::size_t kFixedBytes =
        kSnapshotDomain.size() + 1U + 2U * kKeyBytes + 3U * 8U + 2U * 2U;
    // Key, trust, links flag, two linked keys, two u64, name length, name.
    constexpr std::size_t kContactBytes =
        3U * kKeyBytes + 2U + 2U * 8U + 1U + kMaxNameBytes;
    // ID, hash, expiry and role.
    constexpr std::size_t kSpentBytes = 2U * kKeyBytes + 8U + 1U;
    std::vector<Byte> out;
    const WipeOnExit wiped(out);
    out.reserve(kFixedBytes + state.contacts.size() * kContactBytes +
                state.spent.size() * kSpentBytes);
    Writer write(out);
    write.text(kSnapshotDomain);
    write.u8(state.has_identity ? 1U : 0U);
    if (state.has_identity) {
        write.bytes(state.seed.view());
        write.bytes(state.public_key);
        write.u64(state.identity_created);
    }
    write.u64(state.clock_high_water);
    write.u64(state.pruned_through);
    write.u16(static_cast<std::uint16_t>(state.contacts.size()));
    for (const Contact& contact : state.contacts) {
        write.bytes(contact.key);
        write.u8(static_cast<std::uint8_t>(contact.trust));
        write.u8(static_cast<std::uint8_t>(
            (contact.replaced_by ? kHasReplacedBy : 0U) |
            (contact.replaces ? kHasReplaces : 0U)));
        if (contact.replaced_by) write.bytes(*contact.replaced_by);
        if (contact.replaces) write.bytes(*contact.replaces);
        write.u64(contact.added_at);
        write.u64(contact.verified_at);
        write.u8(static_cast<std::uint8_t>(contact.name.size()));
        write.text(contact.name);
    }
    write.u16(static_cast<std::uint16_t>(state.spent.size()));
    for (const SpentInvitation& spent : state.spent) {
        write.bytes(spent.invitation_id);
        write.bytes(spent.invitation_hash);
        write.u64(spent.expires_at);
        write.u8(static_cast<std::uint8_t>(spent.role));
    }
    return SecretBytes(out);
}

Status decode_snapshot(const CryptoContext& crypto, ByteView snapshot,
                       StoreState& state) {
    if (snapshot.size() > kMaxSnapshotBytes) return Status::CorruptState;
    Reader in(snapshot);
    std::uint8_t has_identity = 0U;
    if (!in.literal(as_bytes(kSnapshotDomain)) || !in.u8(has_identity) ||
        has_identity > 1U) {
        return Status::CorruptState;
    }
    state.has_identity = has_identity == 1U;
    if (state.has_identity) {
        ByteView seed;
        if (!in.take(kKeyBytes, seed) || !in.fixed(state.public_key) ||
            !in.u64(state.identity_created)) {
            return Status::CorruptState;
        }
        state.seed.assign(seed.first<kKeyBytes>());
        if (crypto.public_key(state.seed) != state.public_key) {
            return Status::CorruptState;
        }
    }
    std::uint16_t contact_count = 0U;
    if (!in.u64(state.clock_high_water) || !in.u64(state.pruned_through) ||
        !in.u16(contact_count) || contact_count > kMaxContacts) {
        return Status::CorruptState;
    }
    state.contacts.clear();
    state.contacts.reserve(contact_count);
    for (std::uint16_t index = 0U; index < contact_count; ++index) {
        Contact contact;
        std::uint8_t trust = 0U;
        std::uint8_t flags = 0U;
        std::uint8_t name_size = 0U;
        ByteView name;
        if (!in.fixed(contact.key) || !in.u8(trust) || !valid_trust(trust) ||
            !in.u8(flags) || (flags & ~(kHasReplacedBy | kHasReplaces)) != 0U) {
            return Status::CorruptState;
        }
        contact.trust = static_cast<Trust>(trust);
        if ((flags & kHasReplacedBy) != 0U) {
            Key successor{};
            if (!in.fixed(successor)) return Status::CorruptState;
            contact.replaced_by = successor;
        }
        if ((flags & kHasReplaces) != 0U) {
            Key retired{};
            if (!in.fixed(retired)) return Status::CorruptState;
            contact.replaces = retired;
        }
        if (!in.u64(contact.added_at) || !in.u64(contact.verified_at) ||
            !in.u8(name_size) || !in.take(name_size, name)) {
            return Status::CorruptState;
        }
        contact.name.assign(reinterpret_cast<const char*>(name.data()),
                            name.size());
        if (!valid_contact_name(contact.name) ||
            (!state.contacts.empty() &&
             !key_less(state.contacts.back().key, contact.key))) {
            return Status::CorruptState;
        }
        state.contacts.push_back(std::move(contact));
    }
    std::uint16_t spent_count = 0U;
    if (!in.u16(spent_count) || spent_count > kMaxSpentInvitations) {
        return Status::CorruptState;
    }
    state.spent.clear();
    state.spent.reserve(spent_count);
    for (std::uint16_t index = 0U; index < spent_count; ++index) {
        SpentInvitation spent;
        std::uint8_t role = 0U;
        if (!in.fixed(spent.invitation_id) ||
            !in.fixed(spent.invitation_hash) || !in.u64(spent.expires_at) ||
            !in.u8(role) || role < 1U || role > 2U) {
            return Status::CorruptState;
        }
        spent.role = static_cast<Role>(role);
        if (!state.spent.empty() &&
            !key_less(state.spent.back().invitation_id, spent.invitation_id)) {
            return Status::CorruptState;
        }
        state.spent.push_back(spent);
    }
    if (!in.at_end() || !consistent(state)) return Status::CorruptState;
    return Status::Ok;
}

Status ClockAdmission::project(TimePoint now, std::uint64_t& lower,
                               std::uint64_t& upper) const noexcept {
    if (owner_ == nullptr) return Status::InvalidArgument;
    if (now < observed_) return Status::ClockRegressed;
    const auto start = observed_.time_since_epoch().count();
    const auto finish = now.time_since_epoch().count();
    if (start < 0 && finish > std::numeric_limits<Clock::rep>::max() + start) {
        return Status::InvalidArgument;
    }
    const auto elapsed = now - observed_;
    const auto whole = std::chrono::floor<std::chrono::seconds>(elapsed);
    const auto rounded = std::chrono::ceil<std::chrono::seconds>(elapsed);
    const auto seconds = static_cast<std::uint64_t>(rounded.count());
    if (wall_ >= kUncertainClock - seconds) {
        return Status::InvalidArgument;
    }
    lower = wall_ + static_cast<std::uint64_t>(whole.count());
    upper = wall_ + seconds;
    return Status::Ok;
}

Store::Store(const CryptoContext& crypto, Persist persist)
    : crypto_(crypto), persist_(std::move(persist)) {}

Status Store::open(const CryptoContext& crypto, ByteView snapshot,
                   Persist persist, std::unique_ptr<Store>& out) {
    std::unique_ptr<Store> store(new Store(crypto, std::move(persist)));
    if (!snapshot.empty()) {
        const Status decoded = decode_snapshot(crypto, snapshot, store->state_);
        if (decoded != Status::Ok) return decoded;
    }
    out = std::move(store);
    return Status::Ok;
}

Status Store::commit(StoreState& candidate) {
    if (latched_) return Status::StorageAmbiguous;
    // A previous uncertainty checkpoint may have been refused. Every later
    // successful write must retain that observation, including contact edits.
    if (clock_uncertain_) candidate.clock_high_water = kUncertainClock;
    SecretBytes encoded = encode_snapshot(candidate);
    if (encoded.size() > kMaxSnapshotBytes) return Status::Capacity;
    PersistResult result = PersistResult::Ambiguous;
    try {
        result = persist_(encoded.view());
    } catch (...) {
        // A callback that throws gives no reliable answer.
        result = PersistResult::Ambiguous;
    }
    switch (result) {
        case PersistResult::Committed:
            // Memory takes the published candidate in a step that cannot
            // fail, so it always matches what the platform holds.
            state_.take_from(std::move(candidate));
            return Status::Ok;
        case PersistResult::NotCommitted:
            return Status::StorageFailed;
        case PersistResult::Ambiguous:
            latched_ = true;
            return Status::StorageAmbiguous;
    }
    latched_ = true;
    return Status::StorageAmbiguous;
}

Contact* Store::find(StoreState& state, const Key& key) noexcept {
    const auto found =
        std::lower_bound(state.contacts.begin(), state.contacts.end(), key,
                         [](const Contact& contact, const Key& wanted) {
                             return key_less(contact.key, wanted);
                         });
    return found != state.contacts.end() && found->key == key ? &*found
                                                              : nullptr;
}

void Store::stop_attempt_for(const Key& key) noexcept {
    if (attempt_peer_ == key && attempt_stop_ != nullptr) {
        attempt_stop_->store(true, std::memory_order_release);
    }
}

Status Store::create_identity(std::uint64_t now) {
    std::lock_guard guard(lock_);
    if (state_.has_identity) return Status::WrongState;
    StoreState candidate;
    candidate.copy_from(state_);
    candidate.has_identity = true;
    crypto_.generate_identity(candidate.seed, candidate.public_key);
    candidate.identity_created = now;
    // A contact can never share the new key, but check rather than assume.
    if (find(candidate, candidate.public_key) != nullptr) {
        return Status::Internal;
    }
    return commit(candidate);
}

Status Store::identity(Key& key) const {
    std::lock_guard guard(lock_);
    if (!state_.has_identity) return Status::NotFound;
    key = state_.public_key;
    return Status::Ok;
}

Status Store::add_contact(const Key& key, std::string_view name,
                          std::uint64_t now) {
    if (!valid_contact_name(name)) return Status::InvalidArgument;
    std::lock_guard guard(lock_);
    if (state_.has_identity && key == state_.public_key) {
        return Status::InvalidArgument;
    }
    StoreState candidate;
    candidate.copy_from(state_);
    if (find(candidate, key) != nullptr) return Status::WrongState;
    if (candidate.contacts.size() >= kMaxContacts) return Status::Capacity;
    Contact contact;
    contact.key = key;
    contact.added_at = now;
    contact.name.assign(name);
    const auto position =
        std::lower_bound(candidate.contacts.begin(), candidate.contacts.end(),
                         key, [](const Contact& existing, const Key& wanted) {
                             return key_less(existing.key, wanted);
                         });
    candidate.contacts.insert(position, std::move(contact));
    return commit(candidate);
}

Status Store::verify_contact(const Key& key, std::string_view compared,
                             std::uint64_t now) {
    const Hash expected = fingerprint(crypto_, key);
    std::lock_guard guard(lock_);
    StoreState candidate;
    candidate.copy_from(state_);
    Contact* contact = find(candidate, key);
    if (contact == nullptr) return Status::NotFound;
    if (contact->trust == Trust::Blocked) return Status::Untrusted;
    if (!fingerprint_matches(compared, expected)) {
        return Status::AuthenticationFailed;
    }
    if (contact->trust == Trust::Verified) return Status::Ok;
    contact->trust = Trust::Verified;
    contact->verified_at = now == 0U ? 1U : now;
    return commit(candidate);
}

Status Store::block_contact(const Key& key) {
    std::lock_guard guard(lock_);
    StoreState candidate;
    candidate.copy_from(state_);
    Contact* contact = find(candidate, key);
    if (contact == nullptr) return Status::NotFound;
    if (contact->trust == Trust::Blocked) return Status::Ok;
    contact->trust = Trust::Blocked;
    // Stop an attempt even when persistence fails or throws.
    stop_attempt_for(key);
    return commit(candidate);
}

Status Store::rename_contact(const Key& key, std::string_view name) {
    if (!valid_contact_name(name)) return Status::InvalidArgument;
    std::lock_guard guard(lock_);
    StoreState candidate;
    candidate.copy_from(state_);
    Contact* contact = find(candidate, key);
    if (contact == nullptr) return Status::NotFound;
    contact->name.assign(name);
    return commit(candidate);
}

Status Store::replace_contact(const Key& old_key, const Key& new_key,
                              std::uint64_t now) {
    std::lock_guard guard(lock_);
    if (old_key == new_key ||
        (state_.has_identity && new_key == state_.public_key)) {
        return Status::InvalidArgument;
    }
    StoreState candidate;
    candidate.copy_from(state_);
    Contact* retired = find(candidate, old_key);
    if (retired == nullptr) return Status::NotFound;
    if (retired->replaced_by || find(candidate, new_key) != nullptr) {
        return Status::WrongState;
    }
    if (candidate.contacts.size() >= kMaxContacts) return Status::Capacity;
    retired->trust = Trust::Blocked;
    retired->replaced_by = new_key;
    Contact successor;
    successor.key = new_key;
    successor.replaces = old_key;
    successor.added_at = now;
    successor.name = retired->name;
    const auto position = std::lower_bound(
        candidate.contacts.begin(), candidate.contacts.end(), new_key,
        [](const Contact& existing, const Key& wanted) {
            return key_less(existing.key, wanted);
        });
    candidate.contacts.insert(position, std::move(successor));
    // Stop an attempt even when persistence fails or throws.
    stop_attempt_for(old_key);
    return commit(candidate);
}

std::vector<Contact> Store::contacts() const {
    std::lock_guard guard(lock_);
    return state_.contacts;
}

std::optional<Contact> Store::find_contact(const Key& key) const {
    std::lock_guard guard(lock_);
    for (const Contact& contact : state_.contacts) {
        if (contact.key == key) return contact;
    }
    return std::nullopt;
}

bool Store::is_verified(const Key& key) const {
    std::lock_guard guard(lock_);
    for (const Contact& contact : state_.contacts) {
        if (contact.key == key) return contact.trust == Trust::Verified;
    }
    return false;
}

Status Store::check_unspent(const Id& invitation_id,
                            std::uint64_t expires_at) const {
    std::lock_guard guard(lock_);
    for (const SpentInvitation& spent : state_.spent) {
        if (spent.invitation_id == invitation_id) return Status::AlreadySpent;
    }
    if (expires_at <= state_.pruned_through) return Status::Expired;
    return Status::Ok;
}

Status Store::refuse_clock_locked() {
    clock_uncertain_ = true;
    if (state_.clock_high_water == kUncertainClock)
        return Status::ClockRegressed;
    StoreState candidate;
    candidate.copy_from(state_);
    candidate.clock_high_water = kUncertainClock;
    const Status saved = commit(candidate);
    return saved == Status::Ok ? Status::ClockRegressed : saved;
}

Status Store::check_clock_locked(std::uint64_t now, TimePoint observed) {
    if (latched_) return Status::StorageAmbiguous;
    if (clock_uncertain_ || state_.clock_high_water == kUncertainClock) {
        return refuse_clock_locked();
    }
    if (now == kUncertainClock) return Status::InvalidArgument;
    if (now < state_.clock_high_water) return refuse_clock_locked();
    if (last_clock_observed_ && observed < *last_clock_observed_) {
        return refuse_clock_locked();
    }
    if (clock_anchor_) {
        std::uint64_t lower = 0U;
        std::uint64_t upper = 0U;
        const Status status = clock_anchor_->project(observed, lower, upper);
        if (status == Status::ClockRegressed) return refuse_clock_locked();
        if (status != Status::Ok) return status;
        if (now < lower) return refuse_clock_locked();
    }
    return Status::Ok;
}

Status Store::check_clock(std::uint64_t now, TimePoint observed) {
    std::lock_guard guard(lock_);
    return check_clock_locked(now, observed);
}

Status Store::admit_clock(std::uint64_t now, TimePoint observed,
                          ClockAdmission& out) {
    std::lock_guard guard(lock_);
    const Status checked = check_clock_locked(now, observed);
    if (checked != Status::Ok) return checked;
    if (now > state_.clock_high_water) {
        StoreState candidate;
        candidate.copy_from(state_);
        candidate.clock_high_water = now;
        const Status saved = commit(candidate);
        if (saved != Status::Ok) return saved;
    }
    ClockAdmission admitted;
    admitted.owner_ = this;
    admitted.wall_ = now;
    admitted.observed_ = observed;
    if (!clock_anchor_) {
        clock_anchor_ = admitted;
    } else {
        std::uint64_t lower = 0U;
        std::uint64_t upper = 0U;
        const Status projected = clock_anchor_->project(observed, lower, upper);
        if (projected != Status::Ok) return projected;
        // Equal integer-second samples must not restart elapsed time and
        // disguise a stopped clock. A forward correction establishes a new
        // anchor only when it exceeds the old projection's rounding interval.
        if (now > upper) clock_anchor_ = admitted;
    }
    last_clock_observed_ = observed;
    out = admitted;
    return Status::Ok;
}

Status Store::project_clock(const ClockAdmission& admission, TimePoint now,
                            std::uint64_t& lower) const {
    if (latched_) return Status::StorageAmbiguous;
    if (clock_uncertain_ || state_.clock_high_water == kUncertainClock) {
        return Status::ClockRegressed;
    }
    if (admission.owner_ != this) return Status::InvalidArgument;
    std::uint64_t upper = 0U;
    return admission.project(now, lower, upper);
}

Status Store::advance_clock(const ClockAdmission& admission, TimePoint now) {
    std::lock_guard guard(lock_);
    std::uint64_t lower = 0U;
    const Status checked = project_clock(admission, now, lower);
    if (checked != Status::Ok) return checked;
    if (lower <= state_.clock_high_water) return Status::Ok;
    StoreState candidate;
    candidate.copy_from(state_);
    candidate.clock_high_water = lower;
    return commit(candidate);
}

Status Store::spend(const SpentInvitation& record,
                    const ClockAdmission& admission, TimePoint observed) {
    std::lock_guard guard(lock_);
    std::uint64_t now = 0U;
    const Status checked = project_clock(admission, observed, now);
    if (checked != Status::Ok) return checked;
    StoreState candidate;
    candidate.copy_from(state_);
    for (const SpentInvitation& spent : candidate.spent) {
        if (spent.invitation_id == record.invitation_id) {
            return Status::AlreadySpent;
        }
    }
    if (record.role == Role::Responder &&
        record.expires_at <= candidate.pruned_through) {
        return Status::Expired;
    }
    // This is an admitted attempt's monotonic lower bound. Another fresh
    // observation may be newer, so it is not a new wall-clock sample.
    if (now >= candidate.clock_high_water) {
        std::erase_if(candidate.spent, [&](const SpentInvitation& spent) {
            const bool expired =
                now >= kSpentRetentionSeconds &&
                spent.expires_at <= now - kSpentRetentionSeconds;
            if (expired && spent.role == Role::Responder) {
                candidate.pruned_through =
                    std::max(candidate.pruned_through, spent.expires_at);
            }
            return expired;
        });
    }
    if (candidate.spent.size() >= kMaxSpentInvitations) {
        return Status::Capacity;
    }
    candidate.clock_high_water = std::max(candidate.clock_high_water, now);
    const auto position = std::lower_bound(
        candidate.spent.begin(), candidate.spent.end(), record.invitation_id,
        [](const SpentInvitation& existing, const Id& wanted) {
            return key_less(existing.invitation_id, wanted);
        });
    candidate.spent.insert(position, record);
    return commit(candidate);
}

Status Store::sign(ByteView message, Signature& signature) const {
    std::lock_guard guard(lock_);
    if (!state_.has_identity) return Status::NotFound;
    signature = crypto_.sign(state_.seed, message);
    return Status::Ok;
}

Status Store::acquire_pending() {
    std::lock_guard guard(lock_);
    if (pending_ >= kMaxPendingInvitations) return Status::Capacity;
    ++pending_;
    return Status::Ok;
}

void Store::release_pending() noexcept {
    std::lock_guard guard(lock_);
    if (pending_ > 0U) --pending_;
}

Status Store::register_attempt(const Key& peer, std::atomic<bool>* stop) {
    std::lock_guard guard(lock_);
    if (attempt_peer_) return Status::Busy;
    attempt_peer_ = peer;
    attempt_stop_ = stop;
    return Status::Ok;
}

void Store::unregister_attempt() noexcept {
    std::lock_guard guard(lock_);
    attempt_peer_.reset();
    attempt_stop_ = nullptr;
}

void Store::add_borrower() noexcept {
    std::lock_guard guard(lock_);
    ++borrowers_;
}

void Store::remove_borrower() noexcept {
    std::lock_guard guard(lock_);
    if (borrowers_ > 0U) --borrowers_;
}

bool Store::borrowed() const noexcept {
    std::lock_guard guard(lock_);
    return borrowers_ != 0U;
}

bool Store::latched() const noexcept {
    std::lock_guard guard(lock_);
    return latched_;
}

}  // namespace ito::call
