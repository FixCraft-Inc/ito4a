/* Copyright (C) 2026 FixCraft Inc. AGPL-3.0-or-later. */

#include "driver.hpp"

#include <algorithm>
#include <new>

namespace ito::call {
namespace {

[[nodiscard]] Status status_of_exception() noexcept {
    try {
        throw;
    } catch (const std::bad_alloc&) {
        return Status::OutOfMemory;
    } catch (const CryptoFailure&) {
        return Status::DependencyFailure;
    } catch (...) {
        return Status::Internal;
    }
}

// A fixed I/O buffer that wipes what passed through it.
class IoBuffer final {
public:
    IoBuffer() = default;
    IoBuffer(const IoBuffer&) = delete;
    IoBuffer& operator=(const IoBuffer&) = delete;
    ~IoBuffer() { wipe(bytes_.data(), bytes_.size()); }
    [[nodiscard]] std::span<Byte> span() noexcept { return bytes_; }

private:
    std::array<Byte, kIoChunk> bytes_{};
};

}  // namespace

CallSession::CallSession(std::unique_ptr<CallEngine> engine)
    : engine_(std::move(engine)) {}

bool CallSession::running() const {
    std::lock_guard guard(lock_);
    return running_;
}

template <class Operation>
Status CallSession::guarded(Operation&& operation) {
    std::lock_guard guard(lock_);
    Status result = Status::Internal;
    try {
        result = operation(*engine_);
    } catch (...) {
        result = status_of_exception();
        engine_->abandon(result);
    }
    woken_ = true;
    wakeup_.notify_all();
    return result;
}

Status CallSession::run(Transport& transport) {
    {
        std::lock_guard guard(lock_);
        if (ran_) return Status::WrongState;
        ran_ = true;
        running_ = true;
        try {
            engine_->start(Clock::now());
        } catch (...) {
            engine_->abandon(status_of_exception());
        }
    }
    // Any exit, including an exception from a platform transport, ends the
    // run, so the call can still be destroyed and its secrets wiped.
    struct RunEnd final {
        CallSession& session;
        ~RunEnd() {
            std::lock_guard guard(session.lock_);
            session.running_ = false;
        }
    } run_end{*this};
    try {
        pump(transport);
    } catch (...) {
        // Engine calls contain their own exceptions, so this one came from
        // the platform transport. The attempt ends typed, and the broken
        // transport is not used again.
        const Status status = status_of_exception();
        std::lock_guard guard(lock_);
        engine_->abandon(status);
        return engine_->snapshot().result;
    }
    linger(transport);
    std::lock_guard guard(lock_);
    return engine_->snapshot().result;
}

void CallSession::pump(Transport& transport) {
    IoBuffer output;
    IoBuffer input;
    for (;;) {
        std::size_t out_size = 0U;
        std::size_t capacity = 0U;
        std::optional<TimePoint> deadline;
        {
            std::lock_guard guard(lock_);
            const TimePoint now = Clock::now();
            try {
                engine_->tick(now);
                if (!engine_->closed()) {
                    const ByteView pending = engine_->output(now);
                    out_size = std::min(pending.size(), output.span().size());
                    std::copy_n(pending.begin(), out_size,
                                output.span().begin());
                    capacity = std::min(engine_->receive_capacity(), kIoChunk);
                    deadline = engine_->next_deadline();
                }
            } catch (...) {
                engine_->abandon(status_of_exception());
            }
            if (engine_->closed()) break;
            woken_ = false;
        }
        if (out_size != 0U) {
            const IoResult written = transport.write(
                ByteView(output.span().data(), out_size), kIoSlice);
            wipe(output.span().data(), out_size);
            std::lock_guard guard(lock_);
            try {
                if (written == IoResult::Ok) {
                    engine_->on_written(out_size, Clock::now());
                } else if (written == IoResult::Eof ||
                           written == IoResult::Failed) {
                    // A failed write is terminal in both directions. Only
                    // orderly read EOF can leave accepted input to parse.
                    engine_->on_transport_end(false, Clock::now());
                }
            } catch (...) {
                engine_->abandon(status_of_exception());
            }
            // The write may have ended the attempt or changed its input
            // capacity. Do not issue a read from the pre-write snapshot.
            capacity = engine_->closed()
                           ? 0U
                           : std::min(engine_->receive_capacity(), kIoChunk);
        }
        if (capacity != 0U) {
            // With output still pending the read only polls, so the next
            // write attempt is not delayed.
            Milliseconds timeout = out_size != 0U ? Milliseconds(0) : kIoSlice;
            if (deadline) {
                const auto remaining = std::chrono::duration_cast<Milliseconds>(
                    *deadline - Clock::now());
                timeout = std::clamp(remaining, Milliseconds(0), timeout);
            }
            std::size_t received = 0U;
            const IoResult read =
                transport.read(input.span().first(capacity), received, timeout);
            std::lock_guard guard(lock_);
            try {
                if (read == IoResult::Ok) {
                    engine_->on_received(ByteView(input.span().data(),
                                                  std::min(received, capacity)),
                                         Clock::now());
                } else if (read == IoResult::Eof || read == IoResult::Failed) {
                    engine_->on_transport_end(read == IoResult::Eof,
                                              Clock::now());
                }
            } catch (...) {
                engine_->abandon(status_of_exception());
            }
            wipe(input.span().data(), std::min(received, capacity));
        } else if (out_size == 0U) {
            // Nothing to read or write: wait for a deadline or a request.
            std::unique_lock guard(lock_);
            TimePoint until = Clock::now() + kIoSlice;
            if (deadline) until = std::min(until, *deadline);
            wakeup_.wait_until(guard, until, [this] { return woken_; });
        }
    }
}

void CallSession::linger(Transport& transport) {
    {
        std::lock_guard guard(lock_);
        // Only the side that ended the call waits, so two endpoints never wait
        // for each other. Once the transport reports its end, this bounded
        // wait has no further transport event to observe.
        if (engine_->transport_ended() || engine_->snapshot().local_end == 0U) {
            return;
        }
    }
    // Immediate transport release can cancel accepted writes. Retain the
    // production bounded linger to give them time to reach the peer. Peer
    // transport end is not an acknowledgement of END consumption. Bytes read
    // here follow this side's own close and are discarded unread.
    IoBuffer discard;
    const TimePoint until = Clock::now() + kCloseLinger;
    try {
        for (TimePoint now = Clock::now(); now < until; now = Clock::now()) {
            if (engine_->signals().authority_lost.load(
                    std::memory_order_acquire)) {
                return;
            }
            const auto left =
                std::chrono::duration_cast<Milliseconds>(until - now);
            std::size_t received = 0U;
            const IoResult read = transport.read(discard.span(), received,
                                                 std::min(left, kIoSlice));
            if (read == IoResult::Eof || read == IoResult::Failed) return;
        }
    } catch (...) {
        // A broken transport has nothing left in transit to wait for.
    }
}

Status CallSession::decide(bool accept) {
    return guarded([&](CallEngine& engine) {
        return engine.decide(accept, Clock::now());
    });
}

Status CallSession::send(ByteView data) {
    return guarded(
        [&](CallEngine& engine) { return engine.send(data, Clock::now()); });
}

Status CallSession::receive(std::span<Byte> out, std::size_t& size) {
    return guarded(
        [&](CallEngine& engine) { return engine.receive(out, size); });
}

Status CallSession::hangup() {
    return guarded(
        [&](CallEngine& engine) { return engine.hangup(Clock::now()); });
}

Status CallSession::copy_invitation(std::span<Byte> out, std::size_t& size) {
    std::lock_guard guard(lock_);
    return engine_->copy_invitation(out, size);
}

CallSnapshot CallSession::snapshot() {
    std::lock_guard guard(lock_);
    return engine_->snapshot();
}

// Only the flag: the run loop waits at most one I/O slice, and touching the
// lock or condition here could follow the run's return and the destroy.
void CallSession::cancel() noexcept {
    engine_->signals().cancel.store(true, std::memory_order_release);
}

void CallSession::authority_lost() noexcept {
    engine_->signals().authority_lost.store(true, std::memory_order_release);
}

#if defined(ITO_CALL_EVIDENCE)
CallEngine::Evidence CallSession::evidence() {
    std::lock_guard guard(lock_);
    return engine_->evidence();
}
#endif

}  // namespace ito::call
