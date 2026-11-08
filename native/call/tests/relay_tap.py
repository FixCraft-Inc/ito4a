# Copyright (C) 2026 FixCraft Inc. AGPL-3.0-or-later.
"""A recording tap on the relay's module socket, and the analysis of what it saw.

yumed connects to the tap as it would to the relay. Each connection is
forwarded to the relay unchanged and both directions are recorded. The
analysis splits each direction into the module header, the relay's control
frames and the opaque bytes after pairing, requires those opaque bytes to be
whole TLS records, and searches every recorded byte for each secret the
endpoints held.
"""
from __future__ import annotations

import hashlib
import hmac
import os
import socket
import threading
from pathlib import Path

from relay_peers import Failure, require

TLS_CONTENT_TYPES = {20, 21, 22, 23}
MAX_TLS_RECORD = 16384 + 256


class Tap:
    def __init__(self, directory: Path, upstream: Path) -> None:
        self.path = directory / 'tap.sock'
        self.upstream = upstream
        self.listener = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        self.listener.bind(str(self.path))
        os.chmod(self.path, 0o600)
        self.listener.listen(64)
        self.connections: list[dict] = []
        self.lock = threading.Lock()
        self.closing = False
        self.thread = threading.Thread(target=self._accept, daemon=True)
        self.thread.start()

    def _accept(self) -> None:
        while not self.closing:
            try:
                downstream, _ = self.listener.accept()
            except OSError:
                return
            upstream = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
            upstream.connect(str(self.upstream))
            record = {'to_relay': bytearray(), 'from_relay': bytearray()}
            with self.lock:
                self.connections.append(record)
            for source, sink, key in ((downstream, upstream, 'to_relay'),
                                      (upstream, downstream, 'from_relay')):
                threading.Thread(target=self._forward, args=(source, sink, record[key]),
                                 daemon=True).start()

    def _forward(self, source: socket.socket, sink: socket.socket, log: bytearray) -> None:
        clean = False
        try:
            while True:
                data = source.recv(65536)
                if not data:
                    clean = True
                    break
                with self.lock:
                    log.extend(data)
                sink.sendall(data)
        except OSError:
            pass
        finally:
            # A clean end passes on as a half-close, which is all the relay
            # would see without the tap. Tearing down both directions here
            # raced the peer's last bytes still crossing the relay
            # (cloud-host03). Only an error ends both directions.
            how = socket.SHUT_WR if clean else socket.SHUT_RDWR
            for side in (sink,) if clean else (sink, source):
                try:
                    side.shutdown(how)
                except OSError:
                    pass

    def close(self) -> None:
        self.closing = True
        self.listener.close()

    def snapshot(self) -> list[dict]:
        with self.lock:
            return [{key: bytes(value) for key, value in record.items()}
                    for record in self.connections]


def _control_frames(data: bytes, offset: int, stop_types: set[int]) -> tuple[int, list[int]]:
    """Parses relay frames from offset through the first of stop_types.
    Returns the offset after that frame and the types seen."""
    types = []
    while offset + 5 <= len(data):
        body = int.from_bytes(data[offset:offset + 4], 'big')
        require(1 <= body <= 4096, f'control frame length {body}')
        kind = data[offset + 4]
        types.append(kind)
        offset += 4 + body
        if kind in stop_types:
            return offset, types
    return offset, types


def _tls_records(data: bytes) -> dict:
    offset = 0
    counts: dict[int, int] = {}
    while offset < len(data):
        require(offset + 5 <= len(data), 'a partial TLS record header')
        kind = data[offset]
        version = data[offset + 1:offset + 3]
        length = int.from_bytes(data[offset + 3:offset + 5], 'big')
        require(kind in TLS_CONTENT_TYPES, f'non-TLS content type {kind} at {offset}')
        require(version in (b'\x03\x01', b'\x03\x03'), f'record version {version.hex()}')
        require(length <= MAX_TLS_RECORD, f'record length {length}')
        require(offset + 5 + length <= len(data), 'a truncated TLS record')
        counts[kind] = counts.get(kind, 0) + 1
        offset += 5 + length
    return counts


def split_connection(record: dict) -> dict:
    """The control prefix and the opaque TLS bytes of both directions."""
    to_relay = record['to_relay']
    newline = to_relay.find(b'\n')
    require(to_relay.startswith(b'yume-module 1 ') and 0 < newline <= 144,
            'the module header')
    # Client to relay: RESERVE and JOIN or a lone JOIN, then TLS.
    after_join, sent = _control_frames(to_relay, newline + 1, {3})
    require(sent and sent[-1] == 3, f'client control frames {sent}')
    # Relay to client: RESERVED, WAITING, then PAIRED, then TLS.
    after_paired, received = _control_frames(record['from_relay'], 0, {5})
    require(received and received[-1] == 5, f'relay control frames {received}')
    opaque = {'to_relay': to_relay[after_join:], 'from_relay': record['from_relay'][after_paired:]}
    return {'client_control': sent, 'relay_control': received,
            'tls_records': {key: _tls_records(value) for key, value in opaque.items()},
            'opaque_bytes': {key: len(value) for key, value in opaque.items()}}


def _hkdf_expand_label(secret: bytes, label: bytes, length: int) -> bytes:
    full = b'tls13 ' + label
    info = length.to_bytes(2, 'big') + bytes([len(full)]) + full + b'\x00'
    output, block, counter = b'', b'', 1
    while len(output) < length:
        block = hmac.new(secret, block + info + bytes([counter]), hashlib.sha384).digest()
        output += block
        counter += 1
    return output[:length]


def secrets_from(evidence: dict) -> dict[str, bytes]:
    """Every secret value one endpoint reported, with TLS traffic keys and
    IVs derived from its key log."""
    found = {name: bytes.fromhex(evidence[name])
             for name in ('psk', 'invitation', 'exporter', 'own_signature', 'peer_signature')}
    for index, line in enumerate(evidence['key_log']):
        label, _client_random, secret_hex = line.split(' ')
        secret = bytes.fromhex(secret_hex)
        found[f'{label}#{index}'] = secret
        if 'TRAFFIC_SECRET' in label:
            found[f'{label}#{index}:key'] = _hkdf_expand_label(secret, b'key', 32)
            found[f'{label}#{index}:iv'] = _hkdf_expand_label(secret, b'iv', 12)
    for name, value in found.items():
        require(len(value) >= 12, f'evidence {name} is empty')
    return found


def search(captures: list[dict], secrets: dict[str, bytes], plaintexts: list[bytes]) -> dict:
    """Fails if any secret, or any 32-byte window of a plaintext payload,
    appears anywhere the tap recorded."""
    blobs = [value for record in captures for value in record.values()]
    for name, value in secrets.items():
        for blob in blobs:
            if value in blob:
                raise Failure(f'secret {name} crossed the relay')
    windows = 0
    for plaintext in plaintexts:
        for offset in range(0, max(len(plaintext) - 32, 0), 32):
            window = plaintext[offset:offset + 32]
            windows += 1
            for blob in blobs:
                if window in blob:
                    raise Failure(f'plaintext at {offset} crossed the relay')
    return {'secrets_checked': len(secrets), 'plaintext_windows_checked': windows,
            'bytes_recorded': sum(len(blob) for blob in blobs)}
