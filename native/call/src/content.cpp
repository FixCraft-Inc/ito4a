/* Copyright (C) 2026 FixCraft Inc. AGPL-3.0-or-later. */
#include <ito/content.h>

#include <array>
#include <cstring>
#include <span>

namespace {
using Bytes = std::span<const uint8_t>;
constexpr std::array<uint8_t, 8> kHello{0, 0, 187, 128, 0, 1, 3, 192};

uint32_t read32(const uint8_t* in) noexcept {
    return (uint32_t{in[0]} << 24U) | (uint32_t{in[1]} << 16U) |
           (uint32_t{in[2]} << 8U) | uint32_t{in[3]};
}
void write32(uint8_t* out, uint32_t value) noexcept {
    for (unsigned i = 0; i < 4U; ++i)
        out[i] = static_cast<uint8_t>(value >> (24U - 8U * i));
}

bool valid_text(Bytes text) noexcept {
    if (text.empty() || text.size() > 4096U) return false;
    for (size_t i = 0; i < text.size();) {
        const uint8_t first = text[i++];
        if (first < 0x80U) {
            if ((first < 32U && first != 9U && first != 10U) || first == 127U)
                return false;
            continue;
        }
        unsigned count = 0;
        uint32_t value = 0;
        uint32_t minimum = 0;
        if (first >= 0xc2U && first <= 0xdfU) {
            count = 1;
            value = first & 0x1fU;
            minimum = 0x80U;
        } else if (first >= 0xe0U && first <= 0xefU) {
            count = 2;
            value = first & 0x0fU;
            minimum = 0x800U;
        } else if (first >= 0xf0U && first <= 0xf4U) {
            count = 3;
            value = first & 7U;
            minimum = 0x10000U;
        } else
            return false;
        if (text.size() - i < count) return false;
        for (unsigned j = 0; j < count; ++j) {
            const uint8_t next = text[i++];
            if ((next & 0xc0U) != 0x80U) return false;
            value = (value << 6U) | (next & 0x3fU);
        }
        if (value < minimum || value > 0x10ffffU ||
            (value >= 0xd800U && value <= 0xdfffU) ||
            (value >= 0x80U && value <= 0x9fU))
            return false;
    }
    return true;
}

bool valid_payload(uint32_t kind, Bytes payload) noexcept {
    switch (kind) {
        case ITO_CONTENT_HELLO:
            return payload.size() == kHello.size() &&
                   std::memcmp(payload.data(), kHello.data(), kHello.size()) ==
                       0;
        case ITO_CONTENT_AUDIO:
            if (payload.size() < 9U || payload.size() > 1283U) return false;
            {
                // RFC 6716 section 3: refuse a packet duration other than 20 ms
                // before invoking the platform decoder. Its full Opus parser
                // still validates frame lengths and compressed contents.
                const auto packet = payload.subspan(8U);
                const unsigned config = packet[0] >> 3U;
                unsigned samples = 0;
                if (config < 12U) {
                    const unsigned mode = config & 3U;
                    samples = mode == 3U ? 2880U : (480U << mode);
                } else if (config < 16U)
                    samples = 480U << (config & 1U);
                else
                    samples = 120U << (config & 3U);
                unsigned frames = 1;
                const unsigned code = packet[0] & 3U;
                if (code == 1U || code == 2U) frames = 2;
                if (code == 3U) {
                    if (packet.size() < 2U) return false;
                    frames = packet[1] & 0x3fU;
                }
                return samples * frames == 960U;
            }
        case ITO_CONTENT_CHAT:
            return payload.size() > 16U &&
                   payload.size() <= ITO_CONTENT_MAX_PAYLOAD &&
                   valid_text(payload.subspan(16U));
        case ITO_CONTENT_ACK:
            return payload.size() == 16U;
        default:
            return false;
    }
}
}  // namespace

extern "C" {
ito_status ito_content_encode(uint32_t kind, uint32_t sequence,
                              const uint8_t* payload, size_t payload_size,
                              uint8_t* out, size_t capacity,
                              size_t* written) noexcept {
    if (written == nullptr) return ITO_STATUS_INVALID_ARGUMENT;
    *written = 0;
    if (payload == nullptr || out == nullptr ||
        payload_size > ITO_CONTENT_MAX_PAYLOAD ||
        capacity < ITO_CONTENT_HEADER_BYTES + payload_size ||
        !valid_payload(kind, Bytes(payload, payload_size)))
        return ITO_STATUS_INVALID_ARGUMENT;
    std::memmove(out + ITO_CONTENT_HEADER_BYTES, payload, payload_size);
    std::memcpy(out, "ITOC\x01", 5U);
    out[5] = static_cast<uint8_t>(kind);
    out[6] = 0;
    out[7] = 0;
    write32(out + 8U, sequence);
    write32(out + 12U, static_cast<uint32_t>(payload_size));
    *written = ITO_CONTENT_HEADER_BYTES + payload_size;
    return ITO_STATUS_OK;
}

ito_status ito_content_decode(const uint8_t* input, size_t size,
                              ito_content_frame* out) noexcept {
    if (out == nullptr) return ITO_STATUS_INVALID_ARGUMENT;
    *out = {};
    if (input == nullptr || size < ITO_CONTENT_HEADER_BYTES ||
        size > ITO_CONTENT_HEADER_BYTES + ITO_CONTENT_MAX_PAYLOAD ||
        std::memcmp(input, "ITOC\x01", 5U) != 0 || input[6] != 0 ||
        input[7] != 0 ||
        read32(input + 12U) != size - ITO_CONTENT_HEADER_BYTES ||
        !valid_payload(input[5], Bytes(input + ITO_CONTENT_HEADER_BYTES,
                                       size - ITO_CONTENT_HEADER_BYTES)))
        return ITO_STATUS_MALFORMED;
    *out = {input[5], read32(input + 8U), input + ITO_CONTENT_HEADER_BYTES,
            size - ITO_CONTENT_HEADER_BYTES};
    return ITO_STATUS_OK;
}
}
