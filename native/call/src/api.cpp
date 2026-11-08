/* Copyright (C) 2026 FixCraft Inc. AGPL-3.0-or-later. */

// The C interface. Each function validates its arguments, converts them to
// the core's types and contains every exception. Handles own their C++
// objects. Borrow counts make a destroy refuse while another handle still
// uses it. Calls on the handle being destroyed are the caller's to order.

#include <ito/call.h>

#include <openssl/crypto.h>

#include <algorithm>
#include <atomic>
#include <cstring>
#include <memory>
#include <new>
#include <string_view>

#include "codec.hpp"
#include "crypto.hpp"
#include "driver.hpp"
#include "engine.hpp"
#include "invitation.hpp"
#include "status.hpp"
#include "store.hpp"

namespace call = ito::call;

struct ito_context {
    call::CryptoContext crypto;
    std::atomic<std::size_t> stores{0U};
};

struct ito_store {
    ito_context* context{nullptr};
    std::unique_ptr<call::Store> store;
};

struct ito_invitation {
    ito_store* store{nullptr};
    std::unique_ptr<call::OpenedInvitation> opened;
};

struct ito_call {
    ito_store* store{nullptr};
    std::unique_ptr<call::CallSession> session;
};

namespace {

// Set while a platform callback (persistence or transport) runs on this
// thread. The core refuses to be reentered from one.
thread_local bool g_in_callback = false;

class CallbackScope final {
public:
    CallbackScope() noexcept { g_in_callback = true; }
    CallbackScope(const CallbackScope&) = delete;
    CallbackScope& operator=(const CallbackScope&) = delete;
    ~CallbackScope() { g_in_callback = false; }
};

[[nodiscard]] ito_status to_c(call::Status status) noexcept {
    using call::Status;
    switch (status) {
        case Status::Ok:
            return ITO_STATUS_OK;
        case Status::InvalidArgument:
            return ITO_STATUS_INVALID_ARGUMENT;
        case Status::BufferTooSmall:
            return ITO_STATUS_BUFFER_TOO_SMALL;
        case Status::WrongState:
            return ITO_STATUS_WRONG_STATE;
        case Status::Reentrant:
            return ITO_STATUS_REENTRANT;
        case Status::Malformed:
            return ITO_STATUS_MALFORMED;
        case Status::Unsupported:
            return ITO_STATUS_UNSUPPORTED;
        case Status::Untrusted:
            return ITO_STATUS_UNTRUSTED;
        case Status::AuthenticationFailed:
            return ITO_STATUS_AUTHENTICATION_FAILED;
        case Status::Expired:
            return ITO_STATUS_EXPIRED;
        case Status::ClockRegressed:
            return ITO_STATUS_CLOCK_REGRESSED;
        case Status::AlreadySpent:
            return ITO_STATUS_ALREADY_SPENT;
        case Status::Capacity:
            return ITO_STATUS_CAPACITY;
        case Status::NotFound:
            return ITO_STATUS_NOT_FOUND;
        case Status::CorruptState:
            return ITO_STATUS_CORRUPT_STATE;
        case Status::StorageFailed:
            return ITO_STATUS_STORAGE_FAILED;
        case Status::StorageAmbiguous:
            return ITO_STATUS_STORAGE_AMBIGUOUS;
        case Status::Busy:
            return ITO_STATUS_BUSY;
        case Status::WouldBlock:
            return ITO_STATUS_WOULD_BLOCK;
        case Status::Timeout:
            return ITO_STATUS_TIMEOUT;
        case Status::Canceled:
            return ITO_STATUS_CANCELED;
        case Status::Declined:
            return ITO_STATUS_DECLINED;
        case Status::ProtocolFailure:
            return ITO_STATUS_PROTOCOL_FAILURE;
        case Status::PeerEnded:
            return ITO_STATUS_PEER_ENDED;
        case Status::RelayRefused:
            return ITO_STATUS_RELAY_REFUSED;
        case Status::TransportFailed:
            return ITO_STATUS_TRANSPORT_FAILED;
        case Status::AuthorityMismatch:
            return ITO_STATUS_AUTHORITY_MISMATCH;
        case Status::AuthorityEnded:
            return ITO_STATUS_AUTHORITY_ENDED;
        case Status::ResourceLimit:
            return ITO_STATUS_RESOURCE_LIMIT;
        case Status::DependencyFailure:
            return ITO_STATUS_DEPENDENCY_FAILURE;
        case Status::OutOfMemory:
            return ITO_STATUS_OUT_OF_MEMORY;
        case Status::Internal:
            return ITO_STATUS_INTERNAL;
    }
    return ITO_STATUS_INTERNAL;
}

// Runs body and turns any escaped exception into a status. Every exported
// function goes through here, so none can throw across the interface.
template <class Body>
ito_status contained(Body&& body) noexcept {
    if (g_in_callback) return ITO_STATUS_REENTRANT;
    try {
        return body();
    } catch (const std::bad_alloc&) {
        return ITO_STATUS_OUT_OF_MEMORY;
    } catch (const call::CryptoFailure&) {
        return ITO_STATUS_DEPENDENCY_FAILURE;
    } catch (...) {
        return ITO_STATUS_INTERNAL;
    }
}

template <class T>
[[nodiscard]] bool sized(const T* value) noexcept {
    return value != nullptr && value->struct_size >= sizeof(T);
}

[[nodiscard]] call::Key key_of(const uint8_t* bytes) noexcept {
    call::Key key{};
    std::copy_n(bytes, key.size(), key.begin());
    return key;
}

[[nodiscard]] bool authority_of(const ito_authority* source,
                                call::Authority& authority) {
    if (!sized(source) || source->service_size == 0U ||
        source->service_size > ITO_MAX_SERVICE_BYTES) {
        return false;
    }
    const std::string_view service(source->service, source->service_size);
    if (!call::valid_service_name(service)) return false;
    authority.server_fingerprint = key_of(source->server_fingerprint);
    authority.service.assign(service);
    return true;
}

void fill_contact(const call::Contact& contact, ito_contact* out) noexcept {
    const std::size_t size = out->struct_size;
    std::memset(out, 0, sizeof(*out));
    out->struct_size = size;
    std::copy(contact.key.begin(), contact.key.end(), out->key);
    out->trust = static_cast<uint32_t>(contact.trust);
    if (contact.replaced_by) {
        out->flags |= ITO_CONTACT_REPLACED;
        std::copy(contact.replaced_by->begin(), contact.replaced_by->end(),
                  out->replaced_by);
    }
    if (contact.replaces) {
        out->flags |= ITO_CONTACT_REPLACES;
        std::copy(contact.replaces->begin(), contact.replaces->end(),
                  out->replaces);
    }
    out->added_unix = contact.added_at;
    out->verified_unix = contact.verified_at;
    out->name_size = contact.name.size();
    std::copy(contact.name.begin(), contact.name.end(), out->name);
}

class CTransport final : public call::Transport {
public:
    explicit CTransport(const ito_transport& transport) noexcept
        : transport_(transport) {}

