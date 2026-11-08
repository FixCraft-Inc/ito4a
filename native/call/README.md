# Ito call core

`native/call` implements the endpoint side of
[calling security](../../docs/CALL_SECURITY.md): the local communication
identity, contact trust, one-use invitations, the external-PSK TLS 1.3
channel and the call state machine. It speaks the relay's control framing
from [the relay contract](../../docs/CALL_RELAY.md) and then carries TLS
records through the relay. It is C++20 with no Android, JNI or Kotlin
dependency. The Android app and the Linux `ito-call` endpoint both run it
through one C interface, [`include/ito/call.h`](include/ito/call.h).

The call core carries profile-1 authenticated DATA. Its portable
`ito/content.h` codec validates bounded Opus/chat envelopes described in
[the content contract](../../docs/CONTENT_CHANNEL.md). Android owns platform
Opus and audio devices through [its adapter](../../docs/VOICE.md). The Linux
CLI remains a test-DATA endpoint. This core owns no microphone, playback,
ringing or background delivery.

## Owners

| Source | Responsibility |
| --- | --- |
| `codec.*` | Contact codes, fingerprints, invitations, call context, proofs, relay control frames and in-TLS frames. Pure and bounded |
| `crypto.*` | The private OpenSSL library context, SHA-256, Ed25519 and the random source |
| `tls.*` | The external-PSK TLS 1.3 channel over memory BIOs and its exact policy check |
| `store.*` | Identity, contacts, spent invitations, clock admission, their snapshot format and every trust and replay rule |
| `invitation.*` | Validation of a received invitation and signing of a new one |
| `engine.*` | One attempt as a sans-I/O state machine: relay exchange, TLS, frame order, consent, deadlines and quotas |
| `driver.*` | Runs an engine over a blocking transport with 20 ms I/O slices |
| `api.cpp` | The C interface, its handles, borrow counts and status mapping |
| `cli/` | The Linux endpoint: a host test store and a libyume stream transport |

