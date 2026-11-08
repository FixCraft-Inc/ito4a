/* Copyright (C) 2026 FixCraft Inc. AGPL-3.0-or-later. */

// ito-call: the Linux call endpoint. It manages a local identity and its
// contacts in a host test store, and places or answers a profile-1 test call
// through a libyume stream to the call relay. It uses only the public Ito
// call and YUME C interfaces. It doubles as the host test peer.
//
// Commands print one JSON object. Secrets never appear in output: the
// invitation goes only to the file named for it, as the out-of-band channel.

#include <ito/call.h>

#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>

#include <array>
#include <atomic>
#include <chrono>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <map>
#include <memory>
#include <stdexcept>
#include <string>
#include <thread>
#include <vector>

#include "host_store.hpp"
#include "yume_transport.hpp"

namespace {

using Bytes = std::vector<uint8_t>;
using Clock = std::chrono::steady_clock;

constexpr const char* kUsage =
    "usage: ito-call --store DIR COMMAND [options]\n"
    "  version\n"
    "  identity create | identity show\n"
    "  contact add CODE NAME | contact verify CODE FINGERPRINT\n"
    "  contact block CODE | contact replace OLD_CODE NEW_CODE | contact list\n"
    "  call --peer CODE --invitation-out FILE CONNECTION [DATA]\n"
    "  answer --invitation FILE [--decline | --no-decision] CONNECTION [DATA]\n"
    "CONNECTION: --yume-config DIR --service NAME [--resolver PATH]\n"
    "            [--lifetime SECONDS] --result FILE\n"
    "DATA: [--send FILE] [--expect-bytes N] [--received-out FILE]\n"
    "      [--hangup-after-ms N] [--hold-ms N]\n";

struct Failure final : std::runtime_error {
    using std::runtime_error::runtime_error;
};

uint64_t now_unix() {
    return static_cast<uint64_t>(
        std::chrono::duration_cast<std::chrono::seconds>(
            std::chrono::system_clock::now().time_since_epoch())
            .count());
}

std::string hex(const uint8_t* bytes, size_t size) {
    static constexpr char digits[] = "0123456789abcdef";
    std::string out;
    for (size_t index = 0; index < size; ++index) {
        out += digits[bytes[index] >> 4];
        out += digits[bytes[index] & 15];
    }
    return out;
}

void require(ito_status status, const char* step) {
    if (status != ITO_STATUS_OK) {
        throw Failure(std::string(step) + ": " + ito_status_name(status));
    }
}

// A minimal JSON object writer for fixed keys and plain values.
class Json final {
public:
    Json& text(const std::string& key, const std::string& value) {
        add(key, "\"" + value + "\"");
        return *this;
    }
    Json& number(const std::string& key, uint64_t value) {
        add(key, std::to_string(value));
        return *this;
    }
    Json& boolean(const std::string& key, bool value) {
        add(key, value ? "true" : "false");
        return *this;
    }
    Json& raw(const std::string& key, const std::string& value) {
        add(key, value);
        return *this;
    }
    [[nodiscard]] std::string str() const { return "{" + body_ + "}"; }

private:
    void add(const std::string& key, const std::string& value) {
        if (!body_.empty()) body_ += ",";
        body_ += "\"" + key + "\":" + value;
    }
    std::string body_;
};

struct Options final {
    std::string store;
    std::vector<std::string> positional;
    std::map<std::string, std::string> values;
    bool has(const std::string& key) const { return values.count(key) != 0U; }
    std::string get(const std::string& key) const {
        const auto found = values.find(key);
        if (found == values.end()) throw Failure("missing --" + key);
        return found->second;
    }
    std::string get_or(const std::string& key,
                       const std::string& fallback) const {
        const auto found = values.find(key);
        return found == values.end() ? fallback : found->second;
    }
};

Options parse(int argc, char** argv) {
    static const char* flags[] = {"decline", "no-decision"};
    Options options;
    for (int index = 1; index < argc; ++index) {
        const std::string argument = argv[index];
        if (!argument.starts_with("--")) {
            options.positional.push_back(argument);
            continue;
        }
        const std::string key = argument.substr(2);
        bool flag = false;
        for (const char* name : flags) flag = flag || key == name;
        if (flag) {
            options.values[key] = "1";
        } else if (index + 1 < argc) {
            options.values[key] = argv[++index];
        } else {
            throw Failure("--" + key + " needs a value");
        }
    }
    options.store = options.get_or("store", "");
    return options;
}

// Reads at most limit bytes, so a huge file or a FIFO cannot grow the buffer.
Bytes read_file(const std::string& path, size_t limit) {
    std::ifstream input(path, std::ios::binary);
    if (!input) throw Failure("cannot read " + path);
    Bytes bytes(limit + 1U);
    input.read(reinterpret_cast<char*>(bytes.data()),
               static_cast<std::streamsize>(bytes.size()));
    const auto got = static_cast<size_t>(input.gcount());
    if (got > limit) throw Failure(path + " is too large");
    bytes.resize(got);
    return bytes;
}

// Writes owner-only, failing if the file exists.
void write_private(const std::string& path, const uint8_t* bytes, size_t size) {
    const int descriptor =
        ::open(path.c_str(),
               O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, 0600);
    if (descriptor < 0) throw Failure("cannot create " + path);
    size_t offset = 0;
    while (offset < size) {
        const ssize_t written =
            ::write(descriptor, bytes + offset, size - offset);
        if (written <= 0) {
            ::close(descriptor);
            throw Failure("cannot write " + path);
        }
        offset += static_cast<size_t>(written);
    }
    const bool synced = ::fsync(descriptor) == 0;
    ::close(descriptor);
    if (!synced) throw Failure("cannot sync " + path);
}

void write_text(const std::string& path, const std::string& text) {
    write_private(path, reinterpret_cast<const uint8_t*>(text.data()),
                  text.size());
}

// The context and store, destroyed in order.
struct Session final {
    ito_context* context = nullptr;
    ito_store* store = nullptr;
    std::unique_ptr<ito::cli::HostStore> host;

