#!/usr/bin/env python3
# Copyright (C) 2026 FixCraft Inc. AGPL-3.0-or-later.
"""Two ito-call endpoints through a real yumed and ito-call-relay.

Reuses the relay's yumed harness to provision one server and four client
identities with yume-setup: alice, bob and dave hold the ito-call grant and
carol does not. Each endpoint keeps its own host test store. A recording tap
sits on the relay's module socket for the whole run. The tap scenario uses
evidence builds of the endpoint, which report the secrets they held, and
proves none of them, nor any plaintext, crossed the relay.

The invitation passes from the initiator to the responder through a private
file, standing in for an authenticated, confidential channel.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import signal
import subprocess
import sys
import tempfile
import time
import traceback
from contextlib import ExitStack, contextmanager
from pathlib import Path
from typing import Iterator

HERE = Path(__file__).resolve().parent
sys.dont_write_bytecode = True
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parents[2] / 'relay' / 'tests'))
from relay_peers import Failure, require  # noqa: E402
from relay_tap import Tap, search, secrets_from, split_connection  # noqa: E402
from yume_abi import json_file  # noqa: E402
from yumed_harness import SERVICE, Harness  # noqa: E402

CLI_TIMEOUT = 120


@contextmanager
def cli_child(command: list[str], stderr: Path) -> Iterator[subprocess.Popen]:
    descriptor = os.open(stderr, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, 'wb') as error:
        process = subprocess.Popen(command, stdin=subprocess.DEVNULL,
                                   stdout=subprocess.DEVNULL, stderr=error,
                                   start_new_session=True)
        try:
            yield process
        finally:
            if process.poll() is None:
                try:
                    os.killpg(process.pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass
            process.wait(timeout=5)


def cli_stderr(path: Path, role: str) -> str:
    """Scan the entire private log, retaining only a bounded error tail."""
    markers = (b'AddressSanitizer', b'LeakSanitizer', b'UndefinedBehaviorSanitizer',
               b'ThreadSanitizer', b'MemorySanitizer', b'runtime error:')
    tail = b''
    with path.open('rb') as error:
        while block := error.read(65536):
            window = tail + block
            require(not any(marker in window for marker in markers),
                    f'{role} reported a sanitizer diagnostic')
            tail = window[-4096:]
    return tail.decode('utf-8', errors='replace').strip()


class Endpoints:
    def __init__(self, harness: Harness, arguments: argparse.Namespace, root: Path) -> None:
        self.h = harness
        self.args = arguments
        self.root = root
        self.stores = root / 'stores'
        self.stores.mkdir(mode=0o700)
        self.work = root / 'work'
        self.work.mkdir(mode=0o700)
        self.codes: dict[str, str] = {}
        self.fingerprints: dict[str, str] = {}
        self.counter = 0

    def store(self, name: str) -> str:
        return str(self.stores / name)

    def run(self, name: str, *arguments: str, binary: Path | None = None,
            check: bool = True) -> tuple[int, dict | None, str]:
        command = [str(binary or self.args.ito_call), '--store', self.store(name), *arguments]
        run = subprocess.run(command, capture_output=True, text=True, timeout=CLI_TIMEOUT,
                             check=False)
        output = json.loads(run.stdout) if run.returncode == 0 and run.stdout.strip() else None
        if check:
            require(run.returncode == 0, f'{name} {arguments[0]}: {run.stderr.strip()}')
        return run.returncode, output, run.stderr.strip()

    def create(self, name: str) -> None:
        _, identity, _ = self.run(name, 'identity', 'create')
        self.codes[name] = identity['code']
        self.fingerprints[name] = identity['fingerprint']

    def befriend(self, a: str, b: str) -> None:
        for own, other in ((a, b), (b, a)):
            self.run(own, 'contact', 'add', self.codes[other], other)
            _, contact, _ = self.run(own, 'contact', 'verify', self.codes[other],
                                     self.fingerprints[other])
            require(contact['trust'] == 'verified', f'{own} verified {other}')

    def connection(self, name: str) -> list[str]:
        return ['--yume-config', str(self.h.directories[name]), '--service', SERVICE,
                '--resolver', str(self.args.resolver)]

    def private(self, label: str, data: bytes | None = None) -> Path:
        self.counter += 1
        path = self.work / f'{self.counter:03d}-{label}'
        if data is not None:
            descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            with os.fdopen(descriptor, 'wb') as output:
                output.write(data)
        return path

    def call(self, initiator: str, responder: str, *, initiator_args: list[str] = (),
             responder_args: list[str] = (), binary: Path | None = None,
             lifetime: int = 120, keep_invitation: bool = False,
             during=None, answerer: str | None = None) -> dict:
        """initiator invites responder. answerer, when given, is the store
        that tries to answer instead."""
        answerer = answerer or responder
        invitation = self.private('invitation')
        results = {role: self.private(f'{role}.json') for role in ('initiator', 'responder')}
        errors = {role: self.private(f'{role}.stderr') for role in results}
        program = str(binary or self.args.ito_call)
        completed = False
        try:
            with ExitStack() as children:
                caller = children.enter_context(cli_child(
                    [program, '--store', self.store(initiator), 'call',
                     '--peer', self.codes[responder], '--invitation-out', str(invitation),
                     '--lifetime', str(lifetime), '--result', str(results['initiator']),
                     *self.connection(initiator), *initiator_args], errors['initiator']))
                deadline = time.monotonic() + 30
                while not invitation.exists():
                    if caller.poll() is not None:
                        cli_stderr(errors['initiator'], 'initiator')
                        raise Failure(f'the initiator exited early ({caller.returncode})')
                    if time.monotonic() >= deadline:
                        raise Failure('no invitation within 30 s')
                    time.sleep(0.05)
                time.sleep(0.05)
                answer = children.enter_context(cli_child(
                    [program, '--store', self.store(answerer), 'answer',
                     '--invitation', str(invitation), '--result', str(results['responder']),
                     *self.connection(answerer), *responder_args], errors['responder']))
                if during is not None:
                    during()
                outcome = {}
                for role, process in (('responder', answer), ('initiator', caller)):
                    try:
                        process.wait(timeout=CLI_TIMEOUT)
                    except subprocess.TimeoutExpired:
                        raise Failure(f'{role} did not finish') from None
                    error = cli_stderr(errors[role], role)
                    if results[role].exists():
                        require(process.returncode == 0,
                                f'{role} exited {process.returncode} after writing its result')
                        outcome[role] = json_file(results[role])
                    else:
                        outcome[role] = {'exit': process.returncode, 'error': error}
                if keep_invitation:
                    outcome['invitation_path'] = str(invitation)
                completed = True
                return outcome
        finally:
            if not keep_invitation or not completed:
                wipe(invitation)


def wipe(path: Path) -> None:
    if path.exists():
        size = path.stat().st_size
        with path.open('r+b') as output:
            output.write(b'\0' * size)
            output.flush()
            os.fsync(output.fileno())
        path.unlink()


def payload_args(e: Endpoints, label: str, send: bytes, expect: int) -> tuple[list[str], Path]:
    source = e.private(f'{label}-send', send)
    received = e.private(f'{label}-received')
    return (['--send', str(source), '--expect-bytes', str(expect),
             '--received-out', str(received)], received)


def exchange_call(e: Endpoints, binary: Path | None = None) -> dict:
    to_bob, to_alice = os.urandom(256 * 1024), os.urandom(64 * 1024)
    alice_args, alice_received = payload_args(e, 'alice', to_bob, len(to_alice))
    bob_args, bob_received = payload_args(e, 'bob', to_alice, len(to_bob))
    outcome = e.call('alice', 'bob', initiator_args=alice_args, responder_args=bob_args,
                     binary=binary)
    a, b = outcome['initiator'], outcome['responder']
    require(a.get('result') == 'canceled' and a.get('local_end') == 1,
            f'initiator ended {a}')
    require(b.get('result') == 'peer_ended' and b.get('remote_end') == 1 and
            b.get('remote_end_attributed') is True, f'responder ended {b}')
    require(a['peer_authenticated'] and b['peer_authenticated'], 'both proofs verified')
    require(bob_received.read_bytes() == to_bob and alice_received.read_bytes() == to_alice,
            'payloads arrived intact')
    require(a['relay_fingerprint'] == b['relay_fingerprint'], 'one relay authority')
    return {'initiator': a, 'responder': b, 'plaintexts': [to_bob, to_alice]}


# Scenarios, in order. Later ones rely on state earlier ones leave.

def profile1_call(e: Endpoints) -> dict:
    result = exchange_call(e)
    for role in ('initiator', 'responder'):
        result[role].pop('evidence', None)
    result.pop('plaintexts')
    return result


def tap_blindness(e: Endpoints) -> dict:
    require(e.args.ito_call_evidence is not None, 'the evidence build was not supplied')
    # The detector itself finds a planted secret and a planted plaintext.
    planted = os.urandom(32)
    for secrets, plaintexts in (({'planted': planted}, []), ({}, [planted * 2])):
        try:
            search([{'to_relay': b'x' + planted * 2 + b'y'}], secrets, plaintexts)
        except Failure:
            continue
        raise Failure('the tap missed a planted value')
    before = len(e.tap.snapshot())
    result = exchange_call(e, e.args.ito_call_evidence)
    time.sleep(0.5)
    captures = e.tap.snapshot()[before:]
    require(len(captures) == 2, f'{len(captures)} relay connections recorded')
    shapes = [split_connection(record) for record in captures]
    for shape in shapes:
        for direction, records in shape['tls_records'].items():
            require(records.get(23, 0) > 0, f'application records {direction}: {records}')
    secrets: dict[str, bytes] = {}
    for role in ('initiator', 'responder'):
        evidence = result[role]['evidence']
        require(isinstance(evidence, dict), f'{role} reported no evidence')
        for name, value in secrets_from(evidence).items():
            secrets[f'{role}:{name}'] = value
    # Both sides held the same PSK and exporter, and each other's proofs.
    require(secrets['initiator:psk'] == secrets['responder:psk'], 'one PSK')
    require(secrets['initiator:exporter'] == secrets['responder:exporter'], 'one exporter')
    require(secrets['initiator:own_signature'] == secrets['responder:peer_signature'],
            'the responder verified the initiator proof')
    found = search(captures, secrets, result['plaintexts'])
    (e.root / 'tap-capture.json').write_text(json.dumps(
        [{key: value.hex() for key, value in record.items()} for record in captures]))
    return {'connections': shapes, **found}


def decline(e: Endpoints) -> dict:
    outcome = e.call('alice', 'bob', responder_args=['--decline'], keep_invitation=True)
    a, b = outcome['initiator'], outcome['responder']
    require(b.get('result') == 'declined' and b.get('local_end') == 2, f'responder {b}')
    # The responder declined before proving its identity: its END is not
    # attributed to the contact.
    require(a.get('result') == 'peer_ended' and a.get('remote_end') == 2 and
            a.get('remote_end_attributed') is False and a.get('peer_authenticated') is False,
            f'initiator {a}')
    e.declined_invitation = Path(outcome['invitation_path'])
    return {'initiator': a, 'responder': b}


def replayed_invitation(e: Endpoints) -> dict:
    result = e.private('replay.json')
    code, _, error = e.run('bob', 'answer', '--invitation', str(e.declined_invitation),
                           '--result', str(result), *e.connection('bob'), check=False)
    wipe(e.declined_invitation)
    require(code == 2 and 'invitation: already_spent' in error, f'replay: {code} {error}')
    return {'refusal': error}


def wrong_responder(e: Endpoints) -> dict:
    # dave trusts alice, but the invitation names bob as the responder.
    outcome = e.call('alice', 'bob', lifetime=5, answerer='dave')
    d = outcome['responder']
    require(d.get('exit') == 2 and 'invitation: untrusted' in d.get('error', ''),
            f'responder {d}')
    require(outcome['initiator'].get('result') == 'expired', f"initiator {outcome['initiator']}")
    return outcome


def unverified_and_ungranted(e: Endpoints) -> dict:
    result = e.private('unverified.json')
    e.run('dave', 'contact', 'add', e.codes['bob'], 'bob')
    code, _, error = e.run('dave', 'call', '--peer', e.codes['bob'], '--invitation-out',
                           str(e.private('unused')), '--result', str(result),
                           *e.connection('dave'), check=False)
    require(code == 2 and 'create: untrusted' in error, f'unverified peer: {code} {error}')
    e.create('carol')
    e.run('carol', 'contact', 'add', e.codes['alice'], 'alice')
    e.run('carol', 'contact', 'verify', e.codes['alice'], e.fingerprints['alice'])
    code, _, carol_error = e.run('carol', 'call', '--peer', e.codes['alice'],
                                 '--invitation-out', str(e.private('unused')),
                                 '--result', str(e.private('carol.json')),
                                 *e.connection('carol'), check=False)
    require(code == 2 and 'open failed: -8' in carol_error,
            f'ungranted client: {code} {carol_error}')
    return {'unverified': error, 'ungranted': carol_error}


def consent_timeout(e: Endpoints) -> dict:
    outcome = e.call('alice', 'bob', responder_args=['--no-decision'])
    a, b = outcome['initiator'], outcome['responder']
    require(b.get('result') == 'timeout' and b.get('local_end') == 3, f'responder {b}')
    require(a.get('result') == 'peer_ended' and a.get('remote_end') == 3 and
            not a.get('remote_end_attributed'), f'initiator {a}')
    require(25_000 <= b['milliseconds'] <= 45_000, f'responder waited {b["milliseconds"]} ms')
    return {'initiator': a, 'responder': b}


def authority_loss(e: Endpoints) -> dict:
    to_dave, to_alice = os.urandom(32 * 1024), os.urandom(32 * 1024)
    alice_args, _ = payload_args(e, 'alice-hold', to_dave, len(to_alice))
    dave_args, _ = payload_args(e, 'dave-hold', to_alice, len(to_dave))

    def revoke() -> None:
        time.sleep(6.0)
        e.h.setup('remove-client', '--server', str(e.h.kit / 'server'), '--client-name', 'dave')
        os.kill(e.h.yumed.pid, signal.SIGHUP)

    outcome = e.call('alice', 'dave', initiator_args=alice_args + ['--hold-ms', '20000'],
                     responder_args=dave_args, during=revoke)
    a, d = outcome['initiator'], outcome['responder']
    require(d.get('result') == 'transport_failed', f'revoked responder {d}')
    require(a.get('result') in ('transport_failed', 'peer_ended'), f'initiator {a}')
    require(d['data_received'] == len(to_dave), 'the call carried data before revocation')
    return {'initiator': a, 'responder': d}


def key_replaced(e: Endpoints) -> dict:
    # bob learns that alice's key changed, and has not verified the new one.
    e.create('alice2')
    e.run('bob', 'contact', 'replace', e.codes['alice'], e.codes['alice2'])
    outcome = e.call('alice', 'bob', lifetime=5)
    b = outcome['responder']
    require(b.get('exit') == 2 and 'invitation: untrusted' in b.get('error', ''),
            f'responder {b}')
    require(outcome['initiator'].get('result') == 'expired', f"initiator {outcome['initiator']}")
    return outcome


SCENARIOS = [
    ('profile1_call', profile1_call),
    ('tap_blindness', tap_blindness),
    ('decline', decline),
    ('replayed_invitation', replayed_invitation),
    ('wrong_responder', wrong_responder),
    ('unverified_and_ungranted', unverified_and_ungranted),
    ('consent_timeout', consent_timeout),
    ('authority_loss', authority_loss),
    ('key_replaced', key_replaced),
]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('relay', 'yumed', 'setup', 'libyume', 'resolver', 'ito-call'):
        parser.add_argument(f'--{name}', type=Path, required=True)
    parser.add_argument('--ito-call-evidence', type=Path)
    parser.add_argument('--only', action='append', default=[])
    parser.add_argument('--result', type=Path)
    arguments = parser.parse_args()
    unknown = sorted(set(arguments.only) - {name for name, _ in SCENARIOS})
    if unknown:
        parser.error(f'unknown scenario: {", ".join(unknown)}')
    for name in ('relay', 'yumed', 'setup', 'libyume', 'resolver', 'ito_call',
                 'ito_call_evidence'):
        path = getattr(arguments, name)
        if path is not None:
            setattr(arguments, name, path.resolve(strict=True))
    os.umask(0o077)
    results = []
    selected = [entry for entry in SCENARIOS if not arguments.only or entry[0] in arguments.only]
    with tempfile.TemporaryDirectory(prefix='ito-call-', dir='/tmp') as temporary:
        root = Path(temporary)
        harness = Harness(arguments, root)
        tap = None
        try:
            harness.provision()
            # yumed reaches the relay through the tap for the whole run.
            tap = Tap(root / 'module', harness.relay.path)
            server_path = harness.kit / 'server/yumed.json'
            server = json_file(server_path)
            for adapter in server['adapters']:
                if adapter.get('kind') == 'module':
                    adapter['socket'] = str(tap.path)
            server_path.write_text(json.dumps(server, indent=2), encoding='utf-8')
            harness.start_yumed()
            endpoints = Endpoints(harness, arguments, root)
            endpoints.tap = tap
            for name in ('alice', 'bob', 'dave'):
                endpoints.create(name)
            endpoints.befriend('alice', 'bob')
            endpoints.befriend('alice', 'dave')
            for name, scenario in selected:
                started = time.monotonic()
                entry = {'scenario': name}
                try:
                    entry['details'] = scenario(endpoints)
                    entry['passed'] = True
                except (Failure, OSError, ValueError, KeyError, subprocess.SubprocessError) as error:
                    entry['passed'] = False
                    entry['error'] = f'{type(error).__name__}: {error}'
                    entry['trace'] = traceback.format_exc(limit=4)
                entry['seconds'] = round(time.monotonic() - started, 2)
                print(f"{'PASS' if entry['passed'] else 'FAIL'} {name} ({entry['seconds']} s)"
                      + ('' if entry['passed'] else f": {entry['error']}"), flush=True)
                results.append(entry)
                if not entry['passed']:
                    break
        finally:
            harness.close()
            if tap is not None:
                tap.close()
    binaries = {name: hashlib.sha256(getattr(arguments, name).read_bytes()).hexdigest()
                for name in ('relay', 'yumed', 'setup', 'libyume', 'resolver', 'ito_call',
                             'ito_call_evidence') if getattr(arguments, name) is not None}
    summary = {'schema': 1, 'binaries_sha256': binaries, 'scenarios': results,
               'expected': [name for name, _ in selected],
               'passed': len(results) == len(selected) and all(r['passed'] for r in results)}
    if arguments.result:
        arguments.result.write_text(json.dumps(summary, indent=2) + '\n', encoding='utf-8')
    return 0 if summary['passed'] else 1


if __name__ == '__main__':
    try:
        sys.exit(main())
    except Failure as failure:
        print(f'call harness: {failure}', file=sys.stderr)
        sys.exit(1)
