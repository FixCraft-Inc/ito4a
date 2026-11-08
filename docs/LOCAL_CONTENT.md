# Local messages and communication history

`LocalContentRepository` is the Android storage adapter for communication
content above Ito's authenticated portable application channel. It stores
messages and final call summaries, independently of account enrollment,
provider grants, contact trust and invitation replay state. It makes no network
request and supplies no cloud synchronization or server history API. Relay
buffers carry opaque transient bytes under the separate relay contract.

## Records and limits

A message contains a 16-byte random application message ID, the exact 32-byte
authenticated peer communication key, incoming/outgoing direction, local Unix
receipt/send time and 1..4096 bytes of valid UTF-8 text. C0, DEL and C1 controls
are refused, except tab and newline, matching the portable channel. The wire
message ID and acknowledgment are defined by the portable application channel.
An acknowledgment is distinct from a storage receipt. This repository does
not infer delivery or a read receipt from a successful local write.

For a peer and direction, the same message ID and identical body are
idempotent. Reusing that ID with a different body is refused. Duplicate
recognition ends when its local record is deleted or evicted. The authenticated
channel's own per-session replay protection remains independent. There is no
offline delivery, relay mailbox or automatic retransmission across calls.

A call summary contains a random local record ID, peer key, initiator flag,
local Unix time, final typed result and monotonic duration of 0..24 hours. It
uses native `ito_status` results, or application result -1000 for a local
content/audio-adapter failure. It
contains no audio recording, provider/account identifier, invitation secret,
transport key or endpoint address. The application records only completed
attempts with an authenticated peer. A locked app refuses new history writes,
so a call that finishes while locked need not appear in history.

At most 256 messages and 256 call summaries remain in one snapshot. The
plaintext encoding is limited to 1 MiB, so maximum-sized messages may evict
older entries before the count limit. New entries evict oldest insertion-order
entries. Records older than 30 days are removed at the next repository access.
This time policy uses the local wall clock and is not a guaranteed timer or
trusted-clock erasure deadline. Count and byte bounds hold even across clock
rollback. Text and call-summary retention are separate from the core's contact
and spent-invitation retention rules.

## Encryption and publication

The single snapshot is
`noBackupFilesDir/ito-content/snapshot.bin`. The random AES-256 content key is
wrapped by [application access](APP_LOCK.md), independently of the identity,
provider and manual-configuration encryption keys. AES/GCM/NoPadding uses a
fresh platform-generated 96-bit nonce and a 128-bit tag. Version 1 has the
five-byte header `ITOC` followed by byte 1 and authenticates the fixed domain
`ito/content-storage/snapshot/1`. The closed binary plaintext format has one
version, bounded message/call counts and exact bounded lengths. Unknown
versions, malformed UTF-8, invalid booleans, duplicate IDs and trailing bytes
are refused before publication.

Each operation authenticates the current snapshot. A mutation builds a private
candidate, writes only ciphertext to the named temporary file, syncs it,
atomically renames it and syncs its directory. Files are 0600 and directories
0700. Links and special files are refused. Startup removes only the named
temporary candidate and never promotes it. A failure before rename preserves
the old snapshot. A failure after rename reports `Ambiguous`; rereading actual
durable state resolves what is present. No error becomes an empty successful
history or permission to overwrite unreadable bytes.

Owned plaintext and key byte arrays are cleared after use. Read methods return
owned records that callers close when finished. JVM/provider copies, decoded
strings, flash blocks and a compromised process are outside this best-effort
clearing claim. The application backup/transfer exclusions apply to both the
content snapshot and its key wrapper.

## Deletion and export

Authenticated deletion works offline. `deleteMessage` selects the exact
message ID, peer key and direction. `deletePeer` removes only that peer's
messages and summaries. `clear` removes all current local messages/history.
These operations preserve communication identity, contact trust, manual kits
and provider enrollment. They rewrite the authenticated snapshot and cannot
erase old flash blocks, external copies or another peer's data. They imply no
remote deletion or revocation.

This package implements no content export or import. It never sends plaintext
history through an Android share Intent or includes content keys in a backup.
A future portable encrypted export needs a separate password/key, format,
consent and recovery contract. Copying this installation's ciphertext alone
does not provide a usable cross-installation backup.

The repository APIs support reusable message storage. A complete chat screen,
notification delivery and asynchronous messaging service are separate work.
