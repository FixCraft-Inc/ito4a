/* Copyright (C) 2026 FixCraft Inc. AGPL-3.0-or-later. */

// The C interface through the packaged library: a complete call between two
// threads over an in-process relay, argument and lifetime refusals,
// reentrancy from callbacks, cancellation while running and allocation
// failure. This file sees only <ito/call.h>, as an application does.

#include <ito/call.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstddef>
#include <condition_variable>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <deque>
#include <mutex>
#include <new>
#include <random>
#include <string>
#include <thread>
#include <vector>

#include "check.hpp"

// Allocation failure for this test binary only. Once armed on a thread, the
// replacement global operator new fails that thread's Nth allocation, the
// packaged library's included, and operator delete reports a freed block
// that still holds the armed secret. The library has no testing hook.
// OpenSSL allocates through malloc and is not affected.
namespace {

thread_local std::size_t t_fail_at = 0U;  // 0: disarmed
thread_local std::size_t t_allocations = 0U;
thread_local bool t_failed = false;
thread_local const uint8_t* t_secret = nullptr;  // 32 bytes
thread_local bool t_secret_freed = false;

// Each block records its size, so a freed block can be scanned.
struct alignas(std::max_align_t) Header {
    std::size_t size;
};

void* allocate(std::size_t size) {
    if (t_fail_at != 0U && ++t_allocations == t_fail_at) {
        t_failed = true;
        throw std::bad_alloc();
    }
    auto* header = static_cast<Header*>(std::malloc(sizeof(Header) + size));
    if (header == nullptr) throw std::bad_alloc();
    header->size = size;
    return header + 1;
}

void release(void* memory) noexcept {
    if (memory == nullptr) return;
    Header* header = static_cast<Header*>(memory) - 1;
    if (t_fail_at != 0U && t_secret != nullptr && header->size >= 32U) {
        const auto* bytes = static_cast<const uint8_t*>(memory);
        if (std::search(bytes, bytes + header->size, t_secret, t_secret + 32) !=
            bytes + header->size) {
            t_secret_freed = true;
        }
    }
    std::free(header);
}

}  // namespace

void* operator new(std::size_t size) {
    return allocate(size);
}
void* operator new[](std::size_t size) {
    return allocate(size);
}
void* operator new(std::size_t size, const std::nothrow_t&) noexcept {
    try {
        return allocate(size);
    } catch (const std::bad_alloc&) {
        return nullptr;
    }
}
void* operator new[](std::size_t size, const std::nothrow_t&) noexcept {
    try {
        return allocate(size);
    } catch (const std::bad_alloc&) {
        return nullptr;
    }
}
void operator delete(void* memory) noexcept {
    release(memory);
}
void operator delete[](void* memory) noexcept {
    release(memory);
}
void operator delete(void* memory, std::size_t) noexcept {
    release(memory);
}
void operator delete[](void* memory, std::size_t) noexcept {
    release(memory);
}
void operator delete(void* memory, const std::nothrow_t&) noexcept {
    release(memory);
}
void operator delete[](void* memory, const std::nothrow_t&) noexcept {
    release(memory);
}

namespace {

// Runs operation with this thread's Nth allocation failing, and reports
// whether the operation reached it.
template <class Operation>
bool fails_at(std::size_t n, Operation&& operation) {
    t_allocations = 0U;
    t_failed = false;
    t_fail_at = n;
    operation();
    t_fail_at = 0U;
    return t_failed;
}

constexpr std::size_t kMaxInjections = 4096U;

using Bytes = std::vector<uint8_t>;
using std::chrono::milliseconds;

uint64_t now_unix() {
    return static_cast<uint64_t>(
        std::chrono::duration_cast<std::chrono::seconds>(
            std::chrono::system_clock::now().time_since_epoch())
            .count());
}

// Memory persistence that can call back into the core to prove refusal.
struct Disk {
    Bytes bytes;
    ito_store* reenter = nullptr;
    ito_status reentered = ITO_STATUS_OK;
};

int32_t persist(void* user_data, const uint8_t* snapshot, size_t size) {
    auto* disk = static_cast<Disk*>(user_data);
    if (disk->reenter != nullptr) {
        uint8_t key[ITO_KEY_BYTES];
        disk->reentered = ito_store_identity(disk->reenter, key);
    }
    disk->bytes.assign(snapshot, snapshot + size);
    return ITO_PERSIST_COMMITTED;
}

ito_authority authority() {
    ito_authority result{};
    result.struct_size = sizeof(result);
    std::memset(result.server_fingerprint, 0xA5, ITO_KEY_BYTES);
    const char service[] = "ito-call";
    result.service_size = sizeof(service) - 1;
    std::memcpy(result.service, service, result.service_size);
    return result;
}

// A relay with the version-1 control framing and a 64 KiB queue per
// direction, shared by two transports.
class Relay {
public:
    Bytes& queue(int side) { return to_[side]; }

