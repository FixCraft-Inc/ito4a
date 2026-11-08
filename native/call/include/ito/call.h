/* Copyright (C) 2026 FixCraft Inc. AGPL-3.0-or-later. */

/*
 * Ito's portable call core: local communication identity, contact trust,
 * one-use invitations and the authenticated end-to-end call data path of
 * docs/CALL_SECURITY.md, over a byte stream to the relay of docs/CALL_RELAY.md.
 *
 * Every handle is opaque. Every function returns a typed status and never
 * lets an exception escape. Buffers are bounded by their size arguments.
 * Fixed-size structures start with struct_size, which the caller sets to the
 * size it was compiled with. The core refuses a smaller size.
 *
 * Ownership and lifetime:
 *   ito_context  outlives every store, invitation and call created from it.
 *   ito_store    outlives every invitation and call that borrows it.
 *   ito_call     is run by exactly one ito_call_run. The other call functions
 *                may be used from other threads while it runs. Destroy only
 *                after ito_call_run has returned.
 * A destroy function refuses (ITO_STATUS_WRONG_STATE) while another handle
 * still borrows it (a store's invitations and calls, a context's stores), or
 * while its call still runs, and then leaves it intact. It must not overlap
 * any other function on the same handle: the caller orders those itself.
 *
 * Callbacks (persistence, transport) run on the thread that called into the
 * core. They must not call any function of the handle that invoked them.
 * Such a call returns ITO_STATUS_REENTRANT.
 */

#ifndef ITO_CALL_H
#define ITO_CALL_H

#include <stddef.h>
#include <stdint.h>

#if defined(__GNUC__)
#define ITO_CALL_API __attribute__((visibility("default")))
#else
#define ITO_CALL_API
#endif

