/* Copyright (C) 2026 FixCraft Inc. AGPL-3.0-or-later. */

// Real C++/OpenSSL allocation failures and before-free secret observations.
// Hooks belong to this executable only, linked to the production core objects.
#include <openssl/crypto.h>
#include <openssl/err.h>
#include <openssl/rand.h>

#include <algorithm>
#include <array>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <cerrno>
#include <memory>
#include <new>
#include <string_view>

#include "check.hpp"
#include "driver.hpp"
#include "engine_harness.hpp"

namespace {
using namespace ito::call;
using namespace ito::call::testing;
struct alignas(std::max_align_t) Header { std::size_t size; };
thread_local bool observing = false;
thread_local bool openssl_failure = false;
thread_local std::size_t fail_at = 0, allocations = 0, hits = 0, freed = 0;
thread_local bool secret_freed = false;
thread_local unsigned secret_mask = 0;
thread_local bool capture_reserved_psk = false;
thread_local unsigned random_draws = 0;
thread_local bool reserved_psk_captured = false;
std::array<Id, 3> secrets{};

bool fail(bool openssl) noexcept {
    if (!observing || openssl != openssl_failure) return false;
    if (++allocations != fail_at || fail_at == 0) return false;
    ++hits;
    errno = ENOMEM;
    return true;
}
void* backing(std::size_t size) noexcept {
    if (size > SIZE_MAX - sizeof(Header)) return nullptr;
    auto* h = static_cast<Header*>(std::malloc(sizeof(Header) + size));
    if (h == nullptr) return nullptr;
    h->size = size;
    return h + 1;
}
void* allocate(std::size_t size, bool openssl) noexcept {
    return fail(openssl) ? nullptr : backing(size);
}
void release(void* ptr) noexcept {
    if (ptr == nullptr) return;
    auto* h = static_cast<Header*>(ptr) - 1;
    if (observing) {
        ++freed;
        const auto* bytes = static_cast<const Byte*>(ptr);
        for (std::size_t i = 0; i < secrets.size(); ++i) {
            const Id& secret = secrets[i];
            if (std::search(bytes, bytes + h->size, secret.begin(), secret.end()) != bytes + h->size)
                { secret_freed = true; secret_mask |= 1U << i; }
        }
    }
    std::free(h);
}
void* ssl_malloc(std::size_t size, const char* file, int line) {
    if (observing && openssl_failure && fail_at != 0 && allocations + 1 == fail_at)
        std::fprintf(stderr, "OpenSSL malloc failure at %s:%d size=%zu\n", file, line, size);
    return allocate(size, true);
}
void ssl_free(void* ptr, const char*, int) { release(ptr); }
void* ssl_realloc(void* ptr, std::size_t size, const char* file, int line) {
    if (observing && openssl_failure && fail_at != 0 && allocations + 1 == fail_at)
        std::fprintf(stderr, "OpenSSL realloc failure at %s:%d size=%zu\n", file, line, size);
    if (ptr == nullptr) return allocate(size, true);
    if (size == 0) { release(ptr); return nullptr; }
    // Realloc failure must retain the original allocation. Inspect the old
    // bytes only when they are actually released, before libc touches them.
    if (fail(true)) return nullptr;
    auto* old = static_cast<Header*>(ptr) - 1;
    void* next = backing(size);
    if (next == nullptr) return nullptr;
    std::memcpy(next, ptr, std::min(old->size, size));
    release(ptr);
    return next;
}
void arm(std::size_t index, bool ssl) {
    allocations = hits = freed = 0;
    random_draws = 0;
    reserved_psk_captured = false;
    secret_freed = false;
    secret_mask = 0;
    fail_at = index;
    openssl_failure = ssl;
    observing = true;
}
void disarm() { observing = false; }

class BrokenRelay final : public Transport {
public:
    explicit BrokenRelay(bool initiator) : initiator_(initiator) {
        // RESERVED: room, two bearer tokens, granted lifetime. Fixed arrays
        // stay alive outside the observed heap; no fixture copy is freed.
        reserved_ = {};
        reserved_[3] = 101;
        reserved_[4] = 2;
        std::fill_n(reserved_.begin() + 5, 32, Byte{0x19});
        std::copy(secrets[0].begin(), secrets[0].end(), reserved_.begin() + 37);
        std::copy(secrets[1].begin(), secrets[1].end(), reserved_.begin() + 69);
        reserved_[101] = 0; reserved_[102] = 0; reserved_[103] = 0x75; reserved_[104] = 0x30;
    }
    IoResult write(ByteView, Milliseconds) override {
        return writes_++ == 0 && initiator_ ? IoResult::Ok : IoResult::Failed;
    }
    IoResult read(std::span<Byte> out, std::size_t& size, Milliseconds) override {
        if (!initiator_ || reads_++ != 0) return IoResult::Failed;
        CHECK(out.size() >= reserved_.size());
        std::copy(reserved_.begin(), reserved_.end(), out.begin());
        size = reserved_.size();
        return IoResult::Ok;
    }
private:
    bool initiator_;
    unsigned writes_ = 0, reads_ = 0;
    std::array<Byte, 105> reserved_{};
};

// Each invocation gets a fresh process. Sweep indices externally so a failed
// OpenSSL initialization never contaminates another injection's global state.
int run_case(std::string_view name, std::size_t index) {
    for (std::size_t s = 0; s < secrets.size(); ++s)
        for (std::size_t b = 0; b < secrets[s].size(); ++b)
            secrets[s][b] = static_cast<Byte>(0x80U + s * 33U + b);
    if (name == "observer") {
        arm(0, false);
        auto* copy = static_cast<Byte*>(allocate(32, false));
        CHECK(copy != nullptr);
        std::copy(secrets[0].begin(), secrets[0].end(), copy);
        release(copy);
        disarm();
        CHECK(secret_freed);
        std::puts("observer detects live sentinel before free");
        return 0;
    }
    CHECK(CRYPTO_set_mem_functions(ssl_malloc, ssl_realloc, ssl_free) == 1);
    if (name == "context") {
        arm(index, true);
        try { CryptoContext crypto; }
        catch (const CryptoFailure&) { CHECK(hits == 1); }
        disarm();
        CHECK(!secret_freed);
        std::printf("case=context index=%zu allocations=%zu hit=%zu freed=%zu clean=1\n", index, allocations, hits, freed);
        return 0;
    }
    CryptoContext crypto;
    Psk psk; psk.assign(secrets[2]);
    Id identity{}; identity.fill(0x13);
    bool valid = false;
    if (name == "tls-client" || name == "tls-server" || name == "sign") {
        // Warm only the context's RNG/provider; each actual operation remains
        // fresh, including TLS objects, session callbacks and signing state.
        Key random{}; crypto.random(random);
        Seed seed; seed.assign(secrets[2]);
        const Key key = crypto.public_key(seed);
        std::array<Byte, 8192> hello{};
        std::size_t hello_size = 0;
        if (name == "tls-server") {
            TlsChannel client(crypto, TlsRole::Client, identity, psk);
            CHECK(client.handshake() == TlsChannel::Step::Pending);
            hello_size = client.take_output(hello);
            CHECK(hello_size != 0);
        }
        arm(index, true);
        try {
            if (name == "sign") {
                const Signature signature = crypto.sign(seed, identity);
                valid = crypto.verify(key, identity, signature) == Verification::Valid;
            } else {
                auto tls = std::make_unique<TlsChannel>(crypto,
                    name == "tls-client" ? TlsRole::Client : TlsRole::Server, identity, psk);
                if (hello_size != 0) tls->put_inbound(ByteView(hello.data(), hello_size));
                const auto step = tls->handshake();
                if (index == 0) CHECK(tls->state().supplied);
                valid = step == TlsChannel::Step::Pending ||
                        (hits == 1 && step == TlsChannel::Step::Failed);
            }
        } catch (const CryptoFailure&) {
            valid = hits == 1;
        }
        disarm();
    } else {
        CHECK(name == "reserved" || name == "join");
        const auto now = Clock::now();
        constexpr std::uint64_t wall = 2000000;
        Identity a(crypto, wall), b(crypto, wall);
        befriend(crypto, a, b, wall);
        std::unique_ptr<CallEngine> engine;
        std::unique_ptr<OpenedInvitation> opened;
        Invitation invitation;
        if (name == "reserved") {
            CHECK(CallEngine::create_initiator(*a.store, b.key, authority(), 30, wall, now, engine) == Status::Ok);
        } else {
            invitation.room_id.fill(0x19);
            invitation.responder_token = secrets[1];
            invitation.initiator = a.key; invitation.responder = b.key;
            invitation.authority = authority();
            ClockAdmission admission;
            CHECK(a.store->admit_clock(wall, now, admission) == Status::Ok);
            CHECK(sign_new_invitation(*a.store, invitation, admission, now, 30) == Status::Ok);
            // The real, signed PSK is observed through parsing, handoff and
            // run failure; the caller's encoding stays alive while armed.
            std::copy_n(invitation.psk.data(), 32, secrets[2].begin());
        }
        BrokenRelay transport(name == "reserved");
        arm(index, false);
        capture_reserved_psk = name == "reserved";
        try {
            if (name == "join") {
                CHECK(open_invitation(*b.store, invitation.encoded.view(), authority(), wall, now, opened) == Status::Ok);
                CHECK(CallEngine::create_responder(*b.store, opened, authority(), wall, now, engine) == Status::Ok);
            }
            {
                CallSession session(std::move(engine));
                const Status result = session.run(transport);
                CHECK(result == Status::TransportFailed || result == Status::OutOfMemory ||
                      (hits == 1 && result == Status::StorageAmbiguous));
                valid = true;
            }
        } catch (const std::bad_alloc&) {
            opened.reset(); engine.reset();
            valid = hits == 1;
        }
        disarm();
        if (name == "reserved") {
            if (index == 0) CHECK(random_draws == 2 && reserved_psk_captured);
            std::fprintf(stderr, "reserved random draws=%u psk_captured=%d\n",
                         random_draws, static_cast<int>(reserved_psk_captured));
        }
        capture_reserved_psk = false;
        CHECK(!a.store->borrowed() && !b.store->borrowed());
    }
    CHECK(valid);
    if (secret_freed) std::fprintf(stderr, "before-free sentinel mask=%u (token1=1 token2=2 psk=4)\n", secret_mask);
    CHECK(!secret_freed);
    CHECK(freed > 0 || hits == 1);
    std::printf("case=%.*s index=%zu allocations=%zu hit=%zu freed=%zu clean=1\n",
                static_cast<int>(name.size()), name.data(), index, allocations, hits, freed);
    return 0;
}
}  // namespace

