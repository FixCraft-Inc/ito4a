# Architecture and security

## Application owners

The Android app uses Kotlin, Jetpack Compose and Material 3, with one blue
palette on every Android version (light or dark, following the system setting)
and Material Symbols icons. Its screens read
a lifecycle-aware ViewModel. Chats, profiles and calls open full screen above
the Chats, Service and Settings tabs, and saved navigation holds only route
names and public contact codes. Preferences are serialized into a single
DataStore entry and updated transactionally. They contain routing/audio
choices and at most eight named HTTPS service origins. Credentials, paths,
queries and fragments are refused in those origins. An origin is a saved
address, not proof of a provider's identity or an authorization grant.

Missing preferences use protected routing and adaptive audio. Unreadable,
unsupported or invalid saved state is shown as an error and is not rewritten
with defaults. Service removal preserves routing and audio preferences.
Changing audio quality cannot enable a direct path. Settings do not offer
either choice while direct calls and lower-data audio are unimplemented. They
state the fixed behaviour instead: relay service only and standard Opus audio.
When direct calling exists, enabling it must show the IP-disclosure
explanation again. A value stored by an earlier build is not that consent.

The pure call policy rejects media without authenticated peers and end-to-end
encryption. A YUME path additionally requires authenticated YUME; a direct
path additionally requires the user's direct-path choice. The audio adapter
uses the existing protected call's authenticated/consented DATA capability.
Direct call transport remains unimplemented. Policy tests alone do not
establish encryption or transport implementation.

## Authenticated data connections

The process-owned `ConnectionController` owns one `ConfigurationStore` and one
native run, including its call/probe lane and dedicated mailbox worker. Android screens observe public labels and state. Sealed documents
and codes stay process-local. Native validation precedes storage publication.
Import does not select a configuration. Start reacquires the selected encrypted
material and creates private credentials for that run. Native handles close
before credential files. Startup removes abandoned sessions before allowing new
work. Storage or cleanup failure is latched until an explicit successful retry.

The default connection captures the application's system default Android Network,
including an active VPN. Every native transport socket and the cancellable
ten-second DNS request use that same Network. Android waits five seconds for a
DNS server before asking the next, so the bound covers one server failover. Explicit physical-network choices
are available only while an internet-capable application default exists without
a VPN. Refreshing the network list hides physical choices while a VPN applies
to Ito; Connect rechecks the current default and refuses a stale physical
selection rather than replacing it. A change or loss of the captured default, or loss of the
selected network's internet capability, ends the run without fallback. An imported
numeric `endpoint.connect_address` retains its dial authority, while TLS and
YTP still authenticate `endpoint.host` and the configured native identity.
Adapter/control settings become empty because Ito opens public streams/packets
directly. Circuits and upstream proxies are refused by the current app adapter.
Services, suite, limits and credential authority remain the imported values.
No device is attached, so the native endpoint makes one attempt. A failure or
network loss stops that run. While foreground or owned by the opt-in messaging service, the app retries transient failures
through the selected service with the same network rule and a delay capped at
30 seconds. Authentication, authority and storage errors require intervention.
It never substitutes another configuration or a direct destination socket.
Activity backgrounding stops ordinary data connections unless the opt-in
messaging service owns reachability. An already active foreground audio call
retains its connection; rotation retains the process owner. Process restart restores selection and connects when foreground access is available.
Disconnect pauses automatic attempts for this process until Connect.

