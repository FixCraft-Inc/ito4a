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

The [call core](https://github.com/FixCraft-Inc/ito/blob/main/call/README.md) holds a local communication identity
and verified contacts, and carries an end-to-end authenticated test call
through a [call relay](https://github.com/FixCraft-Inc/ito/blob/main/relay/README.md) over the selected YUME connection.
The Linux `ito-call` endpoint runs the same core. The app's Calls tab creates
the identity, adds and verifies contacts, shows a call invitation as a QR code
and scans one with the camera. The Android [audio adapter](docs/VOICE.md)
adds user-started mono Opus capture/playback and a foreground call service.
Physical audio/routing and voice readiness require separate device evidence.
Ringing and push are not implemented.

[Local content](docs/LOCAL_CONTENT.md) keeps bounded messages/history encrypted
on this installation. [App lock](docs/APP_LOCK.md) adds optional platform
authentication in Settings. The portable [content codec](https://github.com/FixCraft-Inc/ito/blob/main/docs/CONTENT_CHANNEL.md)
supports synchronous chat over an authenticated call, without a chat screen
or cloud storage.

Public service addresses and call preferences retain their own DataStore owner.
Saving an address does not contact its server or grant transport authority.

## Ownership

This repository, `ito4a`, is the Android app. The call core, the relay and the
calling contracts live in [`ito`](https://github.com/FixCraft-Inc/ito), and this
app consumes them as `yume4a` consumes YUME: a signed sibling checkout, with the
exact commit recorded in each APK's notices.

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

[Calling security](https://github.com/FixCraft-Inc/ito/blob/main/docs/CALL_SECURITY.md) specifies the local identity,
contact verification and authenticated channel. The [relay contract](https://github.com/FixCraft-Inc/ito/blob/main/docs/CALL_RELAY.md)
defines the separate opaque forwarding service, which [relay/](https://github.com/FixCraft-Inc/ito/blob/main/relay/README.md)
implements as a YUME module for operators. The [component decision](https://github.com/FixCraft-Inc/ito/blob/main/docs/SECURITY_COMPONENT.md)
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
./gradlew --no-daemon --max-workers=2 -PitoYumeRoot=/path/to/yume -PitoCoreRoot=/path/to/ito \
  -PitoProviders=true -PitoProviderRoot="$ito_provider_root" -PyumeProviderVersion="$(cat "$ito_provider_root/VERSION")" \
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
`-PitoYumeRoot` and `-PitoCoreRoot` name the YUME and Ito core checkouts, by
default `../../yume` and `../../ito`. Dirty or unsigned native candidates require
-PitoUnverifiedSource=true for debug tests only. Release refuses that override. The build records native source/library hashes
and packages verified dependency notices. See [notices](THIRD_PARTY_LICENSES.md).

`./build.sh` builds the release APK and signs it with the key named in the
ignored `.release-signing.env`, as [the example](.release-signing.env.example)
shows. It takes no password as an argument. `--apk-dir DIR` signs APKs that
another machine built, so a build host never needs the key. Arguments after `--`
go to Gradle, such as a distribution's provider registration.

Tests cover storage/refusal/lifecycle boundaries, media authorization under missing authentication/encryption,
direct-path consent, service address validation, corrupt saved preferences,
and keeping routing independent of the Emergency preference.
