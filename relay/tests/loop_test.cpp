/* Copyright (C) 2026 FixCraft Inc. AGPL-3.0-or-later. */

// The event loop with lowered limits, for bounds the real program reaches
// only after 30 minutes or with a failing random source. Clients connect to
// a real UNIX listener as the trusted daemon user would.

#include "relay.hpp"

#include <poll.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <unistd.h>

#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <thread>
#include <vector>

#include "check.hpp"
#include "sequence_tokens.hpp"

namespace {

using namespace ito::relay;
using ito::relay::testing::SequenceTokens;
using namespace std::chrono_literals;
using Bytes = std::vector<std::byte>;

Bytes text(std::string_view value) {
    Bytes out;
    for (const char character : value)
        out.push_back(static_cast<std::byte>(character));
    return out;
}

void send_all(int socket, const Bytes& bytes) {
    std::size_t sent = 0U;
    while (sent < bytes.size()) {
        const ssize_t count = ::send(socket, bytes.data() + sent,
                                     bytes.size() - sent, MSG_NOSIGNAL);
        CHECK(count > 0);
        sent += static_cast<std::size_t>(count);
    }
}

// Reads until count bytes, end of stream or the deadline. eof reports which.
Bytes receive(int socket, std::size_t count, std::chrono::milliseconds limit,
              bool* eof = nullptr) {
    Bytes out;
    const auto deadline = std::chrono::steady_clock::now() + limit;
    if (eof) *eof = false;
    while (out.size() < count) {
        const auto left = std::chrono::duration_cast<std::chrono::milliseconds>(
            deadline - std::chrono::steady_clock::now());
        if (left <= 0ms) break;
        pollfd ready{socket, POLLIN, 0};
        if (::poll(&ready, 1U, static_cast<int>(left.count())) <= 0) break;
        std::byte buffer[16384];
        const ssize_t got = ::recv(
            socket, buffer, std::min(sizeof buffer, count - out.size()), 0);
        if (got <= 0) {
            if (eof) *eof = true;
            break;
        }
        out.insert(out.end(), buffer, buffer + got);
    }
    return out;
}

Bytes reserve_frame(std::uint32_t lifetime) {
    return {std::byte{0},
            std::byte{0},
            std::byte{0},
            std::byte{7},
            std::byte{1},
            std::byte{0},
            std::byte{1},
            static_cast<std::byte>(lifetime >> 24U),
            static_cast<std::byte>(lifetime >> 16U),
            static_cast<std::byte>(lifetime >> 8U),
            static_cast<std::byte>(lifetime)};
}

Bytes join_frame(std::span<const std::byte> room, Role role,
                 std::span<const std::byte> token) {
    Bytes out{std::byte{0}, std::byte{0}, std::byte{0}, std::byte{68},
              std::byte{3}, std::byte{0}, std::byte{1}};
    out.insert(out.end(), room.begin(), room.end());
    out.push_back(static_cast<std::byte>(role));
    out.insert(out.end(), token.begin(), token.end());
    return out;
}

const Bytes kWaiting{std::byte{0}, std::byte{0}, std::byte{0}, std::byte{1},
                     std::byte{4}};
const Bytes kPaired{std::byte{0}, std::byte{0}, std::byte{0}, std::byte{1},
                    std::byte{5}};

class Fixture final {
public:
    Fixture(Limits limits, TokenSource& tokens) {
        char pattern[] = "/tmp/ito-relay-loop-XXXXXX";
        CHECK(::mkdtemp(pattern) != nullptr);
        directory_ = pattern;
        path_ = directory_ + "/relay.sock";
        const int listener = ::socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
        CHECK(listener >= 0);
        sockaddr_un address{};
        address.sun_family = AF_UNIX;
        std::strncpy(address.sun_path, path_.c_str(),
                     sizeof address.sun_path - 1U);
        CHECK(::bind(listener, reinterpret_cast<sockaddr*>(&address),
                     sizeof address) == 0);
        CHECK(::chmod(path_.c_str(), 0600) == 0);
        CHECK(::listen(listener, 64) == 0);
        relay_ = Relay::create(
            listener, -1, RelayOptions{"ito-call", ::getuid(), limits}, tokens);
        CHECK(relay_ != nullptr);
        thread_ = std::thread([this] { result_ = relay_->run(); });
    }
    ~Fixture() { finish(); }

