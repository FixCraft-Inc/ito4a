/* Copyright (C) 2026 FixCraft Inc. AGPL-3.0-or-later. */
#include <jni.h>
#include <yume/yume.h>

#include "call_stream.hpp"

#include <algorithm>
#include <array>
#include <atomic>
#include <chrono>
#include <cstddef>
#include <cstdint>
#include <iterator>
#include <limits>
#include <memory>
#include <new>
#include <string>
#include <type_traits>
#include <utility>
#include <vector>

namespace {

JavaVM* g_vm = nullptr;
std::atomic<jlong> g_owners[7]{};
constexpr std::size_t kMaxDocument = 1024U * 1024U;
constexpr std::size_t kMaxSealed = 2U * 1024U * 1024U;
constexpr std::uint32_t kIoTimeoutMs = 5000U;

struct Failure final {
    yume_status code;
    const char* operation;
};
struct JavaException final {};

void require_ok(yume_status code, const char* operation) {
    if (code != YUME_STATUS_OK) {
        throw Failure{code, operation};
    }
}

void require(bool condition, const char* operation) {
    if (!condition) {
        throw Failure{YUME_STATUS_INVALID_ARGUMENT, operation};
    }
}

void require_java(JNIEnv* env, const void* value) {
    if (env->ExceptionCheck()) {
        throw JavaException{};
    }
    if (value == nullptr) {
        throw std::bad_alloc{};
    }
}

template <typename T>
class LocalRef final {
public:
    LocalRef(JNIEnv* env, T value) : env_(env), value_(value) {
        require_java(env, value);
    }
    LocalRef(const LocalRef&) = delete;
    LocalRef& operator=(const LocalRef&) = delete;
    ~LocalRef() { env_->DeleteLocalRef(value_); }
    T get() const noexcept { return value_; }

private:
    JNIEnv* env_;
    T value_;
};

void throw_failure(JNIEnv* env, yume_status code,
                   const char* operation) noexcept {
    if (env->ExceptionCheck()) {
        return;
    }
    jclass type =
        env->FindClass("com/fixcraft/ito/transport/nativebridge/NativeFailure");
    if (type == nullptr) {
        return;
    }
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
        // The original Java exception remains pending.
    } catch (const Failure& error) {
        throw_failure(env, error.code, error.operation);
    } catch (const std::bad_alloc&) {
        throw_failure(env, YUME_STATUS_RESOURCE_EXHAUSTED, "allocation");
    } catch (...) {
        throw_failure(env, YUME_STATUS_INTERNAL_ERROR, "adapter");
    }
    if constexpr (!std::is_void_v<Result>) {
        return Result{};
    }
}

