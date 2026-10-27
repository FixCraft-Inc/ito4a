# Copyright (C) 2026 FixCraft Inc. AGPL-3.0-or-later.
"""Shared test helpers: the relay process, version-1 frames and module peers.

The component test plays yumed itself, writing module headers over the relay's
socket. The yumed harness uses only the frame helpers and the relay process.
"""
from __future__ import annotations

import hashlib
import json
import os
import select
import signal
import socket
import struct
import threading
import time
from pathlib import Path
from typing import Callable

SERVICE = 'ito-call'
WAITING = b'\0\0\0\x01\x04'
PAIRED = b'\0\0\0\x01\x05'
CANCEL = b'\0\0\0\x01\x06'
RESERVED_BYTES = 105
QUEUE_BYTES = 64 * 1024
FORWARD_CAP = 65 * 1024 * 1024


class Failure(Exception):
    """An expectation of the relay contract did not hold."""


def require(condition: bool, message: str) -> None:
    if not condition:
        raise Failure(message)


def refused(reason: int) -> bytes:
    return b'\0\0\0\x02\x07' + bytes([reason])


def frame(body: bytes) -> bytes:
    return struct.pack('>I', len(body)) + body


def reserve(lifetime: int = 60_000, version: int = 1) -> bytes:
    return frame(bytes([1]) + struct.pack('>HI', version, lifetime))


def join(room: bytes, role: int, token: bytes, version: int = 1) -> bytes:
    return frame(bytes([3]) + struct.pack('>H', version) + room + bytes([role]) + token)


def header(principal: str, service: str = SERVICE) -> bytes:
    return f'yume-module 1 {principal} {service}\n'.encode()


def pattern(seed: bytes, size: int) -> bytes:
    """Deterministic incompressible bytes, independent of the relay."""
    out = bytearray()
    counter = 0
    while len(out) < size:
        out += hashlib.sha256(seed + counter.to_bytes(8, 'big')).digest() * 64
        counter += 1
    return bytes(out[:size])


class Reservation:
    def __init__(self, reply: bytes, lifetime: int) -> None:
        require(len(reply) == RESERVED_BYTES, f'RESERVED has {len(reply)} bytes')
        require(reply[:5] == b'\0\0\0\x65\x02', f'not a RESERVED frame: {reply[:5].hex()}')
        self.room = reply[5:37]
        self.initiator_token = reply[37:69]
        self.responder_token = reply[69:101]
        granted = struct.unpack('>I', reply[101:105])[0]
        require(granted == lifetime, f'granted {granted} ms for {lifetime} ms')
        require(len({self.room, self.initiator_token, self.responder_token}) == 3,
                'room ID and tokens are not independent')


class Stream:
    """One byte stream to the relay: a module connection or a libyume stream."""

    def send(self, data: bytes) -> None:
        raise NotImplementedError

    def receive(self, limit: int, timeout: float) -> bytes | None:
        """Up to limit bytes, b'' at end of stream, None on timeout."""
        raise NotImplementedError

    def close(self) -> None:
        raise NotImplementedError

    def expect(self, expected: bytes, what: str, timeout: float = 5.0) -> None:
        try:
            got = self.read_exact(len(expected), timeout)
        except Failure as failure:
            raise Failure(f'{what}: {failure}') from None
        require(got == expected, f'{what}: expected {expected[:16].hex()}, got {got[:16].hex()}')

    def read_exact(self, count: int, timeout: float = 5.0) -> bytes:
        deadline = time.monotonic() + timeout
        out = bytearray()
        while len(out) < count:
            left = deadline - time.monotonic()
            require(left > 0, f'timed out after {len(out)} of {count} bytes')
            chunk = self.receive(count - len(out), left)
            require(chunk is not None, f'timed out after {len(out)} of {count} bytes')
            require(chunk != b'', f'end of stream after {len(out)} of {count} bytes')
            out += chunk
        return bytes(out)

    def read_until_end(self, timeout: float, limit: int = 1 << 30) -> tuple[bytes, bool]:
        """Bytes until end of stream or the deadline, and whether it ended."""
        deadline = time.monotonic() + timeout
        out = bytearray()
        while len(out) < limit:
            left = deadline - time.monotonic()
            if left <= 0:
                return bytes(out), False
            chunk = self.receive(min(1 << 20, limit - len(out)), left)
            if chunk is None:
                return bytes(out), False
            if chunk == b'':
                return bytes(out), True
            out += chunk
        return bytes(out), False

    def expect_end(self, what: str, timeout: float = 3.0) -> None:
        data, ended = self.read_until_end(timeout)
        require(ended, f'{what}: the stream stayed open')
        require(data == b'', f'{what}: {len(data)} unexpected bytes before the end')

    def expect_refused(self, reason: int, what: str, timeout: float = 5.0) -> None:
        self.expect(refused(reason), what, timeout)
        self.expect_end(f'{what}, after REFUSED')

    def reserve(self, lifetime: int = 60_000) -> Reservation:
        self.send(reserve(lifetime))
        return Reservation(self.read_exact(RESERVED_BYTES), lifetime)


