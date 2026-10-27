# Authenticated calling and identity, version 1

This is the implementation contract for Ito's first two-party call data path.
The application currently provides authenticated transport data probes and
provider enrollment. Calling, contacts, this identity store and end-to-end
security are not implemented. The [component decision](SECURITY_COMPONENT.md)
defines the selected native dependency and the bounded evaluation. The
[relay contract](CALL_RELAY.md) defines the separate service.

## Trust and owners

Assume the network, provider directory and forwarding relay can observe,
delay, duplicate, replace or discard traffic. A relay can correlate both
transport grants, room membership, sizes, timing and termination. YUME protects
each outbound client-to-service connection. Its verified server fingerprint
does not authenticate the remote contact. Provider tokens and grants authorize
infrastructure use, never communication identity or contact trust.

Ito's native security owner must use the selected TLS implementation for the
entire end-to-end record layer. It owns TLS, signing-key borrows, transcript
verification and destruction. A bounded codec owns parsing without I/O or
trust mutation. A serialized application call owner owns invitation consumption,
peer policy, consent, deadlines and state. Kotlin owns Android lifecycle and
the Keystore-backed local store. UI observes typed public state. No component
may infer peer trust from an account label, relay response or transport identity.

The goal is authentication of the exact independently verified contact and
confidentiality/integrity of call data against those intermediaries. This
includes keeping stable communication keys out of relay-visible handshakes.
Endpoint compromise, a compromised verification channel, traffic analysis,
availability and an authorized peer recording a call are outside that claim.
The native security owner must never return unauthenticated payload as success.

## Local identity, storage and recovery

One installation owns one random Ed25519 identity seed (32 bytes). OpenSSL's
private default-provider context generates it and derives its 32-byte public
key. The seed is exportable to the native signer while borrowed. A separate
nonexportable Android Keystore AES-256 wrapping key protects the encrypted
identity/contact snapshot in `noBackupFilesDir/ito-identity/`, independently
of manual configurations and provider records. Do not claim that the Ed25519
key itself is hardware-held or nonexportable.

The store must authenticate format version, `ito/identity-storage/1` and the
exact record name as AAD, use platform-generated fresh GCM nonces and the
existing bounded single-file publication pattern. It must authenticate old
state before replacement, preserve corrupt/unreadable ciphertext, and refuse
to create a replacement key while ciphertext exists. An ambiguous publication
failure cannot publish Ready. Files are private regular files, directories
0700, files 0600, without links. Own and clear plaintext buffers on every path,
including canceled signing borrows. JVM/provider/flash erasure is best effort.
Secrets must not enter preferences, logs, Intents, screenshots, saved state,
crash reports or provider records.

The initial policy excludes identity, trust and invitation secrets from cloud
backup and device transfer. It offers no export or recovery password. App data
clearing, reinstall or loss of the wrapping key loses the identity. Recovery
requires a new identity and independent reverification by contacts. Never
silently regenerate an identity over unreadable state or restore trust onto a
new key. Removing a provider or transport configuration preserves identity and
contacts. No multi-device identity, key synchronization or remote recovery is
part of version 1.

Contact records are keyed by the exact 32-byte Ed25519 public key. The display
fingerprint is lowercase hex SHA-256 of ASCII `ito/contact-identity/1`, one
zero byte, then that key (64 hex characters, groupable for comparison). A
contact exchange encodes `ito-contact:1:` followed by unpadded base64url of the
32-byte key. Refuse noncanonical encodings, wrong lengths and unsupported
versions. A name is local display data with no authority.

Trust states are Unverified, Verified and Blocked. Verification requires an
independent authenticated comparison of the complete fingerprint or an
in-person QR exchange. Merely importing a contact leaves it Unverified and
cannot authorize a call. Known-key changes stop pending/active attempts and
require explicit replacement plus independent verification. Preserve the old
key and its blocked/replaced disposition. A signature by a retired key may
explain a change but cannot bypass reverification. Limit the store to 256
contacts and 1 MiB plaintext. Capacity or authentication failure refuses the
mutation without deleting another contact.

## Invitation and route authority

