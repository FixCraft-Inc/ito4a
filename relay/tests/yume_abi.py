# Copyright (C) 2026 FixCraft Inc. AGPL-3.0-or-later.
"""The few public libyume C ABI calls the yumed harness needs, through ctypes.

Layouts follow include/yume/yume.h of the YUME tree the harness is bound to.
Only public declarations are used. Every status is checked by its caller.
"""
from __future__ import annotations

import ctypes
import json
import time
from pathlib import Path

from relay_peers import Failure, Stream, require

ABI_VERSION = 1
STATUS_OK = 0
STATUS_EOF = 1
STATUS_TIMEOUT = -5
STATUS_WOULD_BLOCK = -6
STATUS_PERMISSION_DENIED = -8
STATUS_CLOSED = -12
SERVICE_BYTE_STREAM = 1
DESTINATION_HOSTNAME = 1
ENDPOINT_RUNNING = 3
MAX_WRITE = 256 * 1024

c_size_t = ctypes.c_size_t
c_uint32 = ctypes.c_uint32
c_int32 = ctypes.c_int32
c_void_p = ctypes.c_void_p


class StringView(ctypes.Structure):
    _fields_ = [('data', ctypes.c_char_p), ('size', c_size_t)]


class RuntimeOptions(ctypes.Structure):
    _fields_ = [('struct_size', c_size_t), ('abi_version', c_uint32),
                ('max_pending_callbacks', c_uint32), ('event_callback', c_void_p),
                ('callback_user_data', c_void_p), ('config_base_dir', ctypes.c_char_p),
                ('resolver_program', ctypes.c_char_p)]


class Destination(ctypes.Structure):
    _fields_ = [('struct_size', c_size_t), ('abi_version', c_uint32), ('kind', c_uint32),
                ('reserved', c_uint32), ('host', StringView), ('port', ctypes.c_uint16),
                ('reserved2', ctypes.c_uint16)]


class OpenOptions(ctypes.Structure):
    _fields_ = [('struct_size', c_size_t), ('abi_version', c_uint32), ('service', StringView),
                ('kind', c_uint32), ('reserved', c_uint32), ('destination', Destination)]


class Diagnostic(ctypes.Structure):
    _fields_ = [('struct_size', c_size_t), ('abi_version', c_uint32), ('status', c_int32),
                ('flags', c_uint32), ('json_pointer', ctypes.c_char * 256),
                ('message', ctypes.c_char * 512)]


# The offsets the header fixes with offsetof, on LP64 Linux.
EXPECTED_LAYOUT = {
    'runtime_options': (ctypes.sizeof(RuntimeOptions), 48),
    'open_options_min': (OpenOptions.destination.offset, 40),
    'destination': (ctypes.sizeof(Destination), 48),
    'diagnostic': (ctypes.sizeof(Diagnostic), 792),
}


class Library:
    def __init__(self, path: Path) -> None:
        for name, (actual, expected) in EXPECTED_LAYOUT.items():
            require(actual == expected, f'ctypes layout {name}: {actual} != {expected}')
        self.lib = ctypes.CDLL(str(path))
        lib = self.lib
        lib.yume_abi_version.restype = c_uint32
        require(lib.yume_abi_version() == ABI_VERSION, 'libyume ABI version')
        signatures = {
            'yume_runtime_create': [ctypes.POINTER(RuntimeOptions), ctypes.POINTER(c_void_p)],
            'yume_config_parse_json': [c_void_p, ctypes.c_char_p, c_size_t,
                                       ctypes.POINTER(c_void_p)],
            'yume_endpoint_create': [c_void_p, c_void_p, ctypes.POINTER(c_void_p)],
            'yume_endpoint_start': [c_void_p, c_uint32],
            'yume_endpoint_stop': [c_void_p, c_uint32],
            'yume_endpoint_open_stream': [c_void_p, ctypes.POINTER(OpenOptions), c_uint32,
                                          ctypes.POINTER(c_void_p)],
            'yume_stream_read': [c_void_p, c_void_p, c_size_t, ctypes.POINTER(c_size_t), c_uint32],
            'yume_stream_write': [c_void_p, ctypes.c_char_p, c_size_t, ctypes.POINTER(c_size_t),
                                  c_uint32],
            'yume_stream_shutdown_write': [c_void_p, c_uint32],
            'yume_stream_close': [c_void_p, c_uint32],
            'yume_handle_get_diagnostic': [c_void_p, ctypes.POINTER(Diagnostic), c_size_t],
        }
        for name, arguments in signatures.items():
            function = getattr(lib, name)
            function.argtypes = arguments
            function.restype = c_int32
        for name in ('yume_runtime_destroy', 'yume_config_destroy', 'yume_endpoint_destroy',
                     'yume_stream_destroy'):
            function = getattr(lib, name)
            function.argtypes = [c_void_p]
            function.restype = None
        lib.yume_endpoint_state.argtypes = [c_void_p]
        lib.yume_endpoint_state.restype = c_uint32

    def diagnostic(self, handle: int) -> str:
        out = Diagnostic()
        out.struct_size = ctypes.sizeof(Diagnostic)
        out.abi_version = ABI_VERSION
        if self.lib.yume_handle_get_diagnostic(handle, ctypes.byref(out),
                                               ctypes.sizeof(out)) != STATUS_OK:
            return ''
        return out.message.decode('utf-8', 'replace')