Document reads admit one importer at a time, check regular-file type and bounded
size, and request provider cancellation at ten seconds or Stop. Native Stop is
independent of a document provider. A broken provider may ignore its platform
cancellation request until its Binder call returns. It cannot publish after the
importer observes cancellation at its commit checkpoint. See the precise
[publication boundary](SECRET_STORAGE.md#failure-channels).

Native channel tests send at most 65536 stream bytes or one 1024-byte packet per
action. The adapter has a five-second total budget. Ito verifies exact echoes,
authenticated server fingerprint and increasing native payload counters, then
publishes hashes/counts only. A Connected label alone is not traffic evidence.
These data probes are not calls or end-to-end media encryption.

[Native integration](NATIVE.md) and [storage](SECRET_STORAGE.md) own the detailed
build, JNI, cancellation, secret and recovery contracts.

## Chats, pairing and calls

Chats is the main destination. `CallsModel` sends book and messenger operations
to I/O and exposes typed refusals through resource strings. Its `ActionGate`
admits one running operation per action, such as Show my code or one
pairing's decision. A repeated tap while it runs shows progress and is
dropped, never queued, and other actions never wait for it. A connection that
is starting or retrying reads as such on Chats, not as a refusal. Only a
person's own operation produces a refusal notice. A mailbox stream the
connection retries by itself does not. `Messenger` owns
identity-2 upgrade, pairing and one native message session per paired contact.
`ChatRepository` owns per-contact history/session encryption, pending sends,
receipts, settings and migration from the old content snapshot. The portable
core owns every message key, trust transition, encoding and replay decision.
[Local content](LOCAL_CONTENT.md) states publication and retention guarantees.

An in-person pairing code appears on a secure window. While a code of the same
kind is shown and unanswered, asking again keeps it rather than creating
another pairing. An answered code leaves the screen. Both people compare the
six-digit code before the owner confirms. A shared link remains unverified
until full safety-code comparison. The one scanner dispatches pairing and safety codes and explains legacy
call invitations and contact text. Unknown text and legacy contact
codes get an explanation. Safety QR text binds both complete composite
fingerprints and all 60 safety digits using the closed grammar
`ito-safety:1:<64 lowercase hex>:<64 lowercase hex>:<60 digits>`.
A matching scan still requires user confirmation. A dashed portrait ring means
unverified, a solid ring verified. A portrait shows the photo chosen on this
phone, or initials. Unverified contacts offer comparison in
place of the message composer. Identity-1 contacts offer Pair again.

Mailbox traffic uses only the currently authenticated shared relay. The app
opens new inboxes before exposing their addresses and deposits the scanner's
confirmation before opening the ratchet. It shows queued, sent, delivered and
read states, local deletion, a shared retention timer and verification method.
Names are local labels or peer-provided hints, never provider attestations.
Messages and call summaries share a timeline ordered by local arrival.

Paired, verified contacts use ratcheted ringing and composite call profile 3.
The incoming screen appears only after the core authenticates the invitation.
Its isolated activity can appear over Android's keyguard without exposing app
navigation. Main and incoming activities share visible connection ownership,
so moving between them keeps the same run and app-lock state.
Accept creates the responder before JOIN, with no second consent prompt.
The [Android lifecycle](MESSAGING_LIFECYCLE.md) owns background reachability,
notification privacy and the app-lock boundary. The end screen
attributes a peer END only after proof. The Android Camera2/ZXing scanner runs
only while its screen is active, requests permission on entry and stops when
the app leaves foreground. Invitation/pairing text stays transient and out of
saved navigation. Only shared pairing links offer Share. Camera, audio and
background reachability require their own device evidence.

## Composition

```text
Android UI and lifecycle
  ├─ Local contacts and communication identity
  ├─ Optional provider enrollment, or manual YUME configuration
  ├─ Call signaling and authenticated end-to-end media
  └─ Public libyume C ABI → authenticated service connection
```

YUME owns its native wire protocol, authentication, session keys and packet
I/O. Ito owns contacts, provider enrollment, call signaling and the choice of
media transport. The media security implementation must be a reviewed native
component with explicit identity/key ownership, negotiated capabilities,
replay handling and independent vectors. The current YUME C ABI is not a
complete media-encryption or application-identity API.

The first [calling security contract](https://github.com/FixCraft-Inc/ito/blob/main/docs/CALL_SECURITY.md) assigns local identity,
contact trust, confidential one-use invitations and channel-bound peer proofs
to Ito. Its [component decision](https://github.com/FixCraft-Inc/ito/blob/main/docs/SECURITY_COMPONENT.md) selects a maintained
native TLS record channel. The [relay contract](https://github.com/FixCraft-Inc/ito/blob/main/docs/CALL_RELAY.md) specifies the
separate authorized service ingress and opaque forwarding. The portable
[call core](https://github.com/FixCraft-Inc/ito/blob/main/call/README.md) implements the endpoint for the app and
the Linux `ito-call` peer. `CallBook` owns the identity and contacts,
`CallController` runs one attempt on the connected run's stream, and calls
carry authenticated DATA. The portable [content codec](https://github.com/FixCraft-Inc/ito/blob/main/docs/CONTENT_CHANNEL.md)
adds bounded Opus/chat envelopes above that DATA channel. Android's
[foreground audio adapter](VOICE.md) owns microphone/playback, codecs, focus
and routes. [App lock](APP_LOCK.md) guards new secret access, and
[local content](LOCAL_CONTENT.md) owns encrypted per-chat messages/history and native sessions.
The call core owns buffered DATA/END processing across orderly input EOF and
keeps application backpressure within the bounds in
[calling security](https://github.com/FixCraft-Inc/ito/blob/main/docs/CALL_SECURITY.md). This lifecycle policy stays above YUME.

The call service joins two outbound client connections and forwards opaque
bytes, which the endpoint will fill with encrypted media. It is separate
application code in [relay/](https://github.com/FixCraft-Inc/ito/blob/main/relay/README.md), a YUME module behind
yumed's grant check, not an ability obtained merely by connecting two users to
an ordinary yumed. It links no YUME library and holds no endpoint secret. YUME protects
each client-to-service connection. Independent end-to-end encryption keeps
audio private from that service. The operator still sees connection metadata
and may deny service. The local contact key must be verified independently
of a provider's account name or directory response.

## Weak connections and emergency audio

The current Emergency setting is a preference only. Normal foreground audio
uses fixed mono Opus at 48 kHz, 20 ms and a requested 24 kbit/s codec payload.
Adaptive bandwidth, bitrate and packet duration are not implemented.
Evaluate roughly 6–8 kbit/s of codec payload for its emergency setting. That
number excludes transport/authentication headers, feedback, redundancy,
handshakes and link overhead. Do not advertise it as whole-link usage.

Evaluate Codec 2 independently for more restrictive links, including its
700–2400 bit/s modes. The upstream project currently marks many legacy modes
as no longer actively developed, so selecting a mode requires checking its
maintenance, portability, intelligibility, delay and loss behavior. No codec
is bundled or selected for that tier yet. Push-to-talk or encrypted voice
messages may remain useful when live conversation is not.

Bitrate adaptation should follow bounded feedback about loss, delay, jitter
and queue growth, with conservative recovery. Forward error correction and
longer packets trade data use against delay and loss damage. Media queues
need deadlines; old speech must not become seconds of accumulated playback.

Today's YUME packet channels preserve boundaries over an ordered TCP-based
carrier. TCP loss recovery can stall subsequent speech. YTP/1 rejects gaps
and out-of-order protected records. An unreliable media path therefore needs
a separate authenticated datagram contract and replay/lifetime model. It
cannot be implemented by relaxing the existing session checks or putting
the same records onto UDP. Any new carrier also needs stealth evidence.

## Ownership and limits

Open source permits private or paid hosting. Server-side authorization and
quotas enforce access even when a user modifies or rebuilds the client.
Source visibility, an APK secret or obscured endpoints are not access control.

No app configuration can guarantee connectivity during an internet shutdown.
Sleeping-phone ringing, push metadata, battery use, censorship, and mobile
handover need explicit designs and device evidence. A provider directory may
be an optional source of service descriptors, never the only path to using
the app. A public directory also exposes its listed endpoints to censors.

Ito does not call emergency telephone numbers. Emergency mode describes a
low-data preference and must not imply a safety or availability guarantee.

## Shared provider implementation

Optional enrollment uses one source library, yume4a’s `provider-library`,
through explicit version and content bindings. Its generic state, validation,
OAuth and grant owners have no UI, VpnService or application dependency.
Ito’s `provider/` package supplies an independent Keystore backend, selection
adapter and public-ABI native validator. `ConnectionController` retains
Ito’s lifecycle, Network and native borrower ownership for both manual and
provider runs. [Provider enrollment](PROVIDERS.md) owns the exact authority,
clock, removal and registration rules; [secret storage](SECRET_STORAGE.md)
owns the consumer boundary. No calling or media capability follows from
provider enrollment.
