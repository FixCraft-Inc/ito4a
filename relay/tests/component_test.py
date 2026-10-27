#!/usr/bin/env python3
# Copyright (C) 2026 FixCraft Inc. AGPL-3.0-or-later.
"""Qualifies ito-call-relay with the version-1 default limits.

The test plays yumed: it holds the module listener and writes module headers
as the trusted daemon user. Each case starts its own relay. Expectations come
from docs/CALL_RELAY.md, not from the implementation. A case that cannot run
fails the suite unless it is listed as optional with its reason.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import resource
import shutil
import socket
import subprocess
import sys
import tempfile
import threading
import time
import traceback
from pathlib import Path
from typing import Callable

# The helpers live beside this file. Bytecode would land in the source tree.
sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parent))
from relay_peers import (CANCEL, FORWARD_CAP, PAIRED, QUEUE_BYTES, WAITING, Failure,  # noqa: E402
                         ModulePeer, RelayProcess, Reservation, exchange, frame, header, join,
                         pattern, refused, require, reserve)

ROLE_INITIATOR = 1
ROLE_RESPONDER = 2
INVALID_ROOM, UNSUPPORTED, CAPACITY, CANCELED, TIMEOUT, INVALID_REQUEST = range(1, 7)


class Context:
    def __init__(self, program: Path, root: Path) -> None:
        self.program = program
        self.root = root
        self.serial = 0
        # Every principal and token a case used, for the log privacy check.
        self.secrets: set[str] = set()
        self.relays: list[RelayProcess] = []

    def principal(self, label: str) -> str:
        self.serial += 1
        value = hashlib.sha256(f'{label}/{self.serial}'.encode()).hexdigest()
        self.secrets.add(value)
        return value

    def relay(self, name: str) -> RelayProcess:
        directory = self.root / name
        directory.mkdir(mode=0o700)
        relay = RelayProcess(self.program, directory)
        self.relays.append(relay)
        return relay

    def peer(self, relay: RelayProcess, principal: str | None) -> ModulePeer:
        return ModulePeer(relay.path, principal)

    def remember(self, reservation: Reservation) -> Reservation:
        for value in (reservation.room, reservation.initiator_token, reservation.responder_token):
            self.secrets.add(value.hex())
        return reservation


def pair(ctx: Context, relay: RelayProcess, initiator: str, responder: str,
         lifetime: int = 60_000) -> tuple[ModulePeer, ModulePeer, Reservation]:
    a = ctx.peer(relay, initiator)
    room = ctx.remember(a.reserve(lifetime))
    a.send(join(room.room, ROLE_INITIATOR, room.initiator_token))
    a.expect(WAITING, 'initiator WAITING')
    b = ctx.peer(relay, responder)
    b.send(join(room.room, ROLE_RESPONDER, room.responder_token))
    b.expect(WAITING + PAIRED, 'responder WAITING then PAIRED')
    a.expect(PAIRED, 'initiator PAIRED')
    return a, b, room


def run_startup(ctx: Context, arguments: list[str], **options) -> tuple[int, RelayProcess]:
    relay = ctx.relay(f'startup-{ctx.serial}')
    ctx.serial += 1
    relay.spawn(arguments, **options)
    return relay.wait(10.0), relay


def failed_reason(lines: list[dict]) -> str:
    failures = [line for line in lines if line.get('ito_call_relay') == 'failed']
    require(len(failures) == 1, f'expected one failure line, got {lines}')
    return failures[0].get('reason', '')


def case_usage(ctx: Context) -> dict:
    results = {}
    uid = str(os.getuid())
    for name, arguments, expected in (
            ('missing', [], 2), ('missing_uid', ['--service', 'ito-call'], 2),
            ('bad_service', ['--service', 'Ito', '--daemon-uid', uid], 2),
            ('reserved_service', ['--service', 'yume.call', '--daemon-uid', uid], 2),
            ('long_service', ['--service', 'a' * 65, '--daemon-uid', uid], 2),
            ('bad_uid', ['--service', 'ito-call', '--daemon-uid', '-1'], 2),
            ('no_user_uid', ['--service', 'ito-call', '--daemon-uid', '4294967295'], 2),
            ('duplicate', ['--service', 'a', '--service', 'b', '--daemon-uid', uid], 2),
            ('unknown', ['--service', 'ito-call', '--daemon-uid', uid, '--verbose'], 2)):
        code, relay = run_startup(ctx, arguments, pass_listener=False)
        text = relay.log_path.read_text(encoding='utf-8')
        require(code == expected and text.startswith('ito-call-relay: '),
                f'usage {name}: exit {code}, {text!r}')
        relay.log_path.unlink()
        results[name] = code
    help_run = subprocess.run([str(ctx.program), '--help'], capture_output=True, timeout=10,
                              check=False)
    require(help_run.returncode == 0 and help_run.stdout.startswith(b'usage: ito-call-relay'),
            'help output')
    version = subprocess.run([str(ctx.program), '--version'], capture_output=True, timeout=10,
                             check=False)
    require(version.returncode == 0 and b'relay protocol 1' in version.stdout, 'version output')
    return results


def case_listener_checks(ctx: Context) -> dict:
    uid = os.getuid()
    arguments = ['--service', 'ito-call', '--daemon-uid', str(uid)]
    results = {}

    def expect(name: str, reason_fragment: str, **options) -> None:
        try:
            code, relay = run_startup(ctx, options.pop('arguments', arguments), **options)
            reason = failed_reason(relay.lines())
        except Failure as failure:
            raise Failure(f'{name}: {failure}') from None
        require(code == 1 and reason_fragment in reason, f'{name}: exit {code}, {reason!r}')
        results[name] = reason

    expect('no_descriptor', 'not a socket', pass_listener=False)
    expect('owner_is_not_daemon', 'belong to the daemon user',
           arguments=['--service', 'ito-call', '--daemon-uid', str(uid + 1)])

    def bound(kind: int, directory: Path, listen: bool, mode: int = 0o600) -> socket.socket:
        path = directory / 'other.sock'
        made = socket.socket(socket.AF_UNIX, kind)
        made.bind(str(path))
        os.chmod(path, mode)
        if listen:
            made.listen(8)
        return made

    for name, fragment, setup in (
            ('group_readable_socket', 'closed to others',
             lambda d: bound(socket.SOCK_STREAM, d, True, 0o660)),
            ('not_listening', 'listening UNIX stream',
             lambda d: bound(socket.SOCK_STREAM, d, False)),
            ('datagram', 'listening UNIX stream',
             lambda d: bound(socket.SOCK_DGRAM, d, False))):
        directory = ctx.root / f'listener-{name}'
        directory.mkdir(mode=0o700)
        with setup(directory) as listener:
            expect(name, fragment, listener=listener)

    shared = ctx.root / 'listener-shared-directory'
    shared.mkdir(mode=0o700)
    os.chmod(shared, 0o770)
    with bound(socket.SOCK_STREAM, shared, True) as listener:
        expect('group_writable_directory', 'directory', listener=listener)

    real = ctx.root / 'listener-real'
    real.mkdir(mode=0o700)
    link = ctx.root / 'listener-link'
    link.symlink_to(real)
    with bound(socket.SOCK_STREAM, link, True) as listener:
        expect('linked_directory', 'directory', listener=listener)

    abstract = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    abstract.bind(b'\0ito-relay-test-' + os.urandom(8).hex().encode())
    abstract.listen(8)
    with abstract:
        expect('abstract', 'no filesystem path', listener=abstract)

    environment = {'PATH': '/usr/bin:/bin', 'LISTEN_FDS': '2', 'LISTEN_PID': '1'}
    expect('activation_count', 'LISTEN_FDS', environment=environment)
    environment = {'PATH': '/usr/bin:/bin', 'LISTEN_FDS': '1', 'LISTEN_PID': '1'}
    expect('activation_pid', 'LISTEN_FDS', environment=environment)

    # systemd's variables for exactly this process are accepted and cleared.
    relay = ctx.relay('listener-activation')
    relay.spawn(prefix=['/bin/sh', '-c', 'LISTEN_PID=$$ LISTEN_FDS=1 exec "$0" "$@"'])
    deadline = time.monotonic() + 5
    while not any(line.get('ito_call_relay') == 'ready' for line in relay.lines()):
        require(time.monotonic() < deadline and relay.poll() is None, 'activation run not ready')
        time.sleep(0.01)
    require(relay.stop() == 0, 'activation run did not stop cleanly')
    results['activation_accepted'] = True

    unshare = shutil.which('unshare')
    if unshare:
        code, relay = run_startup(ctx, arguments, prefix=[unshare, '--user', '--map-root-user'])
        require(code == 1 and 'root' in failed_reason(relay.lines()), f'root refusal: exit {code}')
        results['root_refused'] = True
    else:
        raise Failure('unshare is required to check the root refusal')
    return results


def case_headers(ctx: Context) -> dict:
    relay = ctx.relay('headers')
    relay.start()
    principal = ctx.principal('header')
    timings = {}

    def silent(name: str, raw: bytes, timeout: float = 2.0) -> None:
        peer = ctx.peer(relay, None)
        started = time.monotonic()
        peer.send(raw)
        peer.expect_end(name, timeout)
        timings[name] = round(time.monotonic() - started, 3)
        peer.close()

    silent('wrong_prefix', b'YUME-module 1 ' + principal.encode() + b' ito-call\n')
    silent('uppercase_fingerprint', f'yume-module 1 {principal.upper()} ito-call\n'.encode())
    silent('other_service', f'yume-module 1 {principal} echo\n'.encode())
    silent('version_2', f'yume-module 2 {principal} ito-call\n'.encode())
    silent('long_service', f'yume-module 1 {principal} {"a" * 65}'.encode())

    # The header and request may arrive one byte at a time.
    peer = ctx.peer(relay, None)
    for byte in (f'yume-module 1 {principal} ito-call\n'.encode() + reserve(1_000)):
        peer.send(bytes([byte]))
        time.sleep(0.002)
    ctx.remember(Reservation(peer.read_exact(105), 1_000))
    peer.close()

    peer = ctx.peer(relay, None)
    started = time.monotonic()
    peer.send(f'yume-module 1 {principal} ito'.encode())
    peer.expect_end('incomplete header', 8.0)
    elapsed = time.monotonic() - started
    require(4.5 <= elapsed <= 7.0, f'incomplete header closed after {elapsed:.2f} s')
    timings['incomplete_header'] = round(elapsed, 3)
    peer.close()

    peer = ctx.peer(relay, principal)
    started = time.monotonic()
    peer.expect_refused(TIMEOUT, 'no first request', 8.0)
    elapsed = time.monotonic() - started
    require(4.5 <= elapsed <= 7.0, f'missing request refused after {elapsed:.2f} s')
    timings['no_request'] = round(elapsed, 3)
    peer.close()

    counts = relay.require_clean('headers')
    require(counts['rejected_headers'] == 6, f'rejected headers {counts["rejected_headers"]}')
    require(counts['refusals'][TIMEOUT - 1] == 1, 'one header-budget timeout')
    require(relay.stop() == 0, 'headers relay stop')
    return {'seconds': timings}


def case_framing(ctx: Context) -> dict:
    relay = ctx.relay('framing')
    relay.start()
    principal = ctx.principal('framing')
    observed = {}

    def refusal(name: str, request: bytes, reason: int, timeout: float = 2.0) -> None:
        peer = ctx.peer(relay, principal)
        started = time.monotonic()
        peer.send(request)
        peer.expect_refused(reason, name, timeout)
        observed[name] = round(time.monotonic() - started, 3)
        peer.close()

    refusal('zero_length', b'\0\0\0\0', INVALID_REQUEST)
    # Refused from its length alone, before any body.
    refusal('oversized_length', b'\0\0\x10\x01', INVALID_REQUEST)
    refusal('unknown_type', frame(b'\x09'), INVALID_REQUEST)
    refusal('service_type', frame(b'\x02' + bytes(100)), INVALID_REQUEST)
    refusal('reserve_trailing_byte', frame(b'\x01\x00\x01\x00\x00\x03\xe8\x00'), INVALID_REQUEST)
    refusal('reserve_version_2', reserve(1_000, version=2), UNSUPPORTED)
    refusal('join_version_2', join(bytes(32), 2, bytes(32), version=2), UNSUPPORTED)
    refusal('zero_lifetime', reserve(0), INVALID_REQUEST)
    refusal('long_lifetime', reserve(600_001), INVALID_REQUEST)
    refusal('cancel_first', CANCEL, INVALID_REQUEST)
    refusal('join_short', frame(b'\x03\x00\x01' + bytes(65)), INVALID_REQUEST)
    refusal('join_role_3', join(bytes(32), 3, bytes(32)), INVALID_REQUEST)
    refusal('maximal_frame', frame(b'\x01\x00\x01' + bytes(4093)), INVALID_REQUEST)

    peer = ctx.peer(relay, principal)
    peer.send(reserve(600_000) + reserve(1_000))
    ctx.remember(Reservation(peer.read_exact(105), 600_000))
    peer.expect_refused(INVALID_REQUEST, 'second RESERVE')
    peer.close()

    # Coalesced frames are parsed one after the other.
    peer = ctx.peer(relay, principal)
    peer.send(reserve(1_000) + CANCEL)
    ctx.remember(Reservation(peer.read_exact(105), 1_000))
    peer.expect_refused(CANCELED, 'RESERVE then CANCEL in one write')
    peer.close()

    # A frame split byte by byte is still one frame.
    peer = ctx.peer(relay, principal)
    for byte in reserve(2_000):
        peer.send(bytes([byte]))
        time.sleep(0.002)
    ctx.remember(Reservation(peer.read_exact(105), 2_000))
    peer.send(CANCEL)
    peer.expect_refused(CANCELED, 'CANCEL after a split RESERVE')
    peer.close()

    peer = ctx.peer(relay, principal)
    started = time.monotonic()
    peer.send(b'\0\0\0')
    peer.expect_refused(TIMEOUT, 'partial first frame', 8.0)
    observed['partial_frame_timeout'] = round(time.monotonic() - started, 3)
    require(4.5 <= observed['partial_frame_timeout'] <= 7.0, 'partial frame budget')
    peer.close()

    # A reserving stream may prepare its invitation for longer than the parse
    # budget before it joins. A partial frame there has its own budget.
    peer = ctx.peer(relay, principal)
    room = ctx.remember(peer.reserve(60_000))
    time.sleep(6.5)
    try:
        peer.send(join(room.room, ROLE_INITIATOR, room.initiator_token))
    except OSError as error:
        raise Failure(f'reserving stream waits beyond the parse budget: {error}') from None
    peer.expect(WAITING, 'reserving stream waits beyond the parse budget')
    peer.close()
    peer = ctx.peer(relay, principal)
    room = ctx.remember(peer.reserve(60_000))
    started = time.monotonic()
    peer.send(b'\0\0')
    peer.expect_refused(TIMEOUT, 'partial frame after RESERVED', 8.0)
    elapsed = time.monotonic() - started
    require(4.5 <= elapsed <= 7.0, f'partial frame after RESERVED refused after {elapsed:.2f} s')
    observed['reserved_partial_frame'] = round(elapsed, 3)
    peer.close()

    # While WAITING, a partial frame gets its own budget, and JOIN is invalid.
    for name, follow in (('waiting_partial_frame', b'\0\0'),
                         ('waiting_join', join(bytes(32), 2, bytes(32)))):
        peer = ctx.peer(relay, principal)
        room = ctx.remember(peer.reserve(60_000))
        peer.send(join(room.room, ROLE_INITIATOR, room.initiator_token))
        peer.expect(WAITING, f'{name} WAITING')
        started = time.monotonic()
        peer.send(follow)
        if name == 'waiting_join':
            peer.expect_refused(INVALID_REQUEST, name)
        else:
            peer.expect_refused(TIMEOUT, name, 8.0)
            elapsed = time.monotonic() - started
            require(4.5 <= elapsed <= 7.0, f'waiting partial frame after {elapsed:.2f} s')
            observed[name] = round(elapsed, 3)
        peer.close()

    counts = relay.require_clean('framing')
    require(counts['refusals'][UNSUPPORTED - 1] == 2, 'two unsupported versions')
    require(counts['refusals'][TIMEOUT - 1] == 3, f'three budget timeouts {counts["refusals"]}')
    require(relay.stop() == 0, 'framing relay stop')
    return {'seconds': observed}


def case_pairing(ctx: Context) -> dict:
    relay = ctx.relay('pairing')
    relay.start()
    alice, bob = ctx.principal('alice'), ctx.principal('bob')
    details = {}

    # The responder pipelines bytes behind the JOIN that completes the pair.
    a = ctx.peer(relay, alice)
    room = ctx.remember(a.reserve())
    a.send(join(room.room, ROLE_INITIATOR, room.initiator_token))
    a.expect(WAITING, 'initiator WAITING')
    early = pattern(b'after-join', 32 * 1024)
    b = ctx.peer(relay, bob)
    b.send(join(room.room, ROLE_RESPONDER, room.responder_token) + early)
    b.expect(WAITING + PAIRED, 'responder WAITING then PAIRED')
    a.expect(PAIRED + early, 'PAIRED, then the bytes that followed JOIN')
    details['pipelined_after_join'] = len(early)
    details['exchange'] = exchange(a, b, 1 << 20, b'pairing')
    # CANCEL after PAIRED is only opaque bytes for the peer.
    a.send(CANCEL)
    b.expect(CANCEL, 'CANCEL bytes forwarded verbatim')
    b.send(b'still paired')
    a.expect(b'still paired', 'room alive after opaque CANCEL')
    # Hangup: end of stream flushes accepted bytes, then closes both.
    tail = pattern(b'hangup', 100 * 1024)
    a.send(tail)
    a.shutdown_write()
    data, ended = b.read_until_end(5.0)
    require(ended and data == tail, f'hangup delivered {len(data)} of {len(tail)} bytes')
    a.expect_end('initiator after hangup')
    a.close()
    b.close()

    # A full close while both directions are backpressured. The relay could
    # not yet read the closer's last bytes and still has output queued for
    # it. Those bytes reach the peer anyway, followed by the end.
    a, b, _ = pair(ctx, relay, alice, bob)
    closer = pattern(b'closer', 4 << 20)
    a.socket.setblocking(False)
    accepted = 0
    last_send = time.monotonic()
    while time.monotonic() - last_send < 0.5:
        try:
            accepted += a.socket.send(closer[accepted:accepted + 65536])
            last_send = time.monotonic()
        except BlockingIOError:
            time.sleep(0.01)
    flood = threading.Thread(target=lambda: _send_until_closed(b, pattern(b'toward-closer',
                                                                          4 << 20)))
    flood.start()
    time.sleep(0.5)
    a.close()
    data, ended = b.read_until_end(15.0, limit=accepted + 1)
    flood.join(70.0)
    require(ended and data == closer[:accepted],
            f"full close delivers the closer's last bytes: {len(data)} of {accepted}")
    b.close()
    details['full_close_last_bytes'] = accepted

    # The responder joins first, and the initiator pipelines its bytes.
    a = ctx.peer(relay, alice)
    room = ctx.remember(a.reserve())
    b = ctx.peer(relay, bob)
    b.send(join(room.room, ROLE_RESPONDER, room.responder_token))
    b.expect(WAITING, 'responder waits first')
    early = pattern(b'initiator-early', 4000)
    a.send(join(room.room, ROLE_INITIATOR, room.initiator_token) + early)
    a.expect(WAITING + PAIRED, 'initiator WAITING then PAIRED')
    b.expect(PAIRED + early, 'responder PAIRED, then initiator bytes')
    details['responder_first'] = exchange(b, a, 256 * 1024, b'responder-first')
    b.close()
    a.expect_end('initiator after responder close')
    a.close()

    # Bytes after a JOIN that does not pair are still control frames.
    a = ctx.peer(relay, alice)
    room = ctx.remember(a.reserve())
    a.send(join(room.room, ROLE_INITIATOR, room.initiator_token) + CANCEL)
    a.expect(WAITING, 'WAITING before the coalesced CANCEL')
    a.expect_refused(CANCELED, 'coalesced CANCEL while waiting')
    a.close()
    b = ctx.peer(relay, bob)
    b.send(join(room.room, ROLE_RESPONDER, room.responder_token))
    b.expect_refused(INVALID_ROOM, 'canceled room cannot be joined')
    b.close()

    counts = relay.require_clean('pairing')
    require(counts['pairings'] == 3, f'pairings {counts["pairings"]}')
    require(counts['paired_ends'][0] == 3, f'three peer-closed ends {counts["paired_ends"]}')
    require(relay.stop() == 0, 'pairing relay stop')
    return details


def case_tokens(ctx: Context) -> dict:
    relay = ctx.relay('tokens')
    relay.start()
    alice, bob, mallory = ctx.principal('alice'), ctx.principal('bob'), ctx.principal('mallory')
    latency: dict[str, float] = {}

    def refused_join(name: str, principal: str, request: bytes) -> None:
        peer = ctx.peer(relay, principal)
        started = time.monotonic()
        peer.send(request)
        peer.expect_refused(INVALID_ROOM, name)
        latency[name] = round(time.monotonic() - started, 4)
        peer.close()

    a = ctx.peer(relay, alice)
    room = ctx.remember(a.reserve())
    a.send(join(room.room, ROLE_INITIATOR, room.initiator_token))
    a.expect(WAITING, 'initiator WAITING')
    wrong = bytes([room.responder_token[0] ^ 1]) + room.responder_token[1:]
    refused_join('wrong_token', mallory, join(room.room, ROLE_RESPONDER, wrong))
    refused_join('initiator_token_as_responder', mallory,
                 join(room.room, ROLE_RESPONDER, room.initiator_token))
    refused_join('initiator_role_from_other_stream', mallory,
                 join(room.room, ROLE_INITIATOR, room.initiator_token))
    refused_join('unknown_room', mallory, join(bytes(32), ROLE_RESPONDER, room.responder_token))
    b = ctx.peer(relay, bob)
    b.send(join(room.room, ROLE_RESPONDER, room.responder_token))
    b.expect(WAITING + PAIRED, 'responder pairs after the refusals')
    a.expect(PAIRED, 'initiator PAIRED')
    refused_join('replayed_token', mallory, join(room.room, ROLE_RESPONDER, room.responder_token))
    exchange(a, b, 4096, b'tokens')
    a.close()
    b.close()

    # Before the reserver joins, the initiator token still opens nothing for
    # any other stream.
    a = ctx.peer(relay, alice)
    room = ctx.remember(a.reserve())
    refused_join('initiator_role_before_reserver_joins', mallory,
                 join(room.room, ROLE_INITIATOR, room.initiator_token))
    a.send(join(room.room, ROLE_INITIATOR, room.initiator_token))
    a.expect(WAITING, 'the reserver still joins its room')
    a.close()

    # An occupied role refuses while its holder waits, without disturbing it.
    a = ctx.peer(relay, alice)
    room = ctx.remember(a.reserve())
    b = ctx.peer(relay, bob)
    b.send(join(room.room, ROLE_RESPONDER, room.responder_token))
    b.expect(WAITING, 'responder waits')
    refused_join('occupied_role', mallory, join(room.room, ROLE_RESPONDER, room.responder_token))
    a.send(join(room.room, ROLE_INITIATOR, room.initiator_token))
    a.expect(WAITING + PAIRED, 'initiator pairs with the original responder')
    b.expect(PAIRED, 'original responder PAIRED')
    a.close()
    b.close()

    # The reserving stream may only join its own room as initiator, and a
    # refusal ends the room it reserved.
    for name, make in (('reserver_as_responder', lambda r: join(r.room, ROLE_RESPONDER,
                                                                 r.responder_token)),
                       ('reserver_other_room', lambda r: join(bytes(32), ROLE_INITIATOR,
                                                               r.initiator_token))):
        a = ctx.peer(relay, alice)
        room = ctx.remember(a.reserve())
        a.send(make(room))
        a.expect_refused(INVALID_ROOM, name)
        a.close()
        refused_join(f'{name}_room_closed', bob,
                     join(room.room, ROLE_RESPONDER, room.responder_token))

    # Expiry before any JOIN refuses the reserver with TIMEOUT.
    a = ctx.peer(relay, alice)
    room = ctx.remember(a.reserve(300))
    a.expect_refused(TIMEOUT, 'reservation expiry before JOIN', 3.0)
    a.close()
    refused_join('expired_room', bob, join(room.room, ROLE_RESPONDER, room.responder_token))

    # A pending JOIN lives at most for the reservation's lifetime.
    a = ctx.peer(relay, alice)
    room = ctx.remember(a.reserve(1_500))
    a.send(join(room.room, ROLE_INITIATOR, room.initiator_token))
    a.expect(WAITING, 'waits until expiry')
    started = time.monotonic()
    a.expect_refused(TIMEOUT, 'waiting initiator at expiry', 4.0)
    require(time.monotonic() - started < 2.5, 'expiry is late')
    a.close()
    refused_join('expired_waiting_room', bob,
                 join(room.room, ROLE_RESPONDER, room.responder_token))

    # Wrong, occupied, unknown and expired rooms are refused at once, by the
    # same path. Loopback timing cannot separate finer classes reliably.
    slow = {name: seconds for name, seconds in latency.items() if seconds > 0.25}
    require(not slow, f'refusals outside the immediate class: {slow}')
    counts = relay.require_clean('tokens')
    # Every timed refusal plus the two reserving streams' own refusals.
    require(counts['refusals'][INVALID_ROOM - 1] == len(latency) + 2,
            f'invalid-room refusals {counts["refusals"]}')
    require(relay.stop() == 0, 'tokens relay stop')
    return {'refusal_seconds': latency}


def case_cancel_and_disconnect(ctx: Context) -> dict:
    relay = ctx.relay('cancel')
    relay.start()
    alice, bob = ctx.principal('alice'), ctx.principal('bob')

    a = ctx.peer(relay, alice)
    room = ctx.remember(a.reserve())
    a.send(CANCEL)
    a.expect_refused(CANCELED, 'reserver CANCEL')
    a.close()

    a = ctx.peer(relay, alice)
    room = ctx.remember(a.reserve())
    a.send(join(room.room, ROLE_INITIATOR, room.initiator_token))
    a.expect(WAITING, 'initiator waits')
    a.send(CANCEL)
    a.expect_refused(CANCELED, 'waiting initiator CANCEL')
    a.close()

    for name, end_initiator in (('initiator_cancel', 'cancel'),
                                ('initiator_disconnect', 'disconnect'),
                                ('responder_disconnect', 'responder')):
        a = ctx.peer(relay, alice)
        room = ctx.remember(a.reserve())
        b = ctx.peer(relay, bob)
        b.send(join(room.room, ROLE_RESPONDER, room.responder_token))
        b.expect(WAITING, f'{name}: responder waits')
        if end_initiator == 'cancel':
            a.send(CANCEL)
            a.expect_refused(CANCELED, f'{name}: initiator')
            b.expect_refused(CANCELED, f'{name}: responder')
        elif end_initiator == 'disconnect':
            a.close()
            b.expect_refused(CANCELED, f'{name}: responder')
        else:
            b.close()
            a.expect_refused(CANCELED, f'{name}: reserver')
        a.close()
        b.close()
        late = ctx.peer(relay, bob)
        late.send(join(room.room, ROLE_RESPONDER, room.responder_token))
        late.expect_refused(INVALID_ROOM, f'{name}: room cannot be rejoined')
        late.close()

    counts = relay.require_clean('cancel')
    # 1 + 1, then 2, 1 and 1 across the three waiting-responder cases.
    require(counts['refusals'][CANCELED - 1] == 6, f'canceled refusals {counts["refusals"]}')

    # CANCEL racing the JOIN that would pair the room. Either the CANCEL is
    # parsed first and the room ends for both, or PAIRED is published first
    # and the CANCEL bytes become opaque. Nothing in between is allowed.
    outcomes = {'canceled': 0, 'paired': 0}
    for index in range(40):
        owner, guest = ctx.principal(f'race-a-{index}'), ctx.principal(f'race-b-{index}')
        a = ctx.peer(relay, owner)
        room = ctx.remember(a.reserve())
        a.send(join(room.room, ROLE_INITIATOR, room.initiator_token))
        a.expect(WAITING, 'race initiator waits')
        b = ctx.peer(relay, guest)
        racers = [threading.Thread(target=a.send, args=(CANCEL,)),
                  threading.Thread(target=b.send,
                                   args=(join(room.room, ROLE_RESPONDER, room.responder_token),))]
        for racer in racers:
            racer.start()
        for racer in racers:
            racer.join(5.0)
        first = a.read_exact(5, 5.0)
        if first == PAIRED:
            b.expect(WAITING + PAIRED + CANCEL, 'race lost by CANCEL: its bytes are opaque')
            outcomes['paired'] += 1
        else:
            reply = first + a.read_exact(1, 5.0)
            require(reply == refused(CANCELED), f'race initiator got {reply.hex()}')
            a.expect_end('race initiator after REFUSED')
            b.expect_refused(INVALID_ROOM, 'race won by CANCEL: the room is gone')
            outcomes['canceled'] += 1
        a.close()
        b.close()
    counts = relay.require_clean('cancellation race', 10.0)
    require(relay.stop() == 0, 'cancel relay stop')
    return {'refusals': counts['refusals'], 'race_outcomes': outcomes}


def case_capacity(ctx: Context) -> dict:
    soft, hard = resource.getrlimit(resource.RLIMIT_NOFILE)
    resource.setrlimit(resource.RLIMIT_NOFILE, (min(hard, 8192), hard))
    details = {}

    relay = ctx.relay('capacity-principal')
    relay.start()
    alice = ctx.principal('alice')
    streams = [ctx.peer(relay, alice) for _ in range(4)]
    extra = ctx.peer(relay, alice)
    extra.expect_refused(CAPACITY, 'fifth stream of one principal')
    extra.close()
    rooms = [ctx.remember(peer.reserve()) for peer in streams[:2]]
    streams[2].send(reserve())
    streams[2].expect_refused(CAPACITY, 'third room of one principal')
    streams[2].close()
    replacement = ctx.peer(relay, alice)
    replacement.send(CANCEL)
    replacement.expect_refused(INVALID_REQUEST, 'a released stream slot is reusable')
    for peer in streams + [replacement]:
        peer.close()
    relay.require_clean('principal quotas')
    require(relay.stop() == 0, 'principal capacity stop')
    details['principal'] = {'streams': 4, 'rooms': len(rooms)}

    relay = ctx.relay('capacity-rooms')
    relay.start()
    holders = []
    for index in range(256):
        owner = ctx.principal(f'room-owner-{index}')
        for _ in range(2):
            peer = ctx.peer(relay, owner)
            room = ctx.remember(peer.reserve())
            peer.send(join(room.room, ROLE_INITIATOR, room.initiator_token))
            holders.append((peer, room))
    for peer, _ in holders:
        peer.expect(WAITING, 'room holder waits')
    counts = relay.counts()
    require(counts['rooms'] == 512, f'{counts["rooms"]} rooms')
    late = ctx.peer(relay, ctx.principal('late'))
    late.send(reserve())
    late.expect_refused(CAPACITY, '513th room')
    late.close()
    # A full room table does not stop a responder from completing a room.
    peer, room = holders[0]
    responder = ctx.peer(relay, ctx.principal('responder'))
    responder.send(join(room.room, ROLE_RESPONDER, room.responder_token))
    responder.expect(WAITING + PAIRED, 'responder pairs in a full table')
    peer.expect(PAIRED, 'holder PAIRED')
    responder.close()
    for peer, _ in holders:
        peer.close()
    relay.require_clean('room capacity', 15.0)
    require(relay.stop() == 0, 'room capacity stop')
    details['rooms'] = 512

    relay = ctx.relay('capacity-streams')
    relay.start()
    peers = []
    for index in range(256):
        owner = ctx.principal(f'stream-owner-{index}')
        peers.extend(ctx.peer(relay, owner) for _ in range(4))
    relay.wait_for(lambda c: c['streams'] == 1024, '1024 streams admitted')
    overflow = ModulePeer(relay.path, ctx.principal('overflow'))
    overflow.expect_end('the 1025th stream closes without bytes')
    overflow.close()
    counts = relay.counts()
    require(counts['over_capacity'] == 1, f'over capacity {counts["over_capacity"]}')
    for peer in peers:
        peer.close()
    relay.require_clean('stream capacity', 15.0)
    require(relay.stop() == 0, 'stream capacity stop')
    details['streams'] = 1024

    relay = ctx.relay('capacity-principal-table')
    relay.start()
    retained = []
    started = time.monotonic()
    for batch in range(32):
        group = []
        for index in range(128):
            owner = ctx.principal(f'table-{batch}-{index}')
            peer = ctx.peer(relay, owner)
            peer.send(reserve(1_000))
            group.append((peer, owner))
        for peer, owner in group:
            ctx.remember(Reservation(peer.read_exact(105), 1_000))
            peer.close()
            retained.append(owner)
    counts = relay.wait_for(lambda c: c['streams'] == 0 and c['rooms'] == 0, 'table filled')
    require(counts['principals'] == 4096, f'{counts["principals"]} principals retained')
    require(time.monotonic() - started < 50, 'filling the table took too long to test retention')
    newcomer = ctx.peer(relay, ctx.principal('newcomer'))
    newcomer.expect_refused(CAPACITY, 'a new principal while the table is full')
    newcomer.close()
    returning = ctx.peer(relay, retained[17])
    returning.send(reserve(1_000))
    ctx.remember(Reservation(returning.read_exact(105), 1_000))
    returning.close()
    counts = relay.require_clean('principal table')
    require(counts['principals'] == 4096, 'no retained principal was evicted')
    require(relay.stop() == 0, 'principal table stop')
    details['principals'] = 4096
    resource.setrlimit(resource.RLIMIT_NOFILE, (soft, hard))
    return details


def case_rates(ctx: Context) -> dict:
    relay = ctx.relay('rates')
    relay.start()
    reserver, guesser, owner = (ctx.principal('reserver'), ctx.principal('guesser'),
                                ctx.principal('owner'))
    first = time.monotonic()
    for _ in range(10):
        peer = ctx.peer(relay, reserver)
        ctx.remember(peer.reserve(1_000))
        peer.close()
    for attempt in ('same window', 'after reconnect'):
        peer = ctx.peer(relay, reserver)
        peer.send(reserve(1_000))
        peer.expect_refused(CAPACITY, f'eleventh RESERVE, {attempt}')
        peer.close()

    holder = ctx.peer(relay, owner)
    room = ctx.remember(holder.reserve(150_000))
    holder.send(join(room.room, ROLE_INITIATOR, room.initiator_token))
    holder.expect(WAITING, 'holder waits for the guesser')
    guessed = time.monotonic()
    for index in range(20):
        peer = ctx.peer(relay, guesser)
        peer.send(join(room.room, ROLE_RESPONDER, bytes([index]) * 32))
        peer.expect_refused(INVALID_ROOM, f'wrong guess {index}')
        peer.close()
    for attempt in ('same window', 'after reconnect'):
        peer = ctx.peer(relay, guesser)
        peer.send(join(room.room, ROLE_RESPONDER, room.responder_token))
        peer.expect_refused(CAPACITY, f'correct token at the failure quota, {attempt}')
        peer.close()
    counts = relay.counts()
    require(counts['principals'] == 3, f'retained principals {counts["principals"]}')

    time.sleep(max(0.0, first + 61 - time.monotonic()))
    peer = ctx.peer(relay, reserver)
    ctx.remember(peer.reserve(1_000))
    peer.close()
    time.sleep(max(0.0, guessed + 61 - time.monotonic()))
    peer = ctx.peer(relay, guesser)
    peer.send(join(room.room, ROLE_RESPONDER, room.responder_token))
    peer.expect(WAITING + PAIRED, 'the window rolled, so the correct token pairs')
    holder.expect(PAIRED, 'holder PAIRED')
    peer.close()
    holder.close()
    relay.require_clean('rates')
    # Retention ends 60 s after each principal's newest counted request.
    counts = relay.wait_for(lambda c: c['principals'] == 0, 'retention ends', 70.0)
    require(relay.stop() == 0, 'rates relay stop')
    return {'principals_after_retention': counts['principals']}


def case_backpressure_and_stall(ctx: Context) -> dict:
    relay = ctx.relay('stall')
    relay.start()
    details = {}
    quiet_a, quiet_b, _ = pair(ctx, relay, ctx.principal('quiet-a'), ctx.principal('quiet-b'))
    # Silence longer than the stall timeout, as during consent or a quiet
    # call, is not a stall: nothing waits to be written.
    time.sleep(6.5)
    try:
        quiet_a.send(b'after silence')
    except OSError as error:
        raise Failure(f'idle pair survives the stall timeout: {error}') from None
    quiet_b.expect(b'after silence', 'idle pair survives the stall timeout')
    slow_a, slow_b, _ = pair(ctx, relay, ctx.principal('slow-a'), ctx.principal('slow-b'))
    stuck_a, stuck_b, _ = pair(ctx, relay, ctx.principal('stuck-a'), ctx.principal('stuck-b'))

    # A sender facing a reader that never reads is held back by the relay.
    stream = pattern(b'stall', 32 << 20)
    stuck_a.socket.setblocking(False)
    sent = 0
    last_progress = time.monotonic()
    while time.monotonic() - last_progress < 1.0:
        try:
            count = stuck_a.socket.send(stream[sent:sent + 65536])
            sent += count
            last_progress = time.monotonic()
        except BlockingIOError:
            time.sleep(0.01)
    counts = relay.counts()
    require(counts['queued_bytes'] <= QUEUE_BYTES, f'queued {counts["queued_bytes"]} bytes')
    details['sent_before_backpressure'] = sent
    details['queued_while_blocked'] = counts['queued_bytes']

    # Without reading the stuck reader, wait for the relay to close the
    # sender. The relay's last write progress precedes the sender's last
    # accepted send, so the stall shows within five seconds of it.
    _, ended = stuck_a.read_until_end(10.0)
    elapsed = time.monotonic() - last_progress
    require(ended, 'the stalled pair stayed open')
    require(2.0 <= elapsed <= 7.0, f'stall closed {elapsed:.2f} s after the last send')
    details['stall_seconds_after_last_send'] = round(elapsed, 3)
    data, ended = stuck_b.read_until_end(5.0, limit=sent)
    require(ended and stream[:len(data)] == data, 'bytes before the stall differ')
    details['delivered_before_stall'] = len(data)

    # A reader that drains 64 KiB every two seconds always lets the relay
    # write within five seconds, so it is never stalled. A kernel segment
    # frees the relay's buffer only once it is read completely.
    slow_stream = pattern(b'slow', 8 << 20)
    writer = threading.Thread(target=lambda: _send_until_closed(slow_a, slow_stream))
    writer.start()
    slow_bytes = bytearray()
    try:
        for _ in range(6):
            slow_bytes += slow_b.read_exact(65536, 5.0)
            time.sleep(2.0)
    except Failure as failure:
        raise Failure(f'slow reader that keeps draining was closed: {failure}') from None
    require(bytes(slow_bytes) == slow_stream[:len(slow_bytes)], 'slow reader bytes differ')
    details['slow_reader_bytes'] = len(slow_bytes)

    # The quiet pair idled through all of this and still works.
    quiet_a.send(b'quiet')
    quiet_b.expect(b'quiet', 'idle pair survives')
    slow_a.close()
    slow_b.close()
    writer.join(10.0)
    for peer in (quiet_a, quiet_b, stuck_a, stuck_b):
        peer.close()
    counts = relay.require_clean('stall')
    # Only the stuck pair stalls. The slow pair ends when its reader closes
    # with bytes still queued, and the quiet pair when it closes.
    require(counts['paired_ends'][2] == 1, f'stalled ends {counts["paired_ends"]}')
    require(sum(counts['paired_ends']) == 3, f'paired ends {counts["paired_ends"]}')
    require(counts['peak_queued_bytes'] <= 3 * QUEUE_BYTES, f'peak {counts["peak_queued_bytes"]}')
    require(relay.stop() == 0, 'stall relay stop')
    details['peak_queued_bytes'] = counts['peak_queued_bytes']
    return details


def _send_until_closed(peer: ModulePeer, data: bytes) -> None:
    try:
        peer.send(data)
    except OSError:
        pass


def case_byte_cap(ctx: Context) -> dict:
    relay = ctx.relay('byte-cap')
    relay.start()
    a, b, _ = pair(ctx, relay, ctx.principal('cap-a'), ctx.principal('cap-b'))
    stream = pattern(b'cap', FORWARD_CAP + (1 << 20))
    writer = threading.Thread(target=lambda: _send_until_closed(a, stream))
    writer.start()
    data, ended = b.read_until_end(120.0, limit=len(stream))
    writer.join(30.0)
    require(ended, 'the capped direction did not end')
    require(len(data) == FORWARD_CAP, f'{len(data)} bytes, expected exactly {FORWARD_CAP}')
    require(hashlib.sha256(data).digest() == hashlib.sha256(stream[:FORWARD_CAP]).digest(),
            'capped bytes differ')
    a.close()
    b.close()
    counts = relay.require_clean('byte cap')
    require(counts['paired_ends'][3] == 1, f'byte-limit ends {counts["paired_ends"]}')
    require(relay.stop() == 0, 'byte cap relay stop')
    return {'delivered': len(data), 'sha256': hashlib.sha256(data).hexdigest()}


def case_restart(ctx: Context) -> dict:
    relay = ctx.relay('restart')
    relay.start()
    alice, bob = ctx.principal('alice'), ctx.principal('bob')
    waiting = ctx.peer(relay, alice)
    room = ctx.remember(waiting.reserve())
    waiting.send(join(room.room, ROLE_INITIATOR, room.initiator_token))
    waiting.expect(WAITING, 'waits before the crash')
    a, b, _ = pair(ctx, relay, ctx.principal('paired-a'), ctx.principal('paired-b'))
    relay.kill()
    for peer, name in ((waiting, 'waiting stream'), (a, 'paired initiator'), (b, 'paired responder')):
        _, ended = peer.read_until_end(3.0)
        require(ended, f'{name} survived a crash')
        peer.close()
    # A stream opened while the relay is down waits in the listener queue.
    queued = ctx.peer(relay, bob)
    queued.send(reserve(1_000))
    relay.start()
    ctx.remember(Reservation(queued.read_exact(105), 1_000))
    queued.close()
    late = ctx.peer(relay, bob)
    late.send(join(room.room, ROLE_RESPONDER, room.responder_token))
    late.expect_refused(INVALID_ROOM, 'tokens from before the restart')
    late.close()
    fresh_a, fresh_b, _ = pair(ctx, relay, ctx.principal('fresh-a'), ctx.principal('fresh-b'))
    exchange(fresh_a, fresh_b, 65536, b'restart')
    fresh_a.close()
    fresh_b.close()
    relay.require_clean('restart')
    code = relay.stop()
    require(code == 0, f'orderly stop exit {code}')
    lines = relay.lines()
    require(lines[-1] == {'ito_call_relay': 'stopped'}, f'last line {lines[-1]}')
    return {'orderly_stop_exit': code}


def case_log_privacy(ctx: Context) -> dict:
    lines = 0
    for relay in ctx.relays:
        if not relay.log_path.exists():
            continue
        text = relay.log_path.read_text(encoding='ascii')
        for value in ctx.secrets:
            require(value not in text, f'{relay.log_path.name} contains a principal or token')
        for raw in text.splitlines():
            parsed = json.loads(raw)
            require(set(parsed) <= {'ito_call_relay', 'reason', 'streams', 'principals', 'rooms',
                                    'paired_rooms', 'queued_bytes', 'peak_queued_bytes',
                                    'accepted', 'untrusted_peers', 'over_capacity',
                                    'rejected_headers', 'reservations', 'pairings',
                                    'closed_rooms', 'unqueued_retentions', 'refusals',
                                    'paired_ends'},
                    f'unexpected log fields {sorted(parsed)}')
            lines += 1
    return {'lines': lines, 'secrets_checked': len(ctx.secrets)}


NAMESPACE_DAEMON_UID = 1000
NAMESPACE_RELAY_UID = 1001
NAMESPACE_OTHER_UID = 1002


def _as_user(uid: int, action: Callable[[], bool]) -> bool:
    """Runs action in a child with only uid's credentials."""
    pid = os.fork()
    if pid == 0:
        code = 1
        try:
            os.setgroups([])
            os.setresgid(uid, uid, uid)
            os.setresuid(uid, uid, uid)
            code = 0 if action() else 1
        except BaseException as error:  # reported, then the child fails
            print(f'user {uid}: {type(error).__name__}: {error}', file=sys.stderr, flush=True)
        finally:
            os._exit(code)
    _, status = os.waitpid(pid, 0)
    return os.waitstatus_to_exitcode(status) == 0


