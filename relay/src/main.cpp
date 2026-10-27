/* Copyright (C) 2026 FixCraft Inc. AGPL-3.0-or-later. */

// ito-call-relay serves docs/CALL_RELAY.md as a YUME module: yumed connects
// to the inherited listener once for each authorized stream of the
// configured service. Exit status 0 after SIGTERM or SIGINT, 1 when the
// relay cannot start or its event loop fails, 2 for a usage error.

#include <sys/resource.h>
#include <sys/signalfd.h>
#include <unistd.h>

#include <charconv>
#include <csignal>
#include <cstdint>
#include <cstdlib>
#include <optional>
#include <string>
#include <string_view>

#include "listener.hpp"
#include "relay.hpp"
#include "room_table.hpp"
#include "wire.hpp"

namespace {

using namespace std::string_view_literals;

constexpr std::string_view kVersion =
    "ito-call-relay 0.1.0, relay protocol 1\n";

constexpr std::string_view kUsage =
    "usage: ito-call-relay --service NAME --daemon-uid UID\n"
    "\n"
    "Serves the opaque two-party call relay on the listening UNIX socket\n"
    "inherited as descriptor 3, as a YUME module. Only UID, the user yumed\n"
    "runs as, and root may connect to it.\n"
    "\n"
    "  --service NAME     the stream service name configured in yumed\n"
    "  --daemon-uid UID   the numeric user yumed runs as\n"
    "  --help             show this help\n"
    "  --version          show the version\n"
    "\n"
    "SIGUSR1 writes aggregate counts to standard error. SIGTERM stops it.\n";

// Descriptors the relay needs besides one per stream: the listener, epoll,
// signalfd, eventfd and standard streams, with margin.
constexpr rlim_t kReservedDescriptors = 32U;

void print(int descriptor, std::string_view text) noexcept {
    while (!text.empty()) {
        const ssize_t written = ::write(descriptor, text.data(), text.size());
        if (written <= 0) return;
        text.remove_prefix(static_cast<std::size_t>(written));
    }
}

// Status lines are JSON with fixed reasons, so a supervisor's log holds no
// principal, token or peer-supplied text.
void status(std::string_view event, std::string_view reason = {}) noexcept {
    try {
        std::string line = "{\"ito_call_relay\":\"";
        line += event;
        line += '"';
        if (!reason.empty()) {
            line += ",\"reason\":\"";
            line += reason;
            line += '"';
        }
        line += "}\n";
        print(STDERR_FILENO, line);
    } catch (...) {
        print(STDERR_FILENO, "{\"ito_call_relay\":\"status unavailable\"}\n");
    }
}

int usage_error(std::string_view message) {
    print(STDERR_FILENO, "ito-call-relay: ");
    print(STDERR_FILENO, message);
    print(STDERR_FILENO, "\nTry 'ito-call-relay --help'.\n");
    return 2;
}

std::optional<uid_t> parse_uid(std::string_view text) noexcept {
    if (text.empty() || text.size() > 10U || text.front() < '0' ||
        text.front() > '9')
        return std::nullopt;
    std::uint32_t value = 0U;
    const auto [end, error] =
        std::from_chars(text.data(), text.data() + text.size(), value);
    // (uid_t)-1 means "no user" to the kernel.
    if (error != std::errc{} || end != text.data() + text.size() ||
        value == UINT32_MAX)
        return std::nullopt;
    return static_cast<uid_t>(value);
}

// systemd describes the descriptors it passes. Anything other than exactly
// one listener for this process is a misconfigured unit.
bool activation_matches() noexcept {
    const char* count = std::getenv("LISTEN_FDS");
    if (count == nullptr) return true;
    const char* pid = std::getenv("LISTEN_PID");
    if (std::string_view(count) != "1"sv || pid == nullptr) return false;
    const auto own = std::to_string(::getpid());
    if (own != pid) return false;
    ::unsetenv("LISTEN_FDS");
    ::unsetenv("LISTEN_PID");
    ::unsetenv("LISTEN_FDNAMES");
    return true;
}

bool reserve_descriptors(std::size_t streams) noexcept {
    rlimit limit{};
    if (::getrlimit(RLIMIT_NOFILE, &limit) != 0) return false;
    const rlim_t needed = static_cast<rlim_t>(streams) + kReservedDescriptors;
    if (limit.rlim_cur != RLIM_INFINITY && limit.rlim_cur < needed) {
        if (limit.rlim_max != RLIM_INFINITY && limit.rlim_max < needed)
            return false;
        limit.rlim_cur = needed;
        if (::setrlimit(RLIMIT_NOFILE, &limit) != 0) return false;
    }
    return true;
}

int signal_descriptor() noexcept {
    sigset_t handled;
    sigemptyset(&handled);
    sigaddset(&handled, SIGTERM);
    sigaddset(&handled, SIGINT);
    sigaddset(&handled, SIGUSR1);
    if (::sigprocmask(SIG_BLOCK, &handled, nullptr) != 0) return -1;
    return ::signalfd(-1, &handled, SFD_NONBLOCK | SFD_CLOEXEC);
}

int serve(ito::relay::RelayOptions options) {
    using namespace ito::relay;
    if (::geteuid() == 0U) {
        status("failed", "the relay refuses to run as root");
        return 1;
    }
    if (!activation_matches()) {
        status("failed",
               "LISTEN_FDS must pass exactly one descriptor to this process");
        return 1;
    }
    const auto check = check_listener(kInheritedListener, options.daemon_uid);
    if (check != ListenerCheck::Accepted) {
        status("failed", describe(check));
        return 1;
    }
    if (!reserve_descriptors(options.limits.max_streams)) {
        status("failed", "the descriptor limit cannot hold every stream");
        return 1;
    }
    std::signal(SIGPIPE, SIG_IGN);
    const int signals = signal_descriptor();
    if (signals < 0) {
        status("failed", "signals could not be set up");
        return 1;
    }
    SystemTokenSource tokens;
    const auto relay =
        Relay::create(kInheritedListener, signals, std::move(options), tokens);
    if (!relay) {
        status("failed", "the event loop could not be created");
        return 1;
    }
    status("ready");
    const int result = relay->run();
    print(STDERR_FILENO, format_counts(relay->counts()));
    status(result == 0 ? "stopped" : "failed",
           result == 0 ? "" : "the event loop failed");
    return result;
}

}  // namespace

