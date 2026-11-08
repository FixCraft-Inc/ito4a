/* Copyright (C) 2026 FixCraft Inc. AGPL-3.0-or-later. */

// The core's typed outcomes. Each value maps to exactly one ito_status in
// api.cpp. Expected failures (refused input, policy, time, storage) are
// values. Exceptions only abandon an operation on allocation or dependency
// failure, and the C interface converts them.

#pragma once

namespace ito::call {

enum class Status {
    Ok,
    InvalidArgument,
    BufferTooSmall,
    WrongState,
    Reentrant,
    Malformed,
    Unsupported,
    Untrusted,
    AuthenticationFailed,
    Expired,
    ClockRegressed,
    AlreadySpent,
    Capacity,
    NotFound,
    CorruptState,
    StorageFailed,
    StorageAmbiguous,
    Busy,
    WouldBlock,
    Timeout,
    Canceled,
    Declined,
    ProtocolFailure,
    PeerEnded,
    RelayRefused,
    TransportFailed,
    AuthorityMismatch,
    AuthorityEnded,
    ResourceLimit,
    DependencyFailure,
    OutOfMemory,
    Internal,
};

}  // namespace ito::call