    call::IoResult read(std::span<call::Byte> out, std::size_t& received,
                        call::Milliseconds timeout) override {
        size_t got = 0U;
        int32_t result = ITO_IO_FAILED;
        {
            CallbackScope scope;
            result =
                transport_.read(transport_.user_data, out.data(), out.size(),
                                &got, static_cast<uint32_t>(timeout.count()));
        }
        received = 0U;
        switch (result) {
            case ITO_IO_OK:
                // A transport that claims more than it was given is broken.
                if (got == 0U || got > out.size())
                    return call::IoResult::Failed;
                received = got;
                return call::IoResult::Ok;
            case ITO_IO_EOF:
                return call::IoResult::Eof;
            case ITO_IO_TIMEOUT:
                return call::IoResult::Timeout;
            default:
                return call::IoResult::Failed;
        }
    }

    call::IoResult write(call::ByteView data,
                         call::Milliseconds timeout) override {
        int32_t result = ITO_IO_FAILED;
        {
            CallbackScope scope;
            result =
                transport_.write(transport_.user_data, data.data(), data.size(),
                                 static_cast<uint32_t>(timeout.count()));
        }
        switch (result) {
            case ITO_IO_OK:
                return call::IoResult::Ok;
            case ITO_IO_EOF:
                return call::IoResult::Eof;
            case ITO_IO_TIMEOUT:
                return call::IoResult::Timeout;
            default:
                return call::IoResult::Failed;
        }
    }

private:
    ito_transport transport_;
};

}  // namespace

