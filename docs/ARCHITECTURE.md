# Architecture and security

## Application owners

The Android app uses Kotlin, Jetpack Compose and Material 3. Its screens read
a lifecycle-aware ViewModel. Preferences are serialized into a single
DataStore entry and updated transactionally. They contain routing/audio
choices and at most eight named HTTPS service origins. Credentials, paths,
queries and fragments are refused in those origins. An origin is a saved
address, not proof of a provider's identity or an authorization grant.

Missing preferences use protected routing and adaptive audio. Unreadable,
unsupported or invalid saved state is shown as an error and is not rewritten
with defaults. Service removal preserves routing and audio preferences.
Changing audio quality cannot enable a direct path. Enabling direct calling
requires the user to accept an IP-disclosure explanation.

The pure call policy rejects media without authenticated peers and end-to-end
encryption. A YUME path additionally requires authenticated YUME; a direct
path additionally requires the user's direct-path choice. No media producer
calls this policy yet. Tests establish its decisions, not any encryption or
transport implementation.

## Authenticated data connections

The process-owned `ConnectionController` owns one `ConfigurationStore` and one
native run. Android screens observe public labels and state. Sealed documents
and codes stay process-local. Native validation precedes storage publication.
Import does not select a configuration. Start reacquires the selected encrypted
material and creates private credentials for that run. Native handles close
before credential files. Startup removes abandoned sessions before allowing new
work. Storage or cleanup failure is latched until an explicit successful retry.

Every native transport socket binds to the captured non-VPN Android Network.
DNS uses the same Network with a cancellable five-second request. An imported
numeric `endpoint.connect_address` retains its dial authority, while TLS and
YTP still authenticate `endpoint.host` and the configured native identity.
Adapter/control settings become empty because Ito opens public streams/packets
directly. Circuits and upstream proxies are refused by the current app adapter.
Services, suite, limits and credential authority remain the imported values.
No device is attached, so the native endpoint makes one attempt. A failure or
network loss stops that run. Reconnect requires another explicit Connect action.
It never substitutes another configuration or a direct destination socket.
Activity backgrounding stops data connections; rotation retains the process
owner. Process restart restores explicit selection but does not auto-connect.

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

## Planned composition

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

The first [calling security contract](CALL_SECURITY.md) assigns local identity,
contact trust, confidential one-use invitations and channel-bound peer proofs
to Ito. Its [component decision](SECURITY_COMPONENT.md) selects a maintained
native TLS record channel. The [relay contract](CALL_RELAY.md) specifies the
separate authorized service ingress and opaque forwarding. These are intended
interfaces with a bounded host component evaluation, not implemented app calls.

A minimal call service joins two outbound client connections and forwards
opaque encrypted media. It is separate application code, not an ability
obtained merely by connecting two users to an ordinary yumed. YUME protects
each client-to-service connection. Independent end-to-end encryption keeps
audio private from that service. The operator still sees connection metadata
and may deny service. The local contact key must be verified independently
of a provider's account name or directory response.

## Weak connections and emergency audio

The current Emergency setting is a preference only. The first media candidate
is mono Opus speech with adaptive bandwidth, bitrate and packet duration.
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
