# Ito

Ito (糸, Japanese for “thread”) is an Android app for private messages and
audio calls. ITO is the app’s wordmark. It connects through
[YUME](https://github.com/FixCraft-Inc/yume), with a configuration file you
import or a provider you sign in to. The source is licensed under
[AGPL-3.0-or-later](LICENSE). Service operators set their own access rules and
may charge for service.

This repository, `ito4a`, holds the Android app. The call and messaging core,
the operator's call relay and mailbox, and their contracts are in the
[Ito core](https://github.com/FixCraft-Inc/ito).

## What the app does today

### Connecting

Import a sealed YUME configuration with Android’s document picker, or enroll
through a provider, then choose a configuration and a network. Ito encrypts
the configuration at rest with Android Keystore and connects through libyume.
While the app is open or its background service is running, it retries
transient failures with the same selection. Disconnect pauses those attempts
until you tap Connect or the process restarts.

Protected routing is the default. Failed authentication, unavailable networks
and refused destinations never make Ito switch to another provider, a manual
configuration or a direct route. Optional provider sign-in, entitled server
selection and provisioning use the shared provider library.

The built-in stream and packet echo tests need a destination that the server
authorizes. They report payload counts and SHA-256 receipts.

Public service addresses and call preferences are kept in their own DataStore
entry. Saving an address does not contact its server or grant transport
authority.

### Chats

Use Chats to pair contacts, compare safety codes and exchange ratcheted text
through a shared mailbox. A chat shows delivery and read receipts and keeps its
own history. You can delete messages on this phone and set an auto-delete
timer that both contacts share. You can add photos for your contacts and for
yourself. They stay encrypted on this phone and are never sent. Existing
identity-1 contacts must pair again. Every QR code goes through one scanner,
which explains legacy or unknown input. Contacts on another relay wait for separate routing support.

### Calls

Verified, paired contacts call each other without another QR exchange. An
authenticated incoming screen offers Accept and Decline before the answering
phone joins the call. When Android permits full-screen call alerts, that
screen can appear over the phone's lock screen, with a ringing notification
as the fallback. Call and Answer start audio once the call connects. The
[audio adapter](docs/VOICE.md) supplies mono Opus capture and playback, the
choice of phone, speaker, headset or Bluetooth, and a foreground service that
keeps a minimized call running.

The [call core](https://github.com/FixCraft-Inc/ito/blob/main/call/README.md)
handles local identities and verified contacts. It carries end-to-end
authenticated calls over the selected YUME connection through a
[call relay](https://github.com/FixCraft-Inc/ito/blob/main/relay/README.md).
The Linux `ito-call` test endpoint uses the same core. The portable
[content codec](https://github.com/FixCraft-Inc/ito/blob/main/docs/CONTENT_CHANNEL.md)
also carries synchronous data on top of an authenticated call.

### While Ito is closed

The background mailbox service is off by default. Turn it on in Settings to
keep the selected connection open, with a persistent notification, while Ito
is closed. The service can resume after a reboot or an update and tries to keep
the connection while the phone sleeps. App lock delays authentication of newly
arrived rings until you unlock Ito.
[Android messaging lifecycle](docs/MESSAGING_LIFECYCLE.md) describes
notification privacy, deadlines and interruption limits. Push is not
implemented.

### Local data and app lock

[Local content](docs/LOCAL_CONTENT.md) keeps per-chat messages, native
sessions and history encrypted on this installation. [App lock](docs/APP_LOCK.md)
adds optional platform authentication in Settings.

### Limits

Physical audio, battery and Doze behavior, and signed release qualification
are checked separately. The public YUME ABI is experimental, and calling needs
its own compatibility and device checks. The core's
[messenger design](https://github.com/FixCraft-Inc/ito/blob/main/docs/MESSENGER.md)
describes the complete composition.

## Related projects

Ito is separate from `yume4a`, the general-purpose YUME VPN application. It
embeds the YUME core through its public C ABI, so it needs no `VpnService`,
no YUME app installation and no provider account. YUME and BaseFWX remain
independent projects. Ito's UI text and visual identity live in this
repository, and Ito does not change or fork the shared YUME Android catalogs.

## Documentation

| Page | What it covers |
| --- | --- |
| [Architecture and security](docs/ARCHITECTURE.md) | The product boundaries |
| [Native integration](docs/NATIVE.md) | Build provenance, lifecycle and network binding |
| [Secret storage](docs/SECRET_STORAGE.md) | Backup exclusion, publication, corruption and crash recovery |
| [Provider enrollment](docs/PROVIDERS.md) | The shared-library binding and Ito’s account, selection and connection adapters |
| [Local content](docs/LOCAL_CONTENT.md) | Encrypted per-chat messages, history and photos |
| [App lock](docs/APP_LOCK.md) | Optional platform authentication and key access |
| [Messaging lifecycle](docs/MESSAGING_LIFECYCLE.md) | Incoming calls, background reachability and notification privacy |
| [Foreground audio](docs/VOICE.md) | Capture, playback, codecs and audio routes |
| [Calling security](https://github.com/FixCraft-Inc/ito/blob/main/docs/CALL_SECURITY.md) | The local identity, contact verification and authenticated channel |
| [Relay contract](https://github.com/FixCraft-Inc/ito/blob/main/docs/CALL_RELAY.md) | The separate opaque forwarding service, which [relay/](https://github.com/FixCraft-Inc/ito/blob/main/relay/README.md) implements as a YUME module for operators |
| [Component decision](https://github.com/FixCraft-Inc/ito/blob/main/docs/SECURITY_COMPONENT.md) | The maintained native security component and its host-only evaluation |
| [Notices](THIRD_PARTY_LICENSES.md) | Dependency licenses and the notices each APK carries |

## Building

### Requirements

- JDK 21, Android SDK 36.1 and NDK 28.2.13676358.
- The checked-in Gradle wrapper. It verifies its Gradle 9.3.1 download against
  the pinned SHA-256.
- vcpkg. Set `VCPKG_ROOT` to your vcpkg checkout or put it at `../../vcpkg`.
- Checkouts of [YUME](https://github.com/FixCraft-Inc/yume) and the
  [Ito core](https://github.com/FixCraft-Inc/ito). `-PitoYumeRoot` and
  `-PitoCoreRoot` name them, and default to `../../yume` and `../../ito`.

Dependency versions are in the Gradle version catalog.

### Signed source checkouts

Before each build, Gradle runs YUME's
`tools/build_android_shared_abi.sh --verify-source-only` and the Ito core's
`tools/build_call_core.sh --verify-source-only`. Both refuse a dirty checkout
and check the signature of its HEAD commit with `git verify-commit`. YUME's
check also verifies a pinned signed baseline commit that HEAD must descend
from, so clone YUME with its history rather than as a shallow clone.

Import the signing key into your GnuPG keyring:

```sh
curl -fsSL https://github.com/F1xGOD.gpg | gpg --import
```

That file holds more than one key. Current commits on `main` of both YUME and
the Ito core are signed with the ed25519 key whose fingerprint is
`9672 78FF 6FA4 36F5 04CB  B005 8A15 88B5 E259 8DB1`. Compare it with the
output of `gpg --fingerprint 8A1588B5E2598DB1`, and with the key that
`git -C /path/to/ito verify-commit HEAD` reports.

Release builds require clean, signed native checkouts. For debug testing,
`-PitoUnverifiedSource=true` permits dirty or unsigned native inputs.

### Default build

The default build supports manual and self-hosted use, and leaves out the
provider client and its dependencies:

```sh
./gradlew --no-daemon --max-workers=2 \
  -PitoYumeRoot=/path/to/yume -PitoCoreRoot=/path/to/ito \
  :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

### Build with provider sign-in

Generic provider sign-in is enabled explicitly. This build also keeps manual
use and unrelated providers, and the flag does not grant account access. It is
a build choice, separate from UI presentation. Settings offers Simple and
Advanced presentation, which changes which details are shown, never routing or
authority.

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

Browser enrollment also needs the explicit client and callback registration
and the signer association that [provider enrollment](docs/PROVIDERS.md)
describes. Provisioned configuration stays inside the app. Device-bound
browser enrollment delivers and validates the sealed kit automatically after
server selection. Anonymous providers and manual imports may still require an
opening code.

### Provenance and notices

The build records native source and library hashes and packages dependency
notices. Each APK records the exact source commit in its notices. See
[notices](THIRD_PARTY_LICENSES.md).

### Signed release APKs

`./build.sh` builds the release APK and signs it with the key named in the
ignored `.release-signing.env`, as [the example](.release-signing.env.example)
shows. It takes no password as an argument. `--apk-dir DIR` signs APKs that
another machine built, so a build host never needs the key. Arguments after
`--` go to Gradle, such as a distribution's provider registration.

### Tests

The tests cover storage, refusal and lifecycle boundaries, media
authorization when authentication or encryption is missing, direct-path
consent, service address validation, corrupt saved preferences, and keeping
routing independent of the Emergency preference.
