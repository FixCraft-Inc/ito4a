# Ito

Ito (糸, Japanese for “thread”) is an independent Android communication app
built for private audio calls, with text planned later. ITO is its wordmark,
not an acronym. The source is AGPL-3.0-or-later. Service operators may charge
for access or require authorization to use their infrastructure.

## Current application

Ito imports an operator’s sealed YUME configuration through Android’s document
picker, keeps it encrypted with Android Keystore, and connects through public
libyume without requiring a systemwide VPN. Select a configuration and network
explicitly. The bounded stream and packet echo tests report payload counts and
SHA-256 receipts. They require a server-authorized echo destination.

Protected routing remains the default. Failed authentication, unavailable
networks and refused destinations do not select another provider, manual
configuration or direct route. Optional provider sign-in, entitled server
selection and provisioning use the shared provider library.

The [call core](native/call/README.md) holds a local communication identity
and verified contacts, and carries an end-to-end authenticated test call
through a [call relay](relay/README.md) over the selected YUME connection.
The Linux `ito-call` endpoint runs the same core. The app's Calls tab creates
the identity, adds and verifies contacts, shows a call invitation as a QR code
and scans one with the camera. The Android [audio adapter](docs/VOICE.md)
adds user-started mono Opus capture/playback and a foreground call service.
Physical audio/routing and voice readiness require separate device evidence.
Ringing and push are not implemented.

[Local content](docs/LOCAL_CONTENT.md) keeps bounded messages/history encrypted
on this installation. [App lock](docs/APP_LOCK.md) adds optional platform
authentication in Settings. The portable [content codec](docs/CONTENT_CHANNEL.md)
supports synchronous chat over an authenticated call, without a chat screen
or cloud storage.

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

[Calling security](docs/CALL_SECURITY.md) specifies the local identity,
contact verification and authenticated channel. The [relay contract](docs/CALL_RELAY.md)
defines the separate opaque forwarding service, which [relay/](relay/README.md)
implements as a YUME module for operators. The [component decision](docs/SECURITY_COMPONENT.md)
records the maintained native selection and its host-only evaluation.

## Build and validation

Use JDK 21, Android SDK platform 36.1, and the checked-in Gradle wrapper.
The wrapper pins Gradle 9.3.1 and its distribution SHA-256. Dependencies are
pinned in the version catalog. No release signing keys are included.

The default build supports manual/self-hosted use and excludes the provider
client and its dependencies:

```sh
./gradlew --no-daemon --max-workers=2 -PitoYumeRoot=/path/to/yume \
  :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

Enable generic provider sign-in explicitly. This build also retains manual use
and unrelated providers; the flag does not grant account access. It is a build
choice, separate from UI presentation. Settings offers Simple/Advanced
presentation; it changes which details are shown, never routing or authority.

```sh
ito_provider_root=/path/to/yume4a/provider-library
python3 /path/to/yume4a/tools/provider_source_manifest.py --root "$ito_provider_root"
# Set this to the reviewed manifest’s source_sha256 value.
ito_provider_sha=REVIEWED_SHA256
./gradlew --no-daemon --max-workers=2 -PitoYumeRoot=/path/to/yume \
  -PitoProviders=true -PitoProviderRoot="$ito_provider_root" -PyumeProviderVersion=0.4.0 \
  -PyumeProviderSourceSha256="$ito_provider_sha" \
  :provider-library:testDebugUnitTest :provider-library:lintDebug \
  :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

Browser enrollment additionally needs the explicit client/callback registration
and signer association described in [provider enrollment](docs/PROVIDERS.md).
Provisioned configuration stays inside the app. Device-bound browser enrollment
delivers and validates the sealed kit automatically after server selection.
Anonymous providers and manual imports may still require an opening code.

Builds require NDK 28.2.13676358 and a vcpkg checkout selected by VCPKG_ROOT.
Dirty native candidates require -PitoUnverifiedSource=true for debug tests only.
Release refuses that override. The build records native source/library hashes
and packages verified dependency notices. See [notices](THIRD_PARTY_LICENSES.md).

Tests cover storage/refusal/lifecycle boundaries, media authorization under missing authentication/encryption,
direct-path consent, service address validation, corrupt saved preferences,
and keeping routing independent of the Emergency preference.
