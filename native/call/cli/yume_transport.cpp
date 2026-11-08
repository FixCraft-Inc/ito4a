/* Copyright (C) 2026 FixCraft Inc. AGPL-3.0-or-later. */

#include "yume_transport.hpp"

#include <cstring>
#include <fstream>
#include <iterator>
#include <stdexcept>
#include <vector>

namespace ito::cli {
namespace {

void require(yume_status status, const char* step) {
    if (status != YUME_STATUS_OK) {
        throw std::runtime_error(std::string(step) + " failed: " +
                                 std::to_string(static_cast<int>(status)));
    }
}

}  // namespace

YumeStream::YumeStream(const std::string& config_dir,
                       const std::string& resolver, const std::string& service,
                       std::uint32_t timeout_ms) {
    try {
        yume_runtime_options options{};
        options.struct_size = sizeof(options);
        options.abi_version = YUME_ABI_VERSION;
        options.config_base_dir = config_dir.c_str();
        options.resolver_program =
            resolver.empty() ? nullptr : resolver.c_str();
        require(yume_runtime_create(&options, &runtime_), "runtime");
        std::ifstream input(config_dir + "/yume.json", std::ios::binary);
        const std::vector<char> document(
            (std::istreambuf_iterator<char>(input)),
            std::istreambuf_iterator<char>());
        if (!input.good() && !input.eof())
            throw std::runtime_error("yume.json unreadable");
        yume_config* config = nullptr;
        require(yume_config_parse_json(runtime_, document.data(),
                                       document.size(), &config),
                "configuration");
        const yume_status created =
            yume_endpoint_create(runtime_, config, &endpoint_);
        yume_config_destroy(config);
        require(created, "endpoint");
        require(yume_endpoint_start(endpoint_, timeout_ms), "start");
        yume_open_options open{};
        // The shorter prefix declares a named service with no destination.
        open.struct_size = YUME_OPEN_OPTIONS_MIN_SIZE;
        open.abi_version = YUME_ABI_VERSION;
        open.service = {service.data(), service.size()};
        open.kind = YUME_SERVICE_BYTE_STREAM;
        require(
            yume_endpoint_open_stream(endpoint_, &open, timeout_ms, &stream_),
            "open");
        yume_peer_identity peer{};
        peer.struct_size = sizeof(peer);
        peer.abi_version = YUME_ABI_VERSION;
        require(yume_stream_get_peer_identity(stream_, &peer, sizeof(peer)),
                "identity");
        if (peer.authenticated == 0U)
            throw std::runtime_error("unauthenticated stream");
        const std::size_t service_size =
            ::strnlen(peer.service, sizeof(peer.service));
        if (service_size == 0U || service_size > ITO_MAX_SERVICE_BYTES ||
            std::string(peer.service, service_size) != service) {
            throw std::runtime_error("stream service differs");
        }
        authority_.struct_size = sizeof(authority_);
        std::memcpy(authority_.server_fingerprint,
                    peer.composite_fingerprint_sha256, ITO_KEY_BYTES);
        authority_.service_size = service_size;
        std::memcpy(authority_.service, peer.service, service_size);
    } catch (...) {
        release();
        throw;
    }
}

YumeStream::~YumeStream() {
    release();
}

void YumeStream::release() noexcept {
    if (stream_ != nullptr) {
        // Try to drain accepted writes and half-close within five seconds.
        // Failure or timeout leaves close free to abort pending writes. A
        // nonzero close timeout is not part of libyume's contract.
        (void)yume_stream_shutdown_write(stream_, 5000U);
        (void)yume_stream_close(stream_, 0U);
        yume_stream_destroy(stream_);
        stream_ = nullptr;
    }
    if (endpoint_ != nullptr) {
        (void)yume_endpoint_stop(endpoint_, 0U);
        yume_endpoint_destroy(endpoint_);
        endpoint_ = nullptr;
    }
    if (runtime_ != nullptr) {
        yume_runtime_destroy(runtime_);
        runtime_ = nullptr;
    }
}

ito_transport YumeStream::transport() noexcept {
    ito_transport result{};
    result.struct_size = sizeof(result);
    result.user_data = this;
    result.read = &YumeStream::read;
    result.write = &YumeStream::write;
    return result;
}

int32_t YumeStream::read(void* user_data, uint8_t* out, size_t capacity,
                         size_t* received, uint32_t timeout_ms) {
    auto* self = static_cast<YumeStream*>(user_data);
    *received = 0U;
    switch (
        yume_stream_read(self->stream_, out, capacity, received, timeout_ms)) {
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

int32_t YumeStream::write(void* user_data, const uint8_t* data, size_t size,
                          uint32_t timeout_ms) {
    auto* self = static_cast<YumeStream*>(user_data);
    size_t written = 0U;
    // libyume admits a write all or none.
    switch (
        yume_stream_write(self->stream_, data, size, &written, timeout_ms)) {
        case YUME_STATUS_OK:
            return written == size ? ITO_IO_OK : ITO_IO_FAILED;
        case YUME_STATUS_TIMEOUT:
        case YUME_STATUS_WOULD_BLOCK:
            return ITO_IO_TIMEOUT;
        default:
            return ITO_IO_FAILED;
    }
}

}  // namespace ito::cli
