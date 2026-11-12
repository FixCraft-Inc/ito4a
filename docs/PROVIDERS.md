# Optional provider enrollment

With `-PitoProviders=true`, Ito uses the independent `provider-library` source project from yume4a. The
single protocol contract is yume4a’s `docs/PROVIDERS.md`; the reusable library’s
`README.md` owns its interfaces and version. Ito supplies encrypted storage,
public-libyume validation, connection lifetime and UI adapters. Neither
application links the other application. The library version Ito accepts is
`itoProviderLibraryVersion` in `gradle.properties`, its one owner. It, provider
wire version 1 and the experimental libyume ABI are independent version axes.

## Enrollment and authority

The user explicitly chooses a provider. A distribution can suggest one through
the reviewed build properties described below. The Service page then names the
provider and its sign-in host before anything is contacted, and Continue adds
it. Otherwise the user enters a provider HTTPS origin and, when needed, accepts
its exact issuer. Ito requests the `relay` role for named application data.
yume4a requests `vpn`. The shared client checks that role in the descriptor,
catalog, grant and persisted selection. A provider cannot change Ito’s routing
preferences, the manual catalog, local communication identity or peer trust.
Provider enrollment supplies infrastructure access independently of the local call core and communication identity.

The Add service form uses one window-bounded scroll area for its title,
fields, consent and actions. Text keeps the configured system size. Back,
outside dismissal and Cancel discard the unsaved form without enrollment.

Account authorization uses PKCE S256 in a Custom Tab of the user's browser, or
the browser itself when it has no Custom Tab support. The shared owner checks
TLS, verified app links, registration, issuer, state, redirect, audience, grant
bindings and the actual native credential identity. No WebView, client secret
or production registration is embedded. Without an explicit registration,
browser enrollment is unavailable, and manual use remains available.

Browser providers must support device-bound enrollment, as yume4a's
`docs/PROVIDERS.md` defines it.
Signing in binds the account tokens to a fresh Android Keystore key, and the
device fetches each grant's opening code itself. The ordinary flow is: choose
the provider, sign in, choose a server. Ito then provisions, validates and
selects it, ready to connect. There is no opening code to copy and no file to
import.

Returning from the browser may find the optional app lock locked. The shared
client first validates and consumes the exact callback, then waits for Ito's
access hook before reading or writing provider secrets. Only Disabled or an
explicit authenticated Unlocked state permits continuation, followed by the
normal store access checks. The original authorization attempt's remaining
300-second wall/monotonic budget bounds this process-local wait. Cancel,
removal, shutdown or process death cannot preserve the callback for replay.
Timeout requires a new browser attempt. No lock exemption or callback storage
is added.

Catalogs contain entitled servers. The Service page lists each by the
provider-declared country's flag and name in the user's language, then its label.
Unknown country stays "Location not given" with a plain globe. The flag is
decoration, so only the name reaches a screen reader. Server addresses appear in
Advanced mode. The service's details show the installed server's address, with a
note that it is not proof of where traffic leaves. Each provider row's menu holds
Refresh servers, Service details and Remove service, and these labels and steps
match yume4a's.
Choosing a server provisions and selects that exact service/server authority. Credentials are validated through Ito’s qualified
JNI adapter to the public C ABI before publication. Ito requires exactly the
`ito-call` native `stream` service and no local adapters for a provider relay
grant. The operator must route that named service to the separately deployed
call relay module. A general-purpose `tcp`/`udp` VPN kit cannot become an Ito
provider grant. The shared validator delegates this application policy to
`ItoProviderNative`, while retaining schema, credential containment and exact
server/identity binding. Manual imports keep their separate contract.
Only an anonymous provider's opening code is entered by hand, as a process-local
prompt. Provider bundles never enter the manual
configuration catalog. [Secret storage](SECRET_STORAGE.md) owns Ito’s
Keystore, backup, replacement, recovery and deletion behavior.

## Service and independent clocks

The same connection owner serves explicit manual and provider selections.
Each provider run reacquires the exact selected lease and stages private files
in a provider-only root. Network selection follows the same rules as manual
connections: the default captures the application's system default, including
a VPN, for socket binding and bounded DNS. Explicit physical choices are refused
while a VPN applies to Ito's default route. Default-route change or loss and selected-network loss
end the run without fallback. There is no automatic endpoint,
provider, manual or direct-route fallback.

