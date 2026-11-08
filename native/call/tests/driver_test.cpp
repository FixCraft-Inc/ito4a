/* Copyright (C) 2026 FixCraft Inc. AGPL-3.0-or-later. */

// The run loop when a platform transport throws: the attempt still ends
// typed, the session is no longer running and the attempt slot is free.
// The C interface's transports cannot throw, so this uses the driver's own
// C++ transport interface.

#include <cstdio>
#include <memory>
#include <new>
#include <stdexcept>
#include <string_view>

#include "driver.hpp"
#include "engine_harness.hpp"

using namespace ito::call;
using namespace ito::call::testing;

namespace {

constexpr std::uint64_t kUnix0 = 1'800'000'000U;

template <class Escape>
class ThrowingTransport final : public Transport {
public:
    IoResult read(std::span<Byte>, std::size_t& received,
                  Milliseconds) override {
        received = 0U;
        return IoResult::Timeout;
    }
    IoResult write(ByteView, Milliseconds) override { throw Escape{}; }
};

struct Broken final : std::runtime_error {
    Broken() : std::runtime_error("transport broke") {}
};

class WriteEndTransport final : public Transport {
public:
    explicit WriteEndTransport(IoResult result) : result_(result) {}
    IoResult read(std::span<Byte>, std::size_t& received,
                  Milliseconds) override {
        ++reads;
        received = 0U;
        return IoResult::Timeout;
    }
    IoResult write(ByteView, Milliseconds) override {
        ++writes;
        return result_;
    }
    std::size_t reads{0U};
    std::size_t writes{0U};

private:
    IoResult result_;
};

void test_write_end(const CryptoContext& crypto) {
    for (const auto terminal : {IoResult::Eof, IoResult::Failed}) {
        Identity alice(crypto, kUnix0);
        Identity bob(crypto, kUnix0);
        befriend(crypto, alice, bob, kUnix0);
        std::unique_ptr<CallEngine> engine;
        CHECK(CallEngine::create_initiator(*alice.store, bob.key, authority(),
                                           60, kUnix0, Clock::now(),
                                           engine) == Status::Ok);
        CallSession session(std::move(engine));
        WriteEndTransport transport(terminal);
        CHECK(session.run(transport) == Status::TransportFailed);
        CHECK(session.snapshot().state == CallState::Closed);
        CHECK(!session.running());
        // A terminal write must not be followed by the read whose capacity
        // was sampled before that write.
        CHECK(transport.writes == 1U && transport.reads == 0U);
        CHECK(CallEngine::create_initiator(*alice.store, bob.key, authority(),
                                           60, kUnix0, Clock::now(),
                                           engine) == Status::Ok);
    }
}

template <class Escape>
void test_escape(const CryptoContext& crypto, Status expected) {
    Identity alice(crypto, kUnix0);
    Identity bob(crypto, kUnix0);
    befriend(crypto, alice, bob, kUnix0);
    std::unique_ptr<CallEngine> engine;
    CHECK(CallEngine::create_initiator(*alice.store, bob.key, authority(), 60,
                                       kUnix0, Clock::now(),
                                       engine) == Status::Ok);
    CallSession session(std::move(engine));
    // The first write, RESERVE, throws.
    ThrowingTransport<Escape> transport;
    Status result = Status::Ok;
    bool escaped = false;
    try {
        result = session.run(transport);
    } catch (...) {
        escaped = true;
    }
    CHECK(!escaped && result == expected);
    const CallSnapshot snapshot = session.snapshot();
    CHECK(snapshot.state == CallState::Closed && snapshot.result == expected);
    CHECK(!session.running());
    std::unique_ptr<CallEngine> next;
    CHECK(CallEngine::create_initiator(*alice.store, bob.key, authority(), 60,
                                       kUnix0, Clock::now(),
                                       next) == Status::Ok);
}

}  // namespace

int main(int argc, char** argv) {
    const CryptoContext crypto;
    if (argc == 2 && std::string_view(argv[1]) == "--write-end") {
        test_write_end(crypto);
        std::puts("driver write-end tests passed");
        return 0;
    }
    test_escape<Broken>(crypto, Status::Internal);
    test_escape<std::bad_alloc>(crypto, Status::OutOfMemory);
    test_write_end(crypto);
    std::puts("driver tests passed");
    return 0;
}