class Bytes final {
public:
    explicit Bytes(std::size_t size) : bytes_(size) {}
    Bytes(JNIEnv* env, jbyteArray source, std::size_t maximum) {
        require(source != nullptr, "input");
        const jsize count = env->GetArrayLength(source);
        require(count > 0 && static_cast<std::size_t>(count) <= maximum,
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
    std::uint8_t* data() noexcept { return bytes_.data(); }
    const std::uint8_t* data() const noexcept { return bytes_.data(); }
    std::size_t size() const noexcept { return bytes_.size(); }

private:
    void wipe() noexcept {
        volatile std::uint8_t* p = bytes_.data();
        for (std::size_t i = 0; i < bytes_.size(); ++i) {
            p[i] = 0;
        }
    }
    std::vector<std::uint8_t> bytes_;
};

std::string text(JNIEnv* env, jstring source, std::size_t maximum,
                 bool empty = false) {
    require(source != nullptr, "text");
    const jsize chars = env->GetStringLength(source);
    require((empty || chars > 0) && static_cast<std::size_t>(chars) <= maximum,
            "text-size");
    const jchar* raw = env->GetStringChars(source, nullptr);
    require_java(env, raw);
    bool has_zero = false;
    for (jsize i = 0; i < chars; ++i) {
        has_zero = has_zero || raw[i] == 0;
    }
    env->ReleaseStringChars(source, raw);
    require(!has_zero, "text-nul");
    const jsize length = env->GetStringUTFLength(source);
    require(static_cast<std::size_t>(length) <= maximum, "text-size");
    std::string result(static_cast<std::size_t>(length) + 1U, '\0');
    env->GetStringUTFRegion(source, 0, chars, result.data());
    if (env->ExceptionCheck()) {
        throw JavaException{};
    }
    result.resize(static_cast<std::size_t>(length));
    return result;
}

jbyteArray byte_array(JNIEnv* env, const void* data, std::size_t size) {
    require(size <= static_cast<std::size_t>(std::numeric_limits<jsize>::max()),
            "output-size");
    jbyteArray result = env->NewByteArray(static_cast<jsize>(size));
    require_java(env, result);
    env->SetByteArrayRegion(result, 0, static_cast<jsize>(size),
                            static_cast<const jbyte*>(data));
    if (env->ExceptionCheck()) {
        env->DeleteLocalRef(result);
        throw JavaException{};
    }
    return result;
}

// Retain every copied Java buffer until the complete result is published.
// If a later JNI allocation fails, clear earlier plaintext copies as well.
class JavaTransfers final {
public:
    explicit JavaTransfers(JNIEnv* env) : env_(env) {}
    JavaTransfers(const JavaTransfers&) = delete;
    JavaTransfers& operator=(const JavaTransfers&) = delete;
    ~JavaTransfers() {
        jthrowable pending = env_->ExceptionOccurred();
        if (pending != nullptr) {
            env_->ExceptionClear();
        }
        const std::array<jbyte, 4096> zeros{};
        for (std::size_t i = 0; i < count_; ++i) {
            if (!published_) {
                for (jsize offset = 0; offset < sizes_[i];) {
                    const jsize length =
                        sizes_[i] - offset < 4096 ? sizes_[i] - offset : 4096;
                    env_->SetByteArrayRegion(arrays_[i], offset, length,
                                             zeros.data());
                    if (env_->ExceptionCheck()) {
                        env_->ExceptionClear();
                        break;
                    }
                    offset += length;
                }
            }
            env_->DeleteLocalRef(arrays_[i]);
        }
        if (pending != nullptr) {
            env_->Throw(pending);
            env_->DeleteLocalRef(pending);
        }
    }

    jbyteArray copy(const void* bytes, std::size_t size) {
        require(count_ < arrays_.size() && size <= kMaxDocument, "output-size");
        const auto length = static_cast<jsize>(size);
        jbyteArray result = env_->NewByteArray(length);
        require_java(env_, result);
        arrays_[count_] = result;
        sizes_[count_++] = length;
        env_->SetByteArrayRegion(result, 0, length,
                                 static_cast<const jbyte*>(bytes));
        if (env_->ExceptionCheck()) {
            throw JavaException{};
        }
        return result;
    }
    void publish() noexcept { published_ = true; }

private:
    JNIEnv* env_;
    std::array<jbyteArray, 32> arrays_{};
    std::array<jsize, 32> sizes_{};
    std::size_t count_ = 0;
    bool published_ = false;
};

template <typename T, auto Destroy, std::size_t Index>
struct HandleDelete final {
    void operator()(T* value) const noexcept {
        if (value != nullptr) {
            Destroy(value);
            --g_owners[Index];
        }
    }
};

using Runtime =
    std::unique_ptr<yume_runtime,
                    HandleDelete<yume_runtime, yume_runtime_destroy, 0>>;
using Config =
    std::unique_ptr<yume_config,
                    HandleDelete<yume_config, yume_config_destroy, 1>>;
using Endpoint =
    std::unique_ptr<yume_endpoint,
                    HandleDelete<yume_endpoint, yume_endpoint_destroy, 2>>;
using Kit =
    std::unique_ptr<yume_kit, HandleDelete<yume_kit, yume_kit_destroy, 3>>;
using Stream =
    std::unique_ptr<yume_stream,
                    HandleDelete<yume_stream, yume_stream_destroy, 5>>;
using Packet =
    std::unique_ptr<yume_packet,
                    HandleDelete<yume_packet, yume_packet_destroy, 6>>;

Runtime make_runtime(const char* directory = nullptr) {
    yume_runtime_options options{};
    options.struct_size = sizeof(options);
    options.abi_version = YUME_ABI_VERSION;
    options.config_base_dir = directory;
    yume_runtime* raw = nullptr;
    require_ok(yume_runtime_create(&options, &raw), "runtime-create");
    ++g_owners[0];
    return Runtime(raw);
}

Config parse(yume_runtime* runtime, const Bytes& document) {
    yume_config* raw = nullptr;
    require_ok(
        yume_config_parse_json(runtime, document.data(), document.size(), &raw),
        "config-parse");
    ++g_owners[1];
    return Config(raw);
}

class AttachedEnv final {
public:
    AttachedEnv() noexcept {
        if (g_vm == nullptr) {
            return;
        }
        const jint result =
            g_vm->GetEnv(reinterpret_cast<void**>(&env_), JNI_VERSION_1_6);
        if (result == JNI_EDETACHED) {
            attached_ = g_vm->AttachCurrentThread(&env_, nullptr) == JNI_OK;
            if (!attached_) {
                env_ = nullptr;
            }
        } else if (result != JNI_OK) {
            env_ = nullptr;
        }
    }
    ~AttachedEnv() {
        if (attached_) {
            g_vm->DetachCurrentThread();
        }
    }
    JNIEnv* get() const noexcept { return env_; }

private:
    JNIEnv* env_ = nullptr;
    bool attached_ = false;
};

struct Session final {
    Runtime runtime;
    Config config;
    Endpoint endpoint;
    jobject binder = nullptr;
    jmethodID bind = nullptr;

    ~Session() {
        // The callback is borrowed by native workers until endpoint destroy
        // drains them.
        endpoint.reset();
        config.reset();
        runtime.reset();
        if (binder != nullptr) {
            AttachedEnv attached;
            if (JNIEnv* env = attached.get()) {
                env->DeleteGlobalRef(binder);
                --g_owners[4];
            }
        }
    }
};

int bind_socket(std::uintptr_t socket, void* user) noexcept {
    if (socket >
        static_cast<std::uintptr_t>(std::numeric_limits<jint>::max())) {
        return 0;
    }
    auto* session = static_cast<Session*>(user);
    AttachedEnv attached;
    JNIEnv* env = attached.get();
    if (env == nullptr) {
        return 0;
    }
    const jboolean accepted = env->CallBooleanMethod(
        session->binder, session->bind, static_cast<jint>(socket));
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return 0;
    }
    return accepted == JNI_TRUE ? 1 : 0;
}

Session& session(jlong handle) {
    require(handle != 0, "handle");
    return *reinterpret_cast<Session*>(static_cast<std::uintptr_t>(handle));
}

yume_open_options open_options(const std::string& service, jint kind,
                               jint destination_kind,
                               const std::string& destination, jint port) {
    require(port >= 0 && port <= 65535, "destination-port");
    require(destination_kind >= YUME_DESTINATION_NONE &&
                destination_kind <= YUME_DESTINATION_IPV6,
            "destination-kind");
    require(destination_kind != YUME_DESTINATION_NONE ||
                (destination.empty() && port == 0),
            "destination");
    yume_open_options options{};
    options.struct_size = sizeof(options);
    options.abi_version = YUME_ABI_VERSION;
    options.service = {service.data(), service.size()};
    options.kind = static_cast<std::uint32_t>(kind);
    options.destination.struct_size = sizeof(options.destination);
    options.destination.abi_version = YUME_ABI_VERSION;
    options.destination.kind = static_cast<std::uint32_t>(destination_kind);
    // libyume refuses a NONE destination whose host view has any pointer,
    // and an empty std::string's data() is not null.
    if (destination_kind != YUME_DESTINATION_NONE) {
        options.destination.host = {destination.data(), destination.size()};
    }
    options.destination.port = static_cast<std::uint16_t>(port);
    return options;
}

class Deadline final {
public:
    Deadline()
        : end_(std::chrono::steady_clock::now() +
               std::chrono::milliseconds(kIoTimeoutMs)) {}
    std::uint32_t remaining() const {
        const auto left = std::chrono::duration_cast<std::chrono::milliseconds>(
                              end_ - std::chrono::steady_clock::now())
                              .count();
        if (left <= 0) {
            throw Failure{YUME_STATUS_TIMEOUT, "echo-deadline"};
        }
        return static_cast<std::uint32_t>(left);
    }

private:
    std::chrono::steady_clock::time_point end_;
};

void require_server(const yume_peer_identity& peer) {
    require_ok(peer.authenticated == 1U && peer.role == YUME_ROLE_SERVER
                   ? YUME_STATUS_OK
                   : YUME_STATUS_PERMISSION_DENIED,
               "peer-authentication");
}

jobject echo_result(JNIEnv* env, const Bytes& bytes,
                    const yume_peer_identity& peer) {
    require_server(peer);
    JavaTransfers transfers(env);
    const jbyteArray data = transfers.copy(bytes.data(), bytes.size());
    const jbyteArray fingerprint =
        transfers.copy(peer.composite_fingerprint_sha256, 32U);
    LocalRef<jclass> type(
        env,
        env->FindClass("com/fixcraft/ito/transport/nativebridge/NativeEcho"));
    jmethodID constructor = env->GetMethodID(type.get(), "<init>", "([B[B)V");
    require_java(env, constructor);
    jobject result = env->NewObject(type.get(), constructor, data, fingerprint);
    require_java(env, result);
    transfers.publish();
    return result;
}

}  // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    g_vm = vm;
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_fixcraft_ito_transport_nativebridge_ItoNative_openKit(
    JNIEnv* env, jobject, jbyteArray sealed, jbyteArray code) {
    return guarded(env, [&]() -> jobjectArray {
        Bytes input(env, sealed, kMaxSealed);
        Bytes secret(env, code, 128U);
        auto runtime = make_runtime();
        yume_kit* raw = nullptr;
        require_ok(yume_kit_open(runtime.get(), input.data(), input.size(),
                                 reinterpret_cast<const char*>(secret.data()),
                                 secret.size(), &raw),
                   "kit-open");
        ++g_owners[3];
        Kit kit(raw);
        const std::size_t count = yume_kit_file_count(kit.get());
        require(count > 0U && count <= 32U, "kit-count");
        JavaTransfers transfers(env);
        LocalRef<jclass> type(
            env, env->FindClass(
                     "com/fixcraft/ito/transport/nativebridge/NativeKitFile"));
        jmethodID constructor =
            env->GetMethodID(type.get(), "<init>", "(Ljava/lang/String;[BZ)V");
        require_java(env, constructor);
        LocalRef<jobjectArray> output(
            env, env->NewObjectArray(static_cast<jsize>(count), type.get(),
                                     nullptr));
        for (std::size_t i = 0; i < count; ++i) {
            yume_kit_file file{};
            file.struct_size = sizeof(file);
            file.abi_version = YUME_ABI_VERSION;
            require_ok(yume_kit_get_file(kit.get(), i, &file, sizeof(file)),
                       "kit-file");
            require(file.path.size > 0U && file.path.size <= 64U &&
                        file.size <= kMaxDocument,
                    "kit-file-size");
            const std::string path(file.path.data, file.path.size);
            LocalRef<jstring> name(env, env->NewStringUTF(path.c_str()));
            const jbyteArray bytes = transfers.copy(file.data, file.size);
            LocalRef<jobject> value(
                env,
                env->NewObject(type.get(), constructor, name.get(), bytes,
                               file.executable != 0U ? JNI_TRUE : JNI_FALSE));
            env->SetObjectArrayElement(output.get(), static_cast<jsize>(i),
                                       value.get());
            if (env->ExceptionCheck()) {
                throw JavaException{};
            }
        }
        jobjectArray result =
            static_cast<jobjectArray>(env->NewLocalRef(output.get()));
        require_java(env, result);
        transfers.publish();
        return result;
    });
}

