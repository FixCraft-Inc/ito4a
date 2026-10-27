/* Copyright (C) 2026 FixCraft Inc. AGPL-3.0-or-later. */

#include "listener.hpp"

#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>

#include <cstddef>
#include <cstring>
#include <new>
#include <string>

namespace ito::relay {
namespace {

bool socket_option(int descriptor, int option, int expected) noexcept {
    int value = 0;
    socklen_t length = sizeof value;
    return ::getsockopt(descriptor, SOL_SOCKET, option, &value, &length) == 0 &&
           length == sizeof value && value == expected;
}

}  // namespace

ListenerCheck check_listener(int descriptor, uid_t daemon_uid) noexcept {
    struct stat info{};
    if (::fstat(descriptor, &info) != 0 || !S_ISSOCK(info.st_mode))
        return ListenerCheck::NotSocket;
    if (!socket_option(descriptor, SO_DOMAIN, AF_UNIX) ||
        !socket_option(descriptor, SO_TYPE, SOCK_STREAM) ||
        !socket_option(descriptor, SO_ACCEPTCONN, 1))
        return ListenerCheck::WrongType;

    sockaddr_un address{};
    socklen_t length = sizeof address;
    if (::getsockname(descriptor, reinterpret_cast<sockaddr*>(&address),
                      &length) != 0 ||
        length <= offsetof(sockaddr_un, sun_path) || address.sun_path[0] != '/')
        return ListenerCheck::NoPath;
    const std::size_t path_bytes =
        ::strnlen(address.sun_path, sizeof address.sun_path);
    if (path_bytes == sizeof address.sun_path) return ListenerCheck::NoPath;

    try {
        const std::string path(address.sun_path, path_bytes);
        struct stat socket_file{};
        if (::lstat(path.c_str(), &socket_file) != 0 ||
            !S_ISSOCK(socket_file.st_mode) ||
            socket_file.st_uid != daemon_uid ||
            (socket_file.st_mode & (S_IRWXG | S_IRWXO)) != 0U)
            return ListenerCheck::UnsafeSocket;
        // The directory decides who can replace the socket. lstat refuses a
        // link in its place.
        const auto slash = path.find_last_of('/');
        const std::string directory =
            slash == 0U ? "/" : path.substr(0U, slash);
        struct stat parent{};
        if (::lstat(directory.c_str(), &parent) != 0 ||
            !S_ISDIR(parent.st_mode) ||
            (parent.st_uid != 0U && parent.st_uid != daemon_uid) ||
            (parent.st_mode & (S_IWGRP | S_IWOTH)) != 0U)
            return ListenerCheck::UnsafeDirectory;
    } catch (const std::bad_alloc&) {
        return ListenerCheck::Unchecked;
    }
    return ListenerCheck::Accepted;
}

std::string_view describe(ListenerCheck check) noexcept {
    switch (check) {
        case ListenerCheck::Accepted:
            return "accepted";
        case ListenerCheck::NotSocket:
            return "descriptor 3 is not a socket";
        case ListenerCheck::WrongType:
            return "descriptor 3 is not a listening UNIX stream socket";
        case ListenerCheck::NoPath:
            return "the listener has no filesystem path";
        case ListenerCheck::UnsafeSocket:
            return "the socket must belong to the daemon user and be closed to "
                   "others";
        case ListenerCheck::UnsafeDirectory:
            return "the socket directory must be owned by root or the daemon "
                   "user and "
                   "closed to others' writes";
        case ListenerCheck::Unchecked:
            return "the listener could not be checked";
    }
    return "unknown listener check";
}

}  // namespace ito::relay