    int32_t write(int side, const uint8_t* data, size_t size) {
        std::lock_guard guard(lock_);
        if (closed_) return ITO_IO_FAILED;
        if (paired_) {
            if (to_[1 - side].size() + size > 65536U) return ITO_IO_TIMEOUT;
            to_[1 - side].insert(to_[1 - side].end(), data, data + size);
        } else {
            control_[side].insert(control_[side].end(), data, data + size);
            parse(side);
        }
        changed_.notify_all();
        return ITO_IO_OK;
    }

    int32_t read(int side, uint8_t* out, size_t capacity, size_t* received,
                 uint32_t timeout_ms) {
        std::unique_lock guard(lock_);
        changed_.wait_for(guard, milliseconds(timeout_ms),
                          [&] { return !to_[side].empty() || ended_[side]; });
        if (to_[side].empty()) {
            return ended_[side] ? ITO_IO_EOF : ITO_IO_TIMEOUT;
        }
        const size_t count = std::min(capacity, to_[side].size());
        std::memcpy(out, to_[side].data(), count);
        to_[side].erase(to_[side].begin(),
                        to_[side].begin() + static_cast<std::ptrdiff_t>(count));
        *received = count;
        changed_.notify_all();
        return ITO_IO_OK;
    }

    void end_all() {
        std::lock_guard guard(lock_);
        ended_[0] = ended_[1] = true;
        changed_.notify_all();
    }

private:
    void reply(int side, uint8_t type, const Bytes& payload) {
        const uint32_t body = static_cast<uint32_t>(1U + payload.size());
        Bytes frame{
            static_cast<uint8_t>(body >> 24), static_cast<uint8_t>(body >> 16),
            static_cast<uint8_t>(body >> 8), static_cast<uint8_t>(body), type};
        frame.insert(frame.end(), payload.begin(), payload.end());
        to_[side].insert(to_[side].end(), frame.begin(), frame.end());
    }

    void parse(int side) {
        Bytes& in = control_[side];
        while (!paired_ && in.size() >= 5U) {
            const uint32_t body = (uint32_t{in[0]} << 24) |
                                  (uint32_t{in[1]} << 16) |
                                  (uint32_t{in[2]} << 8) | in[3];
            if (in.size() < 4U + body) return;
            const uint8_t type = in[4];
            const Bytes payload(
                in.begin() + 5,
                in.begin() + 4 + static_cast<std::ptrdiff_t>(body));
            in.erase(in.begin(),
                     in.begin() + 4 + static_cast<std::ptrdiff_t>(body));
            if (type == 1) {
                std::random_device random;
                for (auto* value : {&room_, &tokens_[0], &tokens_[1]}) {
                    for (uint8_t& byte : *value) {
                        byte = static_cast<uint8_t>(random());
                    }
                }
                Bytes out(room_.begin(), room_.end());
                out.insert(out.end(), tokens_[0].begin(), tokens_[0].end());
                out.insert(out.end(), tokens_[1].begin(), tokens_[1].end());
                out.insert(out.end(), payload.begin() + 2, payload.begin() + 6);
                reply(side, 2, out);
            } else if (type == 3) {
                const int role = payload[34];
                if (role < 1 || role > 2 ||
                    std::memcmp(payload.data() + 2, room_.data(), 32) != 0 ||
                    std::memcmp(payload.data() + 35, tokens_[role - 1].data(),
                                32) != 0) {
                    return refuse(1);
                }
                reply(side, 4, {});
                joined_[side] = true;
                if (joined_[0] && joined_[1]) {
                    paired_ = true;
                    reply(0, 5, {});
                    reply(1, 5, {});
                }
            } else {
                return refuse(type == 6 ? 4 : 6);
            }
        }
    }