extern "C" JNIEXPORT jint JNICALL
Java_com_fixcraft_ito_transport_nativebridge_ItoNative_checkConfig(
    JNIEnv* env, jobject, jbyteArray document) {
    return guarded(env, [&]() -> jint {
        Bytes bytes(env, document, kMaxDocument);
        auto runtime = make_runtime();
        auto config = parse(runtime.get(), bytes);
        return static_cast<jint>(yume_config_role(config.get()));
    });
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_fixcraft_ito_transport_nativebridge_ItoNative_validate(
    JNIEnv* env, jobject, jbyteArray document, jstring base_dir) {
    return guarded(env, [&]() -> jbyteArray {
        Bytes bytes(env, document, kMaxDocument);
        const auto directory = text(env, base_dir, 4096U);
        auto runtime = make_runtime(directory.c_str());
        auto config = parse(runtime.get(), bytes);
        std::array<std::uint8_t, 32> fingerprint{};
        require_ok(yume_config_validate_client_credentials(
                       config.get(), fingerprint.data(), fingerprint.size()),
                   "credentials-validate");
        return byte_array(env, fingerprint.data(), fingerprint.size());
    });
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_fixcraft_ito_transport_nativebridge_ItoNative_create(
    JNIEnv* env, jobject, jbyteArray document, jstring base_dir,
    jobject binder) {
    return guarded(env, [&]() -> jlong {
        require(binder != nullptr, "socket-binder");
        Bytes bytes(env, document, kMaxDocument);
        const auto directory = text(env, base_dir, 4096U);
        auto owner = std::make_unique<Session>();
        owner->runtime = make_runtime(directory.c_str());
        owner->config = parse(owner->runtime.get(), bytes);
        std::array<std::uint8_t, 32> fingerprint{};
        require_ok(
            yume_config_validate_client_credentials(
                owner->config.get(), fingerprint.data(), fingerprint.size()),
            "credentials-validate");
        LocalRef<jclass> type(env, env->GetObjectClass(binder));
        owner->bind = env->GetMethodID(type.get(), "bind", "(I)Z");
        require_java(env, owner->bind);
        owner->binder = env->NewGlobalRef(binder);
        require_java(env, owner->binder);
        ++g_owners[4];
        yume_endpoint* raw = nullptr;
        require_ok(yume_endpoint_create(owner->runtime.get(),
                                        owner->config.get(), &raw),
                   "endpoint-create");
        ++g_owners[2];
        owner->endpoint.reset(raw);
        require_ok(yume_endpoint_set_socket_protector(owner->endpoint.get(),
                                                      bind_socket, owner.get()),
                   "socket-binder-set");
        return static_cast<jlong>(
            reinterpret_cast<std::uintptr_t>(owner.release()));
    });
}

extern "C" JNIEXPORT jint JNICALL
Java_com_fixcraft_ito_transport_nativebridge_ItoNative_start(JNIEnv* env,
                                                             jobject,
                                                             jlong handle,
                                                             jint timeout_ms) {
    return guarded(env, [&]() -> jint {
        require(timeout_ms > 0 && timeout_ms <= 300000, "start-deadline");
        return yume_endpoint_start(session(handle).endpoint.get(),
                                   static_cast<std::uint32_t>(timeout_ms));
    });
}

extern "C" JNIEXPORT jint JNICALL
Java_com_fixcraft_ito_transport_nativebridge_ItoNative_stop(JNIEnv* env,
                                                            jobject,
                                                            jlong handle) {
    return guarded(env, [&]() -> jint {
        return yume_endpoint_stop(session(handle).endpoint.get(), 0);
    });
}

extern "C" JNIEXPORT void JNICALL
Java_com_fixcraft_ito_transport_nativebridge_ItoNative_destroy(JNIEnv* env,
                                                               jobject,
                                                               jlong handle) {
    guarded(env, [&]() { delete &session(handle); });
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_fixcraft_ito_transport_nativebridge_ItoNative_status(JNIEnv* env,
                                                              jobject,
                                                              jlong handle) {
    return guarded(env, [&]() -> jobject {
        yume_endpoint_status status{};
        status.struct_size = sizeof(status);
        status.abi_version = YUME_ABI_VERSION;
        require_ok(yume_endpoint_get_status(session(handle).endpoint.get(),
                                            &status, sizeof(status)),
                   "endpoint-status");
        const jlong fields[] = {
            status.state,
            status.session,
            status.last_failure,
            static_cast<jlong>(status.sessions),
            static_cast<jlong>(status.payload_bytes_sent),
            static_cast<jlong>(status.payload_bytes_received),
            static_cast<jlong>(status.record_bytes_sent),
            static_cast<jlong>(status.record_bytes_received),
            static_cast<jlong>(status.connected_ms),
            static_cast<jlong>(status.retry_ms),
            status.failed_attempts};
        LocalRef<jlongArray> values(env, env->NewLongArray(11));
        env->SetLongArrayRegion(values.get(), 0, 11, fields);
        if (env->ExceptionCheck()) {
            throw JavaException{};
        }
        LocalRef<jbyteArray> fingerprint(
            env, byte_array(env, status.peer_fingerprint_sha256, 32U));
        LocalRef<jclass> type(
            env, env->FindClass(
                     "com/fixcraft/ito/transport/nativebridge/NativeStatus"));
        jmethodID constructor =
            env->GetMethodID(type.get(), "<init>", "([J[B)V");
        require_java(env, constructor);
        jobject result = env->NewObject(type.get(), constructor, values.get(),
                                        fingerprint.get());
        require_java(env, result);
        return result;
    });
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_fixcraft_ito_transport_nativebridge_ItoNative_streamEcho(
    JNIEnv* env, jobject, jlong handle, jstring service_name,
    jint destination_kind, jstring destination_name, jint port,
    jbyteArray payload) {
    return guarded(env, [&]() -> jobject {
        Bytes input(env, payload, 65536U);
        const auto service = text(env, service_name, YUME_MAX_SERVICE_NAME);
        const auto destination = text(env, destination_name, 253U, true);
        const auto options = open_options(service, YUME_SERVICE_BYTE_STREAM,
                                          destination_kind, destination, port);
        Deadline deadline;
        yume_stream* raw = nullptr;
        require_ok(
            yume_endpoint_open_stream(session(handle).endpoint.get(), &options,
                                      deadline.remaining(), &raw),
            "stream-open");
        ++g_owners[5];
        Stream stream(raw);
        yume_peer_identity peer{};
        peer.struct_size = sizeof(peer);
        peer.abi_version = YUME_ABI_VERSION;
        require_ok(
            yume_stream_get_peer_identity(stream.get(), &peer, sizeof(peer)),
            "stream-peer");
        require_server(peer);
        std::size_t written = 0;
        require_ok(yume_stream_write(stream.get(), input.data(), input.size(),
                                     &written, deadline.remaining()),
                   "stream-write");
        require_ok(
            written == input.size() ? YUME_STATUS_OK : YUME_STATUS_IO_ERROR,
            "stream-write-count");
        Bytes output(input.size());
        std::size_t received = 0;
        while (received < output.size()) {
            std::size_t count = 0;
            require_ok(yume_stream_read(stream.get(), output.data() + received,
                                        output.size() - received, &count,
                                        deadline.remaining()),
                       "stream-read");
            require_ok(count > 0U ? YUME_STATUS_OK : YUME_STATUS_IO_ERROR,
                       "stream-read-count");
            received += count;
        }
        return echo_result(env, output, peer);
    });
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_fixcraft_ito_transport_nativebridge_ItoNative_packetEcho(
    JNIEnv* env, jobject, jlong handle, jstring service_name,
    jint destination_kind, jstring destination_name, jint port,
    jbyteArray payload) {
    return guarded(env, [&]() -> jobject {
        Bytes input(env, payload, 1024U);
        const auto service = text(env, service_name, YUME_MAX_SERVICE_NAME);
        const auto destination = text(env, destination_name, 253U, true);
        const auto options = open_options(service, YUME_SERVICE_PACKET,
                                          destination_kind, destination, port);
        Deadline deadline;
        yume_packet* raw = nullptr;
        require_ok(
            yume_endpoint_open_packet(session(handle).endpoint.get(), &options,
                                      deadline.remaining(), &raw),
            "packet-open");
        ++g_owners[6];
        Packet packet(raw);
        yume_peer_identity peer{};
        peer.struct_size = sizeof(peer);
        peer.abi_version = YUME_ABI_VERSION;
        require_ok(
            yume_packet_get_peer_identity(packet.get(), &peer, sizeof(peer)),
            "packet-peer");
        require_server(peer);
        const yume_packet_view view{input.data(), input.size()};
        std::size_t written = 0;
        require_ok(yume_packet_write_batch(packet.get(), &view, 1U, &written,
                                           deadline.remaining()),
                   "packet-write");
        require_ok(written == 1U ? YUME_STATUS_OK : YUME_STATUS_IO_ERROR,
                   "packet-write-count");
        Bytes output(input.size());
        yume_packet_slot slot{};
        std::size_t received = 0;
        std::size_t required = 0;
        require_ok(yume_packet_read_batch(packet.get(), output.data(),
                                          output.size(), &slot, 1U, &received,
                                          &required, deadline.remaining()),
                   "packet-read");
        require_ok(
            received == 1U && slot.offset == 0U && slot.size == input.size()
                ? YUME_STATUS_OK
                : YUME_STATUS_IO_ERROR,
            "packet-read-count");
        return echo_result(env, output, peer);
    });
}

namespace {

// A named-service stream lent to the call core for one call attempt.
struct CallStream final {
    Stream stream;
    std::array<std::uint8_t, 32> fingerprint{};
};

CallStream& call_stream_owner(jlong handle) {
    require(handle != 0, "call-stream-handle");
    return *reinterpret_cast<CallStream*>(static_cast<std::uintptr_t>(handle));
}

}  // namespace

yume_stream* ito::jni::call_stream(jlong handle) noexcept {
    if (handle == 0) return nullptr;
    return reinterpret_cast<CallStream*>(static_cast<std::uintptr_t>(handle))
        ->stream.get();
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_fixcraft_ito_transport_nativebridge_ItoNative_openCallStream(
    JNIEnv* env, jobject, jlong handle, jstring service_name) {
    return guarded(env, [&]() -> jlong {
        const auto service = text(env, service_name, YUME_MAX_SERVICE_NAME);
        const auto options = open_options(service, YUME_SERVICE_BYTE_STREAM,
                                          YUME_DESTINATION_NONE, "", 0);
        yume_stream* raw = nullptr;
        require_ok(yume_endpoint_open_stream(session(handle).endpoint.get(),
                                             &options, 10'000U, &raw),
                   "call-stream-open");
        ++g_owners[5];
        // Owned before anything else can throw.
        Stream stream(raw);
        auto owner = std::make_unique<CallStream>();
        owner->stream = std::move(stream);
        yume_peer_identity peer{};
        peer.struct_size = sizeof(peer);
        peer.abi_version = YUME_ABI_VERSION;
        require_ok(yume_stream_get_peer_identity(owner->stream.get(), &peer,
                                                 sizeof(peer)),
                   "call-stream-peer");
        require_server(peer);
        // The call's relay authority is this stream's verified server and
        // exact service, never a configured label.
        require(std::string(peer.service) == service, "call-stream-service");
        std::copy(std::begin(peer.composite_fingerprint_sha256),
                  std::end(peer.composite_fingerprint_sha256),
                  owner->fingerprint.begin());
        return static_cast<jlong>(
            reinterpret_cast<std::uintptr_t>(owner.release()));
    });
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_fixcraft_ito_transport_nativebridge_ItoNative_callStreamFingerprint(
    JNIEnv* env, jobject, jlong handle) {
    return guarded(env, [&]() -> jbyteArray {
        const auto& fingerprint = call_stream_owner(handle).fingerprint;
        jbyteArray result = env->NewByteArray(32);
        require_java(env, result);
        env->SetByteArrayRegion(
            result, 0, 32, reinterpret_cast<const jbyte*>(fingerprint.data()));
        if (env->ExceptionCheck()) {
            env->DeleteLocalRef(result);
            throw JavaException{};
        }
        return result;
    });
}

extern "C" JNIEXPORT void JNICALL
Java_com_fixcraft_ito_transport_nativebridge_ItoNative_closeCallStream(
    JNIEnv* env, jobject, jlong handle) {
    guarded(env, [&]() {
        std::unique_ptr<CallStream> owner(&call_stream_owner(handle));
        // Try to drain accepted writes and half-close within five seconds.
        // Close is immediate even if shutdown fails or times out, so pending
        // writes can be aborted.
        (void)yume_stream_shutdown_write(owner->stream.get(), 5000U);
        (void)yume_stream_close(owner->stream.get(), 0U);
    });
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_fixcraft_ito_transport_nativebridge_ItoNative_liveHandles(JNIEnv* env,
                                                                   jobject) {
    return guarded(env, [&]() -> jlongArray {
        jlong values[7]{};
        for (std::size_t i = 0; i < 7U; ++i) {
            values[i] = g_owners[i].load();
        }
        jlongArray result = env->NewLongArray(7);
        require_java(env, result);
        env->SetLongArrayRegion(result, 0, 7, values);
        if (env->ExceptionCheck()) {
            env->DeleteLocalRef(result);
            throw JavaException{};
        }
        return result;
    });
}
