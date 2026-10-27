#!/usr/bin/env python3
# Copyright (C) 2026 FixCraft Inc. AGPL-3.0-or-later.
"""Qualifies ito-call-relay behind a real yumed with real libyume clients.

yume-setup provisions one server and four client identities. alice, bob and
dave hold the ito-call stream grant. carol is authenticated for the kit's
standard services only, so yumed must refuse her ito-call OPEN before the
relay sees a connection. Every client opens its streams through the public
libyume C ABI. The relay runs on the module socket that yumed's `module`
adapter names, from a listener this harness holds as a socket unit would.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import signal
import socket
import subprocess
import sys
import tempfile
import threading
import time
import traceback
from pathlib import Path
from typing import Callable

sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parent))
from relay_peers import (PAIRED, WAITING, CANCEL, Failure, RelayProcess,  # noqa: E402
                         Reservation, exchange, join, pattern, require, reserve)
from yume_abi import (STATUS_EOF, STATUS_OK, STATUS_PERMISSION_DENIED, AbiStream,  # noqa: E402
                      Client, Library, json_file)

SERVICE = 'ito-call'
INVALID_ROOM, UNSUPPORTED, CAPACITY, CANCELED, TIMEOUT, INVALID_REQUEST = range(1, 7)
GRANTED = ('alice', 'bob', 'dave')
UNGRANTED = 'carol'


class Harness:
    def __init__(self, arguments: argparse.Namespace, root: Path) -> None:
        self.args = arguments
        self.root = root
        self.library = Library(arguments.libyume)
        self.clients: dict[str, Client] = {}
        self.fingerprints: dict[str, str] = {}
        self.secrets: set[str] = set()
        self.yumed: subprocess.Popen | None = None
        relay_directory = root / 'module'
        relay_directory.mkdir(mode=0o700)
        self.relay = RelayProcess(arguments.relay, relay_directory)
        self.kit = root / 'kit'

    # Provisioning and processes.

    def setup(self, *arguments: str) -> None:
        run = subprocess.run([str(self.args.setup), *arguments], capture_output=True, text=True,
                             timeout=120, check=False)
        require(run.returncode == 0, f'yume-setup {arguments[0]}: {run.stderr.strip()}')

    def provision(self) -> None:
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as reservation:
            reservation.bind(('127.0.0.1', 0))
            self.port = reservation.getsockname()[1]
        self.setup('init', '--host', 'localhost', '--port', str(self.port),
                   '--output', str(self.kit), '--client-name', 'alice')
        for name in ('bob', 'carol', 'dave'):
            self.setup('add-client', '--server', str(self.kit / 'server'), '--host', 'localhost',
                       '--output', str(self.kit / name), '--client-name', name)
        directories = {'alice': self.kit / 'client', 'bob': self.kit / 'bob',
                       'carol': self.kit / 'carol', 'dave': self.kit / 'dave'}
        self.directories = directories
        service = {'name': SERVICE, 'kind': 'stream', 'max_concurrent_streams': 8}
        server_path = self.kit / 'server/yumed.json'
        server = json_file(server_path)
        server['services'].append(service)
        server['adapters'].append({'kind': 'module', 'service': SERVICE,
                                   'socket': str(self.relay.path)})
        server['endpoint']['listen_addresses'] = ['127.0.0.1']
        server_path.write_text(json.dumps(server, indent=2), encoding='utf-8')
        for name, directory in directories.items():
            path = directory / 'yume.json'
            client = json_file(path)
            # The embedding backend composes named services only.
            client['services'] = [service]
            client['adapters'] = []
            client['endpoint']['connect_address'] = '127.0.0.1'
            path.write_text(json.dumps(client, indent=2), encoding='utf-8')
        keys_path = self.kit / 'server/credentials/authorized-keys.json'
        keys = json_file(keys_path)
        names = {entry['name']: entry for entry in keys['keys']}
        require(set(names) == set(directories), f'authorized names {sorted(names)}')
        for name, entry in names.items():
            self.fingerprints[name] = entry['identity']['sha256']
            granted = [grant for grant in entry['capabilities'] if grant['service'] != SERVICE]
            if name in GRANTED:
                granted.append({'service': SERVICE, 'kind': 'stream'})
            entry['capabilities'] = granted
        keys_path.write_text(json.dumps(keys, indent=2), encoding='utf-8')
        self.secrets.update(self.fingerprints.values())
        self.relay.start()
        validate = subprocess.run([str(self.args.yumed), '--config', str(server_path), '--validate'],
                                  capture_output=True, text=True, timeout=60, check=False)
        require(validate.returncode == 0, f'yumed --validate: {validate.stderr.strip()}')

    def start_yumed(self) -> None:
        log = open(self.root / 'yumed.log', 'ab')
        self.yumed = subprocess.Popen([str(self.args.yumed), '--config',
                                       str(self.kit / 'server/yumed.json')],
                                      stdin=subprocess.DEVNULL, stdout=log, stderr=log,
                                      start_new_session=True)
        log.close()
        deadline = time.monotonic() + 30
        while True:
            require(self.yumed.poll() is None, f'yumed exited with {self.yumed.returncode}')
            try:
                with socket.create_connection(('127.0.0.1', self.port), timeout=1):
                    return
            except OSError:
                require(time.monotonic() < deadline, 'yumed did not listen')
                time.sleep(0.1)

    def client(self, name: str) -> Client:
        if name not in self.clients:
            client = Client(self.library, self.directories[name], self.args.resolver)
            status = client.start()
            require(status == STATUS_OK, f'{name} start {status}')
            self.clients[name] = client
        return self.clients[name]

    def restart_client(self, name: str) -> Client:
        client = self.clients.pop(name)
        client.destroy()
        return self.client(name)

    def open(self, name: str) -> AbiStream:
        status, stream = self.client(name).open(SERVICE)
        require(status == STATUS_OK and stream is not None, f'{name} ito-call OPEN {status}')
        return stream

    def remember(self, reservation: Reservation) -> Reservation:
        for value in (reservation.room, reservation.initiator_token, reservation.responder_token):
            self.secrets.add(value.hex())
        return reservation

    def pair(self, initiator: str, responder: str,
             early: bytes = b'') -> tuple[AbiStream, AbiStream, Reservation]:
        a = self.open(initiator)
        room = self.remember(a.reserve())
        a.send(join(room.room, 1, room.initiator_token))
        a.expect(WAITING, f'{initiator} WAITING')
        b = self.open(responder)
        b.send(join(room.room, 2, room.responder_token) + early)
        b.expect(WAITING + PAIRED, f'{responder} WAITING then PAIRED')
        a.expect(PAIRED + early, f'{initiator} PAIRED, then the bytes after JOIN')
        return a, b, room

    def close(self) -> None:
        for client in self.clients.values():
            client.destroy()
        self.clients.clear()
        if self.yumed and self.yumed.poll() is None:
            os.killpg(self.yumed.pid, signal.SIGTERM)
            try:
                self.yumed.wait(10)
            except subprocess.TimeoutExpired:
                os.killpg(self.yumed.pid, signal.SIGKILL)
                self.yumed.wait(10)
        self.relay.close()


# Scenarios, in order. Later ones rely on the relay state earlier ones leave.

def granted_pairing(h: Harness) -> dict:
    early = pattern(b'yumed-early', 16 * 1024)
    a, b, _ = h.pair('alice', 'bob', early)
    details = {'pipelined_after_join': len(early), 'exchange': exchange(a, b, 1 << 20, b'yumed')}
    tail = pattern(b'yumed-hangup', 96 * 1024)
    a.send(tail)
    a.shutdown_write()
    data, ended = b.read_until_end(10.0)
    require(ended and data == tail, f'hangup through yumed delivered {len(data)} bytes')
    require(b.end_status == STATUS_EOF, f'hangup ended the responder with {b.end_status}')
    _, ended = a.read_until_end(10.0)
    require(ended, 'initiator stream stayed open after hangup')
    require(a.end_status == STATUS_EOF, f'hangup ended the initiator with {a.end_status}')
    a.close()
    b.close()
    counts = h.relay.require_clean('granted pairing', 10.0)
    require(counts['pairings'] == 1 and counts['paired_ends'][0] == 1, f'counts {counts}')
    return details


def ungranted_refused(h: Harness) -> dict:
    before = h.relay.counts()['accepted']
    carol = h.client(UNGRANTED)
    status, stream = carol.open(SERVICE)
    require(status == STATUS_PERMISSION_DENIED and stream is None,
            f'ungranted ito-call OPEN returned {status}')
    # A destination-routed OPEN is refused even for a granted identity.
    status, stream = h.client('alice').open(SERVICE, destination=('example.com', 443))
    require(status == STATUS_PERMISSION_DENIED and stream is None,
            f'destination-routed OPEN returned {status}')
    after = h.relay.counts()['accepted']
    require(after == before, f'the relay accepted {after - before} refused streams')
    require(carol.running(), "carol's session ended on a refused OPEN")
    return {'ungranted_status': STATUS_PERMISSION_DENIED, 'relay_connections_added': 0}


def principal_quota(h: Harness) -> dict:
    streams = [h.open('alice') for _ in range(4)]
    extra = h.open('alice')
    extra.expect_refused(CAPACITY, "alice's fifth stream")
    extra.close()
    # bob is another transport principal with his own quota.
    other = h.open('bob')
    other.send(CANCEL)
    other.expect_refused(INVALID_REQUEST, 'bob admitted beside four alice streams')
    other.close()
    for stream in streams:
        stream.close()
    h.relay.require_clean('principal quota', 10.0)
    return {'alice_streams': 4, 'fifth_refused': CAPACITY}


def stall_through_yumed(h: Harness) -> dict:
    quiet_a, quiet_b, _ = h.pair('dave', 'alice')
    stuck_a, stuck_b, _ = h.pair('bob', 'alice')
    flood = pattern(b'yumed-stall', 64 << 20)
    sent = 0
    last_progress = time.monotonic()
    closed_status = None
    while time.monotonic() - last_progress < 15.0:
        chunk = flood[sent:sent + 65536]
        status = stuck_a.try_send(chunk, 200)
        if status == STATUS_OK:
            sent += len(chunk)
            last_progress = time.monotonic()
        elif status not in (-5, -6):
            closed_status = status
            break
    require(closed_status is not None, 'the stalled sender stayed open')
    elapsed = time.monotonic() - last_progress
    require(elapsed <= 12.0, f'stall ended {elapsed:.1f} s after the last write')
    data, ended = stuck_b.read_until_end(20.0, limit=sent)
    require(ended and flood[:len(data)] == data, 'bytes before the stall differ')
    # A quiet pair idled meanwhile and still forwards.
    quiet_a.send(b'consent pending')
    quiet_b.expect(b'consent pending', 'idle pair survives the neighbour stall')
    for stream in (quiet_a, quiet_b, stuck_a, stuck_b):
        stream.close()
    counts = h.relay.require_clean('stall through yumed', 10.0)
    require(counts['paired_ends'][2] == 1, f'stalled ends {counts["paired_ends"]}')
    return {'accepted_before_stall': sent, 'delivered': len(data),
            'seconds_after_last_write': round(elapsed, 2), 'writer_status': closed_status}


def relay_restart(h: Harness) -> dict:
    waiting = h.open('alice')
    room = h.remember(waiting.reserve())
    waiting.send(join(room.room, 1, room.initiator_token))
    waiting.expect(WAITING, 'waits before the relay restarts')
    h.relay.kill()
    _, ended = waiting.read_until_end(10.0)
    require(ended, 'a stream survived the relay crash')
    waiting.close()
    # yumed connects while the relay is down. The listener queues the stream.
    queued = h.open('bob')
    queued.send(reserve(1_000))
    h.relay.start()
    h.remember(Reservation(queued.read_exact(105, 10.0), 1_000))
    queued.close()
    late = h.open('bob')
    late.send(join(room.room, 2, room.responder_token))
    late.expect_refused(INVALID_ROOM, 'tokens issued before the restart')
    late.close()
    a, b, _ = h.pair('alice', 'bob')
    exchange(a, b, 128 * 1024, b'after-restart')
    a.close()
    b.close()
    h.relay.require_clean('restart', 10.0)
    return {'old_token_refused': INVALID_ROOM}


def authority_loss(h: Harness) -> dict:
    a, b, _ = h.pair('dave', 'alice')
    exchange(a, b, 32 * 1024, b'before-revocation')
    h.setup('remove-client', '--server', str(h.kit / 'server'), '--client-name', 'dave')
    os.kill(h.yumed.pid, signal.SIGHUP)
    data, ended = b.read_until_end(15.0)
    require(ended and data == b'', f'alice kept her stream after dave lost authority ({len(data)})')
    _, ended = a.read_until_end(10.0)
    require(ended, "dave's stream survived his revocation")
    details = {'alice_end_status': b.end_status, 'dave_end_status': a.end_status}
    a.close()
    b.close()
    counts = h.relay.require_clean('authority loss', 10.0)
    dave = h.clients.pop('dave')
    dave.destroy()
    replacement = Client(h.library, h.directories['dave'], h.args.resolver)
    status = replacement.start(10_000)
    replacement.destroy()
    require(status != STATUS_OK, 'a revoked identity started a new session')
    return dict(details, paired_ends=counts['paired_ends'], revoked_restart_status=status)


def rate_retention(h: Harness) -> dict:
    # Earlier scenarios counted RESERVEs for bob in this window. A restart
    # drops every room and rate bucket, so the quota starts from zero.
    require(h.relay.stop() == 0, 'relay stop before the rate scenario')
    h.relay.start()
    for _ in range(10):
        stream = h.open('bob')
        h.remember(stream.reserve(1_000))
        stream.close()
    stream = h.open('bob')
    stream.send(reserve(1_000))
    stream.expect_refused(CAPACITY, 'eleventh RESERVE through yumed')
    stream.close()
    # A new YUME session for the same identity keeps the same quota.
    h.restart_client('bob')
    stream = h.open('bob')
    stream.send(reserve(1_000))
    stream.expect_refused(CAPACITY, 'RESERVE after a new YUME session')
    stream.close()
    counts = h.relay.require_clean('rate retention', 10.0)
    require(counts['principals'] >= 1, 'no principal retained its window')
    return {'retained_principals': counts['principals']}


def log_privacy(h: Harness) -> dict:
    text = h.relay.log_path.read_text(encoding='ascii')
    for value in h.secrets:
        require(value not in text, 'the relay log contains a fingerprint or token')
    return {'secrets_checked': len(h.secrets), 'log_lines': len(text.splitlines())}


SCENARIOS: list[tuple[str, Callable[[Harness], dict]]] = [
    ('granted_pairing_exact_bytes', granted_pairing),
    ('ungranted_and_routed_refused_by_yumed', ungranted_refused),
    ('principal_quota_by_transport_identity', principal_quota),
    ('stall_and_idle_through_yumed', stall_through_yumed),
    ('relay_restart_under_yumed', relay_restart),
    ('authority_loss_closes_both', authority_loss),
    ('rate_retention_across_sessions', rate_retention),
    ('log_privacy', log_privacy),
]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('relay', 'yumed', 'setup', 'libyume', 'resolver'):
        parser.add_argument(f'--{name}', type=Path, required=True)
    parser.add_argument('--result', type=Path)
    arguments = parser.parse_args()
    for name in ('relay', 'yumed', 'setup', 'libyume', 'resolver'):
        path = getattr(arguments, name).resolve(strict=True)
        require(path.is_file(), f'--{name} is not a regular file')
        setattr(arguments, name, path)
    os.umask(0o077)
    results = []
    with tempfile.TemporaryDirectory(prefix='ito-yumed-', dir='/tmp') as temporary:
        harness = Harness(arguments, Path(temporary))
        try:
            harness.provision()
            harness.start_yumed()
            for name in ('alice', 'bob', 'carol', 'dave'):
                harness.client(name)
            for name, scenario in SCENARIOS:
                started = time.monotonic()
                entry = {'scenario': name}
                try:
                    entry['details'] = scenario(harness)
                    entry['passed'] = True
                except (Failure, OSError, ValueError, subprocess.SubprocessError) as error:
                    entry['passed'] = False
                    entry['error'] = f'{type(error).__name__}: {error}'
                    entry['trace'] = traceback.format_exc(limit=4)
                entry['seconds'] = round(time.monotonic() - started, 2)
                print(f"{'PASS' if entry['passed'] else 'FAIL'} {name} ({entry['seconds']} s)"
                      + ('' if entry['passed'] else f": {entry['error']}"), flush=True)
                results.append(entry)
                if not entry['passed']:
                    break
            yumed_log = (harness.root / 'yumed.log').read_text(encoding='utf-8', errors='replace')
        finally:
            harness.close()
            relay_log = harness.relay.log_path.read_text(encoding='ascii') \
                if harness.relay.log_path.exists() else ''
    binding = {name: hashlib.sha256(getattr(arguments, name).read_bytes()).hexdigest()
               for name in ('relay', 'yumed', 'setup', 'libyume', 'resolver')}
    summary = {'schema': 1, 'binaries_sha256': binding, 'scenarios': results,
               'expected': [name for name, _ in SCENARIOS],
               'passed': len(results) == len(SCENARIOS) and all(r['passed'] for r in results),
               'relay_log': relay_log, 'yumed_log_tail': yumed_log[-4000:]}
    if arguments.result:
        arguments.result.write_text(json.dumps(summary, indent=2) + '\n', encoding='utf-8')
    return 0 if summary['passed'] else 1


if __name__ == '__main__':
    try:
        sys.exit(main())
    except Failure as failure:
        print(f'yumed harness: {failure}', file=sys.stderr)
        sys.exit(1)
