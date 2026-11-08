# Authenticated content channel

Ito content envelope 1 is application DATA inside the existing consented,
authenticated call profile 2. It adds no YUME transport, relay or account
policy. The portable C codec is `native/call/include/ito/content.h`; Android
owns delivery and storage through `ContentSession` and `CallController`.
The existing call core releases DATA only after both contact proofs and
acceptance. The relay continues forwarding opaque TLS ciphertext.

Each DATA payload contains exactly one envelope. All integers below are
unsigned and big endian. The 16-byte header contains ASCII `ITOC`, version
byte 1, a kind byte, two zero reserved bytes, a u32 sequence and a u32 payload
length. The length must equal the complete remaining payload. Unknown kinds,
versions, flags, invalid UTF-8 and oversized/truncated frames are refused.

| Kind | Payload |
| --- | --- |
| 1 HELLO | Exactly `0000bb80000103c0`: u32 rate 48000, u16 channels 1, u16 samples 960 |
| 2 AUDIO | u64 nonnegative capture sample index, then 1–1275 bytes of Opus |
| 3 CHAT | 16-byte application message ID, then 1–4096 bytes of strict UTF-8 |
| 4 ACK | Exactly the 16-byte message ID |

The AUDIO codec checks RFC 6716 TOC duration equals 20 ms before platform
decoding. Full compressed-frame validity remains the Opus decoder's owner.

Text permits tab and newline, refuses other C0, DEL and C1 controls, overlong
encodings, surrogates and invalid Unicode. This is text data, never markup
or executable content. Message IDs are caller-generated random 128-bit values.
The local storage adapter refuses conflicting reuse of an ID for the same
peer and direction. ACK means the peer's storage adapter accepted the message.
It does not establish that a person read it or guarantee survival of device
loss. The current sender exposes admission only, without a delivery indicator.

Each direction starts at sequence zero with exactly one HELLO. Later sequences
must increase by exactly one, without wrapping. Both HELLOs must be admitted
before other content. Failed sends consume no sequence. Audio capture indexes
are increasing multiples of 960; dropped fresh packets may leave gaps.
The content layer adds no cryptography: call TLS and its authenticated ordered
framing own confidentiality, replay prevention and peer identity.

The user starts content negotiation by choosing Start audio on an authenticated
call. A valid peer HELLO may negotiate content without enabling the microphone.
Negotiation has a five-second budget. Once negotiation begins, invalid content
ends the attempt, with no raw-DATA fallback. Profile 2 always parses DATA as
content envelopes, even before local audio starts. The separately selected
profile-1 test-DATA diagnostic never starts audio or creates messages.

Both Android peers must support profile 2. A profile-1 diagnostic invitation
cannot be reused for content: update both endpoints and create a fresh
invitation. Existing communication identity, verified contacts, encrypted local
records and provider enrollment remain independent of that capability change.

Chat is a reusable synchronous channel/storage boundary, with no chat screen,
offline send queue, cloud history, push, automatic reconnect or retransmission.
The caller retains and clears outgoing buffers. Received buffers are cleared
after the local adapter returns. New chat access while locked is refused
without storage or ACK, preserving already active audio. ACK backpressure
does not retry or promise a receipt. [Local content](LOCAL_CONTENT.md) owns retention,
deletion and export. An incoming ACK never changes contact trust.
