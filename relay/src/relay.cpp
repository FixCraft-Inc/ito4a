/* Copyright (C) 2026 FixCraft Inc. AGPL-3.0-or-later. */

#include "relay.hpp"

#include <fcntl.h>
#include <sys/epoll.h>
#include <sys/eventfd.h>
#include <sys/signalfd.h>
#include <sys/socket.h>
#include <unistd.h>

#include <algorithm>
#include <cerrno>
#include <csignal>
#include <cstring>
#include <limits>
#include <map>
#include <new>
#include <optional>
#include <set>
#include <span>
#include <utility>
#include <variant>
#include <vector>

#include "listener.hpp"

namespace ito::relay {
namespace {

constexpr std::uint64_t kListenerKey = 1U;
constexpr std::uint64_t kSignalKey = 2U;
constexpr std::uint64_t kWakeKey = 3U;
constexpr StreamId kFirstStream = 16U;

// A module header and one maximal control frame. Requests are parsed after
// every read, so unparsed input never exceeds one partial frame.
constexpr std::size_t kControlInputBytes = kMaxHeaderBytes + kMaxFrameBytes;
// RESERVED, WAITING and PAIRED, or RESERVED, WAITING and REFUSED, are the
// most control output a stream holds at once.
constexpr std::size_t kControlOutputBytes =
    kReservedFrameBytes + 2U * kSignalFrameBytes + kRefusedFrameBytes;
constexpr std::size_t kAcceptsPerPass = 64U;
// Per-wake work bounds, so one busy stream cannot starve the others.
constexpr std::size_t kForwardBytesPerPass = 256U * 1024U;
constexpr std::size_t kDiscardBytesPerPass = 64U * 1024U;
constexpr int kEventsPerWait = 64;
constexpr std::chrono::milliseconds kAcceptRetry{100};

class Descriptor final {
public:
    Descriptor() noexcept = default;
    explicit Descriptor(int descriptor) noexcept : descriptor_(descriptor) {}
    Descriptor(Descriptor&& other) noexcept
        : descriptor_(std::exchange(other.descriptor_, -1)) {}
    Descriptor& operator=(Descriptor&& other) noexcept {
        if (this != &other) {
            reset();
            descriptor_ = std::exchange(other.descriptor_, -1);
        }
        return *this;
    }
    Descriptor(const Descriptor&) = delete;
    Descriptor& operator=(const Descriptor&) = delete;
    ~Descriptor() { reset(); }

    [[nodiscard]] int get() const noexcept { return descriptor_; }
    void reset() noexcept {
        if (descriptor_ >= 0) ::close(descriptor_);
        descriptor_ = -1;
    }

private:
    int descriptor_{-1};
};

// One direction's opaque queue, allocated when its room pairs.
class ByteRing final {
public:
    [[nodiscard]] bool allocate(std::size_t capacity) noexcept {
        bytes_.reset(new (std::nothrow) std::byte[capacity]);
        capacity_ = bytes_ ? capacity : 0U;
        head_ = size_ = 0U;
        return bytes_ != nullptr;
    }
    [[nodiscard]] bool allocated() const noexcept { return bytes_ != nullptr; }
    [[nodiscard]] std::size_t size() const noexcept { return size_; }
    [[nodiscard]] std::size_t free() const noexcept {
        return capacity_ - size_;
    }

    // The contiguous free region after the queued bytes, at most limit long.
    [[nodiscard]] std::span<std::byte> free_span(std::size_t limit) noexcept {
        if (size_ == capacity_) return {};
        const std::size_t tail = (head_ + size_) % capacity_;
        const std::size_t contiguous =
            tail >= head_ ? capacity_ - tail : head_ - tail;
        return {bytes_.get() + tail, std::min(contiguous, limit)};
    }
    void commit(std::size_t count) noexcept { size_ += count; }

    [[nodiscard]] std::span<const std::byte> data_span() const noexcept {
        return {bytes_.get() + head_, std::min(size_, capacity_ - head_)};
    }
    void consume(std::size_t count) noexcept {
        head_ = (head_ + count) % capacity_;
        size_ -= count;
        if (size_ == 0U) head_ = 0U;
    }

private:
    std::unique_ptr<std::byte[]> bytes_;
    std::size_t capacity_{0U};
    std::size_t head_{0U};
    std::size_t size_{0U};
};

enum class Phase {
    // Reading the module header.
    Header,
    // Header accepted, the first request expected.
    Control,
    // RESERVED sent, JOIN or CANCEL expected.
    Reserved,
    // JOIN admitted, PAIRED or CANCEL pending.
    Waiting,
    // Forwarding opaque bytes.
    Paired,
    // Flushing accepted output, then awaiting end of stream until the
    // drain deadline. Input is read and discarded.
    Draining,
};

bool in_control(Phase phase) noexcept {
    return phase == Phase::Header || phase == Phase::Control ||
           phase == Phase::Reserved || phase == Phase::Waiting;
}

// A reserving or waiting stream may stay silent until its room's deadline.
bool may_idle(Phase phase) noexcept {
    return phase == Phase::Reserved || phase == Phase::Waiting;
}

struct Stream final {
    Stream(StreamId stream_id, Descriptor descriptor) noexcept
        : id(stream_id), socket(std::move(descriptor)) {}
    Stream(const Stream&) = delete;
    Stream& operator=(const Stream&) = delete;
    ~Stream() {
        ::explicit_bzero(input.data(), input.size());
        ::explicit_bzero(output.data(), output.size());
    }

    [[nodiscard]] bool pending_output() const noexcept {
        return output_begin < output_end || inbound.size() > 0U;
    }

