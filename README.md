# Ito

Ito (糸, Japanese for “thread”) is an independent Android communication app
built for private audio calls, with text planned later. ITO is its wordmark,
not an acronym. The source is AGPL-3.0-or-later. Service operators may charge
for access or require authorization to use their infrastructure.

## Current application

Ito imports an operator’s sealed YUME configuration through Android’s document
picker, keeps it encrypted with Android Keystore, and connects through public
libyume without a systemwide VPN. Select a configuration and underlying network
explicitly. The bounded stream and packet echo tests report payload counts and
SHA-256 receipts. They require a server-authorized echo destination.

Protected routing remains the default. Failed authentication, unavailable
networks and refused destinations do not select another provider, manual
configuration or direct route. Calling, communication identity, media security,
audio codecs and push are not implemented. Optional provider sign-in, entitled
server selection and provisioning use the shared provider library.

Public service addresses and call preferences retain their own DataStore owner.
Saving an address does not contact its server or grant transport authority.

## Ownership

Ito is separate from `yume4a`, the general-purpose YUME VPN application.
It embeds the YUME core through its public C ABI without requiring
`VpnService`, a YUME app installation or a provider account. YUME and BaseFWX
remain independent projects. Ito's UI text and visual identity are owned here;
the existing YUME/Android shared catalogs are not changed or forked.

The public YUME ABI is experimental. [Native integration](docs/NATIVE.md)
owns build provenance, lifecycle and network binding.
[Secret storage](docs/SECRET_STORAGE.md) owns backup exclusion, publication,
corruption and crash recovery. Calling needs its own compatibility and device checks.

[Architecture and security](docs/ARCHITECTURE.md) owns the product boundaries.
[Provider enrollment](docs/PROVIDERS.md) owns the shared-library binding and
Ito’s account, selection and connection adapters.

[Calling security](docs/CALL_SECURITY.md) specifies the planned local identity,
contact verification and authenticated channel. The [relay contract](docs/CALL_RELAY.md)
defines the separate opaque forwarding service. The [component decision](docs/SECURITY_COMPONENT.md)
records the maintained native selection and its host-only evaluation. These
contracts do not add calling or media to the application.

## Build and validation

Use JDK 21, Android SDK platform 36.1, and the checked-in Gradle wrapper.
The wrapper pins Gradle 9.3.1 and its distribution SHA-256. Dependencies are
pinned in the version catalog. No release signing keys are included.

```sh
ito_provider_root=/path/to/yume4a/provider-library
python3 /path/to/yume4a/tools/provider_source_manifest.py --root "$ito_provider_root"
# Set this to the reviewed manifest’s source_sha256 value.
ito_provider_sha=REVIEWED_SHA256
./gradlew --no-daemon --max-workers=2 -PitoYumeRoot=/path/to/yume \
  -PitoProviderRoot="$ito_provider_root" -PyumeProviderVersion=0.1.0 \
  -PyumeProviderSourceSha256="$ito_provider_sha" \
  :provider-library:testDebugUnitTest :provider-library:lintDebug \
  :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

Builds require NDK 28.2.13676358 and a vcpkg checkout selected by VCPKG_ROOT.
Dirty native candidates require -PitoUnverifiedSource=true for debug tests only.
Release refuses that override. The build records native source/library hashes
and packages verified dependency notices. See [notices](THIRD_PARTY_LICENSES.md).

Tests cover storage/refusal/lifecycle boundaries, media authorization under missing authentication/encryption,
direct-path consent, service address validation, corrupt saved preferences,
and keeping routing independent of the Emergency preference.