def _reserves(path: Path, principal: str) -> bool:
    peer = ModulePeer(path, principal)
    peer.send(reserve(1_000))
    reply = peer.read_exact(105, 5.0)
    peer.close()
    return reply[4:5] == b'\x02'


def _closed_silently(path: Path, principal: str) -> bool:
    """Connects, then requires the relay to close without sending a byte.

    The relay may close before the header is even written, so a refused
    send counts as closed. The relay's untrusted-peer count, checked by the
    caller, shows that the relay itself refused the connection.
    """
    peer = ModulePeer(path, None)
    try:
        peer.send(header(principal) + reserve(1_000))
    except (BrokenPipeError, ConnectionResetError):
        pass
    data, ended = peer.read_until_end(5.0)
    peer.close()
    return ended and data == b''


def run_namespace_child(program: Path, result: Path) -> int:
    """Runs as root of a user namespace with subordinate IDs mapped."""
    require(os.getuid() == 0, 'the namespace child is not root in its namespace')
    os.umask(0o022)
    directory = Path(tempfile.mkdtemp(prefix='ito-relay-ns-', dir='/tmp'))
    os.chmod(directory, 0o755)
    relay = RelayProcess(program, directory)
    os.chown(relay.path, NAMESPACE_DAEMON_UID, NAMESPACE_DAEMON_UID)
    os.chown(relay.log_path.parent, 0, 0)
    setpriv = shutil.which('setpriv')
    require(setpriv is not None, 'setpriv is required')
    as_relay = [setpriv, f'--reuid={NAMESPACE_RELAY_UID}', f'--regid={NAMESPACE_RELAY_UID}',
                '--clear-groups', '--']
    details = {}
    try:
        # A relay that assumes its own user is the daemon's refuses to start.
        relay.spawn(['--service', 'ito-call', '--daemon-uid', str(NAMESPACE_RELAY_UID)],
                    prefix=as_relay)
        require(relay.wait(10.0) == 1, 'a relay trusting its own user started')
        require('belong to the daemon user' in failed_reason(relay.lines()),
                'wrong refusal for an assumed daemon user')
        relay.log_path.unlink()
        relay.spawn(['--service', 'ito-call', '--daemon-uid', str(NAMESPACE_DAEMON_UID)],
                    prefix=as_relay)
        deadline = time.monotonic() + 5
        while not any(line.get('ito_call_relay') == 'ready' for line in relay.lines()):
            require(time.monotonic() < deadline and relay.poll() is None,
                    'the relay as its own user did not start')
            time.sleep(0.01)
        principal = hashlib.sha256(b'namespace').hexdigest()
        require(_as_user(NAMESPACE_DAEMON_UID, lambda: _reserves(relay.path, principal)),
                'the pinned daemon user was refused')
        require(_reserves(relay.path, principal), 'root was refused')
        # Open the socket file to everyone. Only SO_PEERCRED stands between a
        # third user and a forged header now.
        os.chmod(relay.path, 0o666)
        require(_as_user(NAMESPACE_OTHER_UID, lambda: _closed_silently(relay.path, principal)),
                'a third user was served')
        # The relay's own user is not the daemon's either.
        require(_as_user(NAMESPACE_RELAY_UID, lambda: _closed_silently(relay.path, principal)),
                "the relay's own user was served")
        counts = relay.counts()
        require(counts['untrusted_peers'] == 2, f'untrusted peers {counts["untrusted_peers"]}')
        require(counts['accepted'] == 2, f'accepted {counts["accepted"]}')
        details = {'relay_uid': NAMESPACE_RELAY_UID, 'daemon_uid': NAMESPACE_DAEMON_UID,
                   'untrusted_uids': [NAMESPACE_OTHER_UID, NAMESPACE_RELAY_UID],
                   'untrusted_peers': 2}
        require(relay.stop() == 0, 'namespace relay stop')
    finally:
        relay.close()
        shutil.rmtree(directory, ignore_errors=True)
    result.write_text(json.dumps(details) + '\n', encoding='utf-8')
    return 0


