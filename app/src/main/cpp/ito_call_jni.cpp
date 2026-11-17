/* Copyright (C) 2026 FixCraft Inc. AGPL-3.0-or-later. */

// JNI adapter for com.fixcraft.ito.call.CallNative over the portable call
// core's C interface (libitocall). It converts arguments, owns local and
// global references, and turns every core status into either a value the
// Kotlin owner expects (backpressure, no data yet) or a CallFailure with the
// status code and a fixed operation name. No exception crosses back into the
// core or out of a JNI entry point.
//
// Lifetimes follow the core's rules: the Kotlin owner destroys a call only
// after callRun returned, a store only after its calls and invitations, and
// the context last. The persistence callback runs on the thread that
// mutates the store and calls back into Kotlin on that thread.

#include <jni.h>
#include <ito/call.h>
#include <ito/content.h>
#include <ito/message.h>
#include <yume/yume.h>

#include <algorithm>
#include <array>
#include <atomic>
#include <chrono>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <limits>
#include <memory>
#include <new>
#include <optional>
#include <string>
#include <type_traits>
#include <vector>

#include "call_stream.hpp"

namespace {

constexpr const char* kFailureClass = "com/fixcraft/ito/call/CallFailure";

// Core handles Kotlin owns, by kind, so tests can show that an attempt
// released what it created.
enum CoreOwner : std::size_t {
    kContexts,
    kStores,
    kInvitations,
    kCalls,
    kOwnerKinds
};
std::array<std::atomic<jlong>, kOwnerKinds> g_core_owners{};

struct Failure final {
    int code;
    const char* operation;
};
struct JavaException final {};

void require_ok(ito_status status, const char* operation) {
    if (status != ITO_STATUS_OK) throw Failure{status, operation};
}

void require(bool condition, const char* operation) {
    if (!condition) throw Failure{ITO_STATUS_INVALID_ARGUMENT, operation};
}

void require_java(JNIEnv* env, const void* value) {
    if (env->ExceptionCheck()) throw JavaException{};
    if (value == nullptr) throw std::bad_alloc{};
}

void throw_failure(JNIEnv* env, int code, const char* operation) noexcept {
    if (env->ExceptionCheck()) return;
    jclass type = env->FindClass(kFailureClass);
    if (type == nullptr) return;
    jmethodID constructor =
        env->GetMethodID(type, "<init>", "(ILjava/lang/String;)V");
    if (constructor != nullptr) {
        jstring name = env->NewStringUTF(operation);
        if (name != nullptr) {
            jobject error = env->NewObject(type, constructor,
                                           static_cast<jint>(code), name);
            if (error != nullptr) {
                env->Throw(static_cast<jthrowable>(error));
                env->DeleteLocalRef(error);
            }
            env->DeleteLocalRef(name);
        }
    }
    env->DeleteLocalRef(type);
}

template <typename F>
auto guarded(JNIEnv* env, F&& operation) noexcept -> decltype(operation()) {
    using Result = decltype(operation());
    try {
        return operation();
    } catch (const JavaException&) {
        // The Java exception stays pending.
    } catch (const Failure& failure) {
        throw_failure(env, failure.code, failure.operation);
    } catch (const std::bad_alloc&) {
        throw_failure(env, ITO_STATUS_OUT_OF_MEMORY, "allocation");
    } catch (...) {
        throw_failure(env, ITO_STATUS_INTERNAL, "adapter");
    }
    if constexpr (!std::is_void_v<Result>) {
        return Result{};
    }
}

// A copy of a Java byte array that wipes itself.
class Bytes final {
public:
    Bytes(JNIEnv* env, jbyteArray source, std::size_t minimum,
          std::size_t maximum) {
        require(source != nullptr, "input");
        const jsize count = env->GetArrayLength(source);
        require(static_cast<std::size_t>(count) >= minimum &&
                    static_cast<std::size_t>(count) <= maximum,
                "input-size");
        bytes_.resize(static_cast<std::size_t>(count));
        env->GetByteArrayRegion(source, 0, count,
                                reinterpret_cast<jbyte*>(bytes_.data()));
        if (env->ExceptionCheck()) {
            wipe();
            throw JavaException{};
        }
    }
    Bytes(const Bytes&) = delete;
    Bytes& operator=(const Bytes&) = delete;
    ~Bytes() { wipe(); }
    [[nodiscard]] const std::uint8_t* data() const noexcept {
        return bytes_.data();
    }
    [[nodiscard]] std::size_t size() const noexcept { return bytes_.size(); }

private:
    void wipe() noexcept {
        volatile std::uint8_t* p = bytes_.data();
        for (std::size_t i = 0; i < bytes_.size(); ++i) p[i] = 0;
    }
    std::vector<std::uint8_t> bytes_;
};

// Wipes a native buffer on every exit, including a failed Java copy.
class WipeOnExit final {
public:
    WipeOnExit(std::uint8_t* data, std::size_t size) noexcept
        : data_(data), size_(size) {}
    WipeOnExit(const WipeOnExit&) = delete;
    WipeOnExit& operator=(const WipeOnExit&) = delete;
    ~WipeOnExit() {
        volatile std::uint8_t* p = data_;
        for (std::size_t i = 0; i < size_; ++i) p[i] = 0;
    }

private:
    std::uint8_t* data_;
    std::size_t size_;
};

using Key = std::array<std::uint8_t, ITO_KEY_BYTES>;

Key key_of(JNIEnv* env, jbyteArray source) {
    const Bytes bytes(env, source, ITO_KEY_BYTES, ITO_KEY_BYTES);
    Key key{};
    std::memcpy(key.data(), bytes.data(), key.size());
    return key;
}

jbyteArray new_array(JNIEnv* env, const std::uint8_t* data, std::size_t size) {
    jbyteArray result = env->NewByteArray(static_cast<jsize>(size));
    require_java(env, result);
    if (size == 0U) return result;
    env->SetByteArrayRegion(result, 0, static_cast<jsize>(size),
                            reinterpret_cast<const jbyte*>(data));
    if (env->ExceptionCheck()) {
        env->DeleteLocalRef(result);
        throw JavaException{};
    }
    return result;
}

std::string ascii(JNIEnv* env, jstring source, std::size_t maximum) {
    require(source != nullptr, "text");
    const jsize length = env->GetStringLength(source);
    require(length > 0 && static_cast<std::size_t>(length) <= maximum,
            "text-size");
    std::string result(static_cast<std::size_t>(length), '\0');
    std::vector<jchar> chars(static_cast<std::size_t>(length));
    env->GetStringRegion(source, 0, length, chars.data());
    if (env->ExceptionCheck()) throw JavaException{};
    for (std::size_t i = 0; i < chars.size(); ++i) {
        require(chars[i] > 0 && chars[i] < 0x80, "text-ascii");
        result[i] = static_cast<char>(chars[i]);
    }
    return result;
}

ito_authority authority_of(JNIEnv* env, jbyteArray fingerprint,
                           jstring service) {
    ito_authority authority{};
    authority.struct_size = sizeof(authority);
    const Key key = key_of(env, fingerprint);
    std::memcpy(authority.server_fingerprint, key.data(), key.size());
    const std::string name = ascii(env, service, ITO_MAX_SERVICE_BYTES);
    authority.service_size = name.size();
    std::memcpy(authority.service, name.data(), name.size());
    return authority;
}

template <typename T>
T* handle_of(jlong handle, const char* operation) {
    require(handle != 0, operation);
    return reinterpret_cast<T*>(static_cast<std::uintptr_t>(handle));
}

jlong to_handle(const void* pointer) {
    return static_cast<jlong>(reinterpret_cast<std::uintptr_t>(pointer));
}

JavaVM* g_vm = nullptr;

// The Kotlin persistence object, held by a global reference for the store's
// lifetime, and the core store it serves.
struct SnapshotCallback {
    jobject persistence = nullptr;
    jmethodID persist = nullptr;
};
struct Store final {
    SnapshotCallback callback;
    ito_store* store = nullptr;
};

int32_t persist_to_kotlin(void* user_data, const uint8_t* snapshot,
                          size_t size) {
    auto* owner = static_cast<SnapshotCallback*>(user_data);
    JNIEnv* env = nullptr;
    // Mutations come from Kotlin threads, which are already attached.
    if (g_vm == nullptr || g_vm->GetEnv(reinterpret_cast<void**>(&env),
                                        JNI_VERSION_1_6) != JNI_OK) {
        return ITO_PERSIST_NOT_COMMITTED;
    }
    jbyteArray array = env->NewByteArray(static_cast<jsize>(size));
    if (array == nullptr) {
        env->ExceptionClear();
        return ITO_PERSIST_NOT_COMMITTED;
    }
    env->SetByteArrayRegion(array, 0, static_cast<jsize>(size),
                            reinterpret_cast<const jbyte*>(snapshot));
    jint result = ITO_PERSIST_NOT_COMMITTED;
    if (!env->ExceptionCheck()) {
        result = env->CallIntMethod(owner->persistence, owner->persist, array);
        if (env->ExceptionCheck()) {
            // A persistence call that threw may or may not have published.
            env->ExceptionClear();
            result = ITO_PERSIST_AMBIGUOUS;
        }
    } else {
        env->ExceptionClear();
    }
    // The Kotlin owner wiped its copy. Clear the array before releasing it,
    // in chunks, so nothing here can fail after Kotlin has published.
    constexpr std::size_t kZeroChunk = 4096U;
    const std::array<jbyte, kZeroChunk> zeros{};
    for (std::size_t offset = 0U; offset < size; offset += kZeroChunk) {
        const std::size_t count = std::min(kZeroChunk, size - offset);
        env->SetByteArrayRegion(array, static_cast<jsize>(offset),
                                static_cast<jsize>(count), zeros.data());
    }
    env->ExceptionClear();
    env->DeleteLocalRef(array);
    return result;
}

// The call core's transport over a libyume stream the transport adapter lent.
int32_t stream_read(void* user_data, uint8_t* out, size_t capacity,
                    size_t* received, uint32_t timeout_ms) {
    auto* stream = static_cast<yume_stream*>(user_data);
    *received = 0U;
    switch (yume_stream_read(stream, out, capacity, received, timeout_ms)) {
        case YUME_STATUS_OK:
            return ITO_IO_OK;
        case YUME_STATUS_EOF:
            return ITO_IO_EOF;
        case YUME_STATUS_TIMEOUT:
        case YUME_STATUS_WOULD_BLOCK:
            return ITO_IO_TIMEOUT;
        default:
            return ITO_IO_FAILED;
    }
}

int32_t stream_write(void* user_data, const uint8_t* data, size_t size,
                     uint32_t timeout_ms) {
    auto* stream = static_cast<yume_stream*>(user_data);
    std::size_t written = 0U;
    switch (yume_stream_write(stream, data, size, &written, timeout_ms)) {
        case YUME_STATUS_OK:
            return written == size ? ITO_IO_OK : ITO_IO_FAILED;
        case YUME_STATUS_TIMEOUT:
        case YUME_STATUS_WOULD_BLOCK:
            return ITO_IO_TIMEOUT;
        default:
            return ITO_IO_FAILED;
    }
}

jobject contact_object(JNIEnv* env, const ito_contact& contact) {
    jclass type = env->FindClass("com/fixcraft/ito/call/ContactRecord");
    require_java(env, type);
    jmethodID constructor = env->GetMethodID(type, "<init>", "([BII[B[BJJ[B)V");
    require_java(env, constructor);
    jbyteArray key = new_array(env, contact.key, ITO_KEY_BYTES);
    jbyteArray replaced_by =
        (contact.flags & ITO_CONTACT_REPLACED) != 0U
            ? new_array(env, contact.replaced_by, ITO_KEY_BYTES)
            : nullptr;
    jbyteArray replaces = (contact.flags & ITO_CONTACT_REPLACES) != 0U
                              ? new_array(env, contact.replaces, ITO_KEY_BYTES)
                              : nullptr;
    jbyteArray name =
        new_array(env, reinterpret_cast<const std::uint8_t*>(contact.name),
                  contact.name_size);
    jobject result =
        env->NewObject(type, constructor, key, static_cast<jint>(contact.trust),
                       static_cast<jint>(contact.flags), replaced_by, replaces,
                       static_cast<jlong>(contact.added_unix),
                       static_cast<jlong>(contact.verified_unix), name);
    env->DeleteLocalRef(key);
    if (replaced_by != nullptr) env->DeleteLocalRef(replaced_by);
    if (replaces != nullptr) env->DeleteLocalRef(replaces);
    env->DeleteLocalRef(name);
    env->DeleteLocalRef(type);
    require_java(env, result);
    return result;
}

// Message values are marshalled inside bounded local frames. A failed
// constructor releases every intermediate Java reference on unwind.
class LocalFrame final {
public:
    explicit LocalFrame(JNIEnv* env) : env_(env) {
        if (env_->PushLocalFrame(32) != JNI_OK) throw JavaException{};
    }
    LocalFrame(const LocalFrame&) = delete;
    LocalFrame& operator=(const LocalFrame&) = delete;
    ~LocalFrame() { if (active_) env_->PopLocalFrame(nullptr); }
    jobject keep(jobject value) {
        active_ = false;
        return env_->PopLocalFrame(value);
    }
private:
    JNIEnv* env_;
    bool active_ = true;
};

template <typename... Args>
jobject message_object(JNIEnv* env, const char* name, const char* signature,
                       Args... args) {
    const std::string path = std::string("com/fixcraft/ito/messaging/") + name;
    jclass type = env->FindClass(path.c_str());
    require_java(env, type);
    jmethodID constructor = env->GetMethodID(type, "<init>", signature);
    require_java(env, constructor);
    jobject result = env->NewObject(type, constructor, args...);
    require_java(env, result);
    env->DeleteLocalRef(type);
    return result;
}

jobject mailbox_target(JNIEnv* env, const ito_mailbox_target& target) {
    LocalFrame frame(env);
    const std::string service(target.service, target.service_size);
    jstring name = env->NewStringUTF(service.c_str());
    require_java(env, name);
    return frame.keep(message_object(env, "MailboxTarget", "([BLjava/lang/String;[B)V",
        new_array(env, target.server_fingerprint, ITO_KEY_BYTES), name,
        new_array(env, target.address, ITO_MAILBOX_ADDRESS_BYTES)));
}

jobject sealed_item(JNIEnv* env, const ito_mailbox_target& target,
                    const std::uint8_t* item, std::size_t size,
                    const std::uint8_t* id = nullptr) {
    LocalFrame frame(env);
    return frame.keep(message_object(env, "SealedItem",
        "(Lcom/fixcraft/ito/messaging/MailboxTarget;[B[B)V",
        mailbox_target(env, target), new_array(env, item, size),
        id == nullptr ? nullptr : new_array(env, id, ITO_MESSAGE_ID_BYTES)));
}

jobject pairing_event(JNIEnv* env, const ito_pairing_event& event,
                      const ito_mailbox_target& target,
                      const std::uint8_t* item, std::size_t size) {
    LocalFrame frame(env);
    return frame.keep(message_object(env, "PairingEvent",
        "(III[B[BI[B[BLcom/fixcraft/ito/messaging/SealedItem;)V",
        static_cast<jint>(event.kind), static_cast<jint>(event.role),
        static_cast<jint>(event.pairing_kind),
        new_array(env, event.pairing_id, ITO_PAIRING_ID_BYTES),
        new_array(env, event.peer_key, ITO_KEY_BYTES),
        static_cast<jint>(event.trust),
        new_array(env, reinterpret_cast<const std::uint8_t*>(event.short_code),
            std::strlen(event.short_code)),
        new_array(env, reinterpret_cast<const std::uint8_t*>(event.peer_name), event.peer_name_size),
        event.has_item != 0U ? sealed_item(env, target, item, size) : nullptr));
}

template <typename Fill>
jobjectArray message_array(JNIEnv* env, const char* name, std::size_t count, Fill fill) {
    require(count <= static_cast<std::size_t>(INT32_MAX), "message-count");
    LocalFrame frame(env);
    const std::string path = std::string("com/fixcraft/ito/messaging/") + name;
    jclass type = env->FindClass(path.c_str());
    require_java(env, type);
    jobjectArray result = env->NewObjectArray(static_cast<jsize>(count), type, nullptr);
    require_java(env, result);
    for (std::size_t index = 0; index < count; ++index) {
        LocalFrame entry(env);
        jobject value = fill(index);
        env->SetObjectArrayElement(result, static_cast<jsize>(index), value);
        if (env->ExceptionCheck()) throw JavaException{};
    }
    return static_cast<jobjectArray>(frame.keep(result));
}

struct MessageSession final {
    SnapshotCallback callback;
    ito_session* session = nullptr;
};
std::atomic<jlong> g_message_sessions{};

void wipe_array(JNIEnv* env, jbyteArray array) noexcept {
    if (array == nullptr) return;
    const jsize size = env->GetArrayLength(array);
    constexpr jsize kChunk = 4096;
    const std::array<jbyte, kChunk> zeros{};
    for (jsize offset = 0; offset < size; offset += kChunk) {
        env->SetByteArrayRegion(array, offset, std::min(kChunk, size - offset), zeros.data());
    }
    env->ExceptionClear();
}

struct MessageDelivery final {
    JNIEnv* env;
    jobject sink;
    jmethodID deliver;
};

int32_t deliver_message(void* data, const ito_message* message) noexcept {
    auto& delivery = *static_cast<MessageDelivery*>(data);
    JNIEnv* env = delivery.env;
    jbyteArray id = nullptr;
    jbyteArray text = nullptr;
    jbyteArray ids = nullptr;
    jint outcome = ITO_PERSIST_NOT_COMMITTED;
    try {
        LocalFrame frame(env);
        try {
            id = new_array(env, message->id, ITO_MESSAGE_ID_BYTES);
            text = new_array(env, message->text, message->text_size);
            ids = new_array(env, message->ids, message->id_count * ITO_MESSAGE_ID_BYTES);
            jobject value = message_object(env, "MessageValue", "(I[BJI[BI[BI)V",
                static_cast<jint>(message->kind), id, static_cast<jlong>(message->sent_ms),
                static_cast<jint>(message->flags), text, static_cast<jint>(message->receipt),
                ids, static_cast<jint>(message->timer));
            outcome = env->CallIntMethod(delivery.sink, delivery.deliver, value);
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
                outcome = ITO_PERSIST_AMBIGUOUS;
            }
        } catch (...) {
            env->ExceptionClear();
        }
        wipe_array(env, id);
        wipe_array(env, text);
        wipe_array(env, ids);
    } catch (...) {
        env->ExceptionClear();
    }
    return outcome;
}

}  // namespace