extern "C" {

ito_status ito_component_version(char* out, size_t out_size) noexcept {
    if (out == nullptr) return ITO_STATUS_INVALID_ARGUMENT;
    const std::string_view text = OpenSSL_version(OPENSSL_VERSION);
    if (out_size <= text.size()) return ITO_STATUS_BUFFER_TOO_SMALL;
    std::copy(text.begin(), text.end(), out);
    out[text.size()] = '\0';
    return ITO_STATUS_OK;
}

const char* ito_status_name(ito_status status) noexcept {
    switch (status) {
        case ITO_STATUS_OK:
            return "ok";
        case ITO_STATUS_INVALID_ARGUMENT:
            return "invalid_argument";
        case ITO_STATUS_BUFFER_TOO_SMALL:
            return "buffer_too_small";
        case ITO_STATUS_WRONG_STATE:
            return "wrong_state";
        case ITO_STATUS_REENTRANT:
            return "reentrant";
        case ITO_STATUS_MALFORMED:
            return "malformed";
        case ITO_STATUS_UNSUPPORTED:
            return "unsupported";
        case ITO_STATUS_UNTRUSTED:
            return "untrusted";
        case ITO_STATUS_AUTHENTICATION_FAILED:
            return "authentication_failed";
        case ITO_STATUS_EXPIRED:
            return "expired";
        case ITO_STATUS_CLOCK_REGRESSED:
            return "clock_regressed";
        case ITO_STATUS_ALREADY_SPENT:
            return "already_spent";
        case ITO_STATUS_CAPACITY:
            return "capacity";
        case ITO_STATUS_NOT_FOUND:
            return "not_found";
        case ITO_STATUS_CORRUPT_STATE:
            return "corrupt_state";
        case ITO_STATUS_STORAGE_FAILED:
            return "storage_failed";
        case ITO_STATUS_STORAGE_AMBIGUOUS:
            return "storage_ambiguous";
        case ITO_STATUS_BUSY:
            return "busy";
        case ITO_STATUS_WOULD_BLOCK:
            return "would_block";
        case ITO_STATUS_TIMEOUT:
            return "timeout";
        case ITO_STATUS_CANCELED:
            return "canceled";
        case ITO_STATUS_DECLINED:
            return "declined";
        case ITO_STATUS_PROTOCOL_FAILURE:
            return "protocol_failure";
        case ITO_STATUS_PEER_ENDED:
            return "peer_ended";
        case ITO_STATUS_RELAY_REFUSED:
            return "relay_refused";
        case ITO_STATUS_TRANSPORT_FAILED:
            return "transport_failed";
        case ITO_STATUS_AUTHORITY_MISMATCH:
            return "authority_mismatch";
        case ITO_STATUS_AUTHORITY_ENDED:
            return "authority_ended";
        case ITO_STATUS_RESOURCE_LIMIT:
            return "resource_limit";
        case ITO_STATUS_DEPENDENCY_FAILURE:
            return "dependency_failure";
        case ITO_STATUS_OUT_OF_MEMORY:
            return "out_of_memory";
        case ITO_STATUS_INTERNAL:
            return "internal";
    }
    return "unknown";
}

ito_status ito_context_create(ito_context** out) noexcept {
    return contained([&] {
        if (out == nullptr) return ITO_STATUS_INVALID_ARGUMENT;
        *out = new ito_context{};
        return ITO_STATUS_OK;
    });
}

ito_status ito_context_destroy(ito_context* context) noexcept {
    return contained([&] {
        if (context == nullptr) return ITO_STATUS_INVALID_ARGUMENT;
        if (context->stores.load() != 0U) return ITO_STATUS_WRONG_STATE;
        delete context;
        return ITO_STATUS_OK;
    });
}

ito_status ito_fingerprint(ito_context* context, const uint8_t key[32],
                           char* out, size_t out_size) noexcept {
    return contained([&] {
        if (context == nullptr || key == nullptr || out == nullptr) {
            return ITO_STATUS_INVALID_ARGUMENT;
        }
        if (out_size <= ITO_FINGERPRINT_CHARS)
            return ITO_STATUS_BUFFER_TOO_SMALL;
        const std::string text =
            call::to_hex(call::fingerprint(context->crypto, key_of(key)));
        std::copy(text.begin(), text.end(), out);
        out[text.size()] = '\0';
        return ITO_STATUS_OK;
    });
}

ito_status ito_contact_code_format(const uint8_t key[32], char* out,
                                   size_t out_size) noexcept {
    return contained([&] {
        if (key == nullptr || out == nullptr)
            return ITO_STATUS_INVALID_ARGUMENT;
        if (out_size <= ITO_CONTACT_CODE_CHARS) {
            return ITO_STATUS_BUFFER_TOO_SMALL;
        }
        const std::string code = call::format_contact_code(key_of(key));
        std::copy(code.begin(), code.end(), out);
        out[code.size()] = '\0';
        return ITO_STATUS_OK;
    });
}

ito_status ito_contact_code_parse(const char* code, size_t code_size,
                                  uint8_t key[32]) noexcept {
    return contained([&] {
        if (code == nullptr || key == nullptr)
            return ITO_STATUS_INVALID_ARGUMENT;
        call::Key parsed{};
        switch (call::parse_contact_code(std::string_view(code, code_size),
                                         parsed)) {
            case call::CodecStatus::Ok:
                std::copy(parsed.begin(), parsed.end(), key);
                return ITO_STATUS_OK;
            case call::CodecStatus::Unsupported:
                return ITO_STATUS_UNSUPPORTED;
            case call::CodecStatus::Malformed:
                break;
        }
        return ITO_STATUS_MALFORMED;
    });
}

ito_status ito_store_open(ito_context* context, const uint8_t* snapshot,
                          size_t snapshot_size,
                          const ito_persistence* persistence,
                          ito_store** out) noexcept {
    return contained([&] {
        if (context == nullptr || out == nullptr || !sized(persistence) ||
            persistence->persist == nullptr ||
            (snapshot == nullptr && snapshot_size != 0U)) {
            return ITO_STATUS_INVALID_ARGUMENT;
        }
        const ito_persistence callback = *persistence;
        call::Persist persist = [callback](call::ByteView bytes) {
            int32_t result = ITO_PERSIST_AMBIGUOUS;
            {
                CallbackScope scope;
                result = callback.persist(callback.user_data, bytes.data(),
                                          bytes.size());
            }
            switch (result) {
                case ITO_PERSIST_COMMITTED:
                    return call::PersistResult::Committed;
                case ITO_PERSIST_NOT_COMMITTED:
                    return call::PersistResult::NotCommitted;
                default:
                    return call::PersistResult::Ambiguous;
            }
        };
        auto handle = std::make_unique<ito_store>();
        handle->context = context;
        const call::Status opened = call::Store::open(
            context->crypto, call::ByteView(snapshot, snapshot_size),
            std::move(persist), handle->store);
        if (opened != call::Status::Ok) return to_c(opened);
        ++context->stores;
        *out = handle.release();
        return ITO_STATUS_OK;
    });
}

ito_status ito_store_destroy(ito_store* store) noexcept {
    return contained([&] {
        if (store == nullptr) return ITO_STATUS_INVALID_ARGUMENT;
        if (store->store->borrowed()) return ITO_STATUS_WRONG_STATE;
        ito_context* context = store->context;
        delete store;
        --context->stores;
        return ITO_STATUS_OK;
    });
}

ito_status ito_store_create_identity(ito_store* store,
                                     uint64_t now_unix) noexcept {
    return contained([&] {
        if (store == nullptr) return ITO_STATUS_INVALID_ARGUMENT;
        return to_c(store->store->create_identity(now_unix));
    });
}

ito_status ito_store_identity(ito_store* store, uint8_t key[32]) noexcept {
    return contained([&] {
        if (store == nullptr || key == nullptr)
            return ITO_STATUS_INVALID_ARGUMENT;
        call::Key own{};
        const call::Status status = store->store->identity(own);
        if (status == call::Status::Ok) std::copy(own.begin(), own.end(), key);
        return to_c(status);
    });
}

ito_status ito_store_add_contact(ito_store* store, const uint8_t key[32],
                                 const char* name, size_t name_size,
                                 uint64_t now_unix) noexcept {
    return contained([&] {
        if (store == nullptr || key == nullptr || name == nullptr) {
            return ITO_STATUS_INVALID_ARGUMENT;
        }
        return to_c(store->store->add_contact(
            key_of(key), std::string_view(name, name_size), now_unix));
    });
}

ito_status ito_store_verify_contact(ito_store* store, const uint8_t key[32],
                                    const char* compared, size_t compared_size,
                                    uint64_t now_unix) noexcept {
    return contained([&] {
        if (store == nullptr || key == nullptr || compared == nullptr ||
            compared_size > 256U) {
            return ITO_STATUS_INVALID_ARGUMENT;
        }
        return to_c(store->store->verify_contact(
            key_of(key), std::string_view(compared, compared_size), now_unix));
    });
}

ito_status ito_store_block_contact(ito_store* store,
                                   const uint8_t key[32]) noexcept {
    return contained([&] {
        if (store == nullptr || key == nullptr)
            return ITO_STATUS_INVALID_ARGUMENT;
        return to_c(store->store->block_contact(key_of(key)));
    });
}

ito_status ito_store_rename_contact(ito_store* store, const uint8_t key[32],
                                    const char* name,
                                    size_t name_size) noexcept {
    return contained([&] {
        if (store == nullptr || key == nullptr || name == nullptr) {
            return ITO_STATUS_INVALID_ARGUMENT;
        }
        return to_c(store->store->rename_contact(
            key_of(key), std::string_view(name, name_size)));
    });
}

ito_status ito_store_replace_contact(ito_store* store,
                                     const uint8_t old_key[32],
                                     const uint8_t new_key[32],
                                     uint64_t now_unix) noexcept {
    return contained([&] {
        if (store == nullptr || old_key == nullptr || new_key == nullptr) {
            return ITO_STATUS_INVALID_ARGUMENT;
        }
        return to_c(store->store->replace_contact(key_of(old_key),
                                                  key_of(new_key), now_unix));
    });
}

ito_status ito_store_contact_count(ito_store* store, size_t* count) noexcept {
    return contained([&] {
        if (store == nullptr || count == nullptr) {
            return ITO_STATUS_INVALID_ARGUMENT;
        }
        *count = store->store->contacts().size();
        return ITO_STATUS_OK;
    });
}

ito_status ito_store_contact_at(ito_store* store, size_t index,
                                ito_contact* out) noexcept {
    return contained([&] {
        if (store == nullptr || !sized(out)) return ITO_STATUS_INVALID_ARGUMENT;
        const std::vector<call::Contact> contacts = store->store->contacts();
        if (index >= contacts.size()) return ITO_STATUS_NOT_FOUND;
        fill_contact(contacts[index], out);
        return ITO_STATUS_OK;
    });
}

ito_status ito_store_find_contact(ito_store* store, const uint8_t key[32],
                                  ito_contact* out) noexcept {
    return contained([&] {
        if (store == nullptr || key == nullptr || !sized(out)) {
            return ITO_STATUS_INVALID_ARGUMENT;
        }
        const std::optional<call::Contact> contact =
            store->store->find_contact(key_of(key));
        if (!contact) return ITO_STATUS_NOT_FOUND;
        fill_contact(*contact, out);
        return ITO_STATUS_OK;
    });
}

ito_status ito_invitation_open(ito_store* store, const uint8_t* invitation,
                               size_t invitation_size,
                               const ito_authority* authority,
                               uint64_t now_unix,
                               ito_invitation** out) noexcept {
    return contained([&] {
        call::Authority expected;
        if (store == nullptr || invitation == nullptr || out == nullptr ||
            !authority_of(authority, expected)) {
            return ITO_STATUS_INVALID_ARGUMENT;
        }
        auto handle = std::make_unique<ito_invitation>();
        handle->store = store;
        const call::Status status = call::open_invitation(
            *store->store, call::ByteView(invitation, invitation_size),
            expected, now_unix, call::Clock::now(), handle->opened);
        if (status != call::Status::Ok) return to_c(status);
        *out = handle.release();
        return ITO_STATUS_OK;
    });
}

ito_status ito_invitation_info_get(const ito_invitation* invitation,
                                   ito_invitation_info* out) noexcept {
    return contained([&] {
        if (invitation == nullptr || !sized(out) || !invitation->opened) {
            return ITO_STATUS_INVALID_ARGUMENT;
        }
        const call::Invitation& value = invitation->opened->invitation();
        std::copy(value.initiator.begin(), value.initiator.end(),
                  out->initiator_key);
        std::copy(value.responder.begin(), value.responder.end(),
                  out->responder_key);
        out->issued_unix = value.issued_at;
        out->expires_unix = value.expires_at;
        out->profile = value.profile;
        return ITO_STATUS_OK;
    });
}

ito_status ito_invitation_destroy(ito_invitation* invitation) noexcept {
    return contained([&] {
        if (invitation == nullptr) return ITO_STATUS_INVALID_ARGUMENT;
        delete invitation;
        return ITO_STATUS_OK;
    });
}

ito_status ito_call_create_initiator(ito_store* store, const uint8_t peer[32],
                                     const ito_authority* authority,
                                     uint32_t lifetime_seconds,
                                     uint64_t now_unix,
                                     ito_call** out) noexcept {
    return ito_call_create_initiator_profile(
        store, peer, authority, lifetime_seconds, now_unix, 1U, out);
}

ito_status ito_call_create_initiator_profile(
    ito_store* store, const uint8_t peer[32], const ito_authority* authority,
    uint32_t lifetime_seconds, uint64_t now_unix, uint32_t profile,
    ito_call** out) noexcept {
    return contained([&] {
        if (profile != 1U && profile != 2U) return ITO_STATUS_UNSUPPORTED;
        call::Authority selected;
        if (store == nullptr || peer == nullptr || out == nullptr ||
            !authority_of(authority, selected)) {
            return ITO_STATUS_INVALID_ARGUMENT;
        }
        std::unique_ptr<call::CallEngine> engine;
        call::EngineOptions options;
        options.profile = static_cast<uint16_t>(profile);
        const call::Status status = call::CallEngine::create_initiator(
            *store->store, key_of(peer), selected, lifetime_seconds, now_unix,
            call::Clock::now(), engine, options);
        if (status != call::Status::Ok) return to_c(status);
        auto handle = std::make_unique<ito_call>();
        handle->store = store;
        handle->session =
            std::make_unique<call::CallSession>(std::move(engine));
        *out = handle.release();
        return ITO_STATUS_OK;
    });
}

ito_status ito_call_create_responder(ito_store* store,
                                     ito_invitation* invitation,
                                     const ito_authority* authority,
                                     uint64_t now_unix,
                                     ito_call** out) noexcept {
    return contained([&] {
        call::Authority transport;
        if (store == nullptr || invitation == nullptr || out == nullptr ||
            invitation->store != store || !invitation->opened ||
            !authority_of(authority, transport)) {
            return ITO_STATUS_INVALID_ARGUMENT;
        }
        auto handle = std::make_unique<ito_call>();
        std::unique_ptr<call::CallEngine> engine;
        const call::Status status = call::CallEngine::create_responder(
            *store->store, invitation->opened, transport, now_unix,
            call::Clock::now(), engine);
        if (status != call::Status::Ok) return to_c(status);
        handle->store = store;
        try {
            handle->session =
                std::make_unique<call::CallSession>(std::move(engine));
        } catch (...) {
            // The engine took the invitation. Give it back, so the caller
            // still owns it on failure, as call.h promises.
            if (engine != nullptr) {
                invitation->opened = engine->release_invitation();
            }
            throw;
        }
        // The engine took the invitation. Its empty handle goes now.
        delete invitation;
        *out = handle.release();
        return ITO_STATUS_OK;
    });
}

ito_status ito_call_run(ito_call* call_handle,
                        const ito_transport* transport) noexcept {
    return contained([&] {
        if (call_handle == nullptr || !sized(transport) ||
            transport->read == nullptr || transport->write == nullptr) {
            return ITO_STATUS_INVALID_ARGUMENT;
        }
        CTransport adapter(*transport);
        return to_c(call_handle->session->run(adapter));
    });
}

ito_status ito_call_status_get(ito_call* call_handle,
                               ito_call_status* out) noexcept {
    return contained([&] {
        if (call_handle == nullptr || !sized(out)) {
            return ITO_STATUS_INVALID_ARGUMENT;
        }
        const call::CallSnapshot snapshot = call_handle->session->snapshot();
        const std::size_t size = out->struct_size;
        std::memset(out, 0, sizeof(*out));
        out->struct_size = size;
        out->state = static_cast<uint32_t>(snapshot.state);
        out->flags =
            (snapshot.initiator ? ITO_CALL_FLAG_INITIATOR : 0U) |
            (snapshot.invitation_ready ? ITO_CALL_FLAG_INVITATION_READY : 0U) |
            (snapshot.peer_authenticated ? ITO_CALL_FLAG_PEER_AUTHENTICATED
                                         : 0U) |
            (snapshot.consent_pending ? ITO_CALL_FLAG_CONSENT_PENDING : 0U) |
            (snapshot.remote_end_attributed
                 ? ITO_CALL_FLAG_REMOTE_END_ATTRIBUTED
                 : 0U);
        out->result = to_c(snapshot.result);
        out->local_end_reason = snapshot.local_end;
        out->remote_end_reason = snapshot.remote_end;
        out->relay_refusal = snapshot.relay_refusal;
        std::copy(snapshot.peer.begin(), snapshot.peer.end(), out->peer_key);
        out->data_bytes_sent = snapshot.data_sent;
        out->data_bytes_received = snapshot.data_received;
        out->frames_sent = snapshot.frames_sent;
        out->frames_received = snapshot.frames_received;
        out->key_updates_sent = snapshot.key_updates_sent;
        out->key_updates_received = snapshot.key_updates_received;
        return ITO_STATUS_OK;
    });
}

ito_status ito_call_copy_invitation(ito_call* call_handle, uint8_t* out,
                                    size_t out_size,
                                    size_t* invitation_size) noexcept {
    return contained([&] {
        if (call_handle == nullptr || out == nullptr ||
            invitation_size == nullptr) {
            return ITO_STATUS_INVALID_ARGUMENT;
        }
        return to_c(call_handle->session->copy_invitation(
            std::span<call::Byte>(out, out_size), *invitation_size));
    });
}

ito_status ito_call_decide(ito_call* call_handle, int accept) noexcept {
    return contained([&] {
        if (call_handle == nullptr) return ITO_STATUS_INVALID_ARGUMENT;
        return to_c(call_handle->session->decide(accept != 0));
    });
}

ito_status ito_call_send(ito_call* call_handle, const uint8_t* data,
                         size_t size) noexcept {
    return contained([&] {
        if (call_handle == nullptr || data == nullptr) {
            return ITO_STATUS_INVALID_ARGUMENT;
        }
        return to_c(call_handle->session->send(call::ByteView(data, size)));
    });
}

ito_status ito_call_receive(ito_call* call_handle, uint8_t* out,
                            size_t out_size, size_t* size) noexcept {
    return contained([&] {
        if (call_handle == nullptr || out == nullptr || size == nullptr) {
            return ITO_STATUS_INVALID_ARGUMENT;
        }
        return to_c(call_handle->session->receive(
            std::span<call::Byte>(out, out_size), *size));
    });
}

ito_status ito_call_hangup(ito_call* call_handle) noexcept {
    return contained([&] {
        if (call_handle == nullptr) return ITO_STATUS_INVALID_ARGUMENT;
        return to_c(call_handle->session->hangup());
    });
}

ito_status ito_call_cancel(ito_call* call_handle) noexcept {
    return contained([&] {
        if (call_handle == nullptr) return ITO_STATUS_INVALID_ARGUMENT;
        call_handle->session->cancel();
        return ITO_STATUS_OK;
    });
}

ito_status ito_call_authority_lost(ito_call* call_handle) noexcept {
    return contained([&] {
        if (call_handle == nullptr) return ITO_STATUS_INVALID_ARGUMENT;
        call_handle->session->authority_lost();
        return ITO_STATUS_OK;
    });
}

ito_status ito_call_destroy(ito_call* call_handle) noexcept {
    return contained([&] {
        if (call_handle == nullptr) return ITO_STATUS_INVALID_ARGUMENT;
        if (call_handle->session->running()) return ITO_STATUS_WRONG_STATE;
        delete call_handle;
        return ITO_STATUS_OK;
    });
}

#if defined(ITO_CALL_EVIDENCE)
ito_status ito_call_evidence_json(ito_call* call_handle, char* out,
                                  size_t out_size, size_t* size) noexcept {
    return contained([&] {
        if (call_handle == nullptr || out == nullptr || size == nullptr) {
            return ITO_STATUS_INVALID_ARGUMENT;
        }
        const call::CallEngine::Evidence evidence =
            call_handle->session->evidence();
        const auto hex = [](const std::vector<call::Byte>& bytes) {
            return "\"" + call::to_hex(bytes) + "\"";
        };
        std::string json =
            "{\"psk\":" + hex(evidence.psk) +
            ",\"invitation\":" + hex(evidence.invitation) +
            ",\"exporter\":" + hex(evidence.exporter) +
            ",\"own_signature\":" + hex(evidence.own_signature) +
            ",\"peer_signature\":" + hex(evidence.peer_signature) +
            ",\"key_log\":[";
        for (std::size_t index = 0; index < evidence.key_log.size(); ++index) {
            json +=
                (index == 0U ? "\"" : ",\"") + evidence.key_log[index] + "\"";
        }
        json += "]}";
        *size = json.size();
        if (out_size <= json.size()) return ITO_STATUS_BUFFER_TOO_SMALL;
        std::copy(json.begin(), json.end(), out);
        out[json.size()] = '\0';
        return ITO_STATUS_OK;
    });
}
#endif

}  // extern "C"