    void refuse(uint8_t reason) {
        reply(0, 7, {reason});
        reply(1, 7, {reason});
        ended_[0] = ended_[1] = true;
    }

    std::mutex lock_;
    std::condition_variable changed_;
    Bytes to_[2];
    Bytes control_[2];
    bool joined_[2]{false, false};
    bool ended_[2]{false, false};
    bool paired_{false};
    bool closed_{false};
    std::array<uint8_t, 32> room_{};
    std::array<uint8_t, 32> tokens_[2]{};
};

struct Endpoint {
    Relay* relay;
    int side;
    ito_call* reenter = nullptr;
    std::atomic<int> reentered{ITO_STATUS_OK};
};

int32_t endpoint_read(void* user_data, uint8_t* out, size_t capacity,
                      size_t* received, uint32_t timeout_ms) {
    auto* endpoint = static_cast<Endpoint*>(user_data);
    if (endpoint->reenter != nullptr) {
        ito_call_status status{};
        status.struct_size = sizeof(status);
        endpoint->reentered = ito_call_status_get(endpoint->reenter, &status);
        endpoint->reenter = nullptr;
    }
    return endpoint->relay->read(endpoint->side, out, capacity, received,
                                 timeout_ms);
}

int32_t endpoint_write(void* user_data, const uint8_t* data, size_t size,
                       uint32_t) {
    auto* endpoint = static_cast<Endpoint*>(user_data);
    return endpoint->relay->write(endpoint->side, data, size);
}

ito_transport transport(Endpoint& endpoint) {
    ito_transport result{};
    result.struct_size = sizeof(result);
    result.user_data = &endpoint;
    result.read = endpoint_read;
    result.write = endpoint_write;
    return result;
}

struct Installation {
    Disk disk;
    ito_store* store = nullptr;
    uint8_t key[ITO_KEY_BYTES]{};

