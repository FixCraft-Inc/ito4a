# Messages and incoming calls on Android

Ito uses the portable core's ratchet and call profile 3. A verified contact's
Call action sends an encrypted ring through the selected shared mailbox.
There is no per-call QR exchange. Only `ito_session_receive_ring` can produce
an incoming invitation. Accept consumes that exact handle to create the
responder before JOIN. Decline, busy, cancellation and timeout correlate by
contact and invitation ID. A different invitation cannot stop an attempt.
The core enforces the 45-second ringing deadline and the 90-second room life.
Invitation secrets stay in memory, outside history, intents and saved state.
The incoming owner keeps one pending invitation and admits at most one further
offer for serialized processing. Overflow destroys the offered handle without
replying, leaving the caller's deadline to end its attempt.

When Ito is hidden, authenticated incoming calls request Android's full-screen
call alert. The dedicated, private activity can wake and appear over the device
lock screen, and shows only the invitation it was opened for. It offers Accept
and Decline. Accept dismisses the device keyguard before opening the full app.
A canceled device unlock leaves the invitation pending until its original
deadline. Decline needs no device unlock. A stale notification cannot open or
answer a different invitation. Invitation expiry or cancellation closes the
incoming window.

Android controls whether a full-screen alert appears. On Android 14 and later,
Settings links to Android's "Full-screen incoming calls" access. If access is
denied, Ito keeps the ringing notification, whose tap opens the same isolated
screen. An unlocked, active phone may show a heads-up notification instead.
This permission neither grants app-key access nor starts microphone capture.

## Reachability

"Get messages and calls while Ito is closed" is off by default. Enabling it
from the visible app starts a nonsticky foreground service with a permanent
notification. On Android 14 and later it declares `specialUse`: a user-enabled
persistent encrypted mailbox connection without push. It does not capture
audio. Microphone access belongs to the separate, user-started audio service.
Android notification permission and enabled notification channels are required
to enable background reachability. Refusal leaves the setting off.

The service retains the selected transport and network policy and allows its
bounded reconnection. Disconnect pauses automatic attempts until Connect or
process restart. Turning reachability off stops ordinary background work.
Process death, force-stop, reboot, Doze and vendor battery policy can interrupt
reachability. There is no boot receiver, automatic exemption or push fallback.
The setting resumes a service only when the person opens Ito again.

## Lock and notification privacy

App lock still denies new secret access and clears the content key on
backgrounding. An existing mailbox stream may retain inbox subscriptions and
bounded sealed items, without acknowledging or decrypting them. Newly arrived
sealed traffic produces only a generic unlock notice. Its relay class does
not authenticate a caller and cannot cause ringing. After unlock Ito verifies
the items, stores messages before advancing receive ratchets, and rings only
for an invitation that is still valid. A lost transport cannot acquire new
credentials while locked and waits for unlock.

An invitation authenticated before locking may remain pending until its
deadline. The locked screen and notification hide the contact and offer unlock
or decline, never acceptance. Unlock does not extend its deadline. An already
authorized audio call retains the existing borrower behavior in APP_LOCK.

With access available, message notifications may show the contact and text.
Settings can hide text everywhere. Notifications use private visibility and
a generic public version. Backgrounding with app lock enabled immediately
removes previously visible message text and caller labels. No invitation,
message body, identity key or contact ID is placed in a PendingIntent. Incoming
screen and decline intents carry only an opaque, process-local action token.

Phone battery, Doze, physical audio, ARM64 runtime and signed/minified release
checks are separate qualification gates. Emulator checks do not close them.

The public preferences snapshot is schema 3. Schemas 1 and 2 migrate with
background reachability off and notification text on, preserving routing,
audio and saved services. Wrong Boolean types or unknown fields are refused.
No authentication state or notification-action token is persisted there.