    explicit Session(const std::string& directory) {
        if (directory.empty()) throw Failure("--store is required");
        host = std::make_unique<ito::cli::HostStore>(directory);
        require(ito_context_create(&context), "context");
        Bytes snapshot = host->load();
        ito_persistence persistence{sizeof(persistence), host.get(),
                                    ito::cli::host_store_persist};
        const ito_status opened = ito_store_open(
            context, snapshot.data(), snapshot.size(), &persistence, &store);
        if (!snapshot.empty()) explicit_bzero(snapshot.data(), snapshot.size());
        require(opened, "store");
    }
    Session(const Session&) = delete;
    Session& operator=(const Session&) = delete;
    ~Session() {
        if (store != nullptr) (void)ito_store_destroy(store);
        if (context != nullptr) (void)ito_context_destroy(context);
    }
};

std::array<uint8_t, ITO_KEY_BYTES> key_of(const std::string& code) {
    std::array<uint8_t, ITO_KEY_BYTES> key{};
    require(ito_contact_code_parse(code.data(), code.size(), key.data()),
            "contact code");
    return key;
}

std::string contact_json(ito_context* context, const ito_contact& contact) {
    char code[ITO_CONTACT_CODE_CHARS + 1];
    char fingerprint[ITO_FINGERPRINT_CHARS + 1];
    require(ito_contact_code_format(contact.key, code, sizeof(code)), "format");
    require(
        ito_fingerprint(context, contact.key, fingerprint, sizeof(fingerprint)),
        "fingerprint");
    static const char* trust[] = {"", "unverified", "verified", "blocked"};
    return Json()
        .text("code", code)
        .text("fingerprint", fingerprint)
        .text("trust", trust[contact.trust <= 3 ? contact.trust : 0])
        .boolean("replaced", (contact.flags & ITO_CONTACT_REPLACED) != 0U)
        .boolean("replaces_other", (contact.flags & ITO_CONTACT_REPLACES) != 0U)
        .number("name_bytes", contact.name_size)
        .str();
}

std::string identity_command(Session& session, const Options& options) {
    const std::string action =
        options.positional.size() > 1 ? options.positional[1] : "";
    if (action == "create")
        require(ito_store_create_identity(session.store, now_unix()), "create");
    else if (action != "show")
        throw Failure(kUsage);
    uint8_t key[ITO_KEY_BYTES];
    require(ito_store_identity(session.store, key), "identity");
    char code[ITO_CONTACT_CODE_CHARS + 1];
    char fingerprint[ITO_FINGERPRINT_CHARS + 1];
    require(ito_contact_code_format(key, code, sizeof(code)), "format");
    require(
        ito_fingerprint(session.context, key, fingerprint, sizeof(fingerprint)),
        "fingerprint");
    return Json().text("code", code).text("fingerprint", fingerprint).str();
}

std::string contact_command(Session& session, const Options& options) {
    const auto& args = options.positional;
    const std::string action = args.size() > 1 ? args[1] : "";
    if (action == "list") {
        size_t count = 0;
        require(ito_store_contact_count(session.store, &count), "count");
        std::string list = "[";
        for (size_t index = 0; index < count; ++index) {
            ito_contact contact{};
            contact.struct_size = sizeof(contact);
            require(ito_store_contact_at(session.store, index, &contact),
                    "contact");
            list += (index == 0 ? "" : ",") +
                    contact_json(session.context, contact);
        }
        return Json().raw("contacts", list + "]").str();
    }
    if (args.size() < 3) throw Failure(kUsage);
    const auto key = key_of(args[2]);
    if (action == "add" && args.size() == 4) {
        require(ito_store_add_contact(session.store, key.data(), args[3].data(),
                                      args[3].size(), now_unix()),
                "add");
    } else if (action == "verify" && args.size() == 4) {
        require(
            ito_store_verify_contact(session.store, key.data(), args[3].data(),
                                     args[3].size(), now_unix()),
            "verify");
    } else if (action == "block" && args.size() == 3) {
        require(ito_store_block_contact(session.store, key.data()), "block");
    } else if (action == "replace" && args.size() == 4) {
        const auto replacement = key_of(args[3]);
        require(ito_store_replace_contact(session.store, key.data(),
                                          replacement.data(), now_unix()),
                "replace");
    } else {
        throw Failure(kUsage);
    }
    ito_contact contact{};
    contact.struct_size = sizeof(contact);
    require(ito_store_find_contact(session.store, key.data(), &contact),
            "find");
    return contact_json(session.context, contact);
}

ito_call_status status_of(ito_call* call) {
    ito_call_status status{};
    status.struct_size = sizeof(status);
    require(ito_call_status_get(call, &status), "status");
    return status;
}

// Exchanges the test payload once media is authorized: sends --send in DATA
// frames while collecting received DATA, until --expect-bytes arrived and the
// send finished. Payloads that arrived before the call ended stay readable
// after it, so draining continues until the core reports the queue empty.
// Returns what arrived.
Bytes exchange(ito_call* call, const Options& options,
               const std::atomic<bool>& running) {
    const Bytes payload = options.has("send")
                              ? read_file(options.get("send"), 64U << 20)
                              : Bytes{};
    const size_t expected = std::stoull(options.get_or("expect-bytes", "0"));
    Bytes received;
    size_t sent = 0;
    std::vector<uint8_t> buffer(ITO_MAX_DATA_BYTES);
    bool drained = false;
    while (!drained &&
           ((running && sent < payload.size()) || received.size() < expected)) {
        bool progressed = false;
        if (running && sent < payload.size()) {
            const size_t size =
                std::min<size_t>(ITO_MAX_DATA_BYTES, payload.size() - sent);
            const ito_status status =
                ito_call_send(call, payload.data() + sent, size);
            if (status == ITO_STATUS_OK) {
                sent += size;
                progressed = true;
            } else if (status != ITO_STATUS_WOULD_BLOCK) {
                break;
            }
        }
        size_t size = 0;
        ito_status taken = ITO_STATUS_OK;
        while ((taken = ito_call_receive(call, buffer.data(), buffer.size(),
                                         &size)) == ITO_STATUS_OK) {
            received.insert(received.end(), buffer.begin(),
                            buffer.begin() + static_cast<std::ptrdiff_t>(size));
            progressed = true;
        }
        // WRONG_STATE: the call ended and nothing more is queued.
        drained = taken == ITO_STATUS_WRONG_STATE;
        if (!progressed)
            std::this_thread::sleep_for(std::chrono::milliseconds(2));
    }
    return received;
}

std::string call_command(Session& session, const Options& options,
                         bool initiator) {
    const auto started = Clock::now();
    const std::string result_path = options.get("result");
    ito::cli::YumeStream stream(options.get("yume-config"),
                                options.get_or("resolver", ""),
                                options.get("service"), 30'000U);
    const ito_authority authority = stream.authority();
    ito_call* call = nullptr;
    if (initiator) {
        const auto peer = key_of(options.get("peer"));
        // The core checks 1..600; only refuse what uint32_t cannot carry.
        const unsigned long long requested =
            std::stoull(options.get_or("lifetime", "120"));
        if (requested > UINT32_MAX) throw Failure("--lifetime is out of range");
        const auto lifetime = static_cast<uint32_t>(requested);
        require(
            ito_call_create_initiator(session.store, peer.data(), &authority,
                                      lifetime, now_unix(), &call),
            "create");
    } else {
        Bytes invitation =
            read_file(options.get("invitation"), ITO_MAX_INVITATION_BYTES);
        ito_invitation* opened = nullptr;
        const ito_status status = ito_invitation_open(
            session.store, invitation.data(), invitation.size(), &authority,
            now_unix(), &opened);
        if (!invitation.empty())
            explicit_bzero(invitation.data(), invitation.size());
        require(status, "invitation");
        ito_invitation_info info{};
        info.struct_size = sizeof(info);
        const ito_status inspected = ito_invitation_info_get(opened, &info);
        if (inspected != ITO_STATUS_OK || info.profile != 1U) {
            (void)ito_invitation_destroy(opened);
            require(
                inspected != ITO_STATUS_OK ? inspected : ITO_STATUS_UNSUPPORTED,
                "diagnostic-profile");
        }
        const ito_status created = ito_call_create_responder(
            session.store, opened, &authority, now_unix(), &call);
        if (created != ITO_STATUS_OK) (void)ito_invitation_destroy(opened);
        require(created, "create");
    }
    std::atomic<bool> running{true};
    std::atomic<int> run_result{ITO_STATUS_INTERNAL};
    ito_transport transport = stream.transport();
    std::thread runner([&] {
        run_result = ito_call_run(call, &transport);
        running = false;
    });
    // Whatever unwinds this function stops the attempt and joins its runner
    // before the call, its store or its transport can go away.
    struct Join final {
        ito_call* call;
        std::thread& runner;
        ~Join() {
            if (runner.joinable()) {
                (void)ito_call_cancel(call);
                runner.join();
            }
        }
    } join_guard{call, runner};
    bool invitation_written = false;
    bool decided = false;
    bool exchanged = false;
    Bytes received;
    const uint64_t hangup_after =
        std::stoull(options.get_or("hangup-after-ms", "0"));
    const uint64_t hold = std::stoull(options.get_or("hold-ms", "0"));
    while (running) {
        const ito_call_status status = status_of(call);
        if (initiator && !invitation_written &&
            (status.flags & ITO_CALL_FLAG_INVITATION_READY)) {
            uint8_t invitation[ITO_MAX_INVITATION_BYTES];
            size_t size = 0;
            require(ito_call_copy_invitation(call, invitation,
                                             sizeof(invitation), &size),
                    "copy");
            write_private(options.get("invitation-out"), invitation, size);
            explicit_bzero(invitation, sizeof(invitation));
            invitation_written = true;
        }
        if (!initiator && !decided &&
            (status.flags & ITO_CALL_FLAG_CONSENT_PENDING) &&
            !options.has("no-decision")) {
            require(ito_call_decide(call, options.has("decline") ? 0 : 1),
                    "decide");
            decided = true;
        }
        if (status.state == ITO_CALL_MEDIA_AUTHORIZED && !exchanged) {
            received = exchange(call, options, running);
            exchanged = true;
            if (hold != 0)
                std::this_thread::sleep_for(std::chrono::milliseconds(hold));
            // The initiator ends a completed test call. The responder waits
            // for its END unless told otherwise.
            if (initiator || hangup_after != 0) {
                if (hangup_after != 0)
                    std::this_thread::sleep_for(
                        std::chrono::milliseconds(hangup_after));
                (void)ito_call_hangup(call);
            }
        }
        std::this_thread::sleep_for(std::chrono::milliseconds(5));
    }
    runner.join();
    const ito_call_status final_status = status_of(call);
    std::string evidence;
#if defined(ITO_CALL_EVIDENCE)
    std::vector<char> text(1U << 16);
    size_t size = 0;
    if (ito_call_evidence_json(call, text.data(), text.size(), &size) ==
        ITO_STATUS_OK) {
        evidence.assign(text.data(), size);
    }
#endif
    // The call's TLS and secrets go before its transport closes.
    require(ito_call_destroy(call), "destroy");
    if (options.has("received-out")) {
        write_private(options.get("received-out"), received.data(),
                      received.size());
    }
    char version[128];
    require(ito_component_version(version, sizeof(version)), "version");
    const std::string json =
        Json()
            .text("role", initiator ? "initiator" : "responder")
            .text("result",
                  ito_status_name(static_cast<ito_status>(run_result.load())))
            .number("state", final_status.state)
            .boolean("peer_authenticated",
                     final_status.flags & ITO_CALL_FLAG_PEER_AUTHENTICATED)
            .boolean("remote_end_attributed",
                     final_status.flags & ITO_CALL_FLAG_REMOTE_END_ATTRIBUTED)
            .number("local_end", final_status.local_end_reason)
            .number("remote_end", final_status.remote_end_reason)
            .number("relay_refusal", final_status.relay_refusal)
            .number("data_sent", final_status.data_bytes_sent)
            .number("data_received", final_status.data_bytes_received)
            .number("frames_sent", final_status.frames_sent)
            .number("frames_received", final_status.frames_received)
            .number("key_updates_sent", final_status.key_updates_sent)
            .number("key_updates_received", final_status.key_updates_received)
            .text("relay_fingerprint",
                  hex(authority.server_fingerprint, ITO_KEY_BYTES))
            .text("component", version)
            .number("milliseconds",
                    static_cast<uint64_t>(
                        std::chrono::duration_cast<std::chrono::milliseconds>(
                            Clock::now() - started)
                            .count()))
            .raw("evidence", evidence.empty() ? "null" : evidence)
            .str();
    write_text(result_path, json + "\n");
    return json;
}

}  // namespace

int main(int argc, char** argv) {
    try {
        const Options options = parse(argc, argv);
        if (options.positional.empty()) throw Failure(kUsage);
        const std::string& command = options.positional[0];
        if (command == "version") {
            char version[128];
            require(ito_component_version(version, sizeof(version)), "version");
            std::printf("%s\n",
                        Json().text("component", version).str().c_str());
            return 0;
        }
        Session session(options.store);
        std::string output;
        if (command == "identity") {
            output = identity_command(session, options);
        } else if (command == "contact") {
            output = contact_command(session, options);
        } else if (command == "call" || command == "answer") {
            output = call_command(session, options, command == "call");
        } else {
            throw Failure(kUsage);
        }
        std::printf("%s\n", output.c_str());
        return 0;
    } catch (const std::exception& error) {
        std::fprintf(stderr, "ito-call: %s\n", error.what());
        return 2;
    }
}