int main(int argc, char** argv) {
    ito::relay::RelayOptions options;
    std::optional<std::string> service;
    std::optional<uid_t> daemon_uid;
    for (int index = 1; index < argc; ++index) {
        const std::string_view word(argv[index]);
        if (word == "--help"sv) {
            print(STDOUT_FILENO, kUsage);
            return 0;
        }
        if (word == "--version"sv) {
            print(STDOUT_FILENO, kVersion);
            return 0;
        }
        if (word != "--service"sv && word != "--daemon-uid"sv)
            return usage_error("unknown argument");
        if (index + 1 >= argc) return usage_error("an option needs a value");
        const std::string_view value(argv[++index]);
        if (word == "--service"sv) {
            if (service)
                return usage_error("--service is given more than once");
            if (!ito::relay::valid_service_name(value))
                return usage_error(
                    "--service must be a canonical YUME service name of "
                    "at most 64 bytes");
            service.emplace(value);
        } else {
            if (daemon_uid)
                return usage_error("--daemon-uid is given more than once");
            daemon_uid = parse_uid(value);
            if (!daemon_uid)
                return usage_error("--daemon-uid must be a numeric user ID");
        }
    }
    if (!service) return usage_error("--service is required");
    if (!daemon_uid) return usage_error("--daemon-uid is required");
    options.service = std::move(*service);
    options.daemon_uid = *daemon_uid;
    try {
        return serve(std::move(options));
    } catch (...) {
        status("failed", "the relay could not start");
        return 1;
    }
}
