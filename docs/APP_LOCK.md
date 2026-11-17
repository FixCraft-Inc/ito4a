# Optional application lock

The Settings application lock uses Android's strong biometric or device
credential authentication and an authentication-bound Keystore AES key.
Enabling and disabling it require a fresh platform `BiometricPrompt`
`CryptoObject` operation. A callback reporting success without completing that
exact cipher cannot unlock the app. This feature requires Android 11/API 30
or later and a secure device credential. It is optional, and unavailable
authentication never disables an enabled lock.
Prompt preparation initializes the cipher operation without submitting AAD or
content to the authentication-bound key. After the exact authenticated cipher
returns to the foreground, completion submits the wrapper header/domain AAD
and performs the key wrapping or unwrapping. Android Keystore may reject and
cache an error for bytes submitted before per-operation authentication.
Reload validates a disabled key wrapper before publishing Disabled to screen
observers. A malformed or unauthenticated candidate cannot briefly expose
cached private screens while validation is pending.

The Activity uses Android's secure-window flag for its content and recent-app
preview. Leaving a QR screen preserves an already secure Activity window.
This is a platform screen-capture control, separate from encrypted storage.

## Keys and access

`AppLockController` stores one 66-byte wrapper descriptor at
`noBackupFilesDir/ito-access/access.bin`. It contains a version, the enabled
flag, a fresh platform-generated 96-bit GCM nonce and the encrypted 32-byte
random local-content key with a 128-bit tag. The complete header and the domain
`ito/local-access/content-key/1` are authenticated data.

When lock is disabled, Keystore key `ito.access.device.v1` wraps that content
key. When enabled, `ito.access.auth.v1` wraps the same key and requires
per-operation authentication using `AUTH_BIOMETRIC_STRONG` or
`AUTH_DEVICE_CREDENTIAL`. Enabling or disabling replaces one complete wrapper
atomically and syncs the directory. It preserves existing messages/history and
never rotates the communication identity. An interrupted change leaves an old
or new complete wrapper. A post-rename durability error is refused, preserves
the actual disk state and requires an explicit reload before retrying.
After enabling is durable, the previous unauthenticated wrapping key is
retired. A retirement error leaves access unavailable. A later authenticated
unlock retries that retirement before granting access, so an older disabled
wrapper does not retain a usable unauthenticated content-key path.

Identity, manual-configuration and provider keys keep their existing
nonexportable Keystore owners and encryption formats. Their adapters check
`requireAccess()` before new secret access. The application lock therefore
adds an authenticated application access boundary to those legacy stores. It
does **not** convert their existing encryption keys into hardware
authentication-bound keys. The local-content key has the stronger wrapper
property described above. A modified or compromised running process is outside
this application access boundary.

Background messaging and ringing retain the same access boundary. The
[messaging lifecycle](MESSAGING_LIFECYCLE.md) owns sealed buffering,
notification redaction and acceptance after unlock. A relay's class-2 hint
cannot authenticate a new caller while the ratchet keys are unavailable.

## Lifecycle

Enabled lock states are Locked and Unlocked. Every new process starts Locked.

The dedicated incoming-call activity may appear over Android's device lock
screen. It never unlocks Ito or changes key-access state. With Ito locked it
offers only unlock or decline. Opening the full app first dismisses the device
keyguard, then uses Ito's existing authenticated unlock flow.
Backgrounding or explicit Lock clears the cached local-content key, invalidates
pending challenges and denies new identity, content, import and provider
operations. Rotation alone does not count as backgrounding. Unlock is never
saved in an Activity bundle, preferences or a bearer token. Returning from a
canceled prompt remains locked. A late callback from an invalidated prompt
cannot grant access.

When ordinary connections stop on backgrounding, the connection owner joins
the mailbox worker, closes native sessions and clears transient pairing display
state. Cleanup survives Activity destruction. A call's foreground service retains
its connection and mailbox worker from the call's first phase. Enabled app lock refuses new
message access until unlock, while Disabled permits the retained run to process
messages. The opt-in messaging service can retain this connection independently
of audio. Newly arrived sealed items wait for unlock, and notifications hide
names and text. Returning to foreground with Disabled or Unlocked access permits the selected connection
again, unless Disconnect paused it.

A validated provider browser callback may wait in its existing process-owned
operation for Disabled or Unlocked, then recheck `requireAccess()`. Waiting
does not prompt, unlock, borrow keys or read/write provider secrets. It is
cancellable and consumes only the browser attempt's remaining 300-second
budget. Expiry, cancellation or process death requires a fresh browser attempt;
the callback is never saved for later replay.

Android's credential screen may stop the caller Activity. During that explicit
system prompt, `background(challenge)` preserves only its exact pending cipher
while clearing the cached content key and locking enabled access. Completion
is refused until the caller returns to the foreground. Other backgrounding
invalidates pending proof. Activity destruction cancels that Activity's
challenge, including during rotation, so the user can retry a fresh prompt.

An already authorized foreground call may retain its existing native handles
and media keys while the app is locked. Call and Answer request that call's
content while access is available, so its audio can begin after locking
without new key access. Locking does not substitute a route,
provider or contact and does not authorize a new call or content operation.
Hangup, Stop and teardown remain available. The locked/unavailable screen offers
generic End call and Stop connection actions while the foreground audio owner
exists, without contact details or new key access. This remains usable when
Android hides the notification because permission or its channel is disabled.
New local-history/message writes
are refused while locked. An already sealed in-flight publication may finish;
no new content-key borrow is granted. Native call secrets remain borrowed
until normal call teardown, rather than being destroyed underneath the call.

## Failure and recovery

Malformed wrappers, absent or invalidated Keystore keys, unsupported
authentication and I/O errors refuse access. A missing wrapper alongside
retained legacy content, a retained chat record or an existing wrapping-key alias refuses new key creation.
This also prevents a lost wrapper from silently disabling an enabled lock
before any message was stored. Existing ciphertext is preserved.
There is no automatic reset, replacement identity or silent disabling path.
Canceling an enable attempt leaves the existing disabled wrapper unchanged.
Disabling an enabled lock requires a fresh authenticated decrypt, even when
the current process was already unlocked.

An explicit reload reexamines the actual complete descriptor. Restoring the
device authentication facility can permit another authentication attempt.
There is no app-created recovery password or cloud key escrow. Permanently
lost keys cannot be recovered by this app. Clearing app data is an Android
operation that loses the identity/content, not an app-lock repair button.

JVM tests establish wrapper/state, cancellation, restart and refusal behavior
using injected key authorization. They do not establish the platform's actual
biometric/credential implementation. Device prompt, screen lock, process death
and key invalidation need separate Android/device evidence.