    explicit Installation(ito_context* context) {
        ito_persistence persistence{sizeof(persistence), &disk, persist};
        CHECK(ito_store_open(context, nullptr, 0, &persistence, &store) ==
              ITO_STATUS_OK);
        CHECK(ito_store_create_identity(store, now_unix()) == ITO_STATUS_OK);
        CHECK(ito_store_identity(store, key) == ITO_STATUS_OK);
    }
};

void befriend(ito_context* context, Installation& a, Installation& b) {
    for (auto [self, other] : {std::pair{&a, &b}, std::pair{&b, &a}}) {
        char code[ITO_CONTACT_CODE_CHARS + 1];
        CHECK(ito_contact_code_format(other->key, code, sizeof(code)) ==
              ITO_STATUS_OK);
        uint8_t parsed[ITO_KEY_BYTES];
        CHECK(ito_contact_code_parse(code, std::strlen(code), parsed) ==
              ITO_STATUS_OK);
        CHECK(std::memcmp(parsed, other->key, ITO_KEY_BYTES) == 0);
        CHECK(ito_store_add_contact(self->store, parsed, "peer", 4,
                                    now_unix()) == ITO_STATUS_OK);
        char fingerprint[ITO_FINGERPRINT_CHARS + 1];
        CHECK(ito_fingerprint(context, parsed, fingerprint,
                              sizeof(fingerprint)) == ITO_STATUS_OK);
        CHECK(std::strlen(fingerprint) == ITO_FINGERPRINT_CHARS);
        CHECK(ito_store_verify_contact(self->store, parsed, fingerprint,
                                       ITO_FINGERPRINT_CHARS,
                                       now_unix()) == ITO_STATUS_OK);
    }
}

ito_call_status status_of(ito_call* call) {
    ito_call_status status{};
    status.struct_size = sizeof(status);
    CHECK(ito_call_status_get(call, &status) == ITO_STATUS_OK);
    return status;
}

template <class Predicate>
void wait_for(ito_call* call, Predicate predicate) {
    bool reached = false;
    for (int round = 0; round < 1000 && !reached; ++round) {
        reached = predicate(status_of(call));
        if (!reached) std::this_thread::sleep_for(milliseconds(10));
    }
    CHECK(reached);
}

void test_call(ito_context* context, uint32_t profile = 1U) {
    Installation alice(context);
    Installation bob(context);
    befriend(context, alice, bob);
    Relay relay;
    Endpoint a_end{&relay, 0};
    Endpoint b_end{&relay, 1};
    ito_call* a = nullptr;
    const ito_authority selected = authority();
    CHECK(ito_call_create_initiator_profile(alice.store, bob.key, &selected,
                                            120, now_unix(), profile,
                                            &a) == ITO_STATUS_OK);
    // A transport callback that calls back into the call is refused.
    a_end.reenter = a;
    std::atomic<int> a_result{1};
    std::thread a_thread([&] {
        const ito_transport t = transport(a_end);
        a_result = ito_call_run(a, &t);
    });
    wait_for(a, [](const ito_call_status& s) {
        return (s.flags & ITO_CALL_FLAG_INVITATION_READY) != 0U;
    });
    CHECK(a_end.reentered == ITO_STATUS_REENTRANT);
    // The running call cannot be destroyed, nor its store.
    CHECK(ito_call_destroy(a) == ITO_STATUS_WRONG_STATE);
    CHECK(ito_store_destroy(alice.store) == ITO_STATUS_WRONG_STATE);

    uint8_t invitation[ITO_MAX_INVITATION_BYTES];
    size_t invitation_size = 0;
    CHECK(ito_call_copy_invitation(a, invitation, 10, &invitation_size) ==
          ITO_STATUS_BUFFER_TOO_SMALL);
    CHECK(ito_call_copy_invitation(a, invitation, sizeof(invitation),
                                   &invitation_size) == ITO_STATUS_OK);
    ito_invitation* opened = nullptr;
    CHECK(ito_invitation_open(bob.store, invitation, invitation_size, &selected,
                              now_unix(), &opened) == ITO_STATUS_OK);
    ito_invitation_info info{};
    info.struct_size = sizeof(info);
    CHECK(ito_invitation_info_get(opened, &info) == ITO_STATUS_OK);
    CHECK(std::memcmp(info.initiator_key, alice.key, ITO_KEY_BYTES) == 0 &&
          info.profile == profile && info.expires_unix > info.issued_unix);
    ito_call* b = nullptr;
    CHECK(ito_call_create_responder(bob.store, opened, &selected, now_unix(),
                                    &b) == ITO_STATUS_OK);
    // Kept to show below that the spent invitation is refused as spent.
    const Bytes kept(invitation, invitation + invitation_size);
    std::memset(invitation, 0, sizeof(invitation));
    std::atomic<int> b_result{1};
    std::thread b_thread([&] {
        const ito_transport t = transport(b_end);
        b_result = ito_call_run(b, &t);
    });
    wait_for(b, [](const ito_call_status& s) {
        return (s.flags & ITO_CALL_FLAG_CONSENT_PENDING) != 0U;
    });
    CHECK(ito_call_decide(b, 1) == ITO_STATUS_OK);
    for (ito_call* call : {a, b}) {
        wait_for(call, [](const ito_call_status& s) {
            return s.state == ITO_CALL_MEDIA_AUTHORIZED;
        });
    }
    const Bytes payload(ITO_MAX_DATA_BYTES, 0x3C);
    CHECK(ito_call_send(a, payload.data(), payload.size()) == ITO_STATUS_OK);
    CHECK(ito_call_send(a, payload.data(), payload.size() + 1) ==
          ITO_STATUS_INVALID_ARGUMENT);
    Bytes received(ITO_MAX_DATA_BYTES);
    size_t size = 0;
    ito_status got = ITO_STATUS_WOULD_BLOCK;
    for (int round = 0; round < 500 && got == ITO_STATUS_WOULD_BLOCK; ++round) {
        got = ito_call_receive(b, received.data(), received.size(), &size);
        if (got == ITO_STATUS_WOULD_BLOCK)
            std::this_thread::sleep_for(milliseconds(10));
    }
    CHECK(got == ITO_STATUS_OK && size == payload.size() &&
          received == payload);
    CHECK(ito_call_hangup(b) == ITO_STATUS_OK);
    a_thread.join();
    // The side that ended keeps its transport until the peer's side ends.
    std::this_thread::sleep_for(milliseconds(200));
    CHECK(b_result == 1);
    relay.end_all();
    b_thread.join();
    CHECK(a_result == ITO_STATUS_PEER_ENDED && b_result == ITO_STATUS_CANCELED);
    const ito_call_status final_a = status_of(a);
    CHECK(final_a.state == ITO_CALL_CLOSED && final_a.remote_end_reason == 1U &&
          (final_a.flags & ITO_CALL_FLAG_REMOTE_END_ATTRIBUTED) != 0U);
    CHECK(ito_call_run(a, nullptr) == ITO_STATUS_INVALID_ARGUMENT);
    // A call runs once.
    const ito_transport again = transport(a_end);
    CHECK(ito_call_run(a, &again) == ITO_STATUS_WRONG_STATE);
    CHECK(ito_store_destroy(alice.store) == ITO_STATUS_WRONG_STATE);
    CHECK(ito_call_destroy(a) == ITO_STATUS_OK);
    CHECK(ito_call_destroy(b) == ITO_STATUS_OK);
    // The responder's store spent the invitation. Opening it again is
    // refused as spent, not merely as malformed.
    CHECK(ito_invitation_open(bob.store, kept.data(), kept.size(), &selected,
                              now_unix(), &opened) == ITO_STATUS_ALREADY_SPENT);
    CHECK(ito_store_destroy(alice.store) == ITO_STATUS_OK);
    CHECK(ito_store_destroy(bob.store) == ITO_STATUS_OK);
}

void test_cancel_while_running(ito_context* context) {
    Installation alice(context);
    Installation bob(context);
    befriend(context, alice, bob);
    Relay relay;
    Endpoint a_end{&relay, 0};
    const ito_authority selected = authority();
    ito_call* a = nullptr;
    CHECK(ito_call_create_initiator(alice.store, bob.key, &selected, 60,
                                    now_unix(), &a) == ITO_STATUS_OK);
    // One attempt per installation.
    ito_call* second = nullptr;
    CHECK(ito_call_create_initiator(alice.store, bob.key, &selected, 60,
                                    now_unix(), &second) == ITO_STATUS_BUSY);
    std::atomic<int> result{1};
    std::thread thread([&] {
        const ito_transport t = transport(a_end);
        result = ito_call_run(a, &t);
    });
    wait_for(
        a, [](const ito_call_status& s) { return s.state == ITO_CALL_JOINED; });
    const auto started = std::chrono::steady_clock::now();
    CHECK(ito_call_cancel(a) == ITO_STATUS_OK);
    thread.join();
    CHECK(std::chrono::steady_clock::now() - started < std::chrono::seconds(2));
    CHECK(result == ITO_STATUS_CANCELED);
    CHECK(ito_call_destroy(a) == ITO_STATUS_OK);
    CHECK(ito_call_create_initiator(alice.store, bob.key, &selected, 60,
                                    now_unix(), &second) == ITO_STATUS_OK);
    CHECK(ito_call_destroy(second) == ITO_STATUS_OK);
    CHECK(ito_store_destroy(alice.store) == ITO_STATUS_OK);
    CHECK(ito_store_destroy(bob.store) == ITO_STATUS_OK);
}

void test_refusals(ito_context* context) {
    CHECK(ito_context_create(nullptr) == ITO_STATUS_INVALID_ARGUMENT);
    char version[8];
    CHECK(ito_component_version(version, sizeof(version)) ==
          ITO_STATUS_BUFFER_TOO_SMALL);
    char full[128];
    CHECK(ito_component_version(full, sizeof(full)) == ITO_STATUS_OK);
    CHECK(std::strncmp(full, "OpenSSL 3.", 10) == 0);
    std::printf("component: %s\n", full);
    CHECK(std::strcmp(ito_status_name(ITO_STATUS_REENTRANT), "reentrant") == 0);

    Disk disk;
    ito_persistence persistence{sizeof(persistence) - 1, &disk, persist};
    ito_store* store = nullptr;
    CHECK(ito_store_open(context, nullptr, 0, &persistence, &store) ==
          ITO_STATUS_INVALID_ARGUMENT);
    persistence.struct_size = sizeof(persistence);
    const uint8_t garbage[4] = {1, 2, 3, 4};
    CHECK(ito_store_open(context, garbage, sizeof(garbage), &persistence,
                         &store) == ITO_STATUS_CORRUPT_STATE);
    CHECK(ito_store_open(context, nullptr, 0, &persistence, &store) ==
          ITO_STATUS_OK);
    // The context outlives its stores.
    CHECK(ito_context_destroy(context) == ITO_STATUS_WRONG_STATE);
    uint8_t key[ITO_KEY_BYTES];
    CHECK(ito_store_identity(store, key) == ITO_STATUS_NOT_FOUND);
    // Persistence that calls back into the store is refused, not deadlocked.
    disk.reenter = store;
    CHECK(ito_store_create_identity(store, now_unix()) == ITO_STATUS_OK);
    CHECK(disk.reentered == ITO_STATUS_REENTRANT);
    disk.reenter = nullptr;
    CHECK(ito_store_identity(store, key) == ITO_STATUS_OK);

    ito_contact contact{};
    contact.struct_size = sizeof(contact) - 1;
    CHECK(ito_store_contact_at(store, 0, &contact) ==
          ITO_STATUS_INVALID_ARGUMENT);
    contact.struct_size = sizeof(contact);
    CHECK(ito_store_contact_at(store, 0, &contact) == ITO_STATUS_NOT_FOUND);
    CHECK(ito_contact_code_parse("ito-contact:2:x", 15, key) ==
          ITO_STATUS_UNSUPPORTED);
    CHECK(ito_contact_code_parse("nonsense", 8, key) == ITO_STATUS_MALFORMED);
    // An invitation needs a well-formed authority.
    ito_authority bad = authority();
    bad.service_size = 0;
    ito_invitation* invitation = nullptr;
    const uint8_t bytes[1] = {0};
    CHECK(ito_invitation_open(store, bytes, 1, &bad, now_unix(), &invitation) ==
          ITO_STATUS_INVALID_ARGUMENT);
    const ito_authority good = authority();
    CHECK(ito_invitation_open(store, bytes, 1, &good, now_unix(),
                              &invitation) == ITO_STATUS_MALFORMED);
    // Calls need a Verified peer.
    ito_call* call = nullptr;
    uint8_t stranger[ITO_KEY_BYTES];
    std::memset(stranger, 7, sizeof(stranger));
    CHECK(ito_call_create_initiator(store, stranger, &good, 60, now_unix(),
                                    &call) == ITO_STATUS_UNTRUSTED);
    CHECK(ito_call_create_initiator(store, stranger, &good, 601, now_unix(),
                                    &call) == ITO_STATUS_INVALID_ARGUMENT);
    CHECK(ito_store_destroy(store) == ITO_STATUS_OK);
}

void test_clock_refusal(ito_context* context) {
    for (bool reload : {false, true}) {
        Installation alice(context);
        Installation bob(context);
        befriend(context, alice, bob);
        const ito_authority selected = authority();
        const Bytes original = alice.disk.bytes;
        ito_call* call = nullptr;
        CHECK(ito_call_create_initiator(alice.store, bob.key, &selected, 60,
                                        UINT64_MAX,
                                        &call) == ITO_STATUS_INVALID_ARGUMENT);
        CHECK(call == nullptr && alice.disk.bytes == original);
        const std::uint64_t observed = now_unix() + 120U;
        CHECK(ito_call_create_initiator(alice.store, bob.key, &selected, 60,
                                        observed, &call) == ITO_STATUS_OK);
        CHECK(ito_call_destroy(call) == ITO_STATUS_OK);
        call = nullptr;
        const Bytes checkpoint = alice.disk.bytes;
        if (reload) {
            CHECK(ito_store_destroy(alice.store) == ITO_STATUS_OK);
            alice.store = nullptr;
            const ito_persistence persistence{sizeof(persistence), &alice.disk,
                                              persist};
            const Bytes restored = alice.disk.bytes;
            CHECK(ito_store_open(context, restored.data(), restored.size(),
                                 &persistence, &alice.store) == ITO_STATUS_OK);
        }
        CHECK(ito_call_create_initiator(alice.store, bob.key, &selected, 60,
                                        observed - 1U,
                                        &call) == ITO_STATUS_CLOCK_REGRESSED);
        CHECK(call == nullptr);
        // Only the canonical clock field records detected uncertainty.
        constexpr size_t kClockOffset = 25U + 32U + 32U + 8U;
        CHECK(alice.disk.bytes.size() == checkpoint.size());
        for (size_t index = 0; index < checkpoint.size(); ++index) {
            const uint8_t expected =
                index >= kClockOffset && index < kClockOffset + 8U
                    ? uint8_t{0xFF}
                    : checkpoint[index];
            CHECK(alice.disk.bytes[index] == expected);
        }
        uint8_t own[ITO_KEY_BYTES]{};
        CHECK(ito_store_identity(alice.store, own) == ITO_STATUS_OK);
        CHECK(std::memcmp(own, alice.key, ITO_KEY_BYTES) == 0);
        size_t count = 0U;
        CHECK(ito_store_contact_count(alice.store, &count) == ITO_STATUS_OK &&
              count == 1U);
        CHECK(ito_store_destroy(alice.store) == ITO_STATUS_OK);
        CHECK(ito_store_destroy(bob.store) == ITO_STATUS_OK);
    }
    CHECK(std::strcmp(ito_status_name(ITO_STATUS_CLOCK_REGRESSED),
                      "clock_regressed") == 0);
}

// Every allocation of a store mutation, an invitation opening and the
// responder's call creation fails once, in turn. Each failure is typed and
// leaves nothing half done. LeakSanitizer checks for leaks in the ASan build.
void test_allocation_failures(ito_context* context) {
    Installation alice(context);
    Installation bob(context);
    befriend(context, alice, bob);
    const ito_authority selected = authority();

    // Every allocation precedes publication, so an injected failure leaves
    // the snapshot and the store unchanged. No freed block holds the identity
    // seed. The persistence below never allocates.
    Bytes base;
    {
        Installation carol(context);
        base = carol.disk.bytes;
        CHECK(ito_store_destroy(carol.store) == ITO_STATUS_OK);
    }
    // The snapshot's domain and identity flag precede the 32-byte seed.
    constexpr size_t kSeedOffset = 25U;
    CHECK(base.size() > kSeedOffset + 32U && base[kSeedOffset - 1U] == 1U);
    const Bytes seed(base.data() + kSeedOffset,
                     base.data() + kSeedOffset + 32U);
    for (std::size_t n = 1U;; ++n) {
        CHECK(n < kMaxInjections);
        Disk disk;
        disk.bytes.reserve(1U << 20U);
        disk.bytes = base;
        ito_persistence persistence{sizeof(persistence), &disk, persist};
        ito_store* store = nullptr;
        CHECK(ito_store_open(context, base.data(), base.size(), &persistence,
                             &store) == ITO_STATUS_OK);
        ito_status status = ITO_STATUS_OK;
        t_secret = seed.data();
        t_secret_freed = false;
        const bool failed = fails_at(n, [&] {
            status =
                ito_store_add_contact(store, alice.key, "alice", 5, now_unix());
        });
        t_secret = nullptr;
        CHECK(!t_secret_freed);
        size_t count = 0U;
        CHECK(ito_store_contact_count(store, &count) == ITO_STATUS_OK);
        if (!failed) {
            CHECK(n > 1U);  // At least one injected failure was exercised.
            CHECK(status == ITO_STATUS_OK && count == 1U);
            CHECK(ito_store_destroy(store) == ITO_STATUS_OK);
            break;
        }
        CHECK(disk.bytes == base);
        CHECK(status == ITO_STATUS_OUT_OF_MEMORY && count == 0U);
        CHECK(ito_store_add_contact(store, alice.key, "alice", 5, now_unix()) ==
              ITO_STATUS_OK);
        CHECK(ito_store_destroy(store) == ITO_STATUS_OK);
    }

    // An invitation from a running initiator.
    Relay relay;
    Endpoint a_end{&relay, 0};
    ito_call* a = nullptr;
    CHECK(ito_call_create_initiator(alice.store, bob.key, &selected, 120,
                                    now_unix(), &a) == ITO_STATUS_OK);
    std::atomic<int> a_result{1};
    std::thread a_thread([&] {
        const ito_transport t = transport(a_end);
        a_result = ito_call_run(a, &t);
    });
    wait_for(a, [](const ito_call_status& s) {
        return (s.flags & ITO_CALL_FLAG_INVITATION_READY) != 0U;
    });
    uint8_t invitation[ITO_MAX_INVITATION_BYTES];
    size_t invitation_size = 0U;
    CHECK(ito_call_copy_invitation(a, invitation, sizeof(invitation),
                                   &invitation_size) == ITO_STATUS_OK);

    ito_invitation* opened = nullptr;
    for (std::size_t n = 1U;; ++n) {
        CHECK(n < kMaxInjections);
        ito_status status = ITO_STATUS_OK;
        const bool failed = fails_at(n, [&] {
            status = ito_invitation_open(bob.store, invitation, invitation_size,
                                         &selected, now_unix(), &opened);
        });
        if (!failed) {
            CHECK(n > 1U);  // At least one injected failure was exercised.
            CHECK(status == ITO_STATUS_OK && opened != nullptr);
            break;
        }
        CHECK(status == ITO_STATUS_OUT_OF_MEMORY && opened == nullptr);
    }
    // No failed opening kept a pending slot: seven more fit beside it.
    std::vector<ito_invitation*> others;
    for (int index = 0; index < 7; ++index) {
        ito_invitation* other = nullptr;
        CHECK(ito_invitation_open(bob.store, invitation, invitation_size,
                                  &selected, now_unix(),
                                  &other) == ITO_STATUS_OK);
        others.push_back(other);
    }
    ito_invitation* ninth = nullptr;
    CHECK(ito_invitation_open(bob.store, invitation, invitation_size, &selected,
                              now_unix(), &ninth) == ITO_STATUS_CAPACITY);
    for (ito_invitation* other : others) {
        CHECK(ito_invitation_destroy(other) == ITO_STATUS_OK);
    }

    // The caller keeps a usable invitation after every failure, and no
    // failure leaves an attempt registered.
    ito_call* b = nullptr;
    for (std::size_t n = 1U;; ++n) {
        CHECK(n < kMaxInjections);
        ito_status status = ITO_STATUS_OK;
        const bool failed = fails_at(n, [&] {
            status = ito_call_create_responder(bob.store, opened, &selected,
                                               now_unix(), &b);
        });
        if (!failed) {
            CHECK(n > 1U);  // At least one injected failure was exercised.
            CHECK(status == ITO_STATUS_OK && b != nullptr);
            break;
        }
        CHECK(status == ITO_STATUS_OUT_OF_MEMORY && b == nullptr);
        ito_invitation_info info{};
        info.struct_size = sizeof(info);
        CHECK(ito_invitation_info_get(opened, &info) == ITO_STATUS_OK &&
              info.profile == 1U);
    }
    std::memset(invitation, 0, sizeof(invitation));
    CHECK(ito_call_destroy(b) == ITO_STATUS_OK);
    CHECK(ito_call_cancel(a) == ITO_STATUS_OK);
    a_thread.join();
    CHECK(a_result == ITO_STATUS_CANCELED);
    CHECK(ito_call_destroy(a) == ITO_STATUS_OK);
    CHECK(ito_store_destroy(alice.store) == ITO_STATUS_OK);
    CHECK(ito_store_destroy(bob.store) == ITO_STATUS_OK);
}

}  // namespace

int main() {
    ito_context* context = nullptr;
    CHECK(ito_context_create(&context) == ITO_STATUS_OK);
    test_refusals(context);
    test_call(context);
    test_call(context, 2U);
    test_cancel_while_running(context);
    test_allocation_failures(context);
    test_clock_refusal(context);
    CHECK(ito_context_destroy(context) == ITO_STATUS_OK);
    std::puts("api tests passed");
    return 0;
}