    // Stops the loop and returns its final counts.
    RelayCounts finish() {
        if (thread_.joinable()) {
            relay_->stop();
            thread_.join();
            CHECK(result_ == 0);
            ::unlink(path_.c_str());
            ::rmdir(directory_.c_str());
        }
        return relay_->counts();
    }

    int connect(char fingerprint_digit) const {
        const int socket = ::socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
        CHECK(socket >= 0);
        sockaddr_un address{};
        address.sun_family = AF_UNIX;
        std::strncpy(address.sun_path, path_.c_str(),
                     sizeof address.sun_path - 1U);
        CHECK(::connect(socket, reinterpret_cast<sockaddr*>(&address),
                        sizeof address) == 0);
        send_all(socket,
                 text("yume-module 1 " + std::string(64U, fingerprint_digit) +
                      " ito-call\n"));
        return socket;
    }

    // Reserves, joins both roles and reads both PAIRED replies.
    std::pair<int, int> pair() const {
        const int initiator = connect('a');
        send_all(initiator, reserve_frame(60'000U));
        const Bytes reserved = receive(initiator, kReservedFrameBytes, 2s);
        CHECK(reserved.size() == kReservedFrameBytes &&
              reserved[4] == std::byte{2});
        const std::span<const std::byte> room(reserved.data() + 5, 32U);
        send_all(initiator, join_frame(room, Role::Initiator,
                                       std::span<const std::byte>(
                                           reserved.data() + 37, 32U)));
        CHECK(receive(initiator, kWaiting.size(), 2s) == kWaiting);
        const int responder = connect('b');
        send_all(responder, join_frame(room, Role::Responder,
                                       std::span<const std::byte>(
                                           reserved.data() + 69, 32U)));
        Bytes expected = kWaiting;
        expected.insert(expected.end(), kPaired.begin(), kPaired.end());
        CHECK(receive(responder, expected.size(), 2s) == expected);
        CHECK(receive(initiator, kPaired.size(), 2s) == kPaired);
        return {initiator, responder};
    }

private:
    std::string directory_;
    std::string path_;
    std::unique_ptr<Relay> relay_;
    std::thread thread_;
    int result_{-1};
};

Limits small_limits() {
    Limits limits;
    limits.max_streams = 16U;
    limits.max_total_queued_bytes = 16U * limits.queue_bytes_per_direction;
    limits.drain_budget = 200ms;
    return limits;
}

void test_paired_lifetime() {
    SequenceTokens tokens;
    auto limits = small_limits();
    limits.paired_lifetime = 400ms;
    Fixture relay(limits, tokens);
    const auto start = std::chrono::steady_clock::now();
    const auto [initiator, responder] = relay.pair();
    send_all(initiator, text("before the deadline"));
    CHECK(receive(responder, 19U, 2s) == text("before the deadline"));
    bool initiator_eof = false;
    bool responder_eof = false;
    CHECK(receive(initiator, 1U, 3s, &initiator_eof).empty() && initiator_eof);
    CHECK(receive(responder, 1U, 3s, &responder_eof).empty() && responder_eof);
    const auto elapsed = std::chrono::steady_clock::now() - start;
    CHECK(elapsed >= 400ms && elapsed < 2s);
    ::close(initiator);
    ::close(responder);
    std::this_thread::sleep_for(300ms);
    const auto counts = relay.finish();
    CHECK(counts.paired_ends[static_cast<std::size_t>(
              EndReason::LifetimeExpired)] == 1U);
    CHECK(counts.rooms == 0U && counts.streams == 0U &&
          counts.queued_bytes == 0U);
}

void test_byte_limit_delivers_exactly_the_cap() {
    SequenceTokens tokens;
    auto limits = small_limits();
    limits.max_forwarded_bytes_per_direction = 10'000U;
    Fixture relay(limits, tokens);
    const auto [initiator, responder] = relay.pair();
    Bytes payload(25'000U);
    for (std::size_t index = 0U; index < payload.size(); ++index)
        payload[index] = static_cast<std::byte>(index * 7U + 3U);
    // The relay closes the sender once the cap is reached.
    for (std::size_t sent = 0U; sent < payload.size();) {
        const ssize_t count = ::send(initiator, payload.data() + sent,
                                     payload.size() - sent, MSG_NOSIGNAL);
        if (count <= 0) break;
        sent += static_cast<std::size_t>(count);
    }
    bool eof = false;
    const Bytes received = receive(responder, payload.size(), 3s, &eof);
    CHECK(eof);
    CHECK(received.size() == 10'000U);
    CHECK(std::equal(received.begin(), received.end(), payload.begin()));
    ::close(initiator);
    ::close(responder);
    std::this_thread::sleep_for(300ms);
    const auto counts = relay.finish();
    CHECK(counts.paired_ends[static_cast<std::size_t>(EndReason::ByteLimit)] ==
          1U);
    CHECK(counts.streams == 0U && counts.queued_bytes == 0U);
}

void test_stall_needs_pending_output() {
    SequenceTokens tokens;
    auto limits = small_limits();
    limits.stall_timeout = 300ms;
    Fixture relay(limits, tokens);
    // An idle pair outlives the stall timeout several times over.
    const auto [quiet_initiator, quiet_responder] = relay.pair();
    std::this_thread::sleep_for(1200ms);
    send_all(quiet_initiator, text("still here"));
    CHECK(receive(quiet_responder, 10U, 2s) == text("still here"));

    // A reader that never reads, with output queued, ends both streams.
    const auto [writer, sleeper] = relay.pair();
    Bytes chunk(65536U, std::byte{0x5a});
    const auto start = std::chrono::steady_clock::now();
    bool closed = false;
    while (std::chrono::steady_clock::now() - start < 4s) {
        pollfd ready{writer, POLLOUT, 0};
        if (::poll(&ready, 1U, 50) > 0) {
            if ((ready.revents & (POLLERR | POLLHUP)) != 0) {
                closed = true;
                break;
            }
            if (::send(writer, chunk.data(), chunk.size(),
                       MSG_NOSIGNAL | MSG_DONTWAIT) < 0 &&
                errno != EAGAIN) {
                closed = true;
                break;
            }
        }
    }
    CHECK(closed);
    const auto elapsed = std::chrono::steady_clock::now() - start;
    CHECK(elapsed >= 300ms && elapsed < 3s);
    // The quiet pair is untouched by its neighbour's stall.
    send_all(quiet_responder, text("ok"));
    CHECK(receive(quiet_initiator, 2U, 2s) == text("ok"));
    for (const int socket : {quiet_initiator, quiet_responder, writer, sleeper})
        ::close(socket);
    std::this_thread::sleep_for(300ms);
    const auto counts = relay.finish();
    CHECK(counts.paired_ends[static_cast<std::size_t>(EndReason::Stalled)] ==
          1U);
    CHECK(counts.paired_ends[static_cast<std::size_t>(EndReason::PeerClosed)] ==
          1U);
    CHECK(counts.peak_queued_bytes <= limits.max_total_queued_bytes);
    CHECK(counts.streams == 0U && counts.rooms == 0U);
}

void test_random_failure_refuses_capacity() {
    SequenceTokens tokens;
    tokens.fail = true;
    Fixture relay(small_limits(), tokens);
    const int client = relay.connect('c');
    send_all(client, reserve_frame(1'000U));
    bool eof = false;
    const Bytes reply = receive(client, 7U, 2s, &eof);
    const Bytes refused{std::byte{0}, std::byte{0}, std::byte{0},
                        std::byte{2}, std::byte{7}, std::byte{3}};
    CHECK(reply == refused && eof);
    ::close(client);
    std::this_thread::sleep_for(300ms);
    const auto counts = relay.finish();
    CHECK(counts.refusals[2] == 1U && counts.rooms == 0U &&
          counts.reservations == 0U);
}

}  // namespace

int main() {
    test_paired_lifetime();
    test_byte_limit_delivers_exactly_the_cap();
    test_stall_needs_pending_output();
    test_random_failure_refuses_capacity();
    std::puts("loop: all checks passed");
    return 0;
}