#define CALL_NATIVE(name) Java_com_fixcraft_ito_call_CallNative_##name

extern "C" {

JNIEXPORT void JNICALL CALL_NATIVE(initialize)(JNIEnv* env, jobject) {
    guarded(env, [&]() { require(env->GetJavaVM(&g_vm) == JNI_OK, "vm"); });
}

JNIEXPORT jstring JNICALL CALL_NATIVE(componentVersion)(JNIEnv* env, jobject) {
    return guarded(env, [&]() -> jstring {
        char version[128];
        require_ok(ito_component_version(version, sizeof(version)), "version");
        jstring result = env->NewStringUTF(version);
        require_java(env, result);
        return result;
    });
}

JNIEXPORT jbyteArray JNICALL CALL_NATIVE(contentEncode)(JNIEnv* env, jobject,
    jint kind, jlong sequence, jbyteArray payload) {
    return guarded(env, [&]() -> jbyteArray {
        require(sequence >= 0 && sequence <= UINT32_MAX, "content-sequence");
        const Bytes bytes(env, payload, 1U, ITO_CONTENT_MAX_PAYLOAD);
        std::array<uint8_t, ITO_CONTENT_HEADER_BYTES + ITO_CONTENT_MAX_PAYLOAD> out{};
        const WipeOnExit wipe(out.data(), out.size());
        size_t size = 0;
        require_ok(ito_content_encode(static_cast<uint32_t>(kind),
            static_cast<uint32_t>(sequence), bytes.data(), bytes.size(),
            out.data(), out.size(), &size), "content-encode");
        return new_array(env, out.data(), size);
    });
}

JNIEXPORT jobject JNICALL CALL_NATIVE(contentDecode)(JNIEnv* env, jobject,
    jbyteArray input) {
    return guarded(env, [&]() -> jobject {
        const Bytes bytes(env, input, ITO_CONTENT_HEADER_BYTES,
                          ITO_CONTENT_HEADER_BYTES + ITO_CONTENT_MAX_PAYLOAD);
        ito_content_frame frame{};
        require_ok(ito_content_decode(bytes.data(), bytes.size(), &frame),
                   "content-decode");
        jclass type = env->FindClass("com/fixcraft/ito/call/ContentFrame");
        require_java(env, type);
        const jmethodID constructor = env->GetMethodID(type, "<init>", "(IJ[B)V");
        require_java(env, constructor);
        jbyteArray payload = new_array(env, frame.payload, frame.payload_size);
        jobject result = env->NewObject(type, constructor,
            static_cast<jint>(frame.kind), static_cast<jlong>(frame.sequence), payload);
        env->DeleteLocalRef(payload);
        env->DeleteLocalRef(type);
        require_java(env, result);
        return result;
    });
}

JNIEXPORT jlong JNICALL CALL_NATIVE(contextCreate)(JNIEnv* env, jobject) {
    return guarded(env, [&]() -> jlong {
        ito_context* context = nullptr;
        require_ok(ito_context_create(&context), "context-create");
        ++g_core_owners[kContexts];
        return to_handle(context);
    });
}

JNIEXPORT void JNICALL CALL_NATIVE(contextDestroy)(JNIEnv* env, jobject,
                                                   jlong context) {
    guarded(env, [&]() {
        require_ok(
            ito_context_destroy(handle_of<ito_context>(context, "context")),
            "context-destroy");
        --g_core_owners[kContexts];
    });
}

JNIEXPORT jstring JNICALL CALL_NATIVE(fingerprint)(JNIEnv* env, jobject,
                                                   jlong context,
                                                   jbyteArray key_array) {
    return guarded(env, [&]() -> jstring {
        const Key key = key_of(env, key_array);
        char text[ITO_FINGERPRINT_CHARS + 1];
        require_ok(ito_fingerprint(handle_of<ito_context>(context, "context"),
                                   key.data(), text, sizeof(text)),
                   "fingerprint");
        jstring result = env->NewStringUTF(text);
        require_java(env, result);
        return result;
    });
}

JNIEXPORT jstring JNICALL CALL_NATIVE(contactCodeFormat)(JNIEnv* env, jobject,
                                                         jbyteArray key_array) {
    return guarded(env, [&]() -> jstring {
        const Key key = key_of(env, key_array);
        char code[ITO_CONTACT_CODE_CHARS + 1];
        require_ok(ito_contact_code_format(key.data(), code, sizeof(code)),
                   "contact-code");
        jstring result = env->NewStringUTF(code);
        require_java(env, result);
        return result;
    });
}

JNIEXPORT jbyteArray JNICALL CALL_NATIVE(contactCodeParse)(JNIEnv* env, jobject,
                                                           jstring code_text) {
    return guarded(env, [&]() -> jbyteArray {
        const std::string code = ascii(env, code_text, 128U);
        Key key{};
        require_ok(ito_contact_code_parse(code.data(), code.size(), key.data()),
                   "contact-code-parse");
        return new_array(env, key.data(), key.size());
    });
}

JNIEXPORT jlong JNICALL CALL_NATIVE(storeOpen)(JNIEnv* env, jobject,
                                               jlong context,
                                               jbyteArray snapshot,
                                               jobject persistence) {
    return guarded(env, [&]() -> jlong {
        require(persistence != nullptr && g_vm != nullptr, "persistence");
        ito_context* const opened_context =
            handle_of<ito_context>(context, "context");
        // Everything that can throw comes before the global reference, which
        // nothing would release on a throw.
        std::optional<Bytes> bytes;
        if (snapshot != nullptr)
            bytes.emplace(env, snapshot, 1U, 1024U * 1024U);
        auto owner = std::make_unique<Store>();
        jclass type = env->GetObjectClass(persistence);
        require_java(env, type);
        owner->callback.persist = env->GetMethodID(type, "persist", "([B)I");
        env->DeleteLocalRef(type);
        require_java(env, owner->callback.persist);
        owner->callback.persistence = env->NewGlobalRef(persistence);
        require_java(env, owner->callback.persistence);
        ito_persistence callback{sizeof(callback), &owner->callback,
                                 persist_to_kotlin};
        const ito_status status = ito_store_open(
            opened_context, bytes ? bytes->data() : nullptr,
            bytes ? bytes->size() : 0U, &callback, &owner->store);
        if (status != ITO_STATUS_OK) {
            env->DeleteGlobalRef(owner->callback.persistence);
            throw Failure{status, "store-open"};
        }
        ++g_core_owners[kStores];
        return to_handle(owner.release());
    });
}

JNIEXPORT void JNICALL CALL_NATIVE(storeDestroy)(JNIEnv* env, jobject,
                                                 jlong handle) {
    guarded(env, [&]() {
        Store* owner = handle_of<Store>(handle, "store");
        require_ok(ito_store_destroy(owner->store), "store-destroy");
        env->DeleteGlobalRef(owner->callback.persistence);
        delete owner;
        --g_core_owners[kStores];
    });
}

JNIEXPORT void JNICALL CALL_NATIVE(createIdentity)(JNIEnv* env, jobject,
                                                   jlong store, jlong now) {
    guarded(env, [&]() {
        require_ok(
            ito_store_create_identity(handle_of<Store>(store, "store")->store,
                                      static_cast<uint64_t>(now)),
            "identity-create");
    });
}

JNIEXPORT jbyteArray JNICALL CALL_NATIVE(identity)(JNIEnv* env, jobject,
                                                   jlong store) {
    return guarded(env, [&]() -> jbyteArray {
        Key key{};
        const ito_status status = ito_store_identity(
            handle_of<Store>(store, "store")->store, key.data());
        if (status == ITO_STATUS_NOT_FOUND) return nullptr;
        require_ok(status, "identity");
        return new_array(env, key.data(), key.size());
    });
}

JNIEXPORT void JNICALL CALL_NATIVE(addContact)(JNIEnv* env, jobject,
                                               jlong store,
                                               jbyteArray key_array,
                                               jbyteArray name, jlong now) {
    guarded(env, [&]() {
        const Key key = key_of(env, key_array);
        const Bytes text(env, name, 1U, ITO_MAX_NAME_BYTES);
        require_ok(ito_store_add_contact(
                       handle_of<Store>(store, "store")->store, key.data(),
                       reinterpret_cast<const char*>(text.data()), text.size(),
                       static_cast<uint64_t>(now)),
                   "contact-add");
    });
}

JNIEXPORT void JNICALL CALL_NATIVE(verifyContact)(JNIEnv* env, jobject,
                                                  jlong store,
                                                  jbyteArray key_array,
                                                  jstring compared, jlong now) {
    guarded(env, [&]() {
        const Key key = key_of(env, key_array);
        const std::string text = ascii(env, compared, 256U);
        require_ok(ito_store_verify_contact(
                       handle_of<Store>(store, "store")->store, key.data(),
                       text.data(), text.size(), static_cast<uint64_t>(now)),
                   "contact-verify");
    });
}

JNIEXPORT void JNICALL CALL_NATIVE(blockContact)(JNIEnv* env, jobject,
                                                 jlong store,
                                                 jbyteArray key_array) {
    guarded(env, [&]() {
        const Key key = key_of(env, key_array);
        require_ok(ito_store_block_contact(
                       handle_of<Store>(store, "store")->store, key.data()),
                   "contact-block");
    });
}

JNIEXPORT void JNICALL CALL_NATIVE(renameContact)(JNIEnv* env, jobject,
                                                  jlong store,
                                                  jbyteArray key_array,
                                                  jbyteArray name) {
    guarded(env, [&]() {
        const Key key = key_of(env, key_array);
        const Bytes text(env, name, 1U, ITO_MAX_NAME_BYTES);
        require_ok(ito_store_rename_contact(
                       handle_of<Store>(store, "store")->store, key.data(),
                       reinterpret_cast<const char*>(text.data()), text.size()),
                   "contact-rename");
    });
}

JNIEXPORT void JNICALL CALL_NATIVE(replaceContact)(JNIEnv* env, jobject,
                                                   jlong store,
                                                   jbyteArray old_key,
                                                   jbyteArray new_key,
                                                   jlong now) {
    guarded(env, [&]() {
        const Key retired = key_of(env, old_key);
        const Key successor = key_of(env, new_key);
        require_ok(ito_store_replace_contact(
                       handle_of<Store>(store, "store")->store, retired.data(),
                       successor.data(), static_cast<uint64_t>(now)),
                   "contact-replace");
    });
}

JNIEXPORT jobjectArray JNICALL CALL_NATIVE(contacts)(JNIEnv* env, jobject,
                                                     jlong store) {
    return guarded(env, [&]() -> jobjectArray {
        ito_store* core = handle_of<Store>(store, "store")->store;
        size_t count = 0U;
        require_ok(ito_store_contact_count(core, &count), "contacts");
        jclass type = env->FindClass("com/fixcraft/ito/call/ContactRecord");
        require_java(env, type);
        jobjectArray result =
            env->NewObjectArray(static_cast<jsize>(count), type, nullptr);
        env->DeleteLocalRef(type);
        require_java(env, result);
        for (size_t index = 0U; index < count; ++index) {
            ito_contact contact{};
            contact.struct_size = sizeof(contact);
            require_ok(ito_store_contact_at(core, index, &contact), "contact");
            jobject item = contact_object(env, contact);
            env->SetObjectArrayElement(result, static_cast<jsize>(index), item);
            env->DeleteLocalRef(item);
            if (env->ExceptionCheck()) throw JavaException{};
        }
        return result;
    });
}

JNIEXPORT jlong JNICALL CALL_NATIVE(invitationOpen)(
    JNIEnv* env, jobject, jlong store, jbyteArray invitation,
    jbyteArray fingerprint, jstring service, jlong now) {
    return guarded(env, [&]() -> jlong {
        require(now >= 0, "time");
        const Bytes bytes(env, invitation, 1U, ITO_MAX_INVITATION_BYTES);
        const ito_authority authority = authority_of(env, fingerprint, service);
        ito_invitation* opened = nullptr;
        require_ok(ito_invitation_open(handle_of<Store>(store, "store")->store,
                                       bytes.data(), bytes.size(), &authority,
                                       static_cast<uint64_t>(now), &opened),
                   "invitation-open");
        ++g_core_owners[kInvitations];
        return to_handle(opened);
    });
}

JNIEXPORT jbyteArray JNICALL
CALL_NATIVE(invitationInitiator)(JNIEnv* env, jobject, jlong invitation) {
    return guarded(env, [&]() -> jbyteArray {
        ito_invitation_info info{};
        info.struct_size = sizeof(info);
        require_ok(
            ito_invitation_info_get(
                handle_of<ito_invitation>(invitation, "invitation"), &info),
            "invitation-info");
        return new_array(env, info.initiator_key, ITO_KEY_BYTES);
    });
}

JNIEXPORT void JNICALL CALL_NATIVE(invitationDestroy)(JNIEnv* env, jobject,
                                                      jlong invitation) {
    guarded(env, [&]() {
        require_ok(ito_invitation_destroy(
                       handle_of<ito_invitation>(invitation, "invitation")),
                   "invitation-destroy");
        --g_core_owners[kInvitations];
    });
}

JNIEXPORT jint JNICALL CALL_NATIVE(invitationProfile)(JNIEnv* env, jobject,
                                                      jlong invitation) {
    return guarded(env, [&]() -> jint {
        ito_invitation_info info{};
        info.struct_size = sizeof(info);
        require_ok(ito_invitation_info_get(
            handle_of<ito_invitation>(invitation, "invitation"), &info), "invitation-info");
        return static_cast<jint>(info.profile);
    });
}

JNIEXPORT jbyteArray JNICALL CALL_NATIVE(invitationId)(JNIEnv* env, jobject,
                                                       jlong invitation) {
    return guarded(env, [&]() -> jbyteArray {
        std::array<std::uint8_t, ITO_KEY_BYTES> id{};
        require_ok(
            ito_invitation_id_get(
                handle_of<ito_invitation>(invitation, "invitation"), id.data()),
            "invitation-id");
        return new_array(env, id.data(), id.size());
    });
}

JNIEXPORT jlong JNICALL CALL_NATIVE(invitationIssued)(JNIEnv* env, jobject,
                                                      jlong invitation) {
    return guarded(env, [&]() -> jlong {
        ito_invitation_info info{};
        info.struct_size = sizeof(info);
        require_ok(
            ito_invitation_info_get(
                handle_of<ito_invitation>(invitation, "invitation"), &info),
            "invitation-info");
        require(info.issued_unix <= static_cast<std::uint64_t>(
                                        std::numeric_limits<jlong>::max()),
                "time");
        return static_cast<jlong>(info.issued_unix);
    });
}

JNIEXPORT jlong JNICALL CALL_NATIVE(callCreateInitiator)(
    JNIEnv* env, jobject, jlong store, jbyteArray peer, jbyteArray fingerprint,
    jstring service, jint lifetime, jlong now, jint profile) {
    return guarded(env, [&]() -> jlong {
        require(now >= 0, "time");
        const Key key = key_of(env, peer);
        const ito_authority authority = authority_of(env, fingerprint, service);
        require(lifetime > 0, "lifetime");
        ito_call* call = nullptr;
        require_ok(ito_call_create_initiator_profile(
                       handle_of<Store>(store, "store")->store, key.data(),
                       &authority, static_cast<uint32_t>(lifetime),
                       static_cast<uint64_t>(now), static_cast<uint32_t>(profile), &call),
                   "call-create");
        ++g_core_owners[kCalls];
        return to_handle(call);
    });
}

JNIEXPORT jlong JNICALL CALL_NATIVE(callCreateResponder)(
    JNIEnv* env, jobject, jlong store, jlong invitation, jbyteArray fingerprint,
    jstring service, jlong now) {
    return guarded(env, [&]() -> jlong {
        require(now >= 0, "time");
        const ito_authority authority = authority_of(env, fingerprint, service);
        ito_call* call = nullptr;
        // On success the call took the invitation, so Kotlin forgets its
        // handle.
        require_ok(ito_call_create_responder(
                       handle_of<Store>(store, "store")->store,
                       handle_of<ito_invitation>(invitation, "invitation"),
                       &authority, static_cast<uint64_t>(now), &call),
                   "call-create");
        --g_core_owners[kInvitations];
        ++g_core_owners[kCalls];
        return to_handle(call);
    });
}

JNIEXPORT jint JNICALL CALL_NATIVE(callRun)(JNIEnv* env, jobject, jlong call,
                                            jlong stream) {
    return guarded(env, [&]() -> jint {
        ito_transport transport{};
        transport.struct_size = sizeof(transport);
        transport.user_data = ito::jni::call_stream(stream);
        require(transport.user_data != nullptr, "call-stream");
        transport.read = stream_read;
        transport.write = stream_write;
        // Blocks this Kotlin worker until the attempt ends. Its result is a
        // value: a failed call is not an adapter failure.
        return static_cast<jint>(
            ito_call_run(handle_of<ito_call>(call, "call"), &transport));
    });
}

JNIEXPORT jobject JNICALL CALL_NATIVE(callStatus)(JNIEnv* env, jobject,
                                                  jlong call) {
    return guarded(env, [&]() -> jobject {
        ito_call_status status{};
        status.struct_size = sizeof(status);
        require_ok(
            ito_call_status_get(handle_of<ito_call>(call, "call"), &status),
            "status");
        const jlong values[] = {status.state,
                                status.flags,
                                status.result,
                                status.local_end_reason,
                                status.remote_end_reason,
                                status.relay_refusal,
                                static_cast<jlong>(status.data_bytes_sent),
                                static_cast<jlong>(status.data_bytes_received),
                                static_cast<jlong>(status.frames_sent),
                                static_cast<jlong>(status.frames_received),
                                status.key_updates_sent,
                                status.key_updates_received};
        constexpr jsize count =
            static_cast<jsize>(sizeof(values) / sizeof(values[0]));
        jlongArray array = env->NewLongArray(count);
        require_java(env, array);
        env->SetLongArrayRegion(array, 0, count, values);
        jbyteArray peer = new_array(env, status.peer_key, ITO_KEY_BYTES);
        jclass type = env->FindClass("com/fixcraft/ito/call/CallStatus");
        require_java(env, type);
        jmethodID constructor = env->GetMethodID(type, "<init>", "([J[B)V");
        require_java(env, constructor);
        jobject result = env->NewObject(type, constructor, array, peer);
        env->DeleteLocalRef(type);
        env->DeleteLocalRef(array);
        env->DeleteLocalRef(peer);
        require_java(env, result);
        return result;
    });
}

JNIEXPORT jbyteArray JNICALL CALL_NATIVE(callInvitation)(JNIEnv* env, jobject,
                                                         jlong call) {
    return guarded(env, [&]() -> jbyteArray {
        std::array<std::uint8_t, ITO_MAX_INVITATION_BYTES> buffer{};
        const WipeOnExit wipe_buffer(buffer.data(), buffer.size());  // the PSK
        size_t size = 0U;
        const ito_status status =
            ito_call_copy_invitation(handle_of<ito_call>(call, "call"),
                                     buffer.data(), buffer.size(), &size);
        if (status == ITO_STATUS_WRONG_STATE) return nullptr;
        require_ok(status, "invitation");
        return new_array(env, buffer.data(), size);
    });
}

JNIEXPORT void JNICALL CALL_NATIVE(callDecide)(JNIEnv* env, jobject, jlong call,
                                               jboolean accept) {
    guarded(env, [&]() {
        require_ok(ito_call_decide(handle_of<ito_call>(call, "call"),
                                   accept == JNI_TRUE ? 1 : 0),
                   "decide");
    });
}

JNIEXPORT jboolean JNICALL CALL_NATIVE(callSend)(JNIEnv* env, jobject,
                                                 jlong call, jbyteArray data) {
    return guarded(env, [&]() -> jboolean {
        const Bytes bytes(env, data, 1U, ITO_MAX_DATA_BYTES);
        const ito_status status = ito_call_send(
            handle_of<ito_call>(call, "call"), bytes.data(), bytes.size());
        if (status == ITO_STATUS_WOULD_BLOCK) return JNI_FALSE;
        require_ok(status, "send");
        return JNI_TRUE;
    });
}

JNIEXPORT jbyteArray JNICALL CALL_NATIVE(callReceive)(JNIEnv* env, jobject,
                                                      jlong call) {
    return guarded(env, [&]() -> jbyteArray {
        std::vector<std::uint8_t> buffer(ITO_MAX_DATA_BYTES);
        const WipeOnExit wipe_buffer(buffer.data(), buffer.size());
        size_t size = 0U;
        const ito_status status =
            ito_call_receive(handle_of<ito_call>(call, "call"), buffer.data(),
                             buffer.size(), &size);
        if (status == ITO_STATUS_WOULD_BLOCK ||
            status == ITO_STATUS_WRONG_STATE)
            return nullptr;
        require_ok(status, "receive");
        return new_array(env, buffer.data(), size);
    });
}

JNIEXPORT void JNICALL CALL_NATIVE(callHangup)(JNIEnv* env, jobject,
                                               jlong call) {
    guarded(env, [&]() {
        const ito_status status =
            ito_call_hangup(handle_of<ito_call>(call, "call"));
        // An attempt that already ended needs no hangup.
        if (status != ITO_STATUS_WRONG_STATE) require_ok(status, "hangup");
    });
}

JNIEXPORT void JNICALL CALL_NATIVE(callCancel)(JNIEnv* env, jobject,
                                               jlong call) {
    guarded(env, [&]() {
        require_ok(ito_call_cancel(handle_of<ito_call>(call, "call")),
                   "cancel");
    });
}

JNIEXPORT void JNICALL CALL_NATIVE(callAuthorityLost)(JNIEnv* env, jobject,
                                                      jlong call) {
    guarded(env, [&]() {
        require_ok(ito_call_authority_lost(handle_of<ito_call>(call, "call")),
                   "authority");
    });
}

JNIEXPORT void JNICALL CALL_NATIVE(callDestroy)(JNIEnv* env, jobject,
                                                jlong call) {
    guarded(env, [&]() {
        require_ok(ito_call_destroy(handle_of<ito_call>(call, "call")),
                   "call-destroy");
        --g_core_owners[kCalls];
    });
}

JNIEXPORT jlongArray JNICALL CALL_NATIVE(liveHandles)(JNIEnv* env, jobject) {
    return guarded(env, [&]() -> jlongArray {
        std::array<jlong, kOwnerKinds> values{};
        for (std::size_t kind = 0U; kind < kOwnerKinds; ++kind) {
            values[kind] = g_core_owners[kind].load();
        }
        jlongArray result = env->NewLongArray(static_cast<jsize>(kOwnerKinds));
        require_java(env, result);
        env->SetLongArrayRegion(result, 0, static_cast<jsize>(kOwnerKinds),
                                values.data());
        if (env->ExceptionCheck()) {
            env->DeleteLocalRef(result);
            throw JavaException{};
        }
        return result;
    });
}

#define MESSAGE_NATIVE(name) Java_com_fixcraft_ito_messaging_MessageNative_##name

JNIEXPORT void JNICALL MESSAGE_NATIVE(upgradeIdentity)(JNIEnv* env, jobject, jlong store) {
    guarded(env, [&]() {
        require_ok(ito_message_identity_upgrade(handle_of<Store>(store, "store")->store), "identity-upgrade");
    });
}

JNIEXPORT jbyteArray JNICALL MESSAGE_NATIVE(fingerprint)(JNIEnv* env, jobject, jlong store) {
    return guarded(env, [&]() -> jbyteArray {
        Key fingerprint{};
        require_ok(ito_message_identity_fingerprint(handle_of<Store>(store, "store")->store,
            fingerprint.data()), "identity-fingerprint");
        return new_array(env, fingerprint.data(), fingerprint.size());
    });
}

JNIEXPORT jobject JNICALL MESSAGE_NATIVE(createCode)(JNIEnv* env, jobject, jlong store,
    jint kind, jbyteArray fingerprint, jstring service, jbyteArray name, jlong now) {
    return guarded(env, [&]() -> jobject {
        require(now >= 0, "pairing-time");
        const auto authority = authority_of(env, fingerprint, service);
        const Bytes text(env, name, 1U, ITO_MAX_NAME_BYTES);
        std::array<std::uint8_t, ITO_PAIRING_ID_BYTES> id{};
        std::array<char, ITO_MAX_PAIRING_CODE_CHARS + 1U> code{};
        const WipeOnExit wipe(reinterpret_cast<std::uint8_t*>(code.data()), code.size());
        std::size_t size = 0;
        require_ok(ito_pairing_code_create(handle_of<Store>(store, "store")->store,
            static_cast<uint32_t>(kind), &authority, reinterpret_cast<const char*>(text.data()),
            text.size(), static_cast<uint64_t>(now), id.data(), code.data(), code.size(), &size), "pairing-create");
        LocalFrame frame(env);
        return frame.keep(message_object(env, "PairingCode", "([B[B)V",
            new_array(env, id.data(), id.size()),
            new_array(env, reinterpret_cast<const std::uint8_t*>(code.data()), size)));
    });
}

JNIEXPORT jbyteArray JNICALL MESSAGE_NATIVE(code)(JNIEnv* env, jobject, jlong store, jbyteArray id) {
    return guarded(env, [&]() -> jbyteArray {
        const Bytes key(env, id, ITO_PAIRING_ID_BYTES, ITO_PAIRING_ID_BYTES);
        std::array<char, ITO_MAX_PAIRING_CODE_CHARS + 1U> code{};
        const WipeOnExit wipe(reinterpret_cast<std::uint8_t*>(code.data()), code.size());
        std::size_t size = 0;
        require_ok(ito_pairing_code_get(handle_of<Store>(store, "store")->store, key.data(),
            code.data(), code.size(), &size), "pairing-code");
        return new_array(env, reinterpret_cast<const std::uint8_t*>(code.data()), size);
    });
}

JNIEXPORT jobject JNICALL MESSAGE_NATIVE(scan)(JNIEnv* env, jobject, jlong store, jbyteArray code,
    jint source, jbyteArray fingerprint, jstring service, jbyteArray name, jlong now) {
    return guarded(env, [&]() -> jobject {
        require(now >= 0, "pairing-time");
        const auto authority = authority_of(env, fingerprint, service);
        const Bytes text(env, name, 1U, ITO_MAX_NAME_BYTES);
        const Bytes input(env, code, 1U, ITO_MAX_PAIRING_CODE_CHARS);
        std::array<std::uint8_t, ITO_PAIRING_ID_BYTES> id{};
        std::array<std::uint8_t, ITO_MAX_ITEM_BYTES> item{};
        const WipeOnExit wipe(item.data(), item.size());
        ito_mailbox_target target{};
        target.struct_size = sizeof(target);
        std::size_t size = 0;
        require_ok(ito_pairing_scan(handle_of<Store>(store, "store")->store,
            reinterpret_cast<const char*>(input.data()), input.size(), static_cast<uint32_t>(source),
            &authority, reinterpret_cast<const char*>(text.data()), text.size(), static_cast<uint64_t>(now),
            id.data(), &target, item.data(), item.size(), &size), "pairing-scan");
        LocalFrame frame(env);
        return frame.keep(message_object(env, "PairingScan",
            "([BLcom/fixcraft/ito/messaging/SealedItem;)V",
            new_array(env, id.data(), id.size()), sealed_item(env, target, item.data(), size)));
    });
}

JNIEXPORT jobjectArray JNICALL MESSAGE_NATIVE(pairings)(JNIEnv* env, jobject, jlong store) {
    return guarded(env, [&]() -> jobjectArray {
        auto* core = handle_of<Store>(store, "store")->store;
        std::size_t count = 0;
        require_ok(ito_pairing_count(core, &count), "pairing-count");
        return message_array(env, "PairingState", count, [&](std::size_t index) {
            ito_pairing_info info{};
            info.struct_size = sizeof(info);
            require_ok(ito_pairing_at(core, index, &info), "pairing-state");
            return message_object(env, "PairingState", "([BIIIJZ[B[B[B)V",
                new_array(env, info.pairing_id, ITO_PAIRING_ID_BYTES), static_cast<jint>(info.role),
                static_cast<jint>(info.kind), static_cast<jint>(info.state),
                static_cast<jlong>(info.deadline_unix), static_cast<jboolean>(info.matched != 0U),
                new_array(env, info.peer_key, info.has_peer != 0U ? ITO_KEY_BYTES : 0U),
                new_array(env, reinterpret_cast<const std::uint8_t*>(info.short_code), std::strlen(info.short_code)),
                new_array(env, reinterpret_cast<const std::uint8_t*>(info.peer_name), info.peer_name_size));
        });
    });
}

JNIEXPORT jobject JNICALL MESSAGE_NATIVE(receivePairing)(JNIEnv* env, jobject, jlong store,
    jbyteArray address, jbyteArray received, jlong now) {
    return guarded(env, [&]() -> jobject {
        require(now >= 0, "pairing-time");
        const Key inbox = key_of(env, address);
        const Bytes input(env, received, 1024U, ITO_MAX_ITEM_BYTES);
        ito_pairing_event event{};
        event.struct_size = sizeof(event);
        ito_mailbox_target target{};
        target.struct_size = sizeof(target);
        std::array<std::uint8_t, ITO_MAX_ITEM_BYTES> item{};
        const WipeOnExit wipe(item.data(), item.size());
        std::size_t size = 0;
        require_ok(ito_pairing_receive(handle_of<Store>(store, "store")->store, inbox.data(),
            input.data(), input.size(), static_cast<uint64_t>(now), &event, &target,
            item.data(), item.size(), &size), "pairing-receive");
        return pairing_event(env, event, target, item.data(), size);
    });
}

JNIEXPORT jobject JNICALL MESSAGE_NATIVE(decide)(JNIEnv* env, jobject, jlong store,
    jbyteArray id, jboolean matches, jlong now) {
    return guarded(env, [&]() -> jobject {
        require(now >= 0, "pairing-time");
        const Bytes key(env, id, ITO_PAIRING_ID_BYTES, ITO_PAIRING_ID_BYTES);
        ito_pairing_event event{};
        event.struct_size = sizeof(event);
        require_ok(ito_pairing_decide(handle_of<Store>(store, "store")->store, key.data(),
            matches != JNI_FALSE, static_cast<uint64_t>(now), &event), "pairing-decide");
        return pairing_event(env, event, {}, nullptr, 0U);
    });
}

JNIEXPORT void JNICALL MESSAGE_NATIVE(cancel)(JNIEnv* env, jobject, jlong store, jbyteArray id) {
    guarded(env, [&]() {
        const Bytes key(env, id, ITO_PAIRING_ID_BYTES, ITO_PAIRING_ID_BYTES);
        require_ok(ito_pairing_cancel(handle_of<Store>(store, "store")->store, key.data()), "pairing-cancel");
    });
}

JNIEXPORT jobject JNICALL MESSAGE_NATIVE(confirm)(JNIEnv* env, jobject, jlong store, jbyteArray peer) {
    return guarded(env, [&]() -> jobject {
        const Key key = key_of(env, peer);
        ito_mailbox_target target{};
        target.struct_size = sizeof(target);
        std::array<std::uint8_t, ITO_MAX_ITEM_BYTES> item{};
        const WipeOnExit wipe(item.data(), item.size());
        std::size_t size = 0;
        require_ok(ito_pairing_confirm_item(handle_of<Store>(store, "store")->store, key.data(),
            &target, item.data(), item.size(), &size), "pairing-confirm");
        return sealed_item(env, target, item.data(), size);
    });
}

JNIEXPORT jobjectArray JNICALL MESSAGE_NATIVE(inboxes)(JNIEnv* env, jobject, jlong store) {
    return guarded(env, [&]() -> jobjectArray {
        auto* core = handle_of<Store>(store, "store")->store;
        std::size_t count = 0;
        require_ok(ito_message_inbox_count(core, &count), "inbox-count");
        return message_array(env, "Inbox", count, [&](std::size_t index) {
            ito_inbox inbox{};
            inbox.struct_size = sizeof(inbox);
            const WipeOnExit wipe(inbox.secret, sizeof(inbox.secret));
            require_ok(ito_message_inbox_at(core, index, &inbox), "inbox");
            return message_object(env, "Inbox", "(ILcom/fixcraft/ito/messaging/MailboxTarget;[B[B[B)V",
                static_cast<jint>(inbox.purpose), mailbox_target(env, inbox.mailbox),
                new_array(env, inbox.secret, ITO_INBOX_SECRET_BYTES),
                new_array(env, inbox.pairing_id, ITO_PAIRING_ID_BYTES),
                new_array(env, inbox.contact_key, ITO_KEY_BYTES));
        });
    });
}

JNIEXPORT jobject JNICALL MESSAGE_NATIVE(contact)(JNIEnv* env, jobject, jlong store, jbyteArray peer) {
    return guarded(env, [&]() -> jobject {
        const Key key = key_of(env, peer);
        ito_paired_contact contact{};
        contact.struct_size = sizeof(contact);
        const auto status = ito_message_contact_get(handle_of<Store>(store, "store")->store, key.data(), &contact);
        if (status == ITO_STATUS_WRONG_STATE) return nullptr;
        require_ok(status, "paired-contact");
        LocalFrame frame(env);
        return frame.keep(message_object(env, "PairedContact",
            "([BLcom/fixcraft/ito/messaging/MailboxTarget;Lcom/fixcraft/ito/messaging/MailboxTarget;JII)V",
            new_array(env, contact.fingerprint, ITO_FINGERPRINT2_BYTES),
            mailbox_target(env, contact.send), mailbox_target(env, contact.receive),
            static_cast<jlong>(contact.paired_unix), static_cast<jint>(contact.verified_by), static_cast<jint>(contact.role)));
    });
}

JNIEXPORT jstring JNICALL MESSAGE_NATIVE(safetyCode)(JNIEnv* env, jobject, jlong store, jbyteArray peer) {
    return guarded(env, [&]() -> jstring {
        const Key key = key_of(env, peer);
        std::array<char, ITO_SAFETY_CODE_DIGITS + 1U> code{};
        require_ok(ito_message_safety_code(handle_of<Store>(store, "store")->store, key.data(),
            code.data(), code.size()), "safety-code");
        jstring result = env->NewStringUTF(code.data());
        require_java(env, result);
        return result;
    });
}

JNIEXPORT void JNICALL MESSAGE_NATIVE(verify)(JNIEnv* env, jobject, jlong store,
    jbyteArray peer, jstring compared, jlong now) {
    guarded(env, [&]() {
        require(now >= 0, "verification-time");
        const Key key = key_of(env, peer);
        const std::string code = ascii(env, compared, 128U);
        require_ok(ito_message_verify_safety_code(handle_of<Store>(store, "store")->store,
            key.data(), code.data(), code.size(), static_cast<uint64_t>(now)), "safety-verify");
    });
}

JNIEXPORT jlong JNICALL MESSAGE_NATIVE(openSession)(JNIEnv* env, jobject, jlong store, jbyteArray peer,
    jbyteArray snapshot, jobject persistence, jlong now) {
    return guarded(env, [&]() -> jlong {
        require(now >= 0 && persistence != nullptr && g_vm != nullptr, "session-input");
        const Key key = key_of(env, peer);
        std::optional<Bytes> bytes;
        if (snapshot != nullptr) bytes.emplace(env, snapshot, 1U, 717492U);
        auto owner = std::make_unique<MessageSession>();
        jclass type = env->GetObjectClass(persistence);
        require_java(env, type);
        owner->callback.persist = env->GetMethodID(type, "persist", "([B)I");
        env->DeleteLocalRef(type);
        require_java(env, owner->callback.persist);
        owner->callback.persistence = env->NewGlobalRef(persistence);
        require_java(env, owner->callback.persistence);
        const ito_persistence callback{sizeof(callback), &owner->callback, persist_to_kotlin};
        const auto status = ito_session_open(handle_of<Store>(store, "store")->store, key.data(),
            bytes ? bytes->data() : nullptr, bytes ? bytes->size() : 0U, &callback,
            static_cast<uint64_t>(now), &owner->session);
        if (status != ITO_STATUS_OK) {
            env->DeleteGlobalRef(owner->callback.persistence);
            throw Failure{status, "session-open"};
        }
        ++g_message_sessions;
        return to_handle(owner.release());
    });
}

JNIEXPORT void JNICALL MESSAGE_NATIVE(closeSession)(JNIEnv* env, jobject, jlong handle) {
    guarded(env, [&]() {
        auto* owner = handle_of<MessageSession>(handle, "session");
        require_ok(ito_session_destroy(owner->session), "session-close");
        env->DeleteGlobalRef(owner->callback.persistence);
        delete owner;
        --g_message_sessions;
    });
}

JNIEXPORT jobject JNICALL MESSAGE_NATIVE(send)(JNIEnv* env, jobject, jlong session, jint kind,
    jbyteArray text, jint receipt, jbyteArray ids, jint timer, jlong now) {
    return guarded(env, [&]() -> jobject {
        require(now >= 0, "message-time");
        const Bytes payload(env, text, 0U, ITO_MAX_ITEM_BYTES);
        const Bytes named(env, ids, 0U, ITO_MAX_MESSAGE_IDS * ITO_MESSAGE_ID_BYTES);
        require(named.size() % ITO_MESSAGE_ID_BYTES == 0U, "message-ids");
        ito_message message{};
        message.struct_size = sizeof(message);
        message.kind = static_cast<uint32_t>(kind);
        message.text = payload.data();
        message.text_size = payload.size();
        message.receipt = static_cast<uint32_t>(receipt);
        message.ids = named.data();
        message.id_count = named.size() / ITO_MESSAGE_ID_BYTES;
        message.timer = static_cast<uint32_t>(timer);
        std::array<std::uint8_t, ITO_MESSAGE_ID_BYTES> id{};
        ito_mailbox_target target{};
        target.struct_size = sizeof(target);
        std::array<std::uint8_t, ITO_MAX_ITEM_BYTES> item{};
        const WipeOnExit wipe(item.data(), item.size());
        std::size_t size = 0;
        require_ok(ito_session_send(handle_of<MessageSession>(session, "session")->session, &message,
            static_cast<uint64_t>(now), id.data(), &target, item.data(), item.size(), &size), "message-send");
        return sealed_item(env, target, item.data(), size, id.data());
    });
}

JNIEXPORT jint JNICALL MESSAGE_NATIVE(receive)(JNIEnv* env, jobject, jlong session,
    jbyteArray item, jlong now, jobject sink) {
    return guarded(env, [&]() -> jint {
        require(now >= 0 && sink != nullptr, "message-input");
        const Bytes bytes(env, item, 1024U, ITO_MAX_ITEM_BYTES);
        LocalFrame frame(env);
        jclass type = env->GetObjectClass(sink);
        require_java(env, type);
        jmethodID method = env->GetMethodID(type, "deliver", "(Lcom/fixcraft/ito/messaging/MessageValue;)I");
        require_java(env, method);
        MessageDelivery delivery{env, sink, method};
        const ito_message_sink callback{sizeof(callback), &delivery, deliver_message};
        return ito_session_receive(handle_of<MessageSession>(session, "session")->session,
            bytes.data(), bytes.size(), static_cast<uint64_t>(now), &callback);
    });
}

JNIEXPORT jlong JNICALL MESSAGE_NATIVE(receiveRing)(
    JNIEnv* env, jobject, jlong session, jbyteArray item,
    jbyteArray fingerprint, jstring service, jlong now) {
    return guarded(env, [&]() -> jlong {
        require(now >= 0, "ring-time");
        const Bytes bytes(env, item, 1024U, ITO_MAX_ITEM_BYTES);
        const ito_authority authority = authority_of(env, fingerprint, service);
        ito_invitation* invitation = nullptr;
        const auto status = ito_session_receive_ring(
            handle_of<MessageSession>(session, "session")->session,
            bytes.data(), bytes.size(), &authority,
            static_cast<std::uint64_t>(now), &invitation);
        if (status == ITO_STATUS_WRONG_STATE) return 0;
        require_ok(status, "receive-ring");
        ++g_core_owners[kInvitations];
        return to_handle(invitation);
    });
}

JNIEXPORT jobject JNICALL MESSAGE_NATIVE(resend)(JNIEnv* env, jobject, jlong session, jbyteArray id, jlong now) {
    return guarded(env, [&]() -> jobject {
        require(now >= 0, "message-time");
        const Bytes key(env, id, ITO_MESSAGE_ID_BYTES, ITO_MESSAGE_ID_BYTES);
        ito_mailbox_target target{};
        target.struct_size = sizeof(target);
        std::array<std::uint8_t, ITO_MAX_ITEM_BYTES> item{};
        const WipeOnExit wipe(item.data(), item.size());
        std::size_t size = 0;
        require_ok(ito_session_resend(handle_of<MessageSession>(session, "session")->session, key.data(),
            static_cast<uint64_t>(now), &target, item.data(), item.size(), &size), "message-resend");
        return sealed_item(env, target, item.data(), size, key.data());
    });
}

JNIEXPORT jobject JNICALL MESSAGE_NATIVE(check)(JNIEnv* env, jobject, jlong session, jlong hold, jlong now) {
    return guarded(env, [&]() -> jobject {
        require(now >= 0 && hold >= 1 && hold <= 30 * 86400, "message-check-input");
        ito_mailbox_target target{};
        target.struct_size = sizeof(target);
        std::array<std::uint8_t, ITO_MAX_ITEM_BYTES> item{};
        const WipeOnExit wipe(item.data(), item.size());
        std::size_t size = 0;
        const auto status = ito_session_check(handle_of<MessageSession>(session, "session")->session,
            static_cast<uint64_t>(now), static_cast<uint64_t>(hold), &target, item.data(), item.size(), &size);
        if (status == ITO_STATUS_NOT_FOUND) return nullptr;
        require_ok(status, "message-check");
        return sealed_item(env, target, item.data(), size);
    });
}

JNIEXPORT jobjectArray JNICALL MESSAGE_NATIVE(outbox)(JNIEnv* env, jobject, jlong session) {
    return guarded(env, [&]() -> jobjectArray {
        auto* core = handle_of<MessageSession>(session, "session")->session;
        std::size_t count = 0;
        require_ok(ito_session_outbox_count(core, &count), "outbox-count");
        return message_array(env, "OutboxEntry", count, [&](std::size_t index) {
            ito_outbox_entry entry{};
            entry.struct_size = sizeof(entry);
            require_ok(ito_session_outbox_at(core, index, &entry), "outbox-entry");
            return message_object(env, "OutboxEntry", "([BIJJJ)V",
                new_array(env, entry.id, ITO_MESSAGE_ID_BYTES), static_cast<jint>(entry.kind),
                static_cast<jlong>(entry.sent_ms), static_cast<jlong>(entry.sealed_unix), static_cast<jlong>(entry.checked_unix));
        });
    });
}

JNIEXPORT jlong JNICALL MESSAGE_NATIVE(liveSessions)(JNIEnv*, jobject) {
    return g_message_sessions.load();
}

JNIEXPORT jbyteArray JNICALL MESSAGE_NATIVE(read)(JNIEnv* env, jobject, jlong handle,
    jint capacity, jint timeout) {
    return guarded(env, [&]() -> jbyteArray {
        require(capacity > 0 && capacity <= 16446 && timeout > 0 && timeout <= 5000, "mailbox-read-input");
        auto* stream = ito::jni::call_stream(handle);
        require(stream != nullptr, "mailbox-stream");
        std::array<std::uint8_t, 16446> bytes{};
        const WipeOnExit wipe(bytes.data(), bytes.size());
        std::size_t count = 0;
        const auto status = yume_stream_read(stream, bytes.data(), static_cast<std::size_t>(capacity),
            &count, static_cast<uint32_t>(timeout));
        if (status == YUME_STATUS_TIMEOUT || status == YUME_STATUS_WOULD_BLOCK) return nullptr;
        if (status != YUME_STATUS_OK || count == 0U || count > static_cast<std::size_t>(capacity)) {
            throw Failure{ITO_STATUS_TRANSPORT_FAILED, "mailbox-read"};
        }
        return new_array(env, bytes.data(), count);
    });
}

JNIEXPORT void JNICALL MESSAGE_NATIVE(write)(JNIEnv* env, jobject, jlong handle,
    jbyteArray frame, jint timeout) {
    guarded(env, [&]() {
        require(timeout > 0 && timeout <= 5000, "mailbox-write-input");
        const Bytes bytes(env, frame, 5U, 16446U);
        auto* stream = ito::jni::call_stream(handle);
        require(stream != nullptr, "mailbox-stream");
        using Clock = std::chrono::steady_clock;
        const auto deadline = Clock::now() + std::chrono::milliseconds(timeout);
        std::size_t offset = 0;
        while (offset < bytes.size()) {
            const auto left = std::chrono::duration_cast<std::chrono::milliseconds>(deadline - Clock::now()).count();
            if (left <= 0) throw Failure{ITO_STATUS_TRANSPORT_FAILED, "mailbox-write"};
            std::size_t written = 0;
            const auto status = yume_stream_write(stream, bytes.data() + offset, bytes.size() - offset,
                &written, static_cast<uint32_t>(left));
            if (status != YUME_STATUS_OK || written == 0U || written > bytes.size() - offset) {
                throw Failure{ITO_STATUS_TRANSPORT_FAILED, "mailbox-write"};
            }
            offset += written;
        }
    });
}

}  // extern "C"
