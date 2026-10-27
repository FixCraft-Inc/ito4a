# Opaque two-party relay, version 1

This is the service contract. [relay/](../relay/README.md) implements it as
`ito-call-relay`, a YUME module that operators run beside yumed. The service
joins two authorized outbound streams and forwards the
[end-to-end channel](CALL_SECURITY.md). It owns no contact directory, app
signing keys, invitation PSKs, media keys or codecs. Running it makes no call
by itself: the app endpoint is a separate, later implementation.

## Transport authorization and ingress

Implement a separate unprivileged service behind yumed's existing named-stream
module UNIX socket. Use a configured service name, such as `ito-call`, without
adding a core special case. yumed authenticates the client and checks its
named-service grant before opening that socket. The service accepts only a
private socket path in an owner-controlled directory, validates
peer credentials and parses the bounded module header:
`yume-module 1 <authenticated-composite-fingerprint> <service>\n`.
Use YUME's inherited listening descriptor 3 composition. The listening socket
belongs to the configured yumed UID and permits only that UID and root to
connect. The service may run under a distinct dynamic UID. Pin its trusted
daemon UID in local service configuration and require SO_PEERCRED to match
that UID or root, never the module's own UID by assumption. Check the listener's
type, ownership and private directory, including when testing without systemd.
The service refuses to run as root. Its configuration is the service name and
the numeric daemon UID, given as command-line options in the unit. A
malformed header, another service name, an untrusted peer or a connection
beyond the service's 1024 streams, which yumed's own module limit already
prevents, closes without a reply, because no client request has been read.
YUME's module contract owns header grammar. For this service, bound the header
to 14 prefix bytes, 64 lowercase fingerprint hex digits, one space, 1..64
service bytes and a newline (144 bytes maximum). Check the canonical YUME
service-name grammar and the additional 64-byte application limit.
The selected name must match exactly. Reject destination-routed streams,
untrusted local peers and malformed headers before allocating a room.

The relay uses this authenticated transport principal for quotas and admission.
Do not accept a client-supplied identity or OAuth bearer as a substitute.
Restricted hosting requires an appropriate yumed service grant, checked
independently for each endpoint. Manual/self-hosted admission uses the same
contract. Provider account policy and provisioning remain outside this service.
Transport disconnect/revocation closes its exact stream. A UI label cannot
grant access. Test refusal with an actually ungranted native client.

## Control framing and rooms

Before pairing, requests/responses are u32 big-endian body length (including
type), u8 type, then exact payload. Reject body lengths outside 1..4096 before
allocation. Unknown types, fields, trailing bytes and wrong-state messages
terminate the connection. There are no optional dialects or textual errors.

| Type | Direction and exact payload |
| --- | --- |
| 1 RESERVE | Client, u16 version=1 + u32 requested lifetime milliseconds (1..600000) |
| 2 RESERVED | Service, room ID 32 + initiator token 32 + responder token 32 + u32 granted lifetime milliseconds |
| 3 JOIN | Client, u16 version=1 + room ID 32 + u8 role (1 initiator, 2 responder) + role token 32 |
| 4 WAITING | Service, no payload, one reply to an admitted JOIN |
| 5 PAIRED | Service, no payload, exactly once per joined stream |
| 6 CANCEL | Client, no payload, valid only before PAIRED |
| 7 REFUSED | Service, one reason byte, terminal |

RESERVE is allowed only as the first request on an authenticated stream.
The service generates the room ID and both independent bearer tokens with a
cryptographic RNG. A failed RNG allocates nothing. RESERVED lasts at most the
requested lifetime. Only that reserving stream, bound to its principal, can
JOIN role 1. Its next request must be JOIN or CANCEL. Role 2 opens another
independently authorized outbound stream and presents its token. It cannot
RESERVE and JOIN a different room on the same connection. Constant-time token
comparison and exact role matching precede slot publication.

Tokens authorize room slots, not contact identities. The service knows those
tokens. They must never be reused as the invitation PSK. The initiator sends
only role-2 token in the confidential signed invitation. One stream per role
is permitted. An admitted JOIN consumes its token permanently, even after
disconnect. Wrong tokens, occupied roles and expired/unknown rooms produce
the same refusal reason and timing class. A canceled, paired or disconnected
room can never be rejoined, recovered or migrated. Restart drops all rooms
and invalidates every token. No durable room store is required.

After RESERVED the reserving stream may wait until the reservation expires
before its JOIN or CANCEL. The module header and a stream's first request
share the five-second parsing budget, and so does any partly received frame.

