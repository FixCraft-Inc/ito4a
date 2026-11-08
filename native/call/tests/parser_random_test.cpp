/* Copyright (C) 2026 FixCraft Inc. AGPL-3.0-or-later. */

// A bounded, deterministic random pass over every parser the core exposes
// to peer or user input: byte flips, truncations, extensions and random
// bytes seeded from the fixed vectors. Each parse must stay in bounds (the
// sanitizers check that) and anything accepted must re-encode to exactly
// the bytes that were parsed. This is a smoke pass, not a fuzz campaign.

#include <cstdio>
#include <random>
#include <string>
#include <vector>

#include "check.hpp"
#include "codec.hpp"
#include "store.hpp"

using namespace ito::call;

namespace {

using Bytes = std::vector<Byte>;
constexpr int kRounds = 20'000;

Bytes mutate(const Bytes& seed, std::mt19937_64& random) {
    Bytes out = seed;
    switch (random() % 5U) {
        case 0:
            if (!out.empty())
                out[random() % out.size()] ^=
                    static_cast<Byte>(1U << (random() % 8U));
            break;
        case 1:
            out.resize(out.empty() ? 0 : random() % out.size());
            break;
        case 2:
            for (std::size_t count = random() % 8U; count > 0; --count) {
                out.push_back(static_cast<Byte>(random()));
            }
            break;
        case 3:
            if (!out.empty())
                out[random() % out.size()] = static_cast<Byte>(random());
            break;
        default:
            out.resize(random() % 600U);
            for (Byte& byte : out) byte = static_cast<Byte>(random());
            break;
    }
    return out;
}

void invitations(const Bytes& seed, std::mt19937_64& random, int& accepted) {
    for (int round = 0; round < kRounds; ++round) {
        const Bytes input = mutate(seed, random);
        Invitation invitation;
        if (parse_invitation(input, invitation) != CodecStatus::Ok) continue;
        ++accepted;
        SecretBytes body = encode_invitation_body(invitation);
        body.append(invitation.signature);
        CHECK(std::equal(body.view().begin(), body.view().end(), input.begin(),
                         input.end()));
    }
}

void contact_codes(const Bytes& seed, std::mt19937_64& random, int& accepted) {
    for (int round = 0; round < kRounds; ++round) {
        const Bytes input = mutate(seed, random);
        const std::string code(input.begin(), input.end());
        Key key{};
        if (parse_contact_code(code, key) != CodecStatus::Ok) continue;
        ++accepted;
        CHECK(format_contact_code(key) == code);
    }
}

void relay_replies(const std::vector<Bytes>& seeds, std::mt19937_64& random,
                   int& accepted) {
    for (int round = 0; round < kRounds; ++round) {
        const Bytes input = mutate(seeds[random() % seeds.size()], random);
        RelayReply reply;
        std::size_t consumed = 0;
        if (parse_relay_reply(input, reply, consumed) !=
            ParseProgress::Complete) {
            continue;
        }
        ++accepted;
        CHECK(consumed >= 5U && consumed <= input.size());
    }
}

void frames(const std::vector<Bytes>& seeds, std::mt19937_64& random,
            int& accepted) {
    for (int round = 0; round < kRounds; ++round) {
        const Bytes input = mutate(seeds[random() % seeds.size()], random);
        FrameAssembler assembler;
        std::size_t offset = 0;
        // Random read boundaries.
        while (offset < input.size() && !assembler.malformed()) {
            const std::size_t step =
                1U +
                random() % std::max<std::size_t>(1U, input.size() - offset);
            const std::size_t taken = assembler.feed(ByteView(
                input.data() + offset, std::min(step, input.size() - offset)));
            offset += taken;
            if (const auto frame = assembler.frame()) {
                ++accepted;
                SecretBytes again;
                encode_frame(again, frame->type, frame->sequence,
                             frame->payload);
                CHECK(again.size() ==
                      frame->payload.size() + kFrameHeaderBytes);
                assembler.take();
            } else if (taken == 0U) {
                break;
            }
        }
    }
}

void snapshots(const CryptoContext& crypto, const Bytes& seed,
               std::mt19937_64& random, int& accepted) {
    for (int round = 0; round < kRounds / 4; ++round) {
        const Bytes input = mutate(seed, random);
        StoreState state;
        if (decode_snapshot(crypto, input, state) != Status::Ok) continue;
        ++accepted;
        const SecretBytes again = encode_snapshot(state);
        CHECK(std::equal(again.view().begin(), again.view().end(),
                         input.begin(), input.end()));
    }
}

}  // namespace

int main() {
    const auto v = testing::load_vectors(ITO_CALL_VECTORS);
    const CryptoContext crypto;
    std::mt19937_64 random(0x17C0A11ULL);
    int accepted[5] = {0, 0, 0, 0, 0};
    invitations(v.at("invitation_1"), random, accepted[0]);
    contact_codes(v.at("contact_code_1"), random, accepted[1]);
    relay_replies({v.at("relay_reserved"), v.at("relay_waiting"),
                   v.at("relay_paired"), v.at("relay_refused")},
                  random, accepted[2]);
    frames({v.at("frame_offer"), v.at("frame_data"), v.at("frame_end")}, random,
           accepted[3]);
    snapshots(crypto, v.at("snapshot_1"), random, accepted[4]);
    std::printf(
        "accepted: invitation %d, contact %d, relay %d, frame %d, "
        "snapshot %d\n",
        accepted[0], accepted[1], accepted[2], accepted[3], accepted[4]);
    return 0;
}