// Linker wrapping belongs only to this executable. The real RNG runs first;
// the invitation ID is its first draw and its independent PSK is the second.
extern "C" int __real_RAND_bytes_ex(OSSL_LIB_CTX*, unsigned char*, std::size_t, unsigned int);
extern "C" int __wrap_RAND_bytes_ex(OSSL_LIB_CTX* context, unsigned char* out,
                                    std::size_t size, unsigned int strength) {
    const int result = __real_RAND_bytes_ex(context, out, size, strength);
    if (result == 1 && observing && capture_reserved_psk) {
        CHECK(size == secrets[2].size());
        if (++random_draws == 2) {
            std::copy_n(out, size, secrets[2].begin());
            reserved_psk_captured = true;
        }
        CHECK(random_draws <= 2);
    }
    return result;
}

void* operator new(std::size_t size) {
    void* result = allocate(size, false);
    if (result == nullptr) throw std::bad_alloc();
    return result;
}
void* operator new[](std::size_t size) { return ::operator new(size); }
void operator delete(void* ptr) noexcept { release(ptr); }
void operator delete[](void* ptr) noexcept { release(ptr); }
void operator delete(void* ptr, std::size_t) noexcept { release(ptr); }
void operator delete[](void* ptr, std::size_t) noexcept { release(ptr); }
int main(int argc, char** argv) {
    CHECK(std::atexit([] { for (auto& secret : secrets) wipe(secret.data(), secret.size()); }) == 0);
    CHECK(argc == 3);
    char* end = nullptr;
    const auto index = std::strtoull(argv[2], &end, 10);
    CHECK(end != argv[2] && *end == '\0' && index < 100000);
    return run_case(argv[1], static_cast<std::size_t>(index));
}
