#!/usr/bin/env python3
# Copyright (C) 2026 FixCraft Inc. AGPL-3.0-or-later.
"""Writes tests/vectors.txt: fixed vectors for docs/CALL_SECURITY.md.

An independent implementation of the byte layouts, written from the
contract's tables with Python's hashlib and the `cryptography` package's
Ed25519, so the C++ codec is checked against a second writer rather than
against itself. Each line is `name hex`. Rerun only for a deliberate
contract or vector correction, and review the changed bytes.
"""
from __future__ import annotations

import argparse
import base64
import hashlib
import struct
from pathlib import Path

from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from cryptography.hazmat.primitives.serialization import Encoding, PublicFormat

# RFC 8032 section 7.1, TEST 1 and TEST 2 secret keys. Published test data.
SEED_1 = bytes.fromhex('9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60')
SEED_2 = bytes.fromhex('4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb')


def public(seed: bytes) -> bytes:
    return Ed25519PrivateKey.from_private_bytes(seed).public_key().public_bytes(
        Encoding.Raw, PublicFormat.Raw)


def sign(seed: bytes, message: bytes) -> bytes:
    return Ed25519PrivateKey.from_private_bytes(seed).sign(message)


def sha256(*parts: bytes) -> bytes:
    digest = hashlib.sha256()
    for part in parts:
        digest.update(part)
    return digest.digest()


def u16(value: int) -> bytes:
    return struct.pack('>H', value)


def u32(value: int) -> bytes:
    return struct.pack('>I', value)


def u64(value: int) -> bytes:
    return struct.pack('>Q', value)


def filled(byte: int, size: int = 32) -> bytes:
    return bytes([byte]) * size


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path,
                        default=Path(__file__).resolve().parent / 'vectors.txt')
    out = parser.parse_args().output

    vectors: list[tuple[str, bytes]] = []
    pub_1, pub_2 = public(SEED_1), public(SEED_2)
    vectors += [('seed_1', SEED_1), ('public_1', pub_1),
                ('seed_2', SEED_2), ('public_2', pub_2)]

    code = 'ito-contact:1:' + base64.urlsafe_b64encode(pub_1).decode().rstrip('=')
    vectors.append(('contact_code_1', code.encode()))
    vectors.append(('fingerprint_1', sha256(b'ito/contact-identity/1\0', pub_1)))

    # An invitation from identity 1 to identity 2.
    invitation_id, room, relay, token, psk = (filled(0x01), filled(0x02),
                                              filled(0x03), filled(0x04),
                                              filled(0x05))
    service = b'ito-call'
    issued, expires = 1_700_000_000, 1_700_000_300
    body = (b'ito/invitation/1\0' + invitation_id + room + pub_1 + pub_2 + relay +
            u16(len(service)) + service + token + u64(issued) + u64(expires) +
            u16(1) + psk)
    invitation = body + sign(SEED_1, body)
    invitation_hash = sha256(invitation)
    vectors += [('invitation_id', invitation_id), ('room', room), ('relay', relay),
                ('token', token), ('psk', psk), ('service', service),
                ('issued', u64(issued)), ('expires', u64(expires)),
                ('invitation_1', invitation), ('invitation_hash_1', invitation_hash)]

    offer = select = u16(1)
    context = sha256(b'ito/call-context/1\0', invitation_hash, room, pub_1, pub_2, relay,
                     u16(len(service)), service, offer, select)
    exporter = filled(0x11)
    auth_1 = b'ito/call-auth/1\0' + bytes([1]) + exporter + context
    auth_2 = b'ito/call-auth/1\0' + bytes([2]) + exporter + context
    signature_1, signature_2 = sign(SEED_1, auth_1), sign(SEED_2, auth_2)
    vectors += [('context_hash_1', context), ('exporter', exporter),
                ('auth_message_1', auth_1), ('auth_message_2', auth_2),
                ('auth_signature_1', signature_1), ('auth_signature_2', signature_2),
                ('accept_1', sha256(context, signature_1, signature_2))]

    def frame(kind: int, sequence: int, payload: bytes) -> bytes:
        return u32(9 + len(payload)) + bytes([kind]) + u64(sequence) + payload

    vectors += [('frame_offer', frame(1, 0, offer)),
                ('frame_data', frame(5, 5, b'abc')),
                ('frame_end', frame(6, 7, bytes([2])))]

    def relay_frame(kind: int, payload: bytes) -> bytes:
        return u32(1 + len(payload)) + bytes([kind]) + payload

    vectors += [('relay_reserve', relay_frame(1, u16(1) + u32(300_000))),
                ('relay_join', relay_frame(3, u16(1) + room + bytes([2]) + token)),
                ('relay_cancel', relay_frame(6, b'')),
                ('relay_reserved', relay_frame(2, room + filled(0x06) + token + u32(299_000))),
                ('relay_waiting', relay_frame(4, b'')),
                ('relay_paired', relay_frame(5, b'')),
                ('relay_refused', relay_frame(7, bytes([3])))]

    # A store snapshot: identity 2, contact 1 Verified, one received spend.
    name = 'Alice'.encode()
    snapshot = (b'ito/identity-snapshot/1\0' + bytes([1]) + SEED_2 + pub_2 +
                u64(1_700_000_000) + u64(1_700_000_100) + u64(0) + u16(1) +
                pub_1 + bytes([2, 0]) + u64(1_700_000_001) + u64(1_700_000_002) +
                bytes([len(name)]) + name + u16(1) + invitation_id + invitation_hash +
                u64(expires) + bytes([2]))
    vectors.append(('snapshot_1', snapshot))

    out.write_text(''.join(f'{key} {value.hex()}\n' for key, value in vectors))


if __name__ == '__main__':
    main()
