# Optional provider enrollment

Ito uses the independent `provider-library` source project from yume4a. The
single protocol contract is yume4a’s `docs/PROVIDERS.md`; the reusable library’s
`README.md` owns its interfaces and version. Ito supplies encrypted storage,
public-libyume validation, connection lifetime and UI adapters. Neither
application links the other application. Library version 0.1.0, provider wire
version 1 and the experimental libyume ABI have independent version axes.

## Enrollment and authority

The user explicitly enters a provider HTTPS origin and, when needed, accepts
its exact issuer. Ito requests the `relay` role for named application data.
yume4a requests `vpn`. The shared client checks that role in the descriptor,
catalog, grant and persisted selection. A provider cannot change Ito’s routing
preferences, the manual catalog, local communication identity or peer trust.
No calling or communication identity implementation is supplied here.

The Add provider form uses one window-bounded scroll area for its title,
fields, consent and actions. Text keeps the configured system size. Back,
outside dismissal and Cancel discard the unsaved form without enrollment.

Account authorization uses the external browser and PKCE S256. The shared
owner checks TLS, verified app links, registration, issuer, state, redirect,
audience, grant bindings and the actual native credential identity. No WebView,
client secret or production registration is embedded. Without an explicit
registration, browser enrollment is unavailable; manual use remains available.

Catalogs contain entitled servers. The user provisions and selects an exact
service/server authority. Credentials are validated through Ito’s qualified
JNI adapter to the public C ABI before publication. The opening code is a
separate process-local handoff. Provider bundles never enter the manual
configuration catalog. [Secret storage](SECRET_STORAGE.md) owns Ito’s
Keystore, backup, replacement, recovery and deletion behavior.

## Connections and independent clocks

The same connection owner serves explicit manual and provider selections.
Each provider run reacquires the exact selected lease, stages private files
in a provider-only root and captures one non-VPN Android Network. Socket
binding and bounded DNS use that Network. There is no automatic endpoint,
provider, manual or direct-route fallback.

API/account expiry and transport expiry are independent. API refresh rotates
account credentials without silently granting transport authority. Rotation,
known revocation, provider removal, loss of trusted time or transport expiry
invalidates the captured run permit. Ito stops the native run, joins every JNI
borrower, destroys its handle, then closes the credential session and permit.
A new connection requires an explicit action and a valid exact lease.

## Removal and manual use

Local removal works offline. The shared owner persists a provider tombstone
before deleting encrypted records and attempts bounded remote revocation.
The UI identifies local removal; remote account deletion is a separate action.
A failed remote request does not retain hidden retry credentials. Removal
preserves unrelated providers, the independent manual store and preferences.
An expired or removed selected provider never becomes a manual selection.

Manual document import and explicit configuration selection work without any
provider, yume4a installation or system VPN permission. The protected routing
policy and explicit consent for any future direct path remain in force.
Authentication bootstrap may itself be blocked; enrollment provides no claim
that a blocked issuer is reachable.

## Build registration and qualification

Use `-PitoProviderRoot=/absolute/path/to/yume4a/provider-library` with the exact
reviewed source content. The build requires library version 0.1.0. For an
explicit registration, provide both `-PitoProviderClientId=...` and
`-PitoProviderCallback=https://HOST/ito/oauth/callback`. The callback host must
publish the actual APK package/signer association. Partial or malformed
registrations fail the build. Defaults contain no registration.

Only the debug build trusts a user-installed qualification CA, and only for
four exact loopback fixture hostnames. Release uses normal system trust.
Private acceptance records bind sources, module contents, APKs, native
libraries, signer, fresh fixtures and payload hashes. Physical-device,
Wi-Fi/cellular handover, ARM64 runtime and signed-release readiness require
separate evidence.