def exchange(left: 'Stream', right: 'Stream', size: int, seed: bytes) -> dict:
    """Sends size bytes each way at once and compares them exactly."""
    outgoing = {'left': pattern(seed + b'/left', size), 'right': pattern(seed + b'/right', size)}
    received: dict[str, bytes] = {}
    errors: list[BaseException] = []

    def run(name: str, sender: 'Stream', receiver: 'Stream', other: str) -> None:
        try:
            writer = threading.Thread(target=sender.send, args=(outgoing[name],))
            writer.start()
            received[other] = receiver.read_exact(size, 30.0)
            writer.join(30.0)
        except BaseException as error:  # recorded and raised below
            errors.append(error)

    threads = [threading.Thread(target=run, args=('left', left, right, 'right')),
               threading.Thread(target=run, args=('right', right, left, 'left'))]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join(60.0)
    if errors:
        raise errors[0]
    require(received['right'] == outgoing['left'], 'left to right bytes differ')
    require(received['left'] == outgoing['right'], 'right to left bytes differ')
    return {'bytes_each_way': size,
            'left_sha256': hashlib.sha256(outgoing['left']).hexdigest(),
            'right_sha256': hashlib.sha256(outgoing['right']).hexdigest()}



class ModulePeer(Stream):
    """A synthetic yumed connection, trusted because it runs as the daemon user."""

    def __init__(self, path: Path, principal: str | None, service: str = SERVICE) -> None:
        self.socket = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        # One fixed timeout, so a receive on one thread never changes how a
        # send on another waits. Receives wait in poll instead.
        self.socket.settimeout(60.0)
        self.socket.connect(str(path))
        if principal is not None:
            self.socket.sendall(header(principal, service))

    def send(self, data: bytes) -> None:
        self.socket.sendall(data)

    def receive(self, limit: int, timeout: float) -> bytes | None:
        waiter = select.poll()
        waiter.register(self.socket.fileno(), select.POLLIN | select.POLLHUP | select.POLLERR)
        if not waiter.poll(max(int(timeout * 1000), 1)):
            return None
        try:
            return self.socket.recv(limit)
        except (BlockingIOError, socket.timeout):
            return None
        except (ConnectionResetError, BrokenPipeError):
            return b''

    def shutdown_write(self) -> None:
        self.socket.shutdown(socket.SHUT_WR)

    def close(self) -> None:
        self.socket.close()


