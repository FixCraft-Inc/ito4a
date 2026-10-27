# ito-call-relay

`ito-call-relay` is the opaque two-party relay of [CALL_RELAY.md](../docs/CALL_RELAY.md),
built as a YUME module. yumed authenticates each client and checks its
grant for the configured stream service, then hands the stream to this
program over a private UNIX socket. The relay reserves rooms, admits one
stream per role with one-use tokens, and forwards bytes between the two
paired streams without reading them. It holds no invitation PSK, contact
key or media secret. Forwarding opaque bytes does not by itself make a call
or provide end-to-end encryption. That is the endpoint's job, described in
[CALL_SECURITY.md](../docs/CALL_SECURITY.md).

The program is separate from the Android application and its Gradle build.
It needs Linux, a C++20 compiler and CMake 3.25 or later, and links only the
C++ standard library. The tests need Python 3.11 or later.

## Build and test

```sh
cmake -S relay -B build/relay -G Ninja -DCMAKE_BUILD_TYPE=RelWithDebInfo
cmake --build build/relay
ctest --test-dir build/relay --output-on-failure
```

Warnings are errors by default. `-DITO_RELAY_SANITIZE=address,undefined`
builds every target with those sanitizers. `-DITO_RELAY_TESTS=OFF` builds
only the program.

## Run it with yumed

Declare the stream service on the server and give it a `module` adapter
that names the relay's socket. YUME's [module guide](https://github.com/FixCraft-Inc/yume/blob/main/docs/MODULES.md)
owns the adapter, socket and unit contract.

```json
{
  "services": [
    {"name": "ito-call", "kind": "stream", "max_concurrent_streams": 8}
  ],
  "adapters": [
    {"kind": "module", "service": "ito-call", "socket": "/run/yume-modules/ito-call.sock"}
  ]
}
```

Grant the service to each identity allowed to call through this server, as
`{"service": "ito-call", "kind": "stream"}` in its authorized-keys entry.
yumed refuses every other identity before the relay sees a byte. An
application flag, an account token or a fingerprint sent by a client grants
nothing here.

With the YUME Debian package, install the program and point the module
template at it with a drop-in. `--daemon-uid` is the numeric user yumed runs
as. The relay accepts connections only from that user and root, and never
assumes its own dynamic user is the daemon's.

```sh
sudo install -D -o root -g root -m 0755 build/relay/ito-call-relay \
  /usr/libexec/ito/ito-call-relay
sudo systemctl edit yume-module@ito-call.service
```

```ini
[Service]
ExecStart=
ExecStart=/usr/libexec/ito/ito-call-relay --service ito-call --daemon-uid 999
```

Replace `999` with the output of `id -u yume`. Then enable the socket and
restart the daemon:

```sh
sudo systemctl enable --now yume-module@ito-call.socket
sudo systemctl restart yumed
```

The module service needs no network. Its default account name,
`yume-module-ito-call`, fits the dynamic-user limits.

For development as one user, a private directory and
`systemd-socket-activate` give the same descriptor:

```sh
mkdir -m 0700 -p "$XDG_RUNTIME_DIR/ito"
(umask 077 && systemd-socket-activate -l "$XDG_RUNTIME_DIR/ito/ito-call.sock" \
  build/relay/ito-call-relay --service ito-call --daemon-uid "$(id -u)")
```

## What the program checks and reports

At startup the relay refuses to run as root and checks descriptor 3. It
must be a listening UNIX stream socket with a filesystem path. The socket
file must belong to the daemon user with no group or other permission, and
its directory must be a real directory owned by root or that user that
others cannot write. When systemd's `LISTEN_FDS` is set, it must pass
exactly one descriptor to this process. The relay also raises its open-file
limit to hold every stream, and fails when the hard limit is too low.

Each connection must come from the daemon user or root according to
`SO_PEERCRED`. The relay closes any other connection before reading it.

| Option | Meaning |
| --- | --- |
| `--service NAME` | The service name yumed writes in each module header. It uses YUME's service-name grammar, at most 64 bytes. |
| `--daemon-uid UID` | The numeric user yumed runs as. |
| `--help`, `--version` | Print usage or the version. |

Exit status is 0 after SIGTERM or SIGINT, 1 when the relay cannot start or
its event loop fails, and 2 for a usage error. Standard error carries one
JSON object per line: `ready`, `stopped` or `failed` with a fixed reason,
and the aggregate counts that SIGUSR1 requests and a stop prints. Counts
hold numbers only, such as open streams, rooms, queued bytes and refusals by
reason. The relay never logs a principal, token, control body or forwarded
byte.

The version-1 limits in the contract are fixed, not configurable. A crash or
restart ends every call and drops every room and token, while the listening
socket stays open so streams opened meanwhile wait for the next run.

## Test suites

- `ito_relay_wire_test` checks the module header and control-frame grammar
  against the contract's byte layout.
- `ito_relay_room_table_test` checks rooms, tokens, quotas, rate windows and
  retention with virtual time and the version-1 limits.
- `ito_relay_loop_test` runs the event loop in process with lowered limits,
  for the 30-minute lifetime, the byte cap, stalls and a failing random
  source.
- `component_test.py` runs the real program with the default limits against
  peers that write module headers as yumed would. Its `peer_credentials`
  case needs `unshare`, `setpriv` and `newuidmap` with subordinate IDs, to run
  the relay, the daemon and an untrusted client as three different users.
- `yumed_harness.py` runs the program behind a real yumed with four
  identities provisioned by `yume-setup` and clients that use the public
  libyume C ABI. It needs those YUME programs and the shared library:

```sh
python3 -B relay/tests/yumed_harness.py --relay build/relay/ito-call-relay \
  --yumed "$YUME_BUILD/bin/yumed" --setup "$YUME_BUILD/bin/yume-setup" \
  --libyume "$YUME_BUILD/lib/libyume.so" --resolver "$YUME_BUILD/bin/yume-resolver"
```