Version 1 supports a manually transferred invitation through an authenticated
and confidential out-of-band channel, such as an in-person QR exchange. An
ordinary public URL, clipboard history, provider message, email or unauthenticated
directory does not establish that property. Later encrypted invitation delivery
needs its own contract. There is no global discovery, unattended ringing or
sleeping-phone reachability claim.

Both contacts must already be Verified. The initiator explicitly selects an
authorized relay service and transport configuration, reserves a room and
creates a fresh random 32-byte invitation ID and independent 32-byte TLS PSK.
The explicit initiation action is the initiator's local consent to this peer,
route and profile. The public TLS PSK identity is that invitation ID. It is never the PSK, a
contact fingerprint, a provider token or a room bearer token. Each endpoint
must have independent transport authorization to the same selected service.
The relay authority is the verified YUME server fingerprint (32 raw bytes)
plus its exact ASCII named service (1..64 bytes). This is an application
binding, not a new YUME service-name limit.

The canonical invitation is the following concatenation. Integers are unsigned
big-endian. Variable strings have a u16 byte length and no terminator. All
fields are mandatory, with no extensions, duplicates or trailing bytes:

| Field | Bytes / constraint |
| --- | --- |
| Domain | ASCII `ito/invitation/1` followed by zero |
| Invitation ID, room ID | 32 each, independently generated |
| Initiator key, responder key | 32 each, ordered by role |
| Relay fingerprint, named service | 32, then u16 length + ASCII bytes |
| Recipient room token | 32, supplied by relay for responder role |
| Issued-at, expires-at | u64 Unix seconds each, lifetime 1..600 seconds |
| Capability offer | u16 value 1, the exact version-1 profile below |
| Invitation PSK | 32, generated locally and never sent to the relay |
| Initiator signature | 64, Ed25519 over every preceding byte |

The invitation hash is SHA-256 of the complete signed invitation. Validate
lengths (at most 512 total bytes), domain, time bounds, expected keys, signature,
Verified status and exact service authority before consuming it or starting
TLS. Require `issued_at <= now + 60` and `now < expires_at`, with no expiry
grace, plus `1 <= expires_at - issued_at <= 600`. Check arithmetic before
subtraction/addition. The establishment deadline is capped by remaining invitation lifetime,
using monotonic time. A clock rollback or uncertain restored deadline refuses
the attempt. The relay applies its independent reservation deadline.

An installation retains at most eight pending invitations. Before first JOIN,
atomically mark the invitation ID spent in the authenticated identity store
and remove its persisted PSK, including the complete serialized signed
invitation and every app-owned persisted copy. Retain only its digest and
nonsecret spent metadata. Only the current attempt holds a native secret
borrow. Any failure, cancellation, timeout, process death or disconnect spends
the attempt permanently. No reconnect reuses its PSK or room. A new explicit
call requires a fresh invitation and full handshake. Retain spent IDs until
expiry plus 60 seconds, at most 1024 entries, refusing new work at capacity.
Do not prune against a regressed clock. Startup discards all pending secrets
and refuses corrupt replay state rather than resetting it. Counterfeit invites
must not evict valid state. Remote teardown cannot revoke a peer's saved copy,
so both endpoints enforce these rules independently.

Protected routing remains the default and is the only version-1 call route.
Bind all transport sockets/DNS to the captured underlying Android Network.
Provider authority loss, backgrounding, selected-network loss or native failure
ends the attempt. Preserve the existing no-fallback policy. A future direct
route requires separate user consent before address-disclosing candidate
gathering and a separately qualified contract. Audio preference and Emergency
mode cannot change any identity, cipher, route or grant requirement.

## End-to-end TLS and identity proof

The two outbound streams become one reliable ordered byte channel after relay
JOIN. The initiator is the TLS client and the responder is the TLS server.
Use stock OpenSSL 3.5.9, TLS 1.3 only, external PSK only, PSK-DHE only, group
`X25519MLKEM768` and cipher `TLS_AES_256_GCM_SHA384`. Require the exact external
PSK callback on both peers and verify the negotiated group/cipher after
handshake. Reject missing/wrong identities, hash mismatch, no-DHE exchange,
certificate authentication, other groups and versions. No certificate, trust
store, DNS hostname or provider CA participates in this inner channel.