    const StreamId id;
    Descriptor socket;
    Phase phase{Phase::Header};
    // Set while the room table counts this stream for its principal.
    std::optional<Principal> principal;
    // The room this stream reserved or joined, while that room exists.
    std::optional<RoomId> room;
    std::optional<StreamId> peer;
    // Control input: header and request bytes not yet parsed.
    std::array<std::byte, kControlInputBytes> input{};
    std::size_t input_size{0U};
    // Control output, written before any opaque byte.
    std::array<std::byte, kControlOutputBytes> output{};
    std::size_t output_begin{0U};
    std::size_t output_end{0U};
    // Opaque bytes from the peer, waiting to be written to this stream.
    ByteRing inbound;
    // Opaque bytes this stream sent toward its peer.
    std::uint64_t forwarded{0U};
    // Edge-triggered readiness: unread input may remain until a read
    // returns EAGAIN.
    bool read_ready{true};
    bool eof{false};
    bool write_shut{false};
    // Set when a write failed because the peer closed or reset. Nothing is
    // written afterwards, while its remaining input can still be read.
    bool write_broken{false};
    bool in_ready{false};
    bool in_dirty{false};
    std::optional<TimePoint> parse_deadline;
    std::optional<TimePoint> stall_deadline;
    std::optional<TimePoint> drain_deadline;
    // Its entry in the timer set.
    std::optional<TimePoint> scheduled;
};

std::optional<TimePoint> earliest(std::optional<TimePoint> left,
                                  std::optional<TimePoint> right) noexcept {
    if (!left) return right;
    if (!right) return left;
    return std::min(*left, *right);
}

void append_number(std::string& line, std::string_view name,
                   std::uint64_t value) {
    line += ",\"";
    line += name;
    line += "\":";
    line += std::to_string(value);
}

template <std::size_t N>
void append_list(std::string& line, std::string_view name,
                 const std::array<std::uint64_t, N>& values) {
    line += ",\"";
    line += name;
    line += "\":[";
    for (std::size_t index = 0U; index < N; ++index) {
        if (index > 0U) line += ',';
        line += std::to_string(values[index]);
    }
    line += ']';
}

}  // namespace

bool consistent_limits(const Limits& limits) noexcept {
    using std::chrono::milliseconds;
    if (limits.max_rooms == 0U || limits.max_streams == 0U ||
        limits.max_rooms_per_principal == 0U ||
        limits.max_streams_per_principal == 0U || limits.max_principals == 0U ||
        limits.max_forwarded_bytes_per_direction == 0U ||
        limits.max_streams > 65'536U)
        return false;
    if (limits.rate_window <= milliseconds::zero() ||
        limits.parse_budget <= milliseconds::zero() ||
        limits.stall_timeout <= milliseconds::zero() ||
        limits.paired_lifetime <= milliseconds::zero() ||
        limits.drain_budget <= milliseconds::zero())
        return false;
    // A queue receives a stream's leftover control input when its room pairs.
    if (limits.queue_bytes_per_direction < kControlInputBytes) return false;
    // Each stream holds its control input or the one queue it writes from,
    // so this product bounds every byte the service ever queues.
    return limits.max_streams <=
           limits.max_total_queued_bytes / limits.queue_bytes_per_direction;
}

std::string format_counts(const RelayCounts& counts) {
    std::string line = "{\"ito_call_relay\":\"counts\"";
    append_number(line, "streams", counts.streams);
    append_number(line, "principals", counts.principals);
    append_number(line, "rooms", counts.rooms);
    append_number(line, "paired_rooms", counts.paired_rooms);
    append_number(line, "queued_bytes", counts.queued_bytes);
    append_number(line, "peak_queued_bytes", counts.peak_queued_bytes);
    append_number(line, "accepted", counts.accepted);
    append_number(line, "untrusted_peers", counts.untrusted_peers);
    append_number(line, "over_capacity", counts.over_capacity);
    append_number(line, "rejected_headers", counts.rejected_headers);
    append_number(line, "reservations", counts.reservations);
    append_number(line, "pairings", counts.pairings);
    append_number(line, "closed_rooms", counts.closed_rooms);
    append_number(line, "unqueued_retentions", counts.unqueued_retentions);
    append_list(line, "refusals", counts.refusals);
    append_list(line, "paired_ends", counts.paired_ends);
    line += "}\n";
    return line;
}

struct Relay::State final {
    State(Descriptor listening, Descriptor signal_source,
          RelayOptions relay_options, TokenSource& tokens)
        : options(std::move(relay_options)),
          limits(options.limits),
          listener(std::move(listening)),
          signals(std::move(signal_source)),
          table(options.limits, tokens) {}

    int run();
    void dispatch(const epoll_event& event);
    [[nodiscard]] int wait_milliseconds() const noexcept;

    // Streams.
    Stream* find(StreamId id) noexcept;
    void accept_streams();
    void add_stream(Descriptor connection);
    void read(Stream& stream);
    void read_control(Stream& stream);
    void read_forward(Stream& stream);
    void read_discard(Stream& stream);
    void write(Stream& stream);
    void write_failed(Stream& stream);
    void close_stream(Stream& stream) noexcept;
    void close_all() noexcept;

    // Control protocol.
    void process_input(Stream& stream);
    void consume_input(Stream& stream, std::size_t count) noexcept;
    void admit(Stream& stream, const Principal& principal);
    void handle_request(Stream& stream, const Request& request);
    void reserve(Stream& stream, const ReserveRequest& request);
    void join(Stream& stream, const JoinRequest& request);
    void pair(const Admitted& admitted);
    void move_leftover(Stream& from, Stream& to) noexcept;
    [[nodiscard]] bool append_output(Stream& stream,
                                     std::span<const std::byte> bytes) noexcept;

    // Endings.
    void refuse(Stream& stream, RefusalReason reason);
    void leave_room(Stream& stream, RefusalReason reason_for_others);
    void drop_unpaired(Stream& stream);
    void end_paired(Stream& stream, EndReason reason, bool orderly);
    void begin_drain(Stream& stream);
    void release_principal(Stream& stream) noexcept;
    void abandon(Stream& stream);

    // Timers and scheduling.
    void fire_timers();
    void on_deadline(Stream& stream);
    void handle_expired(const ExpiredRoom& expired);
    void touch(Stream& stream) noexcept;
    void mark_ready(Stream& stream) noexcept;
    void run_ready();
    void settle();
    void reschedule(Stream& stream);

    void add_queued(std::size_t count) noexcept {
        queued_bytes += count;
        counted.peak_queued_bytes =
            std::max(counted.peak_queued_bytes, queued_bytes);
    }
    void sub_queued(std::size_t count) noexcept { queued_bytes -= count; }
    void read_signals();
    void report_counts() noexcept;
    [[nodiscard]] RelayCounts snapshot() const noexcept;

    const RelayOptions options;
    const Limits& limits;
    Descriptor listener;
    Descriptor signals;
    Descriptor wake;
    Descriptor epoll;
    RoomTable table;
    std::map<StreamId, std::unique_ptr<Stream>> streams;
    std::set<std::pair<TimePoint, StreamId>> timers;
    // Reserved to twice the stream limit at creation, so adding a stream id
    // never allocates. Each stream appears at most once in each.
    std::vector<StreamId> ready;
    std::vector<StreamId> ready_batch;
    std::vector<StreamId> dirty;
    std::vector<StreamId> dirty_batch;
    StreamId next_stream{kFirstStream};
    bool accept_pending{false};
    std::optional<TimePoint> accept_retry;
    bool stopping{false};
    std::size_t queued_bytes{0U};
    RelayCounts counted;
    TimePoint now{Clock::now()};
    std::array<std::byte, 16U * 1024U> scratch{};
};

std::unique_ptr<Relay> Relay::create(int listener, int signals,
                                     RelayOptions options,
                                     TokenSource& tokens) {
    Descriptor listening(listener);
    Descriptor signal_source(signals);
    if (!consistent_limits(options.limits) ||
        !valid_service_name(options.service))
        return nullptr;
    try {
        const int flags = ::fcntl(listening.get(), F_GETFL);
        if (flags < 0 ||
            ::fcntl(listening.get(), F_SETFL, flags | O_NONBLOCK) != 0)
            return nullptr;
        auto state = std::make_unique<State>(std::move(listening),
                                             std::move(signal_source),
                                             std::move(options), tokens);
        state->epoll = Descriptor(::epoll_create1(EPOLL_CLOEXEC));
        state->wake = Descriptor(::eventfd(0U, EFD_NONBLOCK | EFD_CLOEXEC));
        if (state->epoll.get() < 0 || state->wake.get() < 0) return nullptr;
        const auto watch = [&](int descriptor, std::uint64_t key,
                               std::uint32_t events) {
            epoll_event event{};
            event.events = events;
            event.data.u64 = key;
            return ::epoll_ctl(state->epoll.get(), EPOLL_CTL_ADD, descriptor,
                               &event) == 0;
        };
        if (!watch(state->listener.get(), kListenerKey, EPOLLIN | EPOLLET) ||
            !watch(state->wake.get(), kWakeKey, EPOLLIN) ||
            (state->signals.get() >= 0 &&
             !watch(state->signals.get(), kSignalKey, EPOLLIN)))
            return nullptr;
        const std::size_t ids = 2U * state->limits.max_streams;
        state->ready.reserve(ids);
        state->ready_batch.reserve(ids);
        state->dirty.reserve(ids);
        state->dirty_batch.reserve(ids);
        return std::unique_ptr<Relay>(new Relay(std::move(state)));
    } catch (const std::bad_alloc&) {
        return nullptr;
    }
}

Relay::Relay(std::unique_ptr<State> state) noexcept
    : state_(std::move(state)) {}
Relay::~Relay() = default;

int Relay::run() noexcept {
    try {
        return state_->run();
    } catch (...) {
        // An allocation failure the loop could not attribute to one stream.
        // Ending every call is the contract's service-crash outcome.
        state_->close_all();
        return 1;
    }
}

void Relay::stop() noexcept {
    const std::uint64_t one = 1U;
    [[maybe_unused]] const auto written =
        ::write(state_->wake.get(), &one, sizeof one);
}

RelayCounts Relay::counts() const noexcept {
    return state_->snapshot();
}

int Relay::State::run() {
    std::array<epoll_event, kEventsPerWait> events{};
    while (!stopping) {
        now = Clock::now();
        const int count = ::epoll_wait(epoll.get(), events.data(),
                                       kEventsPerWait, wait_milliseconds());
        if (count < 0) {
            if (errno == EINTR) continue;
            close_all();
            return 1;
        }
        now = Clock::now();
        for (std::size_t index = 0U;
             index < static_cast<std::size_t>(count) && !stopping; ++index)
            dispatch(events[index]);
        if (stopping) break;
        if (accept_retry && *accept_retry <= now) {
            accept_retry.reset();
            accept_pending = true;
        }
        if (accept_pending) accept_streams();
        run_ready();
        fire_timers();
        settle();
    }
    close_all();
    return 0;
}

int Relay::State::wait_milliseconds() const noexcept {
    if (accept_pending || !ready.empty()) return 0;
    std::optional<TimePoint> next = table.next_deadline();
    if (!timers.empty()) next = earliest(next, timers.begin()->first);
    next = earliest(next, accept_retry);
    if (!next) return -1;
    if (*next <= now) return 0;
    const auto wait =
        std::chrono::ceil<std::chrono::milliseconds>(*next - now).count();
    return static_cast<int>(
        std::min<std::int64_t>(wait, std::numeric_limits<int>::max()));
}

void Relay::State::dispatch(const epoll_event& event) {
    switch (event.data.u64) {
        case kListenerKey:
            accept_pending = true;
            return;
        case kWakeKey: {
            std::uint64_t value = 0U;
            [[maybe_unused]] const auto drained =
                ::read(wake.get(), &value, sizeof value);
            stopping = true;
            return;
        }
        case kSignalKey:
            read_signals();
            return;
        default:
            break;
    }
    const StreamId id = event.data.u64;
    Stream* stream = find(id);
    if (!stream) return;
    if ((event.events & (EPOLLIN | EPOLLRDHUP | EPOLLHUP | EPOLLERR)) != 0U)
        stream->read_ready = true;
    // Read first. A peer that sent its last bytes and closed both directions
    // arrives as HUP, and those bytes must be forwarded before a write to it
    // fails.
    if (stream->read_ready) {
        read(*stream);
        stream = find(id);
        if (!stream) return;
    }
    if ((event.events & (EPOLLOUT | EPOLLHUP | EPOLLERR)) != 0U) write(*stream);
}

Stream* Relay::State::find(StreamId id) noexcept {
    const auto found = streams.find(id);
    return found == streams.end() ? nullptr : found->second.get();
}

void Relay::State::accept_streams() {
    accept_pending = false;
    for (std::size_t pass = 0U; pass < kAcceptsPerPass; ++pass) {
        const int accepted = ::accept4(listener.get(), nullptr, nullptr,
                                       SOCK_NONBLOCK | SOCK_CLOEXEC);
        if (accepted < 0) {
            if (errno == EINTR || errno == ECONNABORTED) continue;
            if (errno != EAGAIN && errno != EWOULDBLOCK) {
                // Descriptor or memory exhaustion leaves the connection in
                // the listen queue. The edge will not repeat, so try again.
                accept_retry = now + kAcceptRetry;
            }
            return;
        }
        Descriptor connection(accepted);
        if (streams.size() >= limits.max_streams) {
            ++counted.over_capacity;
            continue;
        }
        ucred peer{};
        socklen_t length = sizeof peer;
        if (::getsockopt(connection.get(), SOL_SOCKET, SO_PEERCRED, &peer,
                         &length) != 0 ||
            length != sizeof peer ||
            !trusted_peer(peer.uid, options.daemon_uid)) {
            // Its header could be forged. Close before reading a byte.
            ++counted.untrusted_peers;
            continue;
        }
        add_stream(std::move(connection));
    }
    // The pass bound was reached. More connections may be queued.
    accept_pending = true;
}

void Relay::State::add_stream(Descriptor connection) {
    const StreamId id = next_stream++;
    std::unique_ptr<Stream> created;
    try {
        created = std::make_unique<Stream>(id, std::move(connection));
    } catch (const std::bad_alloc&) {
        return;
    }
    epoll_event event{};
    event.events = EPOLLIN | EPOLLOUT | EPOLLRDHUP | EPOLLET;
    event.data.u64 = id;
    if (::epoll_ctl(epoll.get(), EPOLL_CTL_ADD, created->socket.get(),
                    &event) != 0)
        return;
    // The header and the first request share one parse budget.
    created->parse_deadline = now + limits.parse_budget;
    Stream& stream = *created;
    try {
        streams.emplace(id, std::move(created));
    } catch (const std::bad_alloc&) {
        return;
    }
    ++counted.accepted;
    touch(stream);
    mark_ready(stream);
}

void Relay::State::read(Stream& stream) {
    switch (stream.phase) {
        case Phase::Header:
        case Phase::Control:
        case Phase::Reserved:
        case Phase::Waiting:
            read_control(stream);
            return;
        case Phase::Paired:
            read_forward(stream);
            return;
        case Phase::Draining:
            read_discard(stream);
            return;
    }
}

void Relay::State::read_control(Stream& first) {
    const StreamId id = first.id;
    for (;;) {
        Stream* stream = find(id);
        if (!stream) return;
        if (!in_control(stream->phase)) {
            // Pairing or a refusal changed the phase. Unread bytes are now
            // opaque or discarded input, read on the next pass.
            if (stream->read_ready) mark_ready(*stream);
            return;
        }
        const std::size_t space = kControlInputBytes - stream->input_size;
        if (space == 0U) {
            // Parsing always leaves less than one frame. Fail closed if not.
            abandon(*stream);
            return;
        }
        const ssize_t got =
            ::recv(stream->socket.get(),
                   stream->input.data() + stream->input_size, space, 0);
        if (got > 0) {
            stream->input_size += static_cast<std::size_t>(got);
            add_queued(static_cast<std::size_t>(got));
            process_input(*stream);
            continue;
        }
        if (got == 0) {
            stream->eof = true;
            drop_unpaired(*stream);
            return;
        }
        if (errno == EINTR) continue;
        if (errno == EAGAIN || errno == EWOULDBLOCK) {
            stream->read_ready = false;
            return;
        }
        drop_unpaired(*stream);
        return;
    }
}

void Relay::State::read_forward(Stream& first) {
    const StreamId id = first.id;
    std::size_t budget = kForwardBytesPerPass;
    for (;;) {
        Stream* stream = find(id);
        if (!stream) return;
        if (stream->phase != Phase::Paired) {
            if (stream->read_ready) mark_ready(*stream);
            return;
        }
        Stream* peer = stream->peer ? find(*stream->peer) : nullptr;
        if (!peer || peer->phase != Phase::Paired) {
            // Both streams of a room leave Paired together.
            end_paired(*stream, EndReason::ServiceFailure, false);
            return;
        }
        const std::uint64_t remaining =
            limits.max_forwarded_bytes_per_direction - stream->forwarded;
        const std::size_t credit = static_cast<std::size_t>(
            std::min<std::uint64_t>({peer->inbound.free(), remaining, budget}));
        if (credit == 0U) {
            // A full queue resumes this read when the peer's writes free
            // credit. An exhausted pass resumes on the next one.
            if (budget == 0U) mark_ready(*stream);
            return;
        }
        const auto space = peer->inbound.free_span(credit);
        const ssize_t got =
            ::recv(stream->socket.get(), space.data(), space.size(), 0);
        if (got > 0) {
            const auto count = static_cast<std::size_t>(got);
            peer->inbound.commit(count);
            stream->forwarded += count;
            budget -= count;
            add_queued(count);
            write(*peer);
            stream = find(id);
            if (!stream || stream->phase != Phase::Paired) return;
            if (stream->forwarded >= limits.max_forwarded_bytes_per_direction) {
                end_paired(*stream, EndReason::ByteLimit, true);
                return;
            }
            continue;
        }
        // A UNIX peer that closes with bytes it has not read reports a reset
        // after its last sent byte. Either way everything it sent arrived.
        if (got == 0 || errno == ECONNRESET) {
            stream->eof = true;
            end_paired(*stream, EndReason::PeerClosed, true);
            return;
        }
        if (errno == EINTR) continue;
        if (errno == EAGAIN || errno == EWOULDBLOCK) {
            stream->read_ready = false;
            return;
        }
        end_paired(*stream, EndReason::Error, false);
        return;
    }
}

void Relay::State::read_discard(Stream& first) {
    const StreamId id = first.id;
    std::size_t budget = kDiscardBytesPerPass;
    for (;;) {
        Stream* stream = find(id);
        if (!stream || stream->phase != Phase::Draining) return;
        const ssize_t got = ::recv(stream->socket.get(), scratch.data(),
                                   std::min(scratch.size(), budget), 0);
        if (got > 0) {
            budget -= static_cast<std::size_t>(got);
            if (budget == 0U) {
                mark_ready(*stream);
                return;
            }
            continue;
        }
        if (got == 0) {
            stream->eof = true;
            stream->read_ready = false;
            if (!stream->pending_output()) close_stream(*stream);
            return;
        }
        if (errno == EINTR) continue;
        if (errno == EAGAIN || errno == EWOULDBLOCK) {
            stream->read_ready = false;
            return;
        }
        close_stream(*stream);
        return;
    }
}

void Relay::State::write(Stream& stream) {
    if (stream.write_broken) return;
    bool progress = false;
    for (;;) {
        std::span<const std::byte> pending;
        const bool control = stream.output_begin < stream.output_end;
        if (control) {
            pending = {stream.output.data() + stream.output_begin,
                       stream.output_end - stream.output_begin};
        } else if (stream.inbound.size() > 0U) {
            // PAIRED is control output, so it is always written first.
            pending = stream.inbound.data_span();
        } else {
            break;
        }
        const ssize_t sent =
            ::send(stream.socket.get(), pending.data(), pending.size(),
                   MSG_NOSIGNAL | MSG_DONTWAIT);
        if (sent > 0) {
            const auto count = static_cast<std::size_t>(sent);
            progress = true;
            if (control) {
                stream.output_begin += count;
                if (stream.output_begin == stream.output_end) {
                    // RESERVED carried the room's tokens.
                    ::explicit_bzero(stream.output.data(),
                                     stream.output.size());
                    stream.output_begin = stream.output_end = 0U;
                }
            } else {
                stream.inbound.consume(count);
                sub_queued(count);
                if (stream.peer) {
                    Stream* source = find(*stream.peer);
                    if (source && source->phase == Phase::Paired &&
                        source->read_ready)
                        mark_ready(*source);
                }
            }
            continue;
        }
        if (sent < 0 && errno == EINTR) continue;
        if (sent < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) break;
        write_failed(stream);
        return;
    }
    if (stream.pending_output()) {
        // Five seconds without progress, counted only while output waits.
        if (progress || !stream.stall_deadline)
            stream.stall_deadline = now + limits.stall_timeout;
    } else {
        stream.stall_deadline.reset();
        if (stream.phase == Phase::Draining) {
            if (!stream.write_shut) {
                ::shutdown(stream.socket.get(), SHUT_WR);
                stream.write_shut = true;
            }
            if (stream.eof) {
                close_stream(stream);
                return;
            }
        }
    }
    touch(stream);
}

void Relay::State::write_failed(Stream& stream) {
    stream.write_broken = true;
    switch (stream.phase) {
        case Phase::Paired:
            // The peer closed. Write nothing more to it, but keep forwarding
            // what it sent before closing, such as an encrypted END, until
            // its end of stream ends the room in order. The stall deadline,
            // which no write can now satisfy, bounds that wait.
            sub_queued(stream.inbound.size());
            stream.inbound.consume(stream.inbound.size());
            stream.stall_deadline = now + limits.stall_timeout;
            touch(stream);
            read_forward(stream);
            return;
        case Phase::Draining:
            close_stream(stream);
            return;
        case Phase::Header:
        case Phase::Control:
        case Phase::Reserved:
        case Phase::Waiting:
            drop_unpaired(stream);
            return;
    }
}

void Relay::State::close_stream(Stream& stream) noexcept {
    release_principal(stream);
    if (stream.room) {
        // Every caller leaves its room first. A room never outlives a
        // stream it names.
        const RoomId room = *stream.room;
        stream.room.reset();
        if (table.close_room(room)) ++counted.closed_rooms;
    }
    sub_queued(stream.input_size + stream.inbound.size());
    if (stream.scheduled) timers.erase({*stream.scheduled, stream.id});
    // Closing the descriptor also removes it from the epoll set.
    streams.erase(stream.id);
}

void Relay::State::close_all() noexcept {
    timers.clear();
    streams.clear();
    queued_bytes = 0U;
}

void Relay::State::process_input(Stream& first) {
    const StreamId id = first.id;
    for (;;) {
        Stream* stream = find(id);
        if (!stream) return;
        const std::span<const std::byte> input{stream->input.data(),
                                               stream->input_size};
        if (stream->phase == Phase::Header) {
            const auto header = parse_module_header(input, options.service);
            if (header.status == HeaderStatus::Incomplete) return;
            if (header.status != HeaderStatus::Accepted) {
                // yumed writes canonical headers. Anything else is a
                // misconfigured daemon, never a client to answer.
                ++counted.rejected_headers;
                close_stream(*stream);
                return;
            }
            consume_input(*stream, header.consumed);
            admit(*stream, header.principal);
            continue;
        }
        if (!in_control(stream->phase)) return;
        if (stream->input_size == 0U) {
            if (may_idle(stream->phase)) stream->parse_deadline.reset();
            touch(*stream);
            return;
        }
        auto parsed = parse_request(input);
        switch (parsed.status) {
            case RequestStatus::Incomplete:
                // A partial frame on an idle stream gets its own budget.
                if (may_idle(stream->phase) && !stream->parse_deadline) {
                    stream->parse_deadline = now + limits.parse_budget;
                    touch(*stream);
                }
                return;
            case RequestStatus::Invalid:
                refuse(*stream, RefusalReason::InvalidRequest);
                return;
            case RequestStatus::Unsupported:
                refuse(*stream, RefusalReason::UnsupportedProtocol);
                return;
            case RequestStatus::Decoded:
                consume_input(*stream, parsed.consumed);
                handle_request(*stream, parsed.request);
                // A JOIN's token leaves no copy behind.
                ::explicit_bzero(&parsed, sizeof parsed);
                continue;
        }
    }
}

void Relay::State::consume_input(Stream& stream, std::size_t count) noexcept {
    const std::size_t remaining = stream.input_size - count;
    std::memmove(stream.input.data(), stream.input.data() + count, remaining);
    ::explicit_bzero(stream.input.data() + remaining, count);
    stream.input_size = remaining;
    sub_queued(count);
}

void Relay::State::admit(Stream& stream, const Principal& principal) {
    bool admitted = false;
    try {
        admitted = table.admit_stream(principal, now);
    } catch (const std::bad_alloc&) {
        admitted = false;
    }
    if (!admitted) {
        refuse(stream, RefusalReason::Capacity);
        return;
    }
    stream.principal = principal;
    stream.phase = Phase::Control;
}

void Relay::State::handle_request(Stream& stream, const Request& request) {
    if (const auto* reserving = std::get_if<ReserveRequest>(&request)) {
        // RESERVE only as the first request of a stream.
        if (stream.phase != Phase::Control) {
            refuse(stream, RefusalReason::InvalidRequest);
            return;
        }
        reserve(stream, *reserving);
        return;
    }
    if (const auto* joining = std::get_if<JoinRequest>(&request)) {
        if (stream.phase == Phase::Waiting) {
            refuse(stream, RefusalReason::InvalidRequest);
            return;
        }
        join(stream, *joining);
        return;
    }
    // CANCEL is valid after RESERVED and while WAITING. It ends the room
    // for both streams, the canceling one included.
    if (stream.phase == Phase::Control) {
        refuse(stream, RefusalReason::InvalidRequest);
        return;
    }
    refuse(stream, RefusalReason::Canceled);
}

void Relay::State::reserve(Stream& stream, const ReserveRequest& request) {
    std::variant<Reservation, RefusalReason> result = RefusalReason::Capacity;
    try {
        result = table.reserve(*stream.principal, stream.id,
                               request.lifetime_milliseconds, now);
    } catch (const std::bad_alloc&) {
        result = RefusalReason::Capacity;
    }
    if (const auto* reason = std::get_if<RefusalReason>(&result)) {
        refuse(stream, *reason);
        return;
    }
    auto& reservation = std::get<Reservation>(result);
    auto frame = encode_reserved(reservation.room, reservation.initiator_token,
                                 reservation.responder_token,
                                 reservation.lifetime_milliseconds);
    ::explicit_bzero(reservation.initiator_token.data(),
                     reservation.initiator_token.size());
    ::explicit_bzero(reservation.responder_token.data(),
                     reservation.responder_token.size());
    stream.room = reservation.room;
    stream.phase = Phase::Reserved;
    // The initiator may prepare its invitation before it joins. The room's
    // own deadline bounds that wait, and a partial frame still has its budget.
    stream.parse_deadline.reset();
    ++counted.reservations;
    const bool queued = append_output(stream, frame);
    ::explicit_bzero(frame.data(), frame.size());
    if (!queued) {
        abandon(stream);
        return;
    }
    write(stream);
}

void Relay::State::join(Stream& stream, const JoinRequest& request) {
    // The stream's queue exists before its JOIN can be admitted, so pairing
    // never allocates after a token is spent.
    if (!stream.inbound.allocated() &&
        !stream.inbound.allocate(limits.queue_bytes_per_direction)) {
        refuse(stream, RefusalReason::Capacity);
        return;
    }
    const std::optional<RoomId> reserved =
        stream.phase == Phase::Reserved ? stream.room : std::nullopt;
    std::variant<Admitted, RefusalReason> result = RefusalReason::Capacity;
    try {
        result =
            table.join(*stream.principal, stream.id, reserved, request, now);
    } catch (const std::bad_alloc&) {
        result = RefusalReason::Capacity;
    }
    if (const auto* reason = std::get_if<RefusalReason>(&result)) {
        // A reserving stream's refusal also ends the room it reserved.
        refuse(stream, *reason);
        return;
    }
    const auto& admitted = std::get<Admitted>(result);
    stream.room = admitted.room;
    stream.phase = Phase::Waiting;
    stream.parse_deadline.reset();
    if (!append_output(stream, encode_signal(FrameType::Waiting))) {
        abandon(stream);
        return;
    }
    if (admitted.paired) {
        pair(admitted);
        return;
    }
    write(stream);
}

void Relay::State::pair(const Admitted& admitted) {
    Stream* initiator = find(admitted.initiator);
    Stream* responder = find(admitted.responder);
    if (!initiator || !responder) {
        if (initiator) end_paired(*initiator, EndReason::ServiceFailure, false);
        if (responder) end_paired(*responder, EndReason::ServiceFailure, false);
        return;
    }
    initiator->peer = responder->id;
    responder->peer = initiator->id;
    // Each admitted JOIN allocated its stream's queue first.
    if (!initiator->inbound.allocated() || !responder->inbound.allocated()) {
        initiator->phase = responder->phase = Phase::Paired;
        end_paired(*initiator, EndReason::ServiceFailure, false);
        return;
    }
    for (Stream* stream : {initiator, responder}) {
        stream->phase = Phase::Paired;
        stream->parse_deadline.reset();
        // Each already holds WAITING, so PAIRED fits by construction.
        [[maybe_unused]] const bool queued =
            append_output(*stream, encode_signal(FrameType::Paired));
    }
    // Bytes either stream sent after its last control frame are its first
    // opaque bytes. They follow the peer's PAIRED, which is written first.
    move_leftover(*initiator, *responder);
    move_leftover(*responder, *initiator);
    ++counted.pairings;
    const StreamId ids[] = {initiator->id, responder->id};
    for (const StreamId id : ids) {
        Stream* stream = find(id);
        if (!stream || stream->phase != Phase::Paired) continue;
        if (stream->forwarded >= limits.max_forwarded_bytes_per_direction) {
            end_paired(*stream, EndReason::ByteLimit, true);
            continue;
        }
        write(*stream);
        if ((stream = find(id)) && stream->read_ready) mark_ready(*stream);
    }
}

void Relay::State::move_leftover(Stream& from, Stream& to) noexcept {
    if (from.input_size == 0U) return;
    // The queue is empty and at least kControlInputBytes long.
    const auto space = to.inbound.free_span(from.input_size);
    std::memcpy(space.data(), from.input.data(), space.size());
    to.inbound.commit(space.size());
    from.forwarded += space.size();
    ::explicit_bzero(from.input.data(), from.input_size);
    from.input_size = 0U;
}

bool Relay::State::append_output(Stream& stream,
                                 std::span<const std::byte> bytes) noexcept {
    if (stream.output_end + bytes.size() > stream.output.size()) {
        const std::size_t unsent = stream.output_end - stream.output_begin;
        std::memmove(stream.output.data(),
                     stream.output.data() + stream.output_begin, unsent);
        stream.output_begin = 0U;
        stream.output_end = unsent;
        if (unsent + bytes.size() > stream.output.size()) return false;
    }
    std::memcpy(stream.output.data() + stream.output_end, bytes.data(),
                bytes.size());
    stream.output_end += bytes.size();
    return true;
}

void Relay::State::refuse(Stream& stream, RefusalReason reason) {
    ++counted.refusals[static_cast<std::size_t>(reason) - 1U];
    leave_room(stream, RefusalReason::Canceled);
    release_principal(stream);
    if (!append_output(stream, encode_refused(reason))) {
        close_stream(stream);
        return;
    }
    begin_drain(stream);
}

void Relay::State::leave_room(Stream& stream, RefusalReason reason_for_others) {
    if (!stream.room) return;
    const RoomId room = *stream.room;
    stream.room.reset();
    const auto closed = table.close_room(room);
    if (!closed) return;
    ++counted.closed_rooms;
    for (const auto member :
         {std::optional<StreamId>(closed->reserver), closed->responder}) {
        if (!member || *member == stream.id) continue;
        Stream* other = find(*member);
        if (other && other->room == room) {
            other->room.reset();
            refuse(*other, reason_for_others);
        }
    }
}

void Relay::State::drop_unpaired(Stream& stream) {
    leave_room(stream, RefusalReason::Canceled);
    close_stream(stream);
}

// Ends a paired room for both of its streams, which are exactly this stream
// and its peer. An orderly end flushes accepted bytes first.
void Relay::State::end_paired(Stream& stream, EndReason reason, bool orderly) {
    if (stream.room && table.close_room(*stream.room)) {
        ++counted.closed_rooms;
        ++counted.paired_ends[static_cast<std::size_t>(reason)];
    }
    const std::array<std::optional<StreamId>, 2> members{stream.id,
                                                         stream.peer};
    for (const auto& id : members) {
        if (!id) continue;
        Stream* member = find(*id);
        if (!member) continue;
        member->room.reset();
        release_principal(*member);
        if (orderly && member->phase == Phase::Paired) {
            begin_drain(*member);
        } else {
            close_stream(*member);
        }
    }
}

void Relay::State::begin_drain(Stream& stream) {
    // A stream whose peer already closed can receive nothing more.
    if (stream.write_broken) {
        close_stream(stream);
        return;
    }
    const StreamId id = stream.id;
    stream.phase = Phase::Draining;
    stream.parse_deadline.reset();
    stream.stall_deadline.reset();
    stream.drain_deadline = now + limits.drain_budget;
    sub_queued(stream.input_size);
    ::explicit_bzero(stream.input.data(), stream.input_size);
    stream.input_size = 0U;
    touch(stream);
    write(stream);
    if (Stream* still = find(id); still && still->read_ready)
        mark_ready(*still);
}

void Relay::State::release_principal(Stream& stream) noexcept {
    if (!stream.principal) return;
    table.release_stream(*stream.principal);
    stream.principal.reset();
}

void Relay::State::abandon(Stream& stream) {
    if (stream.phase == Phase::Paired) {
        end_paired(stream, EndReason::ServiceFailure, false);
        return;
    }
    drop_unpaired(stream);
}

void Relay::State::fire_timers() {
    if (const auto next = table.next_deadline(); next && *next <= now) {
        for (const auto& expired : table.expire(now)) handle_expired(expired);
    }
    while (!timers.empty() && timers.begin()->first <= now) {
        const StreamId id = timers.begin()->second;
        timers.erase(timers.begin());
        Stream* stream = find(id);
        if (!stream) continue;
        stream->scheduled.reset();
        on_deadline(*stream);
        if ((stream = find(id))) touch(*stream);
    }
}

void Relay::State::on_deadline(Stream& stream) {
    if (stream.drain_deadline && *stream.drain_deadline <= now) {
        close_stream(stream);
        return;
    }
    if (stream.stall_deadline && *stream.stall_deadline <= now) {
        // Linux reports a UNIX socket writable only after its reader drains
        // most of the buffer, so a slow reader that still consumes can pass
        // five seconds without an edge. Retry the write once: progress
        // rearms the deadline, and only a refused write is a stall.
        const TimePoint due = *stream.stall_deadline;
        const StreamId id = stream.id;
        write(stream);
        Stream* still = find(id);
        if (!still || still->stall_deadline != due) return;
        if (still->phase == Phase::Paired) {
            end_paired(*still, EndReason::Stalled, false);
        } else if (still->phase == Phase::Draining) {
            close_stream(*still);
        } else {
            drop_unpaired(*still);
        }
        return;
    }
    if (stream.parse_deadline && *stream.parse_deadline <= now) {
        stream.parse_deadline.reset();
        if (stream.phase == Phase::Header) {
            ++counted.rejected_headers;
            close_stream(stream);
        } else {
            refuse(stream, RefusalReason::Timeout);
        }
    }
}

void Relay::State::handle_expired(const ExpiredRoom& expired) {
    ++counted.closed_rooms;
    if (expired.end == RoomEnd::LifetimeExpired)
        ++counted.paired_ends[static_cast<std::size_t>(
            EndReason::LifetimeExpired)];
    for (const auto member : {std::optional<StreamId>(expired.streams.reserver),
                              expired.streams.responder}) {
        if (!member) continue;
        Stream* stream = find(*member);
        if (!stream || stream->room != expired.room) continue;
        stream->room.reset();
        if (expired.end == RoomEnd::ReservationExpired) {
            refuse(*stream, RefusalReason::Timeout);
        } else {
            release_principal(*stream);
            begin_drain(*stream);
        }
    }
}

void Relay::State::touch(Stream& stream) noexcept {
    if (stream.in_dirty) return;
    stream.in_dirty = true;
    dirty.push_back(stream.id);
}

void Relay::State::mark_ready(Stream& stream) noexcept {
    if (stream.in_ready) return;
    stream.in_ready = true;
    ready.push_back(stream.id);
}

void Relay::State::run_ready() {
    ready_batch.swap(ready);
    for (const StreamId id : ready_batch) {
        Stream* stream = find(id);
        if (!stream) continue;
        stream->in_ready = false;
        if (stream->read_ready) read(*stream);
    }
    ready_batch.clear();
}

void Relay::State::settle() {
    // Rescheduling can end a stream and touch its peer, so repeat until no
    // stream is left unscheduled.
    while (!dirty.empty()) {
        dirty_batch.swap(dirty);
        for (const StreamId id : dirty_batch) {
            Stream* stream = find(id);
            if (!stream) continue;
            stream->in_dirty = false;
            reschedule(*stream);
        }
        dirty_batch.clear();
    }
}

void Relay::State::reschedule(Stream& stream) {
    const auto due =
        earliest(earliest(stream.parse_deadline, stream.stall_deadline),
                 stream.drain_deadline);
    if (due == stream.scheduled) return;
    if (stream.scheduled) timers.erase({*stream.scheduled, stream.id});
    stream.scheduled.reset();
    if (!due) return;
    try {
        timers.emplace(*due, stream.id);
        stream.scheduled = due;
    } catch (const std::bad_alloc&) {
        // A stream without its deadline could outlive every bound.
        abandon(stream);
    }
}

void Relay::State::read_signals() {
    signalfd_siginfo info{};
    while (::read(signals.get(), &info, sizeof info) ==
           static_cast<ssize_t>(sizeof info)) {
        if (info.ssi_signo == SIGUSR1) {
            report_counts();
        } else {
            stopping = true;
        }
    }
}

void Relay::State::report_counts() noexcept {
    try {
        const auto line = format_counts(snapshot());
        [[maybe_unused]] const auto written =
            ::write(STDERR_FILENO, line.data(), line.size());
    } catch (const std::bad_alloc&) {
        // Counts are diagnostics. The relay keeps serving without them.
    }
}

RelayCounts Relay::State::snapshot() const noexcept {
    RelayCounts counts = counted;
    counts.streams = streams.size();
    counts.principals = table.principals();
    counts.rooms = table.rooms();
    counts.paired_rooms = table.paired_rooms();
    counts.unqueued_retentions = table.unqueued_retentions();
    counts.queued_bytes = queued_bytes;
    return counts;
}

}  // namespace ito::relay