The platform seals the store's plaintext snapshot. On Android that is
Kotlin's `IdentityStore` with a Keystore key, as
[secret storage](../../docs/SECRET_STORAGE.md#call-identity-storage)
describes. On Linux `ito-call` keeps it in an owner-only file without
encryption, as a test and development store.

## Building and testing

Packaging and qualification link stock OpenSSL 3.5.9 statically from a
prefix built from the pinned archive in
[`tools/security-component.json`](../../tools/security-component.json):

```sh
cmake -S native/call -B build/call -G Ninja -DCMAKE_BUILD_TYPE=RelWithDebInfo \
  -DITO_CALL_OPENSSL_ROOT=/path/to/openssl-3.5.9 \
  -DITO_CALL_YUME_INCLUDE=/path/to/yume/include -DITO_CALL_LIBYUME=/path/to/libyume.so
cmake --build build/call
ctest --test-dir build/call --output-on-failure
```

Without `ITO_CALL_OPENSSL_ROOT`, and with `-DITO_CALL_OPENSSL_VERSION=`, the
build uses the system's OpenSSL 3.5 or later for quick local checks. That
build is not the pinned component, and it skips the exported-symbol test.
`ITO_CALL_SANITIZE=address,undefined` adds sanitizers. `ITO_CALL_FUZZERS=ON`
with Clang builds a libFuzzer target over every parser.

The tests are:

- `codec_test` checks every encoding against `tests/vectors.txt`, which
  `tests/generate_vectors.py` writes independently in Python from the
  contract's tables.
- `crypto_test`, `store_test`, `tls_test` and `engine_test` cover the
  owners. The engine test drives two engines, and scripted raw TLS peers that
  hold the invitation PSK, through an in-memory relay with virtual time.
  Its `--buffered-eof` selector checks queued DATA/END across orderly EOF,
  split/coalesced records, incomplete input and immediate terminal overrides.
  Its `--handshake-budget` selector feeds real fragmented TLS records to both
  endpoint roles and checks the exact 256 KiB inbound threshold and the next
  byte's typed refusal without changing the 64 KiB BIO bound.
  Its `--hello-retry` selector completes a real one-HRR handshake, keeps
  authentication failures after one HRR typed, and replays the real server HRR
  after ClientHello2. Coalesced and byte-fragmented second HRRs terminate with
  `ProtocolFailure`.
- `parser_random_test` mutates the vectors through every parser.
- `driver_test` ends an attempt typed when a C++ platform transport throws.
  Its `--write-end` selector checks that terminal writes are not followed by a
  stale read and that write-side EOF remains a transport failure.
- `api_test` runs a threaded call through the packaged library, and fails
  each allocation of a store change, an invitation opening and a responder's
  call creation in turn through a test-local `operator new`. Each failure
  must be typed, preserve identity, trust and replay state, keep the caller's
  invitation and borrows intact, and leave no identity seed in freed memory.
  A completed independent clock checkpoint can survive a later failure.
  Each injection loop must exercise a failure before success.
- `exports_test` checks that `libitocall.so` exports only `ito_*` and imports
  no OpenSSL symbol.
- `failure_test`, driven by `tests/failure_sweep.py`, runs each allocation
  index in a fresh child. Its executable links the production core objects
  and its own pinned OpenSSL, with test-local C++ and OpenSSL allocators.
  The packaged library binds its OpenSSL privately and is not interposed.
  RESERVED/JOIN failures inspect buffers before free for the bearer tokens
  and actual invitation PSK. A test-only RAND wrapper delegates to the real
  RNG and captures the initiator PSK for observation without changing it.
  Signing/verification and both first-flight TLS roles exercise actual
  OpenSSL allocation failures. Operation cases finish observation before
  the outer context/provider teardown; this is not whole-process erasure.
  The cold-context case includes initialization and destruction. A failed
  base-lock allocation in the pinned OpenSSL can recursively enter global
  initialization and stall; the sweep kills and waits for that child and
  reports a failed gate. Warming initialization does not qualify this path.

The endpoint harness requires a zero CLI exit for every written call result
and rejects sanitizer diagnostics in each private stderr log. It reaps both
CLI children on setup and scenario failures. `python3 -B
tests/test_call_harness.py` checks these process failures without native builds.

`tests/call_harness.py` runs two `ito-call` endpoints through a real yumed
and `ito-call-relay`, reusing the relay's yumed harness. A recording tap on
the relay's module socket checks that only TLS records pass after pairing.
Evidence builds (`ITO_CALL_EVIDENCE=ON`, never a product) report their
secrets, and the tap shows none of them, and no payload, crossed the relay.

Clock admission uses the caller's current Unix seconds and the core's monotonic
clock. Detected rollback returns `clock_regressed` before new invitation use.
The uncertainty checkpoint remains across reopening. Automatic recovery from
that state is not implemented, and a corrected display clock alone cannot
clear it.
Opened invitations keep their original monotonic deadline across handoff.
Reload retains identity/trust/replay and the clock floor, without restoring
pending secrets or deadlines. The exact observation, persistence and detection
limits are in [calling security](../../docs/CALL_SECURITY.md). Clock refusal is
never a reason to remove or recreate the host store.

## Symbol isolation

`libitocall.so` links OpenSSL with `--exclude-libs,ALL`, `-Bsymbolic` and a
version script that exports only the C interface. Its OpenSSL therefore
neither interposes on nor binds to the patched OpenSSL inside libyume when
both load in one process. `tools/build_call_core.sh` builds it for Android
and refuses a library whose exported or imported surface differs.

## The Linux endpoint

```sh
ito-call --store DIR identity create
ito-call --store DIR contact add CODE NAME
ito-call --store DIR contact verify CODE FINGERPRINT
ito-call --store DIR call --peer CODE --invitation-out FILE \
  --yume-config DIR --service ito-call --result FILE [--send FILE --expect-bytes N]
ito-call --store DIR answer --invitation FILE \
  --yume-config DIR --service ito-call --result FILE [--decline]
```

`--yume-config` names a YUME client configuration directory with
`yume.json`. The invitation file stands in for an authenticated and
confidential channel. It holds the invitation PSK, so the caller moves it
privately and wipes it after use.