PAIRING publishes only after both admitted streams have their WAITING reply.
The service writes and flushes PAIRED to each stream before forwarding any raw
bytes to that stream. Each client waits for its own PAIRED before starting TLS.
The service must preserve bytes arriving immediately after a parsed JOIN for
later forwarding, within the queue bound. It cannot parse one read as one
frame or discard bytes following a control message. Control parsing continues
on a stream until PAIRED is published, so CANCEL remains a control frame while
WAITING. When the JOIN that completes a room is parsed, every byte either
stream sent after its last parsed frame becomes the first opaque bytes of
that direction.

After PAIRED, all further bytes in either direction are opaque TLS bytes.
There is no relay control frame, multiplexing, renegotiation or interpretation
of ciphertext. Hangup uses authenticated END inside TLS and then transport
close. CANCEL after PAIRED is just malformed TLS bytes to the peer and cannot
select a new room. Do not attempt to detect END in ciphertext.

REFUSED reasons are 1 invalid/unavailable room, 2 unsupported protocol,
3 quota/capacity, 4 canceled, 5 timeout, 6 invalid request. Authentication
refusal happens at yumed before this protocol. Close after one bounded refusal
write, with no attacker text. Before pairing, a CANCEL, a disconnect or a
refusal of one stream ends its room: every other stream of the room, and the
canceling stream itself, receive reason 4. An expired reservation refuses its
streams with reason 5, and a failed random source refuses RESERVE with
reason 3. Refusal reveals no peer key, account name or
online directory information. Different connection timings still reveal room
activity to its operator.

## Resource and teardown boundaries

Use one serialized room owner and explicit per-stream I/O owners. Parse
before changing room state. Reserve capacity before publishing tokens or
JOIN. Cancellation settles each pending operation once and releases all
reservations, timers and queues before user callbacks. An exception cannot
leave a published half-room or a callback with an expired borrow.

Default hard limits for version 1 are 512 rooms, 1024 admitted streams,
two rooms and four streams per transport principal, 64 KiB queue per direction
per paired room, and 64 MiB total queued bytes across the service. Bound room
metadata to 2 KiB per room, independent of queues. Header/control parsing has
a five-second budget and a 4096-byte control-frame maximum. Pending JOIN has
at most the reservation's 600-second lifetime. Rate-limit each principal to
ten RESERVE and twenty failed JOIN requests in any rolling 60-second window.
Retain each principal's bounded timestamp buckets for 60 seconds after its
last counted request, even after all its streams close. Bound this table to
4096 authenticated principals, with at most 30 retained request timestamps
each. Refuse new principals at capacity rather than evicting an unexpired
bucket. Do not grow a table from unauthenticated IP addresses. Capacity failure
refuses rather than evicting another call. yumed's existing per-module limit
of 1024 streams is an independent upper bound, and other grant/endpoint limits
may be lower.

Apply TCP backpressure when queue credit is exhausted. While a queue has
pending output, five seconds without write progress closes both streams.
Disarm that timer when the queue drains. At the deadline the service tries the
write once more, because the kernel may not yet have reported space that a
slowly draining reader freed. Only a write that still cannot progress closes
the room. Empty queues during consent or
quiet calls are not stalled writes. A paired room expires after 30 minutes or
65 MiB forwarded per direction (TLS/control overhead included), whichever is
first. EOF, native authority loss, timeout, CANCEL before pairing or any
service failure closes both halves. Do not half-close indefinitely. An end of
stream, the 30-minute lifetime and the byte cap first flush bytes already
accepted, within one second, then close both streams. Errors and stalls close
them at once. When a write fails because its peer closed, nothing more is
written to that stream, but what it sent before closing is still forwarded
for at most the stall timeout. A UNIX peer that closes with unread bytes
reports a reset after its last byte, which also ends the room in order. Release
all room state within one second of cancellation plus the transport close
budget, capped at five seconds total. A service crash ends calls. Recovery
requires explicit new invitations, not automatic reassignment.

The service may log bounded counts and fixed reason codes. Tokens, control
bodies, transport credentials, invitation contents and forwarded bytes are
never logs. Avoid stable-principal logs by default. Private operational
aggregation needs its own retention policy. The operator necessarily sees
transport principals, pairing, addresses visible at its transport boundary,
room selectors, timing, sizes and teardown. It can deny, delay or correlate
calls. This contract protects application content and identity proofs using
the independent endpoint channel, without claiming traffic-analysis resistance.

## Implementation acceptance

The synthetic harness must use two real outbound authorized native
streams through yumed, plus a real ungranted client. The service's tests and
harness live in `relay/tests`. Exercise reservation,
wrong/replayed tokens, occupied roles, expired/canceled rooms, partial and
coalesced framing, unknown/oversized requests, quotas, slow readers, queue
exhaustion, reconnects within the rate window, restart, hangup, authority loss
and cancellation races. Verify
exact byte preservation and bounded cleanup. Opaque byte forwarding alone
does not establish contact authentication or working calls. I4b separately
qualifies the endpoint security composition, and I5 owns audio.
