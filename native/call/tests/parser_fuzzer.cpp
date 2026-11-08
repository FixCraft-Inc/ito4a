/* Copyright (C) 2026 FixCraft Inc. AGPL-3.0-or-later. */

// libFuzzer entry for the core's peer-facing parsers. The first byte selects
// the parser and the rest is its input. Build with ITO_CALL_FUZZERS=ON and
// Clang, which instruments the parsers as well as this file.

#include <cstddef>
#include <cstdint>
#include <string>

#include "codec.hpp"
#include "store.hpp"

using namespace ito::call;

extern "C" int LLVMFuzzerTestOneInput(const std::uint8_t* data,
                                      std::size_t size) {
    if (size == 0U) return 0;
    const ByteView input(data + 1, size - 1U);
    switch (data[0] % 5U) {
        case 0: {
            Invitation invitation;
            (void)parse_invitation(input, invitation);
            break;
        }
        case 1: {
            Key key{};
            (void)parse_contact_code(std::string(input.begin(), input.end()),
                                     key);
            break;
        }
        case 2: {
            RelayReply reply;
            std::size_t consumed = 0U;
            (void)parse_relay_reply(input, reply, consumed);
            break;
        }
        case 3: {
            FrameAssembler assembler;
            std::size_t offset = 0U;
            while (offset < input.size() && !assembler.malformed()) {
                const std::size_t taken = assembler.feed(input.subspan(offset));
                if (assembler.frame()) assembler.take();
                if (taken == 0U) break;
                offset += taken;
            }
            break;
        }
        default: {
            static const CryptoContext crypto;
            StoreState state;
            (void)decode_snapshot(crypto, input, state);
            break;
        }
    }
    return 0;
}
