# Native transport integration

Ito's `app/src/main/cpp/ito_jni.cpp` includes only the public `yume/yume.h`
header and links the shared `libyume.so`. YUME owns parsing, credentials,
authentication, protocol cryptography and data channels. Ito has no engine
patches, private C++ includes, TUN device or VPN service.

## Building and provenance

Build `libyume` with the selected YUME checkout's supported Android builder:

```sh
bash tools/build_native.sh --yume /path/to/yume \
  --ndk /path/to/Android/Sdk/ndk/28.2.13676358 \
  --vcpkg /path/to/vcpkg --abi x86_64 --out /path/to/ito-native --jobs 2
```

The wrapper requires a Git checkout, records the contents, modes and links of
every Git-visible input, invokes YUME's signature/dependency checks and verifies
that inputs did not change during the build. It records the exact copied public
header, shared library, NDK and source binding in `provenance.json`. Keep that
record with the APK and JNI library hashes for qualification.

`--unverified-source` permits dirty candidate sources for debug testing only.
Release builds must retain verified native provenance. A previous APK pass
does not qualify a new JNI adapter or consumer. BaseFWX is not a dependency
of this native transport integration.

Gradle supplies the absolute output root as `ITO_YUME_SHARED_ROOT` to
`app/src/main/jni/Android.mk`. The root contains `include/yume/yume.h` and
`<abi>/libyume.so`. ndk-build compiles Ito's adapter with C++20, hidden symbols
and warnings as errors and packages `libitojni.so`, `libyume.so` and the NDK's
`libc++_shared.so`. All Android/NDK builds run on the approved build host in
an isolated task root.

## Handles, cancellation and network ownership

`ItoNative` owns no application singleton or retry policy. `create` parses
the configuration, validates client credentials and constructs one endpoint.
Its opaque handle belongs to one Kotlin connection owner. Each runtime,
configuration, endpoint, kit, callback, stream and packet has an RAII owner.
An initialization failure releases every earlier acquisition. Native
exceptions are contained at JNI and become `NativeFailure(code, operation)`.
Operation names are fixed strings. Diagnostics, credentials and payloads
are never exception text.

`start` blocks for a finite positive deadline of at most five minutes. A
timeout ends that attempt. Kotlin must not poll by calling start repeatedly.
A cancellation owner calls `stop` on another worker, waits for the start or
I/O borrower to return, then calls `destroy` once. `stop` wakes native I/O
and drains the endpoint's workers. A handle must never be destroyed while
another call borrows it. Status may overlap start, stop and one data call.
The application serializes probes and call attempts. A dedicated mailbox
stream has its own run-owned worker. Handle replacement joins both owners.

No device is attached. The public ABI therefore makes exactly one attempt
per start and does not retry after session loss. Kotlin owns automatic
connection, reconnect and network selection under the
[messaging lifecycle](MESSAGING_LIFECYCLE.md). The default selection captures the application's
system default `Network`, including a VPN, for both DNS and sockets. Explicit
physical-network selection requires an internet-capable application default
without a VPN; a stale or unavailable selection is refused without choosing
another route. A split-tunnel VPN excluding Ito is not the application's default.
The owner observes both the selected Network and the system default. A default
replacement or loss, loss of internet capability, or a blocked selected/default
Network requests Stop. It latches that request before the Main owner joins native
borrowers, destroys the handle and reports `NetworkLost`. Registration precedes
DNS, and route checks run before DNS, native start and each socket binding. Those
checks and callbacks observe Android's changes; they are not an atomic lock on
system routing. A replacement run reacquires the same selected service and applies these
network rules again. Automatic retries admit only network unavailability,
network loss, DNS failure and public YUME TIMEOUT, CLOSED or IO_ERROR, with
exponential delays capped at 30 seconds. Authentication, authority, input and
storage failures require intervention. Disconnect pauses automatic attempts
for this process. Connect resumes them. Backgrounding stops ordinary runs
unless an active audio or opt-in messaging foreground service retains the run.
The owner resolves the selected server through that captured Network and binds
every native socket through the `SocketBinder`. The callback retains its Java object until endpoint
destruction drains all native workers. Binding refusal or a callback exception
fails closed without selecting another network. This connection owner requires
Android 10 (API 29) for bounded DNS, including its numeric-address path. On
API 28 it refuses connection before constructing a native endpoint.

## Bounded data and storage adaptation

Sealed kit opening uses `yume_kit_open` and returns its bounded validated
layout. Its code and native copies are wiped on unwind. Kotlin owns returned
file arrays and clears them after publication or failure. The store validates
configuration/file references and keeps staging private. Native kit opening
alone does not validate configuration, file material or a remote peer.
`checkConfig` uses the strict public parser, and `validate` loads referenced
credentials and returns the local private-key-derived fingerprint.