class Client:
    """One libyume client endpoint and its runtime, for one identity."""

    def __init__(self, library: Library, directory: Path, resolver: Path) -> None:
        self.library = library
        lib = library.lib
        self.directory = str(directory).encode()
        self.resolver = str(resolver).encode()
        options = RuntimeOptions()
        options.struct_size = ctypes.sizeof(RuntimeOptions)
        options.abi_version = ABI_VERSION
        options.config_base_dir = self.directory
        options.resolver_program = self.resolver
        self.runtime = c_void_p()
        status = lib.yume_runtime_create(ctypes.byref(options), ctypes.byref(self.runtime))
        require(status == STATUS_OK, f'runtime create {status}')
        document = (directory / 'yume.json').read_bytes()
        config = c_void_p()
        status = lib.yume_config_parse_json(self.runtime, document, len(document),
                                            ctypes.byref(config))
        require(status == STATUS_OK,
                f'client config {status}: {library.diagnostic(self.runtime.value)}')
        self.endpoint = c_void_p()
        status = lib.yume_endpoint_create(self.runtime, config, ctypes.byref(self.endpoint))
        lib.yume_config_destroy(config)
        require(status == STATUS_OK, f'endpoint create {status}')

    def start(self, timeout_ms: int = 30_000) -> int:
        return self.library.lib.yume_endpoint_start(self.endpoint, timeout_ms)

    def stop(self) -> int:
        return self.library.lib.yume_endpoint_stop(self.endpoint, 0)

    def running(self) -> bool:
        return self.library.lib.yume_endpoint_state(self.endpoint) == ENDPOINT_RUNNING

    def open(self, service: str, timeout_ms: int = 20_000,
             destination: tuple[str, int] | None = None) -> tuple[int, 'AbiStream | None']:
        name = service.encode()
        options = OpenOptions()
        options.abi_version = ABI_VERSION
        options.service = StringView(name, len(name))
        options.kind = SERVICE_BYTE_STREAM
        host = b''
        if destination is None:
            # The shorter prefix declares that no destination follows.
            options.struct_size = OpenOptions.destination.offset
        else:
            host = destination[0].encode()
            options.struct_size = ctypes.sizeof(OpenOptions)
            options.destination.struct_size = ctypes.sizeof(Destination)
            options.destination.abi_version = ABI_VERSION
            options.destination.kind = DESTINATION_HOSTNAME
            options.destination.host = StringView(host, len(host))
            options.destination.port = destination[1]
        stream = c_void_p()
        status = self.library.lib.yume_endpoint_open_stream(
            self.endpoint, ctypes.byref(options), timeout_ms, ctypes.byref(stream))
        if status != STATUS_OK:
            return status, None
        return status, AbiStream(self.library, stream)

    def destroy(self) -> None:
        lib = self.library.lib
        if self.endpoint:
            lib.yume_endpoint_stop(self.endpoint, 0)
            lib.yume_endpoint_destroy(self.endpoint)
            self.endpoint = c_void_p()
        if self.runtime:
            lib.yume_runtime_destroy(self.runtime)
            self.runtime = c_void_p()


class AbiStream(Stream):
    """A libyume byte stream, read by one thread and written by one other."""

    def __init__(self, library: Library, handle: c_void_p) -> None:
        self.library = library
        self.handle = handle
        self.last_status = STATUS_OK
        # How the stream ended: EOF after the peer's orderly shutdown, or the
        # error status that ended it.
        self.end_status: int | None = None

    def send(self, data: bytes) -> None:
        lib = self.library.lib
        view = memoryview(data)
        offset = 0
        deadline = time.monotonic() + 60.0
        while offset < len(view):
            chunk = bytes(view[offset:offset + MAX_WRITE])
            written = c_size_t()
            status = lib.yume_stream_write(self.handle, chunk, len(chunk), ctypes.byref(written),
                                           1_000)
            if status in (STATUS_TIMEOUT, STATUS_WOULD_BLOCK):
                if time.monotonic() > deadline:
                    raise TimeoutError('libyume write made no progress')
                continue
            if status != STATUS_OK:
                self.last_status = status
                raise BrokenPipeError(f'libyume write {status}')
            require(written.value == len(chunk), 'libyume admits writes all or none')
            offset += len(chunk)

    def try_send(self, data: bytes, timeout_ms: int) -> int:
        """One write attempt: its status, without raising."""
        written = c_size_t()
        return self.library.lib.yume_stream_write(self.handle, data, len(data),
                                                  ctypes.byref(written), timeout_ms)

    def receive(self, limit: int, timeout: float) -> bytes | None:
        buffer = ctypes.create_string_buffer(min(limit, 1 << 20))
        got = c_size_t()
        status = self.library.lib.yume_stream_read(self.handle, buffer, len(buffer),
                                                   ctypes.byref(got),
                                                   max(int(timeout * 1000), 1))
        self.last_status = status
        if status == STATUS_OK:
            return buffer.raw[:got.value]
        if status in (STATUS_TIMEOUT, STATUS_WOULD_BLOCK):
            return None
        # EOF after the peer shut down its side, or CLOSED when the stream or
        # its session ended. Callers that need to tell them apart read
        # end_status.
        self.end_status = status
        return b''

    def shutdown_write(self) -> None:
        status = self.library.lib.yume_stream_shutdown_write(self.handle, 5_000)
        require(status == STATUS_OK, f'libyume shutdown {status}')

    def close(self) -> None:
        if self.handle:
            self.library.lib.yume_stream_close(self.handle, 0)
            self.library.lib.yume_stream_destroy(self.handle)
            self.handle = c_void_p()


def json_file(path: Path) -> dict:
    return json.loads(path.read_text(encoding='utf-8'))
