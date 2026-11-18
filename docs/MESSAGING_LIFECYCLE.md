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
and Decline. Accept dismisses the device keyguard, asks for the microphone if
Android has not granted it, then opens the full app. Refusing the microphone
keeps the call ringing until its deadline.
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
from the visible app starts a sticky foreground service with a permanent
notification. On Android 14 and later it declares `specialUse`: a user-enabled
persistent encrypted mailbox connection without push. It does not capture
audio. Microphone access belongs to the separate call service, which starts
with a call the person makes or answers.
Android notification permission and enabled notification channels are required
to enable background reachability. Refusal leaves the setting off.

Enabling it also asks Android, in the system's own dialog, to stop restricting
Ito's battery use. Ito has no push service to wake it, so a restricted phone may
delay its keep-alive or refuse to restart it. While the restriction remains,
Settings shows a row that asks again. Declining keeps the setting on.

The service retains the selected transport and network policy and allows its
bounded reconnection. A network that appears or passes validation ends the
wait between automatic attempts. Disconnect pauses automatic attempts until
Connect or process restart, and the service is then not restarted. Turning
reachability off stops ordinary background work.

The service comes back by itself. Android restarts it after it kills the
process, and a receiver starts it after the phone boots and after Ito is
updated. Each start checks the setting, notification access and app access
before connecting. With app lock on, the connection's settings stay sealed
until the person unlocks Ito, so after a reboot Ito posts a notice to unlock
instead. If Android refuses a restart, Ito posts a notice to open it. Neither
notice names a contact. Force-stop, vendor battery policy and a restriction
the person keeps can still interrupt reachability.

A sleeping phone stops its timers. While the service runs, an inexact
allow-while-idle alarm wakes the phone at the mailbox PING interval, four
minutes. Each wake asks the mailbox stream for one PING and keeps the CPU
awake until its PONG, or at most ten seconds when the connection must
recover. The mailbox client times its PING with a clock that counts sleep, so
the first turn after any wake sends one that is overdue. No message is
polled: the relay pushes each item on the open stream.

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

Each notice looks different in the shade. The background connection says so
in its title, with a connection icon. An incoming call shows the caller and a
call icon. A call that rang here and ended unanswered leaves a missed-call
notice when Ito is not on screen. The chat's history records it either way.

With access available, message notifications may show the contact and text.
Settings can hide text everywhere. Notifications use private visibility and
a generic public version. Backgrounding with app lock enabled immediately
removes previously visible message text, missed-call names and caller labels. No invitation,
message body, identity key or contact ID is placed in a PendingIntent. Incoming
screen and decline intents carry only an opaque, process-local action token.

Phone battery and data use per hour, Doze and vendor battery policy, physical
audio, ARM64 runtime and signed/minified release checks are separate
qualification gates. Emulator checks do not close them.

The public preferences snapshot is schema 3. Schemas 1 and 2 migrate with
background reachability off and notification text on, preserving routing,
audio and saved services. Wrong Boolean types or unknown fields are refused.
No authentication state or notification-action token is persisted there.