#ifdef __cplusplus
#define ITO_CALL_NOEXCEPT noexcept
extern "C" {
#else
#define ITO_CALL_NOEXCEPT
#endif

/* The version of this C interface, independent of the call protocol. */
#define ITO_CALL_INTERFACE_VERSION 1u

typedef enum ito_status {
    ITO_STATUS_OK = 0,
    /* Caller misuse: a null pointer, a short struct_size or a bad length. */
    ITO_STATUS_INVALID_ARGUMENT = -1,
    ITO_STATUS_BUFFER_TOO_SMALL = -2,
    /* The handle is not in a state that permits the operation. */
    ITO_STATUS_WRONG_STATE = -3,
    ITO_STATUS_REENTRANT = -4,
    /* User or peer input that is not the canonical encoding. */
    ITO_STATUS_MALFORMED = -5,
    ITO_STATUS_UNSUPPORTED = -6,
    /* A key that is unknown, unverified, blocked, replaced or unexpected. */
    ITO_STATUS_UNTRUSTED = -7,
    /* A signature, confirmation, PSK or TLS policy check failed. */
    ITO_STATUS_AUTHENTICATION_FAILED = -8,
    ITO_STATUS_EXPIRED = -9,
    /* Clock observation regressed, a deadline is uncertain, or persisted
     * uncertainty remains. Wall-clock catch-up/reopen does not clear it. */
    ITO_STATUS_CLOCK_REGRESSED = -10,
    ITO_STATUS_ALREADY_SPENT = -11,
    /* A store or invitation limit. Nothing was evicted. */
    ITO_STATUS_CAPACITY = -12,
    ITO_STATUS_NOT_FOUND = -13,
    ITO_STATUS_CORRUPT_STATE = -14,
    /* Persistence refused the snapshot. The previous state is still current. */
    ITO_STATUS_STORAGE_FAILED = -15,
    /* Persistence may or may not have published. The store refuses further
     * mutation until it is reopened from what storage actually holds. */
    ITO_STATUS_STORAGE_AMBIGUOUS = -16,
    /* One call attempt is already active for this store. */
    ITO_STATUS_BUSY = -17,
    ITO_STATUS_WOULD_BLOCK = -18,
    ITO_STATUS_TIMEOUT = -19,
    ITO_STATUS_CANCELED = -20,
    ITO_STATUS_DECLINED = -21,
    ITO_STATUS_PROTOCOL_FAILURE = -22,
    /* The peer ended the call. ito_call_status says whether its reason is
     * attributable to the authenticated contact. */
    ITO_STATUS_PEER_ENDED = -23,
    ITO_STATUS_RELAY_REFUSED = -24,
    ITO_STATUS_TRANSPORT_FAILED = -25,
    /* The transport's authenticated relay service differs from the call's. */
    ITO_STATUS_AUTHORITY_MISMATCH = -26,
    ITO_STATUS_AUTHORITY_ENDED = -27,
    ITO_STATUS_RESOURCE_LIMIT = -28,
    /* OpenSSL or the random source failed. */
    ITO_STATUS_DEPENDENCY_FAILURE = -29,
    ITO_STATUS_OUT_OF_MEMORY = -30,
    ITO_STATUS_INTERNAL = -31
} ito_status;

typedef struct ito_context ito_context;
typedef struct ito_store ito_store;
typedef struct ito_invitation ito_invitation;
typedef struct ito_call ito_call;

#define ITO_KEY_BYTES 32u
#define ITO_FINGERPRINT_CHARS 64u
/* "ito-contact:1:" and 43 unpadded base64url characters. */
#define ITO_CONTACT_CODE_CHARS 57u
#define ITO_MAX_NAME_BYTES 128u
#define ITO_MAX_SERVICE_BYTES 64u
#define ITO_MAX_INVITATION_BYTES 512u
#define ITO_MAX_DATA_BYTES 16384u

/* The OpenSSL release this core was linked with, NUL terminated. */
ITO_CALL_API ito_status ito_component_version(char* out, size_t out_size)
    ITO_CALL_NOEXCEPT;
ITO_CALL_API const char* ito_status_name(ito_status status) ITO_CALL_NOEXCEPT;

/* A private OpenSSL library context with its default provider loaded. */
ITO_CALL_API ito_status ito_context_create(ito_context** out) ITO_CALL_NOEXCEPT;
ITO_CALL_API ito_status ito_context_destroy(ito_context* context)
    ITO_CALL_NOEXCEPT;

/* Lowercase hex SHA-256 of "ito/contact-identity/1", zero, key: exactly 64
 * characters followed by a NUL, so out_size must be at least 65. */
ITO_CALL_API ito_status ito_fingerprint(ito_context* context,
                                        const uint8_t key[ITO_KEY_BYTES],
                                        char* out,
                                        size_t out_size) ITO_CALL_NOEXCEPT;
/* The canonical public contact code for a key, NUL terminated. */
ITO_CALL_API ito_status
ito_contact_code_format(const uint8_t key[ITO_KEY_BYTES], char* out,
                        size_t out_size) ITO_CALL_NOEXCEPT;
/* Refuses noncanonical encodings, wrong lengths and other versions. */
ITO_CALL_API ito_status
ito_contact_code_parse(const char* code, size_t code_size,
                       uint8_t key[ITO_KEY_BYTES]) ITO_CALL_NOEXCEPT;

/*
 * Persistence of the plaintext snapshot. The core owns its format and every
 * policy. The platform seals it and publishes it as one atomic replacement.
 * persist is called with the complete new snapshot while the store's lock is
 * held and returns one of the three results below. The platform wipes its own
 * copies of the snapshot.
 */
#define ITO_PERSIST_COMMITTED 0
#define ITO_PERSIST_NOT_COMMITTED 1
#define ITO_PERSIST_AMBIGUOUS 2

typedef struct ito_persistence {
    size_t struct_size;
    void* user_data;
    int32_t (*persist)(void* user_data, const uint8_t* snapshot,
                       size_t snapshot_size);
} ito_persistence;

/* snapshot_size 0 opens an empty store. Any other snapshot must parse
 * exactly. A corrupt snapshot is refused, never reset. */
ITO_CALL_API ito_status ito_store_open(ito_context* context,
                                       const uint8_t* snapshot,
                                       size_t snapshot_size,
                                       const ito_persistence* persistence,
                                       ito_store** out) ITO_CALL_NOEXCEPT;
ITO_CALL_API ito_status ito_store_destroy(ito_store* store) ITO_CALL_NOEXCEPT;

/* Creates the installation's identity. Refused when one exists. */
ITO_CALL_API ito_status ito_store_create_identity(
    ito_store* store, uint64_t now_unix) ITO_CALL_NOEXCEPT;
/* NOT_FOUND before an identity exists. */
ITO_CALL_API ito_status ito_store_identity(
    ito_store* store, uint8_t key[ITO_KEY_BYTES]) ITO_CALL_NOEXCEPT;

#define ITO_TRUST_UNVERIFIED 1u
#define ITO_TRUST_VERIFIED 2u
#define ITO_TRUST_BLOCKED 3u

#define ITO_CONTACT_REPLACED 1u /* replaced_by names the successor key */
#define ITO_CONTACT_REPLACES 2u /* replaces names the retired key */

typedef struct ito_contact {
    size_t struct_size;
    uint8_t key[ITO_KEY_BYTES];
    uint32_t trust;
    uint32_t flags;
    uint8_t replaced_by[ITO_KEY_BYTES];
    uint8_t replaces[ITO_KEY_BYTES];
    uint64_t added_unix;
    uint64_t verified_unix; /* 0 while never verified */
    size_t name_size;
    char name[ITO_MAX_NAME_BYTES + 1u]; /* UTF-8, NUL terminated */
} ito_contact;

/* A name is 1..128 bytes of UTF-8 without control characters. */
ITO_CALL_API ito_status ito_store_add_contact(
    ito_store* store, const uint8_t key[ITO_KEY_BYTES], const char* name,
    size_t name_size, uint64_t now_unix) ITO_CALL_NOEXCEPT;
/* Verified only when compared is the complete 64-character fingerprint,
 * ignoring case, spaces and hyphens. AUTHENTICATION_FAILED otherwise. */
ITO_CALL_API ito_status ito_store_verify_contact(
    ito_store* store, const uint8_t key[ITO_KEY_BYTES], const char* compared,
    size_t compared_size, uint64_t now_unix) ITO_CALL_NOEXCEPT;
ITO_CALL_API ito_status ito_store_block_contact(
    ito_store* store, const uint8_t key[ITO_KEY_BYTES]) ITO_CALL_NOEXCEPT;
ITO_CALL_API ito_status
ito_store_rename_contact(ito_store* store, const uint8_t key[ITO_KEY_BYTES],
                         const char* name, size_t name_size) ITO_CALL_NOEXCEPT;
/* A known contact's key changed: the old record becomes Blocked and points
 * at the new, Unverified record, which keeps its name. An active attempt with
 * the old key stops. */
ITO_CALL_API ito_status ito_store_replace_contact(
    ito_store* store, const uint8_t old_key[ITO_KEY_BYTES],
    const uint8_t new_key[ITO_KEY_BYTES], uint64_t now_unix) ITO_CALL_NOEXCEPT;
ITO_CALL_API ito_status ito_store_contact_count(ito_store* store, size_t* count)
    ITO_CALL_NOEXCEPT;
/* Contacts in ascending key order. */
ITO_CALL_API ito_status ito_store_contact_at(
    ito_store* store, size_t index, ito_contact* out) ITO_CALL_NOEXCEPT;
ITO_CALL_API ito_status
ito_store_find_contact(ito_store* store, const uint8_t key[ITO_KEY_BYTES],
                       ito_contact* out) ITO_CALL_NOEXCEPT;

/* The relay service a transport is authenticated to: the verified YUME
 * server fingerprint and the exact named service. */
typedef struct ito_authority {
    size_t struct_size;
    uint8_t server_fingerprint[ITO_KEY_BYTES];
    size_t service_size;
    char service[ITO_MAX_SERVICE_BYTES];
} ito_authority;

typedef struct ito_invitation_info {
    size_t struct_size;
    uint8_t initiator_key[ITO_KEY_BYTES];
    uint8_t responder_key[ITO_KEY_BYTES];
    uint64_t issued_unix;
    uint64_t expires_unix;
    uint32_t profile;
} ito_invitation_info;

/* Validates a received invitation completely (encoding, clock, time, keys,
 * signature, Verified initiator, this identity as responder, the exact
 * authority and spent state) without spending it. At most eight invitations
 * and calls are pending per store. The handle holds the invitation PSK and
 * its original monotonic deadline. A successful independent clock checkpoint
 * may remain durable if later allocation or capacity checks fail. Identity,
 * trust and replay state are unchanged by that checkpoint. now_unix is the
 * platform's current wall time, never a stored or clamped floor. A detected
 * rollback checkpoints uncertainty even though opening refuses. No safe
 * automatic recovery from persisted uncertainty is currently implemented. */
ITO_CALL_API ito_status
ito_invitation_open(ito_store* store, const uint8_t* invitation,
                    size_t invitation_size, const ito_authority* authority,
                    uint64_t now_unix, ito_invitation** out) ITO_CALL_NOEXCEPT;
ITO_CALL_API ito_status
ito_invitation_info_get(const ito_invitation* invitation,
                        ito_invitation_info* out) ITO_CALL_NOEXCEPT;
/* Wipes the invitation and its PSK. */
ITO_CALL_API ito_status ito_invitation_destroy(ito_invitation* invitation)
    ITO_CALL_NOEXCEPT;

/*
 * The byte stream to the relay service. read returns up to capacity bytes
 * and may return fewer. write admits all bytes or none. A timeout of zero
 * polls. Both return an ITO_IO_* result. The core calls them only from
 * ito_call_run, never with its own locks held.
 */
#define ITO_IO_OK 0
#define ITO_IO_EOF 1
#define ITO_IO_TIMEOUT 2
#define ITO_IO_FAILED 3

typedef struct ito_transport {
    size_t struct_size;
    void* user_data;
    int32_t (*read)(void* user_data, uint8_t* out, size_t capacity,
                    size_t* received, uint32_t timeout_ms);
    int32_t (*write)(void* user_data, const uint8_t* data, size_t size,
                     uint32_t timeout_ms);
} ito_transport;

/* Local call states of docs/CALL_SECURITY.md. */
#define ITO_CALL_IDLE 0u
#define ITO_CALL_PREPARING 1u
#define ITO_CALL_JOINED 2u
#define ITO_CALL_HANDSHAKING 3u
#define ITO_CALL_AUTHENTICATING 4u
#define ITO_CALL_AWAITING_ACCEPTANCE 5u
#define ITO_CALL_MEDIA_AUTHORIZED 6u
#define ITO_CALL_CLOSING 7u
#define ITO_CALL_CLOSED 8u

/* END reasons on the wire. */
#define ITO_END_CANCELED 1u
#define ITO_END_DECLINED 2u
#define ITO_END_EXPIRED 3u
#define ITO_END_PROTOCOL_FAILURE 4u
#define ITO_END_AUTHORITY_ENDED 5u
#define ITO_END_RESOURCE_LIMIT 6u

#define ITO_CALL_FLAG_INITIATOR 1u
#define ITO_CALL_FLAG_INVITATION_READY 2u
#define ITO_CALL_FLAG_PEER_AUTHENTICATED 4u
#define ITO_CALL_FLAG_CONSENT_PENDING 8u
/* The remote END arrived after the peer's proof was verified. */
#define ITO_CALL_FLAG_REMOTE_END_ATTRIBUTED 16u

typedef struct ito_call_status {
    size_t struct_size;
    uint32_t state;
    uint32_t flags;
    /* The final result once CLOSED, else OK. */
    ito_status result;
    uint32_t local_end_reason;  /* 0 when no END was sent */
    uint32_t remote_end_reason; /* 0 when no END arrived */
    uint32_t relay_refusal;     /* 0 or the relay's REFUSED reason */
    uint8_t peer_key[ITO_KEY_BYTES];
    uint64_t data_bytes_sent;
    uint64_t data_bytes_received;
    uint64_t frames_sent;
    uint64_t frames_received;
    uint32_t key_updates_sent;
    uint32_t key_updates_received;
} ito_call_status;

/* The initiator role: peer_key must be a Verified contact. The call reserves
 * a room, signs a fresh invitation and spends it before joining. Clock
 * admission precedes invitation use and may checkpoint time independently
 * of later failure. now_unix must be the platform's current wall time. */
ITO_CALL_API ito_status ito_call_create_initiator(
    ito_store* store, const uint8_t peer_key[ITO_KEY_BYTES],
    const ito_authority* authority, uint32_t lifetime_seconds,
    uint64_t now_unix, ito_call** out) ITO_CALL_NOEXCEPT;
/* Explicit profile: 1 is diagnostic DATA, 2 is bounded audio/chat content.
 * The signed invitation, OFFER and SELECT bind this value. Unknown values
 * fail, with no fallback or downgrade. */
ITO_CALL_API ito_status ito_call_create_initiator_profile(
    ito_store* store, const uint8_t peer_key[ITO_KEY_BYTES],
    const ito_authority* authority, uint32_t lifetime_seconds,
    uint64_t now_unix, uint32_t profile, ito_call** out) ITO_CALL_NOEXCEPT;
/* The responder role. On success the call owns the invitation and the
 * caller's handle is consumed. On failure the caller still owns it. A fresh
 * clock admission cannot extend the opened invitation's monotonic deadline.
 * A completed clock checkpoint can remain durable after a later failure. */
ITO_CALL_API ito_status
ito_call_create_responder(ito_store* store, ito_invitation* invitation,
                          const ito_authority* authority, uint64_t now_unix,
                          ito_call** out) ITO_CALL_NOEXCEPT;
/* Runs the attempt to its end on the calling thread and returns the final
 * result. Callable once per call. */
ITO_CALL_API ito_status
ito_call_run(ito_call* call, const ito_transport* transport) ITO_CALL_NOEXCEPT;
ITO_CALL_API ito_status
ito_call_status_get(ito_call* call, ito_call_status* out) ITO_CALL_NOEXCEPT;
/* The signed invitation, once ITO_CALL_FLAG_INVITATION_READY is set. It
 * holds the invitation PSK: hand it only to an authenticated, confidential
 * channel and wipe every copy. */
ITO_CALL_API ito_status
ito_call_copy_invitation(ito_call* call, uint8_t* out, size_t out_size,
                         size_t* invitation_size) ITO_CALL_NOEXCEPT;
/* The responder's local decision on the verified contact and profile.
 * ITO_STATUS_WRONG_STATE when no decision is pending, including once the
 * consent budget or a cancel has ended the attempt. */
ITO_CALL_API ito_status ito_call_decide(ito_call* call,
                                        int accept) ITO_CALL_NOEXCEPT;
/* Queues one DATA payload of 1..16384 bytes. WOULD_BLOCK under backpressure. */
ITO_CALL_API ito_status ito_call_send(ito_call* call, const uint8_t* data,
                                      size_t size) ITO_CALL_NOEXCEPT;
/* Takes the oldest received DATA payload. WOULD_BLOCK when none is queued. */
ITO_CALL_API ito_status ito_call_receive(ito_call* call, uint8_t* out,
                                         size_t out_size,
                                         size_t* size) ITO_CALL_NOEXCEPT;
/* Ends the call with END "canceled". Before ito_call_run starts it, the
 * call is canceled without spending or sending anything. */
ITO_CALL_API ito_status ito_call_hangup(ito_call* call) ITO_CALL_NOEXCEPT;
/* Stops the attempt from any thread. It only raises a flag, which the run
 * sees within one 20 ms I/O slice. */
ITO_CALL_API ito_status ito_call_cancel(ito_call* call) ITO_CALL_NOEXCEPT;
/* The transport's grant ended: the call ends with END "authority ended".
 * Like cancel, it only raises a flag. */
ITO_CALL_API ito_status ito_call_authority_lost(ito_call* call)
    ITO_CALL_NOEXCEPT;
/* Wipes every secret. Refused while ito_call_run has not returned. */
ITO_CALL_API ito_status ito_call_destroy(ito_call* call) ITO_CALL_NOEXCEPT;

#if defined(ITO_CALL_EVIDENCE)
/* Test evidence builds only, never a product library: a JSON object with
 * every secret this attempt held (PSK, invitation, exporter, both proofs and
 * the TLS key log), hex encoded, so a relay tap can prove none crossed it. */
ITO_CALL_API ito_status ito_call_evidence_json(ito_call* call, char* out,
                                               size_t out_size,
                                               size_t* size) ITO_CALL_NOEXCEPT;
#endif

#ifdef __cplusplus
}
#endif

#endif /* ITO_CALL_H */
