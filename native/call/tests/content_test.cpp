/* Copyright (C) 2026 FixCraft Inc. AGPL-3.0-or-later. */
#include <ito/content.h>
#include <algorithm>
#include <array>
#include "check.hpp"

int main() {
    const std::array<uint8_t, 8> hello{0, 0, 187, 128, 0, 1, 3, 192};
    std::array<uint8_t, 8192> output{};
    size_t size = 99;
    CHECK(ito_content_encode(ITO_CONTENT_HELLO, 0, hello.data(), hello.size(),
                             output.data(), output.size(),
                             &size) == ITO_STATUS_OK);
    const auto expected = ito::call::testing::from_hex(
        "49544f430101000000000000000000080000bb80000103c0");
    CHECK(size == expected.size());
    CHECK(std::equal(expected.begin(), expected.end(), output.begin()));
    ito_content_frame frame{};
    CHECK(ito_content_decode(output.data(), size, &frame) == ITO_STATUS_OK);
    CHECK(frame.kind == ITO_CONTENT_HELLO && frame.sequence == 0);
    for (size_t cut = 0; cut < size; ++cut)
        CHECK(ito_content_decode(output.data(), cut, &frame) ==
              ITO_STATUS_MALFORMED);
    output[6] = 1;
    CHECK(ito_content_decode(output.data(), size, &frame) ==
          ITO_STATUS_MALFORMED);
    output[6] = 0;
    output[5] = 99;
    CHECK(ito_content_decode(output.data(), size, &frame) ==
          ITO_STATUS_MALFORMED);
    std::array<uint8_t, 18> text{};
    text[16] = 0xc0;
    text[17] = 0x80;
    CHECK(ito_content_encode(ITO_CONTENT_CHAT, 1, text.data(), text.size(),
                             output.data(), output.size(),
                             &size) == ITO_STATUS_INVALID_ARGUMENT);
    text[16] = 'o';
    text[17] = 'k';
    CHECK(ito_content_encode(ITO_CONTENT_CHAT, 1, text.data(), text.size(),
                             output.data(), output.size(),
                             &size) == ITO_STATUS_OK);
    CHECK(ito_content_decode(output.data(), size, &frame) == ITO_STATUS_OK);
    CHECK(frame.sequence == 1 && frame.payload_size == 18);
    CHECK(ito_content_encode(ITO_CONTENT_ACK, 2, text.data(), 15, output.data(),
                             output.size(),
                             &size) == ITO_STATUS_INVALID_ARGUMENT);
    CHECK(size == 0);
    CHECK(ito_content_encode(ITO_CONTENT_AUDIO, 2, text.data(), 8,
                             output.data(), output.size(),
                             &size) == ITO_STATUS_INVALID_ARGUMENT);
    std::array<uint8_t, 10> audio{};
    audio[8] = 0xf8;  // One CELT 20 ms frame, independent of decoder validity.
    CHECK(ito_content_encode(ITO_CONTENT_AUDIO, 2, audio.data(), audio.size(),
                             output.data(), output.size(),
                             &size) == ITO_STATUS_OK);
    audio[8] = 0xf0;  // A 10 ms packet violates the fixed-duration contract.
    CHECK(ito_content_encode(ITO_CONTENT_AUDIO, 2, audio.data(), audio.size(),
                             output.data(), output.size(),
                             &size) == ITO_STATUS_INVALID_ARGUMENT);
    CHECK(ito_content_encode(ITO_CONTENT_HELLO, 0, hello.data(), hello.size(),
                             output.data(), 23,
                             &size) == ITO_STATUS_INVALID_ARGUMENT);
    return 0;
}