API/account expiry and transport expiry are independent. API refresh rotates
account credentials without silently granting transport authority. Rotation,
known revocation, provider removal, clock rollback or transport expiry
invalidates the captured run permit. Without trusted time only its monotonic
deadline applies, as yume4a's `docs/PROVIDER_CLIENT.md` "Trusted time" states.
Ito stops the native run, joins every JNI borrower, destroys its handle, then
closes the credential session and permit.
A new connection requires an explicit action and a valid exact lease.

A lease lasts as long as the provider grants, which can be as short as an
hour. Ito renews it on its own, so the card does not show its times. Choosing a
server only records it; Connect acquires its lease, a starting connection uses
any live lease at once, and a running relay reconnects to a newly chosen server.
At half its lease a running relay prepares a fresh grant while it still serves
and reconnects only once that grant exists, never during a call. A call that
outlasts the lease still ends with it. When the issuer or provider refuses the
sign-in itself, the shared client forgets the tokens and their grants. The row
then reads Signed out and offers Sign in with browser; afterwards Connect
acquires a fresh grant for the same chosen server.

## Removal and manual use

Local removal works offline. The shared owner persists a provider tombstone
before deleting encrypted records and attempts bounded remote revocation.
The UI identifies local removal; remote account deletion is a separate action.
A failed remote request does not retain hidden retry credentials. Removal
preserves unrelated providers, the independent manual store and preferences.
An expired or removed selected provider never becomes a manual selection. After
removal the Service page shows no route and no Connect until the user chooses
a server or a configuration file.

Manual document import and explicit configuration selection work without any
provider, yume4a installation or system VPN permission. The protected routing
policy and explicit consent for any future direct path remain in force.
Authentication bootstrap may itself be blocked; enrollment provides no claim
that a blocked issuer is reachable.

## Build registration and qualification

The default `-PitoProviders=false` build includes manual configuration and calls,
but excludes provider source, the shared library, AppAuth, browser queries,
callback activities and the qualification-only provider CA. It does not need a
provider checkout, registration, version or source hash. Supplying registration
properties while providers are disabled fails configuration.

The minimal build never opens retained provider records. If any provider storage
root exists, Connect refuses with `providers_disabled` until the user explicitly
selects a manual configuration during that process. It preserves those records.
After process death a new explicit selection is required. This conservative
boundary avoids treating unreadable or previously selected provider authority as
permission to use a saved manual configuration. Switching builds does not copy,
delete or rewrite provider credentials.

Use `-PitoProviders=true` and
`-PitoProviderRoot=/absolute/path/to/yume4a/provider-library` with the exact
reviewed source content. The build requires the library version that
`gradle.properties` pins. For an
explicit registration, provide both `-PitoProviderClientId=...` and
`-PitoProviderCallback=https://HOST/ito/oauth/callback`. The callback host must
publish the actual APK package/signer association. Partial or malformed
registrations fail the build. Defaults contain no registration.
A distribution may also suggest one provider with
`-PitoSuggestedProvider=https://ORIGIN` and, for browser sign-in,
`-PitoSuggestedIssuer=https://ISSUER`. Both must be canonical HTTPS origins,
and an issuer needs a registered callback. A suggestion grants nothing: the
user still chooses it and every contract check still applies. Defaults suggest
none.

The enabled source set supplies `ConnectionServices`, a narrow local authority
adapter. `ConnectionController` receives only the selected authority, staged
configuration, credential directory and captured permit. The shared owner still
validates every schema, mandatory Ito service, credential identity and lease.
Manual runs use the same transport owner without a provider runtime dependency.
The enabled UI retains manual and unrelated-provider use.

When the optional app lock is active, new provider record reads, writes, removal,
selection and native opening require unlocked access. Existing captured permits
remain independently valid while an already authorized foreground call continues.
Locking does not imply provider revocation. An actual grant expiry or revocation
still stops that call.

Only the enabled debug build trusts a user-installed qualification CA, and only for
four exact loopback fixture hostnames. Release uses normal system trust.
Private acceptance records bind sources, module contents, APKs, native
libraries, signer, fresh fixtures and payload hashes. Physical-device,
Wi-Fi/cellular handover, ARM64 runtime and signed-release readiness require
separate evidence.
