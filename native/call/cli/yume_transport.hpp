/* Copyright (C) 2026 FixCraft Inc. AGPL-3.0-or-later. */

// The Linux endpoint's byte transport: one named-service stream on a libyume
// client endpoint, through the public C ABI only. The stream's verified
// server fingerprint and service name are the call's relay authority.

#pragma once

#include <ito/call.h>
#include <yume/yume.h>

#include <cstdint>
#include <string>

namespace ito::cli {

class YumeStream final {
public:
    // Starts an endpoint from config_dir/yume.json and opens service.
    // Throws std::runtime_error with the failing step and its status name.
    YumeStream(const std::string& config_dir, const std::string& resolver,
               const std::string& service, std::uint32_t timeout_ms);
    YumeStream(const YumeStream&) = delete;
    YumeStream& operator=(const YumeStream&) = delete;
    // Closes the stream, then stops and destroys the endpoint and runtime.
    ~YumeStream();

    [[nodiscard]] const ito_authority& authority() const noexcept {
        return authority_;
    }
    [[nodiscard]] ito_transport transport() noexcept;

private:
    static int32_t read(void* user_data, uint8_t* out, size_t capacity,
                        size_t* received, uint32_t timeout_ms);
    static int32_t write(void* user_data, const uint8_t* data, size_t size,
                         uint32_t timeout_ms);
    void release() noexcept;

    yume_runtime* runtime_{nullptr};
    yume_endpoint* endpoint_{nullptr};
    yume_stream* stream_{nullptr};
    ito_authority authority_{};
};

}  // namespace ito::cli
