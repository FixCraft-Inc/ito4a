# Local messages and communication history

`ChatRepository` keeps one encrypted history and one sealed native message
session per contact. It supplies the history callback required by the portable
[message interface](https://github.com/FixCraft-Inc/ito/blob/main/call/include/ito/message.h).
The app exchanges sealed items through the selected YUME connection and an
[MAILBOX/1 service](https://github.com/FixCraft-Inc/ito/blob/main/docs/MAILBOX.md).
Local storage makes no independent connection and grants no transport authority.

## Records and retention

Messages retain their core-assigned 16-byte ID, direction, kind, sender time,
local arrival time, exact UTF-8 text, resend flag and delivery state. Text is
bounded to 4096 bytes under the core's text rules. Profile hints are at most
128 bytes. The timeline orders messages by arrival and labels them with the
sender's time. Call summaries retain the authenticated peer, direction, local
time, final result and a duration of at most 24 hours. They contain no recording.
Only attempts whose peer authenticated can create a call summary.

History stays until local deletion or the shared auto-delete timer expires.
The timer is Off, one day, one week or 30 days, and applies at the next access
using local arrival time. It is a wall-clock policy, not a secure erasure
or trusted-clock deadline. The default is Off. A chat refuses new records
when it reaches 32 MiB of encoded history or 16,384 messages, calls or deletion
records. It never evicts old history to make room. Each chat also bounds its
sealed deposit queue to 128 items and its control queue to 512 actions.

For each peer and direction, duplicate IDs are recognized in retained history
or in deletion records kept for 30 days. A conflicting retained message is
refused. The native ratchet's replay rules are independent. Deleting history
keeps the ratchet, pending sends and delivery work. Removing a contact first
blocks it, then erases its session, history and queued work. A durable retired
inbox record retains the close secret until the selected relay confirms CLOSE.
The blocked trust record remains, so removal cannot silently reaccept that key.

## Delivery and interrupted sends

Queued means a local send is pending or the relay has not accepted its item.
Sent means the relay accepted it, Delivered means the contact stored it, and
Read means the contact opened the chat with read receipts enabled. Delivery
receipts are always enabled. Read receipts default to on. Turning them off
also hides others' read marks locally.

A send records its intent and the prior native outbox IDs before asking the
core to seal. Recovery finds the one new outbox entry, or seals if none exists,
and commits its history record and sealed deposit before clearing the intent.
Each composer operation has a stable local ID, so retry after an ambiguous
session or history save cannot create a second message. A deliberate second
send of identical text uses a new operation ID. Send stays disabled while its
publication is in progress.

The receive callback commits history and receipt/resend actions before the
core commits the receive ratchet. A refused history write leaves delivery
retryable. An ambiguous ratchet save closes the session and requires reopening
its actual durable snapshot. Stored message IDs suppress a repeated history
entry. The app sends TAKEN only after acceptance or a terminal invalid/replayed
item result. It never treats a storage error as acceptance.

After the relay's hold period, the core generates bounded CHECK messages for
unconfirmed outbox entries. Receipt and missing/resend actions survive restart.
CHECK generation persists its timestamp before deposit. A crash or failed
deposit in that interval can delay the next CHECK by one hold period, after
which the core makes it eligible again. The original message remains in the
native outbox until a delivery receipt arrives.

## Encryption and publication

Files live in `noBackupFilesDir/ito-chats/`: `<peer-key>.history`,
`<peer-key>.session`, `settings.bin` and `retired.bin`. The key in a filename
is the exact 32-byte peer communication key in lowercase hexadecimal.
Settings hold the offered pairing name and mutual read-receipt preference.
A session is at most 717,492 bytes and includes outbox plaintext. It receives
the same protection as history.

`EncryptedContentFile` seals these records under the random AES-256 content
key wrapped by [application access](APP_LOCK.md). AES/GCM/NoPadding uses a
fresh platform-generated 96-bit nonce and a 128-bit tag. History and session
use header `ITOH` followed by byte 1, and authenticated domains
`ito/chat-storage/history/1/<peer-key>` and
`ito/chat-storage/session/1/<peer-key>`. Settings use `ITOS` plus byte 1 and
`ito/chat-storage/settings/1`. Retired inboxes use `ITOR` plus byte 1 and
`ito/chat-storage/retired-inboxes/1`. The independent closed binary formats
reject unknown versions, invalid fields, duplicates and trailing bytes.

Every replacement authenticates existing ciphertext, writes a named private
temporary file, syncs it, atomically renames it and syncs its directory.
Directories are 0700 and files 0600. Links and special records are refused.
An abandoned named temporary is removed, never promoted. Failure before
rename preserves the prior record. Failure after rename reports Ambiguous.
Rereading the actual complete record resolves its presence. Unreadable
ciphertext is never replaced with empty state. A missing or corrupt established
ratchet refuses use. This app does not offer an automatic reset or overwrite
path for an unreadable session.

Owned plaintext arrays are cleared after use. Callers close owned read records.
JVM/provider copies, decoded display strings and flash blocks are outside this
best-effort clearing claim. Suspend closes message sessions. Display summaries are read on demand
and leave composition when the app locks. Files and key wrappers are excluded from backup and transfer.

## Migration and deletion

`LocalContentRepository` reads the previous `ito-content/snapshot.bin`,
whose 1 MiB format and encryption domain remain unchanged. Migration copies
all retained messages and calls, including entries older than 30 days, into
per-chat records. It clears the old snapshot only after every destination
commits. A partial migration restarts idempotently. The old codec remains for
that migration and its format tests, not as a second active history owner.

Local message deletion selects the exact ID and direction. Delete history
clears one chat, and Delete all history visits each history in independent
transactions. An error preserves unvisited chats. These actions preserve
identity, trust, configurations and enrollment. They do not delete a contact's
copy or promise flash erasure. This package has no content export/import or
cloud backup. Copying this installation's ciphertext cannot create a usable
cross-installation backup.

Ringing invitations and their PSKs never enter history or session snapshots.
Ring-end records use the first 16 bytes of the public invitation ID for local
history deduplication. They record canceled, declined, busy or missed calls.
A ring end is stored before the receive ratchet commits. Outgoing ring ends
save the ratchet before queuing a sealed deposit: process death between these
writes can lose the end, and the peer's own deadline still releases its room.