Use a private `OSSL_LIB_CTX`, explicitly load its default provider and use
`provider=default` for all fetched cryptography. Keep it alive beyond all keys
and contexts. There is no global-provider fallback. The external-PSK session
callback must create a correctly typed TLS-1.3/SHA-384 session on every call,
transferring its ownership to OpenSSL. Callback identity storage outlives the
handshake, including HelloRetryRequest. Disable session caching, issued
tickets (`num_tickets=0`) and early data. Never supply a prior session or accept
a ticket identity. Clear `ALLOW_NO_DHE_KEX` and `PREFER_NO_DHE_KEX`.
`SSL_session_reused()` can be true for external PSKs and cannot by itself
distinguish prohibited ticket resumption. Test the positive gates above.

TLS possession of the invitation secret admits the handshake. It does not
verify a contact. After it completes, both sides derive the 32-byte RFC 9266
`tls-exporter` channel binding with label `EXPORTER-Channel-Binding`, empty
context and `use_context=1`. Neither side sends early application data.

The initiator sends OFFER containing u16 profile 1. The responder accepts only
that value and sends SELECT containing u16 profile 1. No capability is inferred
from an absent field. The context hash is SHA-256 of ASCII `ito/call-context/1`,
zero, invitation hash (32), room ID (32), initiator key (32), responder key (32),
relay fingerprint (32), u16 service length + exact service bytes, exact OFFER
body (2) and exact SELECT body (2), in that order.

Each endpoint signs ASCII `ito/call-auth/1`, zero, role byte (1 initiator,
2 responder), exporter (32) and context hash (32), using its expected Ed25519
identity. The initiator sends AUTH first. The responder verifies it against
the pinned initiator key, then requests explicit local acceptance of the
verified contact and offered profile before sending its own AUTH. The
initiator verifies that AUTH against the pinned responder key. Any failure
terminates the attempt. PSK possession cannot authorize substitution of either
key. The TLS exporter prevents forwarding a valid proof into another TLS
connection, even with the same invitation.

Both sides then send ACCEPT whose 32-byte body is SHA-256 of the context hash,
initiator signature and responder signature in that order. The initiator sends
first, the responder verifies before replying. An endpoint publishes
MediaAuthorized only after it has verified both proofs, local consent, the
peer ACCEPT, current transport authority and the existing call policy. The
responder may publish after its reply is successfully queued, retaining the
normal possibility that the peer disconnects before receiving it. Media and
microphone capture remain prohibited before that state. TLS success alone
cannot publish it.

Profile 1 means this exact security/authentication protocol, one peer, the
protected ordered stream, and bounded opaque test payloads for the later
endpoint gate. It does not select a codec, packet duration or audio mode.
Adding audio capabilities requires the I5 contract and a new explicit profile,
with both controlled readers/writers updated. Unknown or unsupported values
fail without a fallback. Product, TLS, call protocol, profile, YTP, relay module
and native ABI versions are independent.

## Frames, ordering and state

Inside TLS, a frame is u32 body length, u8 type, u64 sequence, then payload.
Body length includes type and sequence (9..16393). TLS record boundaries are
not frame boundaries. Bound the length before allocating. Sequences start at
zero independently per sender, increase exactly by one, never wrap, and
include every frame. Refuse gaps, duplicates, wrong-state types and trailing
payload bytes. A partial frame at EOF is failure. TLS already authenticates
record order and rejects ciphertext replay. Application sequence/state also
rejects peer-originated replay of authenticated application messages.

| Type | Payload and allowed position |
| --- | --- |
| 1 OFFER | Initiator only, first frame, exactly u16 profile 1 |
| 2 SELECT | Responder only, first frame, exactly u16 profile 1 |
| 3 AUTH | Exactly 64-byte signature, after OFFER/SELECT, initiator first |
| 4 ACCEPT | Exactly 32-byte confirmation, both AUTHs verified, initiator first |
| 5 DATA | 1..16384 opaque bytes, MediaAuthorized only |
| 6 END | One reason byte, any established TLS state, terminal |

