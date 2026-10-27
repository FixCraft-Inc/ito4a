# End-to-end security component decision

Use stock OpenSSL 3.5.9 from the supported 3.5 LTS line for the initial
reliable end-to-end channel specified in [calling security](CALL_SECURITY.md).
TLS 1.3 owns authenticated key exchange, traffic keys, nonces, ordered record
protection and KeyUpdate. Ito owns confidential invitation delivery, independent
contact verification, exporter-bound identity proofs, consent, policy and
bounded state. This decision does not add the component to the Android runtime
or implement a calling endpoint.

## Selected source and support

The release tag `openssl-3.5.9` points to commit
`45e844fa2a14ec92d146bd8f5778ac130b6625fb`. Its official 53,279,637-byte archive
has SHA-256
`603f5602e2eef00d77fbd429d34dcd5822bb301757a1bc9cdb24c670f1eb859a`.
[tools/security-component.json](../tools/security-component.json) owns the
evaluation pin. The [release](https://github.com/openssl/openssl/releases/tag/openssl-3.5.9)
and [official checksum](https://github.com/openssl/openssl/releases/download/openssl-3.5.9/openssl-3.5.9.tar.gz.sha256)
provide provenance. HTTPS metadata and checksums do not establish independent
verification of the publisher's signing key.

OpenSSL's [release strategy](https://openssl-library.org/policies/releasestrat/)
supports 3.5 LTS through 2030-04-08. New integration must recheck current
security advisories and supported patch releases rather than treating this
evaluation pin as permanent. Its [Apache-2.0 license](https://github.com/openssl/openssl/blob/45e844fa2a14ec92d146bd8f5778ac130b6625fb/LICENSE.txt)
fits Ito's AGPL-3.0-or-later application. The stock component has no YUME patches.
Preserve complete upstream license/NOTICE inputs and actual archive/binary
bindings when it is packaged. A license label alone is not a notice inventory
or corresponding-source delivery.

The existing native transport separately pins patched OpenSSL 3.5.7. That
dependency is preserved. Upstream 3.5.8/3.5.9 contain later security fixes,
including CVE-2026-35189's peer-certificate memory exhaustion. A transport
dependency upgrade needs its own reviewed change and qualification. Do not
reuse the old transport pin for new security code merely because it is present.
The selected external-PSK channel accepts no peer certificate, but still uses
the current supported patch as its new component baseline.

## Authentication and integration

The exact profile uses external per-invitation PSK, PSK-DHE only,
`X25519MLKEM768`, `TLS_AES_256_GCM_SHA384`, no early data and no ticket/cache
resumption. It follows with Ed25519 proofs bound to the RFC 9266 exporter,
both expected keys and the exact call context. The PSK is an invitation secret,
never account or transport authority. TLS KeyUpdate supplies no new DH entropy
or post-compromise recovery. Authentication remains classical Ed25519 even
though key exchange is hybrid. See the complete failure/claim boundaries in
the call contract.

Ordinary mutual TLS was considered. TLS 1.3 hides certificates from a passive
forwarder, but an active relay can initiate its own handshake and obtain the
responder certificate before authenticating as a client. The selected secret
invitation avoids emitting a stable identity to that prober. It requires an
authenticated confidential invitation channel. No automatic invitation delivery
or background reachability is supplied by this component.

OpenSSL's pinned [PSK client API](https://github.com/openssl/openssl/blob/45e844fa2a14ec92d146bd8f5778ac130b6625fb/doc/man3/SSL_CTX_set_psk_client_callback.pod),
[PSK server API](https://github.com/openssl/openssl/blob/45e844fa2a14ec92d146bd8f5778ac130b6625fb/doc/man3/SSL_CTX_use_psk_identity_hint.pod)
and [SSL API tests](https://github.com/openssl/openssl/blob/45e844fa2a14ec92d146bd8f5778ac130b6625fb/test/sslapitest.c)
own callback/session semantics. An external-PSK handshake can report
`SSL_session_reused()` true. Enforce fresh exchange and external-secret
provenance explicitly. Each callback transfers a new session reference, including
on HelloRetryRequest. Disable issued tickets with a zero ticket count as well
as cache/early data. Older TLS 1.2 PSK callbacks cannot express the SHA-384
profile correctly. These choices need endpoint regression tests.

Upstream [Android instructions](https://github.com/openssl/openssl/blob/45e844fa2a14ec92d146bd8f5778ac130b6625fb/NOTES-ANDROID.md)
provide explicit NDK targets, not Android runtime qualification. Later integration
needs a separate narrow native security target, an explicit private library
context/default provider and hidden symbols. Ito's current JNI links only
public libyume, whose internal OpenSSL symbols are not an app API. Avoid symbol
interposition between the transport's static OpenSSL and the separately linked
component. Do not change the YUME ABI or expose transport keys to reuse a
library. No BaseFWX dependency is needed.

The native signing seed is exportable while borrowed, wrapped at rest by a
separate nonexportable Android Keystore AES key. Storage, backups, corruption,
recovery and borrow lifetimes stay Ito responsibilities. OpenSSL does not
implement contact trust or durable replay state.

## Alternatives

| Component and inspected provenance | Decision for the first reliable channel |
| --- | --- |
| [libwebrtc](https://webrtc.googlesource.com/src/+/2d36baf310ef35f012de171bac789662228b017f), BSD-3-Clause project plus patent grant and dependency notices | Maintained Android/native media stack with DTLS-SRTP and libSRTP, but normal PeerConnection adds ICE, SDP, datagram transports and socket/media lifecycle. Fingerprints still require authenticated signaling. Retain for a separately scoped datagram/media decision rather than importing that stack for one reliable opaque stream. |
| [libsignal v0.106.0](https://github.com/signalapp/libsignal/tree/fc90b90fa2b9c37822fa47ba0e0dcdf387a0c071), AGPL-3.0-only | Maintained Rust/Java/JNI identity, prekey and ratchet implementation. Adds persistent asynchronous messaging owners unnecessary for this synchronous channel. Upstream explicitly gives no support for use outside Signal or stable APIs. Licensing is not the rejection reason. It supplies neither call transport nor media lifecycle. |
| [libsodium 1.0.22](https://github.com/jedisct1/libsodium/tree/77e1ce5d6dee871c49ef211222ba18ef0c486bda), ISC | Maintained primitives and exchange helpers. Ito would still need a custom authenticated handshake, record/nonce ownership and rekey construction. Prefer the maintained complete TLS record channel. |
| [Noise-C](https://github.com/rweather/noise-c/tree/cfe25410979a87391bb9ac8d4d4bef64e9f268c6), MIT | Inspected reference implementation has no current release and its latest returned commit dates to 2023-12-06. It does not meet the selected maintenance baseline. This finding concerns this implementation, not every Noise library. |
| YUME relay-channel module v2 | Its clear handshake contains endpoint identities and invitation context, and its optional BaseFWX-dependent library is outside libyume's graph. It is not a calling service or a communication-identity API. Preserve its frozen labels and existing consumers. |

WebRTC additionally brings BoringSSL, libSRTP, libyuv, FFmpeg and GN/gclient
toolchain inputs. libsignal brings Rust and its JNI/prekey/session storage
model. Their upstream tests and Android integration were inspected in source,
not executed in this evaluation. No alternative is rejected merely because
it would need an app authentication contract.

## Reproducible bounded evaluation

Build the pinned stock source into an isolated static prefix on the approved
build host, then run:

```sh
python3 tools/evaluate_security_component.py \
  --openssl-prefix /path/to/openssl-3.5.9-prefix \
  --compiler /usr/bin/c++ --output /path/to/new-evaluation
```

The prefix must contain headers, `lib/libssl.a` and `lib/libcrypto.a`. The output
directory must not exist. The runner compiles with C++20 and warnings as errors,
executes the memory-BIO probe with finite budgets, validates nonempty passing
case results and records input/archive/compiler/binary hashes. The component
version is checked at runtime. No sockets, credentials, provider accounts,
application data store, microphone or Android calls participate.

The case list is executable evidence of component operations and refusal,
including independent known-answer cryptographic checks. A fixed context digest
fixture tests signature binding, not the application's future invitation/frame
parser or consent/replay state. The harness is not production endpoint code.
Later integration must qualify all call-contract gates, two real endpoints
through the service, Android storage/JNI/cancellation, hidden symbol provenance,
notices, physical-device behavior and release packaging. Component execution
does not establish working calls, codec behavior, physical accessibility,
handover, ARM64 runtime or signed-release readiness.
