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
The application serializes data calls and all handle replacement.

No device is attached. The public ABI therefore makes exactly one attempt
per start and does not retry after session loss. Kotlin owns explicit
reconnect and network selection. It resolves the selected server through
that Android `Network` and binds every native socket through the captured
`SocketBinder`. The callback retains its Java object until endpoint
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
