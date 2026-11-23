# Ito

Ito (糸, Japanese for “thread”) is an Android app for private messages and
audio calls. ITO is the app’s wordmark. The source is licensed under
[AGPL-3.0-or-later](LICENSE). Service operators set their own access rules and
may charge for service.

## Current application

Import a sealed YUME configuration with Android’s document picker or enroll
through a provider, then choose a configuration and network. Ito encrypts the
configuration at rest using Android Keystore and connects through libyume.
While the app is open or its background service is running, it retries
transient failures using the same selection. Disconnect pauses those attempts
until you tap Connect or the process restarts.

The built-in stream and packet echo tests require a destination authorized by
the server. They report payload counts and SHA-256 receipts.

Protected routing remains the default. Failed authentication, unavailable
networks and refused destinations do not select another provider, manual
configuration or direct route. Optional provider sign-in, entitled server
selection and provisioning use the shared provider library.

The [call core](https://github.com/FixCraft-Inc/ito/blob/main/call/README.md)
handles local identities and verified contacts. It carries end-to-end
authenticated calls over the selected YUME connection through a
[call relay](https://github.com/FixCraft-Inc/ito/blob/main/relay/README.md).
The Linux `ito-call` test endpoint uses the same core.

Use Chats to pair contacts, compare safety codes and exchange ratcheted text
through a shared mailbox. It shows delivery/read receipts, per-chat history, local deletion
and the shared auto-delete timer. Contacts and the owner can have photos, kept
encrypted on this phone and never sent. Existing identity-1 contacts must pair again.
All QR codes use one scanner, with an explanation for legacy or unknown input.
Contacts on another relay wait for separate routing support.

Verified, paired contacts call without another QR exchange. An authenticated
incoming screen offers Accept and Decline before the responder joins. The
screen can appear over the phone's lock screen when Android permits full-screen
call alerts, with a ringing notification as fallback. Call and Answer start
audio once the call connects. The [audio adapter](docs/VOICE.md) supplies mono
Opus capture/playback, the choice of phone, speaker, headset or Bluetooth, and
a foreground service that keeps a minimized call running.

By default, the mailbox background service is off. Enable it in Settings to
keep the selected connection open with a persistent notification while Ito is
closed. The service can resume after a reboot or update and attempts to maintain
the connection while the phone sleeps. App lock delays authentication of
newly arrived rings until you unlock Ito. [Android messaging lifecycle](docs/MESSAGING_LIFECYCLE.md)
describes notification privacy, deadlines and interruption limits. Physical
audio, battery/Doze and signed release qualification remain separate. Push is
not implemented. The portable core's
[messenger design](https://github.com/FixCraft-Inc/ito/blob/main/docs/MESSENGER.md)
describes the complete composition.

[Local content](docs/LOCAL_CONTENT.md) keeps per-chat messages, native sessions
and history encrypted on this installation. [App lock](docs/APP_LOCK.md) adds
optional platform authentication in Settings. The portable
[content codec](https://github.com/FixCraft-Inc/ito/blob/main/docs/CONTENT_CHANNEL.md)
also supports synchronous data above an authenticated call.

Public service addresses and call preferences retain their own DataStore owner.
Saving an address does not contact its server or grant transport authority.

## Ownership

This repository, `ito4a`, contains the Android app. The call core, relay,
mailbox and contracts live in [`ito`](https://github.com/FixCraft-Inc/ito).
Build from a signed Ito core checkout. Each APK records the exact source
commit in its notices.

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

Use JDK 21, Android SDK 36.1, NDK 28.2.13676358 and the checked-in Gradle
wrapper. Set `VCPKG_ROOT` to your vcpkg checkout or put it at `../../vcpkg`.
Clone [YUME](https://github.com/FixCraft-Inc/yume) and the
[Ito core](https://github.com/FixCraft-Inc/ito) and import the signers' public
keys into your GnuPG keyring. The wrapper verifies its Gradle 9.3.1 download
against the pinned SHA-256. Dependency versions are in the Gradle version
catalog.

The default build supports manual/self-hosted use and excludes the provider
client and its dependencies:

```sh
./gradlew --no-daemon --max-workers=2 \
  -PitoYumeRoot=/path/to/yume -PitoCoreRoot=/path/to/ito \
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

`-PitoYumeRoot` and `-PitoCoreRoot` default to `../../yume` and `../../ito`.
Release builds require clean, signed native checkouts. For debug testing,
`-PitoUnverifiedSource=true` permits dirty or unsigned native inputs. The
build records native source and library hashes and packages dependency notices. See [notices](THIRD_PARTY_LICENSES.md).

`./build.sh` builds the release APK and signs it with the key named in the
ignored `.release-signing.env`, as [the example](.release-signing.env.example)
shows. It takes no password as an argument. `--apk-dir DIR` signs APKs that
another machine built, so a build host never needs the key. Arguments after `--`
go to Gradle, such as a distribution's provider registration.

Tests cover storage/refusal/lifecycle boundaries, media authorization under missing authentication/encryption,
direct-path consent, service address validation, corrupt saved preferences,
and keeping routing independent of the Emergency preference.
