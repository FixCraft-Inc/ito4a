/* Copyright (C) 2026 FixCraft Inc. AGPL-3.0-or-later. */

// Store policy: identity creation, trust transitions, key replacement,
// spent-invitation retention under clock changes, capacity refusal and the
// three persistence outcomes.

#include <atomic>
#include <memory>
#include <limits>
#include <string>
#include <vector>

#include "check.hpp"
#include "codec.hpp"
#include "store.hpp"

using namespace ito::call;

namespace {

// Persists into memory and can be told to refuse, be ambiguous or throw.
struct Disk final {
    std::vector<Byte> committed;
    PersistResult next{PersistResult::Committed};
    bool throw_next{false};
    int calls{0};

    Persist persist() {
        return [this](ByteView snapshot) {
            ++calls;
            if (throw_next) throw std::runtime_error("persist");
            if (next == PersistResult::Committed) {
                committed.assign(snapshot.begin(), snapshot.end());
            }
            return next;
        };
    }
};

std::unique_ptr<Store> open(const CryptoContext& crypto, Disk& disk) {
    std::unique_ptr<Store> store;
    CHECK(Store::open(crypto, disk.committed, disk.persist(), store) ==
          Status::Ok);
    return store;
}

Key key_of(const CryptoContext& crypto) {
    Seed seed;
    Key key{};
    crypto.generate_identity(seed, key);
    return key;
}

std::string fingerprint_text(const CryptoContext& crypto, const Key& key) {
    return to_hex(fingerprint(crypto, key));
}

void test_identity_and_contacts(const CryptoContext& crypto) {
    Disk disk;
    auto store = open(crypto, disk);
    Key own{};
    CHECK(store->identity(own) == Status::NotFound);
    CHECK(store->create_identity(100) == Status::Ok);
    CHECK(store->identity(own) == Status::Ok);
    CHECK(store->create_identity(101) == Status::WrongState);

    const Key alice = key_of(crypto);
    CHECK(store->add_contact(own, "Me", 102) == Status::InvalidArgument);
    CHECK(store->add_contact(alice, "", 102) == Status::InvalidArgument);
    CHECK(store->add_contact(alice, "Alice", 102) == Status::Ok);
    CHECK(store->add_contact(alice, "Again", 102) == Status::WrongState);
    CHECK(!store->is_verified(alice));

    // Verification needs the complete fingerprint, never a prefix.
    const std::string full = fingerprint_text(crypto, alice);
    CHECK(store->verify_contact(alice, full.substr(0, 32), 103) ==
          Status::AuthenticationFailed);
    CHECK(store->verify_contact(alice, fingerprint_text(crypto, own), 103) ==
          Status::AuthenticationFailed);
    CHECK(!store->is_verified(alice));
    CHECK(store->verify_contact(alice, full, 103) == Status::Ok);
    CHECK(store->is_verified(alice));
    CHECK(store->find_contact(alice)->verified_at == 103U);

    // Reopening from what persistence holds restores the same state.
    auto reopened = open(crypto, disk);
    Key again{};
    CHECK(reopened->identity(again) == Status::Ok && again == own);
    CHECK(reopened->contacts() == store->contacts());

    // A key change: the old key is blocked and points at the new one, which
    // keeps the name and starts unverified.
    const Key changed = key_of(crypto);
    std::atomic<bool> stop{false};
    CHECK(store->register_attempt(alice, &stop) == Status::Ok);
    CHECK(store->register_attempt(changed, &stop) == Status::Busy);
    CHECK(store->replace_contact(alice, changed, 104) == Status::Ok);
    CHECK(stop.load());
    store->unregister_attempt();
    const Contact retired = *store->find_contact(alice);
    const Contact successor = *store->find_contact(changed);
    CHECK(retired.trust == Trust::Blocked && retired.replaced_by == changed);
    CHECK(successor.trust == Trust::Unverified && successor.replaces == alice &&
          successor.name == "Alice");
    CHECK(store->replace_contact(alice, key_of(crypto), 105) ==
          Status::WrongState);
    // A retired key cannot be verified again.
    CHECK(store->verify_contact(alice, full, 106) == Status::Untrusted);
    CHECK(store->rename_contact(changed, "Alice (new phone)") == Status::Ok);

    // Blocking stops an attempt with that key.
    std::atomic<bool> blocked_stop{false};
    const Key bob = key_of(crypto);
    CHECK(store->add_contact(bob, "Bob", 107) == Status::Ok);
    CHECK(store->register_attempt(bob, &blocked_stop) == Status::Ok);
    CHECK(store->block_contact(bob) == Status::Ok);
    CHECK(blocked_stop.load());
    store->unregister_attempt();
    CHECK(store->find_contact(bob)->trust == Trust::Blocked);
}

void test_contact_capacity(const CryptoContext& crypto) {
    Disk disk;
    auto store = open(crypto, disk);
    CHECK(store->create_identity(1) == Status::Ok);
    for (std::size_t index = 0; index < kMaxContacts; ++index) {
        CHECK(store->add_contact(key_of(crypto), "c", 2) == Status::Ok);
    }
    const auto before = store->contacts();
    CHECK(store->add_contact(key_of(crypto), "c", 3) == Status::Capacity);
    CHECK(store->replace_contact(before[0].key, key_of(crypto), 3) ==
          Status::Capacity);
    // Nothing was evicted.
    CHECK(store->contacts() == before);
    CHECK(disk.committed.size() <= kMaxSnapshotBytes);
}

void test_persistence_outcomes(const CryptoContext& crypto) {
    Disk disk;
    auto store = open(crypto, disk);
    CHECK(store->create_identity(1) == Status::Ok);
    const std::vector<Byte> saved = disk.committed;

    disk.next = PersistResult::NotCommitted;
    CHECK(store->add_contact(key_of(crypto), "a", 2) == Status::StorageFailed);
    CHECK(store->contacts().empty() && disk.committed == saved);

    disk.next = PersistResult::Ambiguous;
    CHECK(store->add_contact(key_of(crypto), "a", 2) ==
          Status::StorageAmbiguous);
    CHECK(store->latched() && store->contacts().empty());
    // A latched store refuses every later mutation without persisting.
    disk.next = PersistResult::Committed;
    const int calls = disk.calls;
    CHECK(store->add_contact(key_of(crypto), "a", 2) ==
          Status::StorageAmbiguous);
    CHECK(disk.calls == calls);

    Disk thrower;
    auto other = open(crypto, thrower);
    thrower.throw_next = true;
    CHECK(other->create_identity(1) == Status::StorageAmbiguous);
    Key none{};
    CHECK(other->identity(none) == Status::NotFound);
}

SpentInvitation spent(const CryptoContext& crypto, std::uint64_t expires,
                      Role role) {
    SpentInvitation record;
    crypto.random(record.invitation_id);
    record.expires_at = expires;
    record.role = role;
    return record;
}

// New wall observations and existing admitted attempts are separate inputs.
Status spend_at(Store& store, const SpentInvitation& record,
                std::uint64_t now) {
    const TimePoint observed = TimePoint{} + std::chrono::seconds(now);
    ClockAdmission admitted;
    const Status clock = store.admit_clock(now, observed, admitted);
    return clock == Status::Ok ? store.spend(record, admitted, observed)
                               : clock;
}

void test_spent_retention(const CryptoContext& crypto) {
    Disk disk;
    auto store = open(crypto, disk);
    CHECK(store->create_identity(1) == Status::Ok);
    const SpentInvitation first = spent(crypto, 1'000, Role::Responder);
    CHECK(spend_at(*store, first, 900) == Status::Ok);
    CHECK(spend_at(*store, first, 900) == Status::AlreadySpent);
    CHECK(store->check_unspent(first.invitation_id, 1'000) ==
          Status::AlreadySpent);

    // Kept until expiry plus 60 seconds.
    CHECK(spend_at(*store, spent(crypto, 2'000, Role::Initiator), 1'059) ==
          Status::Ok);
    CHECK(store->check_unspent(first.invitation_id, 1'000) ==
          Status::AlreadySpent);
    // Past expiry plus 60: pruned, and its expiry is now the
    // replay floor for received invitations.
    CHECK(spend_at(*store, spent(crypto, 3'000, Role::Initiator), 1'100) ==
          Status::Ok);
    CHECK(store->check_unspent(first.invitation_id, 1'000) == Status::Expired);
    CHECK(spend_at(*store, spent(crypto, 1'000, Role::Responder), 1'100) ==
          Status::Expired);
    CHECK(store->check_unspent(spent(crypto, 0, Role::Responder).invitation_id,
                               1'001) == Status::Ok);

    // The state survives reopening.
    auto reopened = open(crypto, disk);
    CHECK(reopened->check_unspent(first.invitation_id, 1'000) ==
          Status::Expired);

    // Capacity refuses rather than evicting an unexpired record.
    Disk full_disk;
    auto full = open(crypto, full_disk);
    CHECK(full->create_identity(1) == Status::Ok);
    for (std::size_t index = 0; index < kMaxSpentInvitations; ++index) {
        CHECK(spend_at(*full, spent(crypto, 10'000, Role::Initiator), 5'000) ==
              Status::Ok);
    }
    CHECK(spend_at(*full, spent(crypto, 10'000, Role::Initiator), 5'000) ==
          Status::Capacity);
    // After those expire, pruning frees the space.
    CHECK(spend_at(*full, spent(crypto, 20'000, Role::Initiator), 10'060) ==
          Status::Ok);
}

void test_clock_admission(const CryptoContext& crypto) {
    using namespace std::chrono;
    Disk disk;
    auto store = open(crypto, disk);
    CHECK(store->create_identity(1) == Status::Ok);
    const Key peer = key_of(crypto);
    CHECK(store->add_contact(peer, "peer", 2) == Status::Ok);
    CHECK(store->verify_contact(peer, fingerprint_text(crypto, peer), 3) ==
          Status::Ok);
    Key own{};
    CHECK(store->identity(own) == Status::Ok);
    const auto contacts = store->contacts();
    const TimePoint at = TimePoint{} + hours(1);
    ClockAdmission admission;
    CHECK(store->admit_clock(100, at, admission) == Status::Ok);
    const auto saved = disk.committed;
    const int calls = disk.calls;
    ClockAdmission same;
    CHECK(store->admit_clock(100, at + milliseconds(500), same) == Status::Ok);
    CHECK(disk.calls == calls);
    CHECK(store->admit_clock(100, at + seconds(1), same) ==
          Status::ClockRegressed);
    CHECK(store->admit_clock(90, at + seconds(2), same) ==
          Status::ClockRegressed);
    // Catching a static floor does not establish recovery of the live clock.
    CHECK(store->admit_clock(100, at + seconds(10), same) ==
          Status::ClockRegressed);
    const auto uncertain = disk.committed;
    CHECK(disk.calls == calls + 1 && uncertain != saved);
    StoreState marked;
    CHECK(decode_snapshot(crypto, uncertain, marked) == Status::Ok);
    CHECK(marked.clock_high_water == kUncertainClock);
    CHECK(marked.public_key == own && marked.contacts == contacts &&
          marked.spent.empty() && marked.pruned_through == 0U);
    CHECK(store->admit_clock(110, at + seconds(10), same) ==
          Status::ClockRegressed);
    CHECK(disk.committed == uncertain && disk.calls == calls + 1);
    CHECK(store->check_clock(110, at - seconds(1)) == Status::ClockRegressed);
    CHECK(store->contacts() == contacts);
    Key unchanged{};
    CHECK(store->identity(unchanged) == Status::Ok && own == unchanged);

    // A different fresh observation must not invalidate an admitted attempt.
    Disk parallel_disk;
    auto parallel = open(crypto, parallel_disk);
    CHECK(parallel->create_identity(1) == Status::Ok);
    ClockAdmission active;
    CHECK(parallel->admit_clock(100, at, active) == Status::Ok);
    ClockAdmission newer;
    CHECK(parallel->admit_clock(101, at + milliseconds(200), newer) ==
          Status::Ok);
    const SpentInvitation record = spent(crypto, 200, Role::Initiator);
    CHECK(parallel->spend(record, active, at + milliseconds(300)) ==
          Status::Ok);
    CHECK(parallel->check_unspent(record.invitation_id, 200) ==
          Status::AlreadySpent);
    ClockAdmission empty;
    CHECK(parallel->spend(record, empty, at) == Status::InvalidArgument);

    // Reload loses no durable observation, identity, trust or replay record.
    const auto persisted = parallel_disk.committed;
    auto reloaded = open(crypto, parallel_disk);
    CHECK(reloaded->admit_clock(100, at, empty) == Status::ClockRegressed);
    StoreState reloaded_marked;
    CHECK(decode_snapshot(crypto, parallel_disk.committed, reloaded_marked) ==
          Status::Ok);
    CHECK(reloaded_marked.clock_high_water == kUncertainClock);
    CHECK(reloaded_marked.spent.size() == 1U &&
          reloaded_marked.spent[0] == record);
    CHECK(parallel_disk.committed != persisted);
    CHECK(reloaded->check_unspent(record.invitation_id, 200) ==
          Status::AlreadySpent);
    CHECK(reloaded->admit_clock(101, at, empty) == Status::ClockRegressed);
    auto caught_up = open(crypto, parallel_disk);
    CHECK(caught_up->admit_clock(500, at, empty) == Status::ClockRegressed);
    CHECK(parallel->spend(record, same, at) == Status::InvalidArgument);

    Disk refused_disk;
    auto refused = open(crypto, refused_disk);
    CHECK(refused->create_identity(1) == Status::Ok);
    const auto before = refused_disk.committed;
    refused_disk.next = PersistResult::NotCommitted;
    CHECK(refused->admit_clock(100, at, empty) == Status::StorageFailed);
    CHECK(refused_disk.committed == before);
    refused_disk.next = PersistResult::Ambiguous;
    CHECK(refused->admit_clock(100, at, empty) == Status::StorageAmbiguous);
    refused_disk.next = PersistResult::Committed;
    CHECK(refused->admit_clock(0, at, empty) == Status::StorageAmbiguous);
    CHECK(refused_disk.committed == before);

    Disk retry_disk;
    auto retry = open(crypto, retry_disk);
    CHECK(retry->create_identity(1) == Status::Ok);
    CHECK(retry->add_contact(peer, "peer", 2) == Status::Ok);
    CHECK(retry->admit_clock(100, at, empty) == Status::Ok);
    const auto before_detection = retry_disk.committed;
    retry_disk.next = PersistResult::NotCommitted;
    CHECK(retry->admit_clock(90, at + seconds(10), empty) ==
          Status::StorageFailed);
    CHECK(retry_disk.committed == before_detection);
    CHECK(retry->spend(record, empty, at) == Status::ClockRegressed);
    retry_disk.next = PersistResult::Committed;
    CHECK(retry->rename_contact(peer, "renamed") == Status::Ok);
    auto retry_reload = open(crypto, retry_disk);
    CHECK(retry_reload->find_contact(peer)->name == "renamed");
    CHECK(retry_reload->admit_clock(500, at, empty) == Status::ClockRegressed);

    Disk overflow_disk;
    auto overflow = open(crypto, overflow_disk);
    CHECK(overflow->admit_clock(kUncertainClock, at, empty) ==
          Status::InvalidArgument);
    CHECK(overflow->admit_clock(kUncertainClock - 1U, at, empty) == Status::Ok);
    std::uint64_t lower = 0U;
    std::uint64_t upper = 0U;
    CHECK(empty.project(at + nanoseconds(1), lower, upper) ==
          Status::InvalidArgument);
}

void test_pending_and_borrowers(const CryptoContext& crypto) {
    Disk disk;
    auto store = open(crypto, disk);
    for (std::size_t index = 0; index < kMaxPendingInvitations; ++index) {
        CHECK(store->acquire_pending() == Status::Ok);
    }
    CHECK(store->acquire_pending() == Status::Capacity);
    store->release_pending();
    CHECK(store->acquire_pending() == Status::Ok);
    CHECK(!store->borrowed());
    store->add_borrower();
    CHECK(store->borrowed());
    store->remove_borrower();
    CHECK(!store->borrowed());
}

void test_corrupt_snapshot(const CryptoContext& crypto) {
    Disk disk;
    auto store = open(crypto, disk);
    CHECK(store->create_identity(1) == Status::Ok);
    CHECK(store->add_contact(key_of(crypto), "Alice", 2) == Status::Ok);
    // Any single changed byte outside the free-form fields is refused or
    // decodes to the same canonical bytes. It never silently resets.
    for (std::size_t index = 0; index < disk.committed.size(); ++index) {
        std::vector<Byte> changed = disk.committed;
        changed[index] ^= 0x80U;
        std::unique_ptr<Store> opened;
        const Status status =
            Store::open(crypto, changed, disk.persist(), opened);
        if (status == Status::Ok) {
            StoreState state;
            CHECK(decode_snapshot(crypto, changed, state) == Status::Ok);
            const SecretBytes again = encode_snapshot(state);
            CHECK(std::equal(again.view().begin(), again.view().end(),
                             changed.begin(), changed.end()));
        } else {
            CHECK(status == Status::CorruptState);
        }
    }
    std::vector<Byte> trailing = disk.committed;
    trailing.push_back(0);
    std::unique_ptr<Store> refused;
    CHECK(Store::open(crypto, trailing, disk.persist(), refused) ==
          Status::CorruptState);
    CHECK(Store::open(crypto, std::vector<Byte>(kMaxSnapshotBytes + 1U, 0),
                      disk.persist(), refused) == Status::CorruptState);
}

}  // namespace

int main() {
    const CryptoContext crypto;
    test_identity_and_contacts(crypto);
    test_contact_capacity(crypto);
    test_persistence_outcomes(crypto);
    test_spent_retention(crypto);
    test_clock_admission(crypto);
    test_pending_and_borrowers(crypto);
    test_corrupt_snapshot(crypto);
    return 0;
}