class RelayProcess:
    """ito-call-relay serving a listener this object holds, as a socket unit does.

    The listener outlives the program, so a restart reuses it and connections
    made while no program runs wait in its queue.
    """

    def __init__(self, program: Path, directory: Path, name: str = 'relay') -> None:
        self.program = program
        self.directory = directory
        self.path = directory / f'{name}.sock'
        self.log_path = directory / f'{name}.log'
        self.listener = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        self.listener.bind(str(self.path))
        os.chmod(self.path, 0o600)
        self.listener.listen(4096)
        self.pid: int | None = None
        self.exit_code: int | None = None

    def spawn(self, arguments: list[str] | None = None, listener: socket.socket | None = None,
              environment: dict[str, str] | None = None, prefix: list[str] | None = None,
              pass_listener: bool = True) -> int:
        chosen = self.listener if listener is None else listener
        descriptor = chosen.fileno()
        # dup2 onto itself would keep close-on-exec.
        duplicate = os.dup(descriptor) if descriptor == 3 else None
        source = duplicate if duplicate is not None else descriptor
        log = os.open(self.log_path, os.O_WRONLY | os.O_APPEND | os.O_CREAT, 0o600)
        null = os.open(os.devnull, os.O_RDONLY)
        try:
            actions = [(os.POSIX_SPAWN_DUP2, null, 0), (os.POSIX_SPAWN_DUP2, log, 1),
                       (os.POSIX_SPAWN_DUP2, log, 2)]
            if pass_listener:
                actions.append((os.POSIX_SPAWN_DUP2, source, 3))
            argv = (prefix or []) + [str(self.program)] + (
                arguments if arguments is not None
                else ['--service', SERVICE, '--daemon-uid', str(os.getuid())])
            env = {'PATH': '/usr/bin:/bin'} if environment is None else environment
            self.pid = os.posix_spawn(argv[0], argv, env, file_actions=actions)
            self.exit_code = None
            return self.pid
        finally:
            os.close(log)
            os.close(null)
            if duplicate is not None:
                os.close(duplicate)

    def lines(self) -> list[dict]:
        if not self.log_path.exists():
            return []
        # The relay may be writing a line while this reads. Only lines it
        # finished, with their newline, are parsed.
        complete = self.log_path.read_bytes().rpartition(b'\n')[0]
        return [json.loads(raw) for raw in complete.splitlines() if raw]

    def start(self, timeout: float = 5.0) -> None:
        ready_before = sum(1 for line in self.lines() if line.get('ito_call_relay') == 'ready')
        self.spawn()
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            ready = sum(1 for line in self.lines() if line.get('ito_call_relay') == 'ready')
            if ready > ready_before:
                return
            if self.poll() is not None:
                raise Failure(f'the relay exited with {self.exit_code} before it was ready')
            time.sleep(0.01)
        raise Failure('the relay did not report ready')

    def poll(self) -> int | None:
        if self.pid is None or self.exit_code is not None:
            return self.exit_code
        pid, status = os.waitpid(self.pid, os.WNOHANG)
        if pid == 0:
            return None
        self.exit_code = os.waitstatus_to_exitcode(status)
        return self.exit_code

    def wait(self, timeout: float) -> int:
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            code = self.poll()
            if code is not None:
                return code
            time.sleep(0.01)
        raise Failure('the relay did not exit in time')

    def stop(self) -> int:
        if self.poll() is None:
            os.kill(self.pid, signal.SIGTERM)
        return self.wait(10.0)

    def kill(self) -> None:
        if self.poll() is None:
            os.kill(self.pid, signal.SIGKILL)
            self.wait(10.0)

    def counts(self, timeout: float = 3.0) -> dict:
        before = sum(1 for line in self.lines() if line.get('ito_call_relay') == 'counts')
        os.kill(self.pid, signal.SIGUSR1)
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            counts = [line for line in self.lines() if line.get('ito_call_relay') == 'counts']
            if len(counts) > before:
                return counts[-1]
            time.sleep(0.01)
        raise Failure('the relay did not report its counts')

    def wait_for(self, condition: Callable[[dict], bool], what: str, timeout: float = 5.0) -> dict:
        deadline = time.monotonic() + timeout
        counts = self.counts()
        while not condition(counts):
            require(time.monotonic() < deadline, f'{what}: counts stayed {counts}')
            time.sleep(0.2)
            counts = self.counts()
        return counts

    def require_clean(self, what: str, timeout: float = 5.0) -> dict:
        """Every stream, room and queued byte released, each room closed once."""
        counts = self.wait_for(
            lambda c: c['streams'] == 0 and c['rooms'] == 0 and c['queued_bytes'] == 0,
            f'{what}: cleanup', timeout)
        require(counts['closed_rooms'] == counts['reservations'],
                f'{what}: {counts["reservations"]} rooms reserved, {counts["closed_rooms"]} closed')
        require(counts['paired_rooms'] == 0, f'{what}: paired rooms remain')
        return counts

    def close(self) -> None:
        try:
            self.kill()
        finally:
            self.listener.close()