END reasons are 1 canceled, 2 declined, 3 expired, 4 protocol failure,
5 authority ended, 6 resource limit. Unknown values fail. Remote reasons are
authenticated channel statements, not proof of a local cause. Before peer AUTH
verification, END proves only invitation-PSK possession and terminates without
attributing a decline/cancellation to the contact. Pre-TLS errors carry no
authenticated contact status. No human error string drives policy.

Local states are Idle, Preparing, Joined, Handshaking, Authenticating,
AwaitingAcceptance, MediaAuthorized, Closing and Closed. Each transition
rechecks cancellation, transport deadlines and expected identity. Invitation
expiry gates establishment through bilateral ACCEPT. Once MediaAuthorized,
the consumed invitation stays spent and the separate active-call/transport
deadlines apply. Invitation expiry alone does not end an established call.
A terminal error cannot return to an earlier state. Keep expected peer-input
refusal, timeout, cancellation, storage failure and dependency failure typed.
Close exactly once, cancel I/O, join native borrowers, destroy TLS/signing
state, clear PSK/exporter/plaintext buffers and only then close transport
credentials. Contain exceptions at JNI, callbacks and worker entry points.

One active attempt is allowed per installation. TLS handshake has a 10-second
wall budget, 256 KiB total inbound bytes, 64 KiB pending BIO output, and at
most one HelloRetryRequest (a second is a TLS protocol failure). From TLS
completion through bilateral ACCEPT, both endpoints allow one absolute
40-second authentication/consent budget. The responder's local decision uses
at most 30 seconds within it. All establishment budgets are capped by invitation
lifetime and cannot restart on peer input. Permit at most 64 application frames before
MediaAuthorized. Frame parsing buffers at most one 16397-byte frame per
direction. Bound network queues to 64 KiB per direction. Apply backpressure
without unbounded buffering. While output is pending, close after 5 seconds
without write progress. Disarm that timer when the queue drains. Silence or
the bounded local acceptance wait is not a write stall.

## Media key lifetime and security claims

All DATA bytes remain inside the same end-to-end TLS record layer. There is no
exported media key, custom AEAD, SRTP, codec encryption or Kotlin key schedule.
OpenSSL owns traffic keys, nonces, record counters and updates. Request a TLS
KeyUpdate after 16 MiB sent or 10 minutes, whichever comes first. Limit a call
to 30 minutes or 64 MiB application bytes per direction, then close and require
another explicit fresh invitation. Refuse more than eight peer KeyUpdates per
minute. These are application limits, not claims about OpenSSL's maximums.

Fresh hybrid ephemeral key exchange supplies per-attempt forward secrecy
subject to TLS's assumptions and destruction of ephemeral state. The selected
group combines X25519 and ML-KEM-768. Ed25519 contact authentication is classical.
There is no post-quantum authentication or equivalence to YTP's different
composite suite. Compromise of a contact seed alone does not recover earlier
TLS traffic. Compromise of an active process can expose its PSK, plaintext and
traffic keys. TLS KeyUpdate adds no fresh DH entropy and provides no
post-compromise recovery or message-level double ratchet. Fresh handshakes are
required on every new attempt.

An intermediary without the PSK cannot complete the inner handshake or obtain
the encrypted identity proofs. A leaked invitation permits handshake probing
and denial but still cannot replace a pinned contact without its signing key.
The invitation recipient learns its contents. Ordered TCP loss recovery may
stall speech. This design makes no low-bandwidth, audio quality, background
delivery, anonymous-routing or working-call claim.

## Required endpoint gates

Later endpoint integration must execute valid bilateral authentication and
consent, wrong/blocked/unverified contacts, replaced keys, altered invitations,
wrong PSKs, capability/role/room/service/exporter tampering, replay/reorder,
malformed/oversized/truncated frames, stale invitations across restart,
cancel races, expired/revoked transport authority, storage failures and bounded
resource exhaustion. Verify the relay cannot obtain the PSK, signatures in
plaintext, traffic keys or DATA, and verify cleanup under failure injection.
The component evaluation covers only the properties its case list names.
It does not qualify these application state, parser, storage or lifecycle gates.
