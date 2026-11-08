/* Copyright (C) 2026 FixCraft Inc. AGPL-3.0-or-later. */

// Runs one call engine over a blocking byte transport on the caller's
// thread. Every engine access holds one mutex. Transport I/O never does, so
// the application's thread can send, receive, decide or cancel while a read
// or write is in progress. Each I/O call is bounded by a short slice. A
// request from another thread waits at most that slice, unless the run holds
// the lock while the platform persists a spend.

#pragma once

#include <chrono>
#include <condition_variable>
#include <cstddef>
#include <memory>
#include <mutex>
#include <span>

#include "bytes.hpp"
#include "engine.hpp"
#include "status.hpp"

namespace ito::call {

enum class IoResult { Ok, Eof, Timeout, Failed };

class Transport {
public:
    virtual ~Transport() = default;
    // Reads up to out.size() bytes. Fewer is normal.
    [[nodiscard]] virtual IoResult read(std::span<Byte> out,
                                        std::size_t& received,
                                        Milliseconds timeout) = 0;
    // Writes all of data or none of it.
    [[nodiscard]] virtual IoResult write(ByteView data,
                                         Milliseconds timeout) = 0;
};

inline constexpr Milliseconds kIoSlice{20};
inline constexpr std::size_t kIoChunk = 16U * 1024U;

class CallSession final {
public:
    explicit CallSession(std::unique_ptr<CallEngine> engine);
    CallSession(const CallSession&) = delete;
    CallSession& operator=(const CallSession&) = delete;

    // Runs the attempt to its end. Callable once.
    [[nodiscard]] Status run(Transport& transport);
    [[nodiscard]] bool running() const;

    [[nodiscard]] Status decide(bool accept);
    [[nodiscard]] Status send(ByteView data);
    [[nodiscard]] Status receive(std::span<Byte> out, std::size_t& size);
    [[nodiscard]] Status hangup();
    [[nodiscard]] Status copy_invitation(std::span<Byte> out,
                                         std::size_t& size);
    [[nodiscard]] CallSnapshot snapshot();
    // From any thread. They only raise an atomic flag, which the run loop
    // sees within one I/O slice. Other requests wait for the session lock,
    // which the loop also holds while the platform persists a spend.
    void cancel() noexcept;
    void authority_lost() noexcept;

#if defined(ITO_CALL_EVIDENCE)
    [[nodiscard]] CallEngine::Evidence evidence();
#endif

private:
    // Applies one engine operation under the lock, converting an escaped
    // exception into an abandoned attempt.
    template <class Operation>
    Status guarded(Operation&& operation);
    // Moves bytes between the engine and the transport until the engine is
    // Closed. An engine exception abandons the attempt here. A transport
    // exception leaves the loop for run to contain.
    void pump(Transport& transport);
    // After this side's END, reads and discards until the peer's side ends,
    // at most kCloseLinger, so the platform does not release the transport
    // while this side's last bytes are still on their way.
    void linger(Transport& transport);

    mutable std::mutex lock_;
    std::condition_variable wakeup_;
    bool woken_{false};
    std::unique_ptr<CallEngine> engine_;
    bool ran_{false};
    bool running_{false};
};

}  // namespace ito::call