def case_peer_credentials(ctx: Context) -> dict:
    unshare = shutil.which('unshare')
    require(unshare is not None and shutil.which('newuidmap') is not None
            and shutil.which('setpriv') is not None,
            'unavailable: unshare, newuidmap and setpriv are required')
    output = ctx.root / 'namespace-result.json'
    run = subprocess.run([unshare, '--user', '--map-root-user', '--map-auto', '--fork',
                          sys.executable, '-B', str(Path(__file__).resolve()), '--relay',
                          str(ctx.program), '--namespace-child', str(output)],
                         capture_output=True, text=True, timeout=120, check=False)
    require(run.returncode == 0, f'namespace child failed: {run.stderr.strip()[-600:]}')
    return json.loads(output.read_text(encoding='utf-8'))


CASES: list[tuple[str, Callable[[Context], dict]]] = [
    ('usage', case_usage),
    ('listener_checks', case_listener_checks),
    ('headers', case_headers),
    ('framing', case_framing),
    ('pairing_and_exact_bytes', case_pairing),
    ('tokens_and_roles', case_tokens),
    ('cancel_and_disconnect', case_cancel_and_disconnect),
    ('capacity', case_capacity),
    ('rate_retention', case_rates),
    ('backpressure_and_stall', case_backpressure_and_stall),
    ('byte_cap', case_byte_cap),
    ('restart', case_restart),
    ('peer_credentials', case_peer_credentials),
    ('log_privacy', case_log_privacy),
]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--relay', type=Path, required=True)
    parser.add_argument('--result', type=Path, help='write a JSON result here')
    parser.add_argument('--only', action='append', help='run only the named cases')
    parser.add_argument('--namespace-child', type=Path, help=argparse.SUPPRESS)
    parser.add_argument('--without-namespace', action='store_true',
                        help='record peer_credentials as unavailable instead of failing')
    arguments = parser.parse_args()
    program = arguments.relay.resolve(strict=True)
    require(program.is_file(), 'the relay program is not a regular file')
    if arguments.namespace_child:
        return run_namespace_child(program, arguments.namespace_child)
    if arguments.without_namespace:
        arguments.only = arguments.only or [name for name, _ in CASES]
        arguments.only = [name for name in arguments.only if name != 'peer_credentials']
    selected = [case for case in CASES if not arguments.only or case[0] in arguments.only]
    require(selected, 'no case selected')
    results = []
    os.umask(0o077)
    with tempfile.TemporaryDirectory(prefix='ito-relay-', dir='/tmp') as temporary:
        ctx = Context(program, Path(temporary))
        try:
            for name, case in selected:
                started = time.monotonic()
                entry = {'case': name}
                try:
                    entry['details'] = case(ctx)
                    entry['passed'] = True
                except (Failure, OSError, ValueError, subprocess.SubprocessError) as error:
                    entry['passed'] = False
                    entry['error'] = f'{type(error).__name__}: {error}'
                    entry['trace'] = traceback.format_exc(limit=4)
                entry['seconds'] = round(time.monotonic() - started, 2)
                print(f"{'PASS' if entry['passed'] else 'FAIL'} {name} ({entry['seconds']} s)"
                      + ('' if entry['passed'] else f": {entry['error']}"), flush=True)
                results.append(entry)
        finally:
            for relay in ctx.relays:
                relay.close()
    summary = {'schema': 1, 'program': str(program),
               'program_sha256': hashlib.sha256(program.read_bytes()).hexdigest(),
               'cases': results, 'passed': all(entry['passed'] for entry in results),
               'count': len(results)}
    if arguments.result:
        arguments.result.write_text(json.dumps(summary, indent=2) + '\n', encoding='utf-8')
    return 0 if summary['passed'] and len(results) == len(selected) else 1


if __name__ == '__main__':
    try:
        sys.exit(main())
    except Failure as failure:
        print(f'component test: {failure}', file=sys.stderr)
        sys.exit(1)
