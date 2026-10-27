/* Copyright (C) 2026 FixCraft Inc. AGPL-3.0-or-later. */

// The trust boundary of the module socket. The header yumed writes is
// trustworthy only because nobody but the daemon's user and root can connect
// to the inherited listener. The relay checks that at startup, then checks
// each accepted connection's peer credentials.

#pragma once

#include <sys/types.h>

#include <string_view>

namespace ito::relay {

// The listening socket a service manager passes, as YUME modules receive it.
inline constexpr int kInheritedListener = 3;

enum class ListenerCheck {
    Accepted,
    // Descriptor 3 is closed or is not a socket.
    NotSocket,
    // Not a listening UNIX stream socket.
    WrongType,
    // Unnamed or abstract, so no file permission protects it.
    NoPath,
    // The socket file is missing, is not a socket, belongs to another user
    // or is open to group or others.
    UnsafeSocket,
    // Its directory is a link, is not owned by root or the daemon's user,
    // or others can write to it.
    UnsafeDirectory,
    // Memory for the check itself ran out.
    Unchecked,
};

// Checks the listener against the pinned daemon user. Never assumes the
// relay's own user, which systemd makes a distinct dynamic one.
[[nodiscard]] ListenerCheck check_listener(int descriptor,
                                           uid_t daemon_uid) noexcept;

[[nodiscard]] std::string_view describe(ListenerCheck check) noexcept;

// Only root and the pinned daemon user may write a module header.
[[nodiscard]] constexpr bool trusted_peer(uid_t peer,
                                          uid_t daemon_uid) noexcept {
    return peer == 0U || peer == daemon_uid;
}

}  // namespace ito::relay