`streamEcho` accepts 1–65536 bytes and `packetEcho` accepts 1–1024 bytes.
Each call names a service and an optional explicit destination. It has one
five-second budget across OPEN, write and all reads. Partial stream reads
are assembled within the original size, and packets must return exactly one
whole packet of the requested length. Failure returns no payload result.
RAII destroys the logical channel on every return. A result includes the
authenticated channel peer fingerprint. Kotlin compares the returned payload
and fingerprint and records only counts and hashes. Native payload copies
are wiped before release. These are data probes, not call or media functions.

`status` reports public endpoint/session states, typed last failure, native
payload/record counters and the active peer fingerprint. `liveHandles`
exposes counts of adapter-owned resources for lifecycle qualification. It
does not measure all core descriptors, heap allocations or platform copies.

Provider connections use this same JNI/native library and captured Network.
The shared lease supplies a provider-only staging session. Ito compares the
private-key-derived local identity against the selected grant before endpoint
creation, checks the captured permit during startup and data use, and stops
its exact run when authority ends. The native handle is destroyed before the
staging session and permit are closed. See [provider ownership](PROVIDERS.md).

## Call core

The [call core](https://github.com/FixCraft-Inc/ito/blob/main/call/README.md) is a separate library,
`libitocall.so`, with stock OpenSSL 3.5.9 linked inside it and hidden. It comes
from the `ito` repository, a sibling checkout that `-PitoCoreRoot` names, by
default `../../ito`. Each build first requires a clean checkout of a signed
commit, which `-PitoUnverifiedSource=true` waives for debug tests. Gradle's
`buildCallCore` task runs that checkout's `tools/build_call_core.sh`, which checks
the pinned OpenSSL archive against its `tools/security-component.json`, builds
OpenSSL and the core for each selected ABI with NDK 28.2.13676358, refuses a
library that exports anything but the C interface or imports an OpenSSL symbol,
and records the archive, sources, libraries and source commit in `provenance.json`. `-PitoOpenSslArchive`
names the archive, by default `~/.cache/ito/openssl-3.5.9.tar.gz`. The builder
exports `ito/call.h`, `ito/content.h` and `ito/message.h`; its
`headers_sha256` provenance map binds each copy. Notice generation checks all
three before packaging, and includes those bindings in the native source identity.
`app/src/main/jni/Android.mk` packages it beside `libyume.so`, and
`libitojni.so` links both. The notice generator binds `libitocall.so` by that
provenance, and a changed OpenSSL archive requires a catalog review.

Debug builds package libraries with their symbols. Release builds package
stripped ones, so the generator hashes the same `llvm-strip --strip-unneeded`
output after checking each unstripped library against its provenance. The
packaged-library check then holds in both.

`app/src/main/cpp/ito_call_jni.cpp` adapts the C interface to
`com.fixcraft.ito.call.CallNative`. Core statuses become values where Kotlin
expects them (backpressure, no data, no invitation yet) and otherwise
`CallFailure(code, operation)` with a fixed operation name. Its
clock refusal retains `CLOCK_REGRESSED` through JNI and the call outcome's
resource string. Invitation opening and both call constructors refuse negative
JVM Unix-second inputs before unsigned conversion. Those paths use the core's
one clock admission owner, rather than implementing clock policy in Kotlin. Its
`liveHandles` counts the core contexts, stores, invitations and calls Kotlin
owns, for lifecycle qualification. The persistence
callback calls Kotlin on the thread that mutates the store. A call borrows a
named-service stream from a connected run through `ItoNative.openCallStream`,
whose verified server fingerprint and service are the call's relay
authority. `ConnectionController.serveCall` runs the attempt on the run's
worker, serialized with data probes, and closes the stream only after the
call is destroyed. Closing attempts to drain accepted writes and half-close
within five seconds, then closes immediately. A failed or timed-out shutdown
can leave pending writes that the close aborts.
The portable call owner retains bounded buffered TLS/frame input across orderly
read EOF while application reads release backpressure. It keeps the original
deadlines and immediate error/cancellation paths. EOF alone cannot supply an
authenticated END; [calling security](https://github.com/FixCraft-Inc/ito/blob/main/docs/CALL_SECURITY.md) owns that distinction.
Stopping the run, losing its Network or its provider authority ends the
stream and so the call. A lane the run never serves, because it stopped first
or its authority failed, is refused rather than left waiting. The adapter
finds `CallFailure`, `ContactRecord`, `CallStatus` and the persistence
interface by name, so `app/proguard-rules.pro` keeps them in minified builds.

Cancellation while `openCallStream` is blocked ends the waiting attempt. If
the native open later succeeds, the run closes that stream without invoking
the canceled lane. Dismissing the ended attempt must keep its view idle when
that late stream arrives.

`CallOpeningTest` exercises this schedule through real JNI and the public
libyume ABI. It is an opt-in `RequiresI4bFixture` test, enabled with the
instrumentation argument `i4bOpening=true` and the fixture exclusion cleared.
The target app's `no_backup/opening-fixture/client/` holds a generated client
configuration and its referenced files; `control.json` beside it names the
host control port. The authenticated host server registers `ito-call` and
withholds public stream acceptance. On each `accept` control line it accepts
one stream, requires EOF with zero application bytes, and replies `eof`.
The test sends `done` after its lane and application-owner cases. It observes
the blocking native call before cancellation, then checks callback, call-view
and adapter-handle outcomes. These handle counts do not measure all native or
platform resources, and this fixture does not establish call creation or
invitation-secret cleanup.

`CallAuthorityTest` is a separate opt-in `RequiresI4bFixture` fixture, enabled
with `i4bAuthority=true` and the fixture exclusion cleared. The target app's
`no_backup/authority-fixture/client/` contains a generated authenticated client
kit. A public-ABI host registers `ito-call`, answers RESERVE and JOIN, then
holds the call at WAITING. The test seeds a synthetic local enrollment through
the shared ProviderStore; it does not qualify network enrollment or OAuth.
It uses the real ServiceController permit, ConnectionController, CallController,
JNI and call core to exercise provider removal, joint wall/elapsed expiry,
and selected Wi-Fi loss through the framework's actual `Network.onLost`.
It checks typed connection/call termination, invitation withdrawal and zeroing,
no manual fallback, and exact native-handle baselines after call dismissal.
The application-owned call book remains open until process stop. These counts
do not measure all descriptors, heap or platform resources, and a WAITING call
does not establish peer authentication, MediaAuthorized or audio. The Wi-Fi
case restores Wi-Fi and awaits the framework callback during cleanup.

## Messenger sessions and mailbox

`MessageNative` binds the portable `ito/message.h` interface in the same
`libitocall.so`. Native code owns identity-2 upgrade, pairing, safety-code
verification, ratchets and message sealing. Kotlin owns encrypted history,
session persistence, the mailbox framing and foreground lifecycle. No
protocol cryptography is duplicated in Kotlin. Session snapshots are bounded
to 717,492 bytes. JNI clears borrowed plaintext sink arrays after return,
contains exceptions and counts message handles separately. Message sessions
close before their borrowed identity store.

`Messenger` serializes pairing and sessions with a fair I/O lock, yielding to
waiting commands between poll turns. Idle network waits borrow no identity
store, so call admission can use the store on its separate lane. It opens every
new inbox before displaying its pairing code or depositing an item naming it.
The scanning side deposits the saved confirmation before opening its session.
Mailbox targets must match the current authenticated server fingerprint and
named `ito-mailbox` service. A target cannot select another service, network,
provider or direct socket. A contact on another relay remains queued.

One connection-run worker lends a dedicated mailbox stream alongside the
serialized call/probe lane. Stop wakes it, joins it and closes its stream
before destroying the endpoint. Framing enforces exact MAILBOX/1 lengths,
a five-second partial-frame deadline and at most 512 pushed items or 8 MiB
while waiting for request replies. Timeout/transport loss and service refusal
can retry on the same endpoint with bounded backoff. Malformed protocol input
ends the mailbox worker. Failure to open the stream is visible as a messenger
transport error. No registration grants mailbox service access by itself.
An idle new stream sends PING before its first wait, meeting the server's
five-second first-frame budget even when there are no inboxes yet. REFUSED
retains its protocol, capacity, timeout or invalid-request status.
Explicit Stop also closes sessions opened while offline. This cleanup belongs
to the process's connection owner and survives cancellation of an Activity waiter.

`MessageBoundaryTest` exercises real JNI pairing, ratchets and storage
ambiguity. `MessengerLiveTest` and `RingingLiveTest` are opt-in real-yumed
journeys enabled by `m5aLive=true` and `m5bLive=true`, respectively, with
private fixture kits staged under the target app's
`no_backup/m5a-fixtures/{alice,bob}`. Ringing also needs a call relay behind
the authenticated `ito-call` service. Default instrumentation excludes their
`RequiresMessengerFixture` annotation. Synthetic UI galleries establish
rendering only. Physical audio, camera, background and battery checks remain
separate.

For a disposable physical-device fixture, `m5bHostExport=true` additionally
exports only the test's synthetic Bob identity and session under the target
app's `no_backup/m5b-host/`, and enables background messaging after the journey.
The private driver transfers that fixture to the Linux CLI, removes the export,
and restarts the ordinary app to check incoming calls without instrumentation.
These raw fixture snapshots are secrets and must stay outside Git and logs.
The ringing journey also opens the isolated incoming activity through its
notification, accepts a real invitation there and checks stale action tokens.
Automatic full-screen display over a physical keyguard needs a separate phone
pass with Android's full-screen alert permission recorded.

Ringing uses `MessageNative.receiveRing`, a direct adapter to
`ito_session_receive_ring`. WRONG_STATE returns no handle and leaves the item
for ordinary receive. Other core refusals retain their status. A successful
handle is counted, owned by the ringing coordinator and either destroyed or
consumed by responder creation. Invitation metadata and its public ID come
from the core's existing C interface. Kotlin reads only the canonical public
ID prefix of its own locally created invitation for outgoing correlation,
matching the Linux endpoint. Peer admission and clocks remain native.
